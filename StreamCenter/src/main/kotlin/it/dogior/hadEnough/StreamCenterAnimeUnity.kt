package it.dogior.hadEnough

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import it.dogior.hadEnough.StreamCenter.Companion.ANILIST_RECOMMENDATIONS_QUERY
import it.dogior.hadEnough.StreamCenter.Companion.ANIME_SEARCH_ENRICHMENT_TIMEOUT_MS
import it.dogior.hadEnough.StreamCenter.Companion.ANIME_HOME_SCORE_TIMEOUT_MS
import it.dogior.hadEnough.StreamCenter.Companion.AU_ARCHIVE_BATCH_SIZE
import it.dogior.hadEnough.StreamCenter.Companion.ANIME_UNITY_ID_LOOKUP_PARALLELISM
import it.dogior.hadEnough.StreamCenter.Companion.ANIME_UNITY_ID_LOOKUP_TIMEOUT_MS
import it.dogior.hadEnough.StreamCenter.Companion.ANIME_UNITY_LATEST_MAX_PAGES
import it.dogior.hadEnough.StreamCenter.Companion.ANIME_UNITY_TOP_PAGES_PER_HOME_PAGE
import it.dogior.hadEnough.StreamCenter.Companion.RANDOM_HOME_CANDIDATE_FACTOR
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_ALTERNATIVE_TITLE_QUERY_LIMIT
import it.dogior.hadEnough.anime.source.AnimeUnityArchivePage
import it.dogior.hadEnough.anime.source.cleanAnimeUnityTitle
import it.dogior.hadEnough.anime.source.displayTitle
import it.dogior.hadEnough.anime.source.ANIME_UNITY_DUB_LANGUAGE
import it.dogior.hadEnough.anime.source.audioVariants
import it.dogior.hadEnough.anime.source.episodeCount
import it.dogior.hadEnough.anime.source.hasDub
import it.dogior.hadEnough.anime.source.hasSub
import it.dogior.hadEnough.anime.source.toAnimeUnityAnime
import it.dogior.hadEnough.anime.source.toAnimeUnityAnimeList
import it.dogior.hadEnough.anime.source.animeUnityPageProps
import it.dogior.hadEnough.anime.source.sourceTitleDedupKey
import it.dogior.hadEnough.cache.StreamCenterHomeCalendarCache
import it.dogior.hadEnough.cache.nextCalendarDayStartMillis
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.objects
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.parseWholeAnimeEpisodeNumber
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal data class AnimeUnityHomeItem(
    val title: String,
    val type: String?,
    val dub: Boolean,
    val score: String?,
    val imageUrl: String?,
    val anilistId: Int?,
    val malId: Int?,
    val availableEpisodes: Int?,
    val episodeNumber: Int?,
)

internal data class AnimeUnityHomePage(val items: List<SearchResponse>, val hasNext: Boolean)

private data class AnimeUnityHomeCursor(val sitePage: Int, val skip: Int)

private val animeUnityHomeCursors = ConcurrentHashMap<Pair<String, Int>, AnimeUnityHomeCursor>()

internal fun AnimeUnityAnime.toAnimeUnityHomeItems(
    variants: List<Boolean> = audioVariants(),
    episodeNumber: (Boolean) -> Int? = { dub -> episodeCount(dub) },
): List<AnimeUnityHomeItem> {
    val homeTitle = cleanAnimeUnityTitle(displayTitle())
    return variants.map { dub ->
        AnimeUnityHomeItem(
            title = homeTitle,
            type = type,
            dub = dub,
            score = score,
            imageUrl = imageUrl,
            anilistId = anilistId,
            malId = malId,
            availableEpisodes = episodeCount(dub),
            episodeNumber = episodeNumber(dub),
        )
    }
}

private fun animeUnityHomeTvType(type: String?): TvType = when (type?.lowercase(Locale.ROOT)) {
    "movie", "special" -> TvType.AnimeMovie
    else -> TvType.Anime
}

internal fun StreamCenter.animeHomeRoutingUrl(malId: Int?, anilistId: Int?): String? {
    return when {
        anilistId != null -> markAnilistUrl(anilistId, malId)
        malId != null -> markMalOnlyUrl(malId)
        else -> null
    }
}

