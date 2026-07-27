package it.dogior.hadEnough.extractor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class StreamCenterVixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "StreamCenterVixSrc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val resolver = WebViewResolver(
            interceptUrl = Regex("""playlist.*token"""),
            useOkhttp = true,
            timeout = 15_000L,
        )
        val response = app.get(
            url = url,
            referer = mainUrl,
            interceptor = resolver,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            ),
        )
        val playlistUrl = response.url

        if (!playlistUrl.contains("playlist") || !playlistUrl.contains("token")) {
            error("VixSrc playlist was not intercepted")
        }

        callback(
            newExtractorLink(
                source = "VixSrc",
                name = "StreamingCommunity - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to mainUrl,
                )
            },
        )
    }
}
