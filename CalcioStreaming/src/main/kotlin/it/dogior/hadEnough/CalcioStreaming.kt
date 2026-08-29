package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
    private val theSportsDB = "https://www.thesportsdb.com/api/v1/json/123"
    val cfKiller = CloudflareKiller()

    companion object {
        val eventsData =
            mutableMapOf<String, SportsDbEvent>() // La string è l'id dell'evento su direttecommunity
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val resp = app.get("$mainUrl/api/events.php").body.string()
        val respEvents = tryParseJson<JSONResponse>(resp) ?: return null
        val events = respEvents.events
        val searchResponses = events.mapNotNull { event ->
            val eventData = getEventData(event)
            if (event.status == "live") {
                newLiveSearchResponse(
                    name = event.title,
                    url = event.toJson()
                ) {
                    eventData?.let {
                        Log.d("CalcioStreaming - Thumb", it.strThumb)
                        this.posterUrl = it.strThumb
                    }
                }
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

    suspend fun getEventData(event: Event): SportsDbEvent? {
        if (eventsData.keys.contains(event.id)) {
                return eventsData[event.id]
        } else {
            val resp =
                app.get("$theSportsDB/searchevents.php?e=${event.homeTeam}_vs_${event.awayTeam}").body.string()
            try {
                val parsedEvent = parseJson<SportsDbResponse>(resp).events.first()
                eventsData[event.id] = parsedEvent
                return parsedEvent
            } catch (e: MismatchedInputException){
                Log.e("CalcioStreaming - Data Error", e.toString())
                return null
            } catch (e: com.fasterxml.jackson.core.JsonParseException){
                Log.e("CalcioStreaming - Data Error", e.toString())
                return null
            }
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val event = parseJson<Event>(url)
        val data = getEventData(event)
        val posterUrl = data?.strPoster
        val bannerUrl = data?.strBanner
        val title = event.title
        return newLiveStreamLoadResponse(title, url = url, dataUrl = event.streams.toJson()){
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
        }
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

    /*private suspend fun extractVideoStream(url: String): Pair<String, String>? {
        return if(url.contains("sportsonlinee")){
            extractSportsOnline(url, 0)
        } else if(url.contains("zicotv")) {
            extractZicoTv(url)
        }else {
            null
        }
    }

    private suspend fun extractZicoTv(url: String): Pair<String, String>?{
        val resp = app.get(url)
    }*/

    private suspend fun extractSportsOnline(
        url: String,
        n: Int
    ): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (n > 10) return null

        val doc = app.get(url).document
        val link = doc.selectFirst("iframe")?.attr("src") ?: return null
        val referer = "https://" + url.toHttpUrl().host
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
            extractSportsOnline(url = link, n = n + 1)
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
            Log.d("BANANA", stream.toJson())
            try {
                val link = extractSportsOnline(url = stream.url, n = 0)
//            Log.d("CalcioStreaming", "Extracted - $link")
                if (link != null) {
                    Link(name = "${stream.label} ${stream.lang}", ref = link.second, url = link.first)
                } else {
                    null
                }
            } catch (_: Exception){
                null
            }

        }
        links.forEach {
            Log.d("BANANA", it.toJson())
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
    @JsonProperty("away_team")
    val awayTeam: String,
//    @JsonProperty("away_team_badge")
//    val awayTeamBadge: String,
//    @JsonProperty("heat_tier")
//    val heatTier: String,
    @JsonProperty("home_team")
    val homeTeam: String,
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

data class SportsDbResponse(
    @JsonProperty("event")
    val events: List<SportsDbEvent>,
)

data class SportsDbEvent(
    @JsonProperty("strEvent")
    val strTitle: String,
    @JsonProperty("strLeague")
    val strLeague: String?,
    @JsonProperty("strSeason")
    val strSeason: String?,
    @JsonProperty("strThumb")
    val strThumb: String,
    @JsonProperty("strPoster")
    val strPoster: String,
    @JsonProperty("strBanner")
    val strBanner: String,
)
