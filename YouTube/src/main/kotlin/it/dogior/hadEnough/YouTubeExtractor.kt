package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

open class YouTubeExtractor(private val hls: Boolean) : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    constructor() : this(true)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val link =
            YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(
                url.replace(schemaStripRegex, "")
            )

        val extractor = object : YoutubeStreamExtractor(
            ServiceList.YouTube,
            link
        ) {}

        extractor.fetchPage()

        Log.d("YoutubeExtractor", "Is HLS enabled: $hls")
        Log.d("YoutubeExtractor", "HLS Url: ${extractor.hlsUrl}")
        if (hls) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    extractor.hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: ""
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            val stream = M3u8Helper.generateM3u8(this.name, extractor.hlsUrl, "")
            stream.forEach {
                callback.invoke(it)
            }
            val subtitles = try {
                extractor.subtitlesDefault.filterNotNull()
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            subtitles.mapNotNull {
                SubtitleFile(it.languageTag ?: return@mapNotNull null, it.content ?: return@mapNotNull null)
            }.forEach(subtitleCallback)
        }
    }
}