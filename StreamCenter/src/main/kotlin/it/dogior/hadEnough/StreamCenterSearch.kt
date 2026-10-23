package it.dogior.hadEnough

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_ALTERNATIVE_TITLE_PENALTY
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_RELEVANCE_MIN_SCORE
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_SECTION_ANIME
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_SECTION_LIVE
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_SECTION_MAIN
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_SECTION_MOVIES
import it.dogior.hadEnough.StreamCenter.Companion.SEARCH_SECTION_SERIES
import it.dogior.hadEnough.anime.source.sourceTitleDedupKey
import it.dogior.hadEnough.anime.source.sourceTitleScore
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun StreamCenter.fetchSearchResults(
    query: String,
    page: Int,
    includeFallbacks: Boolean = true,
): Pair<List<SearchResponse>, Boolean> = coroutineScope {
    StreamCenterVpnGuard.requireInternetAccess(sharedPref)
    val empty = emptyList<SearchResponse>() to false
    if (query.isBlank() || page < 1) {
        StreamCenterLogger.warning(
            action = "Ricerca ignorata",
            details = mapOf(
                "motivo" to if (query.isBlank()) "query_vuota" else "pagina_non_valida",
                "pagina" to page,
                "api" to name,
            ),
        )
        return@coroutineScope empty
    }
    StreamCenterLogger.logMenu(
        action = "Ricerca avviata",
        metadata = mapOf(
            "query" to query,
            "pagina" to page,
            "api" to name,
            "sezione_ricerca" to searchSection,
            "catalogo" to (catalogDefinition?.displayName ?: "nessuno"),
            "modalita_prestazioni" to performanceMode,
        ),
    )
    if (catalogDefinition != null) {
        if (!catalogIsActive) {
            StreamCenterLogger.warning(
                action = "Ricerca catalogo ignorata",
                details = mapOf(
                    "catalogo" to catalogDefinition.displayName,
                    "motivo" to "catalogo_non_attivo",
                ),
            )
            return@coroutineScope empty
        }
        val catalogPage = runCatching { catalogClient?.search(this@fetchSearchResults, query, page, showCardScores) }
            .onFailure { error ->
                StreamCenterLogger.logMenuError(
                    action = "Ricerca catalogo non riuscita",
                    throwable = error,
                    metadata = mapOf(
                        "catalogo" to catalogDefinition.displayName,
                        "query" to query,
                        "pagina" to page,
                    ),
                )
            }
            .getOrNull()
            ?: StreamCenterCatalogPage(emptyList(), false)
        val result = filterRelevantSearchResults(query, catalogPage.items) to catalogPage.hasNext
        StreamCenterLogger.logMenu(
            action = "Ricerca catalogo completata",
            metadata = mapOf(
                "catalogo" to catalogDefinition.displayName,
                "query" to query,
                "pagina" to page,
                "risultati" to result.first.size,
                "altra_pagina_disponibile" to result.second,
            ),
        )
        logSearchCards(
            action = "Schede complete restituite dalla ricerca catalogo",
            contextDetails = mapOf(
                "catalogo" to catalogDefinition.displayName,
                "query" to query,
                "pagina" to page,
                "altra_pagina_disponibile" to result.second,
            ),
            items = result.first,
        )
        return@coroutineScope result
    }
    val primarySearch: suspend () -> Pair<List<SearchResponse>, Boolean> = {
        fetchPrimarySearchResults(query, page, includeFallbacks)
    }
    val result = if (includeFallbacks && searchSection in setOf(
            SEARCH_SECTION_MAIN, SEARCH_SECTION_MOVIES, SEARCH_SECTION_SERIES,
        )
    ) {
        tmdbSearchFallback.search(
            query = query,
            page = page,
            enabled = StreamCenterPlugin.isTmdbSearchFallbackEnabled(sharedPref),
            primary = primarySearch,
            fallback = { fetchTmdbSearchFallback(query, page) },
        )
    } else primarySearch()
    StreamCenterLogger.logMenu(
        action = "Ricerca completata",
        metadata = mapOf(
            "query" to query,
            "pagina" to page,
            "api" to name,
            "sezione_ricerca" to searchSection,
            "risultati" to result.first.size,
            "altra_pagina_disponibile" to result.second,
        ),
    )
    logSearchCards(
        action = "Schede complete restituite dalla ricerca",
        contextDetails = mapOf(
            "query" to query,
            "pagina" to page,
            "sezione_ricerca" to searchSection,
            "altra_pagina_disponibile" to result.second,
        ),
        items = result.first,
    )
    result
}

