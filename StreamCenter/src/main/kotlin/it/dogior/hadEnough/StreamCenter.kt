package it.dogior.hadEnough

import android.content.SharedPreferences
import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import it.dogior.hadEnough.anime.metadata.KitsuMetadataClient
import it.dogior.hadEnough.anime.source.absoluteProviderUrl
import it.dogior.hadEnough.anime.source.buildAnimeSourceTitleCandidates
import it.dogior.hadEnough.anime.source.exactAnimeTitleKeys
import it.dogior.hadEnough.anime.source.sourceTitleDedupKey
import it.dogior.hadEnough.anime.source.sourceTitleScore
import it.dogior.hadEnough.anime.source.cleanAnimeUnityTitle
import it.dogior.hadEnough.anime.source.contentKey
import it.dogior.hadEnough.anime.source.displayTitle
import it.dogior.hadEnough.anime.source.isDub
import it.dogior.hadEnough.anime.source.matches
import it.dogior.hadEnough.anime.source.titleKeys
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
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.normalizeAnimeEpisodeNumber
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.parseWholeAnimeEpisodeNumber
import it.dogior.hadEnough.util.sortedEpisodeNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Collections
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
        SEARCH_SECTION_MOVIES -> "StreamCenter Film"
        SEARCH_SECTION_SERIES -> "StreamCenter Serie TV"
        SEARCH_SECTION_ANIME -> "StreamCenter Anime"
        SEARCH_SECTION_LIVE -> "StreamCenter TV"
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
    private val catalogClient: StreamCenterCatalog? by lazy {
        when (catalogDefinition?.key) {
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
    private val animeEpisodeMetadataMerger = AnimeEpisodeMetadataMerger(
        kitsuClient = kitsuMetadataClient,
        jikanClient = jikanMetadataClient,
    )
    private val aniListMetadataClient = AniListMetadataClient(
        performanceMode = { performanceMode },
        minRequestIntervalMs = { StreamCenterPlugin.getAnilistMinIntervalMs(sharedPref) },
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
        catalogDefinition?.takeIf { catalogIsActive }?.let { catalog ->
            val section = StreamCenterCatalogs.sectionForData(catalog, request.data)
            val catalogPage = section?.let {
                if (it.trackingServiceKey != null) {
                    catalogTrackingConfig(it)?.let { config ->
                        runCatching { fetchTrackingListHomePage(config, page) }.getOrNull()
                    }
                } else {
                    runCatching { catalogClient?.section(this, it, page, showCardScores) }.getOrNull()
                }
            } ?: StreamCenterCatalogPage(emptyList(), false)
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
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

        val items = runCatching {
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
        }.getOrDefault(emptyList())

        val hasNext = (data == "au:popular" || data.startsWith("au:archive:")) && items.size >= limit
        return newHomePageResponse(
            HomePageList(
                name = request.name,
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

    private fun animeHomeRoutingUrl(malId: Int?, anilistId: Int?): String? {
        return when {
            anilistId != null -> markAnilistUrl(anilistId, malId)
            malId != null -> markMalOnlyUrl(malId)
            else -> null
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

    private suspend fun fetchStreamingCommunityRecommendations(
        title: StreamingCommunityTitle,
        limit: Int = 20,
    ): List<SearchResponse> {
        val type = if (title.type == "tv") TvType.TvSeries else TvType.Movie
        val related = runCatching { searchStreamingCommunity(title.name, 1).first }
            .getOrDefault(emptyList())
        val trending = runCatching {
            fetchStreamingCommunityHome("sc:${title.type}:trending", limit + 1, showScore = false)
        }.getOrDefault(emptyList())
        return (related + trending)
            .filter { it.type == type }
            .filterNot { it.url.contains("$scHomePath${title.id}-") }
            .distinctBy { it.url }
            .take(limit)
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
        val empty = emptyList<SearchResponse>() to false
        if (query.isBlank() || page < 1) return@coroutineScope empty
        if (catalogDefinition != null) {
            if (!catalogIsActive) return@coroutineScope empty
            val catalogPage = runCatching { catalogClient?.search(this@StreamCenter, query, page, showCardScores) }
                .getOrNull()
                ?: StreamCenterCatalogPage(emptyList(), false)
            return@coroutineScope catalogPage.items to catalogPage.hasNext
        }
        when (searchSection) {
            SEARCH_SECTION_MAIN -> {
                fetchHomeSearchResults(query, page)
            }
            SEARCH_SECTION_MOVIES, SEARCH_SECTION_SERIES -> {
                val (scItems, scHasNext) = runCatching { searchStreamingCommunity(query, page) }
                    .getOrDefault(empty)
                val wantedType = if (searchSection == SEARCH_SECTION_MOVIES) {
                    TvType.Movie
                } else {
                    TvType.TvSeries
                }
                scItems.filter { it.type == wantedType }.distinctBy { it.url } to scHasNext
            }
            SEARCH_SECTION_ANIME -> {
                runCatching { searchAnimeUnity(query, page) }.getOrDefault(empty)
            }
            SEARCH_SECTION_LIVE -> {
                if (page > 1) return@coroutineScope empty
                runCatching { searchIptv(query) to false }.getOrDefault(empty)
            }
            else -> {
                empty
            }
        }
    }

    private suspend fun fetchHomeSearchResults(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> = supervisorScope {
        val empty = emptyList<SearchResponse>() to false
        val streamingCommunity = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)) {
            async(Dispatchers.IO) {
                runCatching { searchStreamingCommunity(query, page) }.getOrDefault(empty)
            }
        } else {
            null
        }
        val animeUnity = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)) {
            async(Dispatchers.IO) {
                runCatching { searchAnimeUnity(query, page) }.getOrDefault(empty)
            }
        } else {
            null
        }
        val iptv: kotlinx.coroutines.Deferred<List<SearchResponse>>? = if (page == 1) {
            async(Dispatchers.IO) {
                runCatching { searchIptv(query) }.getOrDefault(emptyList())
            }
        } else {
            null
        }

        val (streamingCommunityItems, streamingCommunityHasNext) = streamingCommunity?.await() ?: empty
        val (animeItems, animeHasNext) = animeUnity?.await() ?: empty
        val iptvItems = iptv?.await().orEmpty()
        val items = (streamingCommunityItems + animeItems + iptvItems)
            .distinctBy { it.url }

        items to (streamingCommunityHasNext || animeHasNext)
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
        }
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
                if (seen.add(response.url)) add(response)
            }
        }
        return items to (titles.length() >= SC_SEARCH_PAGE_SIZE)
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
                newAnimeSearchResponse(cleanAnimeUnityTitle(anime.displayTitle()), url, type) {
                    this.posterUrl = animeUnityPoster(anime.imageUrl)
                    this.year = anime.year
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
            newAnimeSearchResponse(cleanAnimeUnityTitle(primary.displayTitle()), url, type) {
                this.posterUrl = variants.firstNotNullOfOrNull { animeUnityPoster(it.imageUrl) }
                this.year = variants.firstNotNullOfOrNull { it.year }
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

    override suspend fun load(url: String): LoadResponse {
        if (catalogDefinition != null) {
            check(catalogIsActive) { "Il Catalogo selezionato non è più attivo." }
            if (url.contains(trackingHomePath)) return loadTrackingLibraryItem(url)
            return when (catalogDefinition.key) {
                "tmdb" -> loadTmdbMedia(normalizeTmdbUrl(url), strictTmdbMetadata = true)
                "anilist" -> loadAniListCatalogMedia(url)
                "myanimelist" -> loadMyAnimeListMedia(url)
                "kitsu" -> loadKitsuMedia(url)
                "simkl" -> loadSimklMedia(url)
                else -> error("Catalogo non supportato")
            }
        }
        if (url.startsWith(StreamCenterIptv.ROUTE_PREFIX)) {
            return loadIptvChannel(url)
        }
        if (kitsuCatalog.mediaId(url) != null) {
            return loadKitsuMedia(url)
        }
        if (simklCatalog.mediaRoute(url) != null) {
            return loadSimklMedia(url)
        }
        if (url.contains(trackingHomePath)) {
            return loadTrackingLibraryItem(url)
        }
        if (url.contains(scHomePath)) {
            return loadStreamingCommunityHomeTitle(url)
        }
        if (isAnilistOnlyUrl(url)) {
            val anilistId = extractAnilistIdFromText(url)
            val malId = parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return loadAnilistMedia(anilistId, malId)
        }
        if (isMalOnlyUrl(url)) {
            val malId = extractMalIdFromText(url)
                ?: parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return loadAnilistMedia(null, malId)
        }

        val actualUrl = normalizeTmdbUrl(url)
        if (actualUrl.contains(animeMarker)) {
            val selection = parseAnimeSelection(actualUrl)
            val anilistId = selection?.anilistId
            val malId = selection?.malId
            if (anilistId != null || malId != null) {
                return loadAnilistMedia(anilistId, malId)
            }
            error("Identificativo AniList o MyAnimeList mancante")
        }

        return loadTmdbMedia(actualUrl)
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

    private fun providerTrackingRoute(
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
            "simkl" -> item.url.takeIf { simklCatalog.mediaRoute(it) != null }
                ?: item.syncId.toIntOrNull()?.let { id ->
                    val category = when (item.type) {
                        TvType.Movie -> "movies"
                        TvType.Anime, TvType.AnimeMovie, TvType.OVA -> "anime"
                        else -> "tv"
                    }
                    "https://simkl.com/$category/$id"
                }
            else -> null
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
            addStreamCenterTrackingId(service.syncIdName, mediaId)
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
        }
        return when (item.type) {
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
    }

    private suspend fun loadTmdbMedia(
        actualUrl: String,
        scHint: StreamingCommunityTitle? = null,
        strictTmdbMetadata: Boolean = false,
    ): LoadResponse {
        val doc = getTmdbDocument(actualUrl)
        val isTvSeries = actualUrl.contains("/tv/")
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
        val recommendations = if (strictTmdbMetadata) {
            runCatching { tmdbCatalog.recommendations(this, actualUrl, showCardScores) }.getOrDefault(emptyList())
        } else if (!performanceMode) {
            sc?.let { fetchStreamingCommunityRecommendations(it) }.orEmpty()
        } else {
            emptyList()
        }

        return if (isTvSeries) {
            val streamingCommunityEpisodes = streamingCommunityTitle
                ?.let { streamingCommunityClient.episodePayloads(it) }
                .orEmpty()
            val episodes = fetchEpisodes(
                doc = doc,
                actualUrl = actualUrl,
                streamingCommunityEpisodes = streamingCommunityEpisodes,
                stremioContext = stremioContext,
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
                    )
                }
            }
            val seasonNames = if (strictTmdbMetadata) {
                runCatching { fetchTmdbSeasonNames(actualUrl, episodes) }
                    .getOrDefault(buildAnimeSeasonData(episodes))
            } else {
                buildAnimeSeasonData(episodes)
            }
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
                    ),
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
                    ),
                )
                if (!performanceMode || strictTmdbMetadata) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(score)
                }
            }
        }
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
        val iptv = runCatching { JSONObject(data) }.getOrNull()
            ?.takeIf { it.optBoolean("streamcenterIptv") }
        if (iptv != null) {
            val streamUrl = iptv.optString("url").takeIf(String::isNotBlank) ?: return false
            val linkHeaders = buildMap {
                iptv.optString("userAgent").takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
                iptv.optString("referer").takeIf(String::isNotBlank)?.let { put("Referer", it) }
            }
            val resolved = resolveIptvStream(streamUrl, linkHeaders)
            callback(newExtractorLink(name, iptv.optString("name", "TV"), resolved.url, resolved.type) {
                quality = Qualities.Unknown.value
                headers = linkHeaders
            })
            return true
        }
        val playbackData = runCatching { parseJson<StreamCenterPlaybackData>(data) }.getOrNull()
        val emittedLinkKeys = ConcurrentHashMap.newKeySet<String>()
        val emittedSubtitleKeys = ConcurrentHashMap.newKeySet<String>()
        val emittedAnyLink = AtomicBoolean(false)
        val resultCallbackLock = Any()
        val uniqueCallback: (ExtractorLink) -> Unit = { link ->
            synchronized(resultCallbackLock) {
                if (emittedLinkKeys.add(sourceLinkDedupKey(link))) {
                    emittedAnyLink.set(true)
                    callback(link)
                }
            }
        }
        val uniqueSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            synchronized(resultCallbackLock) {
                if (emittedSubtitleKeys.add(subtitle.url.trim().substringBefore('#'))) {
                    subtitleCallback(subtitle)
                }
            }
        }
        val tasksBySource = linkedMapOf<String, MutableList<suspend () -> Boolean>>()
        val stremioTasks = linkedMapOf<String, suspend () -> Boolean>()
        fun addTask(sourceKey: String, task: suspend () -> Boolean) {
            tasksBySource.getOrPut(sourceKey) { mutableListOf() } += task
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

        playbackData?.streamingCommunity
            ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) }
            ?.let { streamingCommunityPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) {
                    loadStreamingCommunityLinks(
                        playbackData = streamingCommunityPlayback,
                        subtitleCallback = uniqueSubtitleCallback,
                        callback = uniqueCallback,
                    )
                }
        }

        playbackData?.stremio?.let { stremioContext ->
            activeStremioResolverAddons().forEach { addon ->
                stremioTasks[addon.key] = suspend {
                    withTimeoutOrNull(STREMIO_ADDON_TIMEOUT_MS) {
                        StreamCenterStremioAddonClient.load(
                            addon = addon,
                            context = stremioContext,
                            subtitleCallback = uniqueSubtitleCallback,
                            callback = uniqueCallback,
                            stopAfterFirstResult = performanceMode,
                        )
                    } ?: false
                }
            }
        }

        val priorityOrder = StreamCenterPlugin.getSourcePriorityOrder(sharedPref)
        val orderedKeys = tasksBySource.keys.sortedBy { key ->
            priorityOrder.indexOf(key).takeIf { it >= 0 } ?: priorityOrder.size
        }
        if (performanceMode) {
            val performanceKeys = (tasksBySource.keys + stremioTasks.keys)
                .distinct()
                .sortedBy { key ->
                    priorityOrder.indexOf(key).takeIf { it >= 0 } ?: priorityOrder.size
                }
            for (sourceKey in performanceKeys) {
                val sourceLoaded = stremioTasks[sourceKey]?.invoke() ?: withTimeoutOrNull(sourceGroupTimeoutMs) {
                    runParallelSourceTasks(tasksBySource[sourceKey].orEmpty())
                } ?: false
                if (sourceLoaded || emittedAnyLink.get()) return true
            }
            return emittedAnyLink.get()
        }
        return supervisorScope {
            val stremioDeferred = stremioTasks.values.toList().takeIf { it.isNotEmpty() }?.let { tasks ->
                async(Dispatchers.IO) {
                    runParallelSourceTasks(tasks, STREMIO_ADDON_CONCURRENCY)
                }
            }
            val nativeDeferred = orderedKeys.takeIf { it.isNotEmpty() }?.let { sourceKeys ->
                async(Dispatchers.IO) {
                    runParallelSourceTasks(
                        tasks = sourceKeys.map { sourceKey ->
                            suspend {
                                withTimeoutOrNull(sourceGroupTimeoutMs) {
                                    runParallelSourceTasks(tasksBySource[sourceKey].orEmpty())
                                } ?: false
                            }
                        },
                        maxConcurrency = NATIVE_SOURCE_CONCURRENCY,
                    )
                }
            }
            val nativeLoaded = nativeDeferred?.await() ?: false
            val stremioLoaded = stremioDeferred?.await() ?: false
            nativeLoaded || stremioLoaded || emittedAnyLink.get()
        }
    }

    private fun sourceLinkDedupKey(link: ExtractorLink): String = buildString {
        append(link.url.trim().substringBefore('#'))
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

    private suspend fun ensureUpdatedSourceDomain(prefKey: String) {
        if (!StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref)) return
        synchronized(checkedSourceDomains) {
            if (!checkedSourceDomains.add(prefKey)) return
        }
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

    private fun extractKitsuIdFromText(text: String): Int? {
        return Regex("""(?:kitsu\.(?:io|app)/anime/|/kitsu/)(\d+)""")
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

    private fun buildAnilistRecommendations(
        recommendations: List<AnilistRecommendation>,
    ): List<SearchResponse> {
        return recommendations.map { recommendation ->
            val type = if (recommendation.format.equals("MOVIE", ignoreCase = true)) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
            newAnimeSearchResponse(
                recommendation.title,
                markAnilistUrl(recommendation.anilistId, recommendation.malId),
                type,
            ) {
                this.posterUrl = recommendation.posterUrl
            }
        }
    }

    private suspend fun loadAnilistMedia(anilistId: Int?, malId: Int?): LoadResponse {
        val metadata = aniListMetadataClient.fetchMetadata(anilistId, malId)
            ?: error("Metadati AniList non trovati")
        val anilistEpisodes = buildAnilistEpisodes(metadata.episodeMetadata)
        val recommendations = buildAnilistRecommendations(metadata.recommendations)
        val resolvedAnilistId = metadata.anilistId
        val resolvedMalId = metadata.malId
        val isMovie = metadata.format.equals("MOVIE", ignoreCase = true)
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
        val resolvedSources = resolveAnimePlaybackSources(
            metadata = streamCenterMetadata,
            matchMetadata = matchMetadata,
            syncIds = sourceSyncIds,
            aniZipIds = resolvedAnilistId to resolvedMalId,
            includeAniZip = !performanceMode &&
                (!isMovie || animeTitlePreference == StreamCenterPlugin.ANIME_CARD_TITLE_ANIZIP),
        )
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val aniZipCatalog = resolvedSources.aniZipCatalog
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
        val contentRating = if (metadata.isAdult) "18+" else null

        return if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
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
                    ),
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
                    targetEpisodeCount = listOfNotNull(
                        metadata.episodes,
                        maxAnimeSourceEpisodeNumber(
                            animeUnitySources = animeUnitySources,
                            animeWorldSources = animeWorldSources,
                            animeSaturnSources = animeSaturnSources,
                        ),
                    ).maxOrNull(),
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
            ).ifEmpty {
                buildAnimeFallbackEpisodes(
                    metadata.episodes,
                    episodeMetadata,
                    episodeFallbackPoster.takeIf { !performanceMode },
                    stremioContext,
                )
            }
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
                    ),
                )
                if (!performanceMode) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(metadata.score)
                }
            }
        }
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
            includeAniZip = false,
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

        return if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
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
                    includeAniZip = false,
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

        return if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
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
                    includeAniZip = false,
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

        return if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                stremio = stremioContext,
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
                    includeAniZip = false,
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
            )
            return newMovieLoadResponse(media.title, media.url, media.type, dataUrl = playbackData.toJson()) {
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
        return if (isAnime) {
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
    }

    private suspend fun resolveAnimePlaybackSources(
        metadata: StreamCenterMetadata,
        matchMetadata: AnilistMetadata,
        syncIds: List<AnimeSyncIds>,
        aniZipIds: Pair<Int?, Int?>? = null,
        includeAniZip: Boolean,
    ): ResolvedLoadSources = coroutineScope {
        val animeUnityDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)) {
            async(Dispatchers.IO) {
                animeUnitySourceClient.fetchSources(metadata, matchMetadata, syncIds)
            }
        } else {
            null
        }
        val animeWorldDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) {
            async(Dispatchers.IO) {
                animeWorldSourceClient.fetchSources(metadata, matchMetadata, syncIds)
            }
        } else {
            null
        }
        val animeSaturnDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) {
            async(Dispatchers.IO) {
                animeSaturnSourceClient.fetchSources(metadata, matchMetadata, syncIds)
            }
        } else {
            null
        }
        val aniZipDeferred = if (includeAniZip && aniZipIds != null) {
            async(Dispatchers.IO) {
                runCatching { aniZipMetadataClient.fetch(aniZipIds.first, aniZipIds.second) }
                    .getOrDefault(AniZipEpisodeCatalog())
            }
        } else {
            null
        }
        ResolvedLoadSources(
            animeUnitySources = animeUnityDeferred?.await().orEmpty(),
            animeWorldSources = animeWorldDeferred?.await().orEmpty(),
            animeSaturnSources = animeSaturnDeferred?.await().orEmpty(),
            aniZipCatalog = aniZipDeferred?.await() ?: AniZipEpisodeCatalog(),
        )
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
    ): List<Episode> {
        if (episodeMetadata.isNotEmpty()) {
            return episodeMetadata.map { info ->
                newEpisode(
                    StreamCenterPlaybackData(
                        stremio = stremioContext.copy(
                            season = info.season ?: 1,
                            episode = info.episode,
                        ),
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
        return sequenceOf(
            doc.selectFirst("section.header.poster h2 a")?.text(),
            doc.selectFirst("section.header h2 a")?.text(),
            doc.selectFirst("section[class*=header] h2 a")?.text(),
            doc.selectFirst("h2 a[href*='/movie/'], h2 a[href*='/tv/']")?.text(),
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:title]")?.attr("content"),
            doc.selectFirst("meta[name=title]")?.attr("content"),
            doc.selectFirst("title")?.text(),
        ).mapNotNull(::cleanTmdbTitle).firstOrNull().orEmpty()
    }

    private fun cleanTmdbTitle(value: String?): String? {
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
        val recommendations = if (!performanceMode) {
            fetchStreamingCommunityRecommendations(title)
        } else {
            emptyList()
        }
        return if (title.type == "tv") {
            val episodes = buildStreamingCommunityEpisodes(
                streamingCommunityClient.episodePayloads(title),
                poster.takeIf { !performanceMode },
                stremioContext,
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
                    ),
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
                    ),
                )
                if (!performanceMode) addScore(title.score)
            }
        }
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
        val items = records.mapNotNull { anime ->
            val title = anime.titleIt ?: anime.titleEng ?: anime.title ?: return@mapNotNull null
            AnimeUnityHomeItem(
                title = cleanAnimeUnityTitle(title),
                type = anime.type,
                dub = anime.dub == 1,
                score = anime.score,
                imageUrl = anime.imageUrl,
                anilistId = anime.anilistId,
                malId = anime.malId,
                availableEpisodes = anime.episodesCount ?: anime.realEpisodesCount,
                episodeNumber = anime.episodesCount ?: anime.realEpisodesCount,
            )
        }
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
                card.selectFirst("[data-percent]")
                    ?.attr("data-percent")
                    ?.toDoubleOrNull()
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
    ): String {
        if (streamingCommunity == null && stremioContext == null) {
            return tmdbUrl
        }
        return StreamCenterPlaybackData(
            tmdbUrl = tmdbUrl.takeIf(String::isNotBlank),
            streamingCommunity = streamingCommunity,
            stremio = stremioContext,
        ).toJson()
    }

    private fun buildAnimeSourceEpisodes(
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
        episodeMetadata: List<Episode>,
        fallbackPoster: String? = null,
        stremioContext: StreamCenterStremioPlaybackContext,
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

    private suspend fun loadStreamingCommunityLinks(
        playbackData: StreamingCommunityPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tasks = mutableListOf<suspend () -> Boolean>()
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

        if (!iframeSrc.isNullOrBlank()) {
            tasks += {
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
            }
        }

        val vixSrcUrl = buildStreamingCommunityVixSrcUrl(playbackData)
        if (!vixSrcUrl.isNullOrBlank()) {
            tasks += {
                StreamCenterVixSrcExtractor().getUrl(
                    url = vixSrcUrl,
                    referer = "https://vixsrc.to/",
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                true
            }
        }

        return runParallelSourceTasks(tasks)
    }

    private fun buildStreamingCommunityVixSrcUrl(playbackData: StreamingCommunityPlaybackData): String? {
        val tmdbId = playbackData.tmdbId ?: return null
        return if (playbackData.type == "movie") {
            "https://vixsrc.to/movie/$tmdbId"
        } else {
            val seasonNumber = playbackData.seasonNumber ?: return null
            val episodeNumber = playbackData.episodeNumber ?: return null
            "https://vixsrc.to/tv/$tmdbId/$seasonNumber/$episodeNumber"
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
        private const val TRACKING_PROVIDER_PAGE_SIZE = 30
        private const val AU_ARCHIVE_BATCH_SIZE = 30
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
        private const val STREMIO_ADDON_TIMEOUT_MS = 45_000L
        private const val NATIVE_SOURCE_CONCURRENCY = 4
        private const val STREMIO_ADDON_CONCURRENCY = 4
        private val YEAR_REGEX = Regex("""\b(?:18|19|20|21)\d{2}\b""")
        private val IMDB_ID_REGEX = Regex("""tt\d{5,}""", RegexOption.IGNORE_CASE)
        private val TMDB_MEDIA_URL_REGEX = Regex(
            """(?:https?://(?:www\.)?themoviedb\.org/)?(?:[a-z]{2}(?:-[a-z]{2})?/)?(movie|tv)/(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        private val TMDB_ERROR_TITLE_REGEX = Regex(
            ".*\\b(?:too many requests|access denied|forbidden|not found|internal server error)\\b.*",
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
