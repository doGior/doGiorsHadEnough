package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StreamTapeExtractor: ExtractorApi() {
    override val mainUrl = "https://streamtape.com/e/"
    override val name = "StreamTape"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ){
        val newUrl = if (!url.startsWith(mainUrl)) {
            val id = url.split("/").getOrNull(4) ?: return
            mainUrl + id
        } else { url }

        val document = app.get(newUrl).document
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return
        val videoUrl = "https:" + script.substringBefore("'") +
                script.substringAfter("+ ('xcd").substringBefore("'")

        callback.invoke(
            newExtractorLink(
                name,
                name,
                videoUrl
            ) {
                this.referer = referer ?: ""
                this.quality = Qualities.Unknown.value
            }
        )

    }
}