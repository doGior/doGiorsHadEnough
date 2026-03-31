package it.dogior.hadEnough

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONException
import org.json.JSONObject

class Vavoo(private val countries: Map<String, Boolean>, language: String) : MainAPI() {
    override var mainUrl = "https://vavoo.to"
    override var name = "1. Vavoo"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    /*companion object {
        val resolveUA = "MediaHubMX/2"
        val authUA = "okhttp/4.11.0"
        var sign: AuthSign? = null
        val uniqueId = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }

    data class AuthSign(
        val sign: String,
        val expires: Long
    )*/

    /* suspend fun getAuthSign(): AuthSign? {
        if (sign != null && sign!!.expires > System.currentTimeMillis()) return sign
        val payload = mapOf(
            "token" to "ldCvE092e7gER0rVIajfsXIvRhwlrAzP6_1oEJ4q6HH89QHt24v6NNL_jQJO219hiLOXF2hqEfsUuEWitEIGN4EaHHEHb7Cd7gojc5SQYRFzU3XWo_kMeryAUbcwWnQrnf0-",
            "reason" to "app-blur",
            "locale" to "de",
            "theme" to "dark",
            "metadata" to mapOf(
                "device" to mapOf(
                    "type" to "Handset",
                    "brand" to "google",
                    "model" to "Nexus",
                    "name" to "21081111RG",
                    "uniqueId" to uniqueId
                ),
                "os" to mapOf(
                    "name" to "android",
                    "version" to "7.1.2",
                    "abis" to listOf("arm64-v8a"),
                    "host" to "android"
                ),
                "app" to mapOf(
                    "platform" to "android",
                    "version" to "1.1.0",
                    "buildId" to "97215000",
                    "engine" to "hbc85",
                    "signatures" to listOf("6e8a975e3cbf07d5de823a760d4c2547f86c1403105020adee5de67ac510999e"),
                    "installer" to "com.android.vending"
                ),
                "version" to mapOf(
                    "package" to "app.lokke.main",
                    "binary" to "1.1.0",
                    "js" to "1.1.0"
                )
            ),
            "appFocusTime" to 0,
            "playerActive" to false,
            "playDuration" to 0,
            "devMode" to true,
            "hasAddon" to true,
            "castConnected" to false,
            "package" to "app.lokke.main",
            "version" to "1.1.0",
            "process" to "app",
            "firstAppStart" to 1743276000000,
            "lastAppStart" to 1743276000000,
            "ipLocation" to null,
            "adblockEnabled" to false,
            "proxy" to mapOf(
                "supported" to listOf("ss", "openvpn"),
                "engine" to "openvpn",
                "ssVersion" to 1,
                "enabled" to false,
                "autoServer" to true,
                "id" to "fi-hel"
            ),
            "iap" to mapOf(
                "supported" to true
            )
        )
        val headers = mapOf(
            "accept" to "application/json",
            "user-agent" to authUA,
            "content-type" to "application/json; charset=utf-8"
        )
        val response = app.post(
            "https://www.vavoo.tv/api/app/ping",
            headers = headers,
            json = payload
        ).body.string()
        val signature = JSONObject(response).getString("addonSig")
        sign = AuthSign(signature, System.currentTimeMillis() + 3600)
        return sign
    }*/

    override val mainPage = countries.filter { it.value }.keys.sorted().map { MainPageData(it, it) }

    suspend fun getCatalog(
        country: String,
        page: Int,
        searchQuery: String = ""
    ): Pair<List<Channel>, Boolean> {
        val payload = mapOf(
            "language" to "en",
            "region" to "UK",
            "catalogId" to "iptv",
            "id" to "iptv",
            "adult" to true,
            "search" to searchQuery,
            "sort" to "name",
            "filter" to mapOf(
                "group" to country
            ),
            "cursor" to ((page - 1) * 300),
            "clientVersion" to "3.0.2"
        )
        val headers = mapOf(
            "content-type" to "application/json; charset=utf-8",
        )
        val r = app.post(
            "https://vavoo.to/mediahubmx-catalog.json",
            headers = headers,
            json = payload
        )
        val response = r.body.string()
        if (response.contains("Validation error")) throw ValidationError(response)
        val jsonResponse = JSONObject(response)
        val hasNext = try {
            jsonResponse.getInt("nextCursor")
            true
        } catch (_: JSONException) {
            false
        }
        val items = jsonResponse.getString("items")
        val objects = parseJson<List<Channel>>(items)
        return objects to hasNext
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = getCatalog(request.data, page = page)
        val searchResponses = items.first.map { channelToSearchResponse(it) }
        return newHomePageResponse(
            request.name, searchResponses, items.second
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val enabledCountries = countries.filter { it.value }
        val country = if (enabledCountries.size == 1) enabledCountries.keys.first() else ""
        val items = getCatalog(country, page = page, searchQuery = query)
        val searchResponses = items.first.map { channelToSearchResponse(it) }
        return newSearchResponseList(searchResponses, items.second)
    }

    override suspend fun load(url: String): LoadResponse? {
        val payload = mapOf(
            "language" to "en",
            "region" to "UK",
            "url" to url,
            "clientVersion" to "3.0.2"
        )
        val response = app.post(
            "https://vavoo.to/mediahubmx-resolve.json",
            headers = mapOf("content-type" to "application/json; charset=utf-8"),
            json = payload
        ).body.string()
        val channel = parseJson<List<ChannelData>>(response).first()
        return newLiveStreamLoadResponse(channel.name, url, channel.url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        callback(
            newExtractorLink(
                this.name,
                this.name,
                data,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }

    fun channelToSearchResponse(channel: Channel): LiveSearchResponse {
        return newLiveSearchResponse(channel.name, channel.url)
    }

    data class Channel(
        val type: String,
        val ids: Ids,
        val url: String,
        val name: String,
        val group: String,
        val logo: String
    )

    data class ChannelData(
        val id: String,
        val name: String,
        val url: String
    )

    data class Ids(
        val id: String
    )

    class ValidationError(message: String? = null) : Exception(message + "\n")
//    class AuthException(message: String? = null) : Exception(message + "\n")
//    class SignatureTimedOutException(message: String? = null) : Exception(message + "\n")
}
