package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL
import java.net.URLEncoder

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = ""
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // List of pairs <channel name, link>
        val links = tryParseJson<List<Pair<String, String>>>(url)
        val extractors = links?.map {
            extractVideo(it.second, it.first)
        } ?: listOf(extractVideo(url))

        extractors.forEach {
            if (it != null) {
                callback(it)
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        val headers = mapOf(
            "Referer" to mainUrl,
            "user-agent" to userAgent
        )
        val resp = app.post(url, headers = headers).body.string()
        val url1 = Regex("iframe src=\"([^\"]*)").find(resp)?.groupValues?.get(1)
            ?: return null
        val parsedUrl = URL(url1)
        val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
        val ref = URLEncoder.encode(refererBase, "UTF-8")
        val userAgent = URLEncoder.encode(userAgent, "UTF-8")

        val resp2 = app.post(url1, headers).body.string()


        val streamId = Regex("fetch\\('([^']*)").find(resp2)?.groupValues?.get(1)
            ?: return null
        val url2 = Regex("var channelKey = \"([^\"]*)").find(resp2)?.groupValues?.get(1)
            ?: return null
        val m3u8 = Regex("(/mono\\.m3u8)").find(resp2)?.groupValues?.get(1)
            ?: return null

        val url3 = "$refererBase$streamId$url2"
        val resp3 = app.post(url3, headers).body.string()
        val key =
            Regex(":\"([^\"]*)").find(resp3)?.groupValues?.get(1)
                ?: return null

        val finalLink = "https://$key.newkso.ru/$key/$url2$m3u8"

        return newExtractorLink(
            sourceName,
            sourceName,
            finalLink,
            type = ExtractorLinkType.M3U8,
        ) {
            this.referer = ""
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "Referer" to ref,
                "Origin" to ref,
                "Keep-Alive" to "true",
                "User-Agent" to userAgent
            )
        }
        
    }
}