internal fun StreamCenter.selectAnimeUnityHomeItems(
    items: List<AnimeUnityHomeItem>,
    limit: Int,
): List<AnimeUnityHomeItem> {
    if (!StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)) {
        val selectedKeys = mutableSetOf<String>()
        return items.filter { item ->
            val url = animeHomeRoutingUrl(item.malId, item.anilistId) ?: return@filter false
            selectedKeys.add("$url:${item.dub}")
        }.take(limit)
    }
    val selectedUrls = items.asSequence()
        .mapNotNull { item -> animeHomeRoutingUrl(item.malId, item.anilistId) }
        .distinct()
        .take(limit)
        .toSet()
    return items.filter { item ->
        animeHomeRoutingUrl(item.malId, item.anilistId) in selectedUrls
    }
}

internal fun StreamCenter.markAnilistUrl(anilistId: Int, malId: Int? = null): String {
    val params = buildList {
        add(animeMarker)
        add("$animeAnilistParam=$anilistId")
        malId?.let { add("$animeMalParam=$it") }
    }
    return "https://anilist.co/anime/$anilistId?${params.joinToString("&")}"
}

internal fun StreamCenter.animeUnityImageHost(): String {
    val host = hostOf(animeUnityUrl)
    return when {
        host == "animeunity.to" -> "img.animeunity.to"
        host.startsWith("www.") -> host.replaceFirst("www.", "img.")
        host.startsWith("img.") -> host
        else -> "img.$host"
    }
}

internal fun StreamCenter.animeUnityPoster(imageUrl: String?): String? {
    val raw = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("http")) return raw
    val fileName = raw.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
    return "https://${animeUnityImageHost()}/anime/$fileName"
}

internal suspend fun StreamCenter.buildGroupedAnimeUnityHomeResponses(
    items: List<AnimeUnityHomeItem>,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
    showScore: Boolean,
    limit: Int,
): List<SearchResponse> {
    val anilistScores = if (showScore) {
        withTimeoutOrNull(ANIME_HOME_SCORE_TIMEOUT_MS) {
            aniListMetadataClient.fetchScores(items.filter { it.score == null }.mapNotNull { it.anilistId })
        }.orEmpty()
    } else {
        emptyMap()
    }
    if (!StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)) {
        val seen = mutableSetOf<String>()
        return items.mapNotNull { item ->
            val baseUrl = animeHomeRoutingUrl(item.malId, item.anilistId) ?: return@mapNotNull null
            val url = "$baseUrl&$animeVariantParam=${if (item.dub) "dub" else "sub"}"
            if (!seen.add(url)) return@mapNotNull null
            newAnimeSearchResponse(item.title, url, animeUnityHomeTvType(item.type)) {
                this.posterUrl = animeUnityPoster(item.imageUrl)
                if (showDubStatus) {
                    addDubStatus(
                        if (item.dub) DubStatus.Dubbed else DubStatus.Subbed,
                        item.episodeNumber.takeIf { showEpisodeNumber },
                    )
                }
                if (showScore) {
                    (item.score ?: item.anilistId?.let { anilistScores[it] })?.let {
                        this.score = Score.from(it, 10)
                    }
                }
            }
        }.take(limit)
    }
    val groups = linkedMapOf<String, MutableList<AnimeUnityHomeItem>>()
    for (item in items) {
        val url = animeHomeRoutingUrl(item.malId, item.anilistId) ?: continue
        groups.getOrPut(url) { mutableListOf() } += item
    }
    return groups.entries.take(limit).map { (url, variants) ->
        val primary = variants.firstOrNull { !it.dub } ?: variants.first()
        newAnimeSearchResponse(primary.title, url, animeUnityHomeTvType(primary.type)) {
            this.posterUrl = variants.firstNotNullOfOrNull { animeUnityPoster(it.imageUrl) }
            if (showDubStatus) {
                addDubStatus(
                    dubExist = variants.any { it.dub },
                    subExist = variants.any { !it.dub },
                    dubEpisodes = variants.filter { it.dub }.mapNotNull { it.episodeNumber }.maxOrNull()
                        .takeIf { showEpisodeNumber },
                    subEpisodes = variants.filter { !it.dub }.mapNotNull { it.episodeNumber }.maxOrNull()
                        .takeIf { showEpisodeNumber },
                )
            }
            if (showScore) {
                val scoreValue = variants.firstNotNullOfOrNull { it.score }
                    ?: variants.firstNotNullOfOrNull { variant -> variant.anilistId?.let { anilistScores[it] } }
                scoreValue?.let { this.score = Score.from(it, 10) }
            }
        }
    }
}

