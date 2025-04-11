package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack

class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "User-Agent" to "Mozilla/5.0 (Windows NT 6.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.110 Safari/537.36",
            "Accept-Language" to "en-US;q=0.5,en;q=0.3",
            "Cache-Control" to "max-age=0",
            "Upgrade-Insecure-Requests" to "1"
        )
        val response = app.get(url, headers = headers, timeout = 10_000)
        val responseBody = response.body.string()

        val script =
            "eval(function(p,a,c,k,e,d)" + responseBody.substringAfter("<script type='text/javascript'>eval(function(p,a,c,k,e,d)")
                .substringBefore(")))") + ")))"
        val unpackedScript = getAndUnpack(script)
        val src = unpackedScript.substringAfter("src:\"").substringBefore("\",")
        Log.d("MaxStream", "Script: $src")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = src,
                type = ExtractorLinkType.M3U8
            ){
                this.referer = referer ?: ""
                this.quality = Qualities.Unknown.value
            }
        )
    }
}