package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.anime.metadata.AniListMetadataClient
import it.dogior.hadEnough.model.AnilistLoadMetadata
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

internal class StreamCenterAniListCatalog(
    private val client: AniListMetadataClient,
    private val titlePreference: () -> String,
) : StreamCenterCatalog {
    private data class QueryOptions(
        val sort: List<String>,
        val status: String? = null,
        val currentSeason: Boolean = false,
    )

    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (page < 1) return StreamCenterCatalogPage(emptyList(), false)
        val options = when (section.key) {
            "anilist_trending" -> QueryOptions(listOf("TRENDING_DESC", "POPULARITY_DESC"))
            "anilist_popular" -> QueryOptions(listOf("POPULARITY_DESC", "SCORE_DESC"))
            "anilist_top" -> QueryOptions(listOf("SCORE_DESC", "POPULARITY_DESC"))
            "anilist_airing" -> QueryOptions(listOf("POPULARITY_DESC", "SCORE_DESC"), status = "RELEASING")
            "anilist_upcoming" -> QueryOptions(
                listOf("POPULARITY_DESC", "SCORE_DESC"),
                status = "NOT_YET_RELEASED",
            )
            "anilist_season" -> QueryOptions(
                listOf("POPULARITY_DESC", "SCORE_DESC"),
                currentSeason = true,
            )
            else -> return StreamCenterCatalogPage(emptyList(), false)
        }
        val variables = baseVariables(page, options.sort).apply {
            options.status?.let { put("status", it) }
            if (options.currentSeason) {
                val (season, seasonYear) = currentSeason()
                put("season", season)
                put("seasonYear", seasonYear)
            }
        }
        return catalogPage(api, variables, showScore)
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (query.isBlank() || page < 1) return StreamCenterCatalogPage(emptyList(), false)
        val variables = baseVariables(page, listOf("SEARCH_MATCH", "POPULARITY_DESC")).apply {
            put("search", query.trim())
        }
        return catalogPage(api, variables, showScore)
    }

    fun preferredTitle(metadata: AnilistLoadMetadata): String {
        val preferred = when (titlePreference()) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> metadata.titleEnglish
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> metadata.titleNative
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> metadata.titleRomaji
            else -> metadata.titleRomaji ?: metadata.titleEnglish
        }
        return preferred?.trim()?.takeIf(String::isNotBlank) ?: metadata.title
    }

    fun mediaId(url: String): Int? {
        return MEDIA_URL.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private suspend fun catalogPage(
        api: MainAPI,
        variables: JSONObject,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        val page = client.execute(CATALOG_QUERY, variables)?.optJSONObject("Page")
            ?: return StreamCenterCatalogPage(emptyList(), false)
        val media = page.optJSONArray("media") ?: JSONArray()
        val items = buildList {
            for (index in 0 until media.length()) {
                val entry = media.optJSONObject(index) ?: continue
                val id = entry.optNullableInt("id") ?: continue
                val title = preferredTitle(entry.optJSONObject("title")) ?: continue
                val format = entry.optNullableString("format")
                val type = mediaType(format)
                val url = entry.optNullableString("siteUrl") ?: "$BASE_URL/anime/$id"
                val poster = entry.optJSONObject("coverImage")?.let { image ->
                    image.optNullableString("extraLarge")
                        ?: image.optNullableString("large")
                        ?: image.optNullableString("medium")
                }
                val year = entry.optNullableInt("seasonYear")
                    ?: entry.optJSONObject("startDate")?.optNullableInt("year")
                val otherName = entry.optJSONObject("title")
                    ?.let { titles ->
                        listOfNotNull(
                            titles.optNullableString("romaji"),
                            titles.optNullableString("english"),
                            titles.optNullableString("native"),
                        ).firstOrNull { it != title }
                    }
                val score = if (showScore) {
                    entry.optNullableInt("averageScore")?.let { Score.from(it.toDouble(), 100) }
                } else {
                    null
                }
                add(api.newAnimeSearchResponse(title, url, type) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                    this.otherName = otherName
                })
            }
        }
        val hasNext = page.optJSONObject("pageInfo")?.optBoolean("hasNextPage", false) == true
        return StreamCenterCatalogPage(items, hasNext)
    }

    private fun preferredTitle(title: JSONObject?): String? {
        val romaji = title?.optNullableString("romaji")
        val english = title?.optNullableString("english")
        val native = title?.optNullableString("native")
        return when (titlePreference()) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> english ?: romaji ?: native
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> native ?: romaji ?: english
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> romaji ?: english ?: native
            else -> romaji ?: english ?: native
        }
    }

    private fun mediaType(format: String?): TvType {
        return when (format?.uppercase(Locale.ROOT)) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun baseVariables(page: Int, sort: List<String>): JSONObject {
        return JSONObject()
            .put("page", page)
            .put("perPage", PAGE_SIZE)
            .put("sort", JSONArray(sort))
    }

    private fun currentSeason(): Pair<String, Int> {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH) + 1
        val year = calendar.get(Calendar.YEAR)
        return when (month) {
            12 -> "WINTER" to year + 1
            1, 2 -> "WINTER" to year
            3, 4, 5 -> "SPRING" to year
            6, 7, 8 -> "SUMMER" to year
            else -> "FALL" to year
        }
    }

    companion object {
        private const val BASE_URL = "https://anilist.co"
        private const val PAGE_SIZE = 20
        private val MEDIA_URL = Regex("(?:anilist\\.co)?/anime/(\\d+)", RegexOption.IGNORE_CASE)

        private val CATALOG_QUERY = """
            query (
              ${'$'}page: Int,
              ${'$'}perPage: Int,
              ${'$'}sort: [MediaSort],
              ${'$'}status: MediaStatus,
              ${'$'}season: MediaSeason,
              ${'$'}seasonYear: Int,
              ${'$'}search: String
            ) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(
                  type: ANIME,
                  isAdult: false,
                  sort: ${'$'}sort,
                  status: ${'$'}status,
                  season: ${'$'}season,
                  seasonYear: ${'$'}seasonYear,
                  search: ${'$'}search
                ) {
                  id
                  idMal
                  siteUrl
                  format
                  episodes
                  averageScore
                  seasonYear
                  startDate { year }
                  title { romaji english native }
                  coverImage { extraLarge large medium }
                }
              }
            }
        """.trimIndent()
    }
}
