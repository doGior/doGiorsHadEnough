package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ShortLink
import com.lagradost.cloudstream3.utils.loadExtractor
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class CB01 : MainAPI() {
    override var mainUrl = "https://cb01net.one/"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    companion object{
        var actualMainUrl = ""
    }

    private fun fixTitle(title: String, isMovie: Boolean): String {
        if (isMovie) {
            return title.replace(Regex("""(\[HD] )*\(\d{4}\)${'$'}"""), "")
        }
        return title.replace(Regex("""[-–] Stagione \d+"""), "")
            .replace(Regex("""[-–] ITA"""), "")
            .replace(Regex("""[-–] *\d+[x×]\d*(/?\d*)*"""), "")
            .replace(Regex("""[-–] COMPLETA"""), "").trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val response = app.get(url)

        if (actualMainUrl.isEmpty()){
            actualMainUrl = response.okhttpResponse.request.url.toString().substringBeforeLast('/')
        }

        val document = response.document
        val items = document.selectFirst(".sequex-one-columns")!!.select(".post")
        val posts = items.mapNotNull { card ->
            val poster = card.selectFirst("img")?.attr("src")
            val data = card.selectFirst("script")?.data()
            val fixedData = data?.substringAfter("=")?.substringBefore(";")
            val post = tryParseJson<Post>(fixedData)
            post?.let { it.poster = poster }
            post
        }
        val pagination = document.selectFirst(".pagination")?.select(".page-item")!!
        val lastPage = pagination[pagination.size - 2].text().replace(".", "").toInt()
        val hasNext = page < lastPage

        val searchResponses = posts.map {
            if (request.data.contains("serietv")) {
//                Log.d("CB01", it.title)
                val title = fixTitle(it.title, false)
                newTvSeriesSearchResponse(title, it.permalink, TvType.TvSeries) {
                    addPoster(it.poster)
                }
            } else {
                val quality = if (it.title.contains("HD")) SearchQuality.HD else null
                newMovieSearchResponse(
                    fixTitle(it.title, true),
                    it.permalink,
                    TvType.Movie
                ) {
                    addPoster(it.poster)
                    this.quality = quality
                }
            }
        }
        val section = HomePageList(request.name, searchResponses, false)
        return newHomePageResponse(section, hasNext)
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val searchLinks =
            listOf("$mainUrl/?s=$query", "$mainUrl/serietv/?s=$query")
        val results = searchLinks.amap { link ->
            val response = app.get(link)
            val document = response.document
            val itemColumn = document.selectFirst(".sequex-one-columns")
            val items = itemColumn?.select(".post")
            val posts = items?.mapNotNull { card ->
                val poster = card.selectFirst("img")?.attr("src")
                val data = card.selectFirst("script")?.data()
                val fixedData = data?.substringAfter("=")?.substringBefore(";")
                val post = tryParseJson<Post>(fixedData)
                post?.let { it.poster = poster }
                post
            }
            posts?.map {
                if (link.contains("serietv")) {
                    newTvSeriesSearchResponse(
                        fixTitle(it.title, false),
                        it.permalink,
                        TvType.TvSeries
                    ) {
                        addPoster(it.poster)
                    }
                } else {
                    val quality = if (it.title.contains("HD")) SearchQuality.HD else null
                    newMovieSearchResponse(
                        fixTitle(it.title, true),
                        it.permalink,
                        TvType.Movie
                    ) {
                        addPoster(it.poster)
                        this.quality = quality
                    }
                }
            }
        }.filterNotNull().flatten()
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val urlPath = url.substringAfter("//").substringAfter('/')
        val actualUrl = "$actualMainUrl/$urlPath"

        val document = app.get(actualUrl).document
        val mainContainer = document.selectFirst(".sequex-main-container")!!
        val poster =
            mainContainer.selectFirst(".sequex-featured-img")!!.selectFirst("img")!!.attr("src")
        val banner = mainContainer.selectFirst("#sequex-page-title-img")?.attr("data-img")
        val title = mainContainer.selectFirst("h1")?.text()!!
//        val actionTable = mainContainer.selectFirst("table.cbtable:nth-child(5)")
        val isMovie = !actualUrl.contains("serietv")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        return if (isMovie) {
            val plot = mainContainer.selectFirst(".ignore-css > p:nth-child(2)")?.text()
                ?.replace("+Info »", "")
            val tags =
                mainContainer.selectFirst(".ignore-css > p:nth-child(1) > strong:nth-child(1)")
                    ?.text()?.split('–')
            val runtime = tags?.find { it.contains("DURATA") }?.trim()
                ?.removePrefix("DURATA")
                ?.removeSuffix("′")?.trim()?.toInt()

            val table =
                mainContainer.selectFirst("table.cbtable > tbody:nth-child(1) > tr:nth-child(1) > td:nth-child(1)")
            val links = table?.select("a")?.mapNotNull {
                    if(it.text() == "Maxstream" || it.text() == "Mixdrop"){
                        it.attr("href")
                    }else{
                        null
                    }
                }
//            Log.d("CB01", "Links: $links")
            val data = links?.let {
                it.subList(links.size - 2, it.size)
            }?.toJson() ?: "null"

            newMovieLoadResponse(fixTitle(title, true), actualUrl, type, data) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags?.mapNotNull {
                    if (it.contains("DURATA")) null else it.trim()
                }
                this.duration = runtime
            }
        } else {

            val description = mainContainer.selectFirst(".ignore-css > p:nth-child(1)")?.text()
                ?.split(Regex("""\(\d{4}-(\d{4})?\)"""))
            val plot = description?.last()?.trim()
            val tags = description?.first()?.split('/')
            val (episodes, seasons) = getEpisodes(document)
            newTvSeriesLoadResponse(fixTitle(title, false), actualUrl, type, episodes) {
                addPoster(poster)
                addSeasonNames(seasons)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags?.map { it.trim() }
            }
        }
    }

    private suspend fun getEpisodes(page: Document): Pair<List<Episode>, MutableList<SeasonData>> {
        val seasonsData = mutableListOf<SeasonData>()
        val nestedEps = mutableListOf<Episode>()

        val seasonDropdowns = page.select(".sp-wrap ")

        val episodes = seasonDropdowns.mapIndexedNotNull { index, dropdown ->
            // Every Season
            val seasonName = dropdown.select("div.sp-head").text()
            val regex = "\\d+".toRegex()
            val seasonNumber = regex.find(seasonName)?.value?.toIntOrNull() ?: index


            val episodesData = dropdown.select("div.sp-body").select("strong")
            episodesData.amap {
                // Every episode
                val epName = it.text().substringBefore('–').trim()
                val epNumber = regex.find(epName.substringAfter('×'))?.value?.toIntOrNull()
                val links = it.select("a").map { a -> a.attr("href") }
//                Log.d("Links", seasonName + " " + links.toJson())
                if (links.any { l -> l.contains("uprot") || l.contains("stayonline") }) {
                    seasonsData.add(
                        SeasonData(
                            seasonNumber,
                            seasonName.replace("- ITA", "")
                                .replace("- HD", "").trim()
                        )
                    )
                    val uprotLink = try {
                        links.first { l -> l.contains("uprot") }
                    } catch (e: NoSuchElementException) {
                        null
                    }
                    // Try getting episodes from maxstream
                    val eps = if (uprotLink != null) {
                        getNestedEpisodes(uprotLink, seasonNumber)
                    } else {
                        null
                    }

                    if (eps.isNullOrEmpty()) {
                        // If I can't find episodes on maxstream it's very likely
                        // that I found the video source
                        Episode(
                            name = epName,
                            data = links.toJson(),
                            season = seasonNumber,
                            episode = epNumber
                        )
                    } else {
                        nestedEps.addAll(eps)
                        null
                    }
                } else {
                    null
                }
            }.filterNotNull()
        }?.flatten()

        if (nestedEps.isNotEmpty()) {
            val eps = nestedEps.toMutableList()
            eps.addAll(episodes ?: emptyList())
            return eps to seasonsData
        }

//        Log.d("CB01", "Episodes: ${episodes.toString()}")
        return (episodes ?: emptyList()) to seasonsData
    }

    private suspend fun getNestedEpisodes(
        uprotLink: String,
        season: Int?,
    ): List<Episode> {
        val link = ShortLink.unshortenUprot(uprotLink)
        Log.d("Link", "Bypassed Link: $link")
        if (link.toHttpUrlOrNull() != null) {
            val response = app.get(link)
            val trs = response.document.select("tr")
            var epNum = 0
            val episodes = trs.map {
                val tds = it.select("td")
                val epLink = tds[1].select("a").attr("href")
                val name = tds.first()?.text()
                epNum++
                Episode(
                    name = name,
                    data = listOf(epLink).toJson(),
                    season = season,
                    episode = epNum
                )
            }
            return episodes
//            Log.d("Link", "Response: $trs")
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data == "null") return false
        var links = parseJson<List<String>>(data)
        links = links.filter { it.contains("uprot.net") || it.contains("stayonline") }
        if (links.size > 2) {
            links = links.subList(2, 4)
        }
//        Log.d("CB01", "Scraped Link: $links")

        links.mapNotNull {
//            Log.d("CB01", "Base Link: $it")
            var link = if (it.contains("uprot")) {
                bypassUprot(it)
            } else if (it.contains("stayonline")) {
                bypassStayOnline(it)
            } else {
                null
            }
            link?.let { l ->
                if (link!!.contains("uprot.net")) {
//                    Log.d("CB01", "Bypassed Link: $link")
                    link = bypassUprot(l)
                } else if (link!!.contains("stayonline")) {
//                    Log.d("CB01", "Bypassed Link: $link")
                    link = bypassStayOnline(l)
                }
            }

            link?.let { l ->
//                Log.d("CB01", "Final Link: $link")
//                loadExtractor(l, "", subtitleCallback, callback)
                if (link!!.contains("maxstream")) {
                    MaxStreamExtractor().getUrl(l, null, subtitleCallback, callback)
                } else if (link!!.contains("mixdrop")) {
                    val videoId = l.split('/')[4]
                    val finalUrl = "https://mixdrop.ag/e/$videoId"
                    loadExtractor(finalUrl, "", subtitleCallback, callback)
                }
            }
        }

        return false
    }

    private suspend fun bypassStayOnline(link: String): String? {
        val headers = mapOf(
            "origin" to "https://stayonline.pro",
            "referer" to link,
            "host" to link.toHttpUrl().host,
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "x-requested-with" to "XMLHttpRequest"
        )
//        Log.d("CB01:StayOnline", link)
        val data = "id=${link.split("/").dropLast(1).last()}&ref="

        val response = app.post(
            "https://stayonline.pro/ajax/linkEmbedView.php",
            headers = headers,
            requestBody = data.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
        )

        val jsonResponse = response.body.string() // Use a JSON parser if needed
//        Log.d("CB01:StayOnline", jsonResponse)
        try {
            val realUrl = JSONObject(jsonResponse).getJSONObject("data").getString("value")
            return realUrl

        } catch (e: JSONException) {
            return null
        }
    }

    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link


        // Generate headers (replace with your own method to generate fake headers)
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        )

//        Log.d("CB01:Uprot", updatedLink)

        // Make the HTTP request
        val response = app.get(updatedLink, headers = headers, timeout = 10_000)

        val responseBody = response.body.string()

        // Parse the HTML using Jsoup
        val document = Jsoup.parse(responseBody)
        Log.d("CB01:Uprot", document.select("a").toString())
        val maxstreamUrl = document.selectFirst("a")?.attr("href")

        return maxstreamUrl
    }

    data class Post(
        @JsonProperty("id") val id: String,
        @JsonProperty("popup") val popup: String,
        @JsonProperty("unique_id") val uniqueId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("permalink") val permalink: String,
        @JsonProperty("item_id") val itemId: String,
        var poster: String? = null,
    )
}
