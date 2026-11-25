package it.dogior.hadEnough.extractor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class StreamCenterVixCloudExtractor(
    private val sourceName: String = "VixCloud",
    private val displayName: String = "AnimeUnity",
) : ExtractorApi() {
    override val mainUrl = "vixcloud.cc"
    override val name = "StreamCenterVixCloud"
    override val requiresReferer = false
    private val headers = mapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(
            newExtractorLink(
                source = sourceName,
                name = displayName,
                url = getPlaylistLink(url, referer),
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = this@StreamCenterVixCloudExtractor.headers
            }
        )
    }

    private suspend fun getPlaylistLink(url: String, referer: String?): String {
        return StreamCenterVixParser.playlistUrl(getScript(url, referer))
    }

    private suspend fun getScript(url: String, referer: String?): JSONObject {
        val requestHeaders = referer?.takeIf(String::isNotBlank)
            ?.let { headers + ("Referer" to it) }
            ?: headers
        val script = app.get(url, headers = requestHeaders).document
            .select("script")
            .firstOrNull { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: error("Missing VixCloud masterPlaylist script")

        return StreamCenterVixParser.parseScript(script)
    }
}
