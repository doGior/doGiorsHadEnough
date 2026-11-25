package it.dogior.hadEnough.anime.source

import it.dogior.hadEnough.StreamCenterAnimeArchiveFilters
import it.dogior.hadEnough.util.objects
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import org.jsoup.Jsoup
import java.net.URLEncoder

internal object AnimeUnityArchiveCatalog {
    const val ORDER_POPULARITY = "Popolarità"
    const val ORDER_LATEST = "Più recenti"
    const val STATUS_UPCOMING = "In Uscita"

    val genres: List<Pair<Int, String>> = listOf(
        1 to "Action", 2 to "Adventure", 3 to "Avant Garde", 4 to "Boys Love", 5 to "Comedy",
        6 to "Demons", 7 to "Drama", 8 to "Ecchi", 9 to "Fantasy", 10 to "Game",
        11 to "Girls Love", 12 to "Gore", 13 to "Gourmet", 14 to "Harem", 15 to "Hentai",
        16 to "Historical", 17 to "Horror", 18 to "Isekai", 19 to "Josei", 20 to "Kids",
        21 to "Mahou Shoujo", 22 to "Martial Arts", 23 to "Mecha", 24 to "Military", 25 to "Music",
        26 to "Mystery", 27 to "Parody", 28 to "Police", 29 to "Psychological", 30 to "Racing",
        31 to "Reincarnation", 32 to "Romance", 33 to "Samurai", 34 to "School", 35 to "Sci-fi",
        36 to "Seinen", 37 to "Shoujo", 38 to "Shounen", 39 to "Slice of Life", 40 to "Space",
        41 to "Sports", 42 to "Super Power", 43 to "Supernatural", 44 to "Survival", 45 to "Thriller",
        46 to "Vampire",
    )

    val orders = linkedMapOf(
        ORDER_LATEST to "latest",
        "A-Z" to "title_az",
        "Z-A" to "title_za",
        ORDER_POPULARITY to "views",
        "Valutazione" to "score",
    )

    val statuses = linkedMapOf(
        "In Corso" to "ongoing",
        "Terminato" to "completed",
        STATUS_UPCOMING to "upcoming",
        "Droppato" to "dropped",
    )

    val types = linkedMapOf(
        "TV" to "tv",
        "TV Short" to "tv_short",
        "OVA" to "ova",
        "ONA" to "ona",
        "Special" to "special",
        "Movie" to "movie",
    )

    val seasons = linkedMapOf(
        "Inverno" to "winter",
        "Primavera" to "spring",
        "Estate" to "summer",
        "Autunno" to "fall",
    )

    val countries = linkedMapOf(
        "Giappone" to "JP",
        "Cina" to "CN",
        "Corea del Sud" to "KR",
        "Taiwan" to "TW",
        "Stati Uniti" to "US",
    )

    val sources = linkedMapOf(
        "Originale" to "original",
        "Manga" to "manga",
        "Light novel" to "light_novel",
        "Web novel" to "web_novel",
        "Romanzo" to "novel",
        "Visual novel" to "visual_novel",
        "Videogioco" to "video_game",
        "Gioco" to "game",
        "Fumetto" to "comic",
        "Doujinshi" to "doujinshi",
        "Libro illustrato" to "picture_book",
        "Live action" to "live_action",
        "Anime" to "anime",
        "Progetto multimediale" to "multimedia_project",
        "Altro" to "other",
    )

    @Volatile
    private var studiosCache: List<Pair<Int, String>>? = null

    fun studios(baseUrl: String, userAgent: String): List<Pair<Int, String>> {
        studiosCache?.let { return it }
        val url = "${baseUrl.trimEnd('/')}/archive"
        val html = Jsoup.connect(url).userAgent(userAgent).timeout(15_000).execute().body()
        val studios = animeUnityPageProps(html, url)?.optJSONArray("animeStudios").objects()
            .mapNotNull { studio ->
                val id = studio.optNullableInt("id") ?: return@mapNotNull null
                val name = studio.optNullableString("name")?.takeUnless { it.all { char -> char == '?' } }
                    ?: return@mapNotNull null
                id to name
            }
            .sortedBy { it.second.lowercase() }
        check(studios.isNotEmpty()) { "Elenco studi AnimeUnity vuoto" }
        studiosCache = studios
        return studios
    }

    private val legacyGenreIds = mapOf(
        51 to 1, 21 to 2, 43 to 3, 59 to 4, 37 to 5, 13 to 6, 22 to 7, 5 to 8, 9 to 9, 44 to 10,
        58 to 11, 52 to 12, 56 to 13, 15 to 14, 4 to 15, 30 to 16, 3 to 17, 53 to 18, 45 to 19, 14 to 20,
        57 to 21, 31 to 22, 38 to 23, 46 to 24, 16 to 25, 24 to 26, 32 to 27, 39 to 28, 47 to 29, 29 to 30,
        54 to 31, 17 to 32, 25 to 33, 33 to 34, 40 to 35, 49 to 36, 18 to 37, 34 to 38, 50 to 39, 19 to 40,
        27 to 41, 35 to 42, 42 to 43, 55 to 44, 48 to 45, 20 to 46,
    )

    fun genreFromLegacyId(legacyId: Int): Int? = legacyGenreIds[legacyId]

    fun query(filters: StreamCenterAnimeArchiveFilters, title: String = "", page: Int = 1): String {
        val params = buildList {
            title.trim().takeIf(String::isNotBlank)?.let { add("query" to it) }
            filters.order?.let(orders::get)?.let { add("sort_by" to it) }
            filters.status?.let(statuses::get)?.let { add("status" to it) }
            filters.type?.let(types::get)?.let { add("type" to it) }
            filters.season?.let(seasons::get)?.let { add("season" to it) }
            filters.year?.let { add("year_from" to it.toString()) }
            (filters.yearTo ?: filters.year)?.let { add("year_to" to it.toString()) }
            filters.country?.let(countries::get)?.let { add("country" to it) }
            filters.source?.let(sources::get)?.let { add("source" to it) }
            if (filters.dubbed) add("language" to ANIME_UNITY_DUB_LANGUAGE)
            filters.selectedGenreIds.forEach { add("genres[]" to it.toString()) }
            filters.selectedExcludedGenreIds.forEach { add("genres_exclude[]" to it.toString()) }
            filters.studios.keys.forEach { add("studio[]" to it.toString()) }
            if (page > 1) add("page" to page.toString())
        }
        return params.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
