package it.dogior.hadEnough.extractor

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class StreamCenterVidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = false

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"

        private val HTML_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "DNT" to "1",
        )

        private val M3U8_HEADERS = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to "https://v.vidxgo.co",
            "Referer" to "https://v.vidxgo.co/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "DNT" to "1",
        )

        private fun extractM3u8FromHtml(html: String): String? {
            val scripts = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
            val encodedValue = Regex("""atob\(\s*['\"]([^'\"]+)['\"]\s*\)""")
            val keyValue = Regex("""var\s+\w+\s*=\s*['\"]([^'\"]+)['\"]""")

            for (script in scripts) {
                val encoded = encodedValue.find(script)?.groupValues?.getOrNull(1) ?: continue
                val keys = keyValue.findAll(script).map { it.groupValues[1] }
                for (key in keys) {
                    if (key.isBlank()) continue
                    val decrypted = runCatching {
                        Base64.decode(encoded, Base64.DEFAULT)
                            .mapIndexed { index, value ->
                                (value.toInt() xor key[index % key.length].code).toByte()
                            }
                            .toByteArray()
                            .toString(Charsets.UTF_8)
                    }.getOrNull() ?: continue
                    val url = Regex("""currentSrc\s*=\s*['\"]([^'\"]+)['\"]""")
                        .find(decrypted)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: Regex("""(https?://[^\s'\"]+\.m3u8[^\s'\"]*)""").find(decrypted)
                            ?.groupValues
                            ?.getOrNull(1)
                    if (!url.isNullOrBlank()) return url.replace("\\/", "/").replace("\\", "")
                }
            }
            return null
        }

        private fun extractM3u8FromJson(payload: String): String? {
            return Regex("""['\"]url['\"]\s*:\s*['\"]([^'\"]+)['\"]""")
                .find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\", "")
                ?.takeIf { it.contains(".m3u8", ignoreCase = true) }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val targetUrl = buildTargetUrl(url)
        val sourceBaseUrl = referer
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: mainUrl
        val mediaHeaders = M3U8_HEADERS + mapOf(
            "Origin" to sourceBaseUrl,
            "Referer" to "$sourceBaseUrl/",
        )
        val requestHeaders = HTML_HEADERS + mapOf(
            "Referer" to "$sourceBaseUrl/",
            "Sec-Fetch-Dest" to if (targetUrl.contains("/t/")) "empty" else "iframe",
        )
        val response = app.get(
            targetUrl,
            headers = requestHeaders,
        )
        val m3u8Url = extractM3u8FromJson(response.text) ?: extractM3u8FromHtml(response.text)

        if (m3u8Url != null) {
            runCatching {
                app.get(m3u8Url, headers = mediaHeaders)
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "VidxGo",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.headers = mediaHeaders
                    this.referer = "$sourceBaseUrl/"
                },
            )
        }
    }

    private fun buildTargetUrl(url: String): String {
        if (url.startsWith("https://") || url.startsWith("http://")) {
            return url
        }

        val idRegex = Regex("""(\d+)""")
        val rawId = idRegex.find(url)?.value ?: return url
        val seasonEpisodeRegex = Regex("""(?:tt)?\d+[-/](\d+)[-/](\d+)""")
        val seMatch = seasonEpisodeRegex.find(url)

        return if (seMatch != null) {
            val season = seMatch.groupValues[1]
            val episode = seMatch.groupValues[2]
            "$mainUrl/t/$rawId/$season/$episode"
        } else {
            "$mainUrl/$rawId"
        }
    }
}