internal suspend fun StreamCenter.buildBaseCatalogAnimeUnityRecommendations(
    sources: List<AnimeUnityTitleSources>,
    anilistId: Int?,
    malId: Int?,
    limit: Int = 20,
): List<SearchResponse> {
    val isCurrentAnime: (AnimeUnityAnime) -> Boolean = { recommendation ->
        (anilistId != null && recommendation.anilistId == anilistId) ||
            (malId != null && recommendation.malId == malId)
    }
    val candidates = sources.flatMap(AnimeUnityTitleSources::related) +
        sources.flatMap(AnimeUnityTitleSources::recommendations)
    val resolved = if (performanceMode) emptyMap() else {
        withAnimeUnityTrackingIds(candidates, limit).associateBy(AnimeUnityAnime::id)
    }
    val withIds: (AnimeUnityAnime) -> AnimeUnityAnime = { anime -> resolved[anime.id] ?: anime }
    val related = sources
        .flatMap(AnimeUnityTitleSources::related)
        .map(withIds)
        .filterNot(isCurrentAnime)
    val relatedIds = related.mapTo(mutableSetOf(), AnimeUnityAnime::id)
    val recommendations = sources
        .flatMap(AnimeUnityTitleSources::recommendations)
        .map(withIds)
        .filterNot { recommendation -> isCurrentAnime(recommendation) || recommendation.id in relatedIds }
    val (_, showDubStatus, showEpisodeNumber) = cardLabelVisibility()
    val relatedResponses = buildGroupedAnimeUnityHomeResponses(
        items = related.flatMap { recommendation -> recommendation.toAnimeUnityHomeItems() },
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showCardScores,
        limit = limit,
    )
    val auResult = if (relatedResponses.size >= limit || recommendations.isEmpty()) {
        relatedResponses
    } else {
        val suggestedResponses = buildGroupedAnimeUnityHomeResponses(
            items = recommendations.flatMap { recommendation -> recommendation.toAnimeUnityHomeItems() },
            showDubStatus = showDubStatus,
            showEpisodeNumber = showEpisodeNumber,
            showScore = showCardScores,
            limit = limit,
        )
        (relatedResponses + suggestedResponses)
            .distinctBy { response -> response.url }
            .take(limit)
    }
    if (auResult.isNotEmpty() || anilistId == null || performanceMode) return auResult
    return buildAniListRecommendationResponses(anilistId, limit)
}

internal suspend fun StreamCenter.buildAniListRecommendationResponses(
    anilistId: Int,
    limit: Int,
): List<SearchResponse> {
    val data = runCatching {
        aniListMetadataClient.execute(ANILIST_RECOMMENDATIONS_QUERY, JSONObject().put("id", anilistId))
    }.getOrNull() ?: return emptyList()
    val nodes = data.optJSONObject("Media")
        ?.optJSONObject("recommendations")
        ?.optJSONArray("nodes")
        ?: return emptyList()
    val includeScore = showCardScores
    return buildList {
        for (index in 0 until nodes.length()) {
            val rec = nodes.optJSONObject(index)?.optJSONObject("mediaRecommendation") ?: continue
            val recId = rec.optNullableInt("id") ?: continue
            val titleObj = rec.optJSONObject("title")
            val title = titleObj?.optNullableString("romaji")
                ?: titleObj?.optNullableString("english")
                ?: titleObj?.optNullableString("native")
                ?: continue
            val poster = rec.optJSONObject("coverImage")?.let {
                it.optNullableString("large") ?: it.optNullableString("medium")
            }
            val type = if (rec.optNullableString("format").equals("MOVIE", ignoreCase = true)) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
            add(
                newAnimeSearchResponse(title, markAnilistUrl(recId, rec.optNullableInt("idMal")), type) {
                    this.posterUrl = poster
                    if (includeScore) {
                        rec.optNullableInt("averageScore")?.let { this.score = Score.from(it.toDouble(), 100) }
                    }
                },
            )
        }
    }.distinctBy { it.url }.take(limit)
}

