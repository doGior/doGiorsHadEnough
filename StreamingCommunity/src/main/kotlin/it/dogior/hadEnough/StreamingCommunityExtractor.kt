package it.dogior.hadEnough


import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.HttpUrl.Companion.toHttpUrl

class StreamingCommunityExtractor : ExtractorApi() {
    override val mainUrl = StreamingCommunity.mainUrl
    override val name = StreamingCommunity.name
    override val requiresReferer = false

    override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        val TAG = "GetUrl"
        Log.d(TAG,"REFERER: $referer  URL: $url")

        if (url.isNotEmpty()) {
            val response = app.get(url).document
            val iframeSrc = response.select("iframe").attr("src")
            val playlistUrl = getPlaylistLink(iframeSrc)
//            val playlistUrl = getPlaylistLink(url)
            Log.w(TAG, "FINAL URL: $playlistUrl")

            callback.invoke(
                newExtractorLink(
                    source = "Vixcloud",
                    name = "Streaming Community",
                    url = playlistUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer!!
                    this.quality = Qualities.Unknown.value
                }
            )
        }

    }

    private suspend fun getPlaylistLink(url: String): String {
        val TAG = "getPlaylistLink"

        Log.d(TAG, "Item url: $url")

        val script = getScript(url)
        val masterPlaylist = script.masterPlaylist

        var masterPlaylistUrl: String
        val params = "token=${masterPlaylist.params.token}&expires=${masterPlaylist.params.expires}"
        masterPlaylistUrl = if ("?b" in masterPlaylist.url) {
            "${masterPlaylist.url.replace("?b:1", "?b=1")}&$params"
        } else{
            "${masterPlaylist.url}?$params"
        }
        Log.d("getPlaylistLink", "masterPlaylistUrl: ${masterPlaylist.url}")

        if(script.canPlayFHD){
            masterPlaylistUrl += "&h=1"
        }

        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun getScript(url:String): Script {
        Log.d("getScript", "url: $url")
        val headers = mutableMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Host" to url.toHttpUrl().host,
            "Referer" to mainUrl,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
        )

        val iframe = app.get(url, headers = headers).document
        Log.d("getScript", "IFRAME1: $iframe")
        val scripts = iframe.select("script")
        val script = scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")

        val scriptJson = getSanitisedScript(script)
        Log.d("getScript", "Script Json: $scriptJson")

        val scriptObj = parseJson<Script>(scriptJson)
        Log.d("getScript", "Script Obj: $scriptObj")

        return scriptObj
    }

    private fun getSanitisedScript(script: String): String {
        return "{" + script.replace("window.video", "\"video\"")
            .replace("window.streams", "\"streams\"")
            .replace("window.masterPlaylist", "\"masterPlaylist\"")
            .replace("window.canPlayFHD", "\"canPlayFHD\"")
            .replace("params", "\"params\"")
            .replace("url", "\"url\"")
            .replace("\"\"url\"\"", "\"url\"")
            .replace("\"canPlayFHD\"", ",\"canPlayFHD\"")
            .replace(",\t        }", "}")
            .replace(",\t            }", "}")
            .replace("'", "\"")
            .replace(";", ",")
            .replace("=", ":")
            .replace("\\", "")
            .trimIndent() + "}"
    }
}
