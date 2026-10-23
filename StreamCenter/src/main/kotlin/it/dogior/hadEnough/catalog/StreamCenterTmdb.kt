package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import it.dogior.hadEnough.tmdb.StreamCenterTmdbApiClient
import it.dogior.hadEnough.tmdb.StreamCenterTmdbUserAgent
import it.dogior.hadEnough.tmdb.TmdbApiMapping
import it.dogior.hadEnough.util.StreamCenterHostGate
import it.dogior.hadEnough.util.cleanText
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
    private val englishTitleCache = ConcurrentHashMap<String, String>()

    suspend fun resolveAnimeIds(titles: List<String>, year: Int?, isMovie: Boolean): List<Int> {
        if (year == null) return emptyList()
        val candidates = titles.map(String::trim).filter(String::isNotBlank).distinct()
        val kind = if (isMovie) "movie" else "tv"
        if (StreamCenterTmdbApiClient.isActive()) apiAnimeIds(candidates, year, kind)?.let { return it }
        for (title in candidates.take(4)) {
            val results = document(
                requestUrl("/search/$kind", 1, "query=${encode(title)}&include_adult=true"),
                search = true,
            )
            val ids = TmdbSeriesMetadata.matchingSearchIds(results.select(CARD_SELECTOR), candidates, year, isMovie)
            if (ids.isNotEmpty()) return ids.take(5)
        }
        return emptyList()
    }

    private suspend fun apiAnimeIds(candidates: List<String>, year: Int, kind: String): List<Int>? {
        for (title in candidates.take(4)) {
            val results = StreamCenterTmdbApiClient.get("search/$kind", mapOf("query" to title)) ?: return null
            val found = TmdbApiMapping.searchItems(results, kind)
                .map { item -> TmdbSeriesMetadata.SearchCandidate(item.id, item.titles, item.year) }
            val ids = TmdbSeriesMetadata.matchingIds(found, candidates, year)
            if (ids.isNotEmpty()) return ids.take(5)
        }
        return emptyList()
    }

    private suspend fun apiCards(
        api: MainAPI,
        endpoint: String,
        params: Map<String, String>,
        page: Int,
        forcedType: String?,
        expectedType: TvType?,
        showScore: Boolean,
    ): StreamCenterCatalogPage? {
        val json = StreamCenterTmdbApiClient.get(endpoint, params + ("page" to page.toString())) ?: return null
        val items = TmdbApiMapping.searchItems(json, forcedType)
            .filter { expectedType == null || (it.type == "tv") == (expectedType == TvType.TvSeries) }
            .distinctBy { it.type to it.id }
            .map { item ->
                val url = mediaUrl(item.type, item.id.toString(), item.title, item.posterUrl)
                val score = item.vote.takeIf { showScore }?.let { Score.from(it, 10) }
                if (item.type == "tv") {
                    api.newTvSeriesSearchResponse(item.title, url, TvType.TvSeries) {
                        posterUrl = item.posterUrl
                        year = item.year
                        this.score = score
                    }
                } else {
                    api.newMovieSearchResponse(item.title, url, TvType.Movie) {
                        posterUrl = item.posterUrl
                        year = item.year
                        this.score = score
                    }
                }
            }
        return StreamCenterCatalogPage(items, json.optInt("page", page) < json.optInt("total_pages", 0))
    }

    suspend fun englishTitle(type: String, id: String): String? {
        val normalizedType = type.lowercase(Locale.ROOT)
            .takeIf { value -> value == "movie" || value == "tv" }
            ?: return null
        val normalizedId = id.trim().takeIf { value -> value.all(Char::isDigit) }
            ?: return null
        val cacheKey = "$normalizedType:$normalizedId"
        englishTitleCache[cacheKey]?.let { return it }
        val title = extractTmdbPageTitle(
            document("$TMDB_BASE_URL/$normalizedType/$normalizedId?language=en-US"),
        ).takeIf(String::isNotBlank) ?: return null
        englishTitleCache[cacheKey] = title
        return title
    }

    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        API_SECTION_ENDPOINTS[section.path]?.takeIf { StreamCenterTmdbApiClient.isActive() }?.let { endpoint ->
            val params = if (endpoint == "movie/upcoming") mapOf("region" to "IT") else emptyMap()
            val forcedType = endpoint.substringBefore('/')
            apiCards(api, endpoint, params, page.coerceAtLeast(1), forcedType, section.type, showScore)?.let { return it }
        }
        val document = document(requestUrl(section.path, page.coerceAtLeast(1)))
        val items = mediaCards(api, document, section.type, showScore)
        return StreamCenterCatalogPage(items, items.size >= PAGE_SIZE)
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage = search(api, query, page, showScore, expectedType = null)

    suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
        expectedType: TvType?,
    ): StreamCenterCatalogPage {
        if (query.isBlank() || page < 1) return StreamCenterCatalogPage(emptyList(), false)
        if (StreamCenterTmdbApiClient.isActive()) {
            val (endpoint, forcedType) = when (expectedType) {
                TvType.Movie -> "search/movie" to "movie"
                TvType.TvSeries -> "search/tv" to "tv"
                else -> "search/multi" to null
            }
            apiCards(
                api, endpoint, mapOf("query" to query.trim(), "include_adult" to "false"), page,
                forcedType, expectedType, showScore,
            )?.let { return it }
        }
        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val path = when (expectedType) {
            TvType.Movie -> "/search/movie"
            TvType.TvSeries -> "/search/tv"
            else -> "/search"
        }
        val document = document(requestUrl(path, page, "query=$encodedQuery&include_adult=true"), search = true)
        val items = mediaCards(api, document, expectedType, showScore)
        return StreamCenterCatalogPage(items, items.size >= PAGE_SIZE)
    }

    suspend fun recommendations(api: MainAPI, mediaUrl: String, showScore: Boolean): List<SearchResponse> {
        val mediaMatch = MEDIA_PATH_REGEX.find(mediaUrl) ?: return emptyList()
        val mediaPath = "/${mediaMatch.groupValues[1].lowercase(Locale.ROOT)}/${mediaMatch.groupValues[2]}"
        if (StreamCenterTmdbApiClient.isActive()) {
            val kind = mediaMatch.groupValues[1].lowercase(Locale.ROOT)
            apiCards(api, "${mediaPath.trimStart('/')}/recommendations", emptyMap(), 1, kind, null, showScore)
                ?.let { return it.items }
        }
        val document = document(
            "$TMDB_BASE_URL$mediaPath/remote/recommendations?language=it-IT&version=1&translate=false",
        )
        val recommendationCards = document.select("div.item.mini_card, $CARD_SELECTOR")
        return mediaCards(api, recommendationCards, null, showScore)
    }

    private suspend fun document(url: String, search: Boolean = false): Document {
        val html = StreamCenterHostGate.request(url) {
            app.get(url, headers = StreamCenterTmdbUserAgent.webHeaders(if (search) tmdbSearchHeaders(headers) else headers))
        }.text
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
            val title = tmdbCardHeading(card)
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
                val html = StreamCenterHostGate.request(card.ratingDetailsUrl) {
                    app.get(
                        card.ratingDetailsUrl,
                        headers = StreamCenterTmdbUserAgent.webHeaders(headers) + mapOf(
                            "Referer" to card.url,
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                    )
                }.text
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
            "div.media-list-results > div[data-object-id], " +
                "div.media-card-list > div[data-object-id], " +
                "div[data-object-id][class*=poster-card], div[data-object-id][class*=media-card], " +
                "div.card.style_1, div.card.v4.tight"
        private val API_SECTION_ENDPOINTS = mapOf(
            "/movie" to "movie/popular",
            "/movie/now-playing" to "movie/now_playing",
            "/movie/upcoming" to "movie/upcoming",
            "/movie/top-rated" to "movie/top_rated",
            "/tv" to "tv/popular",
            "/tv/airing-today" to "tv/airing_today",
            "/tv/on-the-air" to "tv/on_the_air",
            "/tv/top-rated" to "tv/top_rated",
        )
        private val MEDIA_PATH_REGEX = Regex("/(movie|tv)/(\\d+)", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("\\b(?:18|19|20|21)\\d{2}\\b")
        private val VOTE_REGEX = Regex("\\d+(?:[.,]\\d+)?")
    }
}

internal fun tmdbCardHeading(card: Element): String = card.selectFirst("h2, h3")
    ?.clone()
    ?.apply { select(".adult").remove() }
    ?.text()?.trim().orEmpty()

internal fun tmdbSearchHeaders(headers: Map<String, String>): Map<String, String> {
    val preferences = URLEncoder.encode(
        """{"adult":true,"i18n_fallback_language":"en-US","locale":"it-IT","country_code":"IT"}""",
        StandardCharsets.UTF_8.name(),
    )
    val cookies = headers.entries.filter { it.key.equals("Cookie", ignoreCase = true) }
        .flatMap { it.value.split(';') }
        .map(String::trim)
        .filter { it.isNotBlank() && it.substringBefore('=') != "preferences" }
    return headers.filterKeys { !it.equals("Cookie", ignoreCase = true) } +
        ("Cookie" to (cookies + "preferences=$preferences").joinToString("; "))
}

internal fun extractTmdbPageTitle(document: Document): String {
    return sequenceOf(
        document.selectFirst("section.header.poster h2 a")?.text(),
        document.selectFirst("section.header h2 a")?.text(),
        document.selectFirst("section[class*=header] h2 a")?.text(),
        document.selectFirst("h2 a[href*='/movie/'], h2 a[href*='/tv/']")?.text(),
        document.selectFirst("meta[property=og:title]")?.attr("content"),
        document.selectFirst("meta[name=twitter:title]")?.attr("content"),
        document.selectFirst("meta[name=title]")?.attr("content"),
        document.selectFirst("title")?.text(),
    ).mapNotNull(::cleanTmdbPageTitle).firstOrNull().orEmpty()
}

private fun cleanTmdbPageTitle(value: String?): String? {
    return cleanText(value)
        ?.replace(
            Regex(
                """\s*[-–—]\s*(?:The Movie Database|TMDB)(?:\s*\([^)]*\))?\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        ?.let(::cleanText)
        ?.takeUnless { it.equals("The Movie Database (TMDB)", ignoreCase = true) }
        ?.takeUnless { TMDB_ERROR_TITLE_REGEX.matches(it) }
}

private val TMDB_ERROR_TITLE_REGEX = Regex(
    """.*\b(?:too many requests|access denied|forbidden|not found|internal server error|just a moment)\b.*""",
    RegexOption.IGNORE_CASE,
)
