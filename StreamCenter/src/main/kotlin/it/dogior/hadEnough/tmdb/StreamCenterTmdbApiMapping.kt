package it.dogior.hadEnough.tmdb

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import it.dogior.hadEnough.StreamCenter.Companion.PLACEHOLDER_PROFILE_IMAGE
import it.dogior.hadEnough.anime.metadata.TmdbArtwork
import it.dogior.hadEnough.catalog.TmdbSeriesMetadata
import it.dogior.hadEnough.catalog.normalizeTmdbStatusTag
import it.dogior.hadEnough.util.cleanMetadataEpisodeTitle
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.cleanTmdbEpisodeDescription
import it.dogior.hadEnough.util.normalizeTrailerUrl
import it.dogior.hadEnough.util.parseMetadataDate
import it.dogior.hadEnough.util.youtubeTrailerUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

internal data class TmdbApiSearchItem(
    val type: String,
    val id: Int,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val vote: Double?,
    val titles: List<String>,
)

internal data class TmdbApiEpisode(
    val season: Int,
    val episode: Int,
    val title: String?,
    val overview: String?,
    val airDate: String?,
    val still: String?,
    val runtime: Int?,
    val ratingPercent: Int?,
)

internal data class TmdbApiSeason(val number: Int, val name: String?, val poster: String?, val episodeCount: Int)

internal data class TmdbApiDetails(
    val title: String,
    val originalTitle: String?,
    val plot: String?,
    val poster: String?,
    val background: String?,
    val tags: List<String>,
    val genres: List<String>,
    val year: Int?,
    val score: String?,
    val people: List<ActorData>,
    val contentRating: String?,
    val status: String?,
    val duration: Int?,
    val trailerUrl: String?,
    val budget: String?,
    val revenue: String?,
    val seasons: List<TmdbApiSeason>,
)

internal object TmdbApiMapping {
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
    private const val MAX_CAST = 30
    private val ITALIAN = Locale.ITALIAN

    private val statusNames = mapOf(
        "returning series" to "Serie riproposta",
        "ended" to "Terminata",
        "canceled" to "Cancellata",
        "cancelled" to "Cancellata",
        "in production" to "In produzione",
        "planned" to "Pianificata",
        "pilot" to "Pilota",
        "released" to "Uscito",
        "post production" to "Post-produzione",
        "rumored" to "Rumored",
    )
    private val seriesTypes = mapOf(
        "scripted" to "Sceneggiato",
        "miniseries" to "Miniserie",
        "documentary" to "Documentario",
        "reality" to "Reality",
        "talk show" to "Talk show",
        "news" to "Notiziario",
        "video" to "Video",
    )
    private val crewJobs = linkedMapOf(
        "Director" to "Regista",
        "Screenplay" to "Sceneggiatura",
        "Writer" to "Scrittore",
        "Story" to "Soggetto",
        "Novel" to "Romanzo",
        "Characters" to "Personaggi",
    )

    fun image(path: String?, size: String = "original"): String? =
        path?.trim()?.takeIf { it.startsWith("/") }?.let { "$IMAGE_BASE$size$it" }

    fun italianStatus(status: String?): String? =
        status?.trim()?.takeIf(String::isNotEmpty)?.let { statusNames[it.lowercase(Locale.ROOT)] ?: it }

