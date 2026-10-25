package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import it.dogior.hadEnough.StreamCenter.Companion.AU_ARCHIVE_BATCH_SIZE
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.remote.StreamCenterRemote
import it.dogior.hadEnough.remote.StreamCenterRemoteQueue
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.runCatchingCancellable
import java.util.Calendar
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun StreamCenter.getUngroupedMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    StreamCenterVpnGuard.requireInternetAccess(sharedPref)
    catalogDefinition?.takeIf { catalogIsActive }?.let { catalog ->
        val section = StreamCenterCatalogs.sectionForData(catalog, request.data)
        StreamCenterLogger.logMenu(
            action = "Caricamento sezione catalogo avviato",
            metadata = mapOf(
                "catalogo" to catalog.displayName,
                "catalogo_key" to catalog.key,
                "sezione" to (section?.title ?: request.name),
                "dati_sezione" to request.data,
                "pagina" to page,
                "modalita_prestazioni" to performanceMode,
            ),
        )
        val catalogPage = section?.let {
            if (it.trackingServiceKey != null) {
                catalogTrackingConfig(it)?.let { config ->
                    runCatchingCancellable { fetchTrackingListHomePage(config, page) }
                        .onFailure { error ->
                            StreamCenterLogger.logMenuError(
                                action = "Caricamento lista di tracciamento non riuscito",
                                throwable = error,
                                metadata = mapOf(
                                    "catalogo" to catalog.displayName,
                                    "sezione" to it.title,
                                    "pagina" to page,
                                ),
                            )
                        }
                        .getOrNull()
                }
            } else {
                runCatchingCancellable { catalogClient?.section(this, it, page, showCardScores) }
                    .onFailure { error ->
                        StreamCenterLogger.logMenuError(
                            action = "Caricamento sezione catalogo non riuscito",
                            throwable = error,
                            metadata = mapOf(
                                "catalogo" to catalog.displayName,
                                "sezione" to it.title,
                                "pagina" to page,
                            ),
                        )
                    }
                    .getOrNull()
            }
        } ?: StreamCenterCatalogPage(emptyList(), false)
        val title = StreamCenterPlugin.resolveHomeTitlePlaceholders(
            request.name,
            Calendar.getInstance(),
            itemCount = catalogPage.items.size,
        )
        StreamCenterLogger.logMenu(
            action = "Sezione catalogo caricata",
            metadata = mapOf(
                "catalogo" to catalog.displayName,
                "catalogo_key" to catalog.key,
                "sezione" to title,
                "pagina" to page,
                "elementi" to catalogPage.items.size,
                "altra_pagina_disponibile" to catalogPage.hasNext,
            ),
        )
        logSearchCards(
            action = "Schede complete mostrate nella sezione catalogo",
            contextDetails = mapOf(
                "catalogo" to catalog.displayName,
                "sezione" to title,
                "pagina" to page,
                "altra_pagina_disponibile" to catalogPage.hasNext,
            ),
            items = catalogPage.items,
        )
        return newHomePageResponse(
            HomePageList(
                name = title,
                list = catalogPage.items,
                isHorizontalImages = false,
            ),
            hasNext = catalogPage.hasNext,
        )
    }
    val data = request.data
    if (data == REMOTE_QUEUE_SECTION_DATA) return remoteQueuePage(request)
    if (data == "library:catch_up") {
        val result = catchUp.page(page, StreamCenterPlugin.getHomeSectionCount(sharedPref, data))
        return newHomePageResponse(
            HomePageList(
                name = StreamCenterPlugin.resolveHomeTitlePlaceholders(
                    request.name, Calendar.getInstance(), itemCount = result.items.size,
                ),
                list = result.items,
                isHorizontalImages = false,
            ),
            hasNext = result.hasNext,
        )
    }
    if (data.startsWith(StreamCenterHomeImports.DATA_PREFIX)) {
        return importedHomePage(page, request)
    }
    val showHomeScores = showCardScores
    val showAnimeDubStatus = !performanceMode && StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref)
    val showAnimeEpisodeNumber = !performanceMode && StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref)
    val limit = StreamCenterPlugin.getHomeSectionCount(sharedPref, data)

    StreamCenterLogger.logMenu(
        action = "Caricamento sezione Home avviato",
        metadata = mapOf(
            "api" to name,
            "sezione" to request.name,
            "dati_sezione" to data,
            "pagina" to page,
            "limite" to limit,
            "modalita_prestazioni" to performanceMode,
            "punteggi_visibili" to showHomeScores,
        ),
    )
    val loadItems: suspend () -> List<SearchResponse> = loadItems@{
        when {
            data == "au:calendar" -> fetchAnimeUnityCalendarHome(
                limit,
                showHomeScores,
                showAnimeDubStatus,
                showAnimeEpisodeNumber,
            )
            data == "au:latest" -> fetchAnimeUnityLatestHome(
                limit,
                showHomeScores,
                showAnimeDubStatus,
                showAnimeEpisodeNumber,
            )
            data == "au:random" -> fetchAnimeUnityRandomHome(
                limit,
                showHomeScores,
                showAnimeDubStatus,
                showAnimeEpisodeNumber,
            )
            data == "au:popular" -> fetchAnimeUnityPopularHome(
                page,
                limit,
                showHomeScores,
                showAnimeDubStatus,
                showAnimeEpisodeNumber,
                resolveVariants = !performanceMode,
            )
            data.startsWith("au:archive:") -> {
                val sectionKey = data.substringAfter("au:archive:")
                val filters = StreamCenterPlugin.getAnimeCustomSectionFilters(sharedPref, sectionKey)
                    ?: return@loadItems emptyList()
                fetchAnimeUnityArchiveHome(
                    filters = filters,
                    offset = (page - 1) * AU_ARCHIVE_BATCH_SIZE,
                    limit = limit,
                    showScore = showHomeScores,
                    showDubStatus = showAnimeDubStatus,
                    showEpisodeNumber = showAnimeEpisodeNumber,
                )
            }
            data.startsWith("sc:archive:tv_custom:") -> {
                val sectionKey = data.substringAfter("sc:archive:tv_custom:")
                val filters = StreamCenterPlugin.getTvCustomSectionFilters(sharedPref, sectionKey)
                    ?: return@loadItems emptyList()
                fetchStreamingCommunityTvArchiveHome(filters, page, limit, showHomeScores)
            }
            data.startsWith("sc:archive:movie_custom:") -> {
                val sectionKey = data.substringAfter("sc:archive:movie_custom:")
                val filters = StreamCenterPlugin.getMovieCustomSectionFilters(sharedPref, sectionKey)
                    ?: return@loadItems emptyList()
                fetchStreamingCommunityMovieArchiveHome(filters, page, limit, showHomeScores)
            }
            data.startsWith("sc:archive:") -> fetchStreamingCommunityArchiveHome(data, limit, showHomeScores)
            data.startsWith("sc:") -> fetchStreamingCommunityHome(data, limit, showHomeScores)
            data.startsWith("iptv:section:") -> {
                val sectionKey = data.substringAfter("iptv:section:")
                val ordered = StreamCenterPlugin.getIptvSectionChannelOrder(sharedPref, sectionKey)
                fetchIptvChannelsByIds(ordered, maxOf(limit, ordered.size))
            }
            data.startsWith("tracking:") -> {
                val sectionKey = data.substringAfter("tracking:")
                val config = StreamCenterPlugin.getTrackingListConfig(sharedPref, sectionKey)
                    ?: return@loadItems emptyList()
                fetchTrackingListHome(config, limit)
            }
            else -> emptyList()
        }
    }
    val itemsResult = runCatchingCancellable {
        if (data.startsWith("au:")) {
            withTimeoutOrNull(6_000L) { loadItems() } ?: run {
                StreamCenterLogger.logMenu(
                    action = "Tempo massimo di caricamento Home AnimeUnity raggiunto",
                    metadata = mapOf("sezione" to data, "pagina" to page, "limite_ms" to 6_000L),
                )
                emptyList()
            }
        } else {
            loadItems()
        }
    }
    itemsResult.exceptionOrNull()?.let { error ->
        StreamCenterLogger.logMenuError(
            action = "Caricamento sezione Home non riuscito",
            throwable = error,
            metadata = mapOf(
                "api" to name,
                "sezione" to request.name,
                "dati_sezione" to data,
                "pagina" to page,
            ),
        )
    }
    val items = itemsResult.getOrDefault(emptyList())

    val hasNext = (
        data == "au:popular" ||
            data.startsWith("au:archive:") ||
            data.startsWith("sc:archive:tv_custom:") ||
            data.startsWith("sc:archive:movie_custom:")
        ) && items.size >= limit
    val title = StreamCenterPlugin.resolveHomeTitlePlaceholders(
        request.name,
        Calendar.getInstance(),
        itemCount = items.size,
    )
    StreamCenterLogger.logMenu(
        action = "Sezione Home caricata",
        metadata = mapOf(
            "api" to name,
            "sezione" to title,
            "dati_sezione" to data,
            "pagina" to page,
            "elementi" to items.size,
            "altra_pagina_disponibile" to hasNext,
        ),
    )
    logSearchCards(
        action = "Schede complete mostrate nella sezione Home",
        contextDetails = mapOf(
            "sezione" to title,
            "dati_sezione" to data,
            "pagina" to page,
            "altra_pagina_disponibile" to hasNext,
        ),
        items = items,
    )
    return newHomePageResponse(
        HomePageList(
            name = title,
            list = items,
            isHorizontalImages = false,
        ),
        hasNext = hasNext,
    )
}

