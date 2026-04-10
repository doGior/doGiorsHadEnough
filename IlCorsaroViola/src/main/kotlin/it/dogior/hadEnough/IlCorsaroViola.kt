package it.dogior.hadEnough

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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class IlCorsaroViola : TmdbProvider() {
    override var mainUrl = "https://db.corsaroviola.dpdns.org"
    override var name = "Il Corsaro Viola"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Torrent)
    override var lang = "it"
    override val hasMainPage = true
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val TRACKER_LIST_URL = "https://newtrackon.com/api/stable"

    private val apiKey = BuildConfig.TMDB_API
    private val authHeaders =
        mapOf("Authorization" to "Bearer $apiKey")

    private val today = getDate()
    private val tvFilters =
        "&language=it-IT&watch_region=IT&with_watch_providers=359|222|524|283|39|8|337|119|350"


    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?language=it-IT" to "Di Tendenza",
        "$tmdbAPI/movie/now_playing?region=IT&language=it-IT" to "Ora al Cinema",
        "$tmdbAPI/discover/tv?air_date.gte=$today&air_date.lte=$today&sort_by=vote_average.desc$tvFilters" to "Serie in onda oggi",
        "$tmdbAPI/movie/popular?region=IT&language=it-IT" to "Film Popolari",
        "$tmdbAPI/discover/tv?vote_count.gte=100$tvFilters" to "Serie TV Popolari",
        "$tmdbAPI/movie/top_rated?region=IT&language=it-IT" to "Film per Valutazione",
        "$tmdbAPI/discover/tv?sort_by=vote_average.desc&vote_count.gte=100$tvFilters" to "Serie TV per Valutazione",
        "$tmdbAPI/discover/tv?with_networks=213&region=IT&language=it-IT" to "Netflix",
        "$tmdbAPI/discover/tv?with_networks=1024&region=IT&language=it-IT" to "Prime Video",
        "$tmdbAPI/discover/tv?with_networks=2739&region=IT&language=it-IT" to "Disney+",
        "$tmdbAPI/discover/tv?with_watch_providers=39&watch_region=IT&language=it-IT&without_watch_providers=359,110,222" to "Now TV",
        "$tmdbAPI/discover/tv?with_networks=2552&region=IT&language=it-IT" to "Apple TV+",
        "$tmdbAPI/discover/tv?with_networks=4330&region=IT&language=it-IT" to "Paramount+",
        "$tmdbAPI/discover/tv?with_watch_providers=283&watch_region=IT&language=it-IT" to "Crunchyroll",
        "$tmdbAPI/discover/tv?with_watch_providers=222&watch_region=IT&language=it-IT&without_watch_providers=359,110,39" to "RaiPlay",
        "$tmdbAPI/discover/tv?with_watch_providers=359|110&watch_region=IT&language=it-IT&without_watch_providers=39,222" to "Mediaset Infinity",
        "$tmdbAPI/discover/tv?with_watch_providers=524&watch_region=IT&language=it-IT&without_watch_providers=359,110,39,222" to "Discovery+",
    )

    companion object {
        val trackers: MutableList<String> = mutableListOf()
    }

    private fun getDate(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()
        val today = formatter.format(calendar.time)
        return today
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val resp = app.get("${request.data}&page=$page", headers = authHeaders).body.string()
        val parsedResponse = parseJson<Results>(resp).results?.mapNotNull { media ->
            val type = if (request.data.contains("tv")) "tv" else "movie"
            media.toSearchResponse(type = type)
        }?.toMutableList()

        val home = parsedResponse ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val response = app.get(
            "$tmdbAPI/search/multi?language=it-IT&query=$query&page=$page&include_adult=true",
            headers = authHeaders
        ).parsedSafe<Results>()
        val results = response?.results?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: emptyList()
        val hasNext = page < (response?.totalPages ?: 0)
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        if (trackers.isEmpty()) {
            val response = app.get(TRACKER_LIST_URL).text
            trackers.addAll(response.trim().split("\n"))
        }
        val data = parseJson<Data>(url)
        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "alternative_titles,credits,external_ids,keywords,videos,recommendations"

        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=it-IT&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=it-IT&append_to_response=$append"
        }
        val r = app.get(resUrl, headers = authHeaders).text
        val res = tryParseJson<MediaDetail>(r)
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getImageUrl(res.posterPath, getOriginal = true)
        val bgPoster = getImageUrl(res.backdropPath, getOriginal = true)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val rating = res.voteAverage.toString()
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

        val simklId = res.imdbId?.let { fetchSimklId(it, type == TvType.TvSeries) }
        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    tmdbId = data.id,
                    type = data.type,
                    title = title,
                    year = year,
                    imdbId = res.imdbId,
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
                addScore(rating)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.imdbId)
                addSimklId(simklId)
            }
        } else {
            val episodes = getEpisodes(res, data.id)
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = genres
                addScore(rating)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.imdbId)
                addSimklId(simklId)
            }
        }
    }

    private suspend fun getEpisodes(showData: MediaDetail, id: Int?): List<Episode> {
        val episodes = showData.seasons?.mapNotNull { season ->
            app.get(
                "$tmdbAPI/tv/${showData.id}/season/${season.seasonNumber}",
                headers = authHeaders
            ).parsedSafe<MediaDetailEpisodes>()?.episodes?.map { ep ->
                newEpisode(
                    LinkData(
                        id,
                        type = "tv",
                        season = ep.seasonNumber,
                        episode = ep.episodeNumber,
                        epid = ep.id,
                        title = showData.title,
                        year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                        epsTitle = ep.name,
                        date = season.airDate,
                        imdbId = showData.imdbId ?: showData.externalIds?.imdbId
                    ).toJson()
                ) {
                    this.name = ep.name
                    this.season = ep.seasonNumber
                    this.episode = ep.episodeNumber
                    this.posterUrl = getImageUrl(ep.stillPath)
                    this.description = ep.overview
                }
            }
        }?.flatten()
        return episodes ?: emptyList()
    }

    private suspend fun fetchSimklId(
        imdbId: String,
        isSeries: Boolean
    ): Int? = runCatching {
        val type = if (isSeries) "tv" else "movies"
        val url = "https://api.simkl.com/$type/$imdbId?client_id=${BuildConfig.SIMKL_CLIENT_ID}"

        JSONObject(app.get(url).text)
            .optJSONObject("ids")
            ?.optInt("simkl")
            ?.takeIf { it != 0 }
    }.getOrNull()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val showDetail = parseJson<LinkData>(data)
        val vercelUrl = BuildConfig.ILCORSAROVIOLAVERCEL
        val body = if (showDetail.type == "movie") mapOf(
            "tmdb_id" to (showDetail.tmdbId ?: return false)
        ) else
            mapOf(
                "tmdb_id" to (showDetail.tmdbId ?: return false),
                "seasonNumber" to (showDetail.season ?: return false),
                "episodeNumber" to (showDetail.episode ?: return false)
            )
        val response = app.post("$vercelUrl/${showDetail.type}", json = body).text
        val torrents = tryParseJson<VercelResponse>(response) ?: return false
        torrents.results.map {
            val magnet = buildString {
                append(it.magnet)
                trackers.forEach { tracker ->
                    if (tracker.isNotBlank()) {
                        append("&tr=").append(tracker.trim())
                    }
                }
            }
            callback.invoke(
                newExtractorLink(this.name, it.title, magnet, ExtractorLinkType.MAGNET)
            )
        }

        return true
    }


    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link == null) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width/$link" else link
    }

    private fun Media.toSearchResponse(type: String = "tv"): SearchResponse? {
        if (mediaType == "person") return null
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
        }
    }
}