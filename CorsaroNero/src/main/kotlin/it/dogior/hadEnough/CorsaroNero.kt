package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newSearchResponseList


class CorsaroNero : TmdbProvider() {
    override var mainUrl = "https://ilcorsaronero.link"
    override var name = "Il Corsaro Nero"
    override val supportedTypes = setOf(TvType.Movie, TvType.Torrent)
    override var lang = "it"
    override val hasMainPage = true
    private val tmdbAPI = "https://api.themoviedb.org/3"

    private val apiKey = BuildConfig.TMDB_API
    private val authHeaders =
        mapOf("Authorization" to "Bearer $apiKey")

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/movie/day?region=IT&language=it-IT" to "Di Tendenza",
        "$tmdbAPI/movie/popular?region=IT&language=it-IT" to "Popolari",
        "$tmdbAPI/movie/top_rated?region=IT&language=it-IT" to "Valutazione più alta",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=28" to "Azione",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=12" to "Avventura",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=16" to "Animazione",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=35" to "Commedia",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=80" to "Crime",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=99" to "Documentario",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=18" to "Drama",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=10751" to "Famiglia",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=878" to "Fantascienza",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=14" to "Fantasy",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=27" to "Horror",
        "$tmdbAPI/discover/movie?language=it-IT&sort_by=popularity.desc&with_origin_country=IT" to "Italiani",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=9648" to "Mistero",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=10749" to "Romantico",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=36" to "Storico",
        "$tmdbAPI/discover/movie?region=IT&language=it-IT&with_genres=53" to "Thriller",
    )


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get("${request.data}&page=$page", headers = authHeaders).body.string()
//        Log.d("TMDB", resp)
        val home = parseJson<Results>(resp).results?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }


    override suspend fun search(query: String, page: Int): SearchResponseList {
        val response = app.get(
            "$tmdbAPI/search/movie?language=it-IT&query=$query&page=$page&include_adult=true",
            headers = authHeaders
        ).parsedSafe<Results>()
        val results = response?.results?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: emptyList()
        val hasNext = page < (response?.totalPages ?: 0)
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "alternative_titles,credits,external_ids,keywords,videos,recommendations"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=it-IT&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=it-IT&append_to_response=$append"
        }
        val res = app.get(resUrl, headers = authHeaders).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")
        Log.d("CorsaroNero", res.toJson())

        val title = res.title ?: res.name ?: return null
        val poster = getImageUrl(res.posterPath, getOriginal = true)
        val bgPoster = getImageUrl(res.backdropPath, getOriginal = true)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()

        val genres = res.genres?.mapNotNull { it.name }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            val name = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(
                Actor(name, getImageUrl(cast.profilePath)),
                roleString = cast.character
            )
        } ?: emptyList()

        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer = res.videos?.results?.filter { it.type == "Trailer" }
            ?.map { "https://www.youtube.com/watch?v=${it.key}" }?.reversed().orEmpty()
            .ifEmpty { res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" } }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            LinkData(
                data.id,
                data.type,
                title = title,
                year = year,
                airedDate = res.releaseDate
                    ?: res.firstAirDate,
            ).toJson(),
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bgPoster
            this.year = year
            this.plot = res.overview
            this.duration = res.runtime
            this.tags = genres
            addScore(res.voteAverage.toString())
            this.recommendations = recommendations
            this.actors = actors
            addTrailer(trailer)
            addTMDbId(data.id.toString())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val torrents = getMagnetFromData(data)
        var success = false
        Log.d("CorsaroNero:torrents", torrents.toJson())
        if (torrents.isEmpty()) {
            showToast("No torrents found")
            return success
        }
        torrents.forEach {
            if (it.magnet != null) {
                success = true
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = it.movieTitle,
                        url = it.magnet,
                        type = INFER_TYPE,
                    )
                )
            }
        }
        return success
    }

    private suspend fun getMagnetFromData(data: String): List<TorrentPage> {
        val torrentPages = searchTorrent(parseJson<LinkData>(data))
        val finalData = torrentPages.map {
            val resp = app.get(it.url).document
            val mainDiv = resp.select("div.w-full:nth-child(2)")
            val magnet = mainDiv.select("a.w-full:nth-child(1)").attr("href")
            it.copy(magnet = magnet)
        }
//        Log.d("CorsaroNero", finalData.toJson())
        return finalData
    }

    private suspend fun searchTorrent(linkData: LinkData): List<TorrentPage> {
        val page = app.get("$mainUrl/search?q=${linkData.title} ${linkData.year}&cat=film")
        val document = page.document
        val titles = document.select("tbody").select("th").select("a")
        return titles.map { TorrentPage(it.text(), mainUrl + it.attr("href")) }
    }

    data class TorrentPage(
        val movieTitle: String,
        val url: String,
        val magnet: String? = null,
    )


    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link == null) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width/$link" else link
    }

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
        @JsonProperty("total_pages") val totalPages: Int = 0,
    )

    data class Media(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
    )

    private fun Media.toSearchResponse(type: String = "movie"): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
        }
    }

    data class Data(
        val id: Int,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Any? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @JsonProperty("credits") val credits: Credits? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Genres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class ResultsTrailer(
        @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val epid: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime: Boolean = false,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val date: String? = null,
        val airedDate: String? = null,
    )
}