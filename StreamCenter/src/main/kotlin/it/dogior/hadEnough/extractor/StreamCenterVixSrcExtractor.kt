package it.dogior.hadEnough.extractor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class StreamCenterVixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "StreamCenterVixSrc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(
            newExtractorLink(
                source = "VixSrc",
                name = "StreamingCommunity - VixSrc",
                url = getPlaylistLink(url, referer),
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer ?: "https://vixsrc.to/"
            }
        )
    }

    private suspend fun getPlaylistLink(url: String, referer: String?): String {
        return StreamCenterVixParser.playlistUrl(getScript(url, referer))
    }

    private suspend fun getScript(url: String, referer: String?): JSONObject {
        val host = url.toHttpUrl().host
        val headers = mapOf(
            "Accept" to "*/*",
            "Alt-Used" to host,
            "Connection" to "keep-alive",
            "Host" to host,
            "Referer" to referer.orEmpty(),
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0",
        )
        val script = app.get(url, headers = headers).document
            .select("script")
            .firstOrNull { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: error("Missing VixSrc masterPlaylist script")

        return StreamCenterVixParser.parseScript(script)
    }
}
