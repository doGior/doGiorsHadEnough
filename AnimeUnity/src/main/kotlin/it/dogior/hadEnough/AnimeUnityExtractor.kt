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

class AnimeUnityExtractor : ExtractorApi() {
    override val mainUrl = AnimeUnity.mainUrl
    override val name = AnimeUnity.name
    override val requiresReferer = false

    override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
//        val TAG = "AnimeUnity:getUrl"
//        Log.d(TAG, "REFERER: $referer  URL: $url")
        val masterPlaylist = getMasterPlaylistUrl(url)
        if (url.isNotEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = "Vixcloud",
                    name = "AnimeUnity",
                    url = masterPlaylist,
                ) {
                    this.referer = referer!!
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun getMasterPlaylistUrl(url: String): String {
        val script = getScript(url)
        val masterPlaylist = script.masterPlaylist

        var masterPlaylistUrl: String
        val params = "token=${masterPlaylist.params.token}&expires=${masterPlaylist.params.expires}"
        masterPlaylistUrl = if ("?b" in masterPlaylist.url) {
            "${masterPlaylist.url.replace("?b:1", "?b=1")}&$params"
        } else{
            "${masterPlaylist.url}?$params"
        }

        if(script.canPlayFHD){
            masterPlaylistUrl += "&h=1"
        }

//        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun getScript(url:String): Script {
        val headers = mapOf(
            "Host" to url.toHttpUrl().host,
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )

        val iframe = app.get(url, headers = headers).document
        val scripts = iframe.select("script")
        val script = scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")

        val scriptJson = getSanitisedScript(script)
//        Log.d(TAG, "Script Json: $scriptJson")

        val scriptObj = parseJson<Script>(scriptJson)
//        Log.d(TAG, "Script Obj: $scriptObj")

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
