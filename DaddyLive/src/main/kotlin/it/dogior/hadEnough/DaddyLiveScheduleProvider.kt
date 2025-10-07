package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://dlhd.dad"
    override var name = "DaddyLive Schedule"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "un"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"

        fun convertGMTToLocalTime(gmtTime: String): String {
            // Define the input format (GMT time)
            val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            gmtFormat.timeZone = TimeZone.getTimeZone("GMT") // Set the timezone to GMT

            // Parse the input time string
            val date: Date =
                gmtFormat.parse(gmtTime) ?: throw IllegalArgumentException("Invalid time format")

            // Define the output format (local time)
            val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            localFormat.timeZone =
                TimeZone.getDefault() // Set the timezone to the device's local timezone

            // Format the date to local time
            return localFormat.format(date)
        }

        fun convertStringToLocalDate(objectKey: String): String {
            val dateString = objectKey.substringBeforeLast(" -")

            // Remove the ordinal suffix (e.g., "nd" in "02nd")
            val cleanedDateString = dateString.replace(Regex("(?<=\\d)(st|nd|rd|th)"), "")

            // Define the date format
            val dateFormat = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.ENGLISH)

            // Parse the date string into a Date object
            val date = dateFormat.parse(cleanedDateString)

            // Convert the Date to a Calendar object in the system's default time zone
            val calendar = Calendar.getInstance()
            calendar.time = date!!

            val outputFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
            return outputFormat.format(calendar.time)
        }
    }

    private suspend fun searchResponseBuilder(): List<Pair<String, List<LiveSearchResponse>>> {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )
        val schedule = app.get(
            "$mainUrl/schedule/schedule-generated.php",
            headers,
            timeout = 10
        ).body.string()
        val jsonSchedule = JSONObject(schedule)

        val events = mutableMapOf<String, MutableList<LiveSearchResponse>>()
        jsonSchedule.keys().forEach { date ->
            val categories = jsonSchedule.getJSONObject(date)
            categories.keys().forEach { cat ->
                val array = categories.getJSONArray(cat)
                val event = tryParseJson<List<Event>>(array.toString())
                if (event == null) {
                    Log.d("DaddyLive Schedule - Parsing Error", array.toString())
                    return@forEach
                }
                val searchResponses = event.map {
                    it.date = convertStringToLocalDate(date)
                    eventToSearchResponse(it)
                }.toMutableList()

                val fixedCat = cat.replace("</span>", "")
                if (events[fixedCat] == null) {
                    events[fixedCat] = searchResponses
                } else {
                    events[fixedCat]?.addAll(searchResponses)
                }

            }
        }

        return events.map {
            it.key to it.value
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val searchResponses = searchResponseBuilder()
        val sections = searchResponses.map {
            HomePageList(
                it.first,
                it.second,
                false
            )
        }

        return newHomePageResponse(
            sections,
            false
        )
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponses = searchResponseBuilder().map { it.second }.flatten()
        val matches = searchResponses.filter {
            query.lowercase().replace(" ", "") in
                    it.name.lowercase().replace(" ", "")
        }
        return matches
    }

    override suspend fun load(url: String): LoadResponse {
        val event = parseJson<Event>(url)
        val time = convertGMTToLocalTime(event.time)

        return newLiveStreamLoadResponse(event.name, url, event.channels.toJson()) {
            this.tags = listOf(event.date + " " + time)
            this.posterUrl = Companion.posterUrl
        }
//        LiveStreamLoadResponse(
//            event.name,
//            url,
//            this.name,
//            event.channels.toJson(),
//            tags = listOf(event.date + " " + time),
//            posterUrl = posterUrl
//        )
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channels = parseJson<List<Channel>>(data)
        val links = channels.map {
            val link = "$mainUrl/stream/stream-${it.channelId}.php"
            it.channelName to link
        }

        DaddyLiveExtractor().getUrl(links.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Event(
        var date: String?,
        val time: String,
        @JsonProperty("event")
        val name: String,
        val channels: List<Channel>,
        @JsonProperty("channels2")
        val channels2: List<Channel>
    ) {
//        fun toSearchResponse(apiName: String): LiveSearchResponse {
//            val title = this.date?.let { it + " " + convertGMTToLocalTime(time) + " - " + name }
//                ?: (convertGMTToLocalTime(time) + " - " + name)
//
//            return LiveSearchResponse(
//                title,
//                this.toJson(),
//                apiName,
//                posterUrl = posterUrl,
//                type = TvType.Live
//            )
//        }
    }

    private fun eventToSearchResponse(event: Event): LiveSearchResponse {
        val title = convertGMTToLocalTime(event.time) + " - " + event.name

        return newLiveSearchResponse(title, event.toJson(), TvType.Live) {
            posterUrl = Companion.posterUrl
        }
    }

    data class Channel(
        @JsonProperty("channel_name")
        val channelName: String,
        @JsonProperty("channel_id")
        val channelId: String
    )
}