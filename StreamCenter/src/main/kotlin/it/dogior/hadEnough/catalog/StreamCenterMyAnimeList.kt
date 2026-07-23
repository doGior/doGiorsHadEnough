package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import it.dogior.hadEnough.util.mapChunkedParallel
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class StreamCenterMalEpisode(
    val number: Int,
    val title: String?,
    val airedDate: String?,
    val score: Score?,
)

internal data class StreamCenterMalRecommendation(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val type: TvType,
)

internal data class StreamCenterMalMedia(
    val id: Int,
    val url: String,
    val title: String,
    val englishTitle: String?,
    val japaneseTitle: String?,
    val synonyms: List<String>,
    val mediaType: String?,
    val totalEpisodes: Int?,
    val status: ShowStatus?,
    val comingSoon: Boolean,
    val aired: String?,
    val premiered: String?,
    val broadcast: String?,
    val source: String?,
    val studios: List<String>,
    val producers: List<String>,
    val licensors: List<String>,
    val genres: List<String>,
    val themes: List<String>,
    val demographics: List<String>,
    val duration: Int?,
    val contentRating: String?,
    val score: String?,
    val year: Int?,
    val posterUrl: String?,
    val synopsis: String?,
    val trailerUrl: String?,
    val characters: List<ActorData>,
    val recommendations: List<StreamCenterMalRecommendation>,
) {
    val titleCandidates: List<String>
        get() = listOfNotNull(title, englishTitle, japaneseTitle) + synonyms

    val type: TvType
        get() = when (mediaType?.lowercase(Locale.ROOT)) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special", "music" -> TvType.OVA
            else -> TvType.Anime
        }
}