internal suspend fun StreamCenter.fetchPrimarySearchResults(
    query: String,
    page: Int,
    includeFallbacks: Boolean,
): Pair<List<SearchResponse>, Boolean> {
    val empty = emptyList<SearchResponse>() to false
    return when (searchSection) {
        SEARCH_SECTION_MAIN -> {
            fetchHomeSearchResults(query, page, includeFallbacks)
        }
        SEARCH_SECTION_MOVIES, SEARCH_SECTION_SERIES -> {
            val (scItems, scHasNext) = runCatching {
                searchStreamingCommunityWithTypoFallback(query, page, includeFallbacks)
            }
                .onFailure { error ->
                    StreamCenterLogger.logMenuError(
                        action = "Ricerca StreamingCommunity non riuscita",
                        throwable = error,
                        metadata = mapOf("query" to query, "pagina" to page),
                    )
                }
                .getOrDefault(empty)
            val wantedType = if (searchSection == SEARCH_SECTION_MOVIES) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }
            filterRelevantSearchResults(
                query,
                scItems.filter { it.type == wantedType }.distinctBy { it.url },
            ) to scHasNext
        }
        SEARCH_SECTION_ANIME -> {
            runCatching { searchAnimeUnityWithTypoFallback(query, page, includeFallbacks) }
                .onFailure { error ->
                    StreamCenterLogger.logMenuError(
                        action = "Ricerca AnimeUnity non riuscita",
                        throwable = error,
                        metadata = mapOf("query" to query, "pagina" to page),
                    )
                }
                .map { (items, hasNext) -> filterRelevantSearchResults(query, items) to hasNext }
                .getOrDefault(empty)
        }
        SEARCH_SECTION_LIVE -> {
            if (page > 1) return empty
            runCatching { searchIptv(query) to false }
                .onFailure { error ->
                    StreamCenterLogger.logMenuError(
                        action = "Ricerca IPTV non riuscita",
                        throwable = error,
                        metadata = mapOf("query" to query, "pagina" to page),
                    )
                }
                .getOrDefault(empty)
        }
        else -> {
            empty
        }
    }
}

internal suspend fun StreamCenter.fetchTmdbSearchFallback(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
    val empty = emptyList<SearchResponse>() to false
    StreamCenterLogger.logMenu(
        action = "Ricerca estesa a TMDB",
        metadata = mapOf("query" to query, "pagina" to page, "sezione_ricerca" to searchSection),
    )
    return runCatchingCancellable {
        withTimeoutOrNull(12_000L) {
            val wantedType = when (searchSection) {
                SEARCH_SECTION_MOVIES -> TvType.Movie
                SEARCH_SECTION_SERIES -> TvType.TvSeries
                else -> null
            }
            val tmdbPage = tmdbCatalog.search(this@fetchTmdbSearchFallback, query, page, showCardScores, wantedType)
            tmdbPage.items.distinctBy { it.url } to tmdbPage.hasNext
        } ?: empty
    }.onFailure { error ->
        StreamCenterLogger.logMenuError(
            action = "Ricerca TMDB alternativa non riuscita",
            throwable = error,
            metadata = mapOf("query" to query, "pagina" to page),
        )
    }.getOrDefault(empty)
}

