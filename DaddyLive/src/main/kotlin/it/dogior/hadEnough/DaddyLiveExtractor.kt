package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.dad"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "Referer" to mainUrl,
        "user-agent" to userAgent
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // List of pairs <channel name, link>
        val links = tryParseJson<List<Pair<String, String>>>(url)
        Log.d("DDLExt - Links", links?.toJson() ?: "null")
        val extractors = links?.map {
            extractVideo(it.second, it.first)
        } ?: listOf(extractVideo(url))

        extractors.forEach {
            if (it != null) {
                callback(it)
            }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        if (!url.contains("dlhd")) return null

        val resp = app.post(url, headers = headers).document
        val iframes = resp.select("iframe")
        val url1 = iframes.attr("src")
        val parsedUrl = URL(url1)
        val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"


        Log.d("DDLExt", url1)
        val finalUrl = (if (url1.contains("vidembed")) extractFromVidembed(url1) else
            extractFromNewzar(url1, refererBase)) ?: return null


        return newExtractorLink(
            sourceName,
            sourceName,
            finalUrl,
            type = ExtractorLinkType.M3U8,
        ) {
            this.referer = "$refererBase/"
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "Origin" to refererBase,
                "Connection" to "Keep-Alive",
                "User-Agent" to userAgent
            )
        }

    }

    private suspend fun extractFromVidembed(urlNextPage: String): String? {
        val vidembedHost = urlNextPage.toHttpUrl().host
        val liveId = urlNextPage.substringAfterLast("/").substringBefore("#")
        val liveUrl = "https://www.$vidembedHost/api/source/$liveId?type=live"
        val requestBody = "{\"r\":\"https://thedaddy.top/\",\"d\":\"www.$vidembedHost\"}"
        val referer = if (urlNextPage.contains("//www.")) urlNextPage
        else {
            "https://www." + urlNextPage.substringAfter("https://").substringBefore("#")
        }
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer,
            "Origin" to referer.substringBefore("/stream"),
            "X-Requested-With" to "XMLHttpRequest"
        )
        val resp = app.post(liveUrl, headers, referer = referer, json = requestBody).body.string()
        Log.d("DDLExt", liveUrl)
        val data = parseJson<VidembedResponse>(resp)
        Log.d("DDLExt", data.toJson())
        Log.d("DDLExt", data.player)

        return null
    }

    private suspend fun extractFromNewzar(urlNextPage: String, serverUrl: String): String? {
        val page = app.get(urlNextPage, headers).document
//        Log.d("DDLExt", page.toString())
        val script = page.select("script").first { it.data().contains("CHANNEL_KEY") }.data()
//        Log.d("DDLExt", script)

        val bundle = base64Decode(
            Regex("""(?<=const IJXX=").*(?=")""").find(script)?.value ?: return null
        )
        val bundleObj = parseJson<Bundle>(bundle)
        Log.d("DDLExt", bundleObj.toJson())
        val channelKey =
            Regex("""(?<=const CHANNEL_KEY=").*(?=")""").find(script)?.value ?: return null
//        Log.d("DDLExt", channelKey)
        val params = mapOf(
            "channel_id" to channelKey,
            "ts" to base64Decode(bundleObj.bTs),
            "rnd" to base64Decode(bundleObj.bRnd),
            "sig" to base64Decode(bundleObj.bSig),
        )
        Log.d("DDLExt", "Params: $params")
        //Requests
        val authResponse = //withContext(Dispatchers.IO) {
            app.get(
                "https://top2new.newkso.ru/auth.php",
                params = params,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$serverUrl/",
                    "Origin" to serverUrl
                ),
//                interceptor = CloudflareKiller()
            )
        //}
//        Log.d("DDLExt", authResponse.code.toString())
        Log.d("DDLExt", "Auth: " + authResponse.body.string())
        if (authResponse.code == 403) return null

        val serverKey = app.get("$serverUrl/server_lookup.php?channel_id=$channelKey").body.string()
        Log.d("DDLExt", "Server Key: $serverKey")
        val data = try { parseJson<DataResponse>(serverKey) }
        catch (e: MismatchedInputException){
            Log.d("DDLExt", serverKey)
            Log.d("DDLExt", "$e")
            return null
        }
        //So far it works
        val m3u8 = when (data.serverKey) {
            "top1/cdn" -> "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
            else -> "https://${data.serverKey}new.newkso.ru/${data.serverKey}/$channelKey/mono.m3u8"
        }
        Log.d("DDLExt", "Final Url: $m3u8")
        return m3u8
    }

    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
    data class VidembedResponse(
        val success: Boolean,
        val player: String
    )
    data class Bundle(
        @JsonProperty("b_host")
        val bHost: String,
        @JsonProperty("b_rnd")
        val bRnd: String,
        @JsonProperty("b_script")
        val bScript: String,
        @JsonProperty("b_sig")
        val bSig: String,
        @JsonProperty("b_ts")
        val bTs: String
    )
}