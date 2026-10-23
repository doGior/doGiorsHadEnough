package it.dogior.hadEnough.extractor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class StreamCenterVixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "StreamCenterVixSrc"
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = ORIGIN_REGEX.find(url)?.value ?: mainUrl
        val playlistUrl = try {
            apiPlaylistUrl(url, origin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: webViewPlaylistUrl(url, origin)

        callback(
            newExtractorLink(
                source = "VixSrc",
                name = "VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to origin,
                    "Referer" to "$origin/",
                )
            },
        )
    }

    private suspend fun apiPlaylistUrl(url: String, origin: String): String? {
        val path = url.removePrefix(origin)
        val api = app.get(
            "$origin/api$path",
            params = mapOf("lang" to "it"),
            headers = mapOf("User-Agent" to userAgent),
        ).text
        val embedPath = JSONObject(api).optString("src").takeIf(String::isNotBlank) ?: return null
        val script = app.get(
            if (embedPath.startsWith("http")) embedPath else "$origin$embedPath",
            referer = "$origin/",
            headers = mapOf("User-Agent" to userAgent),
        ).document
            .select("script")
            .firstOrNull { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: return null
        return StreamCenterVixParser.playlistUrl(StreamCenterVixParser.parseScript(script))
    }

    private suspend fun webViewPlaylistUrl(url: String, origin: String): String {
        val resolver = WebViewResolver(
            interceptUrl = Regex("""playlist.*token"""),
            useOkhttp = true,
            timeout = 15_000L,
        )
        val playlistUrl = app.get(
            url = url,
            referer = origin,
            interceptor = resolver,
            headers = mapOf("User-Agent" to userAgent),
        ).url
        if (!playlistUrl.contains("playlist") || !playlistUrl.contains("token")) {
            error("VixSrc playlist was not intercepted")
        }
        return playlistUrl
    }

    private companion object {
        val ORIGIN_REGEX = Regex("""^https?://[^/]+""")
    }
}
