package it.dogior.hadEnough

//import android.util.Log
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone


class Arte(language: String) : MainAPI() {
    override var mainUrl = "https://www.arte.tv"
    override var name = "Arte"
    override val supportedTypes = setOf(TvType.Documentary)
    override var lang = language
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers =
            mapOf("Authorization" to "Bearer YTEwZWE3M2UxMTVmYmRjZmE0YTdmNjA4ZTI2NDczZDU3YjdjYmVmMmRmNGFjOTM3M2RhNTM5ZjIxYmI3NTc1Zg")
        val response =
            app.get("https://api.arte.tv/api/emac/v4/$lang/tv/pages/HOME", headers = headers)
        val jsonData = response.body.string()
        val data = parseJson<Page>(jsonData)
        val homePageLists = data.zones.mapNotNull { section ->
            val isHorizontal = !section.displayOptions.template.contains("portrait")
            val searchResponses = section.content.data.mapNotNull {
                it.toSearchResponse(isHorizontal)
            }
            if (searchResponses.isEmpty()) return@mapNotNull null
            HomePageList(section.title, searchResponses, isHorizontalImages = isHorizontal)
        }

        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://api.arte.tv/api/emac/v4/$lang/web/pages/SEARCH/?page=1&query=$query"
        val response = app.get(url).body.string()
        val data = parseJson<Page>(response)
        val searchResponses = data.zones.first().content.data.mapNotNull {
            it.toSearchResponse(false)
        }

        return searchResponses
    }

    override suspend fun load(url: String): LoadResponse {
        val type = if (url.substringAfter("/videos/").startsWith("RC"))
            TvType.TvSeries else TvType.Movie
        val response = app.get(
            url, headers =
                mapOf(
                    "referer" to "${mainUrl}/$lang/videos/serie-e-fiction/",
                    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
                    "cookie" to "ABV=A; validated-age=1"
                )
        )
        val document = response.document
        val title = document.select("meta[property=\"og:title\"]")
            .attr("content").substringBeforeLast("-")
        val plot = document.select("meta[property=\"og:description\"]")
            .attr("content")
        val image = document.select("meta[property=\"og:image\"]")
            .attr("content")

        return if (type == TvType.Movie) {
            val link = extractVideoUrl(document)
            newMovieLoadResponse(title, url, type, link) {
                posterUrl = image
                this.plot = plot
            }
        } else {
            val showId = url.substringAfter("/videos/").substringBefore("/")
            val episodes = getEpisodes(showId, document)
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = image
                this.plot = plot
            }
        }
    }

    private suspend fun getEpisodes(collectionID: String, doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val script = doc.body().select("script").firstOrNull {
            it.data().contains("collection_subcollection_RC-")
        }?.data()
        if (script != null) {// Collections with subcollections
            val matches = Regex("collection_subcollection_RC-[0-9]+_(RC-[0-9]+)").findAll(script)
            val ids = matches.map {
                it.groupValues.toList().last().toJson()
            }
//            Log.d("ARTE", ids.toJson())
            var season = 0
            for (subCollectionID in ids) {
                season++
                val apiUrl =
                    "https://api.arte.tv/api/emac/v4/$lang/web/zones/ea28263a-f2c2-4b63-8098-a31bf1868364/content/?collectionId=$collectionID&page=1&pageId=collection&subCollectionId=$subCollectionID&type=collection"
                val response = app.get(apiUrl).body.string()
                val resp = parseJson<Value>(response)
                var epNum = 0
                episodes.addAll(resp.data.mapNotNull {
                    epNum++
                    it.toEpisode(season, epNum)
                })
            }
        } else { // Normal collections
//            Log.d("ARTE", "No Subcollections")
            var epNumber = 0
            for (page in (1..100)) {
                val apiUrl =
                    "https://api.arte.tv/api/emac/v4/$lang/web/zones/adcc5a2e-85f2-4dfb-9fc6-72755ff56267/content?collectionId=$collectionID&page=$page&pageId=collection&type=collection"
                val response = app.get(apiUrl).body.string()
                val resp = parseJson<Value>(response)
                val lastPage = resp.pagination?.totalPages ?: 100
                val data = resp.data
                episodes.addAll(data.mapNotNull {
                    epNumber++
                    it.toEpisode(fallbackEpNumber = epNumber)
                })
                if (data.isEmpty() || page == lastPage) break
            }
        }
        return episodes
    }

    private fun convertToUnixTimestamp(isoTimestamp: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            sdf.timeZone = TimeZone.getDefault()

            val date = sdf.parse(isoTimestamp)

            date?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVideoUrl(page: Document): String? {
        val urlRegex = Regex("https://manifest-arte.akamaized.net/.+m3u8")
        val script = page.select("script").firstOrNull { it.data().contains("akamaized") }?.data()
            ?: return null
        val link = urlRegex.find(script)?.value
        return link
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
//        Log.d("ARTE", data)
        if (data.isEmpty()) return false
        callback(
            newExtractorLink(
                this.name,
                this.name,
                data
            )
        )
        return true
    }


    fun ApiData.toSearchResponse(isHorizontal: Boolean): SearchResponse? {
        if (this.kind.code == "EXTERNAL") return null
        val type = if (this.kind.isCollection) TvType.TvSeries else TvType.Movie
        val title = if (this.subtitle == null) this.title else {
            this.title + " - " + this.subtitle
        }
        val link = fixUrl(this.url)
        val imgSize = if (isHorizontal) "620x350" else "500x750"
        val image = this.image.url.replace("__SIZE__", imgSize)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, link, type, false) {
                posterUrl = image
            }
        } else {
            newTvSeriesSearchResponse(title, link, type, false) {
                posterUrl = image
            }
        }
    }

    private suspend fun EpisodeData.toEpisode(
        fallbackSeasonNumber: Int = 1,
        fallbackEpNumber: Int
    ): Episode? {
        val data = this
        if (data.type != "trailer") {
            val episodePage = app.get(fixUrl(data.url)).document
            val link = extractVideoUrl(episodePage)
            return newEpisode(link ?: "") {
                this.name = data.title
                this.description = data.description + "\n" + data.availability.label
                this.posterUrl = data.image.url.replace("__SIZE__", "265x149")
                this.runTime = data.duration / 60
                this.date = convertToUnixTimestamp(data.availability.start)
                if (data.epInfo == null || data.epInfo.season == null || data.epInfo.episode == null) {
                    this.episode = fallbackEpNumber
                    this.season = fallbackSeasonNumber
                } else {
                    this.episode = data.epInfo.episode.toIntOrNull()
                    this.season = data.epInfo.season.toIntOrNull()
                }
            }
        } else {
            return null
        }
    }
}