internal class StreamCenterMyAnimeListCatalog(
    private val headers: Map<String, String>,
) : StreamCenterCatalog {
    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (page < 1) return StreamCenterCatalogPage(emptyList(), false)
        val separator = if ('?' in section.path) '&' else '?'
        val url = "$BASE_URL${section.path}${separator}limit=${(page - 1) * PAGE_SIZE}"
        val document = document(url)
        val items = rankingCards(api, document, showScore)
        val hasNext = document.selectFirst("a.link-blue-box.next, a.pagination_next") != null ||
            items.size >= PAGE_SIZE
        return StreamCenterCatalogPage(items, hasNext)
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (query.isBlank() || page != 1) return StreamCenterCatalogPage(emptyList(), false)
        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val response = app.get(
            "$BASE_URL/search/prefix.json?type=anime&keyword=$encodedQuery&v=1",
            headers = headers,
        ).text
        val items = searchCards(api, JSONObject(response), showScore)
        return StreamCenterCatalogPage(items, false)
    }

    fun mediaId(url: String): Int? {
        return animeId(url)
    }

    suspend fun media(url: String): StreamCenterMalMedia {
        val canonicalUrl = canonicalAnimeUrl(url)
        val document = document(canonicalUrl)
        val id = animeId(document.location()) ?: animeId(canonicalUrl)
            ?: throw IllegalArgumentException("Identificativo MyAnimeList non valido")
        val actualUrl = document.selectFirst("link[rel=canonical]")
            ?.attr("href")
            ?.takeIf { animeId(it) == id }
            ?: canonicalUrl
        val information = information(document)
        val title = text(document.selectFirst("h1.title-name strong, h1.title-name, span[itemprop=name]"))
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - MyAnimeList.net")
                ?.trim()
                ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Titolo MyAnimeList non trovato")
        val recommendations = runCatching {
            recommendationCards(document(actualUrl.trimEnd('/') + "/userrecs"), id)
        }.getOrElse { recommendationCards(document, id) }
        return StreamCenterMalMedia(
            id = id,
            url = actualUrl,
            title = title,
            englishTitle = information["English"],
            japaneseTitle = information["Japanese"],
            synonyms = listValue(information["Synonyms"]),
            mediaType = information["Type"],
            totalEpisodes = firstNumber(information["Episodes"]),
            status = showStatus(information["Status"]),
            comingSoon = information["Status"]?.contains("not yet aired", ignoreCase = true) == true,
            aired = information["Aired"],
            premiered = information["Premiered"],
            broadcast = information["Broadcast"],
            source = information["Source"],
            studios = listValue(information["Studios"]),
            producers = listValue(information["Producers"]),
            licensors = listValue(information["Licensors"]),
            genres = listValue(information["Genres"]),
            themes = listValue(information["Themes"]),
            demographics = listValue(information["Demographic"]),
            duration = durationMinutes(information["Duration"]),
            contentRating = information["Rating"],
            score = score(document),
            year = year(information["Premiered"] ?: information["Aired"]),
            posterUrl = poster(document),
            synopsis = synopsis(document),
            trailerUrl = trailer(document),
            characters = characters(document),
            recommendations = recommendations,
        )
    }

    suspend fun episodes(media: StreamCenterMalMedia): List<StreamCenterMalEpisode> {
        val episodeUrl = "${media.url.trimEnd('/')}/episode"
        val firstDocument = document("$episodeUrl?offset=0")
        val knownPageCount = media.totalEpisodes
            ?.takeIf { it > 0 }
            ?.let { ((it - 1) / EPISODE_PAGE_SIZE) + 1 }
            ?: 1
        val offsets = buildSet {
            add(0)
            repeat(knownPageCount) { page -> add(page * EPISODE_PAGE_SIZE) }
            firstDocument.select("a[href*=offset]").forEach { link ->
                OFFSET.find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()?.let(::add)
            }
        }.filter { it >= 0 }.sorted()
        val remainingEpisodes = offsets
            .filterNot { it == 0 }
            .mapChunkedParallel(EPISODE_REQUEST_CHUNK_SIZE) { page ->
                episodeRows(document("$episodeUrl?offset=$page"))
            }
            .flatten()
        return (episodeRows(firstDocument) + remainingEpisodes).distinctBy(StreamCenterMalEpisode::number)
            .sortedBy(StreamCenterMalEpisode::number)
    }

    private suspend fun document(url: String): Document {
        return Jsoup.parse(app.get(url, headers = headers).text, url)
    }

    private fun rankingCards(api: MainAPI, document: Document, showScore: Boolean): List<SearchResponse> {
        val seen = mutableSetOf<Int>()
        return document.select("tr.ranking-list, table.top-ranking-table tr").mapNotNull { row ->
            val link = row.selectFirst("td.title a[href*=/anime/], a.hoverinfo_trigger[href*=/anime/]")
                ?: return@mapNotNull null
            val url = absoluteUrl(link.attr("href")) ?: return@mapNotNull null
            val id = animeId(url) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = text(link)
                ?: row.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val information = text(row.selectFirst("div.information"))
            val mediaType = information?.substringBefore('(')?.trim()
            val type = animeType(mediaType)
            api.newAnimeSearchResponse(title, url, type) {
                posterUrl = row.selectFirst("img")?.let(::imageUrl)
                year = year(information)
                score = if (showScore) score(row) else null
            }
        }
    }

    private fun searchCards(api: MainAPI, root: JSONObject, showScore: Boolean): List<SearchResponse> {
        val categories = root.optJSONArray("categories") ?: return emptyList()
        val seen = mutableSetOf<Int>()
        return buildList {
            for (categoryIndex in 0 until categories.length()) {
                val items = categories.optJSONObject(categoryIndex)?.optJSONArray("items") ?: continue
                for (itemIndex in 0 until items.length()) {
                    val item = items.optJSONObject(itemIndex) ?: continue
                    val id = item.optInt("id").takeIf { it > 0 } ?: continue
                    if (!seen.add(id)) continue
                    val title = item.optString("name").trim().takeIf(String::isNotBlank) ?: continue
                    val url = item.optString("url").trim().takeIf(String::isNotBlank)
                        ?.let(::absoluteUrl)
                        ?: "$BASE_URL/anime/$id"
                    val payload = item.optJSONObject("payload")
                    val type = animeType(payload?.optString("media_type"))
                    add(api.newAnimeSearchResponse(title, url, type) {
                        posterUrl = imageUrl(item.optString("image_url"))
                        year = payload?.optInt("start_year")?.takeIf { it > 0 }
                            ?: year(payload?.optString("aired"))
                        score = if (showScore) score(payload?.opt("score")?.toString()) else null
                    })
                }
            }
        }
    }

    private fun information(document: Document): Map<String, String> {
        return document.select("div.spaceit_pad").mapNotNull { row ->
            val label = row.selectFirst("span.dark_text") ?: return@mapNotNull null
            val key = label.text().trim().removeSuffix(":")
            if (key.isBlank()) return@mapNotNull null
            val value = row.clone().also { clone ->
                clone.select("span.dark_text").remove()
                clone.select("br").append(" ")
            }.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun characters(document: Document): List<ActorData> {
        return document.select("div.detail-characters-list table").mapNotNull { table ->
            val link = table.selectFirst("a[href*=/character/]") ?: return@mapNotNull null
            val name = text(link) ?: return@mapNotNull null
            val image = table.selectFirst("img")?.let(::imageUrl)
            val roleText = table.select("small").asSequence().mapNotNull(::text).firstOrNull()
            val role = when {
                roleText?.contains("main", ignoreCase = true) == true -> ActorRole.Main
                roleText?.contains("supporting", ignoreCase = true) == true -> ActorRole.Supporting
                else -> null
            }
            ActorData(Actor(name, image), role = role, roleString = roleText)
        }.distinctBy { it.actor.name.lowercase(Locale.ROOT) }
    }

    private fun recommendationCards(document: Document, currentId: Int): List<StreamCenterMalRecommendation> {
        val containers = document.select(
            "div.anime_recommendation, div.detail-user-recs-text, div.js-scrollfix-bottom-rel > table, " +
                "table.anime_detail_related_anime",
        )
        val links = if (containers.isEmpty()) {
            document.select("a[href*=/anime/]")
        } else {
            containers.select("a[href*=/anime/]")
        }
        val seen = mutableSetOf<Int>()
        return links.mapNotNull { link ->
            val url = absoluteUrl(link.attr("href")) ?: return@mapNotNull null
            val id = animeId(url) ?: return@mapNotNull null
            if (id == currentId || !seen.add(id)) return@mapNotNull null
            val image = link.selectFirst("img")
            val title = text(link)
                ?: image?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: link.attr("title").trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            StreamCenterMalRecommendation(
                title = title,
                url = url,
                posterUrl = image?.let(::imageUrl),
                type = animeType(null),
            )
        }.take(RECOMMENDATIONS_LIMIT)
    }

    private fun episodeRows(document: Document): List<StreamCenterMalEpisode> {
        return document.select("tr.episode-list-data, table.episode_list tr").mapNotNull { row ->
            val link = row.selectFirst("a[href*=/episode/]") ?: return@mapNotNull null
            val number = EPISODE_PATH.find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: firstNumber(text(row.selectFirst("td.episode-number")))
                ?: return@mapNotNull null
            val title = text(row.selectFirst("td.episode-title a, td.episode-title"))
                ?.removePrefix("Episode $number")
                ?.trim('-', ' ', ':')
                ?.takeIf(String::isNotBlank)
            val aired = parseDate(text(row.selectFirst("td.episode-aired")))
            val episodeScore = score(text(row.selectFirst("td.episode-poll")), 5)
            StreamCenterMalEpisode(number, title, aired, episodeScore)
        }
    }

    private fun canonicalAnimeUrl(url: String): String {
        val absolute = absoluteUrl(url) ?: throw IllegalArgumentException("URL MyAnimeList non valido")
        val match = ANIME_PATH.find(absolute) ?: throw IllegalArgumentException("URL MyAnimeList non valido")
        return "$BASE_URL/anime/${match.groupValues[1]}${match.groupValues[2]}"
    }

    private fun animeId(url: String): Int? {
        return ANIME_PATH.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun animeType(value: String?): TvType {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special", "music" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun showStatus(value: String?): ShowStatus? {
        val normalized = value?.lowercase(Locale.ROOT) ?: return null
        return when {
            "currently airing" in normalized -> ShowStatus.Ongoing
            "finished airing" in normalized -> ShowStatus.Completed
            else -> null
        }
    }

    private fun score(element: Element): Score? {
        val value = sequenceOf(
            element.selectFirst("span[itemprop=ratingValue]")?.text(),
            element.selectFirst("span.score-label")?.text(),
            element.selectFirst("[data-score]")?.attr("data-score"),
        ).firstNotNullOfOrNull { score(it) }
        return value
    }

    private fun score(value: String?, maximum: Int = 10): Score? {
        val number = value?.let { NUMBER.find(it)?.value }
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: return null
        return Score.from(number, maximum)
    }

    private fun score(document: Document): String? {
        return text(document.selectFirst("span[itemprop=ratingValue], div.score-label"))
            ?.let { NUMBER.find(it)?.value }
    }

    private fun synopsis(document: Document): String? {
        return text(document.selectFirst("p[itemprop=description], span[itemprop=description]"))
            ?: document.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf(String::isNotBlank)
    }

    private fun poster(document: Document): String? {
        return document.selectFirst("div.leftside img[itemprop=image], img[itemprop=image]")?.let(::imageUrl)
            ?: imageUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))
    }

    private fun trailer(document: Document): String? {
        val element: Element = document.selectFirst(
            "a[href*=youtube.com/watch], a[href*=youtu.be/], iframe[src*=youtube.com/embed]",
        ) ?: return null
        val value: String = element.attr("href").ifBlank { element.attr("src") }
            .trim()
            .takeIf(String::isNotBlank) ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$BASE_URL$value"
            else -> value
        }
    }

    private fun durationMinutes(value: String?): Int? {
        val hours = HOURS.find(value.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = MINUTES.find(value.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun parseDate(value: String?): String? {
        val match = DATE.find(value.orEmpty()) ?: return null
        val month = MONTHS[match.groupValues[1].lowercase(Locale.ROOT)] ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val year = match.groupValues[3].toIntOrNull() ?: return null
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun listValue(value: String?): List<String> {
        val normalized = value?.trim()?.takeUnless { it.equals("None found", ignoreCase = true) } ?: return emptyList()
        return normalized.split(',').mapNotNull { it.trim().takeIf(String::isNotBlank) }
    }

    private fun firstNumber(value: String?): Int? {
        return value?.let { INTEGER.find(it)?.value }?.toIntOrNull()
    }

    private fun year(value: String?): Int? {
        return value?.let { YEAR.find(it)?.value }?.toIntOrNull()
    }

    private fun text(element: Element?): String? {
        return element?.text()?.replace(WHITESPACE, " ")?.trim()?.takeIf(String::isNotBlank)
    }

    private fun imageUrl(image: Element): String? {
        val srcset = image.attr("data-srcset").ifBlank { image.attr("srcset") }
            .split(',')
            .lastOrNull()
            ?.trim()
            ?.substringBefore(' ')
        return imageUrl(
            sequenceOf(image.attr("data-src"), image.attr("data-original"), srcset, image.attr("src"))
                .firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:") },
        )
    }

    private fun imageUrl(value: String?): String? {
        val source = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val absolute = when {
            source.startsWith("//") -> "https:$source"
            source.startsWith("/") -> "$BASE_URL$source"
            else -> source
        }
        return absolute.replace(RESIZED_IMAGE_PATH, "/").substringBefore('?')
    }

    private fun absoluteUrl(value: String): String? {
        val url = value.trim().takeIf(String::isNotBlank) ?: return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$BASE_URL$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> null
        }
    }

    companion object {
        private const val BASE_URL = "https://myanimelist.net"
        private const val PAGE_SIZE = 50
        private const val EPISODE_PAGE_SIZE = 100
        private const val EPISODE_REQUEST_CHUNK_SIZE = 3
        private const val RECOMMENDATIONS_LIMIT = 20
        private val ANIME_PATH = Regex("/anime/(\\d+)(/[^?#]*)?", RegexOption.IGNORE_CASE)
        private val EPISODE_PATH = Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
        private val OFFSET = Regex("[?&]offset=(\\d+)", RegexOption.IGNORE_CASE)
        private val RESIZED_IMAGE_PATH = Regex("/r/\\d+x\\d+/")
        private val NUMBER = Regex("\\d+(?:[.,]\\d+)?")
        private val INTEGER = Regex("\\d+")
        private val YEAR = Regex("\\b(?:19|20|21)\\d{2}\\b")
        private val HOURS = Regex("(\\d+)\\s*hr", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE)
        private val DATE = Regex("([A-Za-z]{3})\\s+(\\d{1,2}),\\s+(\\d{4})")
        private val WHITESPACE = Regex("\\s+")
        private val MONTHS = mapOf(
            "jan" to 1,
            "feb" to 2,
            "mar" to 3,
            "apr" to 4,
            "may" to 5,
            "jun" to 6,
            "jul" to 7,
            "aug" to 8,
            "sep" to 9,
            "oct" to 10,
            "nov" to 11,
            "dec" to 12,
        )
    }
}