internal suspend fun StreamCenter.fetchAnimeUnityJson(path: String): String =
    withSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) {
        fetchText {
            val response = app.get(
                "$animeUnityUrl$path",
                headers = headers + mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
                timeout = 8L,
            )
            check(response.code in 200..299) { "AnimeUnity Home HTTP ${response.code}" }
            response.text
        }
    }

private val animeUnityResolvedTrackingIds = ConcurrentHashMap<Int, Pair<Int?, Int?>>()

internal suspend fun StreamCenter.withAnimeUnityTrackingIds(
    animes: List<AnimeUnityAnime>,
    limit: Int,
): List<AnimeUnityAnime> {
    val missing = animes.distinctBy(AnimeUnityAnime::id)
        .take(limit)
        .filter { it.anilistId == null && it.malId == null }
        .filterNot { animeUnityResolvedTrackingIds.containsKey(it.id) }
    withTimeoutOrNull(ANIME_UNITY_ID_LOOKUP_TIMEOUT_MS) {
        missing.mapChunkedParallel(ANIME_UNITY_ID_LOOKUP_PARALLELISM) { anime ->
            val match = animeUnitySourceClient.fetchArchive(title = anime.title ?: anime.displayTitle())
                .firstOrNull { it.id == anime.id }
            animeUnityResolvedTrackingIds[anime.id] = match?.anilistId to match?.malId
        }
    }
    return animes.map { anime ->
        val (anilistId, malId) = animeUnityResolvedTrackingIds[anime.id] ?: return@map anime
        if (anime.anilistId != null || anime.malId != null) anime else anime.copy(anilistId = anilistId, malId = malId)
    }
}

private suspend fun StreamCenter.buildAnimeUnityEntryResponses(
    entries: List<Triple<AnimeUnityAnime, Boolean, Int?>>,
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): List<SearchResponse> {
    val resolved = withAnimeUnityTrackingIds(entries.map { it.first }, limit).associateBy(AnimeUnityAnime::id)
    val items = entries.flatMap { (anime, dub, episodeNumber) ->
        resolved.getValue(anime.id).toAnimeUnityHomeItems(listOf(dub)) { episodeNumber }
    }
    return buildGroupedAnimeUnityHomeResponses(
        items = items,
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showScore,
        limit = limit,
    )
}

internal suspend fun StreamCenter.fetchAnimeUnityCalendarHome(
    dayOffset: Int,
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): List<SearchResponse> {
    val targetDay = normalizeDayName(italianCalendarDayName(dayOffset))
    val cache = StreamCenterHomeCalendarCache.storage()
        ?.takeIf { StreamCenterPlugin.isHomeCalendarCacheEnabled(sharedPref) }
    val cacheKey = "$animeUnityUrl/api/calendar"
    val expiresAt = nextCalendarDayStartMillis()
    val generation = cache?.generation()
    val calendar = cache?.read(cacheKey, expiresAt)
        ?.let { cached -> runCatching { JSONObject(cached) }.getOrNull() }
        ?: JSONObject(fetchAnimeUnityJson("/api/calendar")).also { json ->
            check(json.length() > 0) { "AnimeUnity: risposta priva del calendario" }
            if (cache != null && generation != null && StreamCenterPlugin.isHomeCalendarCacheEnabled(sharedPref)) {
                runCatching {
                    cache.write(cacheKey, json.toString(), expiresAt, generation)
                }.onFailure { error ->
                    StreamCenterLogger.logMenuError("Salvataggio cache calendario non riuscito", error)
                }
            }
        }
    val todayKey = calendar.keys().asSequence()
        .firstOrNull { key -> normalizeDayName(key.substringBefore(" - ")) == targetDay }
    val entries = calendar.optJSONArray(todayKey.orEmpty()).objects().mapNotNull { entry ->
        val anime = entry.optJSONObject("anime")?.toAnimeUnityAnime() ?: return@mapNotNull null
        Triple(
            anime,
            entry.optBoolean("isDub", false),
            parseWholeAnimeEpisodeNumber(entry.optNullableString("nextEpisodeNumber")),
        )
    }
    return buildAnimeUnityEntryResponses(entries, limit, showScore, showDubStatus, showEpisodeNumber)
}

