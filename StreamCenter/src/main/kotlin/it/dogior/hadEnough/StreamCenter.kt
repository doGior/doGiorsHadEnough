package it.dogior.hadEnough

import android.content.SharedPreferences
import android.util.Base64
import it.dogior.hadEnough.catalog.StreamCenterSearchFallback
import it.dogior.hadEnough.tracking.StreamCenterSimklTracking
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
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.VPNStatus
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
import it.dogior.hadEnough.cache.CachedActor
import it.dogior.hadEnough.cache.CachedEpisode
import it.dogior.hadEnough.cache.CachedMediaEntry
import it.dogior.hadEnough.cache.CachedSearchItem
import it.dogior.hadEnough.cache.CachedSeason
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import it.dogior.hadEnough.anime.metadata.AnimeMetadataHttpClient
import it.dogior.hadEnough.anime.metadata.AnimeSeasonInfo
import it.dogior.hadEnough.anime.metadata.AnimeSeriesClient
import it.dogior.hadEnough.anime.metadata.AnimeSeasonRoutes
import it.dogior.hadEnough.anime.metadata.AnimeSeasonEpisodeMetadata
import it.dogior.hadEnough.anime.metadata.groupAnimeSeasons
import it.dogior.hadEnough.anime.metadata.selectAnimeSeasonEpisode
import it.dogior.hadEnough.anime.metadata.AnimeDisplayTitles
import it.dogior.hadEnough.anime.metadata.buildAnimeEpisodes
import it.dogior.hadEnough.anime.metadata.AnimeJapaneseTitleHints
import it.dogior.hadEnough.anime.metadata.AnimeJapaneseTitleResolver
import it.dogior.hadEnough.anime.metadata.KitsuMetadataClient
import it.dogior.hadEnough.anime.metadata.TmdbAnimeEpisodeMetadataClient
import it.dogior.hadEnough.anime.metadata.TmdbArtwork
import it.dogior.hadEnough.anime.metadata.TmdbLogoClient
import it.dogior.hadEnough.catalog.TmdbSeriesMetadata
import it.dogior.hadEnough.anime.source.absoluteProviderUrl
import it.dogior.hadEnough.anime.source.sourceTitleDedupKey
import it.dogior.hadEnough.anime.source.sourceTitleScore
import it.dogior.hadEnough.anime.source.cleanAnimeUnityTitle
import it.dogior.hadEnough.anime.source.displayTitle
import it.dogior.hadEnough.anime.source.isDub
import it.dogior.hadEnough.anime.source.AnimeWorldSourceClient
import it.dogior.hadEnough.anime.source.AnimeSaturnSourceClient
import it.dogior.hadEnough.anime.source.AnimeUnitySourceClient
import it.dogior.hadEnough.cache.nextCalendarDayStartMillis
import it.dogior.hadEnough.cache.StreamCenterHomeCalendarCache
import it.dogior.hadEnough.anime.metadata.JikanMetadataClient
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.playback.PlaybackSourceGroup
import it.dogior.hadEnough.playback.StreamCenterSourceOrder
import it.dogior.hadEnough.iptv.StreamCenterIptv
import it.dogior.hadEnough.serie_movie.StreamingCommunityClient
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.cleanMetadataEpisodeTitle
import it.dogior.hadEnough.util.parseMetadataDate
import it.dogior.hadEnough.util.parseMetadataRuntime
import it.dogior.hadEnough.util.runCatchingCancellable
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.normalizeTrailerUrl
import it.dogior.hadEnough.util.youtubeTrailerUrl
import it.dogior.hadEnough.util.cleanTmdbEpisodeDescription
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
import kotlinx.coroutines.currentCoroutineContext
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

