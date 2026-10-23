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
import it.dogior.hadEnough.StreamCenter.Companion.AU_ARCHIVE_BATCH_SIZE
import it.dogior.hadEnough.StreamCenter.Companion.POPULAR_HOME_VARIANT_PARALLELISM
import it.dogior.hadEnough.StreamCenter.Companion.RANDOM_HOME_CANDIDATE_FACTOR
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_ALTERNATIVE_TITLE_QUERY_LIMIT
import it.dogior.hadEnough.anime.source.cleanAnimeUnityTitle
import it.dogior.hadEnough.anime.source.displayTitle
import it.dogior.hadEnough.anime.source.isDub
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
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.parseWholeAnimeEpisodeNumber
import it.dogior.hadEnough.util.runCatchingCancellable
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

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

internal fun JSONObject.toAnimeUnityHomeItem(episodeNumber: Int? = null): AnimeUnityHomeItem? {
    if (optNullableInt("id") == null || optNullableString("slug") == null) return null
    val title = optNullableString("title_it")
        ?: optNullableString("title_eng")
        ?: optNullableString("title")
        ?: return null
    return AnimeUnityHomeItem(
        title = title.replace(" (ITA)", "").trim(),
        type = optNullableString("type"),
        dub = optNullableInt("dub") == 1 || title.contains("(ITA)"),
        score = optNullableString("score"),
        imageUrl = optNullableString("imageurl"),
        anilistId = optNullableInt("anilist_id"),
        malId = optNullableInt("mal_id"),
        availableEpisodes = optNullableInt("episodes_count") ?: optNullableInt("real_episodes_count"),
        episodeNumber = episodeNumber,
    )
}

