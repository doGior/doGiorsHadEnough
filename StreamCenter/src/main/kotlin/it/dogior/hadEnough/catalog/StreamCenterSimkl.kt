package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import it.dogior.hadEnough.BuildConfig
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class StreamCenterSimklIds(
    val simkl: Int,
    val imdb: String?,
    val tmdb: String?,
    val tvdb: String?,
    val mal: Int?,
    val anilist: Int?,
    val kitsu: Int?,
)

internal data class StreamCenterSimklRecommendation(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val type: TvType,
)

internal data class StreamCenterSimklEpisode(
    val season: Int,
    val episode: Int,
    val title: String?,
    val description: String?,
    val posterUrl: String?,
    val date: String?,
    val score: Score?,
    val runTime: Int?,
)

internal data class StreamCenterSimklMedia(
    val ids: StreamCenterSimklIds,
    val category: String,
    val title: String,
    val englishTitle: String?,
    val alternativeTitles: List<String>,
    val mediaType: String?,
    val year: Int?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val plot: String?,
    val tags: List<String>,
    val studios: List<String>,
    val actors: List<ActorData>,
    val score: String?,
    val runtime: Int?,
    val contentRating: String?,
    val status: String?,
    val totalEpisodes: Int?,
    val trailerUrl: String?,
    val recommendations: List<StreamCenterSimklRecommendation>,
) {
    val type: TvType
        get() = when (category) {
            "movies" -> TvType.Movie
            "tv" -> TvType.TvSeries
            else -> when (mediaType?.lowercase(Locale.ROOT)) {
                "movie" -> TvType.AnimeMovie
                "ova", "ona", "special", "music" -> TvType.OVA
                else -> TvType.Anime
            }
        }

    val showStatus: ShowStatus?
        get() = when (status?.lowercase(Locale.ROOT)) {
            "ongoing", "returning series", "in production" -> ShowStatus.Ongoing
            "ended", "finished", "canceled", "cancelled" -> ShowStatus.Completed
            else -> null
        }

    val comingSoon: Boolean
        get() = status?.lowercase(Locale.ROOT) in setOf("upcoming", "planned", "pilot ordered")

    val titleCandidates: List<String>
        get() = (listOfNotNull(title, englishTitle) + alternativeTitles)
            .distinctBy { it.lowercase(Locale.ROOT) }

    val url: String
        get() = "https://simkl.com/$category/${ids.simkl}"
}

internal class StreamCenterSimklCatalog : StreamCenterCatalog {
    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (page < 1) return StreamCenterCatalogPage(emptyList(), false)
        val category = section.path.substringBefore('/')
        val root = if (section.key == "simkl_movies_trending") {
            requestTrendingMovies()
        } else {
            requestArray(section.path)
        }
        return page(api, root, category, page, showScore)
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage = coroutineScope {
        if (query.isBlank() || page < 1) return@coroutineScope StreamCenterCatalogPage(emptyList(), false)
        val categories = listOf("movie" to "movies", "tv" to "tv", "anime" to "anime")
        val results = categories.map { (searchType, category) ->
            async(Dispatchers.IO) {
                val data = requestArray(
                    path = "search/$searchType",
                    extraParams = mapOf(
                        "q" to query.trim(),
                        "extended" to "full",
                        "page" to page.toString(),
                        "limit" to PAGE_SIZE.toString(),
                    ),
                )
                category to data
            }
        }.awaitAll()
        val items = results.flatMap { (category, data) -> cards(api, data, category, showScore) }
            .distinctBy(SearchResponse::url)
        val hasNext = results.any { (_, data) -> data.length() >= PAGE_SIZE }
        StreamCenterCatalogPage(items, hasNext)
    }

    fun mediaRoute(url: String): Pair<String, Int>? {
        val match = MEDIA_URL.find(url) ?: return null
        return match.groupValues[1].lowercase(Locale.ROOT) to match.groupValues[2].toInt()
    }

