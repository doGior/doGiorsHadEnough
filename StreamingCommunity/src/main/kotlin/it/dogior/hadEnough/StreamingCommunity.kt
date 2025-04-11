package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app

import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink


class StreamingCommunity : MainAPI() {
    override var mainUrl = Companion.mainUrl
    override var name = Companion.name
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        private var inertiaVersion = ""
        private val headers = mapOf(
                "X-Inertia" to true.toString(),
                "X-Inertia-Version" to inertiaVersion
            ).toMutableMap()
        val mainUrl = "https://streamingcommunity.airforce"
        var name = "StreamingCommunity"
    }

    private val sectionNamesList = mainPageOf(
        "$mainUrl/browse/top10" to "Top 10 di oggi",
        "$mainUrl/browse/trending" to "I Titoli Del Momento",
        "$mainUrl/browse/latest" to "Aggiunti di Recente",
        "$mainUrl/browse/genre?g=Animazione" to "Animazione",
        "$mainUrl/browse/genre?g=Avventura" to "Avventura",
        "$mainUrl/browse/genre?g=Azione" to "Azione",
        "$mainUrl/browse/genre?g=Commedia" to "Commedia",
        "$mainUrl/browse/genre?g=Crime" to "Crime",
        "$mainUrl/browse/genre?g=Documentario" to "Documentario",
        "$mainUrl/browse/genre?g=Dramma" to "Dramma",
        "$mainUrl/browse/genre?g=Famiglia" to "Famiglia",
        "$mainUrl/browse/genre?g=Fantascienza" to "Fantascienza",
        "$mainUrl/browse/genre?g=Fantasy" to "Fantasy",
        "$mainUrl/browse/genre?g=Horror" to "Horror",
        "$mainUrl/browse/genre?g=Reality" to "Reality",
        "$mainUrl/browse/genre?g=Romance" to "Romance",
        "$mainUrl/browse/genre?g=Thriller" to "Thriller",
    )
    override val mainPage = sectionNamesList

    private suspend fun setInertiaVersion() {
        val response = app.get("$mainUrl/browse/top10")
//        Log.d("Inertia", response.headers.toString())
        val page = response.document
//        Log.d("Inertia", page.toString())
        val inertiaPageObject = page.select("#app").attr("data-page")
        inertiaVersion =
            inertiaPageObject.substringAfter("\"version\":\"").substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    private fun searchResponseBuilder(listJson: List<Title>): List<SearchResponse> {
        val domain = mainUrl.substringAfter("://")
        val list: List<SearchResponse> =
            listJson.filter { it.type == "movie" || it.type == "tv" }.map { title ->
                val url = "$mainUrl/titles/${title.id}-${title.slug}"

                if (title.type == "tv") {
                    TvSeriesSearchResponse(
                        name = title.name,
                        url = url,
                        apiName = this@StreamingCommunity.name,
                        posterUrl = "https://cdn.$domain/images/" + title.getPoster(),
                    )
                } else {
                    MovieSearchResponse(
                        name = title.name,
                        url = url,
                        apiName = this@StreamingCommunity.name,
                        posterUrl = "https://cdn.$domain/images/" + title.getPoster(),
                    )
                }
            }
        return list
    }

    //Get the Homepage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
//        val TAG = "STREAMINGCOMMUNITY:MainPage"
        var url: String = mainUrl + "/api" + request.data.substringAfter(mainUrl)
        val params = emptyMap<String, String>().toMutableMap()

        val section = request.data.substringAfterLast("/")
        when (section) {
            "trending" -> {
//                Log.d(TAG, "TRENDING")
            }

            "latest" -> {
//                Log.d(TAG, "LATEST")
            }

            "top10" -> {
//                Log.d(TAG, "TOP10")
            }

            else -> {
                val genere = url.substringAfterLast('=')
                url = url.substringBeforeLast('?')
                params["g"] = genere
            }
        }

        if (page > 0) {
            params["offset"] = ((page - 1) * 60).toString()
        }
//        Log.d(TAG, "Url: $url")
//        Log.d(TAG, "Params: $params")
        val response = app.get(url, params = params)
        val responseString = response.body.string()
        val responseJson = parseJson<Section>(responseString)
//        Log.d(TAG, "Response: $responseJson")

        val titlesList = searchResponseBuilder(responseJson.titles)

        val hasNextPage =
            response.okhttpResponse.request.url.queryParameter("offset")?.toIntOrNull()
                ?.let { it < 120 } ?: true && titlesList.size == 60

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = titlesList,
                isHorizontalImages = false
            ), hasNextPage
        )
    }

    // This function gets called when you search for something also
    //This is to get Title,Href,Posters for Homepage
    override suspend fun search(query: String): List<SearchResponse> {
//        val TAG = "STREAMINGCOMMUNITY:search"
        val url = "$mainUrl/api/search"
        val params = mapOf("q" to query)

        val response = app.get(url, params = params).body.string()
//        Log.d(TAG, "Response: $response")
        val result = parseJson<SearchData>(response)

        return searchResponseBuilder(result.titles)
    }

    // This function gets called when you enter the page/show
    override suspend fun load(url: String): LoadResponse {
//        val TAG = "STREAMINGCOMMUNITY:Item"

//        Log.d(TAG, "URL: $url")
        val actualUrl = getActualUrl(url)

        if (inertiaVersion == "") {
            setInertiaVersion()
        }

        if (headers["XSRF-TOKEN"].isNullOrEmpty()){
            val response = app.get(actualUrl, headers = headers)
            val token = response.cookies["XSRF-TOKEN"]?.replace("%3D", "=")
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["X-XSRF-TOKEN"] = token!!
        }

//        Log.d(TAG, "Headers: ${headers}")
        val response = app.get(actualUrl, headers = headers)
        val responseBody = response.body.string()
//        Log.d(TAG, "Body: $responseBody")
//        Log.d(TAG, "Request: ${response.okhttpResponse.request}")
//        Log.d(TAG, "Response: $responseBody")

        val props = parseJson<InertiaResponse>(responseBody).props
        val title = props.title!!
        val genres = title.genres.map { it.name.capitalize() }
        val domain = mainUrl.substringAfter("://")
        val year = title.releaseDate.substringBefore('-').toIntOrNull()
        val related = props.sliders?.get(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }
//        Log.d(TAG, "Trailer List: $trailers")
        if (title.type == "tv") {
            val episodes: List<Episode> = getEpisodes(props)
//            Log.d(TAG, "Episode List: $episodes")

            val tvShow = newTvSeriesLoadResponse(title.name, actualUrl, TvType.TvSeries, episodes) {
                this.posterUrl = "https://cdn.$domain/images/" + title.getBackgroundImageId()
                this.tags = genres
                this.episodes = episodes
                this.year = year
                this.plot = title.plot
                title.age?.let{this.contentRating = "$it+"}
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }
                this.addActors(title.mainActors?.map { it.name })
                this.addRating(title.score)
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }

            }