class StreamCenter internal constructor(
    internal val sharedPref: SharedPreferences? = null,
    internal val searchSection: String = SEARCH_SECTION_MAIN,
    internal val catalogDefinition: StreamCenterCatalogDefinition? = null,
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
    override val providerType = ProviderType.MetaProvider
    override val vpnStatus: VPNStatus
        get() = if (instanceCanResolveTorrents && StreamCenterPlugin.isTorrentEnabled(sharedPref)) {
            VPNStatus.Torrent
        } else {
            VPNStatus.None
        }
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = SEQUENTIAL_MAIN_PAGE_DELAY_MS
    override var sequentialMainPageScrollDelay = SEQUENTIAL_MAIN_PAGE_SCROLL_DELAY_MS
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

    internal val instanceCanResolveTorrents = supportedTypes.any { it != TvType.Live }

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

    internal val tmdbLanguage = "it-IT"
    internal val animeMarker = "streamcenter_media=anime"
    internal val animeAnilistParam = "streamcenter_anilist"
    internal val animeMalParam = "streamcenter_mal"
    internal val animeVariantParam = "streamcenter_variant"
    internal val anilistOnlyPath = "/anilist/"
    internal val malOnlyPath = "/mal/"
    internal val scHomePath = "/sc/"
    internal val trackingHomePath = "/tracking/"
    internal val scHomeTypeParam = "streamcenter_sc_type"
    internal val animeUnityUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMEUNITY }
    internal val animeWorldUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMEWORLD }
    internal val animeSaturnUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMESATURN }
    internal val streamingCommunityRootUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_STREAMINGCOMMUNITY } + "/"

    internal val streamingCommunityMainUrl: String
        get() = "${streamingCommunityRootUrl}it"
    internal val vidxGoUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_VIDXGO)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_VIDXGO }
    internal val vixSrcBaseUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_VIXSRC)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_VIXSRC }
    internal val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )
    internal val tmdbCatalog = StreamCenterTmdbCatalog(headers)
    internal val tmdbSearchFallback = StreamCenterSearchFallback<SearchResponse>()
    internal val myAnimeListCatalog = StreamCenterMyAnimeListCatalog(headers)
    internal val aniListCatalog by lazy {
        StreamCenterAniListCatalog(aniListMetadataClient) {
            StreamCenterPlugin.getAnimeCardTitle(sharedPref)
        }
    }
    internal val kitsuCatalog by lazy {
        StreamCenterKitsuCatalog(kitsuMetadataClient) {
            StreamCenterPlugin.getAnimeCardTitle(sharedPref)
        }
    }
    internal val simklCatalog = StreamCenterSimklCatalog()
    internal val animeSeriesClient by lazy {
        AnimeSeriesClient(aniListMetadataClient::execute) { StreamCenterMediaCache.isEnabled(sharedPref) }
    }
    internal val animeSeasonEpisodeMetadata by lazy {
        AnimeSeasonEpisodeMetadata(this, aniZipMetadataClient, tmdbAnimeEpisodeMetadataClient) {
            StreamCenterMediaCache.isEnabled(sharedPref)
        }
    }

    internal fun animeSeasonCards(items: List<SearchResponse>): List<SearchResponse> {
        if (items.isEmpty()) return items
        val groupSeasons = StreamCenterPlugin.shouldGroupAnimeSeasons(sharedPref)
        return items.map { item ->
            if (item.apiName == name) AnimeSeasonRoutes.card(item, groupSeasons) else item
        }
    }
    internal val searchTitleAliases = ConcurrentHashMap<String, List<String>>()
    internal val pendingCardProvenance = Collections.synchronizedMap(
        IdentityHashMap<LoadResponse, CardProvenance>(),
    )
    internal val stremioCatalogClient: StreamCenterStremioCatalog? by lazy {
        catalogDefinition?.let { definition ->
            definition.stremioAddon?.let { addon ->
                StreamCenterStremioCatalog(addon, definition.key)
            }
        }
    }
    internal val iptvCatalogClient: StreamCenterIptvCatalog? by lazy {
        catalogDefinition?.takeIf { StreamCenterCatalogs.isIptvCatalogKey(it.key) }
            ?.let { StreamCenterIptvCatalog(StreamCenterCatalogs.iptvPlaylistKey(it.key)) }
    }
    internal val catalogClient: StreamCenterCatalog? by lazy {
        stremioCatalogClient ?: iptvCatalogClient ?: when (catalogDefinition?.key) {
            "tmdb" -> tmdbCatalog
            "anilist" -> aniListCatalog
            "myanimelist" -> myAnimeListCatalog
            "kitsu" -> kitsuCatalog
            "simkl" -> simklCatalog
            else -> null
        }
    }
    internal val importedCatalogClients = ConcurrentHashMap<StreamCenterCatalogDefinition, StreamCenterCatalog>()

    internal fun importedCatalogClient(definition: StreamCenterCatalogDefinition): StreamCenterCatalog? {
        importedCatalogClients[definition]?.let { return it }
        val client = definition.stremioAddon?.let { StreamCenterStremioCatalog(it, definition.key) }
            ?: if (StreamCenterCatalogs.isIptvCatalogKey(definition.key)) {
                StreamCenterIptvCatalog(StreamCenterCatalogs.iptvPlaylistKey(definition.key))
            } else when (definition.key) {
                "tmdb" -> tmdbCatalog
                "anilist" -> aniListCatalog
                "myanimelist" -> myAnimeListCatalog
                "kitsu" -> kitsuCatalog
                "simkl" -> simklCatalog
                else -> null
            }
        return client?.let { importedCatalogClients.putIfAbsent(definition, it) ?: it }
    }
    internal val animeMetadataHttpClient = AnimeMetadataHttpClient()
    internal val aniZipMetadataClient = AniZipMetadataClient(animeMetadataHttpClient)
    internal val kitsuMetadataClient = KitsuMetadataClient(animeMetadataHttpClient)
    internal val jikanMetadataClient = JikanMetadataClient(animeMetadataHttpClient)
    internal val tmdbAnimeEpisodeMetadataClient = TmdbAnimeEpisodeMetadataClient(
        headers = headers,
        cacheDirectory = { StreamCenterPlugin.activeContext?.cacheDir },
    )
    internal val aniListMetadataClient = AniListMetadataClient(
        performanceMode = { performanceMode },
        minRequestIntervalMs = { StreamCenterPlugin.getAnilistMinIntervalMs(sharedPref) },
    )
    internal val animeJapaneseTitleResolver = AnimeJapaneseTitleResolver(
        kitsuMetadataClient = kitsuMetadataClient,
        aniListMetadataClient = aniListMetadataClient,
        jikanMetadataClient = jikanMetadataClient,
    )
    internal val animeWorldSourceClient = AnimeWorldSourceClient(
        baseUrl = { animeWorldUrl },
        headers = headers,
        queryLimit = { animeSearchQueryLimit },
        detailCandidateLimit = { awDetailCandidateLimit },
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD) },
    )
    internal val animeSaturnSourceClient = AnimeSaturnSourceClient(
        baseUrl = { animeSaturnUrl },
        headers = headers,
        queryLimit = { animeSearchQueryLimit },
        detailCandidateLimit = { animeSaturnDetailCandidateLimit },
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN) },
    )
    internal val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val performanceMode: Boolean
        get() = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref)
    internal val showCardScores: Boolean
        get() = !performanceMode && StreamCenterPlugin.shouldShowHomeScore(sharedPref)
    internal val catalogIsActive: Boolean
        get() = catalogDefinition?.let { catalog ->
            StreamCenterPlugin.isHomeCategoryEnabled(sharedPref, StreamCenterCatalogs.CATEGORY_KEY) &&
                StreamCenterCatalogs.isConfigured(sharedPref, catalog)
        } ?: false
    internal val sourceGroupTimeoutMs: Long
        get() = if (performanceMode) SOURCE_GROUP_TIMEOUT_PERFORMANCE_MS else SOURCE_GROUP_TIMEOUT_MS
    internal val animeSearchQueryLimit: Int
        get() = if (performanceMode) ANIME_SEARCH_QUERY_LIMIT_PERFORMANCE else ANIME_SEARCH_QUERY_LIMIT
    internal val auArchiveQueryLimit: Int
        get() = if (performanceMode) AU_ARCHIVE_QUERY_LIMIT_PERFORMANCE else AU_ARCHIVE_QUERY_LIMIT
    internal val awDetailCandidateLimit: Int
        get() = if (performanceMode) AW_DETAIL_CANDIDATE_LIMIT_PERFORMANCE else AW_DETAIL_CANDIDATE_LIMIT
    internal val animeSaturnDetailCandidateLimit: Int
        get() = if (performanceMode) {
            ANIMESATURN_DETAIL_CANDIDATE_LIMIT_PERFORMANCE
        } else {
            ANIMESATURN_DETAIL_CANDIDATE_LIMIT
        }
    internal val animeUnitySourceClient = AnimeUnitySourceClient(
        sharedPref = sharedPref,
        baseUrl = { animeUnityUrl },
        archiveQueryLimit = { auArchiveQueryLimit },
        posterResolver = ::animeUnityPoster,
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) },
    )

    internal fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore("/").substringBefore(":")
    }
    internal val streamingCommunityClient = StreamingCommunityClient(
        sharedPref = sharedPref,
        rootUrl = { streamingCommunityRootUrl },
        mainUrl = { streamingCommunityMainUrl },
        defaultHeaders = headers,
        ensureDomain = { ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) },
    )

    init {
        synchronized(activeInstances) { activeInstances += this }
    }

    internal fun clearRuntimeConfiguration() {
        animeUnitySourceClient.resetSession()
        streamingCommunityClient.resetSession()
    }

    internal val catchUp = StreamCenterCatchUp

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
        val response = getUngroupedMainPage(page, request)
        return response.copy(items = response.items.map { it.copy(list = animeSeasonCards(it.list)) })
    }

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

    internal fun JSONArray.toStreamingCommunityHomeResponses(
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

    internal fun JSONObject.toStreamingCommunityHomeResponse(
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

    internal fun JSONArray.streamingCommunityPosterFilename(): String? {
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
        return animeSeasonCards(fetchSearchResults(query, 1).first)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = fetchSearchResults(query, page)
        return newSearchResponseList(animeSeasonCards(items), hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return animeSeasonCards(fetchSearchResults(query, 1, includeFallbacks = false).first)
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        StreamCenterVpnGuard.requireInternetAccess(sharedPref)
        val rawId = id.substringBefore("/").trim().toIntOrNull() ?: return null
        val numericId = if (name == SyncIdName.Kitsu) resolveKitsuLibraryMediaId(rawId) else rawId
        StreamCenterTrackingOverrides.urlForManualId(sharedPref, this.name, name, numericId.toString())
            ?.let { return it }
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

    internal fun JSONObject.optTrackingText(key: String): String? = optString(key)
        .trim()
        .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    internal fun JSONObject.streamingCommunitySearchTitleAliases(): List<String> = buildList {
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

    internal fun AnimeUnityAnime.searchTitleAliases(metadataAliases: Collection<String>): List<String> {
        return (listOf(titleIt, titleEng, title, displayTitle()) + metadataAliases)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy(::sourceTitleDedupKey)
    }

    internal fun <T : LoadResponse> T.withCardProvenance(
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

    override suspend fun load(url: String): LoadResponse {
        StreamCenterVpnGuard.requireInternetAccess(sharedPref)
        val original = if (AnimeSeasonRoutes.isGrouped(url)) {
            AnimeSeasonRoutes.unwrap(url) ?: error("Collegamento stagioni anime non valido")
        } else url
        val selected = loadSingleMedia(original)
        val trackingKey = selected.url
        val detectedTrackingIds = runCatching { StreamCenterTrackingOverrides.detectedIds(selected) }
            .getOrDefault(emptyMap())
        applyManualTrackingIds(selected, trackingKey)
        if (!StreamCenterPlugin.shouldGroupAnimeSeasons(sharedPref) || selected !is AnimeLoadResponse ||
            selected.type == TvType.AnimeMovie) {
            rememberTrackingIds(trackingKey, selected, detectedTrackingIds, groupedSeasons = false)
            return selected.also(catchUp::remember)
        }
        rememberTrackingIds(trackingKey, selected, detectedTrackingIds, groupedSeasons = true)
        val anilistId = selected.getAniListId()?.toIntOrNull()?.takeIf { it > 0 }
        val malId = selected.getMalId()?.toIntOrNull()?.takeIf { it > 0 }
        val series = animeSeriesClient.resolve(anilistId, malId)
        val entries = series.entries
        val resolvedId = anilistId ?: entries.firstOrNull { malId != null && it.malId == malId }?.id
        val groupedUrl = AnimeSeasonRoutes.wrap(original)
        val relatedEpisodes = entries.filter { it.isSeries }.mapChunkedParallel(4) { release ->
            release.id to animeSeasonEpisodeMetadata.load(release)
        }.toMap()
        if (series.complete && StreamCenterMediaCache.isEnabled(sharedPref)) {
            StreamCenterMediaCache.rememberAnimeRelations(entries)
        }
        return logLoadedResponse(
            groupAnimeSeasons(selected, resolvedId, entries, groupedUrl, relatedEpisodes),
            "anime:stagioni", groupedUrl, attachExtensions = false,
        ).also(catchUp::remember)
    }

    internal suspend fun loadSingleMedia(url: String): LoadResponse {
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
            if (url.startsWith(StreamCenterIptv.ROUTE_PREFIX)) {
                return logLoadedResponse(loadIptvChannel(url), route, url)
            }
            if (url.contains(trackingHomePath)) return logLoadedResponse(loadTrackingLibraryItem(url), route, url)
            if (catalogDefinition.stremioAddon != null) return logLoadedResponse(loadStremioCatalogMedia(url), route, url)
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
                identityUrl = url,
            )
        }
        if (url.startsWith(StreamCenterIptv.ROUTE_PREFIX)) {
            return logLoadedResponse(loadIptvChannel(url), route, url)
        }
        if (kitsuCatalog.mediaId(url) != null) {
            return logLoadedResponse(loadKitsuMedia(url), route, url)
        }
        if (simklCatalog.mediaRoute(url) != null) {
            return logLoadedResponse(loadSimklMedia(url), route, url)
        }
        if (url.contains(trackingHomePath)) {
            return logLoadedResponse(loadTrackingLibraryItem(url), route, url)
        }
        if (url.contains(scHomePath)) {
            return logLoadedResponse(loadStreamingCommunityHomeTitle(url), route, url)
        }
        if (isAnilistOnlyUrl(url)) {
            val anilistId = extractAnilistIdFromText(url)
            val malId = parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return logLoadedResponse(loadAnilistMedia(anilistId, malId, cacheable = true), route, url)
        }
        if (isMalOnlyUrl(url)) {
            val malId = extractMalIdFromText(url)
                ?: parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return logLoadedResponse(loadAnilistMedia(null, malId, cacheable = true), route, url)
        }

        StreamCenterStremioCatalog.catalogKeyForUrl(url)?.let { key ->
            val definition = StreamCenterCatalogs.allCatalogs(sharedPref).firstOrNull { it.key == key }
                ?: error("Il catalogo Stremio non è più disponibile")
            return logLoadedResponse(
                loadStremioCatalogMedia(url, definition, importedCatalogClient(definition) as? StreamCenterStremioCatalog),
                "stremio", url,
            )
        }
        val actualUrl = normalizeTmdbUrl(url)
        if (actualUrl.contains(animeMarker)) {
            val selection = parseAnimeSelection(actualUrl)
            val anilistId = selection?.anilistId
            val malId = selection?.malId
            if (anilistId != null || malId != null) {
                return logLoadedResponse(loadAnilistMedia(anilistId, malId, cacheable = true), route, url)
            }
            error("Identificativo AniList o MyAnimeList mancante")
        }

        return logLoadedResponse(loadTmdbMedia(actualUrl, cacheable = true), route, url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StreamCenterLogger.logMenuError(
                action = "Apertura scheda degradata",
                throwable = error,
                metadata = mapOf(
                    "api" to name,
                    "rotta" to route,
                    "catalogo" to (catalogDefinition?.displayName ?: "nessuno"),
                    "destinazione" to url,
                ),
            )
            return minimalLoadResponse(url, route)
        }
    }

    internal suspend fun minimalLoadResponse(url: String, route: String): LoadResponse {
        val title = MINIMAL_LOAD_RESPONSE_TITLE
        return when {
            route in setOf("anime", "anilist", "myanimelist", "kitsu", "simkl") ->
                newAnimeLoadResponse(title, url, TvType.Anime) {}
            url.contains("/tv/") ->
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList<Episode>()) {}
            else ->
                newMovieLoadResponse(title, url, TvType.Movie, dataUrl = "") {}
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor? =
        StreamCenterVidxGoExtractor.videoInterceptor(extractorLink)
            ?: InstalledExtensionResolver.videoInterceptor(extractorLink)

    override suspend fun extractorVerifierJob(extractorData: String?) {
        if (currentCoroutineContext()[ExtensionCall.Key] == null) InstalledExtensionResolver.verify(extractorData)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = resolveStreamCenterLinks(data, isCasting, subtitleCallback, callback)

    internal fun Int.toDecimalScore(): String {
        return String.format(Locale.US, "%.1f", this / 10.0)
    }

    internal fun String.toDecimalScore(): String? {
        val value = replace(',', '.').toDoubleOrNull() ?: return null
        val normalized = if (value > 10.0) value / 10.0 else value
        return String.format(Locale.US, "%.1f", normalized)
    }

    internal fun AnimeLoadResponse.applyAnimeCatalogTitles(
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

    internal fun Element.extractImageUrl(): String? {
        val srcset = attr("srcset")
            .split(",")
            .lastOrNull()
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.startsWith("http") }

        return srcset ?: attr("src").takeIf { it.startsWith("http") }
    }

    internal fun List<Episode>.episodeKeys(): Set<Pair<Int, Int>> {
        return mapNotNull { episode ->
            val season = episode.season ?: return@mapNotNull null
            val number = episode.episode ?: return@mapNotNull null
            season to number
        }.toSet()
    }


    companion object {
        internal const val MINIMAL_LOAD_RESPONSE_TITLE = "Contenuto non disponibile"
        const val SEARCH_SECTION_MAIN = "main"
        const val SEARCH_SECTION_MOVIES = "movies"
        const val SEARCH_SECTION_SERIES = "series"
        const val SEARCH_SECTION_ANIME = "anime"
        const val SEARCH_SECTION_LIVE = "live"

        internal const val PLACEHOLDER_PROFILE_IMAGE =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk" +
                "YAAAAAYAAjCB0C8AAAAASUVORK5CYII="

        internal val ANIME_SYNC_NAMES = setOf(
            SyncIdName.Anilist,
            SyncIdName.MyAnimeList,
            SyncIdName.Kitsu,
            SyncIdName.Simkl,
        )
        internal val checkedSourceDomains = mutableSetOf<String>()
        internal val activeInstances = Collections.newSetFromMap(
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

        fun clearHomeCalendarCache() {
            StreamCenterHomeCalendarCache.clear()
        }

        internal const val SC_SEARCH_PAGE_SIZE = 60
        internal const val SEARCH_RELEVANCE_MIN_SCORE = 55
        internal const val SEARCH_ALTERNATIVE_TITLE_QUERY_LIMIT = 3
        internal const val SEARCH_ALTERNATIVE_TITLE_PENALTY = 8
        internal const val TRACKING_PROVIDER_PAGE_SIZE = 30
        internal const val AU_ARCHIVE_BATCH_SIZE = 30
        internal const val POPULAR_HOME_VARIANT_PARALLELISM = 4
        internal const val RANDOM_HOME_CANDIDATE_FACTOR = 3L
        internal const val AU_ARCHIVE_QUERY_LIMIT = 8
        internal const val AU_ARCHIVE_QUERY_LIMIT_PERFORMANCE = 4
        internal const val AW_DETAIL_CANDIDATE_LIMIT = 16
        internal const val AW_DETAIL_CANDIDATE_LIMIT_PERFORMANCE = 8
        internal const val ANIMESATURN_DETAIL_CANDIDATE_LIMIT = 36
        internal const val ANIMESATURN_DETAIL_CANDIDATE_LIMIT_PERFORMANCE = 18
        internal const val ANIME_SEARCH_QUERY_LIMIT = 12
        internal const val ANIME_SEARCH_QUERY_LIMIT_PERFORMANCE = 6
        internal const val SOURCE_GROUP_TIMEOUT_MS = 15_000L
        internal const val SOURCE_GROUP_TIMEOUT_PERFORMANCE_MS = 10_000L
        internal const val STREMIO_KITSU_RESOLUTION_TIMEOUT_MS = 6_000L
        internal const val ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS = 5_000L
        internal const val STREMIO_ADDON_TIMEOUT_MS = 45_000L
        internal const val TMDB_ANIME_EPISODE_METADATA_TIMEOUT_MS = 20_000L
        internal const val ANIME_SEARCH_ENRICHMENT_TIMEOUT_MS = 5_000L
        internal const val SEQUENTIAL_MAIN_PAGE_DELAY_MS = 300L
        internal const val SEQUENTIAL_MAIN_PAGE_SCROLL_DELAY_MS = 300L
        internal val ANILIST_RECOMMENDATIONS_QUERY = """
            query(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                recommendations(sort: RATING_DESC, perPage: 24) {
                  nodes {
                    mediaRecommendation {
                      id
                      idMal
                      format
                      averageScore
                      title { romaji english native }
                      coverImage { large medium }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        internal const val STREMIO_ADDON_CONCURRENCY = 4
        internal val NETWORK_ALT_REGEX = Regex("""\bda\s+(.+?)\s*\.{3}\s*$""")
        internal val WATCH_PROVIDER_REGEX = Regex("""^.*\son\s+(.+)$""", RegexOption.IGNORE_CASE)
        internal val IMDB_ID_REGEX = Regex("""tt\d{5,}""", RegexOption.IGNORE_CASE)
        internal val MAGNET_INFO_HASH_REGEX = Regex(
            """(?:^|[?&])xt=urn:btih:([^&]+)""",
            RegexOption.IGNORE_CASE,
        )
        internal val MAGNET_FILE_INDEX_REGEX = Regex(
            """(?:^|[?&])index=(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        internal val TMDB_MEDIA_URL_REGEX = Regex(
            """(?:https?://(?:www\.)?themoviedb\.org/)?(?:[a-z]{2}(?:-[a-z]{2})?/)?(movie|tv)/(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        internal val TMDB_IMDB_JSON_REGEX = Regex(
            """[\"'](?:imdb_id|imdbId)[\"']\s*:\s*[\"'](tt\d{5,})[\"']""",
            RegexOption.IGNORE_CASE,
        )
    }
}