internal suspend fun StreamCenter.importedHomePage(page: Int, request: MainPageRequest): HomePageResponse {
    val section = StreamCenterHomeImports.read(sharedPref)
        .firstOrNull { it.definition.data == request.data }
    var hasNext = false
    var horizontal = false
    val items = runCatchingCancellable {
        if (section == null || !StreamCenterPlugin.isHomeSectionEnabled(sharedPref, section.definition)) {
            return@runCatchingCancellable emptyList<SearchResponse>()
        }
        withTimeoutOrNull(25_000L) {
            when (section.kind) {
                StreamCenterHomeImports.CATALOG -> {
                    val catalog = StreamCenterCatalogs.allCatalogs(sharedPref).firstOrNull { it.key == section.sourceKey }
                    val descriptor = catalog?.sections?.firstOrNull { it.key == section.sectionKey }
                        ?.takeIf(this@importedHomePage::isCatalogSectionAvailable)
                    val result = if (descriptor == null) null
                    else if (descriptor.trackingServiceKey != null) {
                        catalogTrackingConfig(descriptor)?.let { fetchTrackingListHomePage(it, page) }
                    } else importedCatalogClient(catalog)?.section(this@importedHomePage, descriptor, page, showCardScores)
                    hasNext = result?.hasNext == true
                    result?.items.orEmpty()
                }
                StreamCenterHomeImports.EXTENSION -> {
                    val source = it.dogior.hadEnough.extensions.InstalledExtensionSources.available(forHome = true)
                        .flatMap { it.sources }.firstOrNull { it.key == section.sourceKey }
                    val response = source?.let { StreamCenterHomeImports.extensionPage(it.api, section, page) }
                    val list = StreamCenterHomeImports.extensionList(response, section)
                    hasNext = list != null && response?.hasNext == true
                    horizontal = list?.isHorizontalImages == true
                    list?.list.orEmpty()
                }
                else -> emptyList()
            }
        }.orEmpty()
    }.onFailure { error ->
        StreamCenterLogger.logMenuError(
            action = "Caricamento sezione aggiunta non riuscito", throwable = error,
            metadata = mapOf("fonte" to section?.sourceName, "sezione" to request.name, "pagina" to page),
        )
    }.getOrDefault(emptyList())
    val title = StreamCenterPlugin.resolveHomeTitlePlaceholders(request.name, Calendar.getInstance(), itemCount = items.size)
    return newHomePageResponse(HomePageList(title, items, horizontal), hasNext = hasNext && items.isNotEmpty())
}