    fun details(json: JSONObject, isTv: Boolean): TmdbApiDetails {
        val title = text(json, if (isTv) "name" else "title").orEmpty()
        val originalTitle = text(json, if (isTv) "original_name" else "original_title")
        val status = italianStatus(text(json, "status"))
        val genres = array(json, "genres").mapNotNull { text(it, "name") }.distinctBy { it.lowercase(Locale.ROOT) }
        val originalLanguage = text(json, "original_language")?.let(::languageName)
        val originalTitleTag = originalTitle?.takeIf { !it.equals(title, ignoreCase = true) }
        val budget = money(json.optLong("budget"))
        val revenue = money(json.optLong("revenue"))
        val tags = if (isTv) {
            buildList {
                originalTitleTag?.let { add("Nome originale: $it") }
                array(json, "networks").mapNotNull { text(it, "name") }
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .joinToString(", ")
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
                status?.let { add(normalizeTmdbStatusTag("Stato: $it")) }
                text(json, "type")?.let { add("Tipo: ${seriesTypes[it.lowercase(Locale.ROOT)] ?: it}") }
                originalLanguage?.let { add("Lingua originale: $it") }
                addAll(genres)
            }
        } else {
            buildList {
                originalTitleTag?.let { add("Titolo originale: $it") }
                originalLanguage?.let { add("Lingua originale: $it") }
                budget?.let { add("Budget: $it") }
                revenue?.let { add("Incasso: $it") }
                addAll(genres)
            }
        }.distinctBy { it.lowercase(Locale.ROOT) }
        val runtime = if (isTv) {
            TmdbSeriesMetadata.episodeRuntime(array(json, "episode_run_time", JSONArray::optInt))
        } else {
            json.optInt("runtime").takeIf { it > 0 }
        }
        return TmdbApiDetails(
            title = title,
            originalTitle = originalTitle,
            plot = cleanText(text(json, "overview")),
            poster = image(text(json, "poster_path"), "w500"),
            background = image(text(json, "backdrop_path"), "w1280"),
            tags = tags,
            genres = genres,
            year = year(text(json, if (isTv) "first_air_date" else "release_date")),
            score = score(json),
            people = (cast(json) + crew(json, isTv)).distinct(),
            contentRating = contentRating(json, isTv),
            status = status,
            duration = runtime,
            trailerUrl = trailer(json.optJSONObject("videos")),
            budget = budget,
            revenue = revenue,
            seasons = array(json, "seasons").mapNotNull { season ->
                val number = season.optInt("season_number", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                TmdbApiSeason(
                    number = number,
                    name = text(season, "name"),
                    poster = image(text(season, "poster_path"), "w500"),
                    episodeCount = season.optInt("episode_count"),
                )
            },
        )
    }

    fun trailer(videos: JSONObject?): String? {
        val results = array(videos, "results")
        val ordered = results.sortedWith(
            compareBy<JSONObject> {
                when (text(it, "type")) {
                    "Trailer" -> 0
                    "Teaser" -> 1
                    else -> 2
                }
            }.thenBy { if (it.optBoolean("official")) 0 else 1 },
        )
        return ordered.firstNotNullOfOrNull { video ->
            val key = text(video, "key") ?: return@firstNotNullOfOrNull null
            when (text(video, "site")?.lowercase(Locale.ROOT)) {
                "youtube" -> youtubeTrailerUrl(key)
                "dailymotion" -> normalizeTrailerUrl("https://www.dailymotion.com/video/$key")
                else -> null
            }
        }
    }

    fun logo(images: JSONObject?, preferredLanguages: List<String>): String? {
        val logos = array(images, "logos")
        if (logos.isEmpty()) return null
        val byLanguage = logos.groupBy { text(it, "iso_639_1")?.lowercase(Locale.ROOT) }
        val ordered = preferredLanguages.flatMap { byLanguage[it].orEmpty() } + byLanguage[null].orEmpty() + logos
        return ordered.firstNotNullOfOrNull { TmdbArtwork.imageUrl(image(text(it, "file_path"))) }
    }

    fun imdbId(json: JSONObject): String? =
        (text(json.optJSONObject("external_ids"), "imdb_id") ?: text(json, "imdb_id"))
            ?.takeIf { it.startsWith("tt") }

    fun watchProviders(json: JSONObject?): String? =
        array(json?.optJSONObject("results")?.optJSONObject("IT"), "flatrate")
            .mapNotNull { text(it, "provider_name") }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(", ")
            .takeIf(String::isNotBlank)

    fun episodes(season: JSONObject?): List<TmdbApiEpisode> {
        val fallbackSeason = season?.optInt("season_number", 0) ?: 0
        return array(season, "episodes").mapNotNull { episode ->
            val number = episode.optInt("episode_number", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            TmdbApiEpisode(
                season = episode.optInt("season_number", fallbackSeason),
                episode = number,
                title = cleanMetadataEpisodeTitle(text(episode, "name")),
                overview = cleanTmdbEpisodeDescription(text(episode, "overview")),
                airDate = parseMetadataDate(text(episode, "air_date")),
                still = image(text(episode, "still_path"), "w500"),
                runtime = episode.optInt("runtime").takeIf { it > 0 },
                ratingPercent = episode.optDouble("vote_average", 0.0)
                    .takeIf { it > 0.0 && episode.optInt("vote_count", 1) > 0 }
                    ?.times(10)
                    ?.roundToInt(),
            )
        }
    }

    fun searchItems(json: JSONObject?, forcedType: String? = null): List<TmdbApiSearchItem> =
        array(json, "results").mapNotNull { searchItem(it, forcedType) }

    fun searchItem(item: JSONObject, forcedType: String?): TmdbApiSearchItem? {
        val type = forcedType ?: text(item, "media_type")
        if (type != "movie" && type != "tv") return null
        val id = item.optInt("id").takeIf { it > 0 } ?: return null
        val title = text(item, if (type == "tv") "name" else "title") ?: return null
        return TmdbApiSearchItem(
            type = type,
            id = id,
            title = title,
            posterUrl = image(text(item, "poster_path"), "w500"),
            year = year(text(item, if (type == "tv") "first_air_date" else "release_date")),
            vote = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 && item.optInt("vote_count", 1) > 0 },
            titles = listOfNotNull(
                text(item, "name"), text(item, "title"), text(item, "original_name"), text(item, "original_title"),
            ).distinct(),
        )
    }

    private fun cast(json: JSONObject): List<ActorData> =
        array(json.optJSONObject("credits"), "cast").take(MAX_CAST).mapNotNull { person ->
            val name = text(person, "name") ?: return@mapNotNull null
            ActorData(
                Actor(name, image(text(person, "profile_path"), "w185")),
                roleString = text(person, "character"),
            )
        }

    private fun crew(json: JSONObject, isTv: Boolean): List<ActorData> {
        val roles = linkedMapOf<String, MutableList<String>>()
        val photos = mutableMapOf<String, String?>()
        if (isTv) {
            array(json, "created_by").forEach { person ->
                val name = text(person, "name") ?: return@forEach
                roles.getOrPut(name) { mutableListOf() } += "Ideatore"
                photos[name] = image(text(person, "profile_path"), "w185")
            }
        } else {
            array(json.optJSONObject("credits"), "crew").forEach { person ->
                val role = crewJobs[text(person, "job")] ?: return@forEach
                val name = text(person, "name") ?: return@forEach
                roles.getOrPut(name) { mutableListOf() }.apply { if (role !in this) add(role) }
                photos.putIfAbsent(name, image(text(person, "profile_path"), "w185"))
            }
        }
        return roles.map { (name, jobs) ->
            ActorData(Actor(name, photos[name] ?: PLACEHOLDER_PROFILE_IMAGE), roleString = jobs.joinToString(", "))
        }
    }

    private fun contentRating(json: JSONObject, isTv: Boolean): String? {
        val byCountry = if (isTv) {
            array(json.optJSONObject("content_ratings"), "results").associate {
                text(it, "iso_3166_1") to text(it, "rating")
            }
        } else {
            array(json.optJSONObject("release_dates"), "results").associate { country ->
                text(country, "iso_3166_1") to array(country, "release_dates").firstNotNullOfOrNull { text(it, "certification") }
            }
        }
        return byCountry["IT"] ?: byCountry["US"]
    }

    private fun score(json: JSONObject): String? {
        val average = json.optDouble("vote_average", 0.0)
        if (average <= 0.0 || json.optInt("vote_count", 1) <= 0) return null
        return String.format(Locale.US, "%.1f", average)
    }

    private fun money(value: Long): String? =
        value.takeIf { it > 0 }?.let { String.format(Locale.ITALY, "$%,.2f", it.toDouble()) }

    private fun languageName(code: String): String? =
        Locale(code).getDisplayLanguage(ITALIAN)
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(ITALIAN) }

    private fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()

    private fun text(json: JSONObject?, key: String): String? =
        json?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun array(json: JSONObject?, key: String): List<JSONObject> {
        val values = json?.optJSONArray(key) ?: return emptyList()
        return (0 until values.length()).mapNotNull(values::optJSONObject)
    }

    private fun <T> array(json: JSONObject?, key: String, read: JSONArray.(Int) -> T): List<T> {
        val values = json?.optJSONArray(key) ?: return emptyList()
        return (0 until values.length()).map { values.read(it) }
    }
}