internal suspend fun StreamCenter.fetchHomeSearchResults(
    query: String,
    page: Int,
    includeFallbacks: Boolean = true,
): Pair<List<SearchResponse>, Boolean> = supervisorScope {
    val empty = emptyList<SearchResponse>() to false
    val streamingCommunity = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)) {
        async(Dispatchers.IO) {
            StreamCenterLogger.logMenu(
                action = "Ricerca fonte avviata",
                metadata = mapOf("fonte" to "StreamingCommunity", "query" to query, "pagina" to page),
            )
            val result = runCatching { searchStreamingCommunityWithTypoFallback(query, page, includeFallbacks) }
                .onFailure { error ->
                    StreamCenterLogger.logMenuError(
                        action = "Ricerca fonte non riuscita",
                        throwable = error,
                        metadata = mapOf("fonte" to "StreamingCommunity", "query" to query, "pagina" to page),
                    )
                }
                .getOrDefault(empty)
            StreamCenterLogger.logMenu(
                action = "Ricerca fonte completata",
                metadata = mapOf(
                    "fonte" to "StreamingCommunity",
                    "risultati" to result.first.size,
                    "altra_pagina_disponibile" to result.second,
                ),
            )
            result
        }
    } else {
        null
    }
    val animeUnity = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)) {
        async(Dispatchers.IO) {
            StreamCenterLogger.logMenu(
                action = "Ricerca fonte avviata",
                metadata = mapOf("fonte" to "AnimeUnity", "query" to query, "pagina" to page),
            )
            val result = runCatching { searchAnimeUnityWithTypoFallback(query, page, includeFallbacks) }
                .onFailure { error ->
                    StreamCenterLogger.logMenuError(
                        action = "Ricerca fonte non riuscita",
                        throwable = error,
                        metadata = mapOf("fonte" to "AnimeUnity", "query" to query, "pagina" to page),
                    )
                }
                .getOrDefault(empty)
            StreamCenterLogger.logMenu(
                action = "Ricerca fonte completata",
                metadata = mapOf(
                    "fonte" to "AnimeUnity",
                    "risultati" to result.first.size,
                    "altra_pagina_disponibile" to result.second,
                ),
            )
            result
        }
    } else {
        null
    }
    val iptv: kotlinx.coroutines.Deferred<List<SearchResponse>>? = if (page == 1) {
        async(Dispatchers.IO) {
            StreamCenterLogger.logMenu(
                action = "Ricerca fonte avviata",
                metadata = mapOf("fonte" to "IPTV", "query" to query, "pagina" to page),
            )
            val result = runCatching { searchIptv(query) }
                .onFailure { error ->
                    StreamCenterLogger.logMenuError(
                        action = "Ricerca fonte non riuscita",
                        throwable = error,
                        metadata = mapOf("fonte" to "IPTV", "query" to query, "pagina" to page),
                    )
                }
                .getOrDefault(emptyList())
            StreamCenterLogger.logMenu(
                action = "Ricerca fonte completata",
                metadata = mapOf("fonte" to "IPTV", "risultati" to result.size),
            )
            result
        }
    } else {
        null
    }

    val (streamingCommunityItems, streamingCommunityHasNext) = streamingCommunity?.await() ?: empty
    val (animeItems, animeHasNext) = animeUnity?.await() ?: empty
    val iptvItems = iptv?.await().orEmpty()
    val items = (
        filterRelevantSearchResults(query, streamingCommunityItems + animeItems) +
            iptvItems
        )
        .distinctBy { it.url }

    items to (streamingCommunityHasNext || animeHasNext)
}

internal fun StreamCenter.filterRelevantSearchResults(
    query: String,
    items: List<SearchResponse>,
): List<SearchResponse> {
    return items
        .map { response -> response to searchResponseScore(response, query) }
        .filter { (_, score) -> score >= SEARCH_RELEVANCE_MIN_SCORE }
        .sortedByDescending { (_, score) -> score }
        .map { (response) -> response }
}

internal fun StreamCenter.searchResponseScore(response: SearchResponse, query: String): Int {
    val mainTitleScore = sourceTitleScore(response.name, query)
    val alternativeTitleScore = searchTitleAliases[response.url]
        .orEmpty()
        .maxOfOrNull { title -> sourceTitleScore(title, query) }
        ?.minus(SEARCH_ALTERNATIVE_TITLE_PENALTY)
        ?.coerceAtLeast(0)
        ?: 0
    return maxOf(mainTitleScore, alternativeTitleScore)
}

internal fun StreamCenter.registerSearchTitleAliases(url: String, aliases: Collection<String?>) {
    val cleanedAliases = aliases
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .distinctBy(::sourceTitleDedupKey)
    if (cleanedAliases.isEmpty()) return
    searchTitleAliases[url] = (searchTitleAliases[url].orEmpty() + cleanedAliases)
        .distinctBy(::sourceTitleDedupKey)
}

internal suspend fun StreamCenter.searchWithTypoFallback(
    query: String,
    page: Int,
    search: suspend (String, Int) -> Pair<List<SearchResponse>, Boolean>,
    fallbackSearch: suspend (String, Int) -> Pair<List<SearchResponse>, Boolean> = search,
    includeFallbacks: Boolean = true,
): Pair<List<SearchResponse>, Boolean> {
    val primary = search(query, page)
    if (!includeFallbacks || page > 1 || filterRelevantSearchResults(query, primary.first).isNotEmpty()) {
        return primary
    }
    val fallbackPages = coroutineScope {
        typoFallbackQueries(query).map { fallbackQuery ->
            async(Dispatchers.IO) {
                runCatching { fallbackSearch(fallbackQuery, 1) }
                    .getOrDefault(emptyList<SearchResponse>() to false)
            }
        }.awaitAll()
    }
    return (
        primary.first + fallbackPages.flatMap { it.first }
        ).distinctBy { it.url } to (primary.second || fallbackPages.any { it.second })
}

internal fun StreamCenter.typoFallbackQueries(query: String): List<String> {
    val terms = sourceTitleDedupKey(query)
        .split(' ')
        .filter { it.length >= 3 }
        .distinct()
    if (terms.size < 2) return terms.firstOrNull()
        ?.dropLast(1)
        ?.takeIf { it.length >= 3 }
        ?.let(::listOf)
        .orEmpty()
    return terms
        .flatMap { term -> listOfNotNull(term, term.dropLast(1).takeIf { it.length >= 3 }) }
        .distinct()
        .take(2)
}