//            Log.d(TAG, "TV Show: $tvShow")
            return tvShow
        } else {
            val movie = newMovieLoadResponse(
                title.name,
                actualUrl,
                TvType.Movie,
                dataUrl = "$mainUrl/iframe/${title.id}&canPlayFHD=1"
            ) {
//                this.backgroundPosterUrl = "https://cdn.$domain/images/" + title.getBackgroundImageId()
                this.posterUrl = "https://cdn.$domain/images/" + title.getBackgroundImageId()
                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let{this.contentRating = "$it+"}
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                this.addActors(title.mainActors?.map { it.name })
                this.addRating(title.score)

                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }

                title.runtime?.let { this.duration = it }
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }
            }
//            Log.d(TAG, "Movie: $movie")
            return movie
        }
    }

    private fun getActualUrl(url: String) = if (!url.contains(mainUrl)) {
        val urlComponents = url.split("/")
        val oldUrl = urlComponents.subList(0, 3).joinToString("/")
        //            Log.d("StreamingCommunity", oldUrl)
        url.replace(oldUrl, mainUrl)
    } else {
        url
    }

    private suspend fun getEpisodes(props: Props): List<Episode> {
//        val TAG = "STREAMINGCOMMUNITY:getEpisodes"

        val episodeList = mutableListOf<Episode>()
        val title = props.title

        title?.seasons?.forEach { season ->
            val responseEpisodes = emptyList<it.dogior.hadEnough.Episode>().toMutableList()
            if (season.id == props.loadedSeason!!.id) {
                responseEpisodes.addAll(props.loadedSeason.episodes!!)
            } else {
                if (inertiaVersion == "") {
                    setInertiaVersion()
                }
                val url = "$mainUrl/titles/${title.id}-${title.slug}/stagione-${season.number}"
                val obj =
                    parseJson<InertiaResponse>(app.get(url, headers = headers).body.string())
//                Log.d(TAG, "Parsed Response: $obj")
                responseEpisodes.addAll(obj.props.loadedSeason?.episodes!!)
            }
            responseEpisodes.forEach { ep ->

                episodeList.add(
                    Episode(
                        data = "$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1",
                        name = ep.name,
                        posterUrl = props.cdnUrl + "/images/" + ep.getCover(),
                        description = ep.plot,
                        episode = ep.number,
                        season = season.number,
                        runTime = ep.duration
                    )
                )
            }
        }

        return episodeList
    }

    // This function is how you load the links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
//        val TAG = "STREAMINGCOMMUNITY:Links"

//        Log.d(TAG, "Url : $data")

        StreamingCommunityExtractor().getUrl(
            url = data,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return false
    }
}
