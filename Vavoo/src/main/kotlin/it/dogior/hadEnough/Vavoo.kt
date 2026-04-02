package it.dogior.hadEnough

import com.lagradost.api.Log
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
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

class Vavoo(private val countries: Map<String, Boolean>, language: String) : MainAPI() {
    override var mainUrl = "https://vavoo.to"
    override var name = "Vavoo"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        val posterUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Vavoo/Vavoo.jpg"
        val resolveUA = "MediaHubMX/2"
        val authUA = "okhttp/4.11.0"
        var sign: AuthSign? = null
        val uniqueId = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }

    data class AuthSign(
        val sign: String,
        val expires: Long
    )

     /*suspend fun getAuthSign(): AuthSign? {
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
                    "uniqueId" to "d10e5d99ab665233" //uniqueId
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
            "firstAppStart" to (System.currentTimeMillis() - 86400000),
            "lastAppStart" to System.currentTimeMillis(),
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
        sign = AuthSign(signature, System.currentTimeMillis() + 60)
        return sign
    }*/

     suspend fun getAuthSign(): AuthSign? {
        if (sign != null && sign!!.expires > System.currentTimeMillis()) return sign
        val payload = mapOf("vec" to "9frjpxPjxSNilxJPCJ0XGYs6scej3dW/h/VWlnKUiLSG8IP7mfyDU7NirOlld+VtCKGj03XjetfliDMhIev7wcARo+YTU8KPFuVQP9E2DVXzY2BFo1NhE6qEmPfNDnm74eyl/7iFJ0EETm6XbYyz8IKBkAqPN/Spp3PZ2ulKg3QBSDxcVN4R5zRn7OsgLJ2CNTuWkd/h451lDCp+TtTuvnAEhcQckdsydFhTZCK5IiWrrTIC/d4qDXEd+GtOP4hPdoIuCaNzYfX3lLCwFENC6RZoTBYLrcKVVgbqyQZ7DnLqfLqvf3z0FVUWx9H21liGFpByzdnoxyFkue3NzrFtkRL37xkx9ITucepSYKzUVEfyBh+/3mtzKY26VIRkJFkpf8KVcCRNrTRQn47Wuq4gC7sSwT7eHCAydKSACcUMMdpPSvbvfOmIqeBNA83osX8FPFYUMZsjvYNEE3arbFiGsQlggBKgg1V3oN+5ni3Vjc5InHg/xv476LHDFnNdAJx448ph3DoAiJjr2g4ZTNynfSxdzA68qSuJY8UjyzgDjG0RIMv2h7DlQNjkAXv4k1BrPpfOiOqH67yIarNmkPIwrIV+W9TTV/yRyE1LEgOr4DK8uW2AUtHOPA2gn6P5sgFyi68w55MZBPepddfYTQ+E1N6R/hWnMYPt/i0xSUeMPekX47iucfpFBEv9Uh9zdGiEB+0P3LVMP+q+pbBU4o1NkKyY1V8wH1Wilr0a+q87kEnQ1LWYMMBhaP9yFseGSbYwdeLsX9uR1uPaN+u4woO2g8sw9Y5ze5XMgOVpFCZaut02I5k0U4WPyN5adQjG8sAzxsI3KsV04DEVymj224iqg2Lzz53Xz9yEy+7/85ILQpJ6llCyqpHLFyHq/kJxYPhDUF755WaHJEaFRPxUqbparNX+mCE9Xzy7Q/KTgAPiRS41FHXXv+7XSPp4cy9jli0BVnYf13Xsp28OGs/D8Nl3NgEn3/eUcMN80JRdsOrV62fnBVMBNf36+LbISdvsFAFr0xyuPGmlIETcFyxJkrGZnhHAxwzsvZ+Uwf8lffBfZFPRrNv+tgeeLpatVcHLHZGeTgWWml6tIHwWUqv2TVJeMkAEL5PPS4Gtbscau5HM+FEjtGS+KClfX1CNKvgYJl7mLDEf5ZYQv5kHaoQ6RcPaR6vUNn02zpq5/X3EPIgUKF0r/0ctmoT84B2J1BKfCbctdFY9br7JSJ6DvUxyde68jB+Il6qNcQwTFj4cNErk4x719Y42NoAnnQYC2/qfL/gAhJl8TKMvBt3Bno+va8ve8E0z8yEuMLUqe8OXLce6nCa+L5LYK1aBdb60BYbMeWk1qmG6Nk9OnYLhzDyrd9iHDd7X95OM6X5wiMVZRn5ebw4askTTc50xmrg4eic2U1w1JpSEjdH/u/hXrWKSMWAxaj34uQnMuWxPZEXoVxzGyuUbroXRfkhzpqmqqqOcypjsWPdq5BOUGL/Riwjm6yMI0x9kbO8+VoQ6RYfjAbxNriZ1cQ+AW1fqEgnRWXmjt4Z1M0ygUBi8w71bDML1YG6UHeC2cJ2CCCxSrfycKQhpSdI1QIuwd2eyIpd4LgwrMiY3xNWreAF+qobNxvE7ypKTISNrz0iYIhU0aKNlcGwYd0FXIRfKVBzSBe4MRK2pGLDNO6ytoHxvJweZ8h1XG8RWc4aB5gTnB7Tjiqym4b64lRdj1DPHJnzD4aqRixpXhzYzWVDN2kONCR5i2quYbnVFN4sSfLiKeOwKX4JdmzpYixNZXjLkG14seS6KR0Wl8Itp5IMIWFpnNokjRH76RYRZAcx0jP0V5/GfNNTi5QsEU98en0SiXHQGXnROiHpRUDXTl8FmJORjwXc0AjrEMuQ2FDJDmAIlKUSLhjbIiKw3iaqp5TVyXuz0ZMYBhnqhcwqULqtFSuIKpaW8FgF8QJfP2frADf4kKZG1bQ99MrRrb2A=")
        val response = app.post(
            "https://www.vavoo.tv/api/box/ping2",
            json = payload
        ).body.string()
         val responseObj = JSONObject(response).getJSONObject("response")
        val signature = responseObj.getString("signed")
        val expiration = responseObj.getLong("sigValidUntil")
        sign = AuthSign(signature, expiration)
        return sign
    }

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
        val sign = getAuthSign()?.sign ?: return null
        val payload = mapOf(
            "language" to "en",
            "region" to "UK",
            "url" to url,
            "clientVersion" to "3.0.2"
        )
        val headers = mapOf(
            "user-agent" to resolveUA,
            "accept" to "application/json",
            "content-type" to "application/json; charset=utf-8",
//            "accept-encoding" to "gzip",
            "mediahubmx-signature" to sign
        )
        val response = app.post(
            "https://vavoo.to/mediahubmx-resolve.json",
            headers = headers,
            json = payload
        ).body.string()
        if(response.contains("MediaHubMX signature timed out")) throw SignatureTimedOutException()
        val channel = parseJson<List<ChannelData>>(response).first()
        return newLiveStreamLoadResponse(channel.name, url, channel.url){
            this.posterUrl = Companion.posterUrl
        }
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
            ){
                this.referer = mainUrl
                this.headers = mapOf("user-agent" to resolveUA)
            }
        )
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                Log.d("Vavoo Interceptor", response.peekBody(1024).string())
                return response
            }
        }
    }

    fun channelToSearchResponse(channel: Channel): LiveSearchResponse {
        return newLiveSearchResponse(channel.name, channel.url){
            this.posterUrl = Companion.posterUrl
        }
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
    class SignatureTimedOutException(message: String? = "Signature timed out") : Exception(message + "\n")
}