internal const val REMOTE_QUEUE_SECTION_DATA = "remote:queue"

internal fun StreamCenter.remoteQueueSection(): List<Pair<String, String>> {
    val context = StreamCenterPlugin.activeContext ?: return emptyList()
    if (searchSection != StreamCenter.SEARCH_SECTION_MAIN || !StreamCenterRemote.isEnabled()) return emptyList()
    if (remoteQueueItems(context).isEmpty()) return emptyList()
    return listOf(REMOTE_QUEUE_SECTION_DATA to "Prossimi da guardare")
}

private fun StreamCenter.remoteQueueItems(context: Context) = StreamCenterRemoteQueue.items(context)
    .filter { it.apiName == name || it.apiName.startsWith("$name ") }

private fun StreamCenter.remoteQueuePage(request: MainPageRequest): HomePageResponse {
    val context = StreamCenterPlugin.activeContext
    val items = if (context == null || !StreamCenterRemote.isEnabled()) emptyList() else remoteQueueItems(context)
    return newHomePageResponse(
        HomePageList(
            name = request.name,
            list = items.map { item ->
                newMovieSearchResponse(item.name, item.url, TvType.Movie) { posterUrl = item.poster }
            },
            isHorizontalImages = false,
        ),
        hasNext = false,
    )
}