internal suspend fun StreamCenter.fetchAnimeUnityLatestHome(
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): List<SearchResponse> {
    val entries = mutableListOf<Triple<AnimeUnityAnime, Boolean, Int?>>()
    var page = 1
    while (page <= ANIME_UNITY_LATEST_MAX_PAGES) {
        val root = JSONObject(fetchAnimeUnityJson("/api/home/latest-episodes?per_page=20&page=$page"))
        val data = root.optJSONArray("data") ?: error("AnimeUnity: dati ultimi episodi non validi")
        data.objects().forEach { video ->
            val episode = video.optJSONObject("episode") ?: return@forEach
            val anime = episode.optJSONObject("anime")?.toAnimeUnityAnime() ?: return@forEach
            entries += Triple(
                anime,
                video.optNullableString("language") == ANIME_UNITY_DUB_LANGUAGE,
                parseWholeAnimeEpisodeNumber(episode.optNullableString("number")),
            )
        }
        val distinctAnime = entries.distinctBy { it.first.id }.size
        if (distinctAnime >= limit || page >= (root.optNullableInt("last_page") ?: page)) break
        page++
    }
    return buildAnimeUnityEntryResponses(entries, limit, showScore, showDubStatus, showEpisodeNumber)
}

internal suspend fun StreamCenter.fetchAnimeUnityRandomHome(
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): List<SearchResponse> {
    val requestedCandidates = (limit.toLong() * RANDOM_HOME_CANDIDATE_FACTOR)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val items = animeUnitySourceClient.fetchRandomArchive(requestedCandidates)
        .flatMap { anime -> anime.toAnimeUnityHomeItems() }
        .let { candidates -> selectAnimeUnityHomeItems(candidates, limit) }
    return buildGroupedAnimeUnityHomeResponses(
        items = items,
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showScore,
        limit = limit,
    )
}

internal suspend fun StreamCenter.fetchAnimeUnityTopHome(
    section: String,
    period: String,
    page: Int,
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): AnimeUnityHomePage = fetchAnimeUnityPagedHome(
    section = section,
    page = page,
    limit = limit,
    maxSitePages = ANIME_UNITY_TOP_PAGES_PER_HOME_PAGE,
    showScore = showScore,
    showDubStatus = showDubStatus,
    showEpisodeNumber = showEpisodeNumber,
) { sitePage ->
    val path = "/top-anime?period=$period&page=$sitePage"
    val html = withSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) {
        fetchText { app.get("$animeUnityUrl$path", headers = headers, timeout = 8L).text }
    }
    val animes = animeUnityPageProps(html, "$animeUnityUrl$path")?.optJSONObject("animes")
    val records = animes?.optJSONArray("data")?.toAnimeUnityAnimeList()
        ?: error("AnimeUnity: risposta priva della classifica")
    AnimeUnityArchivePage(
        records = records,
        lastPage = animes.optNullableInt("last_page") ?: if (records.isEmpty()) sitePage else sitePage + 1,
    )
}

private suspend fun StreamCenter.fetchAnimeUnityPagedHome(
    section: String,
    page: Int,
    limit: Int,
    maxSitePages: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
    fetchSitePage: suspend (Int) -> AnimeUnityArchivePage,
): AnimeUnityHomePage {
    val groupVariants = StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)
    val items = mutableListOf<AnimeUnityHomeItem>()
    val cards = mutableSetOf<String>()
    var cursor = animeUnityHomeStart(section, page, limit)
    var hasNext = true
    var loadedPages = 0
    while (hasNext && cards.size < limit && loadedPages < maxSitePages) {
        val sitePage = fetchSitePage(cursor.sitePage)
        loadedPages++
        val records = sitePage.records.drop(cursor.skip)
        var used = 0
        while (used < records.size && cards.size < limit) {
            val variants = records[used++].toAnimeUnityHomeItems()
            items += variants
            variants.forEach { item ->
                val url = animeHomeRoutingUrl(item.malId, item.anilistId) ?: return@forEach
                cards += if (groupVariants) url else "$url:${item.dub}"
            }
        }
        if (used < records.size) {
            cursor = cursor.copy(skip = cursor.skip + used)
        } else {
            hasNext = cursor.sitePage < sitePage.lastPage
            cursor = AnimeUnityHomeCursor(cursor.sitePage + 1, 0)
        }
    }
    animeUnityHomeCursors[section to page + 1] = cursor
    return AnimeUnityHomePage(
        items = buildGroupedAnimeUnityHomeResponses(
            items = items,
            showDubStatus = showDubStatus,
            showEpisodeNumber = showEpisodeNumber,
            showScore = showScore,
            limit = cards.size,
        ),
        hasNext = hasNext,
    )
}

