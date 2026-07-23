package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.anime.metadata.KitsuMetadataClient
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class StreamCenterKitsuRecommendation(
    val id: Int,
    val title: String,
    val subtype: String?,
    val posterUrl: String?,
)

internal data class StreamCenterKitsuMedia(
    val id: Int,
    val url: String,
    val title: String,
    val englishTitle: String?,
    val romajiTitle: String?,
    val nativeTitle: String?,
    val abbreviatedTitles: List<String>,
    val subtype: String?,
    val episodeCount: Int?,
    val episodeLength: Int?,
    val totalLength: Int?,
    val status: String?,
    val startDate: String?,
    val endDate: String?,
    val ageRating: String?,
    val ageRatingGuide: String?,
    val score: String?,
    val year: Int?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val synopsis: String?,
    val trailerUrl: String?,
    val categories: List<String>,
    val characters: List<ActorData>,
    val recommendations: List<StreamCenterKitsuRecommendation>,
    val anilistId: Int?,
    val malId: Int?,
) {
    val type: TvType
        get() = when (subtype?.lowercase(Locale.ROOT)) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special", "music" -> TvType.OVA
            else -> TvType.Anime
        }

    val duration: Int?
        get() = if (type == TvType.AnimeMovie) totalLength ?: episodeLength else episodeLength

    val contentRating: String?
        get() = listOfNotNull(ageRating, ageRatingGuide).joinToString(" - ").takeIf(String::isNotBlank)

    val showStatus: ShowStatus?
        get() = when (status?.lowercase(Locale.ROOT)) {
            "current" -> ShowStatus.Ongoing
            "finished" -> ShowStatus.Completed
            else -> null
        }

    val comingSoon: Boolean
        get() = status?.lowercase(Locale.ROOT) in setOf("upcoming", "tba", "unreleased")

    val titleCandidates: List<String>
        get() = (listOfNotNull(title, englishTitle, romajiTitle, nativeTitle) + abbreviatedTitles)
            .distinctBy { it.lowercase(Locale.ROOT) }
}

