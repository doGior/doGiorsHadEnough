package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import kotlin.io.encoding.Base64


class CalcioStreaming : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://corner.direttecommunity.online/"
    override var name = "CalcioStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    override var sequentialMainPage = true
    override val hasDownloadSupport = false
    val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get("$mainUrl/api/events.php").body.string()
        val events = parseJson<JSONResponse>(resp).events
        val searchResponses = events.mapNotNull { event ->
            if (event.status == "live") {
                newLiveSearchResponse(
                    name = event.title,
                    url = event.toJson()
                )
            } else {
                null
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Live", searchResponses, true
                )
            ), false
        )
    }


    override suspend fun load(url: String): LoadResponse {
        val data = url
        val event = parseJson<Event>(data)
//        val posterUrl = ""
        val title = event.title
        return newLiveStreamLoadResponse(title, url = url, dataUrl = event.streams.toJson())
    }

    fun getStreamUrl(html: String): String? {
        val configMatch = Regex("""window\._econfig\s*=\s*['"]([^'"]+)['"]""").find(html)
            ?: return null

        try {
            val encodedConfig = configMatch.groupValues[1]
            val decodedConfig =
                Base64.decode(encodedConfig + "=".repeat((-encodedConfig.length % 4 + 4) % 4))
                    .toString(Charsets.ISO_8859_1)

            val partOrder = listOf(2, 0, 3, 1)
            val partLength = (decodedConfig.length + 3) / 4
            val encodedParts = mutableListOf<String>()
            var offset = 0

            repeat(4) {
                val part = decodedConfig.substring(
                    offset,
                    minOf(offset + partLength, decodedConfig.length)
                )
                offset += partLength
                encodedParts.add(part.take(3) + part.drop(4))
            }

            val decodedParts = Array(4) { "" }
            encodedParts.forEachIndexed { index, part ->
                val padded = part + "=".repeat((-part.length % 4 + 4) % 4)
                decodedParts[partOrder[index]] = Base64.decode(padded)
                    .toString(Charsets.ISO_8859_1)
            }

            val joinedConfig = decodedParts.joinToString("")
            val configJson = Base64
                .decode(joinedConfig + "=".repeat((-joinedConfig.length % 4 + 4) % 4))
                .toString(Charsets.UTF_8)

            val config = JSONObject(configJson)
            return config.optString("stream_url_nop2p").ifEmpty { null }
                ?: config.optString("stream_url").ifEmpty { null }
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun extractVideoStream(
        url: String,
        n: Int
    ): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (n > 10) return null

        val doc = app.get(url).document
        val link = doc.selectFirst("iframe")?.attr("src") ?: return null
        val referer = "https://"+url.toHttpUrl().host
        val resp2 = app.get(
            fixUrl(link), referer = referer, headers = mapOf(
                "Sec-Fetch-Dest" to "iframe"
            )
        )
        val newPage = resp2.document
        val streamUrl = getStreamUrl(newPage.toString())
        return if (newPage.select("script").size >= 6 && !streamUrl.isNullOrEmpty()) {
            streamUrl to fixUrl(link)
        } else {
            extractVideoStream(url = link, n = n + 1)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streams = parseJson<List<Stream>>(data)
        val links = streams.mapNotNull { stream ->
            Log.d("CalcioStreaming", stream.toJson())
            val link = extractVideoStream(url=stream.url,n=0)
            Log.d("CalcioStreaming", "Link finale: $link\n")
            if(link != null){
                Link(name="${stream.label} ${stream.lang}", ref=link.second, url = link.first)
            }else{
                null
            }
        }
        links.forEach {
            Log.d("CalcioStreaming", it.toJson())
            callback(
                newExtractorLink(
                    source = this.name,
                    name = it.name,
                    url = it.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = it.ref
                }
            )
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = cfKiller.intercept(chain)
                return response
            }
        }
    }
}

data class Link(
    val name: String,
    val url: String,
    val ref: String
)
data class JSONResponse(
    @JsonProperty("events")
    val events: List<Event>
)

data class Event(
//    @JsonProperty("away_team")
//    val awayTeam: String,
//    @JsonProperty("away_team_badge")
//    val awayTeamBadge: String,
//    @JsonProperty("heat_tier")
//    val heatTier: String,
//    @JsonProperty("home_team")
//    val homeTeam: String,
//    @JsonProperty("home_team_badge")
//    val homeTeamBadge: String,
    @JsonProperty("id")
    val id: String,
//    @JsonProperty("league")
//    val league: String,
//    @JsonProperty("sources")
//    val sources: List<String>,
    @JsonProperty("sport")
    val sport: String,
    @JsonProperty("start_time")
    val startTime: String,
    @JsonProperty("start_ts")
    val startTs: Int,
    @JsonProperty("status")
    val status: String,
    @JsonProperty("streams")
    val streams: List<Stream>,
    @JsonProperty("title")
    val title: String
)

data class Stream(
    @JsonProperty("label")
    val label: String,
    @JsonProperty("lang")
    val lang: String,
    @JsonProperty("source")
    val source: String,
    @JsonProperty("url")
    val url: String
)