internal fun AnimeUnityAnime.toAnimeUnityHomeItem(): AnimeUnityHomeItem? {
    val homeTitle = titleIt ?: titleEng ?: title ?: return null
    val episodeCount = episodesCount ?: realEpisodesCount
    return AnimeUnityHomeItem(
        title = cleanAnimeUnityTitle(homeTitle),
        type = type,
        dub = isDub,
        score = score,
        imageUrl = imageUrl,
        anilistId = anilistId,
        malId = malId,
        availableEpisodes = episodeCount,
        episodeNumber = episodeCount,
    )
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
        aniListMetadataClient.fetchScores(items.mapNotNull { it.anilistId })
    } else {
        emptyMap()
    }
    if (!StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)) {
        val seen = mutableSetOf<String>()
        return items.mapNotNull { item ->
            val baseUrl = animeHomeRoutingUrl(item.malId, item.anilistId) ?: return@mapNotNull null
            val url = "$baseUrl&$animeVariantParam=${if (item.dub) "dub" else "sub"}"
            if (!seen.add(url)) return@mapNotNull null
            val type = when (item.type) {
                "Movie", "Special" -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            newAnimeSearchResponse(item.title, url, type) {
                this.posterUrl = animeUnityPoster(item.imageUrl)
                if (showDubStatus) {
                    addDubStatus(
                        if (item.dub) DubStatus.Dubbed else DubStatus.Subbed,
                        item.episodeNumber.takeIf { showEpisodeNumber },
                    )
                }
                if (showScore) {
                    (item.anilistId?.let { anilistScores[it] } ?: item.score)?.let {
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
        val type = when (primary.type) {
            "Movie", "Special" -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        newAnimeSearchResponse(primary.title, url, type) {
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
                val scoreValue = variants.firstNotNullOfOrNull { variant ->
                    variant.anilistId?.let { anilistScores[it] }
                } ?: variants.firstNotNullOfOrNull { it.score }
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
    val related = sources
        .flatMap(AnimeUnityTitleSources::related)
        .filterNot(isCurrentAnime)
    val relatedIds = related.mapTo(mutableSetOf(), AnimeUnityAnime::id)
    val recommendations = sources
        .flatMap(AnimeUnityTitleSources::recommendations)
        .filterNot { recommendation -> isCurrentAnime(recommendation) || recommendation.id in relatedIds }
    val showDubStatus = !performanceMode && StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref)
    val showEpisodeNumber = !performanceMode && StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref)
    val relatedResponses = buildGroupedAnimeUnityHomeResponses(
        items = related.mapNotNull { recommendation -> recommendation.toAnimeUnityHomeItem() },
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showCardScores,
        limit = limit,
    )
    val auResult = if (relatedResponses.size >= limit || recommendations.isEmpty()) {
        relatedResponses
    } else {
        val suggestedResponses = buildGroupedAnimeUnityHomeResponses(
            items = recommendations.mapNotNull { recommendation -> recommendation.toAnimeUnityHomeItem() },
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

internal suspend fun StreamCenter.fetchAnimeUnityHtml(path: String): Document {
    ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
    val url = "$animeUnityUrl$path"
    val html = fetchText {
        val response = app.get(url, headers = headers, timeout = 5L)
        check(response.code in 200..299) { "AnimeUnity Home HTTP ${response.code}" }
        response.text
    }
    return Jsoup.parse(html, url)
}

internal suspend fun StreamCenter.fetchAnimeUnityCalendarHome(
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): List<SearchResponse> {
    val today = normalizeDayName(currentItalianCalendarDayName())
    ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
    val cache = StreamCenterHomeCalendarCache.storage()
        ?.takeIf { StreamCenterPlugin.isHomeCalendarCacheEnabled(sharedPref) }
    val cacheKey = "$animeUnityUrl/calendario"
    val expiresAt = nextCalendarDayStartMillis()
    val generation = cache?.generation()
    val cachedDocument = cache?.read(cacheKey, expiresAt)
    val doc = cachedDocument?.let { Jsoup.parse(it, cacheKey) }
        ?: fetchAnimeUnityHtml("/calendario").also { document ->
            check(document.select("calendario-item").isNotEmpty()) {
                "AnimeUnity: risposta priva del calendario"
            }
            if (cache != null && generation != null && StreamCenterPlugin.isHomeCalendarCacheEnabled(sharedPref)) {
                runCatching {
                    cache.write(cacheKey, document.outerHtml(), expiresAt, generation)
                }.onFailure { error ->
                    StreamCenterLogger.logMenuError("Salvataggio cache calendario non riuscito", error)
                }
            }
        }
    val items = doc.select("calendario-item").mapNotNull { element ->
        val json = element.attr("a").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@mapNotNull null
        if (normalizeDayName(obj.optNullableString("day")) != today) return@mapNotNull null
        val releasedEpisodes = element.attr("episodes_count").trim().toIntOrNull()
        obj.toAnimeUnityHomeItem(episodeNumber = releasedEpisodes?.plus(1))
    }
    return buildGroupedAnimeUnityHomeResponses(
        items = items,
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showScore,
        limit = limit,
    )
}

internal suspend fun StreamCenter.fetchAnimeUnityLatestHome(
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): List<SearchResponse> {
    val doc = fetchAnimeUnityHtml("/")
    val json = doc.selectFirst("#ultimi-episodi layout-items")?.attr("items-json").orEmpty()
    check(json.isNotBlank()) { "AnimeUnity: risposta Home priva degli ultimi episodi" }
    val data = JSONObject(json).optJSONArray("data")
        ?: error("AnimeUnity: dati ultimi episodi non validi")
    val items = buildList {
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val episodeNumber = parseWholeAnimeEpisodeNumber(entry.optNullableString("number"))
            val animeObj = entry.optJSONObject("anime") ?: continue
            animeObj.toAnimeUnityHomeItem(episodeNumber = episodeNumber)?.let(::add)
        }
    }
    return buildGroupedAnimeUnityHomeResponses(
        items = items,
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showScore,
        limit = limit,
    )
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
        .mapNotNull { anime -> anime.toAnimeUnityHomeItem() }
        .let { candidates -> selectAnimeUnityHomeItems(candidates, limit) }
    return buildGroupedAnimeUnityHomeResponses(
        items = items,
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showScore,
        limit = limit,
    )
}

internal suspend fun StreamCenter.fetchAnimeUnityPopularHome(
    page: Int,
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
    resolveVariants: Boolean = true,
): List<SearchResponse> {
    val items = mutableListOf<AnimeUnityHomeItem>()
    var currentPage = page
    while (currentPage <= 5) {
        val doc = fetchAnimeUnityHtml("/top-anime?order=most_viewed&page=$currentPage")
        val json = doc.selectFirst("top-anime")?.attr("animes").orEmpty()
        check(json.isNotBlank()) { "AnimeUnity: risposta priva degli anime popolari" }
        val data = JSONObject(json).optJSONArray("data")
            ?: error("AnimeUnity: dati anime popolari non validi")
        if (data.length() == 0) break

        val pageItems = (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.toAnimeUnityHomeItem()
        }
        for (batch in pageItems.chunked(POPULAR_HOME_VARIANT_PARALLELISM)) {
            items += if (resolveVariants) {
                batch.mapChunkedParallel(POPULAR_HOME_VARIANT_PARALLELISM, this@fetchAnimeUnityPopularHome::fetchAnimeUnityPopularHomeVariants)
                    .flatten()
            } else {
                batch.map { it.copy(episodeNumber = it.availableEpisodes) }
            }
            val groupedCount = groupedAnimeUnityHomeCount(items)
            if (groupedCount >= limit) {
                return buildGroupedAnimeUnityHomeResponses(
                    items = items,
                    showDubStatus = showDubStatus,
                    showEpisodeNumber = showEpisodeNumber,
                    showScore = showScore,
                    limit = limit,
                )
            }
        }
        currentPage++
    }
    return buildGroupedAnimeUnityHomeResponses(
        items = items,
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showScore,
        limit = limit,
    )
}

internal fun StreamCenter.groupedAnimeUnityHomeCount(items: List<AnimeUnityHomeItem>): Int {
    return items.mapNotNull { animeHomeRoutingUrl(it.malId, it.anilistId) }
        .distinct()
        .size
}

internal suspend fun StreamCenter.fetchAnimeUnityPopularHomeVariants(item: AnimeUnityHomeItem): List<AnimeUnityHomeItem> {
    val variants = runCatchingCancellable {
        val syncIds = AnimeSyncIds(
            anilistId = item.anilistId,
            malId = item.malId,
            kitsuId = null,
        )
        animeUnitySourceClient.findVariants(
            syncIds,
            listOf(item.title),
            exactTitleKeys = setOf(sourceTitleDedupKey(item.title)),
            allowTitleFallback = true,
        )
            .map { anime ->
                AnimeUnityHomeItem(
                    title = cleanAnimeUnityTitle(anime.displayTitle()),
                    type = anime.type,
                    dub = anime.isDub,
                    score = anime.score ?: item.score,
                    imageUrl = anime.imageUrl ?: item.imageUrl,
                    anilistId = anime.anilistId,
                    malId = anime.malId,
                    availableEpisodes = anime.episodesCount ?: anime.realEpisodesCount ?: item.availableEpisodes,
                    episodeNumber = anime.episodesCount ?: anime.realEpisodesCount ?: item.availableEpisodes,
                )
            }
    }.getOrDefault(emptyList())
    return variants.ifEmpty {
        listOf(item.copy(episodeNumber = item.availableEpisodes))
    }
}

internal fun StreamCenter.currentItalianCalendarDayName(): String {
    return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
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
    val records = animeUnitySourceClient.fetchArchive(
        title = query,
        offset = (page - 1) * AU_ARCHIVE_BATCH_SIZE,
    )
    val anilistIds = records.mapNotNull { it.anilistId }
    val (anilistScores, anilistTitleAliases) = if (enrich && anilistIds.isNotEmpty()) {
        coroutineScope {
            val scoresJob = async(Dispatchers.IO) {
                if (!showCardScores) return@async emptyMap<Int, String>()
                withTimeoutOrNull(ANIME_SEARCH_ENRICHMENT_TIMEOUT_MS) {
                    aniListMetadataClient.fetchScores(anilistIds)
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
    if (!StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)) {
        val seen = mutableSetOf<String>()
        val items = records.mapNotNull { anime ->
            val baseUrl = animeHomeRoutingUrl(anime.malId, anime.anilistId) ?: return@mapNotNull null
            val url = "$baseUrl&$animeVariantParam=${if (anime.isDub) "dub" else "sub"}"
            if (!seen.add(url)) return@mapNotNull null
            val type = if (anime.type.equals("Movie", ignoreCase = true)) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
            val displayedTitle = cleanAnimeUnityTitle(anime.displayTitle())
            val aliases = anime.searchTitleAliases(anilistTitleAliases[anime.anilistId].orEmpty())
            registerSearchTitleAliases(url, aliases)
            newAnimeSearchResponse(displayedTitle, url, type) {
                this.posterUrl = animeUnityPoster(anime.imageUrl)
                this.year = anime.year
                this.otherName = aliases.firstOrNull { alias ->
                    sourceTitleDedupKey(alias) != sourceTitleDedupKey(displayedTitle)
                }
                (anime.anilistId?.let { anilistScores[it] } ?: anime.score)?.let {
                    this.score = Score.from(it, 10)
                }
                addDubStatus(dubExist = anime.isDub, subExist = !anime.isDub)
            }
        }
        return items to (records.size >= AU_ARCHIVE_BATCH_SIZE)
    }
    val groups = linkedMapOf<String, MutableList<AnimeUnityAnime>>()
    records.forEach { anime ->
        val url = animeHomeRoutingUrl(anime.malId, anime.anilistId) ?: return@forEach
        groups.getOrPut(url) { mutableListOf() } += anime
    }
    val items = groups.map { (url, variants) ->
        val primary = variants.firstOrNull { !it.isDub } ?: variants.first()
        val type = if (primary.type.equals("Movie", ignoreCase = true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }
        val displayedTitle = cleanAnimeUnityTitle(primary.displayTitle())
        val aliases = variants.flatMap { anime ->
            anime.searchTitleAliases(anilistTitleAliases[anime.anilistId].orEmpty())
        }
        registerSearchTitleAliases(url, aliases)
        newAnimeSearchResponse(displayedTitle, url, type) {
            this.posterUrl = variants.firstNotNullOfOrNull { animeUnityPoster(it.imageUrl) }
            this.year = variants.firstNotNullOfOrNull { it.year }
            this.otherName = aliases.firstOrNull { alias ->
                sourceTitleDedupKey(alias) != sourceTitleDedupKey(displayedTitle)
            }
            val scoreValue = variants.firstNotNullOfOrNull { variant ->
                variant.anilistId?.let { anilistScores[it] }
            } ?: variants.firstNotNullOfOrNull { it.score }
            scoreValue?.let { this.score = Score.from(it, 10) }
            addDubStatus(
                dubExist = variants.any { it.isDub },
                subExist = variants.any { !it.isDub },
            )
        }
    }
    return items to (records.size >= AU_ARCHIVE_BATCH_SIZE)
}

internal fun AnimeUnityAnime.searchTitleAliases(metadataAliases: Collection<String>): List<String> {
    return (listOf(titleIt, titleEng, title, displayTitle()) + metadataAliases)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .distinctBy(::sourceTitleDedupKey)
}

internal suspend fun StreamCenter.fetchAnimeUnityArchiveHome(
    filters: StreamCenterAnimeArchiveFilters,
    offset: Int,
    limit: Int,
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodeNumber: Boolean,
): List<SearchResponse> {
    val records = buildList {
        var nextOffset = offset
        while (size.toLong() < limit.toLong() * 3L) {
            val batch = animeUnitySourceClient.fetchArchive(filters, nextOffset)
            addAll(batch)
            if (batch.size < AU_ARCHIVE_BATCH_SIZE) break
            nextOffset += batch.size
        }
    }
    val items = records.mapNotNull { anime -> anime.toAnimeUnityHomeItem() }
    return buildGroupedAnimeUnityHomeResponses(
        items,
        showDubStatus = showDubStatus,
        showEpisodeNumber = showEpisodeNumber,
        showScore = showScore,
        limit = limit,
    )
}