internal class StreamCenterKitsuCatalog(
    private val client: KitsuMetadataClient,
    private val titlePreference: () -> String,
) : StreamCenterCatalog {
    private data class QueryOptions(
        val sort: String,
        val status: String? = null,
    )

    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (page < 1) return StreamCenterCatalogPage(emptyList(), false)
        val options = when (section.key) {
            "kitsu_popular" -> QueryOptions("popularityRank")
            "kitsu_top" -> QueryOptions("ratingRank")
            "kitsu_airing" -> QueryOptions("popularityRank", "current")
            "kitsu_upcoming" -> QueryOptions("startDate", "upcoming")
            "kitsu_favorite" -> QueryOptions("-favoritesCount")
            else -> return StreamCenterCatalogPage(emptyList(), false)
        }
        return catalogPage(api, page, showScore, options.sort, options.status, null)
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (query.isBlank() || page < 1) return StreamCenterCatalogPage(emptyList(), false)
        return catalogPage(api, page, showScore, null, null, query.trim())
    }

    fun mediaId(url: String): Int? {
        return MEDIA_URL.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun preferredTitle(media: StreamCenterKitsuMedia): String {
        return preferredTitle(
            canonical = media.title,
            english = media.englishTitle,
            romaji = media.romajiTitle,
            native = media.nativeTitle,
        ) ?: media.title
    }

    suspend fun media(url: String): StreamCenterKitsuMedia = coroutineScope {
        val id = mediaId(url) ?: throw IllegalArgumentException("Identificativo Kitsu non valido")
        val mediaDeferred = async(Dispatchers.IO) { client.request("anime/$id?include=categories") }
        val charactersDeferred = async(Dispatchers.IO) {
            client.request("anime/$id/characters?include=character&page%5Blimit%5D=$PAGE_SIZE")
        }
        val recommendationsDeferred = async(Dispatchers.IO) {
            client.request(
                "anime/$id/media-relationships?include=destination&page%5Blimit%5D=$PAGE_SIZE",
            )
        }
        val mappingsDeferred = async(Dispatchers.IO) {
            client.request("anime/$id/mappings?page%5Blimit%5D=$PAGE_SIZE")
        }
        val root = mediaDeferred.await() ?: error("Metadati Kitsu non trovati")
        val data = root.optJSONObject("data") ?: error("Metadati Kitsu non validi")
        val attributes = data.optJSONObject("attributes") ?: error("Attributi Kitsu non trovati")
        val titles = attributes.optJSONObject("titles")
        val canonicalTitle = attributes.optNullableString("canonicalTitle")
            ?: error("Titolo Kitsu non trovato")
        val englishTitle = titles?.optNullableString("en") ?: titles?.optNullableString("en_us")
        val romajiTitle = titles?.optNullableString("en_jp") ?: canonicalTitle
        val nativeTitle = titles?.optNullableString("ja_jp")
        val startDate = attributes.optNullableString("startDate")
        val mappings = mappingsDeferred.await()?.let(::externalIds).orEmpty()
        StreamCenterKitsuMedia(
            id = id,
            url = "$BASE_URL/anime/$id",
            title = canonicalTitle,
            englishTitle = englishTitle,
            romajiTitle = romajiTitle,
            nativeTitle = nativeTitle,
            abbreviatedTitles = strings(attributes.optJSONArray("abbreviatedTitles")),
            subtype = attributes.optNullableString("subtype"),
            episodeCount = attributes.optNullableInt("episodeCount"),
            episodeLength = attributes.optNullableInt("episodeLength"),
            totalLength = attributes.optNullableInt("totalLength"),
            status = attributes.optNullableString("status"),
            startDate = startDate,
            endDate = attributes.optNullableString("endDate"),
            ageRating = attributes.optNullableString("ageRating"),
            ageRatingGuide = attributes.optNullableString("ageRatingGuide"),
            score = attributes.optNullableString("averageRating"),
            year = startDate?.take(4)?.toIntOrNull(),
            posterUrl = image(attributes.optJSONObject("posterImage")),
            backgroundUrl = image(attributes.optJSONObject("coverImage")),
            synopsis = cleanText(
                attributes.optNullableString("synopsis") ?: attributes.optNullableString("description"),
            ),
            trailerUrl = attributes.optNullableString("youtubeVideoId")
                ?.let { "https://www.youtube.com/watch?v=$it" },
            categories = categories(root),
            characters = characters(charactersDeferred.await()),
            recommendations = recommendations(recommendationsDeferred.await()),
            anilistId = mappings[ANILIST_SITE],
            malId = mappings[MAL_SITE],
        )
    }

    private suspend fun catalogPage(
        api: MainAPI,
        page: Int,
        showScore: Boolean,
        sort: String?,
        status: String?,
        query: String?,
    ): StreamCenterCatalogPage {
        val parameters = buildList {
            query?.let {
                add("filter%5Btext%5D=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}")
            }
            status?.let { add("filter%5Bstatus%5D=$it") }
            sort?.let { add("sort=$it") }
            add("page%5Blimit%5D=$PAGE_SIZE")
            add("page%5Boffset%5D=${(page - 1) * PAGE_SIZE}")
        }
        val root = client.request("anime?${parameters.joinToString("&")}")
            ?: return StreamCenterCatalogPage(emptyList(), false)
        val data = root.optJSONArray("data") ?: JSONArray()
        val items = buildList {
            for (index in 0 until data.length()) {
                val entry = data.optJSONObject(index) ?: continue
                val id = entry.optNullableString("id")?.toIntOrNull() ?: continue
                val attributes = entry.optJSONObject("attributes") ?: continue
                if (attributes.optBoolean("nsfw", false)) continue
                val titles = attributes.optJSONObject("titles")
                val canonical = attributes.optNullableString("canonicalTitle") ?: continue
                val title = preferredTitle(
                    canonical = canonical,
                    english = titles?.optNullableString("en") ?: titles?.optNullableString("en_us"),
                    romaji = titles?.optNullableString("en_jp"),
                    native = titles?.optNullableString("ja_jp"),
                ) ?: continue
                val subtype = attributes.optNullableString("subtype")
                val otherName = listOfNotNull(
                    titles?.optNullableString("en") ?: titles?.optNullableString("en_us"),
                    titles?.optNullableString("en_jp"),
                    titles?.optNullableString("ja_jp"),
                    canonical,
                ).firstOrNull { it != title }
                val score = if (showScore) {
                    attributes.optNullableString("averageRating")
                        ?.toDoubleOrNull()
                        ?.takeIf { it > 0.0 }
                        ?.let { Score.from(it, 100) }
                } else {
                    null
                }
                add(api.newAnimeSearchResponse(title, "$BASE_URL/anime/$id", mediaType(subtype)) {
                    posterUrl = image(attributes.optJSONObject("posterImage"))
                    year = attributes.optNullableString("startDate")?.take(4)?.toIntOrNull()
                    this.score = score
                    this.otherName = otherName
                })
            }
        }
        val hasNext = root.optJSONObject("links")?.optNullableString("next") != null
        return StreamCenterCatalogPage(items, hasNext)
    }

    private fun preferredTitle(
        canonical: String,
        english: String?,
        romaji: String?,
        native: String?,
    ): String? {
        return when (titlePreference()) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> english ?: romaji ?: native ?: canonical
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> native ?: romaji ?: english ?: canonical
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> romaji ?: canonical
            else -> romaji ?: canonical
        }.trim().takeIf(String::isNotBlank)
    }

    private fun categories(root: JSONObject): List<String> {
        val included = root.optJSONArray("included") ?: return emptyList()
        return buildList {
            for (index in 0 until included.length()) {
                val item = included.optJSONObject(index) ?: continue
                if (!item.optString("type").equals("categories", ignoreCase = true)) continue
                val attributes = item.optJSONObject("attributes") ?: continue
                val title = attributes.optNullableString("title")
                    ?: attributes.optNullableString("name")
                    ?: attributes.optNullableString("slug")
                    ?: continue
                add(title)
            }
        }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun characters(root: JSONObject?): List<ActorData> {
        root ?: return emptyList()
        val included = includedById(root, "characters")
        val data = root.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val relation = data.optJSONObject(index) ?: continue
                val characterId = relation.optJSONObject("relationships")
                    ?.optJSONObject("character")
                    ?.optJSONObject("data")
                    ?.optNullableString("id")
                    ?: continue
                val attributes = included[characterId]?.optJSONObject("attributes") ?: continue
                val name = attributes.optNullableString("canonicalName") ?: continue
                val roleText = relation.optJSONObject("attributes")?.optNullableString("role")
                val role = when (roleText?.lowercase(Locale.ROOT)) {
                    "main" -> ActorRole.Main
                    "supporting" -> ActorRole.Supporting
                    else -> null
                }
                add(
                    ActorData(
                        Actor(name, image(attributes.optJSONObject("image"))),
                        role = role,
                        roleString = roleText,
                    ),
                )
            }
        }.distinctBy { it.actor.name.lowercase(Locale.ROOT) }
    }

    private fun recommendations(root: JSONObject?): List<StreamCenterKitsuRecommendation> {
        root ?: return emptyList()
        val included = includedById(root, "anime")
        val data = root.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val relation = data.optJSONObject(index) ?: continue
                val destinationId = relation.optJSONObject("relationships")
                    ?.optJSONObject("destination")
                    ?.optJSONObject("data")
                    ?.optNullableString("id")
                    ?: continue
                val destination = included[destinationId] ?: continue
                val id = destinationId.toIntOrNull() ?: continue
                val attributes = destination.optJSONObject("attributes") ?: continue
                val titles = attributes.optJSONObject("titles")
                val canonical = attributes.optNullableString("canonicalTitle") ?: continue
                val title = preferredTitle(
                    canonical = canonical,
                    english = titles?.optNullableString("en") ?: titles?.optNullableString("en_us"),
                    romaji = titles?.optNullableString("en_jp"),
                    native = titles?.optNullableString("ja_jp"),
                ) ?: continue
                add(
                    StreamCenterKitsuRecommendation(
                        id = id,
                        title = title,
                        subtype = attributes.optNullableString("subtype"),
                        posterUrl = image(attributes.optJSONObject("posterImage")),
                    ),
                )
            }
        }.distinctBy(StreamCenterKitsuRecommendation::id)
    }

    private fun externalIds(root: JSONObject): Map<String, Int> {
        val data = root.optJSONArray("data") ?: return emptyMap()
        return buildMap {
            for (index in 0 until data.length()) {
                val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
                val site = attributes.optNullableString("externalSite") ?: continue
                val id = attributes.optNullableString("externalId")?.toIntOrNull() ?: continue
                put(site.lowercase(Locale.ROOT), id)
            }
        }
    }

    private fun includedById(root: JSONObject, type: String): Map<String, JSONObject> {
        val included = root.optJSONArray("included") ?: return emptyMap()
        return buildMap {
            for (index in 0 until included.length()) {
                val item = included.optJSONObject(index) ?: continue
                if (!item.optString("type").equals(type, ignoreCase = true)) continue
                val id = item.optNullableString("id") ?: continue
                put(id, item)
            }
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun image(value: JSONObject?): String? {
        return value?.optNullableString("original")
            ?: value?.optNullableString("large")
            ?: value?.optNullableString("medium")
            ?: value?.optNullableString("small")
            ?: value?.optNullableString("tiny")
    }

    private fun mediaType(subtype: String?): TvType {
        return when (subtype?.lowercase(Locale.ROOT)) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special", "music" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    companion object {
        private const val BASE_URL = "https://kitsu.io"
        private const val PAGE_SIZE = 20
        private const val ANILIST_SITE = "anilist/anime"
        private const val MAL_SITE = "myanimelist/anime"
        private val MEDIA_URL = Regex(
            """(?:https?://)?(?:www\.)?kitsu\.(?:io|app)/anime/(\d+)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
