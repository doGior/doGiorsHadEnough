package it.dogior.hadEnough

import android.content.SharedPreferences
import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.anime.metadata.AniZipMetadataClient
import it.dogior.hadEnough.anime.metadata.AniListMetadataClient
import it.dogior.hadEnough.anime.metadata.AnimeMetadataHttpClient
import it.dogior.hadEnough.anime.metadata.AnimeEpisodeMetadataMerger
import it.dogior.hadEnough.anime.metadata.AnimeJapaneseTitleHints
import it.dogior.hadEnough.anime.metadata.AnimeJapaneseTitleResolver
import it.dogior.hadEnough.anime.metadata.KitsuMetadataClient
import it.dogior.hadEnough.anime.metadata.TmdbAnimeEpisodeMetadataClient
import it.dogior.hadEnough.anime.source.absoluteProviderUrl
import it.dogior.hadEnough.anime.source.sourceTitleDedupKey
import it.dogior.hadEnough.anime.source.sourceTitleScore
import it.dogior.hadEnough.anime.source.cleanAnimeUnityTitle
import it.dogior.hadEnough.anime.source.displayTitle
import it.dogior.hadEnough.anime.source.isDub
import it.dogior.hadEnough.anime.source.AnimeWorldSourceClient
import it.dogior.hadEnough.anime.source.AnimeSaturnSourceClient
import it.dogior.hadEnough.anime.source.AnimeUnitySourceClient
import it.dogior.hadEnough.anime.metadata.JikanMetadataClient
import it.dogior.hadEnough.availability.StreamCenterAvailabilityChecker
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.iptv.StreamCenterIptv
import it.dogior.hadEnough.serie_movie.StreamingCommunityClient
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.parseWholeAnimeEpisodeNumber
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class StreamCenter internal constructor(
    private val sharedPref: SharedPreferences? = null,
    private val searchSection: String = SEARCH_SECTION_MAIN,
    private val catalogDefinition: StreamCenterCatalogDefinition? = null,
) : MainAPI() {
    override var mainUrl = catalogDefinition?.websiteUrl ?: "https://www.themoviedb.org"
    override var name = catalogDefinition?.displayName ?: when (searchSection) {
        SEARCH_SECTION_MOVIES -> "StreamCenter [Film]"
        SEARCH_SECTION_SERIES -> "StreamCenter [Serie TV]"
        SEARCH_SECTION_ANIME -> "StreamCenter [Anime]"
        SEARCH_SECTION_LIVE -> "StreamCenter [TV]"
        else -> "StreamCenter"
    }
    override var lang = "it"
    override var canBeOverridden = true
    override val hasMainPage: Boolean
        get() = if (catalogDefinition != null) catalogIsActive else searchSection == SEARCH_SECTION_MAIN
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = if (catalogDefinition != null) {
        catalogDefinition.supportedTypes
    } else {
        when (searchSection) {
            SEARCH_SECTION_MOVIES -> setOf(TvType.Movie)
            SEARCH_SECTION_SERIES -> setOf(TvType.TvSeries)
            SEARCH_SECTION_ANIME -> setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
            SEARCH_SECTION_LIVE -> setOf(TvType.Live)
            else -> setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Live)
        }
    }

    override val supportedSyncNames: Set<SyncIdName>
        get() = when (catalogDefinition?.key) {
            "tmdb" -> setOf(SyncIdName.Simkl)
            "anilist", "myanimelist", "kitsu", "simkl" -> ANIME_SYNC_NAMES
            null -> when (searchSection) {
                SEARCH_SECTION_MAIN, SEARCH_SECTION_ANIME -> ANIME_SYNC_NAMES
                SEARCH_SECTION_MOVIES, SEARCH_SECTION_SERIES -> setOf(SyncIdName.Simkl)
                else -> emptySet()
            }
            else -> emptySet()
        }

    private val tmdbLanguage = "it-IT"
    private val animeMarker = "streamcenter_media=anime"
    private val animeAnilistParam = "streamcenter_anilist"
    private val animeMalParam = "streamcenter_mal"
    private val animeVariantParam = "streamcenter_variant"
    private val anilistOnlyPath = "/anilist/"
    private val malOnlyPath = "/mal/"
    private val scHomePath = "/sc/"
    private val trackingHomePath = "/tracking/"
    private val scHomeTypeParam = "streamcenter_sc_type"
    private val animeUnityUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMEUNITY }
    private val animeWorldUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMEWORLD }
    private val animeSaturnUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMESATURN }
    private val streamingCommunityRootUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_STREAMINGCOMMUNITY } + "/"

    private val streamingCommunityMainUrl: String
        get() = "${streamingCommunityRootUrl}it"
    private val vidxGoUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_VIDXGO)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_VIDXGO }
    private val vixSrcBaseUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_VIXSRC)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_VIXSRC }
    private val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )
    private val tmdbCatalog = StreamCenterTmdbCatalog(headers)
    private val myAnimeListCatalog = StreamCenterMyAnimeListCatalog(headers)
    private val aniListCatalog by lazy {
        StreamCenterAniListCatalog(aniListMetadataClient) {
            StreamCenterPlugin.getAnimeCardTitle(sharedPref)
        }
    }
    private val kitsuCatalog by lazy {
        StreamCenterKitsuCatalog(kitsuMetadataClient) {
            StreamCenterPlugin.getAnimeCardTitle(sharedPref)
        }
    }
    private val simklCatalog = StreamCenterSimklCatalog()
    private val searchTitleAliases = ConcurrentHashMap<String, List<String>>()
    private data class CardProvenance(
        val defaultSource: String,
        val fieldSources: Map<String, List<String>> = emptyMap(),
        val fieldNotes: Map<String, String> = emptyMap(),
    ) {
        fun sources(path: String): List<String> {
            val direct = fieldSources[path]
            if (!direct.isNullOrEmpty()) return direct
            var parent = path
            while ('.' in parent) {
                parent = parent.substringBeforeLast('.')
                val inherited = fieldSources[parent]
                if (!inherited.isNullOrEmpty()) return inherited
            }
            return listOf(defaultSource)
        }

        fun note(path: String): String? {
            return fieldNotes[path] ?: fieldNotes.entries
                .firstOrNull { (key, _) -> path.startsWith("$key.") }
                ?.value
        }
    }
    private val pendingCardProvenance = Collections.synchronizedMap(
        IdentityHashMap<LoadResponse, CardProvenance>(),
    )
    private val stremioCatalogClient: StreamCenterStremioCatalog? by lazy {
        catalogDefinition?.let { definition ->
            definition.stremioAddon?.let { addon ->
                StreamCenterStremioCatalog(addon, definition.key)
            }
        }
    }
    private val catalogClient: StreamCenterCatalog? by lazy {
        stremioCatalogClient ?: when (catalogDefinition?.key) {
            "tmdb" -> tmdbCatalog
            "anilist" -> aniListCatalog
            "myanimelist" -> myAnimeListCatalog
            "kitsu" -> kitsuCatalog
            "simkl" -> simklCatalog
            else -> null
        }
    }
    private val animeMetadataHttpClient = AnimeMetadataHttpClient()
    private val aniZipMetadataClient = AniZipMetadataClient(animeMetadataHttpClient)
    private val kitsuMetadataClient = KitsuMetadataClient(animeMetadataHttpClient)
    private val jikanMetadataClient = JikanMetadataClient(animeMetadataHttpClient)
    private val tmdbAnimeEpisodeMetadataClient = TmdbAnimeEpisodeMetadataClient(
        headers = headers,
        cacheDirectory = { StreamCenterPlugin.activeContext?.cacheDir },
    )
    private val animeEpisodeMetadataMerger = AnimeEpisodeMetadataMerger(
        kitsuClient = kitsuMetadataClient,
        jikanClient = jikanMetadataClient,
    )
    private val aniListMetadataClient = AniListMetadataClient(
        performanceMode = { performanceMode },
        minRequestIntervalMs = { StreamCenterPlugin.getAnilistMinIntervalMs(sharedPref) },
    )
    private val animeJapaneseTitleResolver = AnimeJapaneseTitleResolver(
        kitsuMetadataClient = kitsuMetadataClient,
        aniListMetadataClient = aniListMetadataClient,
        jikanMetadataClient = jikanMetadataClient,
    )
    private val animeWorldSourceClient = AnimeWorldSourceClient(
        baseUrl = { animeWorldUrl },
        headers = headers,
        queryLimit = { animeSearchQueryLimit },
        detailCandidateLimit = { awDetailCandidateLimit },
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD) },
    )
    private val animeSaturnSourceClient = AnimeSaturnSourceClient(
        baseUrl = { animeSaturnUrl },
        headers = headers,
        queryLimit = { animeSearchQueryLimit },
        detailCandidateLimit = { animeSaturnDetailCandidateLimit },
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN) },
    )
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val performanceMode: Boolean
        get() = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref)
    private val showCardScores: Boolean
        get() = !performanceMode && StreamCenterPlugin.shouldShowHomeScore(sharedPref)
    private val catalogIsActive: Boolean
        get() = catalogDefinition?.let { catalog ->
            StreamCenterPlugin.isHomeCategoryEnabled(sharedPref, StreamCenterCatalogs.CATEGORY_KEY) &&
                StreamCenterCatalogs.isConfigured(sharedPref, catalog)
        } ?: false
    private val sourceGroupTimeoutMs: Long
        get() = if (performanceMode) SOURCE_GROUP_TIMEOUT_PERFORMANCE_MS else SOURCE_GROUP_TIMEOUT_MS
    private val animeSearchQueryLimit: Int
        get() = if (performanceMode) ANIME_SEARCH_QUERY_LIMIT_PERFORMANCE else ANIME_SEARCH_QUERY_LIMIT
    private val auArchiveQueryLimit: Int
        get() = if (performanceMode) AU_ARCHIVE_QUERY_LIMIT_PERFORMANCE else AU_ARCHIVE_QUERY_LIMIT
    private val awDetailCandidateLimit: Int
        get() = if (performanceMode) AW_DETAIL_CANDIDATE_LIMIT_PERFORMANCE else AW_DETAIL_CANDIDATE_LIMIT
    private val animeSaturnDetailCandidateLimit: Int
        get() = if (performanceMode) {
            ANIMESATURN_DETAIL_CANDIDATE_LIMIT_PERFORMANCE
        } else {
            ANIMESATURN_DETAIL_CANDIDATE_LIMIT
        }
    private val animeUnitySourceClient = AnimeUnitySourceClient(
        sharedPref = sharedPref,
        baseUrl = { animeUnityUrl },
        archiveQueryLimit = { auArchiveQueryLimit },
        posterResolver = ::animeUnityPoster,
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) },
    )

    private fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore("/").substringBefore(":")
    }
    private val streamingCommunityClient = StreamingCommunityClient(
        sharedPref = sharedPref,
        rootUrl = { streamingCommunityRootUrl },
        mainUrl = { streamingCommunityMainUrl },
        defaultHeaders = headers,
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) },
    )

    init {
        synchronized(activeInstances) { activeInstances += this }
    }

    private fun clearRuntimeConfiguration() {
        animeUnitySourceClient.resetSession()
        streamingCommunityClient.resetSession()
    }

    override val mainPage
        get() = when {
            catalogDefinition == null -> StreamCenterPlugin.getConfiguredHomeSections(sharedPref)
                .map { it.definition.data to it.title }
            !catalogIsActive -> emptyList()
            else -> StreamCenterCatalogs.selectedSections(sharedPref, catalogDefinition)
                .filter(::isCatalogSectionAvailable)
                .map { section ->
                    StreamCenterCatalogs.sectionData(catalogDefinition, section) to section.title
                }
        }.let { configuredSections -> mainPageOf(*configuredSections.toTypedArray()) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
                        runCatching { fetchTrackingListHomePage(config, page) }
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
                    runCatching { catalogClient?.section(this, it, page, showCardScores) }
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
        val itemsResult = runCatching {
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
                        ?: return@runCatching emptyList()
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
                        ?: return@runCatching emptyList()
                    fetchStreamingCommunityTvArchiveHome(filters, page, limit, showHomeScores)
                }
                data.startsWith("sc:archive:movie_custom:") -> {
                    val sectionKey = data.substringAfter("sc:archive:movie_custom:")
                    val filters = StreamCenterPlugin.getMovieCustomSectionFilters(sharedPref, sectionKey)
                        ?: return@runCatching emptyList()
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
                        ?: return@runCatching emptyList()
                    fetchTrackingListHome(config, limit)
                }
                else -> emptyList()
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

    private data class AnimeUnityHomeItem(
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

    private fun JSONObject.toAnimeUnityHomeItem(episodeNumber: Int? = null): AnimeUnityHomeItem? {
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

    private fun AnimeUnityAnime.toAnimeUnityHomeItem(): AnimeUnityHomeItem? {
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

    private fun animeHomeRoutingUrl(malId: Int?, anilistId: Int?): String? {
        return when {
            anilistId != null -> markAnilistUrl(anilistId, malId)
            malId != null -> markMalOnlyUrl(malId)
            else -> null
        }
    }

    private fun selectAnimeUnityHomeItems(
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

    private fun markAnilistUrl(anilistId: Int, malId: Int? = null): String {
        val params = buildList {
            add(animeMarker)
            add("$animeAnilistParam=$anilistId")
            malId?.let { add("$animeMalParam=$it") }
        }
        return "https://anilist.co/anime/$anilistId?${params.joinToString("&")}"
    }

    private fun animeUnityImageHost(): String {
        val host = hostOf(animeUnityUrl)
        return when {
            host == "animeunity.so" -> "img.animeunity.so"
            host.startsWith("www.") -> host.replaceFirst("www.", "img.")
            host.startsWith("img.") -> host
            else -> "img.$host"
        }
    }

    private fun animeUnityPoster(imageUrl: String?): String? {
        val raw = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("http")) return raw
        val fileName = raw.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
        return "https://${animeUnityImageHost()}/anime/$fileName"
    }

    private suspend fun buildGroupedAnimeUnityHomeResponses(
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

    private suspend fun buildBaseCatalogAnimeUnityRecommendations(
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
        if (relatedResponses.size >= limit || recommendations.isEmpty()) return relatedResponses
        val suggestedResponses = buildGroupedAnimeUnityHomeResponses(
            items = recommendations.mapNotNull { recommendation -> recommendation.toAnimeUnityHomeItem() },
            showDubStatus = showDubStatus,
            showEpisodeNumber = showEpisodeNumber,
            showScore = showCardScores,
            limit = limit,
        )
        return (relatedResponses + suggestedResponses)
            .distinctBy { response -> response.url }
            .take(limit)
    }

    private suspend fun fetchAnimeUnityHtml(path: String): Document {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
        val url = "$animeUnityUrl$path"
        val html = fetchText {
            app.get(url, headers = headers).text
        }
        return Jsoup.parse(html, url)
    }

    private suspend fun fetchAnimeUnityCalendarHome(
        limit: Int,
        showScore: Boolean,
        showDubStatus: Boolean,
        showEpisodeNumber: Boolean,
    ): List<SearchResponse> {
        val today = normalizeDayName(currentItalianCalendarDayName())
        val doc = fetchAnimeUnityHtml("/calendario")
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

    private suspend fun fetchAnimeUnityLatestHome(
        limit: Int,
        showScore: Boolean,
        showDubStatus: Boolean,
        showEpisodeNumber: Boolean,
    ): List<SearchResponse> {
        val doc = fetchAnimeUnityHtml("/")
        val json = doc.selectFirst("#ultimi-episodi layout-items")?.attr("items-json").orEmpty()
        if (json.isBlank()) return emptyList()
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
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

    private suspend fun fetchAnimeUnityRandomHome(
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

    private suspend fun fetchAnimeUnityPopularHome(
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
            if (json.isBlank()) break
            val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: break
            if (data.length() == 0) break

            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: continue
                val item = obj.toAnimeUnityHomeItem() ?: continue
                items += if (resolveVariants) {
                    fetchAnimeUnityPopularHomeVariants(item)
                } else {
                    listOf(item.copy(episodeNumber = item.availableEpisodes))
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

    private fun groupedAnimeUnityHomeCount(items: List<AnimeUnityHomeItem>): Int {
        return items.mapNotNull { animeHomeRoutingUrl(it.malId, it.anilistId) }
            .distinct()
            .size
    }

    private suspend fun fetchAnimeUnityPopularHomeVariants(item: AnimeUnityHomeItem): List<AnimeUnityHomeItem> {
        val variants = runCatching {
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

    private fun currentItalianCalendarDayName(): String {
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

    private fun normalizeDayName(day: String?): String {
        return java.text.Normalizer.normalize(day.orEmpty(), java.text.Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private suspend fun fetchStreamingCommunityHome(
        data: String,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        val parts = data.split(":")
        val type = parts.getOrNull(1) ?: "tv"
        val sliderName = parts.getOrNull(2) ?: "trending"
        val pageUrl = when {
            type == "tv" && sliderName == "upcoming" -> streamingCommunityMainUrl
            type == "tv" -> "$streamingCommunityMainUrl/tv-shows"
            type == "movie" -> "$streamingCommunityMainUrl/movies"
            else -> streamingCommunityMainUrl
        }

        val props = streamingCommunityClient.fetchPageProps(pageUrl) ?: return emptyList()
        val sliders = props.optJSONArray("sliders") ?: return emptyList()
        val titles = (0 until sliders.length())
            .asSequence()
            .mapNotNull { sliders.optJSONObject(it) }
            .firstOrNull { it.optNullableString("name").equals(sliderName, ignoreCase = true) }
            ?.optJSONArray("titles")
            ?: return emptyList()

        return titles.toStreamingCommunityHomeResponses(type, streamingCommunityClient.cdnUrl(props), limit, showScore)
    }

    private suspend fun fetchStreamingCommunityArchiveHome(
        data: String,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        val parts = data.split(":")
        val type = parts.getOrNull(2) ?: "movie"
        val genreId = parts.getOrNull(3)?.toIntOrNull() ?: return emptyList()
        val pageUrl = "$streamingCommunityMainUrl/archive?type=$type&genre%5B%5D=$genreId&sort=views"

        val props = streamingCommunityClient.fetchPageProps(pageUrl) ?: return emptyList()
        val titles = props.optJSONArray("titles") ?: return emptyList()
        return titles.toStreamingCommunityHomeResponses(type, streamingCommunityClient.cdnUrl(props), limit, showScore)
    }

    private suspend fun fetchStreamingCommunityTvArchiveHome(
        filters: StreamCenterTvArchiveFilters,
        page: Int,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        return fetchStreamingCommunityArchiveByType(filters, "tv", page, limit, showScore)
    }

    private suspend fun fetchStreamingCommunityMovieArchiveHome(
        filters: StreamCenterMovieArchiveFilters,
        page: Int,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        return fetchStreamingCommunityArchiveByType(filters, "movie", page, limit, showScore)
    }

    private suspend fun fetchStreamingCommunityArchiveByType(
        filters: StreamCenterTvArchiveFilters,
        type: String,
        page: Int,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        val query = buildList {
            add("type=$type")
            filters.genreId?.let { add("genre%5B%5D=$it") }
            filters.year?.let { add("year=$it") }
            filters.minimumScore?.let { add("score=$it") }
            filters.countryId?.let { add("country%5B%5D=$it") }
            add("sort=${filters.sort ?: "release_date"}")
            if (page > 1) add("page=$page")
        }.joinToString("&")
        val props = streamingCommunityClient.fetchPageProps("$streamingCommunityMainUrl/archive?$query")
            ?: return emptyList()
        val titles = props.optJSONArray("titles") ?: return emptyList()
        return titles.toStreamingCommunityHomeResponses(
            type = type,
            cdnUrl = streamingCommunityClient.cdnUrl(props),
            limit = limit,
            showScore = showScore,
        )
    }

    private suspend fun fetchStreamingCommunityRecommendations(
        title: StreamingCommunityTitle,
        limit: Int = 20,
    ): List<SearchResponse> {
        return runCatching {
            streamingCommunityClient.fetchRelatedTitles(title, limit)
                .mapNotNull(::streamingCommunityRelatedResponse)
                .distinctBy { it.url }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    private fun streamingCommunityRelatedResponse(title: StreamingCommunityTitle): SearchResponse? {
        val url = "$mainUrl$scHomePath${title.id}-${title.slug}?$scHomeTypeParam=${title.type}"
        val poster = streamingCommunityClient.imageUrl(title.posterFilename)
        val scoreValue = title.score
        return when (title.type) {
            "tv" -> newTvSeriesSearchResponse(title.name, url, TvType.TvSeries) {
                this.posterUrl = poster
                if (showCardScores) scoreValue?.let { this.score = Score.from(it, 10) }
            }
            "movie" -> newMovieSearchResponse(title.name, url, TvType.Movie) {
                this.posterUrl = poster
                if (showCardScores) scoreValue?.let { this.score = Score.from(it, 10) }
            }
            else -> null
        }
    }

    private data class ScHomeCard(
        val name: String,
        val url: String,
        val poster: String?,
        val scScore: String?,
    )

    private fun JSONArray.toStreamingCommunityHomeResponses(
        type: String,
        cdnUrl: String,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        val source = this
        val seen = mutableSetOf<String>()
        val cards = buildList {
            for (index in 0 until source.length()) {
                val title = source.optJSONObject(index) ?: continue
                if (title.optNullableString("type") != type) continue
                val id = title.optNullableInt("id") ?: continue
                val slug = title.optNullableString("slug") ?: continue
                val name = title.optNullableString("name") ?: continue
                val url = "$mainUrl$scHomePath$id-$slug?$scHomeTypeParam=$type"
                if (!seen.add(url)) continue
                add(ScHomeCard(
                    name = name,
                    url = url,
                    poster = title.optJSONArray("images")?.streamingCommunityPosterFilename()
                        ?.let { "$cdnUrl/images/$it" },
                    scScore = title.optNullableString("score"),
                ))
                if (size >= limit) break
            }
        }
        return cards.map { card ->
            val scoreValue = card.scScore
            if (type == "tv") {
                newTvSeriesSearchResponse(card.name, card.url, TvType.TvSeries) {
                    this.posterUrl = card.poster
                    if (showScore) scoreValue?.let { this.score = Score.from(it, 10) }
                }
            } else {
                newMovieSearchResponse(card.name, card.url, TvType.Movie) {
                    this.posterUrl = card.poster
                    if (showScore) scoreValue?.let { this.score = Score.from(it, 10) }
                }
            }
        }
    }

    private fun JSONObject.toStreamingCommunityHomeResponse(
        type: String,
        cdnUrl: String,
        showScore: Boolean,
    ): SearchResponse? {
        val id = optNullableInt("id") ?: return null
        val slug = optNullableString("slug") ?: return null
        val name = optNullableString("name") ?: return null
        val poster = optJSONArray("images")?.streamingCommunityPosterFilename()
            ?.let { "$cdnUrl/images/$it" }
        val scoreValue = optNullableString("score")
        val url = "$mainUrl$scHomePath$id-$slug?$scHomeTypeParam=$type"
        return if (type == "tv") {
            newTvSeriesSearchResponse(name, url, TvType.TvSeries) {
                this.posterUrl = poster
                if (showScore) scoreValue?.let { this.score = Score.from(it, 10) }
            }
        } else {
            newMovieSearchResponse(name, url, TvType.Movie) {
                this.posterUrl = poster
                if (showScore) scoreValue?.let { this.score = Score.from(it, 10) }
            }
        }
    }

    private fun JSONArray.streamingCommunityPosterFilename(): String? {
        var fallback: String? = null
        for (index in 0 until length()) {
            val image = optJSONObject(index) ?: continue
            val filename = image.optNullableString("filename") ?: continue
            if (image.optNullableString("type") == "poster") return filename
            if (fallback == null) fallback = filename
        }
        return fallback
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchSearchResults(query, 1).first
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = fetchSearchResults(query, page)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return fetchSearchResults(query, 1).first
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        StreamCenterVpnGuard.requireInternetAccess(sharedPref)
        val rawId = id.substringBefore("/").trim().toIntOrNull() ?: return null
        val numericId = if (name == SyncIdName.Kitsu) resolveKitsuLibraryMediaId(rawId) else rawId
        return when (catalogDefinition?.key) {
            "tmdb" -> resolveTmdbTrackingUrl(name, numericId, setOf("movies", "tv"))
            "anilist" -> resolveAniListTrackingUrl(name, numericId)
            "myanimelist" -> resolveMyAnimeListTrackingUrl(name, numericId)
            "kitsu" -> resolveKitsuTrackingUrl(name, numericId)
            "simkl" -> resolveSimklTrackingUrl(name, numericId)
            null -> when (searchSection) {
                SEARCH_SECTION_MOVIES -> resolveTmdbTrackingUrl(name, numericId, setOf("movies"))
                SEARCH_SECTION_SERIES -> resolveTmdbTrackingUrl(name, numericId, setOf("tv"))
                SEARCH_SECTION_MAIN, SEARCH_SECTION_ANIME -> when (name) {
                    SyncIdName.Anilist -> markAnilistUrl(numericId)
                    SyncIdName.MyAnimeList -> markMalOnlyUrl(numericId)
                    SyncIdName.Kitsu -> "https://kitsu.io/anime/$numericId"
                    SyncIdName.Simkl -> resolveSimklTrackingUrl(name, numericId)
                    else -> null
                }
                else -> null
            }
            else -> null
        }
    }

    private suspend fun resolveKitsuLibraryMediaId(id: Int): Int {
        val account = AccountManager.syncApis
            .firstOrNull { it.syncIdName == SyncIdName.Kitsu }
            ?.authData()
            ?: return id
        val root = runCatching {
            JSONObject(
                app.get(
                    "https://kitsu.io/api/edge/library-entries/$id?include=anime",
                    headers = mapOf("Authorization" to "Bearer ${account.token.accessToken}"),
                    cacheTime = 0,
                ).text,
            )
        }.getOrNull() ?: return id
        return root.optJSONObject("data")
            ?.optJSONObject("relationships")
            ?.optJSONObject("anime")
            ?.optJSONObject("data")
            ?.optNullableString("id")
            ?.toIntOrNull()
            ?: root.optJSONArray("included")
                ?.optJSONObject(0)
                ?.optNullableString("id")
                ?.toIntOrNull()
            ?: id
    }

    private suspend fun resolveAniListTrackingUrl(name: SyncIdName, id: Int): String? {
        if (name == SyncIdName.Anilist) return "https://anilist.co/anime/$id"
        val ids = resolveAnimeTrackingIds(name, id) ?: return null
        val anilistId = ids.anilist ?: ids.mal?.let { malId ->
            aniListMetadataClient.fetchMetadata(null, malId, forceFullMetadata = true)?.anilistId
        }
        return anilistId?.let { "https://anilist.co/anime/$it" }
    }

    private suspend fun resolveMyAnimeListTrackingUrl(name: SyncIdName, id: Int): String? {
        if (name == SyncIdName.MyAnimeList) return "https://myanimelist.net/anime/$id"
        val ids = resolveAnimeTrackingIds(name, id) ?: return null
        val malId = ids.mal ?: ids.anilist?.let { anilistId ->
            aniListMetadataClient.fetchMetadata(anilistId, null, forceFullMetadata = true)?.malId
        }
        return malId?.let { "https://myanimelist.net/anime/$it" }
    }

    private suspend fun resolveKitsuTrackingUrl(name: SyncIdName, id: Int): String? {
        if (name == SyncIdName.Kitsu) return "https://kitsu.io/anime/$id"
        val ids = resolveAnimeTrackingIds(name, id) ?: return null
        val kitsuId = ids.kitsu ?: kitsuMetadataClient.resolveAnimeId(ids.mal, ids.anilist)
        return kitsuId?.let { "https://kitsu.io/anime/$it" }
    }

    private suspend fun resolveSimklTrackingUrl(
        name: SyncIdName,
        id: Int,
        allowedCategories: Set<String> = setOf("movies", "tv", "anime"),
    ): String? {
        return when (name) {
            SyncIdName.Simkl -> simklCatalog.resolveMediaUrl(
                simkl = id,
                allowedCategories = allowedCategories,
            )
            SyncIdName.Anilist -> simklCatalog.resolveMediaUrl(
                anilist = id,
                allowedCategories = allowedCategories,
            )
            SyncIdName.MyAnimeList -> simklCatalog.resolveMediaUrl(
                mal = id,
                allowedCategories = allowedCategories,
            )
            SyncIdName.Kitsu -> {
                val ids = resolveAnimeTrackingIds(name, id) ?: return null
                simklCatalog.resolveMediaUrl(
                    mal = ids.mal,
                    anilist = ids.anilist,
                    allowedCategories = allowedCategories,
                )
            }
            else -> null
        }
    }

    private suspend fun resolveSimklId(
        imdb: String? = null,
        tmdb: String? = null,
        mal: Int? = null,
        anilist: Int? = null,
        allowedCategories: Set<String>,
    ): Int? = runCatching {
        simklCatalog.resolveMediaUrl(
            imdb = imdb,
            tmdb = tmdb,
            mal = mal,
            anilist = anilist,
            allowedCategories = allowedCategories,
        )?.let(simklCatalog::mediaRoute)?.second
    }.getOrNull()

    private suspend fun resolveTmdbTrackingUrl(
        name: SyncIdName,
        id: Int,
        allowedCategories: Set<String>,
    ): String? {
        val simklUrl = resolveSimklTrackingUrl(name, id, allowedCategories) ?: return null
        val media = runCatching { simklCatalog.media(simklUrl) }.getOrNull() ?: return null
        val tmdbId = media.ids.tmdb ?: return null
        val type = if (media.category == "movies") "movie" else "tv"
        return "https://www.themoviedb.org/$type/$tmdbId"
    }

    private suspend fun resolveAnimeTrackingIds(name: SyncIdName, id: Int): StreamCenterTrackingIds? {
        return when (name) {
            SyncIdName.Anilist -> aniListMetadataClient.fetchMetadata(
                anilistId = id,
                malId = null,
                forceFullMetadata = true,
            )?.let { metadata ->
                StreamCenterTrackingIds(anilist = metadata.anilistId, mal = metadata.malId)
            } ?: StreamCenterTrackingIds(anilist = id)
            SyncIdName.MyAnimeList -> aniListMetadataClient.fetchMetadata(
                anilistId = null,
                malId = id,
                forceFullMetadata = true,
            )?.let { metadata ->
                StreamCenterTrackingIds(anilist = metadata.anilistId, mal = metadata.malId)
            } ?: StreamCenterTrackingIds(mal = id)
            SyncIdName.Kitsu -> runCatching {
                kitsuCatalog.media("https://kitsu.io/anime/$id")
            }.getOrNull()?.let { media ->
                StreamCenterTrackingIds(
                    anilist = media.anilistId,
                    mal = media.malId,
                    kitsu = media.id,
                )
            }
            SyncIdName.Simkl -> resolveSimklTrackingUrl(name, id, setOf("anime"))
                ?.let { url -> runCatching { simklCatalog.media(url) }.getOrNull() }
                ?.let { media ->
                    StreamCenterTrackingIds(
                        anilist = media.ids.anilist,
                        mal = media.ids.mal,
                        kitsu = media.ids.kitsu,
                        simkl = media.ids.simkl,
                    )
                }
            else -> null
        }
    }

    private suspend fun fetchSearchResults(query: String, page: Int): Pair<List<SearchResponse>, Boolean> = coroutineScope {
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
            val catalogPage = runCatching { catalogClient?.search(this@StreamCenter, query, page, showCardScores) }
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
        val result = when (searchSection) {
            SEARCH_SECTION_MAIN -> {
                fetchHomeSearchResults(query, page)
            }
            SEARCH_SECTION_MOVIES, SEARCH_SECTION_SERIES -> {
                val (scItems, scHasNext) = runCatching { searchStreamingCommunityWithTypoFallback(query, page) }
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
                runCatching { searchAnimeUnityWithTypoFallback(query, page) }
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
                if (page > 1) return@coroutineScope empty
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

    private suspend fun fetchHomeSearchResults(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> = supervisorScope {
        val empty = emptyList<SearchResponse>() to false
        val streamingCommunity = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)) {
            async(Dispatchers.IO) {
                StreamCenterLogger.logMenu(
                    action = "Ricerca fonte avviata",
                    metadata = mapOf("fonte" to "StreamingCommunity", "query" to query, "pagina" to page),
                )
                val result = runCatching { searchStreamingCommunityWithTypoFallback(query, page) }
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
                val result = runCatching { searchAnimeUnityWithTypoFallback(query, page) }
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

    private fun filterRelevantSearchResults(
        query: String,
        items: List<SearchResponse>,
    ): List<SearchResponse> {
        return items
            .map { response -> response to searchResponseScore(response, query) }
            .filter { (_, score) -> score >= SEARCH_RELEVANCE_MIN_SCORE }
            .sortedByDescending { (_, score) -> score }
            .map { (response) -> response }
    }

    private fun searchResponseScore(response: SearchResponse, query: String): Int {
        val mainTitleScore = sourceTitleScore(response.name, query)
        val alternativeTitleScore = searchTitleAliases[response.url]
            .orEmpty()
            .maxOfOrNull { title -> sourceTitleScore(title, query) }
            ?.minus(SEARCH_ALTERNATIVE_TITLE_PENALTY)
            ?.coerceAtLeast(0)
            ?: 0
        return maxOf(mainTitleScore, alternativeTitleScore)
    }

    private fun registerSearchTitleAliases(url: String, aliases: Collection<String?>) {
        val cleanedAliases = aliases
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy(::sourceTitleDedupKey)
        if (cleanedAliases.isEmpty()) return
        searchTitleAliases[url] = (searchTitleAliases[url].orEmpty() + cleanedAliases)
            .distinctBy(::sourceTitleDedupKey)
    }

    private suspend fun searchStreamingCommunityWithTypoFallback(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val primary = searchWithTypoFallback(
            query = query,
            page = page,
            search = ::searchStreamingCommunity,
        )
        if (page > 1 || filterRelevantSearchResults(query, primary.first).isNotEmpty()) return primary
        return searchStreamingCommunityWithTmdbTitles(query, primary)
    }

    private suspend fun searchAnimeUnityWithTypoFallback(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val primary = searchWithTypoFallback(
            query = query,
            page = page,
            search = ::searchAnimeUnity,
        )
        if (page > 1 || filterRelevantSearchResults(query, primary.first).isNotEmpty()) return primary
        return searchAnimeUnityWithAniListTitles(query, primary)
    }

    private suspend fun searchWithTypoFallback(
        query: String,
        page: Int,
        search: suspend (String, Int) -> Pair<List<SearchResponse>, Boolean>,
    ): Pair<List<SearchResponse>, Boolean> {
        val primary = search(query, page)
        if (page > 1 || filterRelevantSearchResults(query, primary.first).isNotEmpty()) return primary
        val fallbackPages = coroutineScope {
            typoFallbackQueries(query).map { fallbackQuery ->
                async(Dispatchers.IO) {
                    runCatching { search(fallbackQuery, 1) }
                        .getOrDefault(emptyList<SearchResponse>() to false)
                }
            }.awaitAll()
        }
        return (
            primary.first + fallbackPages.flatMap { it.first }
            ).distinctBy { it.url } to (primary.second || fallbackPages.any { it.second })
    }

    private fun typoFallbackQueries(query: String): List<String> {
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
            .take(4)
    }

    private suspend fun searchAnimeUnityWithAniListTitles(
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
                    val page = runCatching { searchAnimeUnity(title, 1) }
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

    private suspend fun searchStreamingCommunityWithTmdbTitles(
        query: String,
        primary: Pair<List<SearchResponse>, Boolean>,
    ): Pair<List<SearchResponse>, Boolean> {
        val candidates = runCatching {
            tmdbCatalog.search(this, query, 1, showScore = false)
        }.getOrNull()
            ?.items
            ?.take(SEARCH_ALTERNATIVE_TITLE_QUERY_LIMIT)
            .orEmpty()
        if (candidates.isEmpty()) return primary
        val bridgedPages = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    val page = runCatching { searchStreamingCommunity(candidate.name, 1) }
                        .getOrDefault(emptyList<SearchResponse>() to false)
                    val items = page.first
                        .filter { it.type == candidate.type }
                        .filter { response -> sourceTitleScore(response.name, candidate.name) >= SEARCH_BRIDGED_TITLE_MIN_SCORE }
                    items.forEach { response ->
                        registerSearchTitleAliases(response.url, listOf(query, candidate.name))
                    }
                    items to page.second
                }
            }.awaitAll()
        }
        return (
            primary.first + bridgedPages.flatMap { it.first }
            ).distinctBy { it.url } to (primary.second || bridgedPages.any { it.second })
    }

    private fun iptvSearchResponse(channel: StreamCenterIptv.Channel): SearchResponse {
        val encodedId = URLEncoder.encode(channel.id, StandardCharsets.UTF_8.name())
        return newLiveSearchResponse(channel.name, "${StreamCenterIptv.ROUTE_PREFIX}$encodedId") {
            posterUrl = channel.logo
            lang = StreamCenterIptv.languageCodeFor(channel.regionKey)
        }
    }

    private suspend fun fetchIptvChannelsByIds(ordered: List<String>, limit: Int): List<SearchResponse> {
        if (ordered.isEmpty()) return emptyList()
        val regionKeys = ordered.map { it.substringBefore(':') }.distinct()
        val channelsById = regionKeys.flatMap { regionKey ->
            runCatching { StreamCenterIptv.fetchChannels(regionKey) }.getOrDefault(emptyList())
        }.associateBy { it.id }
        return ordered.mapNotNull { channelsById[it] }.take(limit).map(::iptvSearchResponse)
    }

    private fun trackingRepo(service: StreamCenterTrackingService) =
        AccountManager.syncApis.firstOrNull { it.syncIdName == service.syncIdName }

    private fun catalogTrackingService(
        section: StreamCenterCatalogSection,
    ): StreamCenterTrackingService? {
        val serviceKey = section.trackingServiceKey ?: return null
        return StreamCenterPlugin.trackingServices.firstOrNull { it.key == serviceKey }
    }

    private fun catalogTrackingConfig(
        section: StreamCenterCatalogSection,
    ): StreamCenterTrackingListConfig? {
        val service = catalogTrackingService(section) ?: return null
        val listKey = section.trackingListKey ?: return null
        val status = service.statuses.firstOrNull { it.key == listKey } ?: return null
        return StreamCenterTrackingListConfig(service, status)
    }

    private fun isCatalogSectionAvailable(section: StreamCenterCatalogSection): Boolean {
        if (section.trackingServiceKey == null) return true
        return catalogTrackingService(section)?.let { trackingServiceIsConnected(it.syncIdName) } == true
    }

    private fun trackingServiceIsConnected(name: SyncIdName): Boolean {
        return AccountManager.syncApis.firstOrNull { it.syncIdName == name }?.authData() != null
    }

    private fun trackingRepo(config: StreamCenterTrackingListConfig) = trackingRepo(config.service)

    private suspend fun allTrackingLibraryItems(
        service: StreamCenterTrackingService,
    ): List<SyncAPI.LibraryItem> {
        val repo = AccountManager.syncApis.firstOrNull { it.syncIdName == service.syncIdName }
            ?: return emptyList()
        val account = repo.authData() ?: return emptyList()
        return repo.api.library(account)?.allLibraryLists?.flatMap { it.items }.orEmpty()
    }

    private suspend fun trackingServiceDetails(
        service: StreamCenterTrackingService,
        syncId: String,
    ): SyncAPI.SyncResult? {
        val repo = trackingRepo(service) ?: return null
        val account = repo.authData() ?: return null
        return runCatching { repo.api.load(account, syncId) }.getOrNull()
    }

    private fun trackingMediaId(
        service: StreamCenterTrackingService,
        item: SyncAPI.LibraryItem,
    ): String {
        return if (service.syncIdName == SyncIdName.Kitsu) {
            kitsuCatalog.mediaId(item.url)?.toString() ?: item.syncId
        } else {
            item.syncId
        }
    }

    private data class TrackingPersonalMetadata(
        val status: String? = null,
        val startedAt: String? = null,
        val finishedAt: String? = null,
    )

    private fun trackingStatusLabel(status: SyncWatchType?): String? = when (status) {
        SyncWatchType.WATCHING -> "Guardando"
        SyncWatchType.COMPLETED -> "Completato"
        SyncWatchType.ONHOLD -> "In pausa"
        SyncWatchType.DROPPED -> "Interrotto"
        SyncWatchType.PLANTOWATCH -> "Da guardare"
        SyncWatchType.REWATCHING -> "Riguardando"
        else -> null
    }

    private fun trackingStatusLabel(status: String?): String? = when (status?.trim()?.uppercase(Locale.ROOT)) {
        "CURRENT", "WATCHING", "WATCHED" -> "Guardando"
        "COMPLETED", "COMPLETE" -> "Completato"
        "PAUSED", "ON_HOLD", "ONHOLD", "HOLD" -> "In pausa"
        "DROPPED" -> "Interrotto"
        "PLANNING", "PLAN_TO_WATCH", "PLANTOWATCH", "PLANNED" -> "Da guardare"
        "REPEATING", "REWATCHING" -> "Riguardando"
        else -> null
    }

    private fun JSONObject.optTrackingText(key: String): String? = optString(key)
        .trim()
        .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun formatTrackingDate(value: String?): String? {
        val match = value?.trim()?.let {
            Regex("""^(\\d{4})-(\\d{1,2})-(\\d{1,2})""").find(it)
        } ?: return null
        return "${match.groupValues[3].padStart(2, '0')}/${match.groupValues[2].padStart(2, '0')}/${match.groupValues[1]}"
    }

    private fun formatTrackingDate(value: JSONObject?): String? {
        val year = value?.optInt("year") ?: 0
        val month = value?.optInt("month") ?: 0
        val day = value?.optInt("day") ?: 0
        return when {
            year <= 0 -> null
            month <= 0 -> year.toString()
            day <= 0 -> "${month.toString().padStart(2, '0')}/$year"
            else -> "${day.toString().padStart(2, '0')}/${month.toString().padStart(2, '0')}/$year"
        }
    }

    private suspend fun myAnimeListPersonalMetadata(
        account: AuthData,
        syncId: String,
    ): TrackingPersonalMetadata? {
        val body = app.get(
            "https://api.myanimelist.net/v2/anime/$syncId",
            params = mapOf("fields" to "my_list_status{status,start_date,finish_date}"),
            headers = mapOf("Authorization" to "Bearer ${account.token.accessToken}"),
            cacheTime = 0,
        ).text
        val status = JSONObject(body).optJSONObject("my_list_status") ?: return null
        return TrackingPersonalMetadata(
            status = trackingStatusLabel(status.optTrackingText("status")),
            startedAt = formatTrackingDate(status.optTrackingText("start_date")),
            finishedAt = formatTrackingDate(status.optTrackingText("finish_date")),
        )
    }

    private suspend fun aniListPersonalMetadata(
        account: AuthData,
        syncId: String,
    ): TrackingPersonalMetadata? {
        val mediaId = syncId.toIntOrNull() ?: return null
        val query = """
            query {
                MediaList(mediaId: $mediaId, userId: ${account.user.id}) {
                    status
                    startedAt { year month day }
                    completedAt { year month day }
                }
            }
        """.trimIndent()
        val body = app.post(
            "https://graphql.anilist.co/",
            headers = mapOf("Authorization" to "Bearer ${account.token.accessToken}"),
            data = mapOf("query" to URLEncoder.encode(query, StandardCharsets.UTF_8.name())),
            cacheTime = 0,
            timeout = 5,
        ).text.replace("\\/", "/")
        val status = JSONObject(body).optJSONObject("data")?.optJSONObject("MediaList") ?: return null
        return TrackingPersonalMetadata(
            status = trackingStatusLabel(status.optTrackingText("status")),
            startedAt = formatTrackingDate(status.optJSONObject("startedAt")),
            finishedAt = formatTrackingDate(status.optJSONObject("completedAt")),
        )
    }

    private suspend fun kitsuPersonalMetadata(
        account: AuthData,
        syncId: String,
    ): TrackingPersonalMetadata? {
        val body = app.get(
            "https://kitsu.io/api/edge/library-entries/$syncId",
            headers = mapOf("Authorization" to "Bearer ${account.token.accessToken}"),
            cacheTime = 0,
        ).text
        val attributes = JSONObject(body).optJSONObject("data")?.optJSONObject("attributes") ?: return null
        return TrackingPersonalMetadata(
            status = trackingStatusLabel(attributes.optTrackingText("status")),
            startedAt = formatTrackingDate(
                attributes.optTrackingText("startedAt") ?: attributes.optTrackingText("started_at"),
            ),
            finishedAt = formatTrackingDate(
                attributes.optTrackingText("finishedAt") ?: attributes.optTrackingText("finished_at"),
            ),
        )
    }

    private suspend fun trackingPersonalMetadata(
        service: StreamCenterTrackingService,
        libraryId: String,
        mediaId: String,
    ): TrackingPersonalMetadata {
        val repo = trackingRepo(service) ?: return TrackingPersonalMetadata()
        val account = repo.authData() ?: return TrackingPersonalMetadata()
        val fallbackStatus = runCatching {
            trackingStatusLabel(repo.api.status(account, mediaId)?.status)
        }.getOrNull()
        val serviceMetadata = runCatching {
            when (service.key) {
                "myanimelist" -> myAnimeListPersonalMetadata(account, mediaId)
                "anilist" -> aniListPersonalMetadata(account, mediaId)
                "kitsu" -> kitsuPersonalMetadata(account, libraryId)
                else -> null
            }
        }.getOrNull()
        return serviceMetadata?.copy(status = serviceMetadata.status ?: fallbackStatus)
            ?: TrackingPersonalMetadata(status = fallbackStatus)
    }

    private suspend fun trackingLibraryItems(
        config: StreamCenterTrackingListConfig,
    ): List<SyncAPI.LibraryItem> {
        val context = StreamCenterPlugin.activeContext ?: return emptyList()
        val requestedListName = context.getString(config.status.watchType.stringRes)
        val repo = trackingRepo(config) ?: return emptyList()
        val account = repo.authData() ?: return emptyList()
        return repo.api.library(account)?.allLibraryLists
            ?.firstOrNull { it.name.asString(context) == requestedListName }?.items.orEmpty()
    }

    private suspend fun fetchTrackingListHome(
        config: StreamCenterTrackingListConfig,
        limit: Int,
    ): List<SearchResponse> {
        return trackingLibraryItems(config)
            .take(limit)
            .map { item -> trackingSearchResponse(config.service, item) }
    }

    private suspend fun fetchTrackingListHomePage(
        config: StreamCenterTrackingListConfig,
        page: Int,
    ): StreamCenterCatalogPage {
        if (page < 1 || !trackingServiceIsConnected(config.service.syncIdName)) {
            return StreamCenterCatalogPage(emptyList(), false)
        }
        val library = trackingLibraryItems(config).distinctBy(SyncAPI.LibraryItem::syncId)
        val offset = (page - 1) * TRACKING_PROVIDER_PAGE_SIZE
        val pageItems = library.drop(offset).take(TRACKING_PROVIDER_PAGE_SIZE)
        return StreamCenterCatalogPage(
            items = pageItems.map { item -> trackingSearchResponse(config.service, item) },
            hasNext = offset + pageItems.size < library.size,
        )
    }

    private fun trackingSearchResponse(
        service: StreamCenterTrackingService,
        item: SyncAPI.LibraryItem,
    ): SearchResponse {
        val route = "$mainUrl$trackingHomePath${service.key}/${URLEncoder.encode(item.syncId, StandardCharsets.UTF_8.name())}"
        val score = item.score ?: item.personalRating
        val itemType = item.type
        return when (itemType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(item.name, route, TvType.TvSeries) {
                posterUrl = item.posterUrl
                score?.let { this.score = it }
            }
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> newAnimeSearchResponse(item.name, route, itemType) {
                posterUrl = item.posterUrl
                score?.let { this.score = it }
            }
            else -> newMovieSearchResponse(item.name, route, TvType.Movie) {
                posterUrl = item.posterUrl
                score?.let { this.score = it }
            }
        }
    }

    private suspend fun searchIptv(query: String): List<SearchResponse> {
        val terms = query.trim().lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        val favoriteRegions = StreamCenterPlugin.getAllIptvSelectedChannelIds(sharedPref)
            .map { it.substringBefore(':') }
        val regionKeys = (listOf(StreamCenterPlugin.getIptvRegion(sharedPref)) + favoriteRegions)
            .distinct()
            .filter { key -> StreamCenterIptv.regions.any { it.key == key } }
        return coroutineScope {
            regionKeys.map { regionKey ->
                async(Dispatchers.IO) {
                    runCatching { StreamCenterIptv.fetchChannels(regionKey) }.getOrDefault(emptyList())
                }
            }.awaitAll()
                .flatten()
                .filter { channel ->
                    val text = "${channel.name} ${channel.group}".lowercase(Locale.ROOT)
                    terms.all(text::contains)
                }
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .map(::iptvSearchResponse)
        }
    }

    private suspend fun loadIptvChannel(url: String): LoadResponse {
        val id = URLDecoder.decode(url.substringAfter(StreamCenterIptv.ROUTE_PREFIX), StandardCharsets.UTF_8.name())
        val regionKey = id.substringBefore(':')
        val channel = StreamCenterIptv.fetchChannels(regionKey).firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Canale TV non più disponibile nella playlist")
        val sectionKey = StreamCenterPlugin.getIptvCustomSectionKeys(sharedPref)
            .firstOrNull { id in StreamCenterPlugin.getIptvSectionChannelIds(sharedPref, it) }
        val suggestions = sectionKey
            ?.let { StreamCenterPlugin.getIptvSectionChannelOrder(sharedPref, it) }
            .orEmpty()
            .filterNot { it == id }
            .let { fetchIptvChannelsByIds(it, it.size) }
        return newLiveStreamLoadResponse(channel.name, url, channel.playbackData()) {
            posterUrl = channel.logo
            plot = "Diretta TV · ${channel.regionName} · ${channel.group}"
            tags = listOf("TV", channel.regionName, channel.group)
            recommendations = suggestions
        }.withCardProvenance(
            defaultSource = "Playlist IPTV (${channel.regionName})",
            fieldSources = mapOf(
                "tipo_contenuto" to listOf("StreamCenter (routing IPTV)"),
                "trama" to listOf("Playlist IPTV", "StreamCenter (testo composto)"),
                "tag" to listOf("Playlist IPTV", "StreamCenter (classificazione)"),
                "raccomandazioni" to listOf(
                    "Configurazione sezione IPTV StreamCenter",
                    "Playlist IPTV",
                ),
            ),
        )
    }

    private suspend fun searchStreamingCommunity(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val empty = emptyList<SearchResponse>() to false
        val props = streamingCommunityClient.fetchSearchPage(query, page) ?: return empty
        val cdnUrl = streamingCommunityClient.cdnUrl(props)
        val titles = props.optJSONArray("titles") ?: return empty

        val seen = mutableSetOf<String>()
        val items = buildList {
            for (index in 0 until titles.length()) {
                val title = titles.optJSONObject(index) ?: continue
                val type = title.optNullableString("type") ?: "tv"
                val response = title.toStreamingCommunityHomeResponse(type, cdnUrl, showScore = true)
                    ?: continue
                if (seen.add(response.url)) {
                    registerSearchTitleAliases(response.url, title.streamingCommunitySearchTitleAliases())
                    add(response)
                }
            }
        }
        return items to (titles.length() >= SC_SEARCH_PAGE_SIZE)
    }

    private fun JSONObject.streamingCommunitySearchTitleAliases(): List<String> = buildList {
        listOf("name", "original_name", "original_title", "title", "title_original")
            .mapNotNull(::optNullableString)
            .forEach(::add)
        val translations = optJSONArray("translations") ?: return@buildList
        for (index in 0 until translations.length()) {
            val translation = translations.optJSONObject(index) ?: continue
            if (!translation.optNullableString("key").equals("name", ignoreCase = true)) continue
            translation.optNullableString("value")?.let(::add)
        }
    }

    private suspend fun searchAnimeUnity(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val records = animeUnitySourceClient.fetchArchive(
            title = query,
            offset = (page - 1) * AU_ARCHIVE_BATCH_SIZE,
        )
        val anilistScores = aniListMetadataClient.fetchScores(records.mapNotNull { it.anilistId })
        val anilistTitleAliases = aniListMetadataClient.fetchTitleAliases(records.mapNotNull { it.anilistId })
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

    private fun AnimeUnityAnime.searchTitleAliases(metadataAliases: Collection<String>): List<String> {
        return (listOf(titleIt, titleEng, title, displayTitle()) + metadataAliases)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy(::sourceTitleDedupKey)
    }

    private fun isExplicitAdultContent(response: LoadResponse): Boolean {
        val rating = response.contentRating?.uppercase(Locale.ROOT).orEmpty()
        val ratingAdult = rating.contains("HENTAI") ||
            rating == "RX" || rating.startsWith("RX ") || rating.startsWith("RX-") ||
            rating == "R18" || rating == "R-18" || rating == "R18+" ||
            rating == "X" || rating == "XXX"
        val tagsAdult = response.tags?.any { tag ->
            val normalized = tag.lowercase(Locale.ROOT)
            normalized == "hentai" || normalized == "erotica" || normalized == "adult"
        } == true
        return ratingAdult || tagsAdult
    }

    private fun logLoadedResponse(response: LoadResponse, route: String): LoadResponse {
        if (isExplicitAdultContent(response)) response.type = TvType.NSFW
        if (!StreamCenterLogger.isEnabled(sharedPref)) {
            pendingCardProvenance.remove(response)
            return response
        }
        val provenance = pendingCardProvenance.remove(response) ?: defaultCardProvenance(route)
        val fields = completeCardLogFields(response, provenance)
        fields["rotta_caricamento"] = sourcedCardValue(
            provenance = provenance,
            path = "rotta_caricamento",
            value = route,
            sources = listOf("StreamCenter (routing)"),
        )
        fields["catalogo_streamcenter"] = sourcedCardValue(
            provenance = provenance,
            path = "catalogo_streamcenter",
            value = catalogDefinition?.displayName,
            sources = listOf("Configurazione StreamCenter"),
        )
        fields["modalita_prestazioni"] = sourcedCardValue(
            provenance = provenance,
            path = "modalita_prestazioni",
            value = performanceMode,
            sources = listOf("Configurazione StreamCenter"),
            note = if (performanceMode) {
                "Alcuni valori possono essere null perché la modalità prestazioni evita richieste opzionali."
            } else {
                null
            },
        )
        StreamCenterLogger.logTab(
            tabName = response.name,
            action = "Scheda completa: tutti i valori finali e relativa provenienza",
            metadata = fields,
        )
        return response
    }

    private fun defaultCardProvenance(route: String): CardProvenance {
        val source = when {
            route == "iptv" -> "Playlist IPTV"
            route == "streamingcommunity" -> "StreamingCommunity"
            route == "anilist" || route == "anime" -> "AniList e fonti anime configurate"
            route == "myanimelist" -> "MyAnimeList (Jikan) e fonti anime configurate"
            route == "kitsu" -> "Kitsu e fonti anime configurate"
            route == "simkl" -> "Simkl e fonti configurate"
            route == "tmdb" -> "TMDB e fonti configurate"
            route == "tracciamento" -> "Servizio di tracciamento"
            route.startsWith("catalogo:") -> catalogDefinition?.displayName
                ?.let { "Catalogo $it" }
                ?: route.removePrefix("catalogo:")
            else -> "StreamCenter"
        }
        return CardProvenance(defaultSource = source)
    }

    private fun <T : LoadResponse> T.withCardProvenance(
        defaultSource: String,
        fieldSources: Map<String, List<String>> = emptyMap(),
        fieldNotes: Map<String, String> = emptyMap(),
    ): T = apply {
        if (StreamCenterLogger.isEnabled(sharedPref)) {
            pendingCardProvenance[this] = CardProvenance(
                defaultSource = defaultSource,
                fieldSources = fieldSources,
                fieldNotes = fieldNotes,
            )
        }
    }

    private fun completeCardLogFields(
        response: LoadResponse,
        provenance: CardProvenance,
    ): LinkedHashMap<String, Any?> {
        val fields = linkedMapOf<String, Any?>(
            "titolo" to sourcedCardValue(provenance, "titolo", response.name),
            "url_scheda" to sourcedCardValue(provenance, "url_scheda", response.url),
            "api_visualizzata" to sourcedCardValue(
                provenance,
                "api_visualizzata",
                response.apiName,
                sources = listOf("Configurazione StreamCenter"),
            ),
            "tipo_contenuto" to sourcedCardValue(
                provenance,
                "tipo_contenuto",
                response.type.name,
                sources = provenance.sources("tipo_contenuto"),
            ),
            "poster" to sourcedCardValue(provenance, "poster", response.posterUrl),
            "sfondo" to sourcedCardValue(provenance, "sfondo", response.backgroundPosterUrl),
            "logo" to sourcedCardValue(provenance, "logo", response.logoUrl),
            "anno" to sourcedCardValue(provenance, "anno", response.year),
            "trama" to sourcedCardValue(provenance, "trama", response.plot),
            "punteggio" to sourcedCardValue(
                provenance,
                "punteggio",
                response.score?.toString(),
            ),
            "tag" to sourcedCardValue(provenance, "tag", response.tags),
            "durata_minuti" to sourcedCardValue(provenance, "durata_minuti", response.duration),
            "classificazione_contenuti" to sourcedCardValue(
                provenance,
                "classificazione_contenuti",
                response.contentRating,
            ),
            "in_arrivo" to sourcedCardValue(provenance, "in_arrivo", response.comingSoon),
            "url_univoco" to sourcedCardValue(provenance, "url_univoco", response.uniqueUrl),
            "id_sincronizzazione" to response.syncData.entries.map { (key, value) ->
                linkedMapOf(
                    "servizio" to sourcedCardValue(
                        provenance,
                        "id_sincronizzazione.$key.servizio",
                        key,
                    ),
                    "id" to sourcedCardValue(
                        provenance,
                        "id_sincronizzazione.$key.id",
                        value,
                    ),
                )
            },
            "header_poster" to response.posterHeaders.orEmpty().entries.map { (key, value) ->
                linkedMapOf(
                    "nome" to sourcedCardValue(
                        provenance,
                        "header_poster.nome",
                        key,
                    ),
                    "valore" to sourcedCardValue(
                        provenance,
                        "header_poster.$key",
                        value,
                    ),
                )
            },
            "trailer" to response.trailers.map { trailer ->
                linkedMapOf(
                    "url" to sourcedCardValue(
                        provenance,
                        "trailer.url",
                        trailer.extractorUrl,
                    ),
                    "referer" to sourcedCardValue(
                        provenance,
                        "trailer.referer",
                        trailer.referer,
                    ),
                    "raw" to sourcedCardValue(
                        provenance,
                        "trailer.raw",
                        trailer.raw,
                    ),
                    "header" to sourcedCardValue(
                        provenance,
                        "trailer.header",
                        trailer.headers,
                    ),
                )
            },
            "cast" to response.actors.orEmpty().map { actorData ->
                linkedMapOf(
                    "nome" to sourcedCardValue(
                        provenance,
                        "cast.nome",
                        actorData.actor.name,
                    ),
                    "immagine" to sourcedCardValue(
                        provenance,
                        "cast.immagine",
                        actorData.actor.image,
                    ),
                    "ruolo" to sourcedCardValue(
                        provenance,
                        "cast.ruolo",
                        actorData.role?.name,
                    ),
                    "descrizione_ruolo" to sourcedCardValue(
                        provenance,
                        "cast.descrizione_ruolo",
                        actorData.roleString,
                    ),
                    "doppiatore_nome" to sourcedCardValue(
                        provenance,
                        "cast.doppiatore_nome",
                        actorData.voiceActor?.name,
                    ),
                    "doppiatore_immagine" to sourcedCardValue(
                        provenance,
                        "cast.doppiatore_immagine",
                        actorData.voiceActor?.image,
                    ),
                )
            },
            "raccomandazioni" to response.recommendations.orEmpty().map { recommendation ->
                linkedMapOf(
                    "titolo" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.titolo",
                        recommendation.name,
                    ),
                    "url" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.url",
                        recommendation.url,
                    ),
                    "api" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.api",
                        recommendation.apiName,
                    ),
                    "tipo" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.tipo",
                        recommendation.type?.name,
                    ),
                    "poster" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.poster",
                        recommendation.posterUrl,
                    ),
                    "header_poster" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.header_poster",
                        recommendation.posterHeaders,
                    ),
                    "id_locale" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.id_locale",
                        recommendation.id,
                    ),
                    "qualita" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.qualita",
                        recommendation.quality?.name,
                    ),
                    "punteggio" to sourcedCardValue(
                        provenance,
                        "raccomandazioni.punteggio",
                        recommendation.score?.toString(),
                    ),
                )
            },
        )

        when (response) {
            is AnimeLoadResponse -> {
                fields["titolo_inglese"] = sourcedCardValue(
                    provenance,
                    "titolo_inglese",
                    response.engName,
                )
                fields["titolo_originale"] = sourcedCardValue(
                    provenance,
                    "titolo_originale",
                    response.japName,
                )
                fields["titoli_alternativi"] = sourcedCardValue(
                    provenance,
                    "titoli_alternativi",
                    response.synonyms,
                )
                fields["stato_trasmissione"] = sourcedCardValue(
                    provenance,
                    "stato_trasmissione",
                    response.showStatus?.name,
                )
                fields["prossimo_episodio"] = response.nextAiring?.let { next ->
                    linkedMapOf(
                        "episodio" to sourcedCardValue(
                            provenance,
                            "prossimo_episodio.episodio",
                            next.episode,
                        ),
                        "stagione" to sourcedCardValue(
                            provenance,
                            "prossimo_episodio.stagione",
                            next.season,
                        ),
                        "timestamp_unix" to sourcedCardValue(
                            provenance,
                            "prossimo_episodio.timestamp_unix",
                            next.unixTime,
                        ),
                    )
                } ?: sourcedCardValue(provenance, "prossimo_episodio", null)
                fields["stagioni"] = loggedSeasons(response.seasonNames.orEmpty(), provenance)
                fields["episodi"] = response.episodes.entries.flatMap { (dubStatus, episodes) ->
                    episodes.mapIndexed { index, episode ->
                        loggedEpisode(
                            episode = episode,
                            provenance = provenance,
                            index = index,
                            audio = dubStatus.name,
                        )
                    }
                }
            }

            is TvSeriesLoadResponse -> {
                fields["stato_trasmissione"] = sourcedCardValue(
                    provenance,
                    "stato_trasmissione",
                    response.showStatus?.name,
                )
                fields["prossimo_episodio"] = response.nextAiring?.let { next ->
                    linkedMapOf(
                        "episodio" to sourcedCardValue(
                            provenance,
                            "prossimo_episodio.episodio",
                            next.episode,
                        ),
                        "stagione" to sourcedCardValue(
                            provenance,
                            "prossimo_episodio.stagione",
                            next.season,
                        ),
                        "timestamp_unix" to sourcedCardValue(
                            provenance,
                            "prossimo_episodio.timestamp_unix",
                            next.unixTime,
                        ),
                    )
                } ?: sourcedCardValue(provenance, "prossimo_episodio", null)
                fields["stagioni"] = loggedSeasons(response.seasonNames.orEmpty(), provenance)
                fields["episodi"] = response.episodes.mapIndexed { index, episode ->
                    loggedEpisode(episode, provenance, index)
                }
            }

            is MovieLoadResponse -> {
                fields["dati_riproduzione"] = sourcedCardValue(
                    provenance,
                    "dati_riproduzione",
                    response.dataUrl,
                )
            }

            is LiveStreamLoadResponse -> {
                fields["dati_riproduzione"] = sourcedCardValue(
                    provenance,
                    "dati_riproduzione",
                    response.dataUrl,
                )
            }
        }
        return fields
    }

    private fun loggedSeasons(
        seasons: List<SeasonData>,
        provenance: CardProvenance,
    ): List<Map<String, Any?>> {
        return seasons.map { season ->
            linkedMapOf(
                "stagione" to sourcedCardValue(
                    provenance,
                    "stagioni.stagione",
                    season.season,
                ),
                "nome" to sourcedCardValue(
                    provenance,
                    "stagioni.nome",
                    season.name,
                ),
                "stagione_visualizzata" to sourcedCardValue(
                    provenance,
                    "stagioni.stagione_visualizzata",
                    season.displaySeason,
                ),
            )
        }
    }

    private fun loggedEpisode(
        episode: Episode,
        provenance: CardProvenance,
        index: Int,
        audio: String? = null,
    ): Map<String, Any?> {
        return linkedMapOf(
            "indice_lista" to sourcedCardValue(
                provenance,
                "episodi.indice_lista",
                index,
                sources = listOf("StreamCenter (ordinamento finale)"),
            ),
            "audio" to sourcedCardValue(
                provenance,
                "episodi.audio",
                audio,
                sources = listOf("StreamCenter (raggruppamento audio)"),
            ),
            "nome" to sourcedCardValue(provenance, "episodi.nome", episode.name),
            "stagione" to sourcedCardValue(provenance, "episodi.stagione", episode.season),
            "episodio" to sourcedCardValue(provenance, "episodi.episodio", episode.episode),
            "poster" to sourcedCardValue(provenance, "episodi.poster", episode.posterUrl),
            "punteggio" to sourcedCardValue(
                provenance,
                "episodi.punteggio",
                episode.score?.toString(),
            ),
            "descrizione" to sourcedCardValue(
                provenance,
                "episodi.descrizione",
                episode.description,
            ),
            "data_timestamp" to sourcedCardValue(provenance, "episodi.data", episode.date),
            "durata_minuti" to sourcedCardValue(
                provenance,
                "episodi.durata_minuti",
                episode.runTime,
            ),
            "dati_riproduzione" to sourcedCardValue(
                provenance,
                "episodi.dati_riproduzione",
                episode.data,
            ),
        )
    }

    private fun sourcedCardValue(
        provenance: CardProvenance,
        path: String,
        value: Any?,
        sources: List<String> = provenance.sources(path),
        note: String? = provenance.note(path),
    ): StreamCenterLogger.SourcedValue {
        return StreamCenterLogger.SourcedValue(
            value = value,
            sources = sources,
            note = note,
        )
    }

    private fun animeProviderCardSources(
        metadataSource: String,
        playbackSources: List<String>,
        episodeMetadataSources: List<String> = listOf(metadataSource),
        trackingSources: List<String> = listOf(metadataSource),
        torrentContext: StreamCenterTorrentPlaybackContext? = null,
    ): Map<String, List<String>> {
        val finalPlaybackSources = (
            playbackSources +
                "Add-on Stremio abilitati" +
                torrentPlaybackProvenance(torrentContext) +
                "StreamCenter (payload di riproduzione)"
            ).distinct()
        val episodeSources = (episodeMetadataSources + finalPlaybackSources).distinct()
        return mapOf(
            "titolo" to listOf(metadataSource),
            "titolo_inglese" to listOf(metadataSource),
            "titolo_originale" to listOf(metadataSource),
            "titoli_alternativi" to listOf(metadataSource),
            "poster" to listOf(metadataSource),
            "sfondo" to listOf(metadataSource),
            "trama" to listOf(metadataSource),
            "tag" to listOf(metadataSource, "StreamCenter (etichette derivate)"),
            "anno" to listOf(metadataSource),
            "punteggio" to listOf(metadataSource),
            "durata_minuti" to listOf(metadataSource),
            "classificazione_contenuti" to listOf(metadataSource),
            "cast" to listOf(metadataSource),
            "raccomandazioni" to listOf(metadataSource),
            "trailer" to listOf(metadataSource),
            "stato_trasmissione" to listOf(metadataSource),
            "prossimo_episodio" to listOf(metadataSource),
            "in_arrivo" to listOf(metadataSource),
            "id_sincronizzazione" to trackingSources,
            "stagioni" to listOf(metadataSource, "StreamCenter (normalizzazione stagioni)"),
            "episodi" to episodeSources,
            "episodi.nome" to episodeMetadataSources + "StreamCenter (fallback nome)",
            "episodi.poster" to episodeMetadataSources + "$metadataSource (poster scheda fallback)",
            "episodi.punteggio" to episodeMetadataSources,
            "episodi.descrizione" to episodeMetadataSources,
            "episodi.data" to episodeMetadataSources,
            "episodi.durata_minuti" to episodeMetadataSources,
            "episodi.stagione" to episodeMetadataSources + "StreamCenter (normalizzazione)",
            "episodi.episodio" to episodeSources,
            "episodi.dati_riproduzione" to finalPlaybackSources,
            "dati_riproduzione" to finalPlaybackSources,
        )
    }

    private fun logSearchCards(
        action: String,
        contextDetails: Map<String, Any?>,
        items: List<SearchResponse>,
    ) {
        if (!StreamCenterLogger.isEnabled(sharedPref)) return
        StreamCenterLogger.logMenu(
            action = action,
            metadata = linkedMapOf<String, Any?>().apply {
                contextDetails.forEach { (key, value) ->
                    put(
                        key,
                        StreamCenterLogger.SourcedValue(
                            value = value,
                            sources = listOf("StreamCenter"),
                        ),
                    )
                }
                put(
                    "numero_schede",
                    StreamCenterLogger.SourcedValue(
                        value = items.size,
                        sources = listOf("StreamCenter (conteggio finale)"),
                    ),
                )
                put(
                    "schede_complete",
                    StreamCenterLogger.SourcedValue(
                        value = items.mapIndexed(::completeSearchCardLogFields),
                        sources = listOf("Fonti indicate accanto a ogni campo"),
                        note = "Nessun elemento o valore della lista è troncato.",
                    ),
                )
            },
        )
    }

    private fun completeSearchCardLogFields(
        index: Int,
        response: SearchResponse,
    ): Map<String, Any?> {
        val source = searchCardSource(response)
        fun sourced(value: Any?, vararg overrideSources: String): StreamCenterLogger.SourcedValue {
            return StreamCenterLogger.SourcedValue(
                value = value,
                sources = overrideSources.toList().takeIf { it.isNotEmpty() } ?: source,
            )
        }

        return linkedMapOf<String, Any?>(
            "indice" to sourced(index, "StreamCenter (ordinamento finale)"),
            "titolo" to sourced(response.name),
            "url" to sourced(response.url),
            "api" to sourced(response.apiName, "Configurazione StreamCenter"),
            "tipo" to sourced(
                response.type?.name,
                *(source + "StreamCenter (conversione tipo)").toTypedArray(),
            ),
            "poster" to sourced(response.posterUrl),
            "header_poster" to sourced(response.posterHeaders),
            "id_locale" to sourced(response.id, "CloudStream / StreamCenter"),
            "qualita" to sourced(response.quality?.name),
            "punteggio" to sourced(response.score?.toString()),
        ).apply {
            when (response) {
                is AnimeSearchResponse -> {
                    put("anno", sourced(response.year))
                    put("stato_doppiaggio", sourced(response.dubStatus?.map { it.name }))
                    put("altro_titolo", sourced(response.otherName))
                    put(
                        "episodi_disponibili",
                        sourced(
                            response.episodes.entries.associate { (status, count) ->
                                status.name to count
                            },
                        ),
                    )
                }

                is MovieSearchResponse -> put("anno", sourced(response.year))
                is TvSeriesSearchResponse -> {
                    put("anno", sourced(response.year))
                    put("episodi_disponibili", sourced(response.episodes))
                }

                is LiveSearchResponse -> put("lingua", sourced(response.lang))
            }
        }
    }

    private fun searchCardSource(response: SearchResponse): List<String> {
        val url = response.url
        return when {
            catalogDefinition?.stremioAddon != null -> listOf(
                "Add-on Stremio: ${catalogDefinition.stremioAddon.name}",
            )
            catalogDefinition != null -> listOf("Catalogo ${catalogDefinition.displayName}")
            url.startsWith(StreamCenterIptv.ROUTE_PREFIX) -> listOf("Playlist IPTV")
            url.contains(trackingHomePath) -> listOf("Servizio di tracciamento")
            url.contains(scHomePath) -> listOf("StreamingCommunity")
            url.contains(animeMarker) || isAnilistOnlyUrl(url) || isMalOnlyUrl(url) ->
                listOf("AnimeUnity", "AniList")
            url.contains("anilist.co", ignoreCase = true) -> listOf("AniList")
            url.contains("myanimelist.net", ignoreCase = true) -> listOf("MyAnimeList (Jikan)")
            url.contains("kitsu.", ignoreCase = true) -> listOf("Kitsu")
            url.contains("simkl.com", ignoreCase = true) -> listOf("Simkl")
            url.contains("themoviedb.org", ignoreCase = true) -> listOf("TMDB")
            else -> listOf("StreamCenter (fonte non identificata dalla rotta)")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        StreamCenterVpnGuard.requireInternetAccess(sharedPref)
        val route = when {
            catalogDefinition != null -> "catalogo:${catalogDefinition.key}"
            url.startsWith(StreamCenterIptv.ROUTE_PREFIX) -> "iptv"
            kitsuCatalog.mediaId(url) != null -> "kitsu"
            simklCatalog.mediaRoute(url) != null -> "simkl"
            url.contains(trackingHomePath) -> "tracciamento"
            url.contains(scHomePath) -> "streamingcommunity"
            isAnilistOnlyUrl(url) -> "anilist"
            isMalOnlyUrl(url) -> "myanimelist"
            url.contains(animeMarker) -> "anime"
            else -> "tmdb"
        }
        StreamCenterLogger.logMenu(
            action = "Apertura scheda avviata",
            metadata = mapOf(
                "api" to name,
                "rotta" to route,
                "catalogo" to (catalogDefinition?.displayName ?: "nessuno"),
                "destinazione" to url,
                "modalita_prestazioni" to performanceMode,
            ),
        )
        try {
        if (catalogDefinition != null) {
            check(catalogIsActive) { "Il Catalogo selezionato non è più attivo." }
            if (url.contains(trackingHomePath)) return logLoadedResponse(loadTrackingLibraryItem(url), route)
            if (catalogDefinition.stremioAddon != null) return logLoadedResponse(loadStremioCatalogMedia(url), route)
            return logLoadedResponse(
                response = when (catalogDefinition.key) {
                    "tmdb" -> loadTmdbMedia(normalizeTmdbUrl(url), strictTmdbMetadata = true)
                    "anilist" -> loadAniListCatalogMedia(url)
                    "myanimelist" -> loadMyAnimeListMedia(url)
                    "kitsu" -> loadKitsuMedia(url)
                    "simkl" -> loadSimklMedia(url)
                    else -> error("Catalogo non supportato")
                },
                route = route,
            )
        }
        if (url.startsWith(StreamCenterIptv.ROUTE_PREFIX)) {
            return logLoadedResponse(loadIptvChannel(url), route)
        }
        if (kitsuCatalog.mediaId(url) != null) {
            return logLoadedResponse(loadKitsuMedia(url), route)
        }
        if (simklCatalog.mediaRoute(url) != null) {
            return logLoadedResponse(loadSimklMedia(url), route)
        }
        if (url.contains(trackingHomePath)) {
            return logLoadedResponse(loadTrackingLibraryItem(url), route)
        }
        if (url.contains(scHomePath)) {
            return logLoadedResponse(loadStreamingCommunityHomeTitle(url), route)
        }
        if (isAnilistOnlyUrl(url)) {
            val anilistId = extractAnilistIdFromText(url)
            val malId = parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return logLoadedResponse(loadAnilistMedia(anilistId, malId), route)
        }
        if (isMalOnlyUrl(url)) {
            val malId = extractMalIdFromText(url)
                ?: parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return logLoadedResponse(loadAnilistMedia(null, malId), route)
        }

        val actualUrl = normalizeTmdbUrl(url)
        if (actualUrl.contains(animeMarker)) {
            val selection = parseAnimeSelection(actualUrl)
            val anilistId = selection?.anilistId
            val malId = selection?.malId
            if (anilistId != null || malId != null) {
                return logLoadedResponse(loadAnilistMedia(anilistId, malId), route)
            }
            error("Identificativo AniList o MyAnimeList mancante")
        }

        return logLoadedResponse(loadTmdbMedia(actualUrl), route)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StreamCenterLogger.logMenuError(
                action = "Apertura scheda non riuscita",
                throwable = error,
                metadata = mapOf(
                    "api" to name,
                    "rotta" to route,
                    "catalogo" to (catalogDefinition?.displayName ?: "nessuno"),
                    "destinazione" to url,
                ),
            )
            throw error
        }
    }

    private suspend fun loadTrackingLibraryItem(url: String): LoadResponse {
        val route = url.substringAfter(trackingHomePath).trimStart('/')
        val serviceKey = route.substringBefore('/').takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Lista di tracciamento non valida")
        val syncId = route.substringAfter('/', "")
            .takeIf { it.isNotBlank() }
            ?.let { URLDecoder.decode(it.substringBefore('?'), StandardCharsets.UTF_8.name()) }
            ?: throw IllegalArgumentException("Elemento della lista non valido")
        val service = StreamCenterPlugin.trackingServices.firstOrNull { it.key == serviceKey }
            ?: throw IllegalArgumentException("Servizio di tracciamento non supportato")
        val item = allTrackingLibraryItems(service).firstOrNull { it.syncId == syncId }
            ?: throw IllegalStateException("Elemento non più presente nella lista di ${service.title}")

        providerTrackingRoute(service, item)?.let { providerUrl ->
            return when (service.key) {
                "anilist" -> loadAniListCatalogMedia(providerUrl)
                "myanimelist" -> loadMyAnimeListMedia(providerUrl)
                "kitsu" -> loadKitsuMedia(providerUrl)
                "simkl" -> loadSimklMedia(providerUrl)
                else -> error("Servizio di tracciamento non supportato")
            }
        }

        val mediaId = trackingMediaId(service, item)
        val details = trackingServiceDetails(service, mediaId)
        val personal = trackingPersonalMetadata(service, item.syncId, mediaId)
        return createTrackingLoadResponse(url, service, item, mediaId, details, personal)
    }

    private suspend fun providerTrackingRoute(
        service: StreamCenterTrackingService,
        item: SyncAPI.LibraryItem,
    ): String? {
        return when (service.key) {
            "anilist" -> (aniListCatalog.mediaId(item.url) ?: item.syncId.toIntOrNull())
                ?.let { "https://anilist.co/anime/$it" }
            "myanimelist" -> (myAnimeListCatalog.mediaId(item.url) ?: item.syncId.toIntOrNull())
                ?.let { "https://myanimelist.net/anime/$it" }
            "kitsu" -> trackingMediaId(service, item).toIntOrNull()
                ?.let { "https://kitsu.io/anime/$it" }
            "simkl" -> resolveSimklLibraryRoute(item)
            else -> null
        }
    }

    private suspend fun resolveSimklLibraryRoute(item: SyncAPI.LibraryItem): String? {
        val parsedRoute = simklCatalog.mediaRoute(item.url)
        val simklId = parsedRoute?.second
            ?: item.syncId.substringBefore('/').trim().toIntOrNull()
            ?: return null
        val canonicalRoute = runCatching {
            simklCatalog.resolveMediaUrl(simkl = simklId)
        }.getOrNull()
        if (canonicalRoute != null) return canonicalRoute

        return parsedRoute?.let { (category, id) ->
            "https://simkl.com/$category/$id"
        } ?: run {
            val category = when (item.type) {
                TvType.Movie -> "movies"
                TvType.Anime, TvType.AnimeMovie, TvType.OVA -> "anime"
                else -> "tv"
            }
            "https://simkl.com/$category/$simklId"
        }
    }

    private data class TrackingCardMetadata(
        val title: String,
        val poster: String?,
        val background: String?,
        val plot: String?,
        val tags: List<String>,
        val year: Int?,
        val score: Score?,
        val showStatus: ShowStatus?,
        val nextAiring: NextAiring?,
        val actors: List<ActorData>,
        val duration: Int?,
        val synonyms: List<String>,
    )

    private fun trackingCardMetadata(
        item: SyncAPI.LibraryItem,
        details: SyncAPI.SyncResult?,
        personal: TrackingPersonalMetadata,
    ): TrackingCardMetadata {
        val title = details?.title?.takeIf(String::isNotBlank) ?: item.name
        val personalRating = item.personalRating?.takeIf { it.toDouble() > 0 }
        val progress = when {
            item.episodesCompleted != null && item.episodesTotal != null ->
                "Progresso: ${item.episodesCompleted}/${item.episodesTotal}"
            item.episodesCompleted != null -> "Progresso: ${item.episodesCompleted} episodi"
            else -> null
        }
        val serviceTags = details?.genres?.filterIsInstance<String>().orEmpty()
        val libraryTags = item.tags?.filterIsInstance<String>().orEmpty()
        val tags = (serviceTags.ifEmpty { libraryTags } +
            listOfNotNull(
                progress,
                personal.status?.let { "Stato: $it" },
                personal.startedAt?.let { "Data inizio: $it" },
                personal.finishedAt?.let { "Data fine: $it" },
                personalRating?.let { "Personale: $it/10" },
            ))
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
        val year = item.releaseDate?.let { date ->
            Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
        } ?: details?.startDate?.let { timestamp ->
            val milliseconds = if (timestamp < 10_000_000_000L) timestamp * 1_000L else timestamp
            Calendar.getInstance().apply { timeInMillis = milliseconds }.get(Calendar.YEAR)
        }
        return TrackingCardMetadata(
            title = title,
            poster = details?.posterUrl ?: item.posterUrl,
            background = details?.backgroundPosterUrl ?: item.posterUrl,
            plot = details?.synopsis?.takeIf(String::isNotBlank) ?: item.plot,
            tags = tags,
            year = year,
            score = details?.publicScore ?: item.score,
            showStatus = details?.airStatus,
            nextAiring = details?.nextAiring,
            actors = details?.actors.orEmpty(),
            duration = details?.duration,
            synonyms = details?.synonyms.orEmpty(),
        )
    }

    private suspend fun createTrackingLoadResponse(
        route: String,
        service: StreamCenterTrackingService,
        item: SyncAPI.LibraryItem,
        mediaId: String,
        details: SyncAPI.SyncResult?,
        personal: TrackingPersonalMetadata,
    ): LoadResponse {
        val metadata = trackingCardMetadata(item, details, personal)
        val applySharedMetadata: suspend LoadResponse.() -> Unit = {
            apiName = this@StreamCenter.name
            if (!performanceMode) {
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.background
                plot = metadata.plot
                tags = metadata.tags
                year = metadata.year
                duration = metadata.duration
                actors = metadata.actors
                posterHeaders = item.posterHeaders
                addScore(metadata.score)
            }
            addStreamCenterTrackingId(service.syncIdName, mediaId)
        }
        val response = when (item.type) {
            TvType.TvSeries -> newTvSeriesLoadResponse(
                metadata.title,
                route,
                TvType.TvSeries,
                emptyList(),
            ) {
                applySharedMetadata()
                if (!performanceMode) {
                    showStatus = metadata.showStatus
                    nextAiring = metadata.nextAiring
                }
            }
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> newAnimeLoadResponse(
                metadata.title,
                route,
                item.type ?: TvType.Anime,
            ) {
                applySharedMetadata()
                applyAnimeCatalogTitles(null, null, metadata.synonyms)
                if (!performanceMode) {
                    showStatus = metadata.showStatus
                    nextAiring = metadata.nextAiring
                }
            }
            else -> newMovieLoadResponse(
                metadata.title,
                route,
                item.type ?: TvType.Movie,
                dataUrl = "",
            ) {
                applySharedMetadata()
            }
        }
        val librarySource = "${service.title} (libreria personale)"
        val detailsSource = "${service.title} (dettagli metadati)"
        return response.withCardProvenance(
            defaultSource = service.title,
            fieldSources = mapOf(
                "titolo" to listOf(
                    if (!details?.title.isNullOrBlank()) detailsSource else librarySource,
                ),
                "poster" to listOf(
                    if (!details?.posterUrl.isNullOrBlank()) detailsSource else librarySource,
                ),
                "sfondo" to listOf(
                    if (!details?.backgroundPosterUrl.isNullOrBlank()) detailsSource else librarySource,
                ),
                "trama" to listOf(
                    if (!details?.synopsis.isNullOrBlank()) detailsSource else librarySource,
                ),
                "tag" to listOf(detailsSource, librarySource, "${service.title} (dati personali)"),
                "anno" to listOf(
                    if (item.releaseDate != null) librarySource else detailsSource,
                ),
                "punteggio" to listOf(
                    if (details?.publicScore != null) detailsSource else librarySource,
                ),
                "durata_minuti" to listOf(detailsSource),
                "cast" to listOf(detailsSource),
                "stato_trasmissione" to listOf(detailsSource),
                "prossimo_episodio" to listOf(detailsSource),
                "titoli_alternativi" to listOf(detailsSource),
                "header_poster" to listOf(librarySource),
                "id_sincronizzazione" to listOf(service.title),
                "episodi" to listOf("StreamCenter (nessun episodio fornito dalla libreria)"),
            ),
            fieldNotes = mapOf(
                "tag" to "Il valore finale può combinare generi pubblici, progresso e dati personali della libreria.",
            ),
        )
    }

    private suspend fun loadStremioCatalogMedia(url: String): LoadResponse {
        val media = stremioCatalogClient?.media(url)
            ?: throw IllegalStateException("Elemento del catalogo Stremio non disponibile")
        val type = stremioCatalogTvType(media.type)
            ?: throw IllegalArgumentException("Tipo Stremio non supportato")
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = when (type) {
                TvType.Movie -> listOf("movie")
                TvType.Anime -> listOf("series", "anime")
                TvType.Live -> listOf("tv", "channel")
                else -> listOf("series")
            },
            stremioId = media.id,
            imdbId = media.imdbId,
            tmdbId = media.tmdbId,
            anilistId = media.anilistId,
            malId = media.malId,
            kitsuId = media.kitsuId,
            catalogAddonKey = catalogDefinition?.stremioAddon?.key,
        )
        val tmdbEnglishTitle = when (type) {
            TvType.Movie -> resolveTmdbEnglishTitle(media.tmdbId, isMovie = true)
            TvType.TvSeries -> resolveTmdbEnglishTitle(media.tmdbId, isMovie = false)
            else -> null
        }
        val torrentContext = when (type) {
            TvType.Live -> null
            TvType.Anime -> animeTorrentPlaybackContext(
                titles = listOf(media.name),
                year = media.year,
                isMovie = false,
                tabName = media.name,
                anilistId = media.anilistId,
                malId = media.malId,
                kitsuId = media.kitsuId,
                imdbId = media.imdbId,
            )
            else -> torrentPlaybackContext(
                titles = listOf(media.name),
                englishTitle = tmdbEnglishTitle,
                year = media.year,
                isAnime = false,
                isMovie = type == TvType.Movie,
                imdbId = media.imdbId,
            )
        }
        val applyMetadata: LoadResponse.() -> Unit = {
            apiName = this@StreamCenter.name
            posterUrl = media.posterUrl
            backgroundPosterUrl = media.backgroundUrl
            plot = media.description
            tags = media.genres
            year = media.year
            addScore(media.score?.let { value -> Score.from(value, 10) })
            addStreamCenterTrackingIds(
                StreamCenterTrackingIds(
                    tmdb = media.tmdbId,
                    imdb = media.imdbId,
                    anilist = media.anilistId,
                    mal = media.malId,
                    kitsu = media.kitsuId,
                ),
            )
        }
        val response = when (type) {
            TvType.Movie -> newMovieLoadResponse(
                media.name,
                url,
                TvType.Movie,
                dataUrl = StreamCenterPlaybackData(
                    stremio = stremioContext,
                    torrent = torrentContext,
                ).toJson(),
            ) {
                applyMetadata()
            }
            TvType.Live -> newLiveStreamLoadResponse(
                media.name,
                url,
                StreamCenterPlaybackData(stremio = stremioContext).toJson(),
            ) {
                applyMetadata()
            }
            TvType.Anime -> {
                val episodes = buildStremioCatalogEpisodes(media, stremioContext, torrentContext)
                check(episodes.isNotEmpty()) { "L'add-on non fornisce gli episodi per questo anime" }
                newAnimeLoadResponse(media.name, url, TvType.Anime) {
                    applyMetadata()
                    addEpisodes(DubStatus.Subbed, episodes)
                    addSeasonNames(buildAnimeSeasonData(episodes))
                }
            }
            else -> {
                val episodes = buildStremioCatalogEpisodes(media, stremioContext, torrentContext)
                check(episodes.isNotEmpty()) { "L'add-on non fornisce gli episodi per questa serie" }
                newTvSeriesLoadResponse(media.name, url, TvType.TvSeries, episodes) {
                    applyMetadata()
                    addSeasonNames(buildAnimeSeasonData(episodes))
                }
            }
        }
        val addonSource = catalogDefinition?.stremioAddon?.name
            ?.let { "Add-on Stremio: $it" }
            ?: "Add-on Stremio"
        val playbackSources = (
            listOf(addonSource, "StreamCenter (contesto Stremio)") +
                torrentPlaybackProvenance(torrentContext)
            ).distinct()
        return response.withCardProvenance(
            defaultSource = addonSource,
            fieldSources = mapOf(
                "tipo_contenuto" to listOf(addonSource, "StreamCenter (conversione tipo)"),
                "id_sincronizzazione" to listOf(addonSource),
                "stagioni" to listOf(addonSource, "StreamCenter (raggruppamento episodi)"),
                "episodi" to listOf(addonSource),
                "episodi.dati_riproduzione" to playbackSources,
                "dati_riproduzione" to playbackSources,
            ),
        )
    }

    private fun torrentPlaybackContext(
        titles: Iterable<String?>,
        englishTitle: String? = null,
        year: Int? = null,
        isAnime: Boolean,
        isMovie: Boolean,
        japaneseTitle: String? = null,
        imdbId: String? = null,
    ): StreamCenterTorrentPlaybackContext? {
        val baseTitles = titles
            .mapNotNull(::cleanText)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
        val normalizedEnglishTitle = englishTitle
            ?.let(::cleanText)
            ?.takeIf(String::isNotBlank)
        val normalizedTitles = buildList {
            baseTitles.firstOrNull()?.let(::add)
            normalizedEnglishTitle?.let(::add)
            addAll(baseTitles.drop(1))
        }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(6)
        if (normalizedTitles.isEmpty()) return null
        return StreamCenterTorrentPlaybackContext(
            titles = normalizedTitles,
            englishTitle = normalizedEnglishTitle,
            year = year,
            isAnime = isAnime,
            isMovie = isMovie,
            japaneseTitle = japaneseTitle?.let(::cleanText),
            imdbId = imdbId?.trim()?.takeIf { id -> id.startsWith("tt", ignoreCase = true) },
        )
    }

    private fun shouldResolveTorrentJapaneseTitle(): Boolean =
        StreamCenterPlugin.isTorrentEnabled(sharedPref)

    private fun shouldResolveTmdbEnglishTitle(): Boolean {
        return StreamCenterPlugin.isTorrentEnabled(sharedPref)
    }

    private suspend fun resolveTmdbEnglishTitle(
        tmdbId: String?,
        isMovie: Boolean,
    ): String? {
        if (!shouldResolveTmdbEnglishTitle()) return null
        val normalizedId = tmdbId
            ?.trim()
            ?.takeIf { value -> value.all(Char::isDigit) }
            ?: return null
        return runCatching {
            tmdbCatalog.englishTitle(
                type = if (isMovie) "movie" else "tv",
                id = normalizedId,
            )
        }.getOrNull()
    }

    private fun shouldResolveAnimeTorrentMetadata(): Boolean {
        return StreamCenterPlugin.isTorrentEnabled(sharedPref)
    }

    private suspend fun animeTorrentPlaybackContext(
        titles: Iterable<String?>,
        year: Int? = null,
        isMovie: Boolean,
        tabName: String,
        aniZipCatalog: AniZipEpisodeCatalog? = null,
        anilistId: Int? = null,
        malId: Int? = null,
        kitsuId: Int? = null,
        imdbId: String? = null,
        knownKitsuTitle: String? = null,
        knownAniListTitle: String? = null,
        knownMyAnimeListTitle: String? = null,
    ): StreamCenterTorrentPlaybackContext? {
        val baseContext = torrentPlaybackContext(
            titles = titles,
            year = year,
            isAnime = true,
            isMovie = isMovie,
            imdbId = imdbId,
        ) ?: return null
        val shouldResolveJapaneseTitle = shouldResolveTorrentJapaneseTitle()
        if (!shouldResolveAnimeTorrentMetadata() && !shouldResolveJapaneseTitle) return baseContext

        val startedAt = System.currentTimeMillis()
        if (shouldResolveJapaneseTitle) {
            StreamCenterLogger.logMetadata(
                tabName = tabName,
                source = "Torrent · EXT",
                action = "Risoluzione titolo giapponese avviata",
                metadata = mapOf(
                    "ordine_provider" to listOf("AniZip", "Kitsu", "AniList", "MyAnimeList"),
                    "id_anilist" to anilistId,
                    "id_myanimelist" to malId,
                    "id_kitsu" to kitsuId,
                    "catalogo_anizip_gia_disponibile" to (aniZipCatalog != null),
                ),
            )
        }

        var aniZipTimedOut = false
        var aniZipFailed = false
        val resolvedAniZipCatalog = if (aniZipCatalog != null) {
            aniZipCatalog
        } else if (anilistId != null || malId != null) {
            try {
                withTimeoutOrNull(ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS) {
                    aniZipMetadataClient.fetch(anilistId, malId)
                } ?: AniZipEpisodeCatalog().also { aniZipTimedOut = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                aniZipFailed = true
                StreamCenterLogger.logTabError(
                    tabName = tabName,
                    action = "Recupero metadati Torrent AniZip non riuscito",
                    throwable = error,
                    metadata = mapOf("id_anilist" to anilistId, "id_myanimelist" to malId),
                )
                AniZipEpisodeCatalog()
            }
        } else {
            AniZipEpisodeCatalog()
        }

        val aniZipTitles = resolvedAniZipCatalog.titles.values
            .mapNotNull(::cleanText)
            .filter(String::isNotBlank)
            .distinctBy { title -> title.lowercase(Locale.ROOT) }
        val promotedTitles = buildList {
            baseContext.titles.firstOrNull()?.let(::add)
            addAll(aniZipTitles)
            addAll(baseContext.titles.drop(1))
        }.distinctBy { title -> title.lowercase(Locale.ROOT) }
        val rawEpisodeNumberings = resolvedAniZipCatalog.episodes
            .mapValues { (_, metadata) ->
                StreamCenterTorrentEpisodeNumbering(
                    seasonNumber = metadata.seasonNumber?.takeIf { number -> number > 0 },
                    seasonEpisodeNumber = metadata.episodeNumber?.takeIf { number -> number > 0 },
                    absoluteEpisodeNumber = metadata.absoluteEpisodeNumber
                        ?.takeIf { number -> number > 0 },
                )
            }
            .filterValues { numbering ->
                numbering.seasonNumber != null ||
                    numbering.seasonEpisodeNumber != null ||
                    numbering.absoluteEpisodeNumber != null
            }
        val episodeNumberings = normalizeDominantEpisodeOffsets(rawEpisodeNumberings)
        val episodeNumberAliases = episodeNumberings
            .mapValues { (localNumber, numbering) ->
                listOfNotNull(
                    numbering.seasonEpisodeNumber,
                    numbering.absoluteEpisodeNumber,
                )
                    .filter { number -> number > 0 && number != localNumber }
                    .distinct()
            }
            .filterValues(List<Int>::isNotEmpty)
        val contextWithEpisodeAliases = baseContext.copy(
            titles = promotedTitles,
            episodeNumberAliases = episodeNumberAliases.ifEmpty { null },
            episodeNumberings = episodeNumberings.ifEmpty { null },
            imdbId = baseContext.imdbId ?: resolvedAniZipCatalog.imdbId,
        )
        StreamCenterLogger.logMetadata(
            tabName = tabName,
            source = "Torrent · AniZip",
            action = if (episodeNumberAliases.isEmpty()) {
                "Numerazione alternativa episodi Torrent non trovata"
            } else {
                "Numerazione alternativa episodi Torrent risolta"
            },
            metadata = mapOf(
                "episodi_anizip" to resolvedAniZipCatalog.episodes.size,
                "titoli_anizip_promossi" to aniZipTitles.size,
                "episodi_con_numerazione_alternativa" to episodeNumberAliases.size,
                "episodi_con_coordinate_strutturate" to episodeNumberings.size,
            ),
            level = if (episodeNumberAliases.isEmpty()) {
                StreamCenterLogger.Level.WARNING
            } else {
                StreamCenterLogger.Level.INFO
            },
        )
        if (!shouldResolveJapaneseTitle) return contextWithEpisodeAliases

        val resolution = try {
            animeJapaneseTitleResolver.resolve(
                aniZipCatalog = resolvedAniZipCatalog,
                kitsuId = kitsuId,
                anilistId = anilistId,
                malId = malId,
                hints = AnimeJapaneseTitleHints(
                    kitsu = listOfNotNull(knownKitsuTitle),
                    aniList = listOfNotNull(knownAniListTitle),
                    myAnimeList = listOfNotNull(knownMyAnimeListTitle),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            StreamCenterLogger.logTabError(
                tabName = tabName,
                action = "Risoluzione titolo giapponese Torrent non riuscita",
                throwable = error,
                metadata = mapOf(
                    "id_anilist" to anilistId,
                    "id_myanimelist" to malId,
                    "id_kitsu" to kitsuId,
                ),
            )
            return contextWithEpisodeAliases
        }

        val sourceName = resolution.source?.logName
        StreamCenterLogger.logMetadata(
            tabName = tabName,
            source = "Torrent · EXT",
            action = if (resolution.title != null) {
                "Titolo giapponese Torrent risolto"
            } else {
                "Titolo giapponese Torrent non trovato"
            },
            metadata = mapOf(
                "fonte_titolo" to sourceName,
                "fallback_usato" to (sourceName != null && sourceName != "AniZip"),
                "cache" to resolution.cacheHit,
                "script_giapponese_valido" to (resolution.title != null),
                "anizip_timeout" to aniZipTimedOut,
                "anizip_errore" to aniZipFailed,
                "durata_ms" to (System.currentTimeMillis() - startedAt),
                "tentativi" to resolution.attempts.map { attempt ->
                    mapOf(
                        "provider" to attempt.source.logName,
                        "stato" to attempt.status.toString(),
                        "origine" to attempt.origin,
                        "identificativo" to attempt.identifier,
                        "durata_ms" to attempt.durationMs,
                        "tipo_errore" to attempt.errorType,
                    )
                },
            ),
            level = if (resolution.title != null) {
                StreamCenterLogger.Level.INFO
            } else {
                StreamCenterLogger.Level.WARNING
            },
        )
        return contextWithEpisodeAliases.copy(japaneseTitle = resolution.title)
    }

    private fun torrentPlaybackProvenance(
        context: StreamCenterTorrentPlaybackContext?,
    ): List<String> {
        if (context == null || !StreamCenterPlugin.isTorrentEnabled(sharedPref)) {
            return emptyList()
        }
        return if (StreamCenterPlugin.isTorrentEnabled(sharedPref)) {
            listOf("Fonti Torrent abilitate", "StreamCenter (contesto Torrent)")
        } else {
            emptyList()
        }
    }

    private fun buildStremioCatalogEpisodes(
        media: StreamCenterStremioCatalogItem,
        stremioContext: StreamCenterStremioPlaybackContext,
        torrentContext: StreamCenterTorrentPlaybackContext?,
    ): List<Episode> {
        return media.videos
            .distinctBy { video -> video.id }
            .mapIndexed { index, video ->
                val season = video.season ?: 1
                val episode = video.episode ?: index + 1
                newEpisode(
                    StreamCenterPlaybackData(
                        stremio = stremioContext.copy(
                            stremioVideoId = video.id,
                            season = season,
                            episode = episode,
                        ),
                        torrent = torrentContext?.forEpisode(season, episode),
                    ).toJson(),
                ) {
                    name = video.title ?: "Episodio $episode"
                    this.season = season
                    this.episode = episode
                    posterUrl = video.posterUrl ?: media.posterUrl
                    description = video.description
                }
            }
            .sortedWith(compareBy({ episode -> episode.season ?: 1 }, { episode -> episode.episode ?: 0 }))
    }

    private fun stremioCatalogTvType(type: String): TvType? = when (type.lowercase(Locale.ROOT)) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "tv", "channel" -> TvType.Live
        else -> null
    }

    private suspend fun loadTmdbMedia(
        actualUrl: String,
        scHint: StreamingCommunityTitle? = null,
        strictTmdbMetadata: Boolean = false,
    ): LoadResponse {
        val isTvSeries = actualUrl.contains("/tv/")
        val (doc, tmdbEnglishTitle) = coroutineScope {
            val englishTitleDeferred = async(Dispatchers.IO) {
                resolveTmdbEnglishTitle(
                    tmdbId = extractTmdbId(actualUrl),
                    isMovie = !isTvSeries,
                )
            }
            getTmdbDocument(actualUrl) to englishTitleDeferred.await()
        }
        val metadata = buildMetadata(
            doc,
            actualUrl,
            minimalMetadata = performanceMode && !strictTmdbMetadata,
        )
        val cardTitle = streamCenterUrlParameter(actualUrl, "title")
        val cardPoster = streamCenterUrlParameter(actualUrl, "poster")
        val streamingCommunityTitle = scHint ?: if (
            isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        ) {
            streamingCommunityClient.findTitle(metadata, isTvSeries)
        } else {
            null
        }
        val sc = streamingCommunityTitle
        val tmdbImdbId = extractTmdbImdbId(doc)
        val resolvedStremioImdbId = if (
            tmdbImdbId == null &&
            sc?.imdbId == null &&
            activeStremioResolversNeedImdbId(if (isTvSeries) "series" else "movie")
        ) {
            StreamCenterStremioAddonClient.resolveImdbId(
                contentType = if (isTvSeries) "series" else "movie",
                titleCandidates = listOfNotNull(metadata.originalTitle, metadata.title),
                year = metadata.year,
            )
        } else {
            null
        }
        val playbackImdbId = tmdbImdbId ?: sc?.imdbId ?: resolvedStremioImdbId
        val responseImdbId = tmdbImdbId ?: sc?.imdbId.takeUnless { strictTmdbMetadata }
        val resolvedSimklId = if (catalogDefinition == null) {
            resolveSimklId(
                imdb = playbackImdbId,
                tmdb = metadata.tmdbId,
                allowedCategories = if (isTvSeries) setOf("tv") else setOf("movies"),
            )
        } else {
            null
        }
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = if (isTvSeries) listOf("series") else listOf("movie"),
            imdbId = playbackImdbId,
            tmdbId = metadata.tmdbId,
        )

        val title = metadata.title.takeIf { it.isNotBlank() && it != "Sconosciuto" }
            ?: cardTitle
            ?: sc?.name.takeUnless { strictTmdbMetadata }
            ?: metadata.title
        val poster = metadata.poster
            ?: cardPoster
            ?: streamingCommunityClient.imageUrl(sc?.posterFilename).takeUnless { strictTmdbMetadata }
        val background = metadata.background
            ?: streamingCommunityClient.imageUrl(sc?.backgroundFilename).takeUnless { strictTmdbMetadata }
        val logo = metadata.logo
            ?: streamingCommunityClient.imageUrl(sc?.logoFilename).takeUnless { strictTmdbMetadata }
        val plot = metadata.plot ?: sc?.plot.takeUnless { strictTmdbMetadata }
        val tags = metadata.tags.distinctBy { it.lowercase(Locale.ROOT) }
            .ifEmpty { sc?.genres.orEmpty().takeUnless { strictTmdbMetadata }.orEmpty() }
        val year = metadata.year ?: sc?.year.takeUnless { strictTmdbMetadata }
        val score = metadata.score ?: sc?.score.takeUnless { strictTmdbMetadata }
        val contentRating = metadata.contentRating
            ?: sc?.age?.let { "$it+" }.takeUnless { strictTmdbMetadata }
        val torrentContext = torrentPlaybackContext(
            titles = listOf(title, metadata.originalTitle, cardTitle, sc?.name),
            englishTitle = tmdbEnglishTitle,
            year = year,
            isAnime = false,
            isMovie = !isTvSeries,
            imdbId = playbackImdbId,
        )
        StreamCenterLogger.logMetadata(
            tabName = title,
            source = "TMDB",
            action = "Metadati TMDB acquisiti",
            metadata = mapOf(
                "id_tmdb" to metadata.tmdbId,
                "tipo" to if (isTvSeries) "serie_tv" else "film",
                "modalita_minimale" to (performanceMode && !strictTmdbMetadata),
                "titolo_originale_disponibile" to !metadata.originalTitle.isNullOrBlank(),
                "titolo_inglese_torrent_disponibile" to !tmdbEnglishTitle.isNullOrBlank(),
                "trama_disponibile" to !metadata.plot.isNullOrBlank(),
                "poster_disponibile" to !metadata.poster.isNullOrBlank(),
                "tag" to metadata.tags.size,
                "persone" to metadata.people.size,
                "anno" to metadata.year,
                "imdb_disponibile" to !tmdbImdbId.isNullOrBlank(),
            ),
        )
        StreamCenterLogger.logMetadata(
            tabName = title,
            source = "StreamingCommunity",
            action = if (sc == null) {
                "Corrispondenza StreamingCommunity non trovata"
            } else {
                "Corrispondenza StreamingCommunity acquisita"
            },
            metadata = mapOf(
                "fonte_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY),
                "corrispondenza_presente" to (sc != null),
                "id_streamingcommunity" to sc?.id,
                "tipo" to sc?.type,
                "id_tmdb_associazione" to sc?.tmdbId,
            ),
            level = if (sc == null) StreamCenterLogger.Level.WARNING else StreamCenterLogger.Level.INFO,
        )
        val recommendations = if (strictTmdbMetadata) {
            runCatching { tmdbCatalog.recommendations(this, actualUrl, showCardScores) }.getOrDefault(emptyList())
        } else if (!performanceMode) {
            sc?.let { fetchStreamingCommunityRecommendations(it) }.orEmpty()
        } else {
            emptyList()
        }

        val response = if (isTvSeries) {
            val streamingCommunityEpisodes = streamingCommunityTitle
                ?.let { streamingCommunityClient.episodePayloads(it) }
                .orEmpty()
            val episodes = fetchEpisodes(
                doc = doc,
                actualUrl = actualUrl,
                streamingCommunityEpisodes = streamingCommunityEpisodes,
                stremioContext = stremioContext,
                torrentContext = torrentContext,
                fallbackPoster = poster.takeIf { !performanceMode || strictTmdbMetadata },
                minimalMetadata = performanceMode && !strictTmdbMetadata,
            ).ifEmpty {
                if (strictTmdbMetadata) {
                    emptyList()
                } else {
                    buildStreamingCommunityEpisodes(
                        streamingCommunityEpisodes,
                        poster.takeIf { !performanceMode },
                        stremioContext,
                        torrentContext,
                    )
                }
            }
            val seasonNames = if (strictTmdbMetadata) {
                runCatching { fetchTmdbSeasonNames(actualUrl, episodes) }
                    .getOrDefault(buildAnimeSeasonData(episodes))
            } else {
                buildAnimeSeasonData(episodes)
            }
            StreamCenterLogger.logTab(
                tabName = title,
                action = "Dettagli serie TMDB aggregati",
                metadata = mapOf(
                    "episodi_tmdb" to episodes.size,
                    "episodi_streamingcommunity" to streamingCommunityEpisodes.size,
                    "stagioni" to seasonNames.size,
                    "raccomandazioni" to recommendations.size,
                    "id_simkl" to resolvedSimklId,
                    "id_imdb_risolto" to playbackImdbId,
                ),
            )
            newTvSeriesLoadResponse(
                title,
                actualUrl,
                TvType.TvSeries,
                episodes,
            ) {
                if (!performanceMode || strictTmdbMetadata) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.logoUrl = logo
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.actors = metadata.people
                    this.recommendations = recommendations
                    this.contentRating = contentRating
                    this.duration = metadata.duration
                    this.showStatus = metadata.showStatus
                        ?: streamingCommunityClient.showStatus(sc?.status).takeUnless { strictTmdbMetadata }
                    this.comingSoon = metadata.comingSoon
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        tmdb = metadata.tmdbId,
                        imdb = responseImdbId,
                        simkl = resolvedSimklId,
                    ),
                    showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                )
                addSeasonNames(seasonNames)
                if (!performanceMode || strictTmdbMetadata) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(score)
                }
            }
        } else {
            val moviePlaybackData = StreamCenterPlaybackData(
                tmdbUrl = actualUrl,
                streamingCommunity = streamingCommunityTitle?.let(streamingCommunityClient::moviePlayback),
                stremio = stremioContext,
                torrent = torrentContext,
            )
            StreamCenterLogger.logTab(
                tabName = title,
                action = "Dettagli film TMDB aggregati",
                metadata = mapOf(
                    "raccomandazioni" to recommendations.size,
                    "id_simkl" to resolvedSimklId,
                    "id_imdb_risolto" to playbackImdbId,
                    "fonte_riproduzione_streamingcommunity" to (streamingCommunityTitle != null),
                ),
            )
            newMovieLoadResponse(
                title,
                actualUrl,
                TvType.Movie,
                dataUrl = moviePlaybackData.toJson(),
            ) {
                if (!performanceMode || strictTmdbMetadata) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.logoUrl = logo
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.duration = metadata.duration ?: sc?.runtime.takeUnless { strictTmdbMetadata }
                    this.actors = metadata.people
                    this.recommendations = recommendations
                    this.contentRating = contentRating
                    this.comingSoon = metadata.comingSoon
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        tmdb = metadata.tmdbId,
                        imdb = responseImdbId,
                        simkl = resolvedSimklId,
                    ),
                    showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                )
                if (!performanceMode || strictTmdbMetadata) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(score)
                }
            }
        }
        val titleSource = when {
            metadata.title.isNotBlank() && metadata.title != "Sconosciuto" -> "TMDB"
            !cardTitle.isNullOrBlank() -> "Parametri della card StreamCenter"
            !strictTmdbMetadata && !sc?.name.isNullOrBlank() -> "StreamingCommunity"
            else -> "TMDB"
        }
        val posterSource = when {
            !metadata.poster.isNullOrBlank() -> "TMDB"
            !cardPoster.isNullOrBlank() -> "Parametri della card StreamCenter"
            !strictTmdbMetadata && !sc?.posterFilename.isNullOrBlank() -> "StreamingCommunity"
            else -> "TMDB (valore non disponibile)"
        }
        val metadataOrStreamingCommunity = { tmdbAvailable: Boolean, scAvailable: Boolean ->
            when {
                tmdbAvailable -> listOf("TMDB")
                !strictTmdbMetadata && scAvailable -> listOf("StreamingCommunity")
                else -> listOf("TMDB (valore non disponibile)")
            }
        }
        val episodeSources = buildList {
            add("TMDB")
            if (streamingCommunityTitle != null) add("StreamingCommunity")
            add("Add-on Stremio abilitati")
            addAll(torrentPlaybackProvenance(torrentContext))
            add("StreamCenter (aggregazione episodio)")
        }
        val playbackPayloadSources = (
            listOf(
                "TMDB",
                "StreamingCommunity",
                "Add-on Stremio abilitati",
            ) +
                torrentPlaybackProvenance(torrentContext) +
                "StreamCenter (payload di riproduzione)"
            ).distinct()
        return response.withCardProvenance(
            defaultSource = "TMDB",
            fieldSources = mapOf(
                "titolo" to listOf(titleSource),
                "poster" to listOf(posterSource),
                "sfondo" to metadataOrStreamingCommunity(
                    !metadata.background.isNullOrBlank(),
                    !sc?.backgroundFilename.isNullOrBlank(),
                ),
                "logo" to metadataOrStreamingCommunity(
                    !metadata.logo.isNullOrBlank(),
                    !sc?.logoFilename.isNullOrBlank(),
                ),
                "trama" to metadataOrStreamingCommunity(
                    !metadata.plot.isNullOrBlank(),
                    !sc?.plot.isNullOrBlank(),
                ),
                "tag" to metadataOrStreamingCommunity(
                    metadata.tags.isNotEmpty(),
                    !sc?.genres.isNullOrEmpty(),
                ),
                "anno" to metadataOrStreamingCommunity(
                    metadata.year != null,
                    sc?.year != null,
                ),
                "punteggio" to metadataOrStreamingCommunity(
                    !metadata.score.isNullOrBlank(),
                    !sc?.score.isNullOrBlank(),
                ),
                "classificazione_contenuti" to metadataOrStreamingCommunity(
                    !metadata.contentRating.isNullOrBlank(),
                    sc?.age != null,
                ),
                "durata_minuti" to metadataOrStreamingCommunity(
                    metadata.duration != null,
                    sc?.runtime != null,
                ),
                "cast" to listOf("TMDB"),
                "trailer" to listOf("TMDB"),
                "in_arrivo" to listOf("TMDB"),
                "raccomandazioni" to listOf(
                    if (strictTmdbMetadata) "TMDB" else "StreamingCommunity",
                ),
                "id_sincronizzazione" to listOf("TMDB", "IMDb", "Simkl"),
                "stato_trasmissione" to listOf("TMDB"),
                "stagioni" to listOf("TMDB", "StreamCenter (normalizzazione stagioni)"),
                "episodi" to episodeSources,
                "episodi.dati_riproduzione" to playbackPayloadSources,
                "dati_riproduzione" to playbackPayloadSources,
            ),
            fieldNotes = mapOf(
                "episodi" to "Ogni episodio conserva integralmente metadati e payload finale; le fonti elencate sono quelle combinate nel builder.",
                "raccomandazioni" to if (performanceMode && !strictTmdbMetadata) {
                    "Valore vuoto perché la modalità prestazioni evita la richiesta opzionale."
                } else {
                    "Fonte scelta in base alla modalità del catalogo."
                },
            ),
        )
    }

    private data class ResolvedIptvStream(
        val url: String,
        val type: ExtractorLinkType,
    )

    private suspend fun resolveIptvStream(
        originalUrl: String,
        requestHeaders: Map<String, String>,
    ): ResolvedIptvStream {
        fun typeFrom(url: String, contentType: String = "", bodyStart: String = ""): ExtractorLinkType? {
            val normalizedUrl = url.substringBefore('?').lowercase(Locale.ROOT)
            val normalizedType = contentType.lowercase(Locale.ROOT)
            return when {
                normalizedUrl.endsWith(".m3u8") ||
                    "mpegurl" in normalizedType ||
                    bodyStart.trimStart().startsWith("#EXTM3U") -> ExtractorLinkType.M3U8
                normalizedUrl.endsWith(".mpd") ||
                    "dash+xml" in normalizedType ||
                    bodyStart.trimStart().startsWith("<MPD", ignoreCase = true) -> ExtractorLinkType.DASH
                normalizedUrl.endsWith(".mp4") || normalizedUrl.endsWith(".webm") -> ExtractorLinkType.VIDEO
                else -> null
            }
        }

        typeFrom(originalUrl)?.let { return ResolvedIptvStream(originalUrl, it) }
        val response = runCatching {
            app.get(
                originalUrl,
                headers = requestHeaders,
                allowRedirects = true,
                timeout = 15L,
            )
        }.getOrNull()
        if (response != null) {
            val finalUrl = response.url
            val contentType = response.headers["Content-Type"].orEmpty()
            val detected = typeFrom(finalUrl, contentType, response.text.take(64))
            if (detected != null) return ResolvedIptvStream(finalUrl, detected)
        }
        return ResolvedIptvStream(response?.url ?: originalUrl, ExtractorLinkType.M3U8)
    }

    private fun activeStremioResolverAddons(): List<StreamCenterStremioAddon> {
        val addonsByKey = StreamCenterPlugin.getStremioAddons(sharedPref)
            .associateBy(StreamCenterStremioAddon::key)
        return StreamCenterPlugin.getSourcePriorityOrder(sharedPref).asSequence()
            .distinct()
            .mapNotNull(addonsByKey::get)
            .filter { addon -> StreamCenterPlugin.isStremioAddonEnabled(sharedPref, addon.key) }
            .filter { addon ->
                addon.resources.any { resource ->
                    resource.name.equals("stream", ignoreCase = true) ||
                        resource.name.equals("subtitles", ignoreCase = true)
                }
            }
            .toList()
    }

    private fun activeStremioResolversNeedKitsuId(): Boolean {
        return activeStremioResolverAddons().any { addon ->
            addon.resources.any { resource ->
                val isPlaybackResource =
                    resource.name.equals("stream", ignoreCase = true) ||
                        resource.name.equals("subtitles", ignoreCase = true)
                val supportedTypes = resource.types.ifEmpty { addon.types }
                val supportsAnime = supportedTypes.isEmpty() || supportedTypes.any { type ->
                    type.equals("anime", ignoreCase = true) ||
                        type.equals("series", ignoreCase = true)
                }
                val prefixes = resource.idPrefixes.ifEmpty { addon.idPrefixes }
                val needsKitsu = prefixes.isNotEmpty() &&
                    prefixes.any { prefix -> "kitsu:1".startsWith(prefix, ignoreCase = true) } &&
                    prefixes.none { prefix -> "anilist:1".startsWith(prefix, ignoreCase = true) }
                isPlaybackResource && supportsAnime && needsKitsu
            }
        }
    }

    private fun activeStremioResolversNeedImdbId(contentType: String): Boolean {
        return activeStremioResolverAddons().any { addon ->
            addon.resources.any { resource ->
                val isPlaybackResource =
                    resource.name.equals("stream", ignoreCase = true) ||
                        resource.name.equals("subtitles", ignoreCase = true)
                val supportedTypes = resource.types.ifEmpty { addon.types }
                val supportsContentType = supportedTypes.isEmpty() ||
                    supportedTypes.any { it.equals(contentType, ignoreCase = true) }
                val prefixes = resource.idPrefixes.ifEmpty { addon.idPrefixes }
                val supportsImdb = prefixes.any { prefix ->
                    "tt0000000".startsWith(prefix, ignoreCase = true) ||
                        "imdb:tt0000000".startsWith(prefix, ignoreCase = true)
                }
                val supportsTmdb = prefixes.any { prefix -> "tmdb:1".startsWith(prefix, ignoreCase = true) }
                isPlaybackResource && supportsContentType && prefixes.isNotEmpty() && supportsImdb && !supportsTmdb
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        StreamCenterVpnGuard.requireInternetAccess(sharedPref)
        val iptv = runCatching { JSONObject(data) }.getOrNull()
            ?.takeIf { it.optBoolean("streamcenterIptv") }
        if (iptv != null) {
            val channelName = iptv.optString("name", "TV")
            val streamUrl = iptv.optString("url").takeIf(String::isNotBlank) ?: run {
                StreamCenterLogger.logTab(
                    tabName = channelName,
                    action = "Risoluzione stream IPTV non riuscita",
                    metadata = mapOf("motivo" to "url_mancante", "casting" to isCasting),
                    level = StreamCenterLogger.Level.WARNING,
                )
                return false
            }
            StreamCenterLogger.logTab(
                tabName = channelName,
                action = "Risoluzione stream IPTV avviata",
                metadata = mapOf(
                    "casting" to isCasting,
                    "header_personalizzati" to (iptv.has("userAgent") || iptv.has("referer")),
                ),
            )
            val linkHeaders = buildMap {
                iptv.optString("userAgent").takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
                iptv.optString("referer").takeIf(String::isNotBlank)?.let { put("Referer", it) }
            }
            val resolved = resolveIptvStream(streamUrl, linkHeaders)
            callback(newExtractorLink(name, channelName, resolved.url, resolved.type) {
                quality = Qualities.Unknown.value
                headers = linkHeaders
            })
            StreamCenterLogger.logTab(
                tabName = channelName,
                action = "Stream IPTV risolto",
                metadata = mapOf("tipo_stream" to resolved.type.toString(), "header_personalizzati" to linkHeaders.isNotEmpty()),
            )
            return true
        }
        val playbackData = runCatching { parseJson<StreamCenterPlaybackData>(data) }.getOrNull()
        val playbackLogTab = playbackData?.stremio?.anilistId?.let { "Anime AniList $it" }
            ?: playbackData?.stremio?.tmdbId?.let { "Contenuto TMDB $it" }
            ?: playbackData?.streamingCommunity?.tmdbId?.let { "Contenuto TMDB $it" }
            ?: "Riproduzione"
        StreamCenterLogger.logTab(
            tabName = playbackLogTab,
            action = "Risoluzione fonti di riproduzione avviata",
            metadata = mapOf(
                "casting" to isCasting,
                "modalita_prestazioni" to performanceMode,
                "payload_valido" to (playbackData != null),
                "animeunity_disponibile" to (playbackData?.animeUnity != null),
                "animeworld_disponibili" to playbackData?.animeWorld.orEmpty().size,
                "animesaturn_disponibili" to playbackData?.animeSaturn.orEmpty().size,
                "streamingcommunity_disponibile" to (playbackData?.streamingCommunity != null),
                "stremio_disponibile" to (playbackData?.stremio != null),
                "torrent_disponibile" to (playbackData?.torrent != null),
                "torrent_abilitati" to StreamCenterPlugin.isTorrentEnabled(sharedPref),
                "link_p2p_esclusi_per_casting" to isCasting,
            ),
            level = if (playbackData == null) StreamCenterLogger.Level.WARNING else StreamCenterLogger.Level.INFO,
        )
        val emittedLinkKeys = ConcurrentHashMap.newKeySet<String>()
        val emittedSubtitleKeys = ConcurrentHashMap.newKeySet<String>()
        val emittedAnyLink = AtomicBoolean(false)
        val resultCallbackLock = Any()
        val uniqueCallback: (ExtractorLink) -> Unit = linkCallback@{ link ->
            val isPeerToPeer =
                link.type == ExtractorLinkType.MAGNET ||
                    link.type == ExtractorLinkType.TORRENT ||
                    link.url.startsWith("magnet:", ignoreCase = true)
            if (isCasting && isPeerToPeer) return@linkCallback
            synchronized(resultCallbackLock) {
                if (emittedLinkKeys.add(sourceLinkDedupKey(link))) {
                    emittedAnyLink.set(true)
                    callback(link)
                    StreamCenterLogger.logTab(
                        tabName = playbackLogTab,
                        action = "Link di riproduzione emesso",
                        metadata = mapOf("link_univoci_emessi" to emittedLinkKeys.size),
                    )
                }
            }
        }
        val uniqueSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            synchronized(resultCallbackLock) {
                if (emittedSubtitleKeys.add(subtitle.url.trim().substringBefore('#'))) {
                    subtitleCallback(subtitle)
                    StreamCenterLogger.logTab(
                        tabName = playbackLogTab,
                        action = "Sottotitolo emesso",
                        metadata = mapOf("sottotitoli_univoci_emessi" to emittedSubtitleKeys.size),
                    )
                }
            }
        }
        val tasksBySource = linkedMapOf<String, MutableList<suspend () -> Boolean>>()
        val torrentTasks = linkedMapOf<String, suspend () -> Boolean>()
        val stremioTasks = linkedMapOf<String, suspend () -> Boolean>()
        fun loggedSourceTask(
            sourceName: String,
            warnWhenEmpty: Boolean = true,
            task: suspend () -> Boolean,
        ): suspend () -> Boolean = suspend {
            StreamCenterLogger.logTab(
                tabName = playbackLogTab,
                action = "Tentativo fonte riproduzione avviato",
                metadata = mapOf("fonte" to sourceName),
            )
            try {
                val result = task()
                StreamCenterLogger.logTab(
                    tabName = playbackLogTab,
                    action = "Tentativo fonte riproduzione completato",
                    metadata = mapOf("fonte" to sourceName, "link_trovato" to result),
                    level = if (result || !warnWhenEmpty) {
                        StreamCenterLogger.Level.INFO
                    } else {
                        StreamCenterLogger.Level.WARNING
                    },
                )
                result
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                StreamCenterLogger.logTabError(
                    tabName = playbackLogTab,
                    action = "Tentativo fonte riproduzione non riuscito",
                    throwable = error,
                    metadata = mapOf("fonte" to sourceName),
                )
                throw error
            }
        }
        fun addTask(sourceKey: String, task: suspend () -> Boolean) {
            val sourceName = StreamCenterPlugin.streamingSources
                .firstOrNull { source -> source.key == sourceKey }
                ?.title
                ?: sourceKey
            tasksBySource.getOrPut(sourceKey) { mutableListOf() } +=
                loggedSourceTask(sourceName = sourceName, task = task)
        }

        playbackData?.animeUnity
            ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) }
            ?.let { animeUnityPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) {
                    loadAnimeUnityLinks(
                        playbackData = animeUnityPlayback,
                        subtitleCallback = uniqueSubtitleCallback,
                        callback = uniqueCallback,
                    )
                }
            }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) {
            playbackData?.animeWorld.orEmpty().forEach { animeWorldPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD) {
                    loadAnimeWorldLink(
                        playbackData = animeWorldPlayback,
                        subtitleCallback = uniqueSubtitleCallback,
                        callback = uniqueCallback,
                    )
                }
            }
        }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) {
            playbackData?.animeSaturn.orEmpty().forEach { animeSaturnPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN) {
                    loadAnimeSaturnLink(
                        playbackData = animeSaturnPlayback,
                        subtitleCallback = uniqueSubtitleCallback,
                        callback = uniqueCallback,
                    )
                }
            }
        }

        playbackData?.streamingCommunity?.let { streamingCommunityPlayback ->
            val fallbackTmdbId = playbackData.tmdbUrl
                ?.let(::extractTmdbId)
                ?.toIntOrNull()
            if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_VIXCLOUD)) {
                addTask(StreamCenterPlugin.PREF_SOURCE_VIXCLOUD) {
                    loadVixCloudLinks(
                        playbackData = streamingCommunityPlayback,
                        subtitleCallback = uniqueSubtitleCallback,
                        callback = uniqueCallback,
                    )
                }
            }
            if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_VIXSRC)) {
                addTask(StreamCenterPlugin.PREF_SOURCE_VIXSRC) {
                    loadVixSrcLinks(
                        playbackData = streamingCommunityPlayback,
                        fallbackTmdbId = fallbackTmdbId,
                        subtitleCallback = uniqueSubtitleCallback,
                        callback = uniqueCallback,
                    )
                }
            }
            if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_VIDXGO)) {
                addTask(StreamCenterPlugin.PREF_SOURCE_VIDXGO) {
                    loadVidxGoLinks(
                        playbackData = streamingCommunityPlayback,
                        subtitleCallback = uniqueSubtitleCallback,
                        callback = uniqueCallback,
                    )
                }
            }
        }

        if (StreamCenterPlugin.isTorrentEnabled(sharedPref) && !isCasting) {
            playbackData?.torrent?.let { torrentContext ->
                val torrentFilters = StreamCenterTorrentPreferences.read(sharedPref)
                val domains = StreamCenterTorrentPreferences.enabledDomains(sharedPref)
                torrentTasks["torrent-ext"] = loggedSourceTask(
                    sourceName = "EXT",
                    warnWhenEmpty = false,
                ) {
                    StreamCenterTorrentResolver.load(
                        domains = domains,
                        context = torrentContext,
                        filters = torrentFilters,
                        callback = uniqueCallback,
                        performanceMode = performanceMode,
                        logTabName = playbackLogTab,
                    )
                }
            }
        }

        playbackData?.stremio?.let { stremioContext ->
            val catalogAddon = stremioContext.catalogAddonKey?.let { addonKey ->
                catalogDefinition?.stremioAddon?.takeIf { addon -> addon.key == addonKey }
                    ?: StreamCenterCatalogs.stremioCatalogAddon(sharedPref, addonKey)
            }
            (listOfNotNull(catalogAddon) + activeStremioResolverAddons())
                .distinctBy(StreamCenterStremioAddon::key)
                .forEach { addon ->
                    stremioTasks[addon.key] = suspend {
                        StreamCenterLogger.logTab(
                            tabName = playbackLogTab,
                            action = "Tentativo add-on Stremio avviato",
                            metadata = mapOf("add_on" to addon.name, "modalita_prestazioni" to performanceMode),
                        )
                        try {
                            val result = withTimeoutOrNull(STREMIO_ADDON_TIMEOUT_MS) {
                                StreamCenterStremioAddonClient.load(
                                    addon = addon,
                                    context = stremioContext,
                                    subtitleCallback = uniqueSubtitleCallback,
                                    callback = uniqueCallback,
                                    stopAfterFirstResult = performanceMode,
                                )
                            } ?: false
                            StreamCenterLogger.logTab(
                                tabName = playbackLogTab,
                                action = "Tentativo add-on Stremio completato",
                                metadata = mapOf("add_on" to addon.name, "link_trovato" to result),
                                level = if (result) StreamCenterLogger.Level.INFO else StreamCenterLogger.Level.WARNING,
                            )
                            result
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            StreamCenterLogger.logTabError(
                                tabName = playbackLogTab,
                                action = "Tentativo add-on Stremio non riuscito",
                                throwable = error,
                                metadata = mapOf("add_on" to addon.name),
                            )
                            throw error
                        }
                    }
                }
        }

        val priorityOrder = StreamCenterPlugin.getSourcePriorityOrder(sharedPref)
        val orderedKeys = tasksBySource.keys.sortedBy { key ->
            priorityOrder.indexOf(key).takeIf { it >= 0 } ?: priorityOrder.size
        }
        StreamCenterLogger.logTab(
            tabName = playbackLogTab,
            action = "Piano risoluzione fonti preparato",
            metadata = mapOf(
                "fonti_native" to orderedKeys.size,
                "fonti_torrent" to torrentTasks.size,
                "add_on_stremio" to stremioTasks.size,
                "ordine_priorita" to priorityOrder.joinToString(", "),
                "timeout_fonte_ms" to sourceGroupTimeoutMs,
                "timeout_torrent_ms" to TORRENT_SOURCE_TIMEOUT_MS,
                "timeout_torrent_totale_ms" to TORRENT_TOTAL_TIMEOUT_MS,
            ),
        )
        if (performanceMode) {
            val performanceKeys = (tasksBySource.keys + torrentTasks.keys + stremioTasks.keys)
                .distinct()
                .sortedBy { key ->
                    priorityOrder.indexOf(key).takeIf { it >= 0 } ?: priorityOrder.size
                }
            var torrentDeadlineNanos: Long? = null
            for (sourceKey in performanceKeys) {
                when {
                    stremioTasks[sourceKey] != null -> stremioTasks.getValue(sourceKey).invoke()
                    torrentTasks[sourceKey] != null -> {
                        val deadline = torrentDeadlineNanos
                            ?: (
                                System.nanoTime() +
                                    TORRENT_PERFORMANCE_TOTAL_TIMEOUT_MS * 1_000_000L
                                ).also { torrentDeadlineNanos = it }
                        val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L)
                            .coerceAtLeast(0L)
                        if (remainingMs > 0L) {
                            withTimeoutOrNull(
                                minOf(TORRENT_SOURCE_TIMEOUT_MS, remainingMs),
                            ) {
                                torrentTasks.getValue(sourceKey).invoke()
                            } ?: false
                        } else {
                            false
                        }
                    }
                    else -> withTimeoutOrNull(sourceGroupTimeoutMs) {
                        runParallelSourceTasks(tasksBySource[sourceKey].orEmpty())
                    }
                        ?: false
                }
                if (emittedAnyLink.get()) {
                    StreamCenterLogger.logTab(
                        tabName = playbackLogTab,
                        action = "Risoluzione fonti di riproduzione completata",
                        metadata = mapOf(
                            "esito" to "link_trovato",
                            "fonte_risolutiva" to sourceKey,
                            "link_univoci_emessi" to emittedLinkKeys.size,
                            "sottotitoli_univoci_emessi" to emittedSubtitleKeys.size,
                        ),
                    )
                    return true
                }
            }
            val result = emittedAnyLink.get()
            StreamCenterLogger.logTab(
                tabName = playbackLogTab,
                action = "Risoluzione fonti di riproduzione completata",
                metadata = mapOf(
                    "esito" to if (result) "link_trovato" else "nessun_link",
                    "link_univoci_emessi" to emittedLinkKeys.size,
                    "sottotitoli_univoci_emessi" to emittedSubtitleKeys.size,
                ),
                level = if (result) StreamCenterLogger.Level.INFO else StreamCenterLogger.Level.WARNING,
            )
            return result
        }
        val result = supervisorScope {
            val stremioDeferred = stremioTasks.values.toList().takeIf { it.isNotEmpty() }?.let { tasks ->
                async(Dispatchers.IO) {
                    runParallelSourceTasks(tasks, STREMIO_ADDON_CONCURRENCY)
                }
            }
            val torrentDeferred = torrentTasks.values.toList().takeIf { it.isNotEmpty() }?.let { tasks ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(TORRENT_TOTAL_TIMEOUT_MS) {
                        runParallelSourceTasks(tasks)
                    } ?: false
                }
            }
            val nativeDeferred = orderedKeys.takeIf { it.isNotEmpty() }?.let { sourceKeys ->
                async(Dispatchers.IO) {
                    sourceKeys.fold(false) { anySourceLoaded, sourceKey ->
                        val sourceLoaded = withTimeoutOrNull(sourceGroupTimeoutMs) {
                            runParallelSourceTasks(tasksBySource[sourceKey].orEmpty())
                        } ?: false
                        anySourceLoaded || sourceLoaded
                    }
                }
            }
            nativeDeferred?.await()
            torrentDeferred?.await()
            stremioDeferred?.await()
            emittedAnyLink.get()
        }
        StreamCenterLogger.logTab(
            tabName = playbackLogTab,
            action = "Risoluzione fonti di riproduzione completata",
            metadata = mapOf(
                "esito" to if (result) "link_trovato" else "nessun_link",
                "link_univoci_emessi" to emittedLinkKeys.size,
                "sottotitoli_univoci_emessi" to emittedSubtitleKeys.size,
            ),
            level = if (result) StreamCenterLogger.Level.INFO else StreamCenterLogger.Level.WARNING,
        )
        return result
    }

    private fun sourceLinkDedupKey(link: ExtractorLink): String {
        val normalizedUrl = link.url.trim().substringBefore('#')
        if (normalizedUrl.startsWith("magnet:?", ignoreCase = true)) {
            val infoHash = MAGNET_INFO_HASH_REGEX.find(normalizedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.uppercase(Locale.ROOT)
            if (infoHash != null) {
                val fileIndex = MAGNET_FILE_INDEX_REGEX.find(normalizedUrl)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
                return "magnet|$infoHash|$fileIndex"
            }
        }
        return buildString {
            append(normalizedUrl)
            append('|')
            append(link.referer)
            append('|')
            link.headers.entries
                .sortedBy { entry -> entry.key.lowercase(Locale.ROOT) }
                .forEach { entry ->
                    append(entry.key.lowercase(Locale.ROOT))
                    append('=')
                    append(entry.value)
                    append(';')
                }
        }
    }

    private suspend fun runParallelSourceTasks(
        tasks: List<suspend () -> Boolean>,
        maxConcurrency: Int = tasks.size.coerceAtLeast(1),
    ): Boolean {
        if (tasks.isEmpty()) return false
        return supervisorScope {
            val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
            tasks.map { task ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            task()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }
            }.awaitAll().any { it }
        }
    }

    private fun isSourceEnabled(prefKey: String): Boolean {
        return StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, prefKey)
    }

    private fun ensureUpdatedSourceDomain(prefKey: String) {
        if (!StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref)) return
        synchronized(checkedSourceDomains) {
            if (!checkedSourceDomains.add(prefKey)) return
        }
        backgroundScope.launch {
            val result = runCatching {
                val baseUrl = StreamCenterPlugin.getSourceBaseUrl(sharedPref, prefKey)
                if (baseUrl.isBlank()) return@runCatching false
                val response = app.get(baseUrl, headers = headers, timeout = 15L)
                val finalUrl = response.url
                val newHost = hostOf(finalUrl)
                val moved = response.code in 200..299 &&
                    finalUrl.startsWith("http") &&
                    newHost.isNotBlank() &&
                    !newHost.equals(hostOf(baseUrl), ignoreCase = true)
                if (moved) {
                    val scheme = finalUrl.substringBefore("://")
                    StreamCenterPlugin.setSourceBaseUrl(sharedPref, prefKey, "$scheme://$newHost")
                }
                moved
            }
            when {
                result.isFailure -> synchronized(checkedSourceDomains) { checkedSourceDomains.remove(prefKey) }
                result.getOrDefault(false) -> resetSourceSession(prefKey)
            }
        }
    }

    private fun resetSourceSession(prefKey: String) {
        when (prefKey) {
            StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY -> {
                streamingCommunityClient.resetSession()
            }
            StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY -> {
                    animeUnitySourceClient.resetSession()
            }
        }
    }

    private fun buildMetadata(
        doc: Document,
        actualUrl: String,
        minimalMetadata: Boolean = performanceMode,
    ): StreamCenterMetadata {
        if (minimalMetadata) {
            return StreamCenterMetadata(
                title = getLocalizedTitle(doc).ifBlank { "Sconosciuto" },
                originalTitle = null,
                plot = null,
                poster = null,
                background = null,
                logo = null,
                tags = emptyList(),
                year = null,
                tmdbId = extractTmdbId(actualUrl),
                score = null,
                people = emptyList(),
                contentRating = null,
                showStatus = null,
                comingSoon = false,
                duration = null,
                trailerUrl = null,
            )
        }
        val title = getLocalizedTitle(doc).ifBlank { "Sconosciuto" }
        val originalTitle = extractAnyFact(doc, "Titolo originale", "Original Title", "Original Name")
        val status = extractAnyFact(doc, "Stato", "Status")
        val originalLanguage = extractAnyFact(doc, "Lingua Originale", "Original Language")
        val type = extractAnyFact(doc, "Tipo", "Type")
        val budget = extractAnyFact(doc, "Budget")
        val revenue = extractAnyFact(doc, "Incasso", "Revenue")
        val genres = doc.select("span.genres a").mapNotNull { cleanText(it.text()) }
        val factTags = buildFactTags(title, originalTitle, status, originalLanguage, type, budget, revenue)
        val keywords = extractKeywords(doc)
        val images = doc.select("meta[property=og:image]").mapNotNull { cleanText(it.attr("content")) }
        val score = extractTmdbScore(doc)

        return StreamCenterMetadata(
            title = title,
            originalTitle = originalTitle,
            plot = cleanText(doc.selectFirst("meta[property=og:description]")?.attr("content")),
            poster = images.firstOrNull(),
            background = images.getOrNull(1),
            logo = extractTmdbLogo(doc),
            tags = (genres + factTags + keywords).distinctBy { it.lowercase(Locale.ROOT) },
            year = parseYear(doc),
            tmdbId = extractTmdbId(actualUrl),
            score = score,
            people = (parseActors(doc) + parseCrew(doc)).distinct(),
            contentRating = extractContentRating(doc),
            showStatus = mapShowStatus(status),
            comingSoon = isComingSoon(status),
            duration = parseRuntime(doc.selectFirst("span.runtime")?.text()),
            trailerUrl = extractTrailerUrl(doc),
        )
    }

    private suspend fun getTmdbDocument(
        url: String,
        page: Int? = null,
    ): Document {
        val requestUrl = stripStreamCenterParams(normalizeTmdbUrl(url, page))
        val html = fetchText {
            app.get(
                requestUrl,
                headers = headers,
            ).text
        }
        return Jsoup.parse(html, requestUrl)
    }

    private suspend fun fetchText(fetch: suspend () -> String): String = fetch()

    private fun extractTmdbImdbId(document: Document): String? {
        val externalLink = document.selectFirst("a[href*='imdb.com/title/tt']")
            ?.attr("href")
            ?.let { href -> IMDB_ID_REGEX.find(href)?.value }
        if (externalLink != null) return externalLink
        return TMDB_IMDB_JSON_REGEX.find(document.html())?.groupValues?.getOrNull(1)
    }

    private fun extractTmdbLogo(document: Document): String? {
        return document.selectFirst(
            "section.header.poster img.logo, section.header [class*=logo] img, " +
                "section[class*=header] img[class*=logo]",
        )?.extractImageUrl()
    }

    private fun normalizeTmdbUrl(url: String, page: Int? = null): String {
        val absoluteUrl = if (url.startsWith("http")) {
            url
        } else {
            mainUrl + if (url.startsWith("/")) url else "/$url"
        }

        val params = buildMap {
            put("language", tmdbLanguage)
            page?.let { put("page", it.toString()) }
        }

        val existingQuery = absoluteUrl.substringAfter("?", "")
        val paramsToAppend = params
            .filterKeys { "$it=" !in existingQuery }
            .map { "${it.key}=${it.value}" }

        if (paramsToAppend.isEmpty()) return absoluteUrl

        val separator = if ("?" in absoluteUrl) "&" else "?"
        return absoluteUrl + separator + paramsToAppend.joinToString("&")
    }

    private fun stripStreamCenterParams(url: String): String {
        val baseUrl = url.substringBefore("?")
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return url

        val params = query.split("&").filterNot { parameter -> parameter.startsWith("streamcenter_") }
        return if (params.isEmpty()) baseUrl else "$baseUrl?${params.joinToString("&")}"
    }

    private fun streamCenterUrlParameter(url: String, name: String): String? {
        val encodedValue = url.substringAfter("?", "")
            .split("&")
            .firstOrNull { parameter -> parameter.substringBefore("=") == "streamcenter_$name" }
            ?.substringAfter("=", "")
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching { URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.let(::cleanText)
    }

    private fun Int.toDecimalScore(): String {
        return String.format(Locale.US, "%.1f", this / 10.0)
    }

    private fun extractTmdbScore(element: Element): String? {
        extractTmdbScorePercent(element)?.let { return it }
        return extractTmdbJsonLdScore(element)
    }

    private fun extractTmdbScorePercent(element: Element): String? {
        return element.selectFirst(".user_score_chart[data-percent]")
            ?.attr("data-percent")
            ?.toIntOrNull()
            ?.toDecimalScore()
    }

    private fun extractTmdbJsonLdScore(element: Element): String? {
        return element.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { script ->
                runCatching {
                    JSONObject(script.data().trim())
                        .optJSONObject("aggregateRating")
                        ?.opt("ratingValue")
                        ?.toString()
                        ?.toDecimalScore()
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun String.toDecimalScore(): String? {
        val value = replace(',', '.').toDoubleOrNull() ?: return null
        val normalized = if (value > 10.0) value / 10.0 else value
        return String.format(Locale.US, "%.1f", normalized)
    }

    private fun isAnilistOnlyUrl(url: String): Boolean {
        return url.contains(anilistOnlyPath) || Regex("""anilist\.co/anime/\d+""").containsMatchIn(url)
    }

    private fun extractAnilistIdFromText(text: String): Int? {
        return Regex("""(?:anilist\.co/anime/|/anilist/)(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun markMalOnlyUrl(malId: Int): String {
        val params = listOf(animeMarker, "$animeMalParam=$malId")
        return "https://myanimelist.net/anime/$malId?${params.joinToString("&")}"
    }

    private fun isMalOnlyUrl(url: String): Boolean {
        return url.contains(malOnlyPath) || Regex("""myanimelist\.net/anime/\d+""").containsMatchIn(url)
    }

    private fun extractMalIdFromText(text: String): Int? {
        return Regex("""(?:myanimelist\.net/anime/|/mal/)(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun buildAnilistEpisodes(metadata: List<AnilistEpisodeMetadata>): List<Episode> {
        return metadata.map { item ->
            newEpisode("") {
                this.name = item.title
                this.season = 1
                this.episode = item.number
                this.posterUrl = item.posterUrl
            }
        }
    }

    private suspend fun loadAnilistMedia(anilistId: Int?, malId: Int?): LoadResponse {
        val metadata = aniListMetadataClient.fetchMetadata(anilistId, malId)
            ?: error("Metadati AniList non trovati")
        StreamCenterLogger.logMetadata(
            tabName = metadata.title,
            source = "AniList",
            action = "Metadati AniList acquisiti",
            metadata = mapOf(
                "id_anilist_richiesto" to anilistId,
                "id_mal_richiesto" to malId,
                "id_anilist" to metadata.anilistId,
                "id_mal" to metadata.malId,
                "formato" to metadata.format,
                "episodi_dichiarati" to metadata.episodes,
                "modalita_prestazioni" to performanceMode,
                "titoli_alternativi" to metadata.titleCandidates.size,
                "personaggi" to metadata.characters.size,
                "raccomandazioni_anilist" to metadata.recommendations.size,
                "episodi_anilist" to metadata.episodeMetadata.size,
            ),
        )
        val anilistEpisodes = buildAnilistEpisodes(metadata.episodeMetadata)
        val resolvedAnilistId = metadata.anilistId
        val resolvedMalId = metadata.malId
        val isMovie = metadata.format.equals("MOVIE", ignoreCase = true)
        val shouldResolveJapaneseTitle = shouldResolveTorrentJapaneseTitle()
        val shouldResolveKitsu = !performanceMode ||
            activeStremioResolversNeedKitsuId() ||
            trackingServiceIsConnected(SyncIdName.Kitsu)
        val resolvedKitsuId = if (!shouldResolveKitsu) {
            null
        } else if (performanceMode) {
            withTimeoutOrNull(STREMIO_KITSU_RESOLUTION_TIMEOUT_MS) {
                kitsuMetadataClient.resolveAnimeId(resolvedMalId, resolvedAnilistId)
            }
        } else {
            kitsuMetadataClient.resolveAnimeId(resolvedMalId, resolvedAnilistId)
        }
        StreamCenterLogger.logMetadata(
            tabName = metadata.title,
            source = "Kitsu",
            action = "Identificativo Kitsu risolto",
            metadata = mapOf(
                "risoluzione_richiesta" to shouldResolveKitsu,
                "id_anilist" to resolvedAnilistId,
                "id_mal" to resolvedMalId,
                "id_kitsu" to resolvedKitsuId,
                "timeout_modalita_prestazioni" to (performanceMode && shouldResolveKitsu),
            ),
            level = if (shouldResolveKitsu && resolvedKitsuId == null) {
                StreamCenterLogger.Level.WARNING
            } else {
                StreamCenterLogger.Level.INFO
            },
        )
        val sourceSyncIds = listOf(
            AnimeSyncIds(
                anilistId = resolvedAnilistId,
                malId = resolvedMalId,
                kitsuId = resolvedKitsuId,
            )
        )
        val streamCenterMetadata = metadata.toStreamCenterMetadata()
        val matchMetadata = AnilistMetadata(
            title = metadata.title,
            titleCandidates = metadata.titleCandidates,
        )
        val animeTitlePreference = StreamCenterPlugin.getAnimeCardTitle(sharedPref)
        val includeAniZip = shouldResolveAnimeTorrentMetadata() ||
            (!performanceMode &&
                (!isMovie || animeTitlePreference == StreamCenterPlugin.ANIME_CARD_TITLE_ANIZIP))
        val (resolvedSources, kitsuContentRating, resolvedSimklId) = coroutineScope {
            val sourcesDeferred = async(Dispatchers.IO) {
                resolveAnimePlaybackSources(
                    metadata = streamCenterMetadata,
                    matchMetadata = matchMetadata,
                    syncIds = sourceSyncIds,
                    aniZipIds = resolvedAnilistId to resolvedMalId,
                    includeAniZip = includeAniZip,
                    ignoreSourceFailures = true,
                )
            }
            val contentRatingDeferred = if (performanceMode) {
                null
            } else {
                async(Dispatchers.IO) {
                    runCatching {
                        kitsuMetadataClient.fetchContentRating(resolvedKitsuId)
                    }.getOrNull()
                }
            }
            val simklIdDeferred = async(Dispatchers.IO) {
                resolveSimklId(
                    mal = resolvedMalId,
                    anilist = resolvedAnilistId,
                    allowedCategories = setOf("anime"),
                )
            }
            Triple(
                sourcesDeferred.await(),
                contentRatingDeferred?.await(),
                simklIdDeferred.await(),
            )
        }
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val aniZipCatalog = resolvedSources.aniZipCatalog
        val recommendations = buildBaseCatalogAnimeUnityRecommendations(
            sources = animeUnitySources,
            anilistId = resolvedAnilistId,
            malId = resolvedMalId,
        )
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
            anilistId = resolvedAnilistId,
            malId = resolvedMalId,
            kitsuId = sourceSyncIds.firstOrNull()?.kitsuId,
        )
        val cardTitle = when (animeTitlePreference) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY -> animeUnitySources
                .firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) }
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> metadata.titleRomaji
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> metadata.titleEnglish
                ?: aniZipMetadataClient.localizedText(aniZipCatalog.titles, "en")
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> metadata.titleNative
            else -> aniZipMetadataClient.localizedText(aniZipCatalog.titles, "it")
                ?: metadata.titleEnglish
                ?: metadata.titleRomaji
        }?.trim()?.takeIf(String::isNotBlank) ?: metadata.title
        val torrentContext = animeTorrentPlaybackContext(
            titles = listOf(
                cardTitle,
                metadata.title,
                metadata.titleRomaji,
                metadata.titleEnglish,
                metadata.titleNative,
                metadata.originalTitle,
            ) + metadata.titleCandidates + aniZipCatalog.titles.values,
            year = metadata.year,
            isMovie = isMovie,
            tabName = cardTitle,
            aniZipCatalog = aniZipCatalog,
            anilistId = resolvedAnilistId,
            malId = resolvedMalId,
            kitsuId = resolvedKitsuId,
            knownAniListTitle = metadata.titleNative,
        )
        StreamCenterLogger.logTab(
            tabName = cardTitle,
            action = "Risoluzione fonti anime completata",
            metadata = mapOf(
                "preferenza_titolo" to animeTitlePreference,
                "titoli_animeunity" to animeUnitySources.size,
                "episodi_animeunity" to animeUnitySources.sumOf { it.episodeNumbers().size },
                "raccomandazioni_animeunity" to recommendations.size,
                "titoli_animeworld" to animeWorldSources.size,
                "episodi_animeworld" to animeWorldSources.sumOf { it.episodeNumbers().size },
                "titoli_animesaturn" to animeSaturnSources.size,
                "episodi_animesaturn" to animeSaturnSources.sumOf { it.episodeNumbers().size },
                "episodi_anizip" to aniZipCatalog.episodes.size,
                "titoli_anizip" to aniZipCatalog.titles.size,
                "id_kitsu" to resolvedKitsuId,
                "id_simkl" to resolvedSimklId,
            ),
        )
        StreamCenterLogger.logMetadata(
            tabName = cardTitle,
            source = "AniZip",
            action = "Catalogo episodi AniZip valutato",
            metadata = mapOf(
                "richiesto" to includeAniZip,
                "richiesto_per_torrent_ext" to shouldResolveJapaneseTitle,
                "episodi" to aniZipCatalog.episodes.size,
                "titoli_localizzati" to aniZipCatalog.titles.size,
                "id_anilist" to aniZipCatalog.anilistId,
                "id_mal" to aniZipCatalog.malId,
                "id_kitsu" to aniZipCatalog.kitsuId,
                "id_tmdb" to aniZipCatalog.tmdbId,
            ),
            level = if (aniZipCatalog.episodes.isEmpty() && aniZipCatalog.titles.isEmpty()) {
                StreamCenterLogger.Level.WARNING
            } else {
                StreamCenterLogger.Level.INFO
            },
        )
        val sourceUrl = markAnilistUrl(resolvedAnilistId, resolvedMalId)
        val resolvedPlot = aniZipCatalog.description
            ?: animeUnitySources.firstNotNullOfOrNull { it.plot?.takeIf(String::isNotBlank) }
            ?: metadata.description
        val episodeFallbackPoster = animeUnitySources.firstNotNullOfOrNull { it.posterUrl }
            ?: metadata.poster
        val tags = (
            if (performanceMode) {
                listOf("Anime") + metadata.genres
            } else {
                listOfNotNull("Anime", aniListMetadataClient.formatLabel(metadata.format)) +
                    metadata.genres +
                    metadata.studios.map { "Studio: $it" } +
                    listOfNotNull(
                        aniListMetadataClient.seasonLabel(metadata.season, metadata.year),
                        aniListMetadataClient.sourceLabel(metadata.source),
                    )
            }
            ).distinctBy { it.lowercase(Locale.ROOT) }
        val contentRating = kitsuContentRating ?: if (metadata.isAdult) "18+" else null

        val response = if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
                torrent = torrentContext,
            )
            StreamCenterLogger.logTab(
                tabName = cardTitle,
                action = "Dettagli film anime aggregati",
                metadata = mapOf(
                    "fonte_metadati_principale" to "AniList",
                    "fonte_trama" to when {
                        !aniZipCatalog.description.isNullOrBlank() -> "AniZip"
                        animeUnitySources.any { !it.plot.isNullOrBlank() } -> "AnimeUnity"
                        else -> "AniList"
                    },
                    "fonti_riproduzione_disponibili" to listOf(
                        "AnimeUnity" to animeUnitySources.isNotEmpty(),
                        "AnimeWorld" to animeWorldSources.isNotEmpty(),
                        "AnimeSaturn" to animeSaturnSources.isNotEmpty(),
                    ).filter { it.second }.joinToString(", ") { it.first },
                    "contesto_stremio_preparato" to true,
                    "raccomandazioni" to recommendations.size,
                ),
            )
            newMovieLoadResponse(
                cardTitle,
                sourceUrl,
                TvType.AnimeMovie,
                dataUrl = playbackData.toJson(),
            ) {
                if (!performanceMode) {
                    this.posterUrl = metadata.poster
                    this.backgroundPosterUrl = metadata.background
                    this.plot = resolvedPlot
                    this.tags = tags
                    this.year = metadata.year
                    this.duration = metadata.duration
                    this.contentRating = contentRating
                    this.actors = metadata.characters
                    this.recommendations = recommendations
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        anilist = resolvedAnilistId,
                        mal = resolvedMalId,
                        kitsu = resolvedKitsuId,
                        simkl = resolvedSimklId,
                        imdb = aniZipCatalog.imdbId,
                    ),
                    showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                )
                if (!performanceMode) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(metadata.score)
                }
            }
        } else {
            val episodeMetadata = if (performanceMode) {
                anilistEpisodes
            } else {
                animeEpisodeMetadataMerger.merge(
                    malId = resolvedMalId,
                    kitsuId = sourceSyncIds.firstNotNullOfOrNull { it.kitsuId },
                    anilistEpisodes = anilistEpisodes,
                    aniZipCatalog = aniZipCatalog,
                    tmdbEpisodes = {
                        withTimeoutOrNull(TMDB_ANIME_EPISODE_METADATA_TIMEOUT_MS) {
                            tmdbAnimeEpisodeMetadataClient.fetch(
                                anilistId = resolvedAnilistId,
                                aniZipCatalog = aniZipCatalog,
                            )
                        }.orEmpty()
                    },
                    targetEpisodeCount = listOfNotNull(
                        metadata.episodes,
                        maxAnimeSourceEpisodeNumber(
                            animeUnitySources = animeUnitySources,
                            animeWorldSources = animeWorldSources,
                            animeSaturnSources = animeSaturnSources,
                        ),
                    ).maxOrNull(),
                    tabName = cardTitle,
                    episodeFactory = { initializer -> newEpisode("", initializer) },
                )
            }
            val episodes = buildAnimeSourceEpisodes(
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                episodeMetadata = episodeMetadata,
                fallbackPoster = episodeFallbackPoster.takeIf { !performanceMode },
                stremioContext = stremioContext,
                torrentContext = torrentContext,
            ).ifEmpty {
                buildAnimeFallbackEpisodes(
                    metadata.episodes,
                    episodeMetadata,
                    episodeFallbackPoster.takeIf { !performanceMode },
                    stremioContext,
                    torrentContext,
                )
            }
            StreamCenterLogger.logTab(
                tabName = cardTitle,
                action = "Episodi anime aggregati",
                metadata = mapOf(
                    "fonte_metadati_principale" to if (performanceMode) "AniList" else "Merger episodi",
                    "episodi_metadata" to episodeMetadata.size,
                    "episodi_finali" to episodes.size,
                    "episodi_anilist" to anilistEpisodes.size,
                    "episodi_anizip" to aniZipCatalog.episodes.size,
                    "fallback_episodi_usato" to (episodes.size != episodeMetadata.size),
                    "fonti_streaming_trovate" to listOf(
                        "AnimeUnity" to animeUnitySources.isNotEmpty(),
                        "AnimeWorld" to animeWorldSources.isNotEmpty(),
                        "AnimeSaturn" to animeSaturnSources.isNotEmpty(),
                    ).filter { it.second }.joinToString(", ") { it.first },
                ),
            )
            val animeType = when (metadata.format?.uppercase(Locale.ROOT)) {
                "OVA", "ONA", "SPECIAL" -> TvType.OVA
                else -> TvType.Anime
            }
            newAnimeLoadResponse(
                cardTitle,
                sourceUrl,
                animeType,
            ) {
                applyAnimeCatalogTitles(
                    englishTitle = metadata.titleEnglish,
                    nativeTitle = metadata.titleNative,
                    alternativeTitles = metadata.titleCandidates,
                )
                if (!performanceMode) {
                    this.posterUrl = metadata.poster
                    this.backgroundPosterUrl = metadata.background
                    this.plot = resolvedPlot
                    this.tags = tags
                    this.year = metadata.year
                    this.duration = metadata.duration
                    this.contentRating = contentRating
                    this.actors = metadata.characters
                    this.recommendations = recommendations
                    this.showStatus = aniListMetadataClient.showStatus(metadata.status)
                    this.comingSoon = metadata.status.equals("NOT_YET_RELEASED", ignoreCase = true)
                }
                if (!performanceMode && metadata.nextAiringEpisode != null && metadata.nextAiringAtSeconds != null) {
                    this.nextAiring = NextAiring(
                        episode = metadata.nextAiringEpisode,
                        unixTime = metadata.nextAiringAtSeconds,
                    )
                }
                addEpisodes(DubStatus.Subbed, episodes)
                if (
                    animeUnitySources.isEmpty() &&
                    animeWorldSources.isEmpty() &&
                    animeSaturnSources.isEmpty()
                ) {
                    addSeasonNames(buildAnimeSeasonData(episodes))
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        anilist = resolvedAnilistId,
                        mal = resolvedMalId,
                        kitsu = resolvedKitsuId,
                        simkl = resolvedSimklId,
                        imdb = aniZipCatalog.imdbId,
                    ),
                    showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                )
                if (!performanceMode) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(metadata.score)
                }
            }
        }
        val cardTitleSource = when (animeTitlePreference) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY ->
                if (animeUnitySources.any { !it.title.isNullOrBlank() }) "AnimeUnity" else "AniList"
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> "AniList"
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH ->
                if (!metadata.titleEnglish.isNullOrBlank()) "AniList" else "AniZip"
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> "AniList"
            else -> if (
                !aniZipMetadataClient.localizedText(aniZipCatalog.titles, "it").isNullOrBlank()
            ) {
                "AniZip"
            } else {
                "AniList"
            }
        }
        val plotSource = when {
            !aniZipCatalog.description.isNullOrBlank() -> "AniZip"
            animeUnitySources.any { !it.plot.isNullOrBlank() } -> "AnimeUnity"
            else -> "AniList"
        }
        val playbackSources = buildList {
            if (animeUnitySources.isNotEmpty()) add("AnimeUnity")
            if (animeWorldSources.isNotEmpty()) add("AnimeWorld")
            if (animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
            add("Add-on Stremio abilitati")
            addAll(torrentPlaybackProvenance(torrentContext))
            add("StreamCenter (payload di riproduzione)")
        }
        val episodeMetadataSources = listOf("TMDB", "AniZip", "Kitsu", "AniList", "Jikan")
        return response.withCardProvenance(
            defaultSource = "AniList",
            fieldSources = mapOf(
                "titolo" to listOf(cardTitleSource),
                "titolo_inglese" to listOf("AniList"),
                "titolo_originale" to listOf("AniList"),
                "titoli_alternativi" to listOf("AniList", "AniZip"),
                "poster" to listOf("AniList"),
                "sfondo" to listOf("AniList"),
                "trama" to listOf(plotSource),
                "tag" to listOf("AniList"),
                "anno" to listOf("AniList"),
                "punteggio" to listOf("AniList"),
                "durata_minuti" to listOf("AniList"),
                "classificazione_contenuti" to listOf(
                    if (!kitsuContentRating.isNullOrBlank()) "Kitsu" else "AniList",
                ),
                "cast" to listOf("AniList"),
                "raccomandazioni" to listOf("AniList"),
                "trailer" to listOf("AniList"),
                "stato_trasmissione" to listOf("AniList"),
                "prossimo_episodio" to listOf("AniList"),
                "in_arrivo" to listOf("AniList"),
                "id_sincronizzazione" to listOf(
                    "AniList",
                    "MyAnimeList",
                    "Kitsu",
                    "Simkl",
                ),
                "stagioni" to listOf(
                    "Fonti di riproduzione anime",
                    "StreamCenter (normalizzazione stagioni)",
                ),
                "episodi" to episodeMetadataSources + playbackSources,
                "episodi.nome" to listOf(
                    "TMDB",
                    "AniZip",
                    "Kitsu",
                    "AniList",
                    "Jikan",
                    "StreamCenter (fallback nome)",
                ),
                "episodi.poster" to listOf(
                    "Kitsu",
                    "AniZip",
                    "AniList",
                    "AnimeUnity (fallback scheda)",
                ),
                "episodi.descrizione" to listOf("TMDB", "AniZip", "Kitsu"),
                "episodi.punteggio" to listOf("Jikan", "AniZip"),
                "episodi.durata_minuti" to listOf("Kitsu", "AniZip"),
                "episodi.data" to listOf("AniZip", "Kitsu", "Jikan"),
                "episodi.stagione" to listOf(
                    "Aggregatore metadati episodi",
                    "StreamCenter (normalizzazione)",
                ),
                "episodi.episodio" to listOf(
                    "Fonti di riproduzione anime",
                    "Aggregatore metadati episodi",
                ),
                "episodi.dati_riproduzione" to playbackSources,
                "dati_riproduzione" to playbackSources,
            ),
            fieldNotes = mapOf(
                "trama" to "Fallback applicato nell'ordine AniZip → AnimeUnity → AniList.",
                "classificazione_contenuti" to "Kitsu ha precedenza; AniList fornisce il fallback 18+.",
                "episodi.nome" to "Fallback per campo: TMDB → AniZip → Kitsu → AniList → Jikan.",
                "episodi.poster" to "Fallback per campo: Kitsu → AniZip → AniList → poster della scheda.",
                "episodi.descrizione" to "Fallback per campo: TMDB → AniZip (summary) → Kitsu → AniZip (overview).",
                "episodi.punteggio" to "Fallback per campo: Jikan → AniZip.",
                "episodi.durata_minuti" to "Fallback per campo: Kitsu → AniZip.",
                "episodi.data" to "Fallback per campo: AniZip → Kitsu → Jikan → data fallback AniZip.",
            ),
        )
    }

    private suspend fun loadAniListCatalogMedia(url: String): LoadResponse {
        val requestedId = aniListCatalog.mediaId(url)
            ?: throw IllegalArgumentException("Identificativo AniList non valido")
        val metadata = aniListMetadataClient.fetchMetadata(
            anilistId = requestedId,
            malId = null,
            forceFullMetadata = true,
        ) ?: error("Metadati AniList non trovati")
        val anilistId = metadata.anilistId
        val malId = metadata.malId
        val isMovie = metadata.format.equals("MOVIE", ignoreCase = true)
        val kitsuId = runCatching {
            kitsuMetadataClient.resolveAnimeId(malId, anilistId)
        }.getOrNull()
        val syncIds = listOf(
            AnimeSyncIds(
                anilistId = anilistId,
                malId = malId,
                kitsuId = kitsuId,
            )
        )
        val resolvedSources = resolveAnimePlaybackSources(
            metadata = metadata.toStreamCenterMetadata(),
            matchMetadata = AnilistMetadata(
                title = metadata.title,
                titleCandidates = metadata.titleCandidates,
            ),
            syncIds = syncIds,
            aniZipIds = anilistId to malId,
            includeAniZip = shouldResolveAnimeTorrentMetadata(),
        )
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
            anilistId = anilistId,
            malId = malId,
            kitsuId = kitsuId,
        )
        val title = aniListCatalog.preferredTitle(metadata)
        val torrentContext = animeTorrentPlaybackContext(
            titles = listOf(
                title,
                metadata.title,
                metadata.titleRomaji,
                metadata.titleEnglish,
                metadata.titleNative,
                metadata.originalTitle,
            ) + metadata.titleCandidates,
            year = metadata.year,
            isMovie = isMovie,
            tabName = title,
            aniZipCatalog = resolvedSources.aniZipCatalog,
            anilistId = anilistId,
            malId = malId,
            kitsuId = kitsuId,
            knownAniListTitle = metadata.titleNative,
        )
        val sourceUrl = "https://anilist.co/anime/$anilistId"
        val recommendations = metadata.recommendations.map { recommendation ->
            val type = if (recommendation.format.equals("MOVIE", ignoreCase = true)) {
                TvType.AnimeMovie
            } else {
                when (recommendation.format?.uppercase(Locale.ROOT)) {
                    "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
                    else -> TvType.Anime
                }
            }
            newAnimeSearchResponse(
                recommendation.title,
                "https://anilist.co/anime/${recommendation.anilistId}",
                type,
            ) {
                posterUrl = recommendation.posterUrl
            }
        }
        val formatLabel = metadata.format
            ?.replace('_', ' ')
            ?.lowercase(Locale.ROOT)
            ?.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val tags = (
            listOfNotNull(formatLabel) +
                metadata.genres +
                metadata.tags +
                metadata.studios.map { "Studio: $it" } +
                listOfNotNull(
                    aniListMetadataClient.seasonLabel(metadata.season, metadata.year),
                    aniListMetadataClient.sourceLabel(metadata.source),
                )
            ).distinctBy { it.lowercase(Locale.ROOT) }
        val contentRating = if (metadata.isAdult) "18+" else null

        val response = if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
                torrent = torrentContext,
            )
            newMovieLoadResponse(
                title,
                sourceUrl,
                TvType.AnimeMovie,
                dataUrl = playbackData.toJson(),
            ) {
                apiName = this@StreamCenter.name
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.background
                plot = metadata.description
                this.tags = tags
                year = metadata.year
                duration = metadata.duration
                this.contentRating = contentRating
                actors = metadata.characters
                this.recommendations = recommendations
                comingSoon = metadata.status.equals("NOT_YET_RELEASED", ignoreCase = true)
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        anilist = anilistId,
                        mal = malId,
                        kitsu = kitsuId,
                    ),
                )
                metadata.trailerUrl?.let { addTrailer(it) }
                addScore(metadata.score)
            }
        } else {
            val episodeMetadata = buildAnilistEpisodes(metadata.episodeMetadata)
            val totalEpisodes = metadata.episodes
                ?: metadata.nextAiringEpisode?.minus(1)?.takeIf { it > 0 }
            val episodes = buildCatalogAnimeEpisodes(
                totalEpisodes = totalEpisodes,
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                episodeMetadata = episodeMetadata,
                fallbackPoster = metadata.poster,
                stremioContext = stremioContext,
                torrentContext = torrentContext,
            )
            val animeType = when (metadata.format?.uppercase(Locale.ROOT)) {
                "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
                else -> TvType.Anime
            }
            newAnimeLoadResponse(
                title,
                sourceUrl,
                animeType,
            ) {
                apiName = this@StreamCenter.name
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.background
                plot = metadata.description
                this.tags = tags
                year = metadata.year
                duration = metadata.duration
                this.contentRating = contentRating
                actors = metadata.characters
                this.recommendations = recommendations
                showStatus = aniListMetadataClient.showStatus(metadata.status)
                comingSoon = metadata.status.equals("NOT_YET_RELEASED", ignoreCase = true)
                applyAnimeCatalogTitles(
                    englishTitle = metadata.titleEnglish,
                    nativeTitle = metadata.titleNative,
                    alternativeTitles = metadata.titleCandidates,
                )
                if (metadata.nextAiringEpisode != null && metadata.nextAiringAtSeconds != null) {
                    nextAiring = NextAiring(
                        episode = metadata.nextAiringEpisode,
                        unixTime = metadata.nextAiringAtSeconds,
                    )
                }
                addEpisodes(DubStatus.Subbed, episodes)
                if (
                    animeUnitySources.isEmpty() &&
                    animeWorldSources.isEmpty() &&
                    animeSaturnSources.isEmpty()
                ) {
                    addSeasonNames(buildAnimeSeasonData(episodes))
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        anilist = anilistId,
                        mal = malId,
                        kitsu = kitsuId,
                    ),
                )
                metadata.trailerUrl?.let { addTrailer(it) }
                addScore(metadata.score)
            }
        }
        val playbackSourceNames = buildList {
            if (animeUnitySources.isNotEmpty()) add("AnimeUnity")
            if (animeWorldSources.isNotEmpty()) add("AnimeWorld")
            if (animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
        }
        return response.withCardProvenance(
            defaultSource = "AniList",
            fieldSources = animeProviderCardSources(
                metadataSource = "AniList",
                playbackSources = playbackSourceNames,
                episodeMetadataSources = listOf("AniList"),
                trackingSources = listOf("AniList", "MyAnimeList", "Kitsu"),
                torrentContext = torrentContext,
            ),
        )
    }

    private suspend fun loadMyAnimeListMedia(url: String): LoadResponse {
        val media = myAnimeListCatalog.media(url)
        val isMovie = media.type == TvType.AnimeMovie
        val resolvedKitsuId = runCatching {
            kitsuMetadataClient.resolveAnimeId(media.id, null)
        }.getOrNull()
        val syncIds = listOf(
            AnimeSyncIds(
                anilistId = null,
                malId = media.id,
                kitsuId = resolvedKitsuId,
            )
        )
        val sourceMetadata = StreamCenterMetadata(
            title = media.title,
            originalTitle = media.japaneseTitle,
            plot = media.synopsis,
            poster = media.posterUrl,
            background = null,
            tags = media.genres + media.themes + media.demographics,
            year = media.year,
            tmdbId = null,
            score = media.score,
            people = media.characters,
            contentRating = media.contentRating,
            showStatus = media.status,
            comingSoon = media.comingSoon,
            duration = media.duration,
            trailerUrl = media.trailerUrl,
        )
        val matchMetadata = AnilistMetadata(
            title = media.title,
            titleCandidates = media.titleCandidates,
        )
        val (malEpisodes, resolvedSources) = coroutineScope {
            val episodesDeferred = async(Dispatchers.IO) {
                runCatching { myAnimeListCatalog.episodes(media) }.getOrDefault(emptyList())
            }
            val sourcesDeferred = async(Dispatchers.IO) {
                resolveAnimePlaybackSources(
                    metadata = sourceMetadata,
                    matchMetadata = matchMetadata,
                    syncIds = syncIds,
                    aniZipIds = null to media.id,
                    includeAniZip = shouldResolveAnimeTorrentMetadata(),
                )
            }
            episodesDeferred.await() to sourcesDeferred.await()
        }
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
            malId = media.id,
            kitsuId = resolvedKitsuId,
        )
        val torrentContext = animeTorrentPlaybackContext(
            titles = media.titleCandidates,
            year = media.year,
            isMovie = isMovie,
            tabName = media.title,
            aniZipCatalog = resolvedSources.aniZipCatalog,
            malId = media.id,
            kitsuId = resolvedKitsuId,
            knownMyAnimeListTitle = media.japaneseTitle,
        )
        val recommendations = media.recommendations.map { recommendation ->
            newAnimeSearchResponse(
                recommendation.title,
                recommendation.url,
                recommendation.type,
            ) {
                posterUrl = recommendation.posterUrl
            }
        }
        val tags = buildList {
            media.mediaType?.let(::add)
            addAll(media.genres)
            addAll(media.themes)
            addAll(media.demographics)
            media.studios.forEach { add("Studio: $it") }
            media.producers.forEach { add("Produttore: $it") }
            media.licensors.forEach { add("Licenza: $it") }
            media.source?.let { add("Fonte: $it") }
            media.premiered?.let { add("Stagione: $it") }
            media.broadcast?.let { add("Trasmissione: $it") }
        }.distinctBy { it.lowercase(Locale.ROOT) }

        val response = if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
                torrent = torrentContext,
            )
            newMovieLoadResponse(
                media.title,
                media.url,
                TvType.AnimeMovie,
                dataUrl = playbackData.toJson(),
            ) {
                apiName = this@StreamCenter.name
                posterUrl = media.posterUrl
                plot = media.synopsis
                this.tags = tags
                year = media.year
                duration = media.duration
                contentRating = media.contentRating
                actors = media.characters
                this.recommendations = recommendations
                comingSoon = media.comingSoon
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        mal = media.id,
                        kitsu = resolvedKitsuId,
                    ),
                )
                media.trailerUrl?.let { addTrailer(it) }
                addScore(media.score)
            }
        } else {
            val episodeMetadata = malEpisodes.map { episode ->
                newEpisode("") {
                    name = episode.title ?: "Episodio ${episode.number}"
                    season = 1
                    this.episode = episode.number
                    score = episode.score
                    episode.airedDate?.let { addDate(it) }
                }
            }
            val episodes = buildCatalogAnimeEpisodes(
                totalEpisodes = media.totalEpisodes,
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                episodeMetadata = episodeMetadata,
                fallbackPoster = media.posterUrl,
                stremioContext = stremioContext,
                torrentContext = torrentContext,
            )
            newAnimeLoadResponse(
                media.title,
                media.url,
                media.type,
            ) {
                apiName = this@StreamCenter.name
                posterUrl = media.posterUrl
                plot = media.synopsis
                this.tags = tags
                year = media.year
                duration = media.duration
                contentRating = media.contentRating
                actors = media.characters
                this.recommendations = recommendations
                showStatus = media.status
                comingSoon = media.comingSoon
                applyAnimeCatalogTitles(
                    englishTitle = media.englishTitle,
                    nativeTitle = media.japaneseTitle,
                    alternativeTitles = media.synonyms,
                )
                addEpisodes(DubStatus.Subbed, episodes)
                if (
                    animeUnitySources.isEmpty() &&
                    animeWorldSources.isEmpty() &&
                    animeSaturnSources.isEmpty()
                ) {
                    addSeasonNames(buildAnimeSeasonData(episodes))
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        mal = media.id,
                        kitsu = resolvedKitsuId,
                    ),
                )
                media.trailerUrl?.let { addTrailer(it) }
                addScore(media.score)
            }
        }
        val playbackSourceNames = buildList {
            if (animeUnitySources.isNotEmpty()) add("AnimeUnity")
            if (animeWorldSources.isNotEmpty()) add("AnimeWorld")
            if (animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
        }
        return response.withCardProvenance(
            defaultSource = "MyAnimeList (Jikan)",
            fieldSources = animeProviderCardSources(
                metadataSource = "MyAnimeList (Jikan)",
                playbackSources = playbackSourceNames,
                episodeMetadataSources = listOf("MyAnimeList (Jikan)"),
                trackingSources = listOf("MyAnimeList", "Kitsu"),
                torrentContext = torrentContext,
            ),
        )
    }

    private suspend fun loadKitsuMedia(url: String): LoadResponse {
        val media = kitsuCatalog.media(url)
        val title = kitsuCatalog.preferredTitle(media)
        val isMovie = media.type == TvType.AnimeMovie
        val syncIds = listOf(
            AnimeSyncIds(
                anilistId = media.anilistId,
                malId = media.malId,
                kitsuId = media.id,
            ),
        )
        val sourceMetadata = StreamCenterMetadata(
            title = media.title,
            originalTitle = media.nativeTitle,
            plot = media.synopsis,
            poster = media.posterUrl,
            background = media.backgroundUrl,
            tags = media.categories,
            year = media.year,
            tmdbId = null,
            score = media.score,
            people = media.characters,
            contentRating = media.contentRating,
            showStatus = media.showStatus,
            comingSoon = media.comingSoon,
            duration = media.duration,
            trailerUrl = media.trailerUrl,
        )
        val matchMetadata = AnilistMetadata(
            title = media.title,
            titleCandidates = media.titleCandidates,
        )
        val (kitsuEpisodes, resolvedSources) = coroutineScope {
            val episodesDeferred = if (isMovie) {
                null
            } else {
                async(Dispatchers.IO) {
                    kitsuMetadataClient.fetchEpisodes(media.id, media.episodeCount)
                }
            }
            val sourcesDeferred = async(Dispatchers.IO) {
                resolveAnimePlaybackSources(
                    metadata = sourceMetadata,
                    matchMetadata = matchMetadata,
                    syncIds = syncIds,
                    aniZipIds = media.anilistId to media.malId,
                    includeAniZip = shouldResolveAnimeTorrentMetadata(),
                )
            }
            episodesDeferred?.await().orEmpty() to sourcesDeferred.await()
        }
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
            anilistId = media.anilistId,
            malId = media.malId,
            kitsuId = media.id,
        )
        val torrentContext = animeTorrentPlaybackContext(
            titles = listOf(title) + media.titleCandidates,
            year = media.year,
            isMovie = isMovie,
            tabName = title,
            aniZipCatalog = resolvedSources.aniZipCatalog,
            anilistId = media.anilistId,
            malId = media.malId,
            kitsuId = media.id,
            knownKitsuTitle = media.nativeTitle,
        )
        val recommendations = media.recommendations.map { recommendation ->
            newAnimeSearchResponse(
                recommendation.title,
                "https://kitsu.io/anime/${recommendation.id}",
                when (recommendation.subtype?.lowercase(Locale.ROOT)) {
                    "movie" -> TvType.AnimeMovie
                    "ova", "ona", "special", "music" -> TvType.OVA
                    else -> TvType.Anime
                },
            ) {
                posterUrl = recommendation.posterUrl
            }
        }
        val subtype = media.subtype
            ?.replace('_', ' ')
            ?.lowercase(Locale.ROOT)
            ?.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val tags = (listOfNotNull(subtype) + media.categories)
            .distinctBy { it.lowercase(Locale.ROOT) }

        val response = if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
                torrent = torrentContext,
            )
            newMovieLoadResponse(
                title,
                media.url,
                TvType.AnimeMovie,
                dataUrl = playbackData.toJson(),
            ) {
                apiName = this@StreamCenter.name
                posterUrl = media.posterUrl
                backgroundPosterUrl = media.backgroundUrl
                plot = media.synopsis
                this.tags = tags
                year = media.year
                duration = media.duration
                contentRating = media.contentRating
                actors = media.characters
                this.recommendations = recommendations
                comingSoon = media.comingSoon
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        anilist = media.anilistId,
                        mal = media.malId,
                        kitsu = media.id,
                    ),
                )
                media.trailerUrl?.let { addTrailer(it) }
                addScore(media.score)
            }
        } else {
            val episodeMetadata = kitsuEpisodes.map { (number, episode) ->
                newEpisode("") {
                    name = episode.name ?: "Episodio $number"
                    season = 1
                    this.episode = number
                    posterUrl = episode.posterUrl
                    description = episode.description
                    runTime = episode.runTime
                    episode.date?.let { addDate(it) }
                }
            }
            val totalEpisodes = media.episodeCount
                ?: kitsuEpisodes.keys.maxOrNull()?.takeIf { it > 0 }
            val episodes = buildCatalogAnimeEpisodes(
                totalEpisodes = totalEpisodes,
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                episodeMetadata = episodeMetadata,
                fallbackPoster = media.posterUrl,
                stremioContext = stremioContext,
                torrentContext = torrentContext,
            )
            newAnimeLoadResponse(
                title,
                media.url,
                media.type,
            ) {
                apiName = this@StreamCenter.name
                posterUrl = media.posterUrl
                backgroundPosterUrl = media.backgroundUrl
                plot = media.synopsis
                this.tags = tags
                year = media.year
                duration = media.duration
                contentRating = media.contentRating
                actors = media.characters
                this.recommendations = recommendations
                showStatus = media.showStatus
                comingSoon = media.comingSoon
                applyAnimeCatalogTitles(
                    englishTitle = media.englishTitle,
                    nativeTitle = media.nativeTitle,
                    alternativeTitles = listOfNotNull(media.romajiTitle) + media.abbreviatedTitles,
                )
                addEpisodes(DubStatus.Subbed, episodes)
                if (
                    animeUnitySources.isEmpty() &&
                    animeWorldSources.isEmpty() &&
                    animeSaturnSources.isEmpty()
                ) {
                    addSeasonNames(buildAnimeSeasonData(episodes))
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        anilist = media.anilistId,
                        mal = media.malId,
                        kitsu = media.id,
                    ),
                )
                media.trailerUrl?.let { addTrailer(it) }
                addScore(media.score)
            }
        }
        val playbackSourceNames = buildList {
            if (animeUnitySources.isNotEmpty()) add("AnimeUnity")
            if (animeWorldSources.isNotEmpty()) add("AnimeWorld")
            if (animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
        }
        return response.withCardProvenance(
            defaultSource = "Kitsu",
            fieldSources = animeProviderCardSources(
                metadataSource = "Kitsu",
                playbackSources = playbackSourceNames,
                episodeMetadataSources = listOf("Kitsu"),
                trackingSources = listOf("Kitsu", "AniList", "MyAnimeList"),
                torrentContext = torrentContext,
            ),
        )
    }

    private suspend fun loadSimklMedia(url: String): LoadResponse {
        val media = simklCatalog.media(url)
        val isAnime = media.category == "anime"
        val isMovie = media.category == "movies" || media.type == TvType.AnimeMovie
        val syncIds = listOf(
            AnimeSyncIds(
                anilistId = media.ids.anilist,
                malId = media.ids.mal,
                kitsuId = media.ids.kitsu,
            ),
        )
        val sourceMetadata = StreamCenterMetadata(
            title = media.title,
            originalTitle = media.englishTitle,
            plot = media.plot,
            poster = media.posterUrl,
            background = media.backgroundUrl,
            tags = media.tags,
            year = media.year,
            tmdbId = media.ids.tmdb,
            score = media.score,
            people = media.actors,
            contentRating = media.contentRating,
            showStatus = media.showStatus,
            comingSoon = media.comingSoon,
            duration = media.runtime,
            trailerUrl = media.trailerUrl,
        )
        val (simklEpisodes, resolvedSources, streamingCommunityTitle) = coroutineScope {
            val episodesDeferred: kotlinx.coroutines.Deferred<List<StreamCenterSimklEpisode>>? =
                if (isMovie) null else async(Dispatchers.IO) {
                    runCatching { simklCatalog.episodes(media) }.getOrDefault(emptyList())
                }
            val sourcesDeferred = if (isAnime) async(Dispatchers.IO) {
                resolveAnimePlaybackSources(
                    metadata = sourceMetadata,
                    matchMetadata = AnilistMetadata(media.title, media.titleCandidates),
                    syncIds = syncIds,
                    aniZipIds = media.ids.anilist to media.ids.mal,
                    includeAniZip = shouldResolveAnimeTorrentMetadata(),
                )
            } else {
                null
            }
            val streamingCommunityDeferred = if (
                !isAnime && isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
            ) {
                async(Dispatchers.IO) {
                    runCatching { streamingCommunityClient.findTitle(sourceMetadata, !isMovie) }.getOrNull()
                }
            } else {
                null
            }
            Triple(
                episodesDeferred?.await().orEmpty(),
                sourcesDeferred?.await() ?: ResolvedLoadSources(),
                streamingCommunityDeferred?.await(),
            )
        }
        val streamingCommunityEpisodes = if (streamingCommunityTitle?.type == "tv") {
            runCatching { streamingCommunityClient.episodePayloads(streamingCommunityTitle) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = when {
                isAnime && isMovie -> listOf("movie", "anime")
                isAnime -> listOf("series", "anime")
                isMovie -> listOf("movie")
                else -> listOf("series")
            },
            imdbId = media.ids.imdb,
            tmdbId = media.ids.tmdb,
            anilistId = media.ids.anilist,
            malId = media.ids.mal,
            kitsuId = media.ids.kitsu,
            simklId = media.ids.simkl,
        )
        val tmdbEnglishTitle = if (isAnime) {
            null
        } else {
            resolveTmdbEnglishTitle(media.ids.tmdb, isMovie)
        }
        val torrentContext = if (isAnime) {
            animeTorrentPlaybackContext(
                titles = media.titleCandidates,
                year = media.year,
                isMovie = isMovie,
                tabName = media.title,
                aniZipCatalog = resolvedSources.aniZipCatalog,
                anilistId = media.ids.anilist,
                malId = media.ids.mal,
                kitsuId = media.ids.kitsu,
                imdbId = media.ids.imdb,
            )
        } else {
            torrentPlaybackContext(
                titles = media.titleCandidates,
                englishTitle = tmdbEnglishTitle ?: media.englishTitle,
                year = media.year,
                isAnime = false,
                isMovie = isMovie,
                imdbId = media.ids.imdb,
            )
        }
        val recommendations = media.recommendations.map { recommendation ->
            when (recommendation.type) {
                TvType.Movie -> newMovieSearchResponse(recommendation.title, recommendation.url, recommendation.type) {
                    posterUrl = recommendation.posterUrl
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(
                    recommendation.title,
                    recommendation.url,
                    recommendation.type,
                ) {
                    posterUrl = recommendation.posterUrl
                }
                else -> newAnimeSearchResponse(recommendation.title, recommendation.url, recommendation.type) {
                    posterUrl = recommendation.posterUrl
                }
            }
        }
        val tags = (media.tags + media.studios.map { "Studio: $it" })
            .distinctBy { it.lowercase(Locale.ROOT) }

        if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = resolvedSources.animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = resolvedSources.animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = resolvedSources.animeSaturnSources.flatMap { it.firstPlaybacks() },
                streamingCommunity = streamingCommunityTitle?.let(streamingCommunityClient::moviePlayback),
                stremio = stremioContext,
                torrent = torrentContext,
            )
            val response = newMovieLoadResponse(media.title, media.url, media.type, dataUrl = playbackData.toJson()) {
                apiName = this@StreamCenter.name
                posterUrl = media.posterUrl
                backgroundPosterUrl = media.backgroundUrl
                plot = media.plot
                this.tags = tags
                year = media.year
                duration = media.runtime
                contentRating = media.contentRating
                actors = media.actors
                this.recommendations = recommendations
                comingSoon = media.comingSoon
                addStreamCenterTrackingIds(media.trackingIds())
                media.trailerUrl?.let { addTrailer(it) }
                addScore(media.score)
            }
            val playbackSourceNames = buildList {
                if (resolvedSources.animeUnitySources.isNotEmpty()) add("AnimeUnity")
                if (resolvedSources.animeWorldSources.isNotEmpty()) add("AnimeWorld")
                if (resolvedSources.animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
                if (streamingCommunityTitle != null) add("StreamingCommunity")
            }
            return response.withCardProvenance(
                defaultSource = "Simkl",
                fieldSources = animeProviderCardSources(
                    metadataSource = "Simkl",
                    playbackSources = playbackSourceNames,
                    episodeMetadataSources = listOf("Simkl"),
                    trackingSources = listOf(
                        "Simkl",
                        "TMDB",
                        "IMDb",
                        "AniList",
                        "MyAnimeList",
                        "Kitsu",
                    ),
                    torrentContext = torrentContext,
                ),
            )
        }

        val metadataEpisodes = simklEpisodes.map { episode ->
            newEpisode("") {
                name = episode.title ?: "Episodio ${episode.episode}"
                season = episode.season
                this.episode = episode.episode
                posterUrl = episode.posterUrl
                description = episode.description
                score = episode.score
                runTime = episode.runTime
                episode.date?.let { addDate(it) }
            }
        }
        val episodes = if (isAnime) {
            buildCatalogAnimeEpisodes(
                totalEpisodes = media.totalEpisodes,
                animeUnitySources = resolvedSources.animeUnitySources,
                animeWorldSources = resolvedSources.animeWorldSources,
                animeSaturnSources = resolvedSources.animeSaturnSources,
                episodeMetadata = metadataEpisodes,
                fallbackPoster = media.posterUrl,
                stremioContext = stremioContext,
                torrentContext = torrentContext,
            )
        } else {
            metadataEpisodes.map { episode ->
                newEpisode(
                    StreamCenterPlaybackData(
                        streamingCommunity = streamingCommunityEpisodes[episode.season to episode.episode],
                        stremio = stremioContext.copy(
                            season = episode.season,
                            episode = episode.episode,
                        ),
                        torrent = torrentContext?.forEpisode(episode.season, episode.episode),
                    ).toJson(),
                ) {
                    name = episode.name
                    season = episode.season
                    this.episode = episode.episode
                    posterUrl = episode.posterUrl ?: media.posterUrl
                    description = episode.description
                    score = episode.score
                    runTime = episode.runTime
                    episode.date?.let { date = it }
                }
            }
        }
        val synonyms = (listOfNotNull(media.englishTitle) + media.alternativeTitles)
            .filterNot { it.equals(media.title, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val response = if (isAnime) {
            newAnimeLoadResponse(media.title, media.url, media.type) {
                apiName = this@StreamCenter.name
                posterUrl = media.posterUrl
                backgroundPosterUrl = media.backgroundUrl
                plot = media.plot
                this.tags = tags
                year = media.year
                duration = media.runtime
                contentRating = media.contentRating
                actors = media.actors
                this.recommendations = recommendations
                showStatus = media.showStatus
                comingSoon = media.comingSoon
                applyAnimeCatalogTitles(
                    englishTitle = media.englishTitle,
                    nativeTitle = null,
                    alternativeTitles = synonyms,
                )
                addEpisodes(DubStatus.Subbed, episodes)
                addSeasonNames(buildAnimeSeasonData(episodes))
                addStreamCenterTrackingIds(media.trackingIds())
                media.trailerUrl?.let { addTrailer(it) }
                addScore(media.score)
            }
        } else {
            newTvSeriesLoadResponse(media.title, media.url, TvType.TvSeries, episodes) {
                apiName = this@StreamCenter.name
                posterUrl = media.posterUrl
                backgroundPosterUrl = media.backgroundUrl
                plot = media.plot
                this.tags = tags
                year = media.year
                duration = media.runtime
                contentRating = media.contentRating
                actors = media.actors
                this.recommendations = recommendations
                showStatus = media.showStatus
                comingSoon = media.comingSoon
                addSeasonNames(buildAnimeSeasonData(episodes))
                addStreamCenterTrackingIds(media.trackingIds())
                media.trailerUrl?.let { addTrailer(it) }
                addScore(media.score)
            }
        }
        val playbackSourceNames = buildList {
            if (resolvedSources.animeUnitySources.isNotEmpty()) add("AnimeUnity")
            if (resolvedSources.animeWorldSources.isNotEmpty()) add("AnimeWorld")
            if (resolvedSources.animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
            if (streamingCommunityTitle != null) add("StreamingCommunity")
        }
        return response.withCardProvenance(
            defaultSource = "Simkl",
            fieldSources = animeProviderCardSources(
                metadataSource = "Simkl",
                playbackSources = playbackSourceNames,
                episodeMetadataSources = listOf("Simkl"),
                trackingSources = listOf(
                    "Simkl",
                    "TMDB",
                    "IMDb",
                    "AniList",
                    "MyAnimeList",
                    "Kitsu",
                ),
                torrentContext = torrentContext,
            ),
        )
    }

    private suspend fun resolveAnimePlaybackSources(
        metadata: StreamCenterMetadata,
        matchMetadata: AnilistMetadata,
        syncIds: List<AnimeSyncIds>,
        aniZipIds: Pair<Int?, Int?>? = null,
        includeAniZip: Boolean,
        ignoreSourceFailures: Boolean = false,
    ): ResolvedLoadSources = coroutineScope {
        StreamCenterLogger.logTab(
            tabName = metadata.title,
            action = "Risoluzione fonti anime avviata",
            metadata = mapOf(
                "anilist_id" to syncIds.mapNotNull(AnimeSyncIds::anilistId),
                "mal_id" to syncIds.mapNotNull(AnimeSyncIds::malId),
                "kitsu_id" to syncIds.mapNotNull(AnimeSyncIds::kitsuId),
                "animeunity_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY),
                "animeworld_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD),
                "animesaturn_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN),
                "anizip_richiesto" to (includeAniZip && aniZipIds != null),
                "fallimenti_ignorati" to ignoreSourceFailures,
            ),
        )
        val animeUnityDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)) {
            async(Dispatchers.IO) {
                fetchAnimeSource(metadata.title, "AnimeUnity", ignoreSourceFailures) {
                    animeUnitySourceClient.fetchSources(metadata, matchMetadata, syncIds)
                }
            }
        } else {
            null
        }
        val animeWorldDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) {
            async(Dispatchers.IO) {
                fetchAnimeSource(metadata.title, "AnimeWorld", ignoreSourceFailures) {
                    animeWorldSourceClient.fetchSources(metadata, matchMetadata, syncIds)
                }
            }
        } else {
            null
        }
        val animeSaturnDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) {
            async(Dispatchers.IO) {
                fetchAnimeSource(metadata.title, "AnimeSaturn", ignoreSourceFailures) {
                    animeSaturnSourceClient.fetchSources(metadata, matchMetadata, syncIds)
                }
            }
        } else {
            null
        }
        val aniZipDeferred = if (includeAniZip && aniZipIds != null) {
            async(Dispatchers.IO) {
                StreamCenterLogger.logMetadata(
                    tabName = metadata.title,
                    source = "AniZip",
                    action = "Ricerca catalogo episodi avviata",
                    metadata = mapOf("id_anilist" to aniZipIds.first, "id_mal" to aniZipIds.second),
                )
                var timedOut = false
                val result = try {
                    if (shouldResolveAnimeTorrentMetadata()) {
                        withTimeoutOrNull(ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS) {
                            aniZipMetadataClient.fetch(aniZipIds.first, aniZipIds.second)
                        } ?: AniZipEpisodeCatalog().also { timedOut = true }
                    } else {
                        aniZipMetadataClient.fetch(aniZipIds.first, aniZipIds.second)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    StreamCenterLogger.logTabError(
                        tabName = metadata.title,
                        action = "Ricerca catalogo episodi AniZip non riuscita",
                        throwable = error,
                        metadata = mapOf("id_anilist" to aniZipIds.first, "id_mal" to aniZipIds.second),
                    )
                    AniZipEpisodeCatalog()
                }
                StreamCenterLogger.logMetadata(
                    tabName = metadata.title,
                    source = "AniZip",
                    action = "Ricerca catalogo episodi completata",
                    metadata = mapOf(
                        "episodi" to result.episodes.size,
                        "titoli_localizzati" to result.titles.size,
                        "id_anilist" to result.anilistId,
                        "id_mal" to result.malId,
                        "id_kitsu" to result.kitsuId,
                        "id_tmdb" to result.tmdbId,
                        "timeout" to timedOut,
                    ),
                    level = if (timedOut || (result.episodes.isEmpty() && result.titles.isEmpty())) {
                        StreamCenterLogger.Level.WARNING
                    } else {
                        StreamCenterLogger.Level.INFO
                    },
                )
                result
            }
        } else {
            null
        }
        val animeUnitySources = animeUnityDeferred?.await().orEmpty()
        val animeWorldSources = animeWorldDeferred?.await().orEmpty()
        val animeSaturnSources = animeSaturnDeferred?.await().orEmpty()
        val aniZipCatalog = aniZipDeferred?.await() ?: AniZipEpisodeCatalog()
        val resolved = ResolvedLoadSources(
            animeUnitySources = animeUnitySources,
            animeWorldSources = animeWorldSources,
            animeSaturnSources = animeSaturnSources,
            aniZipCatalog = aniZipCatalog,
        )
        StreamCenterLogger.logTab(
            tabName = metadata.title,
            action = "Risoluzione fonti anime completata",
            metadata = mapOf(
                "titoli_animeunity" to animeUnitySources.size,
                "titoli_animeworld" to animeWorldSources.size,
                "titoli_animesaturn" to animeSaturnSources.size,
                "episodi_anizip" to aniZipCatalog.episodes.size,
                "titoli_anizip" to aniZipCatalog.titles.size,
            ),
        )
        resolved
    }

    private suspend fun <T> fetchAnimeSource(
        tabName: String,
        source: String,
        ignoreFailures: Boolean,
        fetch: suspend () -> List<T>,
    ): List<T> {
        return try {
            StreamCenterLogger.logMetadata(
                tabName = tabName,
                source = source,
                action = "Ricerca fonte anime avviata",
                metadata = mapOf("fallimenti_ignorati" to ignoreFailures),
            )
            val result = fetch()
            StreamCenterLogger.logMetadata(
                tabName = tabName,
                source = source,
                action = "Ricerca fonte anime completata",
                metadata = mapOf("corrispondenze_titolo" to result.size),
                level = if (result.isEmpty()) StreamCenterLogger.Level.WARNING else StreamCenterLogger.Level.INFO,
            )
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StreamCenterLogger.logTabError(
                tabName = tabName,
                action = "Ricerca fonte anime non riuscita",
                throwable = error,
                metadata = mapOf("fonte_metadati" to source, "fallimenti_ignorati" to ignoreFailures),
            )
            if (ignoreFailures) emptyList() else throw error
        }
    }

    private fun AnimeLoadResponse.applyAnimeCatalogTitles(
        englishTitle: String?,
        nativeTitle: String?,
        alternativeTitles: Iterable<String>,
    ) {
        engName = englishTitle?.trim()?.takeIf(String::isNotBlank)
        japName = nativeTitle?.trim()?.takeIf(String::isNotBlank)
        synonyms = (alternativeTitles + listOfNotNull(engName, japName))
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals(name, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun buildCatalogAnimeEpisodes(
        totalEpisodes: Int?,
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
        episodeMetadata: List<Episode>,
        fallbackPoster: String?,
        stremioContext: StreamCenterStremioPlaybackContext,
        torrentContext: StreamCenterTorrentPlaybackContext?,
    ): List<Episode> {
        val metadataByNumber = episodeMetadata.mapNotNull { episode ->
            episode.episode?.takeIf { it > 0 }?.let { it to episode }
        }.toMap()
        val episodeNumbers = totalEpisodes
            ?.takeIf { it > 0 }
            ?.let { (1..it).toList() }
            ?: metadataByNumber.keys.sorted()
        val animeUnityTitleSources = animeUnitySources.firstOrNull()
        val animeWorldTitleSources = animeWorldSources.firstOrNull()
        val animeSaturnTitleSources = animeSaturnSources.firstOrNull()
        return episodeNumbers.map { number ->
            val metadataEpisode = metadataByNumber[number]
            newEpisode(
                StreamCenterPlaybackData(
                    animeUnity = animeUnityTitleSources?.playbackForEpisode(number.toString()),
                    animeWorld = animeWorldTitleSources?.playbacksForEpisode(number.toString()).orEmpty(),
                    animeSaturn = animeSaturnTitleSources?.playbacksForEpisode(number.toString()).orEmpty(),
                    stremio = stremioContext.copy(season = 1, episode = number),
                    torrent = torrentContext?.forEpisode(1, number),
                ).toJson(),
            ) {
                name = metadataEpisode?.name ?: "Episodio $number"
                season = 1
                episode = number
                posterUrl = metadataEpisode?.posterUrl ?: fallbackPoster
                description = metadataEpisode?.description
                score = metadataEpisode?.score
                runTime = metadataEpisode?.runTime
                metadataEpisode?.date?.let { date = it }
            }
        }
    }

    private fun buildAnimeFallbackEpisodes(
        totalEpisodes: Int?,
        episodeMetadata: List<Episode>,
        fallbackPoster: String?,
        stremioContext: StreamCenterStremioPlaybackContext,
        torrentContext: StreamCenterTorrentPlaybackContext?,
    ): List<Episode> {
        if (episodeMetadata.isNotEmpty()) {
            return episodeMetadata.map { info ->
                newEpisode(
                    StreamCenterPlaybackData(
                        stremio = stremioContext.copy(
                            season = info.season ?: 1,
                            episode = info.episode,
                        ),
                        torrent = torrentContext?.forEpisode(info.season ?: 1, info.episode),
                    ).toJson(),
                ) {
                    this.name = info.name ?: "Episodio ${info.episode}"
                    this.season = 1
                    this.episode = info.episode
                    this.posterUrl = info.posterUrl ?: fallbackPoster
                    this.description = info.description
                    this.score = info.score
                    this.runTime = info.runTime
                    info.date?.let { this.date = it }
                }
            }
        }
        val total = totalEpisodes ?: return emptyList()
        if (total <= 0) return emptyList()
        return (1..total).map { number ->
            newEpisode(
                StreamCenterPlaybackData(
                    stremio = stremioContext.copy(season = 1, episode = number),
                    torrent = torrentContext?.forEpisode(1, number),
                ).toJson(),
            ) {
                this.name = "Episodio $number"
                this.season = 1
                this.episode = number
                this.posterUrl = fallbackPoster
            }
        }
    }

    private fun Element.extractImageUrl(): String? {
        val srcset = attr("srcset")
            .split(",")
            .lastOrNull()
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.startsWith("http") }

        return srcset ?: attr("src").takeIf { it.startsWith("http") }
    }

    private fun getLocalizedTitle(doc: Document): String {
        return extractTmdbPageTitle(doc)
    }

    private fun parseYear(doc: Document): Int? {
        val titleYear = doc.selectFirst("section.header.poster h2 span.release_date")
            ?.text()
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        if (titleYear != null) return titleYear

        return doc.selectFirst("span.release")
            ?.text()
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
    }

    private fun parseRuntime(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val hours = Regex("""(\d+)\s*h""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*m""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val runtime = hours * 60 + minutes
        return runtime.takeIf { it > 0 }
    }

    private fun extractAnyFact(doc: Document, vararg labels: String): String? {
        return labels.asSequence().mapNotNull { extractFact(doc, it) }.firstOrNull()
    }

    private fun extractFact(doc: Document, label: String): String? {
        val expectedLabel = normalizeFactLabel(label)
        for (fact in doc.select("section.facts.left_column p")) {
            val strong = fact.selectFirst("strong") ?: continue
            val key = strong.text().trim()
            if (normalizeFactLabel(key) != expectedLabel) continue

            val ownText = fact.ownText().trim()
            val allText = fact.text().trim()
            val fallbackText = if (allText.startsWith(key)) allText.substring(key.length).trim() else allText
            return cleanText(ownText.ifBlank { fallbackText })
        }
        return null
    }

    private fun normalizeFactLabel(value: String): String {
        return value.trim().trimEnd(':').lowercase(Locale.ROOT)
    }

    private fun buildFactTags(
        title: String,
        originalTitle: String?,
        status: String?,
        originalLanguage: String?,
        type: String?,
        budget: String?,
        revenue: String?,
    ): List<String> {
        val originalTitleTag = originalTitle
            ?.takeIf { !it.equals(title, ignoreCase = true) }
            ?.let { "Titolo originale: $it" }

        return listOfNotNull(
            originalTitleTag,
            status?.let { "Stato: $it" },
            originalLanguage?.let { "Lingua originale: $it" },
            type?.let { "Tipo: $it" },
            budget?.let { "Budget: $it" },
            revenue?.let { "Incasso: $it" },
        )
    }

    private fun extractKeywords(doc: Document): List<String> {
        return doc.select("section.keywords li a")
            .mapNotNull { cleanText(it.text()) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun extractContentRating(doc: Document): String? {
        return cleanText(doc.selectFirst("span.certification")?.text())
    }

    private fun extractTrailerUrl(doc: Document): String? {
        val trailer = doc.select("a.play_trailer[data-id]")
            .firstOrNull { it.attr("data-site").equals("YouTube", ignoreCase = true) }
            ?: return null
        val youtubeId = cleanText(trailer.attr("data-id")) ?: return null
        return "https://www.youtube.com/watch?v=$youtubeId"
    }

    private fun mapShowStatus(status: String?): ShowStatus? {
        val normalized = status?.lowercase(Locale.ROOT) ?: return null
        return when {
            normalized.contains("terminat") ||
                normalized.contains("conclus") ||
                normalized.contains("cancellat") ||
                normalized.contains("ended") ||
                normalized.contains("canceled") -> ShowStatus.Completed
            normalized.contains("in corso") ||
                normalized.contains("in onda") ||
                normalized.contains("produzione") ||
                normalized.contains("returning") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun isComingSoon(status: String?): Boolean {
        val normalized = status?.lowercase(Locale.ROOT) ?: return false
        return normalized.contains("prossimamente") ||
            normalized.contains("pianificat") ||
            normalized.contains("annunciat") ||
            normalized.contains("post produzione") ||
            normalized.contains("post-produzione") ||
            normalized.contains("in produzione") ||
            normalized.contains("rumored")
    }

    private suspend fun loadStreamingCommunityHomeTitle(url: String): LoadResponse {
        val type = parseQueryParams(url)[scHomeTypeParam] ?: "tv"
        val idSlug = url.substringAfter(scHomePath).substringBefore("?")
        val id = idSlug.substringBefore("-").toIntOrNull()
            ?: error("StreamingCommunity: id non valido")
        val slug = idSlug.substringAfter("-", "")
        val baseTitle = StreamingCommunityTitle(
            id = id,
            slug = slug,
            name = slug,
            type = type,
            tmdbId = null,
            year = null,
            seasons = emptyList(),
        )
        val detail = runCatching { streamingCommunityClient.fetchTitleDetail(baseTitle) }
            .getOrNull()
            ?: baseTitle
        detail.tmdbId?.let { tmdbId ->
            val tmdbPath = if (type == "tv") "tv" else "movie"
            runCatching { loadTmdbMedia("$mainUrl/$tmdbPath/$tmdbId", scHint = detail) }
                .getOrNull()
                ?.let { return it }
        }
        return loadStreamingCommunityOnly(detail)
    }

    private fun buildStreamingCommunityEpisodes(
        payloads: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
        fallbackPoster: String?,
        stremioContext: StreamCenterStremioPlaybackContext? = null,
        torrentContext: StreamCenterTorrentPlaybackContext? = null,
    ): List<Episode> {
        return payloads.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (seasonEpisode, playback) ->
                newEpisode(
                    StreamCenterPlaybackData(
                        streamingCommunity = playback,
                        stremio = stremioContext?.copy(
                            season = seasonEpisode.first,
                            episode = seasonEpisode.second,
                        ),
                        torrent = torrentContext?.forEpisode(
                            seasonEpisode.first,
                            seasonEpisode.second,
                        ),
                    ).toJson(),
                ) {
                    season = seasonEpisode.first
                    episode = seasonEpisode.second
                    posterUrl = fallbackPoster
                }
            }
    }

    private suspend fun loadStreamingCommunityOnly(title: StreamingCommunityTitle): LoadResponse {
        val sourceUrl = "$mainUrl$scHomePath${title.id}-${title.slug}?$scHomeTypeParam=${title.type}"
        val poster = streamingCommunityClient.imageUrl(title.posterFilename)
        val stremioContext = StreamCenterStremioPlaybackContext(
            contentTypes = if (title.type == "tv") listOf("series") else listOf("movie"),
            imdbId = title.imdbId,
            tmdbId = title.tmdbId?.toString(),
        )
        val tmdbEnglishTitle = resolveTmdbEnglishTitle(
            tmdbId = title.tmdbId?.toString(),
            isMovie = title.type != "tv",
        )
        val torrentContext = torrentPlaybackContext(
            titles = listOf(title.name),
            englishTitle = tmdbEnglishTitle,
            year = title.year,
            isAnime = false,
            isMovie = title.type != "tv",
            imdbId = title.imdbId,
        )
        val resolvedSimklId = resolveSimklId(
            imdb = title.imdbId,
            tmdb = title.tmdbId?.toString(),
            allowedCategories = if (title.type == "tv") setOf("tv") else setOf("movies"),
        )
        val recommendations = if (!performanceMode) {
            fetchStreamingCommunityRecommendations(title)
        } else {
            emptyList()
        }
        val response = if (title.type == "tv") {
            val episodes = buildStreamingCommunityEpisodes(
                streamingCommunityClient.episodePayloads(title),
                poster.takeIf { !performanceMode },
                stremioContext,
                torrentContext,
            )
            newTvSeriesLoadResponse(title.name, sourceUrl, TvType.TvSeries, episodes) {
                if (!performanceMode) {
                    this.posterUrl = poster
                    this.year = title.year
                    this.backgroundPosterUrl = streamingCommunityClient.imageUrl(title.backgroundFilename)
                    this.logoUrl = streamingCommunityClient.imageUrl(title.logoFilename)
                    this.plot = title.plot
                    this.tags = title.genres
                    this.showStatus = streamingCommunityClient.showStatus(title.status)
                    this.contentRating = title.age?.let { "$it+" }
                    this.recommendations = recommendations
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        tmdb = title.tmdbId?.toString(),
                        imdb = title.imdbId,
                        simkl = resolvedSimklId,
                    ),
                    showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                )
                addSeasonNames(buildAnimeSeasonData(episodes))
                if (!performanceMode) addScore(title.score)
            }
        } else {
            val playback = streamingCommunityClient.moviePlayback(title)
            newMovieLoadResponse(
                title.name,
                sourceUrl,
                TvType.Movie,
                dataUrl = StreamCenterPlaybackData(
                    streamingCommunity = playback,
                    stremio = stremioContext,
                    torrent = torrentContext,
                ).toJson(),
            ) {
                if (!performanceMode) {
                    this.posterUrl = poster
                    this.year = title.year
                    this.backgroundPosterUrl = streamingCommunityClient.imageUrl(title.backgroundFilename)
                    this.logoUrl = streamingCommunityClient.imageUrl(title.logoFilename)
                    this.plot = title.plot
                    this.tags = title.genres
                    this.duration = title.runtime
                    this.contentRating = title.age?.let { "$it+" }
                    this.recommendations = recommendations
                }
                addStreamCenterTrackingIds(
                    StreamCenterTrackingIds(
                        tmdb = title.tmdbId?.toString(),
                        imdb = title.imdbId,
                        simkl = resolvedSimklId,
                    ),
                    showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                )
                if (!performanceMode) addScore(title.score)
            }
        }
        val torrentProvenance = torrentPlaybackProvenance(torrentContext)
        val playbackPayloadSources = (
            listOf("StreamingCommunity", "Add-on Stremio abilitati") +
                torrentProvenance +
                "StreamCenter (payload di riproduzione)"
            ).distinct()
        return response.withCardProvenance(
            defaultSource = "StreamingCommunity",
            fieldSources = mapOf(
                "tipo_contenuto" to listOf("StreamingCommunity", "StreamCenter (conversione tipo)"),
                "classificazione_contenuti" to listOf(
                    "StreamingCommunity",
                    "StreamCenter (formattazione età)",
                ),
                "raccomandazioni" to listOf("StreamingCommunity"),
                "id_sincronizzazione" to listOf(
                    "StreamingCommunity",
                    "TMDB",
                    "IMDb",
                    "Simkl",
                ),
                "stagioni" to listOf(
                    "StreamingCommunity",
                    "StreamCenter (normalizzazione stagioni)",
                ),
                "episodi" to (
                    listOf("StreamingCommunity", "Add-on Stremio abilitati") +
                        torrentProvenance
                    ).distinct(),
                "episodi.nome" to listOf("StreamCenter (nome episodio predefinito)"),
                "episodi.poster" to listOf("StreamingCommunity (poster scheda)"),
                "episodi.stagione" to listOf("StreamingCommunity"),
                "episodi.episodio" to listOf("StreamingCommunity"),
                "episodi.dati_riproduzione" to playbackPayloadSources,
                "dati_riproduzione" to playbackPayloadSources,
            ),
            fieldNotes = if (performanceMode) {
                mapOf(
                    "poster" to "Valore omesso dalla scheda finale in modalità prestazioni.",
                    "trama" to "Valore omesso dalla scheda finale in modalità prestazioni.",
                    "raccomandazioni" to "Richiesta opzionale non eseguita in modalità prestazioni.",
                )
            } else {
                emptyMap()
            },
        )
    }

    private fun parseAnimeSelection(url: String): StreamCenterAnimeSelection? {
        val params = parseQueryParams(url)
        val selection = StreamCenterAnimeSelection(
            anilistId = params[animeAnilistParam]?.toIntOrNull(),
            malId = params[animeMalParam]?.toIntOrNull(),
        )

        return selection.takeIf {
            it.anilistId != null || it.malId != null
        }
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val query = url.substringAfter("?", "").takeIf(String::isNotBlank) ?: return emptyMap()
        return query.split("&").mapNotNull { parameter ->
            val key = parameter.substringBefore("=").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val value = parameter.substringAfter("=", "")
            key to value
        }.toMap()
    }

    private fun buildAnimeSeasonData(episodes: List<Episode>): List<SeasonData> {
        return episodes.mapNotNull { it.season }
            .distinct()
            .sorted()
            .map { season ->
                SeasonData(
                    season = season,
                    name = if (season == 0) "Speciali" else "Stagione $season",
                    displaySeason = season.takeIf { it > 0 },
                )
            }
    }

    private suspend fun fetchTmdbSeasonNames(
        actualUrl: String,
        episodes: List<Episode>,
    ): List<SeasonData> {
        val mediaMatch = TMDB_MEDIA_URL_REGEX.find(actualUrl) ?: return buildAnimeSeasonData(episodes)
        if (!mediaMatch.groupValues[1].equals("tv", ignoreCase = true)) return emptyList()
        val tmdbId = mediaMatch.groupValues[2]
        val seasonDocument = getTmdbDocument("https://www.themoviedb.org/tv/$tmdbId/seasons")
        val namesBySeason = seasonDocument.select("a[href*=/season/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").takeUnless { it.contains("/episode/") }
                    ?: return@mapNotNull null
                val season = extractSeasonNumber(href) ?: return@mapNotNull null
                season to anchor.text().trim().takeIf(String::isNotBlank)
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, names) -> names.firstOrNull { !it.isNullOrBlank() } }
        return episodes.mapNotNull(Episode::season)
            .distinct()
            .sorted()
            .map { season ->
                SeasonData(
                    season = season,
                    name = namesBySeason[season]
                        ?: if (season == 0) "Speciali" else "Stagione $season",
                    displaySeason = season.takeIf { it > 0 },
                )
            }
    }

    private suspend fun fetchAnimeUnityArchiveHome(
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

    private suspend fun fetchEpisodes(
        doc: Document,
        actualUrl: String,
        targetSeason: Int? = null,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData> = emptyMap(),
        stremioContext: StreamCenterStremioPlaybackContext? = null,
        torrentContext: StreamCenterTorrentPlaybackContext? = null,
        fallbackPoster: String? = null,
        minimalMetadata: Boolean = false,
    ): List<Episode> = coroutineScope {
        if (!actualUrl.contains("/tv/")) {
            emptyList()
        } else {
            val seasonUrls = targetSeason
                ?.let { listOf(buildTmdbSeasonUrl(actualUrl, it)) }
                ?: fetchSeasonUrls(doc, actualUrl)

            seasonUrls.map { seasonUrl ->
                async(Dispatchers.IO) {
                    val fallbackSeason = extractSeasonNumber(seasonUrl)
                    runCatching { getTmdbDocument(seasonUrl) }
                        .getOrNull()
                        ?.let {
                            parseSeasonEpisodes(
                                seasonDoc = it,
                                fallbackSeason = fallbackSeason,
                                streamingCommunityEpisodes = streamingCommunityEpisodes,
                                stremioContext = stremioContext,
                                torrentContext = torrentContext,
                                fallbackPoster = fallbackPoster,
                                minimalMetadata = minimalMetadata,
                            )
                        }
                        .orEmpty()
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun fetchSeasonUrls(doc: Document, actualUrl: String): List<String> {
        val mediaMatch = TMDB_MEDIA_URL_REGEX.find(actualUrl) ?: return emptyList()
        if (!mediaMatch.groupValues[1].equals("tv", ignoreCase = true)) return emptyList()
        val tmdbId = mediaMatch.groupValues[2]
        val path = "tv/$tmdbId"

        val seasonIndex = runCatching {
            getTmdbDocument("https://www.themoviedb.org/$path/seasons")
        }.getOrNull()
        val seasonUrls = seasonIndex?.let(::extractSeasonLinks).orEmpty()
        return seasonUrls.ifEmpty { extractSeasonLinks(doc) }
    }

    private fun buildTmdbSeasonUrl(actualUrl: String, seasonNumber: Int): String {
        val baseUrl = stripStreamCenterParams(normalizeTmdbUrl(actualUrl)).substringBefore("?").trimEnd('/')
        return normalizeTmdbUrl("$baseUrl/season/$seasonNumber")
    }

    private fun extractSeasonLinks(doc: Document): List<String> {
        return doc.select("a[href*=/season/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                    .substringBefore("#")
                    .takeIf { it.contains("/season/") && !it.contains("/episode/") }
                    ?: return@mapNotNull null
                normalizeTmdbUrl(href)
            }
            .filter { extractSeasonNumber(it) != null }
            .distinctBy { extractSeasonNumber(it) }
            .sortedWith(compareBy({ extractSeasonNumber(it) ?: Int.MAX_VALUE }, { it }))
    }

    private fun extractSeasonNumber(url: String): Int? {
        return Regex("""/season/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseSeasonEpisodes(
        seasonDoc: Document,
        fallbackSeason: Int?,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
        stremioContext: StreamCenterStremioPlaybackContext? = null,
        torrentContext: StreamCenterTorrentPlaybackContext? = null,
        fallbackPoster: String? = null,
        minimalMetadata: Boolean = false,
    ): List<Episode> {
        return seasonDoc.select("div.episode_list div.card").mapNotNull { card ->
            val anchor = card.selectFirst("a[data-episode-number][data-season-number]")
                ?: card.selectFirst("a[href*=/episode/]")
            val rawHref = anchor?.attr("href")?.takeIf { it.isNotBlank() && it.contains("/episode/") }
                ?: card.attr("data-url").takeIf { it.isNotBlank() && it.contains("/episode/") }
            val dataUrl = rawHref?.let { normalizeTmdbUrl(it) }.orEmpty()
            val episodeNumber = anchor?.attr("data-episode-number")?.toIntOrNull()
                ?: rawHref?.let { Regex("""/episode/(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            val seasonNumber = anchor?.attr("data-season-number")?.toIntOrNull()
                ?: rawHref?.let { extractSeasonNumber(it) }
                ?: fallbackSeason
            val title = if (minimalMetadata) {
                null
            } else {
                cleanEpisodeTitle(
                    card.selectFirst("div.episode_title h3 a")?.text()
                        ?: anchor?.text()
                )
            }

            if (dataUrl.isBlank() && episodeNumber == null && title == null) return@mapNotNull null

            val airDate = if (minimalMetadata) null else parseItalianDateToIso(card.selectFirst("div.date span.date")?.text())
            val runtime = if (minimalMetadata) null else parseRuntime(card.selectFirst("span.runtime")?.text())
            val score = if (minimalMetadata) {
                null
            } else {
                (
                    card.selectFirst("div.rating")?.text()
                        ?: card.selectFirst("[data-percent]")?.attr("data-percent")
                    )
                    ?.let { Regex("""\d+""").find(it)?.value }
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                    ?.div(10.0)
                    ?.let { Score.from(it.toString(), 10) }
            }
            val streamingCommunityPlayback = seasonNumber?.let { season ->
                episodeNumber?.let { episode -> streamingCommunityEpisodes[season to episode] }
            }
            val sourcePayload = buildStreamCenterEpisodePayload(
                tmdbUrl = dataUrl,
                streamingCommunity = streamingCommunityPlayback,
                stremioContext = stremioContext?.copy(
                    season = seasonNumber,
                    episode = episodeNumber,
                ),
                torrentContext = torrentContext?.forEpisode(seasonNumber, episodeNumber),
            )
            newEpisode(sourcePayload) {
                this.name = if (minimalMetadata) {
                    episodeNumber?.let { "Episodio $it" }
                } else {
                    title
                }
                this.season = seasonNumber
                this.episode = episodeNumber
                if (!minimalMetadata) {
                    this.posterUrl = card.selectFirst("img.backdrop")?.extractImageUrl()
                        ?: card.selectFirst("img")?.extractImageUrl()
                        ?: fallbackPoster
                    this.description = cleanEpisodeDescription(card.selectFirst("div.overview p")?.text())
                    this.runTime = runtime
                    this.score = score
                    airDate?.let { this.addDate(it) }
                }
            }
        }
    }

    private fun buildStreamCenterEpisodePayload(
        tmdbUrl: String,
        streamingCommunity: StreamingCommunityPlaybackData? = null,
        stremioContext: StreamCenterStremioPlaybackContext? = null,
        torrentContext: StreamCenterTorrentPlaybackContext? = null,
    ): String {
        if (streamingCommunity == null && stremioContext == null && torrentContext == null) {
            return tmdbUrl
        }
        return StreamCenterPlaybackData(
            tmdbUrl = tmdbUrl.takeIf(String::isNotBlank),
            streamingCommunity = streamingCommunity,
            stremio = stremioContext,
            torrent = torrentContext,
        ).toJson()
    }

    private fun buildAnimeSourceEpisodes(
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
        episodeMetadata: List<Episode>,
        fallbackPoster: String? = null,
        stremioContext: StreamCenterStremioPlaybackContext,
        torrentContext: StreamCenterTorrentPlaybackContext?,
    ): List<Episode> {
        val animeUnityTitleSources = animeUnitySources.firstOrNull()
        val animeWorldTitleSources = animeWorldSources.firstOrNull()
        val animeSaturnTitleSources = animeSaturnSources.firstOrNull()
        val episodeNumbers = (
            animeUnityTitleSources?.episodeNumbers().orEmpty() +
                animeWorldTitleSources?.episodeNumbers().orEmpty() +
                animeSaturnTitleSources?.episodeNumbers().orEmpty()
            )
            .distinct()
            .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        if (episodeNumbers.isEmpty()) return emptyList()

        val metadataByNumber = episodeMetadata.mapNotNull { episode ->
            episode.episode?.let { it to episode }
        }.toMap()

        return episodeNumbers.mapNotNull { number ->
            val playback = animeUnityTitleSources?.playbackForEpisode(number)
            val animeWorldPlaybacks = animeWorldTitleSources?.playbacksForEpisode(number).orEmpty()
            val animeSaturnPlaybacks = animeSaturnTitleSources?.playbacksForEpisode(number).orEmpty()
            if (
                playback == null &&
                animeWorldPlaybacks.isEmpty() &&
                animeSaturnPlaybacks.isEmpty()
            ) {
                return@mapNotNull null
            }
            val episodeNumber = parseWholeAnimeEpisodeNumber(number)
            val metadataEpisode = episodeNumber?.let { metadataByNumber[it] }
            val isSpecialEpisode = episodeNumber == null || episodeNumber <= 0
            newEpisode(
                StreamCenterPlaybackData(
                    animeUnity = playback,
                    animeWorld = animeWorldPlaybacks,
                    animeSaturn = animeSaturnPlaybacks,
                    stremio = stremioContext.copy(
                        season = metadataEpisode?.season ?: 1,
                        episode = episodeNumber?.takeIf { it > 0 } ?: metadataEpisode?.episode,
                    ),
                    torrent = torrentContext?.forEpisode(
                        metadataEpisode?.season ?: 1,
                        episodeNumber?.takeIf { it > 0 } ?: metadataEpisode?.episode,
                    ),
                ).toJson()
            ) {
                this.name = metadataEpisode?.name
                    ?: if (isSpecialEpisode) "Speciale $number" else "Episodio $number"
                this.season = metadataEpisode?.season ?: 1
                this.episode = episodeNumber?.takeIf { it > 0 } ?: metadataEpisode?.episode
                this.posterUrl = metadataEpisode?.posterUrl ?: fallbackPoster
                this.description = metadataEpisode?.description
                this.score = metadataEpisode?.score
                this.runTime = metadataEpisode?.runTime
                metadataEpisode?.date?.let { this.date = it }
            }
        }
    }

    private fun maxAnimeSourceEpisodeNumber(
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
    ): Int? {
        return (
            animeUnitySources.flatMap { it.episodeNumbers() } +
                animeWorldSources.flatMap { it.episodeNumbers() } +
                animeSaturnSources.flatMap { it.episodeNumbers() }
            )
            .mapNotNull(::parseWholeAnimeEpisodeNumber)
            .maxOrNull()
    }

    private fun cleanEpisodeTitle(text: String?): String? {
        return cleanText(text)
            ?.replace(Regex("""^\d+\.\s*"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun cleanEpisodeDescription(text: String?): String? {
        return cleanText(
            text
                ?.replace("Leggi di pi\u00f9", "")
                ?.replace("Leggi di piu", "")
        )
    }

    private fun parseItalianDateToIso(text: String?): String? {
        val cleaned = cleanText(text) ?: return null
        Regex("""\d{4}-\d{2}-\d{2}""").find(cleaned)?.value?.let { return it }

        val match = Regex("""(\d{1,2})\s+([A-Za-z]+),?\s+(\d{4})""").find(cleaned) ?: return null
        val day = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val monthName = match.groupValues.getOrNull(2)?.lowercase(Locale.ROOT) ?: return null
        val year = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val month = mapOf(
            "gennaio" to 1,
            "febbraio" to 2,
            "marzo" to 3,
            "aprile" to 4,
            "maggio" to 5,
            "giugno" to 6,
            "luglio" to 7,
            "agosto" to 8,
            "settembre" to 9,
            "ottobre" to 10,
            "novembre" to 11,
            "dicembre" to 12,
        )[monthName] ?: return null

        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun parseActors(doc: Document): List<ActorData> {
        return doc.select("#cast_scroller li.card").mapNotNull { card ->
            val name = card.selectFirst("p a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val character = card.selectFirst("p.character")?.text()?.trim()
            val image = card.selectFirst("img.profile")?.extractImageUrl()
            ActorData(Actor(name, image), roleString = character)
        }
    }

    private fun parseCrew(doc: Document): List<ActorData> {
        return doc.select("ol.people.no_image li.profile").mapNotNull { person ->
            val name = cleanText(person.selectFirst("p a")?.text()) ?: return@mapNotNull null
            val role = cleanText(person.selectFirst("p.character")?.text())
            ActorData(Actor(name, null), roleString = role)
        }
    }

    private fun buildAnimeUnityPlayerSources(playbackData: AnimeUnityPlaybackData): List<AnimeUnityPlayerSource> {
        val sources = mutableListOf<AnimeUnityPlayerSource>()
        val seen = linkedSetOf<String>()

        fun add(url: String?, label: String) {
            val normalizedUrl = url?.takeIf(String::isNotBlank) ?: return
            if (seen.add(normalizedUrl)) {
                sources += AnimeUnityPlayerSource(label = label, url = normalizedUrl)
            }
        }

        when (playbackData.preferredUrl) {
            playbackData.dubUrl -> {
                add(playbackData.dubUrl, "[DUB]")
                add(playbackData.subUrl, "[SUB]")
            }
            playbackData.subUrl -> {
                add(playbackData.subUrl, "[SUB]")
                add(playbackData.dubUrl, "[DUB]")
            }
            else -> {
                add(playbackData.preferredUrl, "[SOURCE]")
                add(playbackData.subUrl, "[SUB]")
                add(playbackData.dubUrl, "[DUB]")
            }
        }

        return sources
    }

    private fun animeSourceDisplayName(provider: String, label: String): String {
        val audio = when {
            label.contains("DUB", ignoreCase = true) -> "\uD83C\uDDEE\uD83C\uDDF9 (DUB)"
            label.contains("SUB", ignoreCase = true) -> "\uD83C\uDDEF\uD83C\uDDF5 (SUB)"
            else -> "Fonte principale"
        }
        return "$provider - $audio"
    }

    private suspend fun loadAnimeUnityLinks(
        playbackData: AnimeUnityPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tasks = buildAnimeUnityPlayerSources(playbackData).map { source ->
            suspend {
                val embedUrl = getAnimeUnityEmbedUrl(source.url)
                if (embedUrl.isNullOrBlank()) {
                    false
                } else {
                    StreamCenterVixCloudExtractor(
                        sourceName = animeSourceDisplayName("VixCloud", source.label),
                        displayName = animeSourceDisplayName("AnimeUnity", source.label),
                    ).getUrl(
                        url = embedUrl,
                        referer = animeUnityUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                    true
                }
            }
        }
        return runParallelSourceTasks(tasks)
    }

    private suspend fun getAnimeUnityEmbedUrl(playerUrl: String): String? {
        val html = fetchText {
            app.get(
                playerUrl,
            ).text
        }

        return Jsoup.parse(html, playerUrl)
            .selectFirst("video-player")
            ?.attr("embed_url")
            ?.takeIf(String::isNotBlank)
    }

    private suspend fun loadAnimeWorldLink(
        playbackData: AnimeWorldPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val infoUrl = "$animeWorldUrl/api/episode/info?id=${URLEncoder.encode(playbackData.episodeToken, "UTF-8")}"
        val text = runCatching {
            fetchText {
                app.get(
                    infoUrl,
                    headers = headers + mapOf("Referer" to playbackData.pageUrl),
                ).text
            }
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val grabber = json.optNullableString("grabber") ?: return false
        val target = json.optNullableString("target").orEmpty()

        if (target.contains("listeamed.net", ignoreCase = true) ||
            grabber.contains("listeamed.net", ignoreCase = true)
        ) {
            return runCatching {
                loadExtractor(grabber, animeWorldUrl, subtitleCallback, callback)
            }.getOrDefault(false)
        }

        emitDirectVideoLink(
            source = "AnimeWorld",
            name = animeSourceDisplayName("AnimeWorld", playbackData.label),
            url = grabber,
            referer = animeWorldUrl,
            callback = callback,
        )
        return true
    }

    private suspend fun loadAnimeSaturnLink(
        playbackData: AnimeSaturnPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            fetchText {
                app.get(
                    playbackData.watchUrl,
                    headers = headers,
                ).text
            }
        }.getOrNull() ?: return false

        val doc = Jsoup.parse(html, playbackData.watchUrl)
        val embedUrl = doc.selectFirst("iframe#watch-iframe[src], iframe[src*='play.saturncdn.net'][src]")
            ?.attr("src")
            ?.takeIf(String::isNotBlank)
            ?.let { absoluteProviderUrl(animeSaturnUrl, it) }
            ?: Regex(""""initialVideoUrl"\s*:\s*"([^"]+)"""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
            ?: return false

        val videoUrl = getAnimeSaturnVideoUrl(embedUrl) ?: return false
        if (videoUrl.contains("youtube.com/embed/", ignoreCase = true)) {
            return runCatching {
                loadExtractor(videoUrl, animeSaturnUrl, subtitleCallback, callback)
            }.getOrDefault(false)
        }

        emitDirectVideoLink(
            source = "AnimeSaturn",
            name = animeSourceDisplayName("AnimeSaturn", playbackData.label),
            url = videoUrl,
            referer = embedUrl,
            callback = callback,
        )
        return true
    }

    private suspend fun getAnimeSaturnVideoUrl(embedUrl: String): String? {
        val match = Regex("""/embed/(\d+)\?token=([^&]+)&expires=(\d+)""").find(embedUrl) ?: return null
        val id = match.groupValues.getOrNull(1) ?: return null
        val token = match.groupValues.getOrNull(2) ?: return null
        val expires = match.groupValues.getOrNull(3) ?: return null
        val baseUrl = embedUrl.substringBefore("/embed/")
        val playlistUrl = "$baseUrl/embed/$id/playlist?token=$token&expires=$expires"
        val text = runCatching {
            fetchText {
                app.get(
                    playlistUrl,
                    headers = headers + mapOf("Referer" to embedUrl),
                ).text
            }
        }.getOrNull() ?: return null
        val encoded = runCatching { JSONObject(text).optNullableString("d") }.getOrNull() ?: return null
        return decodeAnimeSaturnPayload(encoded, token)?.let { decoded ->
            if (decoded.startsWith("youtube/")) {
                "https://www.youtube.com/embed/${decoded.removePrefix("youtube/")}"
            } else {
                decoded
            }
        }?.takeIf(String::isNotBlank)
    }

    private fun decodeAnimeSaturnPayload(encoded: String, key: String): String? {
        if (key.isBlank()) return null
        val decodedBytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val output = ByteArray(decodedBytes.size)
        decodedBytes.forEachIndexed { index, byte ->
            output[index] = (byte.toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
        }
        return String(output, Charsets.UTF_8)
    }

    private suspend fun emitDirectVideoLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(
            newExtractorLink(
                source = source,
                name = name,
                url = url,
                type = INFER_TYPE,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun loadVixCloudLinks(
        playbackData: StreamingCommunityPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val iframeSrc = runCatching {
            val html = fetchText {
                app.get(
                    playbackData.iframeUrl,
                ).body.string()
            }
            Jsoup.parse(html, playbackData.iframeUrl)
                .selectFirst("iframe")
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
        if (iframeSrc.isNullOrBlank()) return false

        return runCatching {
            StreamCenterVixCloudExtractor(
                sourceName = "VixCloud",
                displayName = "StreamingCommunity - VixCloud",
            ).getUrl(
                url = iframeSrc,
                referer = streamingCommunityRootUrl,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun loadVixSrcLinks(
        playbackData: StreamingCommunityPlaybackData,
        fallbackTmdbId: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val vixSrcUrl = buildStreamingCommunityVixSrcUrl(playbackData, fallbackTmdbId)
        if (vixSrcUrl.isNullOrBlank()) return false

        return runCatching {
            StreamCenterVixSrcExtractor().getUrl(
                url = vixSrcUrl,
                referer = "$vixSrcBaseUrl/",
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun loadVidxGoLinks(
        playbackData: StreamingCommunityPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val rawImdbId = playbackData.imdbId ?: return false
        val imdbNumber = rawImdbId.removePrefix("tt")

        val targetUrl = if (playbackData.type == "movie") {
            "$vidxGoUrl/$imdbNumber"
        } else {
            val season = playbackData.seasonNumber ?: return false
            val episode = playbackData.episodeNumber ?: return false
            "$vidxGoUrl/t/$imdbNumber/$season/$episode"
        }

        return runCatching {
            StreamCenterVidxGoExtractor().getUrl(
                url = targetUrl,
                referer = "$vidxGoUrl/",
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }.isSuccess
    }

    private fun buildStreamingCommunityVixSrcUrl(
        playbackData: StreamingCommunityPlaybackData,
        fallbackTmdbId: Int?,
    ): String? {
        val tmdbId = playbackData.tmdbId ?: fallbackTmdbId ?: return null
        return if (playbackData.type == "movie") {
            "$vixSrcBaseUrl/movie/$tmdbId"
        } else {
            val seasonNumber = playbackData.seasonNumber ?: return null
            val episodeNumber = playbackData.episodeNumber ?: return null
            "$vixSrcBaseUrl/tv/$tmdbId/$seasonNumber/$episodeNumber"
        }
    }

    private fun extractTmdbId(url: String): String? {
        return TMDB_MEDIA_URL_REGEX.find(url)
            ?.groupValues
            ?.getOrNull(2)
            ?.toIntOrNull()
            ?.toString()
    }

    companion object {
        const val SEARCH_SECTION_MAIN = "main"
        const val SEARCH_SECTION_MOVIES = "movies"
        const val SEARCH_SECTION_SERIES = "series"
        const val SEARCH_SECTION_ANIME = "anime"
        const val SEARCH_SECTION_LIVE = "live"

        private val ANIME_SYNC_NAMES = setOf(
            SyncIdName.Anilist,
            SyncIdName.MyAnimeList,
            SyncIdName.Kitsu,
            SyncIdName.Simkl,
        )
        private val checkedSourceDomains = mutableSetOf<String>()
        private val activeInstances = Collections.newSetFromMap(
            WeakHashMap<StreamCenter, Boolean>(),
        )

        fun resetSourceDomainChecks() {
            synchronized(checkedSourceDomains) { checkedSourceDomains.clear() }
        }

        fun resetRuntimeConfiguration() {
            resetSourceDomainChecks()
            val instances = synchronized(activeInstances) { activeInstances.toList() }
            instances.forEach { it.clearRuntimeConfiguration() }
        }

        private const val SC_SEARCH_PAGE_SIZE = 60
        private const val SEARCH_RELEVANCE_MIN_SCORE = 55
        private const val SEARCH_ALTERNATIVE_TITLE_QUERY_LIMIT = 3
        private const val SEARCH_ALTERNATIVE_TITLE_PENALTY = 8
        private const val SEARCH_BRIDGED_TITLE_MIN_SCORE = 95
        private const val TRACKING_PROVIDER_PAGE_SIZE = 30
        private const val AU_ARCHIVE_BATCH_SIZE = 30
        private const val RANDOM_HOME_CANDIDATE_FACTOR = 3L
        private const val AU_ARCHIVE_QUERY_LIMIT = 8
        private const val AU_ARCHIVE_QUERY_LIMIT_PERFORMANCE = 4
        private const val AW_DETAIL_CANDIDATE_LIMIT = 16
        private const val AW_DETAIL_CANDIDATE_LIMIT_PERFORMANCE = 8
        private const val ANIMESATURN_DETAIL_CANDIDATE_LIMIT = 36
        private const val ANIMESATURN_DETAIL_CANDIDATE_LIMIT_PERFORMANCE = 18
        private const val ANIME_SEARCH_QUERY_LIMIT = 12
        private const val ANIME_SEARCH_QUERY_LIMIT_PERFORMANCE = 6
        private const val SOURCE_GROUP_TIMEOUT_MS = 15_000L
        private const val SOURCE_GROUP_TIMEOUT_PERFORMANCE_MS = 10_000L
        private const val STREMIO_KITSU_RESOLUTION_TIMEOUT_MS = 6_000L
        private const val ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS = 5_000L
        private const val STREMIO_ADDON_TIMEOUT_MS = 45_000L
        private const val TMDB_ANIME_EPISODE_METADATA_TIMEOUT_MS = 20_000L
        private const val STREMIO_ADDON_CONCURRENCY = 4
        private val YEAR_REGEX = Regex("""\b(?:18|19|20|21)\d{2}\b""")
        private val IMDB_ID_REGEX = Regex("""tt\d{5,}""", RegexOption.IGNORE_CASE)
        private val MAGNET_INFO_HASH_REGEX = Regex(
            """(?:^|[?&])xt=urn:btih:([^&]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val MAGNET_FILE_INDEX_REGEX = Regex(
            """(?:^|[?&])index=(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        private val TMDB_MEDIA_URL_REGEX = Regex(
            """(?:https?://(?:www\.)?themoviedb\.org/)?(?:[a-z]{2}(?:-[a-z]{2})?/)?(movie|tv)/(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        private val TMDB_IMDB_JSON_REGEX = Regex(
            """[\"'](?:imdb_id|imdbId)[\"']\s*:\s*[\"'](tt\d{5,})[\"']""",
            RegexOption.IGNORE_CASE,
        )

        suspend fun checkApisAvailability(
            sharedPref: SharedPreferences?,
            onProgress: suspend (
                name: String,
                isRunning: Boolean,
                result: Boolean?,
                detail: String?,
            ) -> Unit = { _, _, _, _ -> },
        ): List<Pair<String, Boolean>> = StreamCenterAvailabilityChecker.check(sharedPref, onProgress)

    }
}
