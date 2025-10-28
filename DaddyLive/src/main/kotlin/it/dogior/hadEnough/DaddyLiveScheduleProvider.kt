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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    private fun searchResponseBuilder(doc: Element): List<LiveSearchResponse> {
        return doc.select(".schedule__event").map { e ->
            val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
            val eventTitle = e.select(".schedule__eventTitle").text()
            val channels = e.select(".schedule__channels > a").map { fixUrl(it.attr("href")) }
            val time = e.select(".schedule__time").text()
            val formattedTime = convertGMTToLocalTime(time)
            newLiveSearchResponse("$formattedTime - $eventTitle", dataTitle){
                this.posterUrl = Companion.posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            val sectionTitle = it.select(".card__meta").text()
            val events = searchResponseBuilder(it)
            HomePageList(
                sectionTitle,
                events,
                false
            )
        }
        return newHomePageResponse(
            schedule,
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            searchResponseBuilder(it)
        }.flatten()
        val matches = schedule.filter {
            query.lowercase().replace(" ", "") in
                    it.name.lowercase().replace(" ", "")
        }
        return matches
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(mainUrl).document
        val dataTitle = url.removePrefix("$mainUrl/")
        val event = doc.select(".schedule__event").first{
            val header = it.select("div.schedule__eventHeader")
            header.attr("data-title") == dataTitle
        }
        val eventTitle = event.select(".schedule__eventTitle").text()
        val channels = event.select(".schedule__channels > a").map {
            val id = it.attr("href").substringAfter("id=")
            Channel(it.text(), "$mainUrl/%s/stream-$id.php")
        }
        Log.d("DDL Schedule - Channels", channels.toJson())
        val time = event.select(".schedule__time").text()
        val formattedTime = convertGMTToLocalTime(time)
        return newLiveStreamLoadResponse("$formattedTime - $eventTitle", url, dataUrl = channels.toJson()){
            this.posterUrl = Companion.posterUrl
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val players = listOf("stream", "cast", "watch", "plus", "casting", "player")
        val channels = parseJson<List<Channel>>(data)

        val links = channels.map {
            players.map { l ->
                val url = it.channelId.format(l)
                Log.d("DDL - Servers", url)
                it.channelName + " - $l" to url
            }
        }.flatten()

        DaddyLiveExtractor().getUrl(links.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(
        val channelName: String,
        val channelId: String
    )
}