private fun animeUnityHomeStart(section: String, page: Int, limit: Int): AnimeUnityHomeCursor {
    if (page <= 1) return AnimeUnityHomeCursor(1, 0)
    val offset = (page - 1) * limit
    return animeUnityHomeCursors[section to page]
        ?: AnimeUnityHomeCursor(offset / AU_ARCHIVE_BATCH_SIZE + 1, offset % AU_ARCHIVE_BATCH_SIZE)
}

internal fun StreamCenter.italianCalendarDayName(dayOffset: Int = 0): String {
    val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
    return when (day.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "Lunedì"
        Calendar.TUESDAY -> "Martedì"
        Calendar.WEDNESDAY -> "Mercoledì"
        Calendar.THURSDAY -> "Giovedì"
        Calendar.FRIDAY -> "Venerdì"
        Calendar.SATURDAY -> "Sabato"
        else -> "Domenica"
    }
}

internal fun StreamCenter.normalizeDayName(day: String?): String {
    return java.text.Normalizer.normalize(day.orEmpty(), java.text.Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .trim()
        .lowercase(Locale.ROOT)
}

internal suspend fun StreamCenter.searchAnimeUnityWithTypoFallback(
    query: String,
    page: Int,
    includeFallbacks: Boolean = true,
): Pair<List<SearchResponse>, Boolean> {
    val primary = searchWithTypoFallback(
        query = query,
        page = page,
        search = { q, p -> searchAnimeUnity(q, p) },
        fallbackSearch = { q, p -> searchAnimeUnity(q, p, enrich = false) },
        includeFallbacks = includeFallbacks,
    )
    if (!includeFallbacks || page > 1 || filterRelevantSearchResults(query, primary.first).isNotEmpty()) {
        return primary
    }
    return searchAnimeUnityWithAniListTitles(query, primary)
}

internal suspend fun StreamCenter.searchAnimeUnityWithAniListTitles(
    query: String,
    primary: Pair<List<SearchResponse>, Boolean>,
): Pair<List<SearchResponse>, Boolean> {
    val candidates = runCatching {
        aniListCatalog.search(this, query, 1, showScore = false)
    }.getOrNull()
        ?.items
        ?.mapNotNull { response ->
            aniListCatalog.mediaId(response.url)?.let { id -> id to response.name }
        }
        ?.take(SEARCH_ALTERNATIVE_TITLE_QUERY_LIMIT)
        .orEmpty()
    if (candidates.isEmpty()) return primary
    val bridgedPages = coroutineScope {
        candidates.map { (anilistId, title) ->
            async(Dispatchers.IO) {
                val page = runCatching { searchAnimeUnity(title, 1, enrich = false) }
                    .getOrDefault(emptyList<SearchResponse>() to false)
                val items = page.first.filter { response ->
                    extractAnilistIdFromText(response.url) == anilistId
                }
                items.forEach { response ->
                    registerSearchTitleAliases(response.url, listOf(query, title))
                }
                items to page.second
            }
        }.awaitAll()
    }
    return (
        primary.first + bridgedPages.flatMap { it.first }
        ).distinctBy { it.url } to (primary.second || bridgedPages.any { it.second })
}

internal suspend fun StreamCenter.searchAnimeUnity(
    query: String,
    page: Int,
    enrich: Boolean = true,
): Pair<List<SearchResponse>, Boolean> {
    val records = animeUnitySourceClient.fetchArchive(title = query, page = page)
    val anilistIds = records.mapNotNull { it.anilistId }
    val (anilistScores, anilistTitleAliases) = if (enrich && anilistIds.isNotEmpty()) {
        coroutineScope {
            val scoresJob = async(Dispatchers.IO) {
                if (!showCardScores) return@async emptyMap<Int, String>()
                withTimeoutOrNull(ANIME_SEARCH_ENRICHMENT_TIMEOUT_MS) {
                    aniListMetadataClient.fetchScores(records.filter { it.score == null }.mapNotNull { it.anilistId })
                }.orEmpty()
            }
            val aliasesJob = async(Dispatchers.IO) {
                withTimeoutOrNull(ANIME_SEARCH_ENRICHMENT_TIMEOUT_MS) {
                    aniListMetadataClient.fetchTitleAliases(anilistIds)
                }.orEmpty()
            }
            scoresJob.await() to aliasesJob.await()
        }
    } else {
        emptyMap<Int, String>() to emptyMap<Int, List<String>>()
    }
    val hasNext = records.size >= AU_ARCHIVE_BATCH_SIZE
    if (!StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)) {
        val seen = mutableSetOf<String>()
        val items = records.flatMap { anime ->
            val baseUrl = animeHomeRoutingUrl(anime.malId, anime.anilistId) ?: return@flatMap emptyList()
            val displayedTitle = cleanAnimeUnityTitle(anime.displayTitle())
            val aliases = anime.searchTitleAliases(anilistTitleAliases[anime.anilistId].orEmpty())
            anime.audioVariants().mapNotNull { dub ->
                val url = "$baseUrl&$animeVariantParam=${if (dub) "dub" else "sub"}"
                if (!seen.add(url)) return@mapNotNull null
                registerSearchTitleAliases(url, aliases)
                newAnimeSearchResponse(displayedTitle, url, animeUnitySearchTvType(anime.type)) {
                    this.posterUrl = animeUnityPoster(anime.imageUrl)
                    this.year = anime.year
                    this.otherName = aliases.firstOrNull { alias ->
                        sourceTitleDedupKey(alias) != sourceTitleDedupKey(displayedTitle)
                    }
                    (anime.score ?: anime.anilistId?.let { anilistScores[it] })?.let {
                        this.score = Score.from(it, 10)
                    }
                    addDubStatus(dubExist = dub, subExist = !dub)
                }
            }
        }
        return items to hasNext
    }
    val groups = linkedMapOf<String, MutableList<AnimeUnityAnime>>()
    records.forEach { anime ->
        val url = animeHomeRoutingUrl(anime.malId, anime.anilistId) ?: return@forEach
        groups.getOrPut(url) { mutableListOf() } += anime
    }
    val items = groups.map { (url, variants) ->
        val primary = variants.firstOrNull { it.hasSub } ?: variants.first()
        val displayedTitle = cleanAnimeUnityTitle(primary.displayTitle())
        val aliases = variants.flatMap { anime ->
            anime.searchTitleAliases(anilistTitleAliases[anime.anilistId].orEmpty())
        }
        registerSearchTitleAliases(url, aliases)
        newAnimeSearchResponse(displayedTitle, url, animeUnitySearchTvType(primary.type)) {
            this.posterUrl = variants.firstNotNullOfOrNull { animeUnityPoster(it.imageUrl) }
            this.year = variants.firstNotNullOfOrNull { it.year }
            this.otherName = aliases.firstOrNull { alias ->
                sourceTitleDedupKey(alias) != sourceTitleDedupKey(displayedTitle)
            }
            val scoreValue = variants.firstNotNullOfOrNull { it.score }
                ?: variants.firstNotNullOfOrNull { variant -> variant.anilistId?.let { anilistScores[it] } }
            scoreValue?.let { this.score = Score.from(it, 10) }
            addDubStatus(
                dubExist = variants.any { it.hasDub },
                subExist = variants.any { it.hasSub },
            )
        }
    }
    return items to hasNext
}

private fun animeUnitySearchTvType(type: String?): TvType =
    if (type.equals("movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

internal fun AnimeUnityAnime.searchTitleAliases(metadataAliases: Collection<String>): List<String> {
    return (listOf(titleIt, title, titleJa, displayTitle()) + metadataAliases)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .distinctBy(::sourceTitleDedupKey)
}

internal suspend fun StreamCenter.fetchAnimeUnityArchiveHome(
    section: String,
    filters: StreamCenterAnimeArchiveFilters,
    page: Int,
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): AnimeUnityHomePage = fetchAnimeUnityPagedHome(
    section = section,
    page = page,
    limit = limit,
    maxSitePages = (limit * 3 + AU_ARCHIVE_BATCH_SIZE - 1) / AU_ARCHIVE_BATCH_SIZE,
    showScore = showScore,
    showDubStatus = showDubStatus,
    showEpisodeNumber = showEpisodeNumber,
) { sitePage -> animeUnitySourceClient.fetchArchivePage(filters, sitePage) }