    suspend fun resolveMediaUrl(
        simkl: Int? = null,
        imdb: String? = null,
        tmdb: String? = null,
        mal: Int? = null,
        anilist: Int? = null,
        allowedCategories: Set<String> = MEDIA_CATEGORIES,
    ): String? {
        val params = buildMap {
            simkl?.let { put("simkl", it.toString()) }
            imdb?.trim()?.takeIf(String::isNotBlank)?.let { put("imdb", it) }
            tmdb?.trim()?.takeIf(String::isNotBlank)?.let { put("tmdb", it) }
            mal?.let { put("mal", it.toString()) }
            anilist?.let { put("anilist", it.toString()) }
        }
        if (params.isEmpty()) return null
        val matches = requestArray("search/id", params)
        for (index in 0 until matches.length()) {
            val item = matches.optJSONObject(index) ?: continue
            val itemIds = ids(item.optJSONObject("ids"), simkl)
            val category = mediaCategory(item)
            if (itemIds.simkl > 0 && category in allowedCategories) {
                return "https://simkl.com/$category/${itemIds.simkl}"
            }
        }
        return null
    }

    suspend fun media(url: String): StreamCenterSimklMedia {
        val (category, id) = mediaRoute(url) ?: throw IllegalArgumentException("Identificativo Simkl non valido")
        val root = requestObject("$category/$id", mapOf("extended" to "full"))
            ?: error("Metadati Simkl non trovati")
        val ids = ids(root.optJSONObject("ids"), id)
        val title = root.optNullableString("title") ?: error("Titolo Simkl non trovato")
        return StreamCenterSimklMedia(
            ids = ids,
            category = category,
            title = title,
            englishTitle = root.optNullableString("en_title"),
            alternativeTitles = alternativeTitles(root.optJSONArray("alt_titles")),
            mediaType = root.optNullableString("anime_type") ?: root.optNullableString("type"),
            year = root.optNullableInt("year"),
            posterUrl = image(root.optNullableString("poster"), "posters", "_m"),
            backgroundUrl = image(root.optNullableString("fanart"), "fanart", "_medium"),
            plot = cleanText(root.optNullableString("overview")),
            tags = strings(root.optJSONArray("genres")),
            studios = names(root.optJSONArray("studios")),
            actors = actors(root),
            score = root.optJSONObject("ratings")?.optJSONObject("simkl")
                ?.opt("rating")?.toString()?.takeUnless { it == "0" || it == "0.0" },
            runtime = runtime(root.opt("runtime")),
            contentRating = root.optNullableString("certification"),
            status = root.optNullableString("status"),
            totalEpisodes = root.optNullableInt("total_episodes"),
            trailerUrl = trailer(root.optJSONArray("trailers")),
            recommendations = recommendations(root.optJSONArray("users_recommendations"), category),
        )
    }

