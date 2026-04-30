package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class HubVixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "HubVixSrc"
    override val requiresReferer = false
    private var referer: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        this.referer = referer
        callback(
            newExtractorLink(
                source = "VixSrc",
                name = "StreamingCommunity - VixSrc",
                url = getPlaylistLink(url),
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer ?: "https://vixsrc.to/"
            }
        )
    }

    private suspend fun getPlaylistLink(url: String): String {
        val script = getScript(url)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val masterPlaylistParams = masterPlaylist.getJSONObject("params")
        val token = masterPlaylistParams.getString("token")
        val expires = masterPlaylistParams.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")
        val params = "token=$token&expires=$expires"
        val basePlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else {
            "$playlistUrl?$params"
        }

        return if (script.getBoolean("canPlayFHD")) {
            "$basePlaylistUrl&h=1"
        } else {
            basePlaylistUrl
        }
    }

    private suspend fun getScript(url: String): JSONObject {
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

        return JSONObject(getSanitisedScript(script))
    }

    private fun getSanitisedScript(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)
        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()
        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }

        return "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
    }
}
