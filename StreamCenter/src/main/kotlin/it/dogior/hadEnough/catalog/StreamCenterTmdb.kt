package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import it.dogior.hadEnough.util.mapChunkedParallel
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class StreamCenterTmdbCatalog(
    private val headers: Map<String, String>,
) : StreamCenterCatalog {
    private data class CatalogCard(
        val title: String,
        val url: String,
        val ratingDetailsUrl: String,
        val type: TvType,
        val posterUrl: String?,
        val year: Int?,
        val embeddedScore: Score?,
    )

    private val scoreCache = ConcurrentHashMap<String, Score>()

    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        val document = document(requestUrl(section.path, page.coerceAtLeast(1)))
        val items = mediaCards(api, document, section.type, showScore)
        return StreamCenterCatalogPage(items, items.size >= PAGE_SIZE)
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (query.isBlank() || page < 1) return StreamCenterCatalogPage(emptyList(), false)
        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val document = document(requestUrl("/search", page, "query=$encodedQuery"))
        val items = mediaCards(api, document, null, showScore)
        return StreamCenterCatalogPage(items, items.size >= PAGE_SIZE)
    }

    suspend fun recommendations(api: MainAPI, mediaUrl: String, showScore: Boolean): List<SearchResponse> {
        val mediaMatch = MEDIA_PATH_REGEX.find(mediaUrl) ?: return emptyList()
        val mediaPath = "/${mediaMatch.groupValues[1].lowercase(Locale.ROOT)}/${mediaMatch.groupValues[2]}"
        val document = document(
            "$TMDB_BASE_URL$mediaPath/remote/recommendations?language=it-IT&version=1&translate=false",
        )
        val recommendationCards = document.select("div.item.mini_card, $CARD_SELECTOR")
        return mediaCards(api, recommendationCards, null, showScore)
    }

    private suspend fun document(url: String): Document {
        val html = app.get(url, headers = headers).text
        return Jsoup.parse(html, url)
    }

    private fun requestUrl(path: String, page: Int, extraQuery: String? = null): String {
        return buildString {
            append(TMDB_BASE_URL)
            append(path)
            append("?language=it-IT&page=")
            append(page)
            extraQuery?.takeIf(String::isNotBlank)?.let {
                append('&')
                append(it)
            }
        }
    }

    private suspend fun mediaCards(
        api: MainAPI,
        document: Document,
        expectedType: TvType?,
        showScore: Boolean,
    ): List<SearchResponse> {
        return mediaCards(api, document.select(CARD_SELECTOR), expectedType, showScore)
    }

    private suspend fun mediaCards(
        api: MainAPI,
        cards: Iterable<Element>,
        expectedType: TvType?,
        showScore: Boolean,
    ): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val parsedCards = cards.mapNotNull { card ->
            val link = card.select("a[href]").firstOrNull { anchor ->
                MEDIA_PATH_REGEX.containsMatchIn(anchor.attr("href"))
            } ?: return@mapNotNull null
            val href = link.attr("href")
            val pathMatch = MEDIA_PATH_REGEX.find(href) ?: return@mapNotNull null
            val type = if (pathMatch.groupValues[1].equals("tv", ignoreCase = true)) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }
            if (expectedType != null && type != expectedType) return@mapNotNull null
            val canonicalUrl = "$TMDB_BASE_URL/${pathMatch.groupValues[1].lowercase(Locale.ROOT)}/${pathMatch.groupValues[2]}"
            if (!seen.add(canonicalUrl)) return@mapNotNull null
            val titleElement = card.selectFirst("h2, h3")
            val title = titleElement?.text()?.trim().orEmpty()
                .ifBlank { link.attr("title").trim() }
                .ifBlank { card.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let(::imageUrl)
            val url = mediaUrl(
                type = pathMatch.groupValues[1],
                id = pathMatch.groupValues[2],
                title = title,
                posterUrl = poster,
            )
            val year = YEAR_REGEX.find(
                card.selectFirst("span.subheader, span.release_date, span.date, p")?.text().orEmpty(),
            )?.value?.toIntOrNull()
            val mediaPath = href
                .substringAfter(TMDB_BASE_URL, href)
                .substringBefore('?')
                .trimEnd('/')
                .takeIf { it.startsWith('/') }
                ?: "/${pathMatch.groupValues[1].lowercase(Locale.ROOT)}/${pathMatch.groupValues[2]}"
            CatalogCard(
                title = title,
                url = url,
                ratingDetailsUrl = "$TMDB_BASE_URL$mediaPath/remote/rating/details?translate=false",
                type = type,
                posterUrl = poster,
                year = year,
                embeddedScore = if (showScore) tmdbScore(card) else null,
            )
        }
        val remoteScores = if (showScore) fetchMissingScores(parsedCards) else emptyMap()
        return parsedCards.map { card ->
            val score = card.embeddedScore ?: scoreCache[card.url] ?: remoteScores[card.url]
            if (card.type == TvType.TvSeries) {
                api.newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                    posterUrl = card.posterUrl
                    year = card.year
                    this.score = score
                }
            } else {
                api.newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                    posterUrl = card.posterUrl
                    year = card.year
                    this.score = score
                }
            }
        }
    }

    private suspend fun fetchMissingScores(cards: List<CatalogCard>): Map<String, Score> {
        cards.forEach { card -> card.embeddedScore?.let { scoreCache[card.url] = it } }
        return cards
            .filter { it.embeddedScore == null && scoreCache[it.url] == null }
            .distinctBy(CatalogCard::url)
            .mapChunkedParallel(REMOTE_SCORE_REQUEST_CHUNK_SIZE) { card ->
                val html = app.get(
                    card.ratingDetailsUrl,
                    headers = headers + mapOf(
                        "Referer" to card.url,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                ).text
                val score = Jsoup.parse(html, card.ratingDetailsUrl)
                    .selectFirst(".user_score_chart[data-percent]")
                    ?.attr("data-percent")
                    ?.let { scoreFromTmdbValue(it, true) }
                    ?: return@mapChunkedParallel null
                scoreCache[card.url] = score
                card.url to score
            }
            .toMap()
    }

    private fun tmdbScore(card: Element): Score? {
        val candidates = sequenceOf(
            card.selectFirst("[data-percent]")?.attr("data-percent") to true,
            card.selectFirst("[data-vote-average]")?.attr("data-vote-average") to false,
            card.selectFirst("[data-rating]")?.attr("data-rating") to false,
            card.selectFirst("[data-score]")?.attr("data-score") to false,
            card.selectFirst(".vote_average, .rating, .percentage")?.text() to false,
            card.select("[aria-label]")
                .asSequence()
                .map { it.attr("aria-label") }
                .firstOrNull { '%' in it } to true,
        )
        return candidates.firstNotNullOfOrNull { (rawValue, percentScale) ->
            rawValue?.let { scoreFromTmdbValue(it, percentScale) }
        }
    }

    private fun scoreFromTmdbValue(rawValue: String, percentScale: Boolean): Score? {
        val value = VOTE_REGEX.find(rawValue)?.value
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: return null
        val maximum = if (percentScale || '%' in rawValue || value > 10.0) 100 else 10
        return Score.from(value, maximum)
    }

    private fun imageUrl(image: Element): String? {
        val value = sequenceOf("data-src", "data-original", "src")
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$TMDB_BASE_URL$value"
            else -> value
        }
    }

    private fun mediaUrl(
        type: String,
        id: String,
        title: String,
        posterUrl: String?,
    ): String {
        val parameters = buildList {
            add("streamcenter_title=${encode(title)}")
            posterUrl?.takeIf(String::isNotBlank)?.let { add("streamcenter_poster=${encode(it)}") }
        }
        return "$TMDB_BASE_URL/${type.lowercase(Locale.ROOT)}/$id?${parameters.joinToString("&")}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val PAGE_SIZE = 20
        private const val REMOTE_SCORE_REQUEST_CHUNK_SIZE = 5
        private const val TMDB_BASE_URL = "https://www.themoviedb.org"
        private const val CARD_SELECTOR =
            "div[data-object-id][class*=poster-card], div[data-object-id][class*=media-card], " +
                "div.card.style_1, div.card.v4.tight"
        private val MEDIA_PATH_REGEX = Regex("/(movie|tv)/(\\d+)", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("\\b(?:18|19|20|21)\\d{2}\\b")
        private val VOTE_REGEX = Regex("\\d+(?:[.,]\\d+)?")
    }
}