    suspend fun episodes(media: StreamCenterSimklMedia): List<StreamCenterSimklEpisode> {
        if (media.category == "movies") return emptyList()
        val data = requestArray("${media.category}/episodes/${media.ids.simkl}")
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                if (item.optString("type").equals("special", ignoreCase = true)) continue
                val episode = item.optNullableInt("episode") ?: continue
                add(
                    StreamCenterSimklEpisode(
                        season = if (media.category == "anime") 1 else item.optNullableInt("season") ?: 1,
                        episode = episode,
                        title = item.optNullableString("title"),
                        description = cleanText(item.optNullableString("description")),
                        posterUrl = image(item.optNullableString("img"), "episodes", "_w"),
                        date = item.optNullableString("date")?.substringBefore('T'),
                        score = item.optJSONObject("ratings")
                            ?.optJSONObject("simkl")
                            ?.opt("rating")
                            ?.toString()
                            ?.toDoubleOrNull()
                            ?.takeIf { it > 0.0 }
                            ?.let { Score.from(it, 10) },
                        runTime = runtime(item.opt("runtime")),
                    ),
                )
            }
        }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private fun page(
        api: MainAPI,
        root: JSONArray,
        category: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        val start = (page - 1) * PAGE_SIZE
        if (start >= root.length()) return StreamCenterCatalogPage(emptyList(), false)
        val slice = JSONArray()
        for (index in start until minOf(start + PAGE_SIZE, root.length())) slice.put(root.opt(index))
        return StreamCenterCatalogPage(
            items = cards(api, slice, category, showScore),
            hasNext = start + PAGE_SIZE < root.length(),
        )
    }

    private fun cards(
        api: MainAPI,
        data: JSONArray,
        category: String,
        showScore: Boolean,
    ): List<SearchResponse> = buildList {
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val id = ids(item.optJSONObject("ids"), null).simkl.takeIf { it > 0 } ?: continue
            val title = item.optNullableString("title")
                ?: item.optNullableString("title_romaji")
                ?: continue
            val itemCategory = when (category) {
                "movie" -> "movies"
                else -> category
            }
            val type = mediaType(itemCategory, item.optNullableString("anime_type"))
            val url = "https://simkl.com/$itemCategory/$id"
            val poster = image(item.optNullableString("poster"), "posters", "_m")
            val year = item.optNullableInt("year")
                ?: YEAR.find(item.optNullableString("release_date").orEmpty())?.value?.toIntOrNull()
            val episodeCount = item.optNullableInt("total_episodes")
                ?: item.optNullableInt("episodes")
            val otherName = listOfNotNull(
                item.optNullableString("en_title"),
                item.optNullableString("title_romaji"),
                item.optNullableString("alt_title"),
            ).firstOrNull { it != title }
            val score = if (showScore) {
                item.optJSONObject("ratings")?.optJSONObject("simkl")
                    ?.opt("rating")?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }
                    ?.let { Score.from(it, 10) }
            } else {
                null
            }
            val response = when (type) {
                TvType.Movie -> api.newMovieSearchResponse(title, url, type) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                }
                TvType.TvSeries -> api.newTvSeriesSearchResponse(title, url, type) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                    episodes = episodeCount
                }
                else -> api.newAnimeSearchResponse(title, url, type) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                    this.otherName = otherName
                }
            }
            add(response)
        }
    }

    private suspend fun requestArray(path: String, extraParams: Map<String, String> = emptyMap()): JSONArray {
        val response = app.get(
            "$API_URL/${path.trimStart('/')}",
            params = API_PARAMS + extraParams,
            headers = HEADERS,
            cacheTime = 0,
        ).text
        return runCatching { JSONArray(response) }.getOrDefault(JSONArray())
    }

    private suspend fun requestTrendingMovies(): JSONArray {
        val response = app.get(
            "$DATA_URL/discover/trending/movies/today_100.json",
            params = API_PARAMS,
            headers = HEADERS,
            cacheTime = 0,
        ).text
        return runCatching { JSONArray(response) }.getOrDefault(JSONArray())
    }

    private suspend fun requestObject(path: String, extraParams: Map<String, String>): JSONObject? {
        val response = app.get(
            "$API_URL/${path.trimStart('/')}",
            params = API_PARAMS + extraParams,
            headers = HEADERS,
            cacheTime = 0,
        ).text
        return runCatching { JSONObject(response) }.getOrNull()
    }

    private fun ids(value: JSONObject?, fallback: Int?): StreamCenterSimklIds {
        val simkl = value?.optNullableInt("simkl")
            ?: value?.optNullableInt("simkl_id")
            ?: fallback
            ?: -1
        return StreamCenterSimklIds(
            simkl = simkl,
            imdb = value?.optNullableString("imdb"),
            tmdb = value?.optNullableString("tmdb"),
            tvdb = value?.optNullableString("tvdb"),
            mal = value?.optNullableString("mal")?.toIntOrNull(),
            anilist = value?.optNullableString("anilist")?.toIntOrNull(),
            kitsu = value?.optNullableString("kitsu")?.toIntOrNull(),
        )
    }

    private fun recommendations(array: JSONArray?, fallbackCategory: String): List<StreamCenterSimklRecommendation> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = ids(item.optJSONObject("ids"), null).simkl.takeIf { it > 0 } ?: continue
                val category = when (item.optNullableString("type")?.lowercase(Locale.ROOT)) {
                    "movie" -> "movies"
                    "show", "tv" -> "tv"
                    "anime" -> "anime"
                    else -> fallbackCategory
                }
                val title = item.optNullableString("title") ?: continue
                add(
                    StreamCenterSimklRecommendation(
                        title = title,
                        url = "https://simkl.com/$category/$id",
                        posterUrl = image(item.optNullableString("poster"), "posters", "_m"),
                        type = mediaType(category, item.optNullableString("anime_type")),
                    ),
                )
            }
        }
    }

    private fun alternativeTitles(array: JSONArray?): List<String> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                val title = when (value) {
                    is JSONObject -> value.optNullableString("name")
                    is String -> value.trim().takeIf(String::isNotBlank)
                    else -> null
                }
                title?.let(::add)
            }
        }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun strings(array: JSONArray?): List<String> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun names(array: JSONArray?): List<String> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                when (value) {
                    is JSONObject -> value.optNullableString("name")
                    is String -> value.trim().takeIf(String::isNotBlank)
                    else -> null
                }?.let(::add)
            }
        }
    }

    private fun actors(root: JSONObject): List<ActorData> {
        val entries = root.optJSONArray("cast")
            ?: root.optJSONObject("credits")?.optJSONArray("cast")
            ?: return emptyList()
        return buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val person = item.optJSONObject("person")
                val name = item.optNullableString("name")
                    ?: person?.optNullableString("name")
                    ?: continue
                val image = item.optNullableString("image")
                    ?: item.optNullableString("poster")
                    ?: person?.optNullableString("image")
                val role = item.optNullableString("character")
                    ?: item.optNullableString("role")
                add(ActorData(Actor(name, image), roleString = role))
            }
        }.distinctBy { it.actor.name.lowercase(Locale.ROOT) }
    }

    private fun trailer(array: JSONArray?): String? {
        val youtube = array?.optJSONObject(0)?.optNullableString("youtube") ?: return null
        return "https://www.youtube.com/watch?v=$youtube"
    }

    private fun runtime(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt().takeIf { it > 0 }
            is String -> RUNTIME.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            else -> null
        }
    }

    private fun image(path: String?, kind: String, size: String): String? {
        val value = path?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return "$IMAGE_BASE/$kind/$value$size.webp&q=90"
    }

    private fun mediaType(category: String, animeType: String?): TvType {
        return when (category) {
            "movies" -> TvType.Movie
            "tv" -> TvType.TvSeries
            else -> when (animeType?.lowercase(Locale.ROOT)) {
                "movie" -> TvType.AnimeMovie
                "ova", "ona", "special", "music" -> TvType.OVA
                else -> TvType.Anime
            }
        }
    }

    private fun mediaCategory(item: JSONObject): String {
        return when (item.optNullableString("type")?.lowercase(Locale.ROOT)) {
            "movie", "movies" -> "movies"
            "show", "tv", "series" -> "tv"
            "anime" -> "anime"
            else -> when {
                item.optJSONObject("ids")?.has("mal") == true -> "anime"
                item.optJSONObject("ids")?.has("anilist") == true -> "anime"
                else -> "tv"
            }
        }
    }

    companion object {
        private const val API_URL = "https://api.simkl.com"
        private const val DATA_URL = "https://data.simkl.in"
        private const val CLIENT_ID = "39f470a9f2ec1aa2383269ca831bc7be0e47da48d6d708ccad9bed4e1a60993e"
        private const val IMAGE_BASE = "https://wsrv.nl/?url=https://simkl.in"
        private const val PAGE_SIZE = 20
        private val MEDIA_CATEGORIES = setOf("movies", "tv", "anime")
        private val API_PARAMS = mapOf(
            "client_id" to CLIENT_ID,
            "app-name" to "cloudstream",
            "app-version" to BuildConfig.PLUGIN_VERSION,
        )
        private val HEADERS = mapOf("Accept" to "application/json", "User-Agent" to "CloudStream/StreamCenter")
        private val MEDIA_URL = Regex("simkl\\.com/(movies|tv|anime)/(\\d+)", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("\\b(?:18|19|20|21)\\d{2}\\b")
        private val RUNTIME = Regex("(\\d+)")
    }
}
