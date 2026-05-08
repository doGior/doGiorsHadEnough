package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
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
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StreamCenterCacheStats(
    val memoryEntries: Int,
    val diskEntries: Int,
    val diskBytes: Long,
) {
    val diskSizeLabel: String
        get() = when {
            diskBytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", diskBytes / 1024.0 / 1024.0)
            diskBytes >= 1024L -> String.format(Locale.US, "%.1f KB", diskBytes / 1024.0)
            else -> "$diskBytes B"
        }
}

class StreamCenter(
    private val sharedPref: SharedPreferences? = null,
) : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = "StreamCenter"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    private val tmdbLanguage = "it-IT"
    private val animeKeywordPath = "/keyword/210024-anime/"
    private val animeMarker = "streamcenter_media=anime"
    private val animeAnilistParam = "streamcenter_anilist"
    private val animeMalParam = "streamcenter_mal"
    private val animeTmdbSeasonParam = "streamcenter_tmdb_season"
    private val animeDisplaySeasonParam = "streamcenter_anime_season"
    private val anilistGraphqlUrl = "https://graphql.anilist.co"
    private val anilistSectionPrefix = "anilist:"
    private val anilistOnlyPath = "/anilist/"
    private val animeUnityUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
    private val animeWorldUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
    private val animeSaturnUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
    private val hentaiWorldUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)
    private val hentaiSaturnUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_HENTAISATURN)
    private val animeListMappingUrl = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"
    private val streamingCommunityRootUrl: String
        get() = StreamCenterPlugin
            .getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
            .trimEnd('/') + "/"
    private val streamingCommunityMainUrl: String
        get() = "${streamingCommunityRootUrl}it"
    private val anilistHomePageSize = 24
    private val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )
    private val anilistHeaders = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json;charset=utf-8",
    )
    private val animeUnitySessionHeaders = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )
    private var streamingCommunityInertiaVersion = ""
    private var streamingCommunityXsrfToken = ""
    private val streamingCommunityHeaders = mutableMapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to streamingCommunityInertiaVersion,
        "X-Requested-With" to "XMLHttpRequest",
    )

    @Suppress("unused")
    private val legacyMainPage = mainPageOf(
        "$mainUrl/tv/airing-today?without_keywords=210024" to "Serie TV - In onda oggi (${currentItalianWeekdayName()})",
        "$mainUrl/tv/on-the-air?without_keywords=210024" to "Serie TV - In onda",
        "$mainUrl/tv/top-rated?without_keywords=210024" to "Serie TV - Più votate",
        "$mainUrl/movie/now-playing?without_keywords=210024" to "Film - Al cinema",
        "$mainUrl/movie/upcoming?without_keywords=210024" to "Film - In arrivo",
        "$mainUrl/movie/top-rated?without_keywords=210024" to "Film - Più votati",
        "anilist:anime_calendar" to "Anime - In uscita oggi (${currentItalianWeekdayName()})",
        "anilist:anime_latest" to "Anime - Annunciati",
        "anilist:anime_season_top" to "Anime - Più votati della stagione",
        "anilist:anime_top" to "Anime - Più votati",
        "anilist:anime_movie_latest" to "Anime - Film recenti",
        "anilist:anime_movie_top" to "Anime - Film più votati",
    )

    override val mainPage
        get() = mainPageOf(
            *StreamCenterPlugin.getConfiguredHomeSections(sharedPref)
                .map { it.definition.data to it.title }
                .toTypedArray(),
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        parseAnilistSection(request.data)?.let { section ->
            val showHomeScores = StreamCenterPlugin.shouldShowHomeScore(sharedPref)
            val limit = StreamCenterPlugin.getHomeSectionCount(sharedPref, request.data)
            val anilistPage = fetchAnilistHomePage(section, page, showHomeScores)
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = anilistPage.items.take(limit),
                    isHorizontalImages = false,
                ),
                hasNext = anilistPage.hasNext,
            )
        }

        val showHomeScores = StreamCenterPlugin.shouldShowHomeScore(sharedPref)
        val limit = StreamCenterPlugin.getHomeSectionCount(sharedPref, request.data)
        val isAnimeSection = isAnimeSection(request.data)
        val isTvCalendarSection = isTmdbTvCalendarSection(request.data)
        val doc = getTmdbDocument(request.data, page = page, cacheProfile = TmdbCacheProfile.Home)
        val items = parseMediaCards(
            doc = doc,
            isAnime = isAnimeSection,
            excludeAnime = !isAnimeSection,
            fetchMissingScores = showHomeScores,
            showScores = showHomeScores,
            maxItems = limit,
            includeTvCalendarEpisode = isTvCalendarSection,
        )
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items.take(limit),
                isHorizontalImages = false,
            ),
            hasNext = hasNextPage(doc, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchSearchResults(query, 1).first
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = fetchSearchResults(query, page)
        return newSearchResponseList(items, hasNext)
    }

    private suspend fun fetchSearchResults(query: String, page: Int): Pair<List<SearchResponse>, Boolean> = coroutineScope {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val movieDocDeferred = async(Dispatchers.IO) {
            getTmdbDocument(
                "$mainUrl/search/movie?query=$encodedQuery",
                page = page,
                cacheProfile = TmdbCacheProfile.Search,
            )
        }
        val tvDocDeferred = async(Dispatchers.IO) {
            getTmdbDocument(
                "$mainUrl/search/tv?query=$encodedQuery",
                page = page,
                cacheProfile = TmdbCacheProfile.Search,
            )
        }
        val anilistPageDeferred = async(Dispatchers.IO) { fetchAnilistSearchPage(query, page) }

        val movieDoc = movieDocDeferred.await()
        val tvDoc = tvDocDeferred.await()
        val anilistPage = anilistPageDeferred.await()
        val items = (
            parseMediaCards(movieDoc, excludeAnime = true, fetchMissingScores = false) +
                parseMediaCards(tvDoc, excludeAnime = true, fetchMissingScores = false) +
                anilistPage.items
            ).distinctBy { it.url }
        val hasNext = hasNextPage(movieDoc, page) || hasNextPage(tvDoc, page) || anilistPage.hasNext
        items to hasNext
    }

    override suspend fun load(url: String): LoadResponse {
        if (isAnilistOnlyUrl(url)) {
            return loadAnilistOnly(url)
        }

        val actualUrl = normalizeTmdbUrl(url)
        val doc = getTmdbDocument(actualUrl, cacheProfile = TmdbCacheProfile.Detail)
        val isTvSeries = actualUrl.contains("/tv/")
        val isAnime = actualUrl.contains(animeMarker)
        val animeSelection = if (isAnime) parseAnimeSelection(actualUrl) else null
        val metadata = buildMetadata(doc, actualUrl, isAnime)
        val animeSyncIds = if (isAnime) {
            selectAnimeSyncIds(fetchAnimeSyncIds(metadata.tmdbId, isTvSeries), animeSelection)
        } else {
            emptyList()
        }
        val resolvedSources = resolveLoadSources(metadata, isTvSeries, isAnime, animeSyncIds)
        val anilistMetadata = resolvedSources.anilistMetadata
        val myAnimeListMetadata = resolvedSources.myAnimeListMetadata
        val aniZipMetadata = resolvedSources.aniZipMetadata
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val hentaiWorldSources = resolvedSources.hentaiWorldSources
        val hentaiSaturnSources = resolvedSources.hentaiSaturnSources
        val streamingCommunityTitle = resolvedSources.streamingCommunityTitle
        val typeTags = if (isAnime) listOf("Anime") else emptyList()
        val tags = (typeTags + metadata.tags).distinctBy { it.lowercase(Locale.ROOT) }
        val displayTitle = if (isAnime) {
            anilistMetadata?.title ?: metadata.title
        } else {
            metadata.title
        }

        return if (isTvSeries) {
            val selectedSeason = animeSelection?.displaySeason ?: animeSelection?.tmdbSeason
            val streamingCommunityEpisodes = streamingCommunityTitle
                ?.let { fetchStreamingCommunityEpisodePayloads(it) }
                .orEmpty()
            val tmdbEpisodes = fetchEpisodes(
                doc = doc,
                actualUrl = actualUrl,
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                hentaiWorldSources = hentaiWorldSources,
                hentaiSaturnSources = hentaiSaturnSources,
                targetSeason = selectedSeason,
                streamingCommunityEpisodes = streamingCommunityEpisodes,
                aniZipMetadata = if (isAnime) aniZipMetadata else null,
            )
            val animeSourceEpisodes = if (isAnime && animeSelection != null) {
                buildAnimeSourceEpisodes(
                    animeUnitySources = animeUnitySources,
                    animeWorldSources = animeWorldSources,
                    animeSaturnSources = animeSaturnSources,
                    hentaiWorldSources = hentaiWorldSources,
                    hentaiSaturnSources = hentaiSaturnSources,
                    seasonNumber = animeSelection.displaySeason ?: selectedSeason,
                    tmdbEpisodes = tmdbEpisodes,
                    aniZipMetadata = aniZipMetadata,
                )
            } else {
                emptyList()
            }
            val episodes = animeSourceEpisodes.ifEmpty { tmdbEpisodes }
            val primarySyncIds = pickPrimaryAnimeSyncIds(animeSyncIds)
            val hasDubSources = hasAnimeDubSources(animeUnitySources, animeWorldSources, animeSaturnSources)

            if (isAnime) {
                newAnimeLoadResponse(
                    displayTitle,
                    actualUrl,
                    TvType.Anime,
                ) {
                    this.posterUrl = metadata.poster
                    this.backgroundPosterUrl = metadata.background
                    this.plot = metadata.plot
                    this.tags = tags
                    this.year = metadata.year
                    this.actors = myAnimeListMetadata?.characters.orEmpty()
                    this.recommendations = metadata.recommendations
                    this.contentRating = metadata.contentRating
                    this.showStatus = metadata.showStatus
                    this.comingSoon = metadata.comingSoon
                    metadata.duration?.let { addDuration("$it minuti") }
                    addEpisodes(DubStatus.Subbed, episodes)
                    if (hasDubSources) {
                        addEpisodes(DubStatus.Dubbed, episodes)
                    }
                    addSeasonNames(buildAnimeSeasonData(episodes))
                    metadata.tmdbId?.let { addTMDbId(it) }
                    primarySyncIds?.anilistId?.let { addAniListId(it) }
                    primarySyncIds?.malId?.let { addMalId(it) }
                    myAnimeListMetadata?.trailerUrl?.let { addTrailer(it) }
                    addScore(anilistMetadata?.score)
                }
            } else {
                newTvSeriesLoadResponse(
                    displayTitle,
                    actualUrl,
                    TvType.TvSeries,
                    episodes,
                ) {
                    this.posterUrl = metadata.poster
                    this.backgroundPosterUrl = metadata.background
                    this.plot = metadata.plot
                    this.tags = tags
                    this.year = metadata.year
                    this.actors = metadata.people
                    this.recommendations = metadata.recommendations
                    this.contentRating = metadata.contentRating
                    this.showStatus = metadata.showStatus
                    this.comingSoon = metadata.comingSoon
                    metadata.tmdbId?.let { addTMDbId(it) }
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(metadata.score)
                }
            }
        } else {
            val moviePlaybackData = StreamCenterPlaybackData(
                tmdbUrl = actualUrl,
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                hentaiWorld = hentaiWorldSources.flatMap { it.firstPlaybacks() },
                hentaiSaturn = hentaiSaturnSources.flatMap { it.firstPlaybacks() },
                streamingCommunity = streamingCommunityTitle?.toStreamingCommunityMoviePlayback(),
            )
            val primarySyncIds = animeSyncIds.firstOrNull()

            newMovieLoadResponse(
                displayTitle,
                actualUrl,
                if (isAnime) TvType.AnimeMovie else TvType.Movie,
                dataUrl = moviePlaybackData.toJson(),
            ) {
                this.posterUrl = metadata.poster
                this.backgroundPosterUrl = metadata.background
                this.plot = metadata.plot
                this.tags = tags
                this.year = metadata.year
                this.duration = metadata.duration
                this.actors = if (isAnime) {
                    myAnimeListMetadata?.characters.orEmpty()
                } else {
                    metadata.people
                }
                this.recommendations = metadata.recommendations
                this.contentRating = metadata.contentRating
                this.comingSoon = metadata.comingSoon
                metadata.tmdbId?.let { addTMDbId(it) }
                primarySyncIds?.anilistId?.let { addAniListId(it) }
                primarySyncIds?.malId?.let { addMalId(it) }
                if (isAnime) {
                    myAnimeListMetadata?.trailerUrl?.let { addTrailer(it) }
                } else {
                    metadata.trailerUrl?.let { addTrailer(it) }
                }
                addScore(if (isAnime) anilistMetadata?.score else metadata.score)
            }
        }
    }

    private suspend fun resolveLoadSources(
        metadata: StreamCenterMetadata,
        isTvSeries: Boolean,
        isAnime: Boolean,
        animeSyncIds: List<AnimeSyncIds>,
    ): ResolvedLoadSources = coroutineScope {
        if (isAnime) {
            val useAnimeUnity = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
            val useAnimeWorld = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
            val useAnimeSaturn = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
            val useHentaiWorld = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)
            val useHentaiSaturn = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAISATURN)
            val anilistDeferred = async(Dispatchers.IO) { fetchAnilistMetadataSafely(animeSyncIds) }
            val myAnimeListDeferred = async(Dispatchers.IO) { fetchMyAnimeListMetadata(animeSyncIds) }
            val aniZipDeferred = async(Dispatchers.IO) { fetchAniZipMetadata(animeSyncIds) }
            val animeUnityDeferred = if (useAnimeUnity) {
                async(Dispatchers.IO) { fetchAnimeUnitySources(metadata, animeSyncIds) }
            } else {
                null
            }
            val anilistMetadata = anilistDeferred.await()
            val animeWorldDeferred = if (useAnimeWorld) {
                async(Dispatchers.IO) {
                    fetchAnimeWorldSources(metadata, anilistMetadata, animeSyncIds)
                }
            } else {
                null
            }
            val animeSaturnDeferred = if (useAnimeSaturn) {
                async(Dispatchers.IO) {
                    fetchAnimeSaturnSources(metadata, anilistMetadata, animeSyncIds)
                }
            } else {
                null
            }
            val hentaiWorldDeferred = if (useHentaiWorld) {
                async(Dispatchers.IO) {
                    fetchHentaiWorldSources(metadata, anilistMetadata)
                }
            } else {
                null
            }
            val hentaiSaturnDeferred = if (useHentaiSaturn) {
                async(Dispatchers.IO) {
                    fetchHentaiSaturnSources(metadata, anilistMetadata, animeSyncIds)
                }
            } else {
                null
            }

            ResolvedLoadSources(
                anilistMetadata = anilistMetadata,
                myAnimeListMetadata = myAnimeListDeferred.await(),
                aniZipMetadata = aniZipDeferred.await(),
                animeUnitySources = animeUnityDeferred?.await().orEmpty(),
                animeWorldSources = animeWorldDeferred?.await().orEmpty(),
                animeSaturnSources = animeSaturnDeferred?.await().orEmpty(),
                hentaiWorldSources = hentaiWorldDeferred?.await().orEmpty(),
                hentaiSaturnSources = hentaiSaturnDeferred?.await().orEmpty(),
            )
        } else {
            if (!isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)) {
                ResolvedLoadSources()
            } else {
                val streamingCommunityTitleDeferred = async(Dispatchers.IO) {
                    fetchStreamingCommunityTitle(metadata, isTvSeries)
                }
                ResolvedLoadSources(streamingCommunityTitle = streamingCommunityTitleDeferred.await())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playbackData = runCatching { parseJson<StreamCenterPlaybackData>(data) }.getOrNull()
        val sourceTasks = mutableListOf<StreamCenterSourceTask>()

        playbackData?.animeUnity
            ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) }
            ?.let { animeUnityPlayback ->
                sourceTasks += StreamCenterSourceTask(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) {
                    loadAnimeUnityLinks(
                        playbackData = animeUnityPlayback,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) {
            playbackData?.animeWorld.orEmpty().forEach { animeWorldPlayback ->
                sourceTasks += StreamCenterSourceTask(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD) {
                    loadAnimeWorldLink(
                        playbackData = animeWorldPlayback,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }
        }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) {
            playbackData?.animeSaturn.orEmpty().forEach { animeSaturnPlayback ->
                sourceTasks += StreamCenterSourceTask(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN) {
                    loadSaturnLink(
                        source = "AnimeSaturn",
                        baseUrl = animeSaturnUrl,
                        cacheProfile = TmdbCacheProfile.AnimeSaturnPlayer,
                        playbackData = animeSaturnPlayback,
                        callback = callback,
                    )
                }
            }
        }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)) {
            playbackData?.hentaiWorld.orEmpty().forEach { hentaiWorldPlayback ->
                sourceTasks += StreamCenterSourceTask(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD) {
                    loadHentaiWorldLink(
                        playbackData = hentaiWorldPlayback,
                        callback = callback,
                    )
                }
            }
        }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAISATURN)) {
            playbackData?.hentaiSaturn.orEmpty().forEach { hentaiSaturnPlayback ->
                sourceTasks += StreamCenterSourceTask(StreamCenterPlugin.PREF_SOURCE_HENTAISATURN) {
                    loadSaturnLink(
                        source = "HentaiSaturn",
                        baseUrl = hentaiSaturnUrl,
                        cacheProfile = TmdbCacheProfile.HentaiSaturnPlayer,
                        playbackData = hentaiSaturnPlayback,
                        callback = callback,
                    )
                }
            }
        }

        playbackData?.streamingCommunity
            ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) }
            ?.let { streamingCommunityPlayback ->
                sourceTasks += StreamCenterSourceTask(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) {
                    loadStreamingCommunityLinks(
                        playbackData = streamingCommunityPlayback,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }

        return runPrioritizedSourceTasks(sourceTasks)
    }

    private suspend fun runPrioritizedSourceTasks(tasks: List<StreamCenterSourceTask>): Boolean {
        if (tasks.isEmpty()) return false
        val sourceOrder = StreamCenterPlugin.getEnabledStreamingSourcesInPriority(sharedPref).map { it.key }
        var foundAny = false
        sourceOrder.forEach { sourceKey ->
            val group = tasks.filter { it.sourceKey == sourceKey }.map { it.task }
            if (group.isNotEmpty()) {
                foundAny = runParallelSourceTasks(group) || foundAny
            }
        }
        val remaining = tasks.filter { task -> sourceOrder.none { it == task.sourceKey } }.map { it.task }
        if (remaining.isNotEmpty()) {
            foundAny = runParallelSourceTasks(remaining) || foundAny
        }
        return foundAny
    }

    private suspend fun runParallelSourceTasks(tasks: List<suspend () -> Boolean>): Boolean {
        if (tasks.isEmpty()) return false
        return supervisorScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    runCatching { task() }.getOrDefault(false)
                }
            }.awaitAll().any { it }
        }
    }

    private fun isSourceEnabled(prefKey: String): Boolean {
        return StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, prefKey)
    }

    private suspend fun loadAnilistOnly(url: String): LoadResponse {
        val anilistId = extractAnilistIdFromText(url) ?: error("Missing AniList ID")
        val metadata = fetchAnilistMediaById(anilistId)
            ?.toAnilistLoadMetadata()
            ?: error("AniList metadata not found")
        val isMovie = metadata.format == "MOVIE"
        val syncIds = fetchAnimeSyncIdsForAnilist(
            anilistId = metadata.anilistId,
            malId = metadata.malId,
            isTvSeries = !isMovie,
        )
        val streamCenterMetadata = metadata.toStreamCenterMetadata()
        val anilistMetadata = AnilistMetadata(
            title = metadata.title,
            score = metadata.score,
            titleCandidates = metadata.titleCandidates,
        )
        val fallbackSyncIds = listOf(
            AnimeSyncIds(
                tmdbId = 0,
                tmdbSeason = null,
                displaySeason = null,
                anilistId = metadata.anilistId,
                malId = metadata.malId,
                type = metadata.format,
            )
        )
        val sourceSyncIds = syncIds.ifEmpty { fallbackSyncIds }
        val resolvedSources = coroutineScope {
            val useAnimeUnity = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
            val useAnimeWorld = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
            val useAnimeSaturn = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
            val useHentaiWorld = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)
            val useHentaiSaturn = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAISATURN)
            val myAnimeListDeferred = async(Dispatchers.IO) {
                metadata.malId?.let { fetchMyAnimeListMetadata(fallbackSyncIds) }
            }
            val aniZipDeferred = async(Dispatchers.IO) {
                fetchAniZipMetadata(
                    syncIds = sourceSyncIds,
                    anilistId = metadata.anilistId,
                    malId = metadata.malId,
                )
            }
            val animeUnityDeferred = if (useAnimeUnity) {
                async(Dispatchers.IO) {
                    fetchAnimeUnitySources(streamCenterMetadata, sourceSyncIds)
                }
            } else {
                null
            }
            val animeWorldDeferred = if (useAnimeWorld) {
                async(Dispatchers.IO) {
                    fetchAnimeWorldSources(streamCenterMetadata, anilistMetadata, sourceSyncIds)
                }
            } else {
                null
            }
            val animeSaturnDeferred = if (useAnimeSaturn) {
                async(Dispatchers.IO) {
                    fetchAnimeSaturnSources(streamCenterMetadata, anilistMetadata, sourceSyncIds)
                }
            } else {
                null
            }
            val hentaiWorldDeferred = if (useHentaiWorld) {
                async(Dispatchers.IO) {
                    fetchHentaiWorldSources(streamCenterMetadata, anilistMetadata)
                }
            } else {
                null
            }
            val hentaiSaturnDeferred = if (useHentaiSaturn) {
                async(Dispatchers.IO) {
                    fetchHentaiSaturnSources(streamCenterMetadata, anilistMetadata, sourceSyncIds)
                }
            } else {
                null
            }

            ResolvedLoadSources(
                anilistMetadata = anilistMetadata,
                myAnimeListMetadata = myAnimeListDeferred.await(),
                aniZipMetadata = aniZipDeferred.await(),
                animeUnitySources = animeUnityDeferred?.await().orEmpty(),
                animeWorldSources = animeWorldDeferred?.await().orEmpty(),
                animeSaturnSources = animeSaturnDeferred?.await().orEmpty(),
                hentaiWorldSources = hentaiWorldDeferred?.await().orEmpty(),
                hentaiSaturnSources = hentaiSaturnDeferred?.await().orEmpty(),
            )
        }
        val myAnimeListMetadata = resolvedSources.myAnimeListMetadata
        val aniZipMetadata = resolvedSources.aniZipMetadata
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val hentaiWorldSources = resolvedSources.hentaiWorldSources
        val hentaiSaturnSources = resolvedSources.hentaiSaturnSources
        val sourceUrl = "$mainUrl${anilistOnlyPath}${metadata.anilistId}"

        return if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                hentaiWorld = hentaiWorldSources.flatMap { it.firstPlaybacks() },
                hentaiSaturn = hentaiSaturnSources.flatMap { it.firstPlaybacks() },
            )
            newMovieLoadResponse(
                metadata.title,
                sourceUrl,
                TvType.AnimeMovie,
                dataUrl = playbackData.toJson(),
            ) {
                this.posterUrl = metadata.poster
                this.backgroundPosterUrl = metadata.background
                this.plot = metadata.plot
                this.tags = listOf("Anime") + metadata.tags
                this.year = metadata.year
                this.duration = metadata.duration
                this.actors = myAnimeListMetadata?.characters.orEmpty()
                this.recommendations = metadata.recommendations
                this.contentRating = if (metadata.isAdult) "18+" else null
                this.comingSoon = metadata.status == "NOT_YET_RELEASED"
                metadata.malId?.let { addMalId(it) }
                addAniListId(metadata.anilistId)
                myAnimeListMetadata?.trailerUrl?.let { addTrailer(it) }
                addScore(metadata.score)
            }
        } else {
            val episodes = buildAnimeSourceEpisodes(
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                hentaiWorldSources = hentaiWorldSources,
                hentaiSaturnSources = hentaiSaturnSources,
                seasonNumber = null,
                tmdbEpisodes = emptyList(),
                aniZipMetadata = aniZipMetadata,
            ).ifEmpty {
                buildAnilistFallbackEpisodes(metadata, aniZipMetadata)
            }
            val hasDubSources = hasAnimeDubSources(animeUnitySources, animeWorldSources, animeSaturnSources)
            newAnimeLoadResponse(
                metadata.title,
                sourceUrl,
                TvType.Anime,
            ) {
                this.posterUrl = metadata.poster
                this.backgroundPosterUrl = metadata.background
                this.plot = metadata.plot
                this.tags = listOf("Anime") + metadata.tags
                this.year = metadata.year
                this.actors = myAnimeListMetadata?.characters.orEmpty()
                this.recommendations = metadata.recommendations
                this.contentRating = if (metadata.isAdult) "18+" else null
                this.showStatus = mapAnilistShowStatus(metadata.status)
                this.comingSoon = metadata.status == "NOT_YET_RELEASED"
                metadata.duration?.let { addDuration("$it minuti") }
                addEpisodes(DubStatus.Subbed, episodes)
                if (hasDubSources) {
                    addEpisodes(DubStatus.Dubbed, episodes)
                }
                addSeasonNames(buildAnimeSeasonData(episodes))
                metadata.malId?.let { addMalId(it) }
                addAniListId(metadata.anilistId)
                myAnimeListMetadata?.trailerUrl?.let { addTrailer(it) }
                addScore(metadata.score)
            }
        }
    }

    private data class StreamCenterMetadata(
        val title: String,
        val originalTitle: String?,
        val plot: String?,
        val poster: String?,
        val background: String?,
        val tags: List<String>,
        val year: Int?,
        val tmdbId: String?,
        val score: String?,
        val people: List<ActorData>,
        val recommendations: List<SearchResponse>,
        val contentRating: String?,
        val showStatus: ShowStatus?,
        val comingSoon: Boolean,
        val duration: Int?,
        val trailerUrl: String?,
    )

    private enum class AnilistHomeSection(
        val key: String,
        val isTvSeries: Boolean,
        val sort: String,
        val formats: List<String>,
        val currentSeason: Boolean = false,
        val releasedOnly: Boolean = false,
        val status: String? = null,
        val calendarToday: Boolean = false,
    ) {
        AnimeLatest(
            key = "anime_latest",
            isTvSeries = true,
            sort = "POPULARITY_DESC",
            formats = listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL"),
            status = "NOT_YET_RELEASED",
        ),
        AnimeCalendar(
            key = "anime_calendar",
            isTvSeries = true,
            sort = "TIME",
            formats = listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL"),
            calendarToday = true,
        ),
        AnimeTop(
            key = "anime_top",
            isTvSeries = true,
            sort = "SCORE_DESC",
            formats = listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL"),
        ),
        AnimeSeasonTop(
            key = "anime_season_top",
            isTvSeries = true,
            sort = "SCORE_DESC",
            formats = listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL"),
            currentSeason = true,
        ),
        AnimeMovieLatest(
            key = "anime_movie_latest",
            isTvSeries = false,
            sort = "START_DATE_DESC",
            formats = listOf("MOVIE"),
            status = "FINISHED",
        ),
        AnimeMovieTop(
            key = "anime_movie_top",
            isTvSeries = false,
            sort = "SCORE_DESC",
            formats = listOf("MOVIE"),
        );

        companion object {
            fun fromKey(key: String): AnilistHomeSection? {
                return values().firstOrNull { it.key == key }
            }
        }
    }

    private data class AnilistHomePageResult(
        val items: List<SearchResponse>,
        val hasNext: Boolean,
    )

    private data class AnilistMedia(
        val anilistId: Int,
        val malId: Int?,
        val format: String?,
        val title: String,
        val poster: String?,
        val score: String?,
        val calendarEpisode: Int? = null,
    )

    private data class TmdbCalendarEpisode(
        val label: String,
        val episodeNumber: Int?,
    )

    private data class TmdbCalendarCardMetadata(
        val score: String?,
        val episode: TmdbCalendarEpisode?,
    )

    private data class AnilistMetadata(
        val title: String?,
        val score: String?,
        val titleCandidates: List<String> = emptyList(),
    )

    private data class AnilistLoadMetadata(
        val anilistId: Int,
        val malId: Int?,
        val title: String,
        val titleCandidates: List<String>,
        val originalTitle: String?,
        val format: String?,
        val poster: String?,
        val background: String?,
        val plot: String?,
        val score: String?,
        val year: Int?,
        val duration: Int?,
        val episodes: Int?,
        val status: String?,
        val isAdult: Boolean,
        val tags: List<String>,
        val recommendations: List<SearchResponse>,
    )

    private data class MyAnimeListMetadata(
        val trailerUrl: String?,
        val characters: List<ActorData>,
    )

    private data class AniZipMetadata(
        val fallbackPoster: String?,
        val episodes: Map<Int, AniZipEpisodeMetadata>,
    ) {
        fun episode(number: Int?): AniZipEpisodeMetadata? = number?.let(episodes::get)
    }

    private data class AniZipEpisodeMetadata(
        val title: String?,
        val overview: String?,
        val image: String?,
        val runtime: Int?,
        val score: Score?,
        val airDate: String?,
    )

    private data class AnilistSeason(
        val name: String,
        val year: Int,
    )

    private class AnilistRateLimitException : RuntimeException("AniList error: Too Many Requests")

    private enum class TmdbCacheProfile(val ttlMs: Long) {
        Home(12L * 60L * 60L * 1000L),
        Search(12L * 60L * 60L * 1000L),
        Detail(7L * 24L * 60L * 60L * 1000L),
        Seasons(24L * 60L * 60L * 1000L),
        TvCalendarEpisode(12L * 60L * 60L * 1000L),
        Recommendations(7L * 24L * 60L * 60L * 1000L),
        AnilistHome(12L * 60L * 60L * 1000L),
        AnilistSearch(12L * 60L * 60L * 1000L),
        AnilistDetail(7L * 24L * 60L * 60L * 1000L),
        MyAnimeListDetail(7L * 24L * 60L * 60L * 1000L),
        AnimeMappings(7L * 24L * 60L * 60L * 1000L),
        AnimeUnityArchive(24L * 60L * 60L * 1000L),
        AnimeUnityDetail(24L * 60L * 60L * 1000L),
        AnimeUnityPlayer(60L * 60L * 1000L),
        AnimeWorldSearch(24L * 60L * 60L * 1000L),
        AnimeWorldDetail(24L * 60L * 60L * 1000L),
        AnimeWorldPlayer(60L * 60L * 1000L),
        AnimeSaturnSearch(24L * 60L * 60L * 1000L),
        AnimeSaturnDetail(24L * 60L * 60L * 1000L),
        AnimeSaturnPlayer(60L * 60L * 1000L),
        HentaiWorldSearch(24L * 60L * 60L * 1000L),
        HentaiWorldDetail(24L * 60L * 60L * 1000L),
        HentaiWorldPlayer(60L * 60L * 1000L),
        HentaiSaturnSearch(24L * 60L * 60L * 1000L),
        HentaiSaturnDetail(24L * 60L * 60L * 1000L),
        HentaiSaturnPlayer(60L * 60L * 1000L),
        TmdbScore(7L * 24L * 60L * 60L * 1000L),
        StreamingCommunitySession(24L * 60L * 60L * 1000L),
        StreamingCommunitySearch(6L * 60L * 60L * 1000L),
        StreamingCommunityDetail(24L * 60L * 60L * 1000L),
        StreamingCommunityPlayer(60L * 60L * 1000L),
        AnimeUnitySession(12L * 60L * 60L * 1000L);

        val cacheMinutes: Int
            get() = (ttlMs / 60_000L).toInt().coerceAtLeast(1)
    }

    private data class TextCacheEntry(
        val text: String,
        val expiresAtMs: Long,
    )

    private class ExpiringTextCache(private val maxEntries: Int) {
        private var diskDirectory: File? = null
        private val entries = object : LinkedHashMap<String, TextCacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextCacheEntry>?): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        fun setDiskDirectory(directory: File) {
            diskDirectory = directory
            runCatching { directory.mkdirs() }
        }

        @Synchronized
        fun get(key: String, allowExpired: Boolean = false): String? {
            val entry = entries[key]
            if (entry != null) {
                if (allowExpired || entry.expiresAtMs > System.currentTimeMillis()) {
                    return entry.text
                }

                entries.remove(key)
            }

            return readDisk(key, allowExpired)
        }

        @Synchronized
        fun put(key: String, text: String, ttlMs: Long) {
            val expiresAtMs = System.currentTimeMillis() + ttlMs
            entries[key] = TextCacheEntry(
                text = text,
                expiresAtMs = expiresAtMs,
            )
            writeDisk(key, text, expiresAtMs)
        }

        @Synchronized
        fun remove(key: String) {
            entries.remove(key)
            runCatching { cacheFile(key)?.delete() }
        }

        @Synchronized
        fun clear() {
            entries.clear()
            val directory = diskDirectory ?: return
            directory.listFiles { file -> file.isFile && file.extension == "html" }
                .orEmpty()
                .forEach { runCatching { it.delete() } }
        }

        @Synchronized
        fun stats(): StreamCenterCacheStats {
            val files = diskDirectory
                ?.listFiles { file -> file.isFile && file.extension == "html" }
                .orEmpty()
            return StreamCenterCacheStats(
                memoryEntries = entries.size,
                diskEntries = files.size,
                diskBytes = files.sumOf { it.length() },
            )
        }

        private fun readDisk(key: String, allowExpired: Boolean): String? {
            val file = cacheFile(key) ?: return null
            val raw = runCatching { file.readText() }.getOrNull() ?: return null
            val separator = raw.indexOf('\n')
            if (separator <= 0) {
                runCatching { file.delete() }
                return null
            }

            val expiresAtMs = raw.substring(0, separator).toLongOrNull()
            if (expiresAtMs == null) {
                runCatching { file.delete() }
                return null
            }

            val text = raw.substring(separator + 1)
            if (!allowExpired && expiresAtMs <= System.currentTimeMillis()) {
                runCatching { file.delete() }
                return null
            }

            entries[key] = TextCacheEntry(text, expiresAtMs)
            return text
        }

        private fun writeDisk(key: String, text: String, expiresAtMs: Long) {
            val directory = diskDirectory ?: return
            runCatching {
                directory.mkdirs()
                cacheFile(key)?.writeText("$expiresAtMs\n$text")
                trimDisk(directory)
            }
        }

        private fun cacheFile(key: String): File? {
            val directory = diskDirectory ?: return null
            return File(directory, "${sha256(key)}.html")
        }

        private fun trimDisk(directory: File) {
            val files = directory.listFiles { file -> file.isFile && file.extension == "html" }.orEmpty()
            val maxDiskEntries = maxEntries * 2
            if (files.size <= maxDiskEntries) return

            files.sortedBy { it.lastModified() }
                .take(files.size - maxDiskEntries)
                .forEach { runCatching { it.delete() } }
        }

        private fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private data class AnimeSyncIds(
        val tmdbId: Int,
        val tmdbSeason: Int?,
        val displaySeason: Int?,
        val anilistId: Int?,
        val malId: Int?,
        val type: String?,
    )

    private data class StreamCenterAnimeSelection(
        val tmdbSeason: Int?,
        val displaySeason: Int?,
        val anilistId: Int?,
        val malId: Int?,
    )

    private data class StreamCenterPlaybackData(
        val tmdbUrl: String? = null,
        val animeUnity: AnimeUnityPlaybackData? = null,
        val animeWorld: List<AnimeWorldPlaybackData> = emptyList(),
        val animeSaturn: List<SaturnPlaybackData> = emptyList(),
        val hentaiWorld: List<HentaiWorldPlaybackData> = emptyList(),
        val hentaiSaturn: List<SaturnPlaybackData> = emptyList(),
        val streamingCommunity: StreamingCommunityPlaybackData? = null,
    )

    private data class StreamCenterSourceTask(
        val sourceKey: String,
        val task: suspend () -> Boolean,
    )

    private data class ResolvedLoadSources(
        val anilistMetadata: AnilistMetadata? = null,
        val myAnimeListMetadata: MyAnimeListMetadata? = null,
        val aniZipMetadata: AniZipMetadata? = null,
        val animeUnitySources: List<AnimeUnityTitleSources> = emptyList(),
        val animeWorldSources: List<AnimeWorldTitleSources> = emptyList(),
        val animeSaturnSources: List<SaturnTitleSources> = emptyList(),
        val hentaiWorldSources: List<HentaiWorldTitleSources> = emptyList(),
        val hentaiSaturnSources: List<SaturnTitleSources> = emptyList(),
        val streamingCommunityTitle: StreamingCommunityTitle? = null,
    )

    private data class AnimeUnityPlaybackData(
        val preferredUrl: String,
        val subUrl: String? = null,
        val dubUrl: String? = null,
    )

    private data class AnimeWorldPlaybackData(
        val label: String,
        val pageUrl: String,
        val episodeToken: String,
    )

    private data class HentaiWorldPlaybackData(
        val watchUrl: String,
    )

    private data class SaturnPlaybackData(
        val label: String,
        val episodeUrl: String,
        val watchUrl: String? = null,
    )

    private data class StreamingCommunityTitle(
        val id: Int,
        val slug: String,
        val name: String,
        val type: String,
        val tmdbId: Int?,
        val year: Int?,
        val seasons: List<StreamingCommunitySeason>,
    )

    private data class StreamingCommunitySeason(
        val id: Int,
        val number: Int,
        val episodes: List<StreamingCommunityEpisode>,
    )

    private data class StreamingCommunityEpisode(
        val id: Int,
        val number: Int,
    )

    private data class StreamingCommunityPlaybackData(
        val iframeUrl: String,
        val type: String,
        val tmdbId: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    )

    private data class AnimeUnityPlayerSource(
        val label: String,
        val url: String,
    )

    private data class AnimeUnityAnime(
        val id: Int,
        val slug: String,
        val title: String?,
        val titleEng: String?,
        val titleIt: String?,
        val dub: Int,
        val anilistId: Int?,
        val malId: Int?,
        val episodesCount: Int?,
        val realEpisodesCount: Int?,
    )

    private data class AnimeUnityEpisodeInfo(
        val id: Int,
        val number: String,
    )

    private data class AnimeUnityPageData(
        val anime: AnimeUnityAnime,
        val episodes: List<AnimeUnityEpisodeInfo>,
    )

    private data class AnimeWorldSearchItem(
        val url: String,
        val title: String,
        val otherTitle: String?,
        val isDub: Boolean,
    )

    private data class AnimeWorldPageData(
        val searchItem: AnimeWorldSearchItem,
        val anilistId: Int?,
        val malId: Int?,
        val episodeSources: Map<String, AnimeWorldPlaybackData>,
    )

    private data class AnimeWorldEpisodeInfo(
        val number: String,
        val token: String,
    )

    private data class AnimeWorldTitleSources(
        val syncIds: AnimeSyncIds,
        val subSources: Map<String, AnimeWorldPlaybackData>,
        val dubSources: Map<String, AnimeWorldPlaybackData>,
    ) {
        fun playbacksForEpisode(number: String?): List<AnimeWorldPlaybackData> {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return emptyList()
            val sources = mutableListOf<AnimeWorldPlaybackData>()
            subSources[normalizedNumber]?.let(sources::add)
            dubSources[normalizedNumber]?.let(sources::add)
            return sources.distinctBy { "${it.label}:${it.episodeToken}:${it.pageUrl}" }
        }

        fun firstPlaybacks(): List<AnimeWorldPlaybackData> {
            val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
            return playbacksForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> {
            return (subSources.keys + dubSources.keys)
                .distinct()
                .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        }
    }

    private data class HentaiWorldSearchItem(
        val url: String,
        val title: String,
    )

    private data class SaturnSearchItem(
        val url: String,
        val title: String,
        val isDub: Boolean,
    )

    private data class SaturnPageData(
        val searchItem: SaturnSearchItem,
        val anilistId: Int?,
        val malId: Int?,
        val episodeSources: Map<String, SaturnPlaybackData>,
    )

    private data class HentaiWorldTitleSources(
        val sources: Map<String, HentaiWorldPlaybackData>,
    ) {
        fun playbacksForEpisode(number: String?): List<HentaiWorldPlaybackData> {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return emptyList()
            return listOfNotNull(sources[normalizedNumber])
        }

        fun firstPlaybacks(): List<HentaiWorldPlaybackData> {
            val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
            return playbacksForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> {
            return sources.keys
                .distinct()
                .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        }
    }

    private data class SaturnTitleSources(
        val syncIds: AnimeSyncIds?,
        val subSources: Map<String, SaturnPlaybackData>,
        val dubSources: Map<String, SaturnPlaybackData> = emptyMap(),
    ) {
        fun playbacksForEpisode(number: String?): List<SaturnPlaybackData> {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return emptyList()
            val sources = mutableListOf<SaturnPlaybackData>()
            subSources[normalizedNumber]?.let(sources::add)
            dubSources[normalizedNumber]?.let(sources::add)
            return sources.distinctBy { "${it.label}:${it.episodeUrl}:${it.watchUrl}" }
        }

        fun firstPlaybacks(): List<SaturnPlaybackData> {
            val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
            return playbacksForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> {
            return (subSources.keys + dubSources.keys)
                .distinct()
                .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        }
    }

    private data class AnimeUnityTitleSources(
        val syncIds: AnimeSyncIds,
        val subSources: Map<String, String>,
        val dubSources: Map<String, String>,
    ) {
        fun playbackForEpisode(number: String?): AnimeUnityPlaybackData? {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return null
            val subUrl = subSources[normalizedNumber]
            val dubUrl = dubSources[normalizedNumber]
            val preferredUrl = subUrl ?: dubUrl ?: return null
            return AnimeUnityPlaybackData(
                preferredUrl = preferredUrl,
                subUrl = subUrl,
                dubUrl = dubUrl,
            )
        }

        fun firstPlayback(): AnimeUnityPlaybackData? {
            val firstNumber = episodeNumbers().firstOrNull()
            return playbackForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> {
            return (subSources.keys + dubSources.keys)
                .distinct()
                .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        }
    }

    private suspend fun buildMetadata(doc: Document, actualUrl: String, isAnime: Boolean): StreamCenterMetadata {
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
            tags = (genres + factTags + keywords).distinctBy { it.lowercase(Locale.ROOT) },
            year = parseYear(doc),
            tmdbId = extractTmdbId(actualUrl),
            score = score,
            people = (parseActors(doc) + parseCrew(doc)).distinct(),
            recommendations = fetchRecommendations(actualUrl, isAnime),
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
        cacheProfile: TmdbCacheProfile = TmdbCacheProfile.Detail,
    ): Document {
        val requestUrl = stripStreamCenterParams(normalizeTmdbUrl(url, page))
        val html = getCachedText("GET:$requestUrl", cacheProfile) {
            app.get(
                requestUrl,
                headers = headers,
                cacheTime = cacheProfile.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        return Jsoup.parse(html, requestUrl)
    }

    private suspend fun getCachedText(
        cacheKey: String,
        cacheProfile: TmdbCacheProfile,
        cacheBlank: Boolean = false,
        fetch: suspend () -> String,
    ): String {
        tmdbTextCache.get(cacheKey)?.let { return it }

        return try {
            fetch().also { text ->
                if (text.isNotBlank() || cacheBlank) {
                    tmdbTextCache.put(cacheKey, text, cacheProfile.ttlMs)
                }
            }
        } catch (exception: Exception) {
            tmdbTextCache.get(cacheKey, allowExpired = true) ?: throw exception
        }
    }

    private fun parseAnilistSection(data: String): AnilistHomeSection? {
        if (!data.startsWith(anilistSectionPrefix)) return null
        return AnilistHomeSection.fromKey(data.removePrefix(anilistSectionPrefix))
    }

    private fun String.throwOnAnilistErrors(): String {
        val errors = runCatching { JSONObject(this).optJSONArray("errors") }.getOrNull()
        if (errors != null && errors.length() > 0) {
            val message = errors.optJSONObject(0)?.optString("message") ?: "unknown"
            if (message.equals("Too Many Requests", ignoreCase = true)) {
                markAnilistRateLimited()
                throw AnilistRateLimitException()
            }
            error("AniList error: $message")
        }
        return this
    }

    private fun markAnilistRateLimited() {
        anilistRateLimitedUntilMs = maxOf(
            anilistRateLimitedUntilMs,
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2),
        )
    }

    private fun checkAnilistRateLimit() {
        if (anilistRateLimitedUntilMs > System.currentTimeMillis()) {
            throw AnilistRateLimitException()
        }
    }

    private fun Throwable.isAnilistRateLimitFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is AnilistRateLimitException) return true
            if (current.message?.contains("Too Many Requests", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private suspend fun fetchAnilistGraphqlText(
        cacheKey: String,
        cacheProfile: TmdbCacheProfile,
        requestBodyText: String,
    ): String {
        val requestBody = requestBodyText.toRequestBody("application/json;charset=utf-8".toMediaType())
        return getCachedText(cacheKey, cacheProfile) {
            checkAnilistRateLimit()
            app.post(
                anilistGraphqlUrl,
                headers = anilistHeaders,
                requestBody = requestBody,
                cacheTime = cacheProfile.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string().throwOnAnilistErrors()
        }
    }

    private suspend fun fetchAnilistSearchPage(query: String, page: Int): AnilistHomePageResult {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isBlank()) return AnilistHomePageResult(emptyList(), false)

        extractAnilistIdFromText(cleanedQuery)?.let { anilistId ->
            val media = runCatching {
                fetchAnilistMediaById(anilistId)
                    ?.toAnilistMedia()
                    ?.let(::listOf)
                    .orEmpty()
            }.getOrElse { exception ->
                if (exception.isAnilistRateLimitFailure()) emptyList() else throw exception
            }
            return AnilistHomePageResult(
                items = buildAnilistSearchResponses(media),
                hasNext = false,
            )
        }

        val pages = listOf(null, true).mapNotNull { adultFilter ->
            runCatching { fetchAnilistSearchMedia(cleanedQuery, page, adultFilter) }.getOrNull()
        }
        val media = pages.flatMap { it.first }.distinctBy { it.anilistId }
        val hasNext = pages.any { it.second }

        return AnilistHomePageResult(
            items = buildAnilistSearchResponses(media),
            hasNext = hasNext,
        )
    }

    private suspend fun fetchAnilistSearchMedia(
        query: String,
        page: Int,
        isAdult: Boolean?,
    ): Pair<List<AnilistMedia>, Boolean> {
        val requestBodyText = buildAnilistSearchRequestBody(query, page, isAdult)
        val responseText = fetchAnilistGraphqlText(
            cacheKey = "AL:SEARCH2:$requestBodyText",
            cacheProfile = TmdbCacheProfile.AnilistSearch,
            requestBodyText = requestBodyText,
        )
        return parseAnilistMediaPage(responseText)
    }

    private fun buildAnilistSearchRequestBody(query: String, page: Int, isAdult: Boolean?): String {
        val variables = JSONObject().apply {
            put("page", page.coerceAtLeast(1))
            put("perPage", 50)
            put("search", query)
            isAdult?.let { put("isAdult", it) }
            put(
                "formatIn",
                JSONArray(listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL", "MOVIE"))
            )
        }
        val adultVariable = if (isAdult == null) "" else "${'$'}isAdult: Boolean,"
        val adultArgument = if (isAdult == null) "" else "isAdult: ${'$'}isAdult,"
        val graphql = """
            query (
                ${'$'}page: Int,
                ${'$'}perPage: Int,
                ${'$'}search: String,
                $adultVariable
                ${'$'}formatIn: [MediaFormat]
            ) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo {
                        hasNextPage
                    }
                    media(
                        type: ANIME,
                        $adultArgument
                        search: ${'$'}search,
                        sort: SEARCH_MATCH,
                        format_in: ${'$'}formatIn
                    ) {
                        id
                        idMal
                        format
                        title {
                            userPreferred
                            english
                            romaji
                            native
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                        averageScore
                    }
                }
            }
        """.trimIndent()

        return JSONObject()
            .put("query", graphql)
            .put("variables", variables)
            .toString()
    }

    private suspend fun fetchAnilistMediaById(anilistId: Int): JSONObject? {
        val requestBodyText = buildAnilistMediaByIdRequestBody(anilistId)
        val responseText = fetchAnilistGraphqlText(
            cacheKey = "AL:ID2:$anilistId",
            cacheProfile = TmdbCacheProfile.AnilistDetail,
            requestBodyText = requestBodyText,
        )

        return JSONObject(responseText)
            .optJSONObject("data")
            ?.optJSONObject("Media")
    }

    private fun buildAnilistMediaByIdRequestBody(anilistId: Int): String {
        val query = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    id
                    idMal
                    format
                    status
                    episodes
                    duration
                    isAdult
                    description(asHtml: false)
                    genres
                    startDate {
                        year
                    }
                    title {
                        userPreferred
                        english
                        romaji
                        native
                    }
                    coverImage {
                        extraLarge
                        large
                    }
                    bannerImage
                    averageScore
                    recommendations {
                        edges {
                            node {
                                mediaRecommendation {
                                    id
                                    idMal
                                    format
                                    title {
                                        userPreferred
                                        english
                                        romaji
                                        native
                                    }
                                    coverImage {
                                        extraLarge
                                        large
                                    }
                                    averageScore
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        return JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("id", anilistId))
            .toString()
    }

    private suspend fun fetchAnilistHomePage(
        section: AnilistHomeSection,
        page: Int,
        showScores: Boolean,
    ): AnilistHomePageResult {
        return runCatching {
            if (section.calendarToday) {
                fetchAnilistCalendarPage(section, page, showScores)
            } else {
                val requestBodyText = buildAnilistHomeRequestBody(section, page)
                val responseText = fetchAnilistGraphqlText(
                    cacheKey = "AL:POST:$requestBodyText",
                    cacheProfile = TmdbCacheProfile.AnilistHome,
                    requestBodyText = requestBodyText,
                )
                val (media, hasNext) = parseAnilistMediaPage(responseText)

                AnilistHomePageResult(
                    items = buildAnilistAnimeSearchResponses(media, section, showScores),
                    hasNext = hasNext,
                )
            }
        }.getOrElse { exception ->
            if (exception.isAnilistRateLimitFailure()) {
                AnilistHomePageResult(emptyList(), false)
            } else {
                throw exception
            }
        }
    }

    private fun buildAnilistHomeRequestBody(section: AnilistHomeSection, page: Int): String {
        val variables = JSONObject().apply {
            put("page", page.coerceAtLeast(1))
            put("perPage", anilistHomePageSize)
            put("sort", JSONArray().put(section.sort))
            put("formatIn", JSONArray(section.formats))
            section.status?.let { put("status", it) }
            if (section.currentSeason) {
                val season = getCurrentAnilistSeason()
                put("season", season.name)
                put("seasonYear", season.year)
            }
            if (section.releasedOnly) {
                put("startDateLesser", getTomorrowAnilistDateInt())
            }
        }
        val query = """
            query (
                ${'$'}page: Int,
                ${'$'}perPage: Int,
                ${'$'}sort: [MediaSort],
                ${'$'}formatIn: [MediaFormat],
                ${'$'}season: MediaSeason,
                ${'$'}seasonYear: Int,
                ${'$'}startDateLesser: FuzzyDateInt,
                ${'$'}status: MediaStatus
            ) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo {
                        hasNextPage
                    }
                    media(
                        type: ANIME,
                        isAdult: false,
                        sort: ${'$'}sort,
                        format_in: ${'$'}formatIn,
                        season: ${'$'}season,
                        seasonYear: ${'$'}seasonYear,
                        startDate_lesser: ${'$'}startDateLesser,
                        status: ${'$'}status
                    ) {
                        id
                        idMal
                        format
                        title {
                            userPreferred
                            english
                            romaji
                            native
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                        averageScore
                    }
                }
            }
        """.trimIndent()

        return JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
    }

    private suspend fun fetchAnilistCalendarPage(
        section: AnilistHomeSection,
        page: Int,
        showScores: Boolean,
    ): AnilistHomePageResult {
        val requestBodyText = buildAnilistCalendarRequestBody(page)
        val responseText = fetchAnilistGraphqlText(
            cacheKey = "AL:POST:$requestBodyText",
            cacheProfile = TmdbCacheProfile.AnilistHome,
            requestBodyText = requestBodyText,
        )
        val (media, hasNext) = parseAnilistCalendarPage(responseText, section)

        return AnilistHomePageResult(
            items = buildAnilistAnimeSearchResponses(media, section, showScores),
            hasNext = hasNext,
        )
    }

    private fun buildAnilistCalendarRequestBody(page: Int): String {
        val (start, end) = todayUnixRange()
        val variables = JSONObject().apply {
            put("page", page.coerceAtLeast(1))
            put("perPage", anilistHomePageSize)
            put("start", start)
            put("end", end)
        }
        val query = """
            query (
                ${'$'}page: Int,
                ${'$'}perPage: Int,
                ${'$'}start: Int,
                ${'$'}end: Int
            ) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo {
                        hasNextPage
                    }
                    airingSchedules(
                        airingAt_greater: ${'$'}start,
                        airingAt_lesser: ${'$'}end,
                        sort: TIME
                    ) {
                        episode
                        media {
                            id
                            idMal
                            format
                            title {
                                userPreferred
                                english
                                romaji
                                native
                            }
                            coverImage {
                                extraLarge
                                large
                            }
                            averageScore
                        }
                    }
                }
            }
        """.trimIndent()

        return JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
    }

    private fun parseAnilistMediaPage(text: String): Pair<List<AnilistMedia>, Boolean> {
        return runCatching {
            val page = JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?: return@runCatching emptyList<AnilistMedia>() to false
            val hasNext = page.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
            val mediaItems = page.optJSONArray("media") ?: JSONArray()
            val media = buildList {
                for (index in 0 until mediaItems.length()) {
                    mediaItems.optJSONObject(index)?.toAnilistMedia()?.let(::add)
                }
            }

            media to hasNext
        }.getOrDefault(emptyList<AnilistMedia>() to false)
    }

    private fun parseAnilistCalendarPage(
        text: String,
        section: AnilistHomeSection,
    ): Pair<List<AnilistMedia>, Boolean> {
        return runCatching {
            val page = JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?: return@runCatching emptyList<AnilistMedia>() to false
            val hasNext = page.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
            val schedules = page.optJSONArray("airingSchedules") ?: JSONArray()
            val media = buildList {
                val seen = mutableSetOf<Int>()
                for (index in 0 until schedules.length()) {
                    val mediaJson = schedules.optJSONObject(index)
                        ?.optJSONObject("media")
                        ?: continue
                    val format = mediaJson.optNullableString("format") ?: continue
                    if (format !in section.formats) continue
                    val episode = schedules.optJSONObject(index)?.optNullableInt("episode")
                    val item = mediaJson.toAnilistMedia(calendarEpisode = episode) ?: continue
                    if (seen.add(item.anilistId)) {
                        add(item)
                    }
                }
            }

            media to hasNext
        }.getOrDefault(emptyList<AnilistMedia>() to false)
    }

    private fun JSONObject.toAnilistMedia(calendarEpisode: Int? = null): AnilistMedia? {
        val anilistId = optNullableInt("id") ?: return null
        val titleJson = optJSONObject("title")
        val title = titleJson?.let { title ->
            listOf(
                title.optNullableString("userPreferred"),
                title.optNullableString("english"),
                title.optNullableString("romaji"),
                title.optNullableString("native"),
            ).firstOrNull { !it.isNullOrBlank() }
        } ?: return null
        val cover = optJSONObject("coverImage")?.let { coverImage ->
            coverImage.optNullableString("extraLarge")
                ?: coverImage.optNullableString("large")
        }

        return AnilistMedia(
            anilistId = anilistId,
            malId = optNullableInt("idMal"),
            format = optNullableString("format"),
            title = title,
            poster = cover,
            score = optNullableInt("averageScore")?.toDecimalScore(),
            calendarEpisode = calendarEpisode?.takeIf { it > 0 },
        )
    }

    private fun JSONObject.toAnilistLoadMetadata(): AnilistLoadMetadata? {
        val anilistId = optNullableInt("id") ?: return null
        val titleJson = optJSONObject("title")
        val titleCandidates = titleJson?.anilistTitleCandidates().orEmpty()
        val title = titleCandidates.firstOrNull() ?: return null
        val originalTitle = listOf(
            titleJson?.optNullableString("romaji"),
            titleJson?.optNullableString("native"),
        ).firstOrNull { !it.isNullOrBlank() && !it.equals(title, ignoreCase = true) }
        val cover = optJSONObject("coverImage")?.let { coverImage ->
            coverImage.optNullableString("extraLarge")
                ?: coverImage.optNullableString("large")
        }
        val genresJson = optJSONArray("genres")
        val genres = buildList {
            if (genresJson != null) {
                for (index in 0 until genresJson.length()) {
                    genresJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }

        return AnilistLoadMetadata(
            anilistId = anilistId,
            malId = optNullableInt("idMal"),
            title = title,
            titleCandidates = titleCandidates,
            originalTitle = originalTitle,
            format = optNullableString("format"),
            poster = cover,
            background = optNullableString("bannerImage"),
            plot = cleanText(optNullableString("description")),
            score = optNullableInt("averageScore")?.toDecimalScore(),
            year = optJSONObject("startDate")?.optNullableInt("year"),
            duration = optNullableInt("duration"),
            episodes = optNullableInt("episodes"),
            status = optNullableString("status"),
            isAdult = optBoolean("isAdult", false),
            tags = genres,
            recommendations = parseAnilistRecommendations(),
        )
    }

    private fun JSONObject.parseAnilistRecommendations(): List<SearchResponse> {
        val edges = optJSONObject("recommendations")
            ?.optJSONArray("edges")
            ?: return emptyList()

        return buildList {
            for (index in 0 until edges.length()) {
                val media = edges
                    .optJSONObject(index)
                    ?.optJSONObject("node")
                    ?.optJSONObject("mediaRecommendation")
                    ?.toAnilistMedia()
                    ?: continue
                add(
                    media.toAnilistOnlySearchResponse(
                        tvType = if (media.isMovieFormat()) TvType.AnimeMovie else TvType.Anime,
                    )
                )
            }
        }.distinctBy { it.url }
    }

    private suspend fun fetchAnilistMetadata(syncIds: List<AnimeSyncIds>): AnilistMetadata? {
        val syncIdsForLookup = syncIds.firstOrNull { it.anilistId != null || it.malId != null } ?: return null
        val cacheKey = "AL:DETAIL:${syncIdsForLookup.anilistId ?: 0}:${syncIdsForLookup.malId ?: 0}"
        anilistMetadataMemoryCache[cacheKey]?.let { return it }

        val requestBodyText = buildAnilistMetadataRequestBody(syncIdsForLookup)
        val responseText = fetchAnilistGraphqlText(
            cacheKey = "AL:POST:$requestBodyText",
            cacheProfile = TmdbCacheProfile.AnilistDetail,
            requestBodyText = requestBodyText,
        )

        return parseAnilistMetadata(responseText)?.also { anilistMetadataMemoryCache[cacheKey] = it }
    }

    private suspend fun fetchAnilistMetadataSafely(syncIds: List<AnimeSyncIds>): AnilistMetadata? {
        return runCatching { fetchAnilistMetadata(syncIds) }
            .getOrElse { exception ->
                if (exception.isAnilistRateLimitFailure()) null else throw exception
            }
    }

    private fun buildAnilistMetadataRequestBody(syncIds: AnimeSyncIds): String {
        val variables = JSONObject().apply {
            syncIds.anilistId?.let { put("id", it) }
            syncIds.malId?.let { put("malId", it) }
        }
        val query = """
            query (
                ${'$'}id: Int,
                ${'$'}malId: Int
            ) {
                Media(id: ${'$'}id, idMal: ${'$'}malId, type: ANIME) {
                    title {
                        userPreferred
                        english
                        romaji
                        native
                    }
                    averageScore
                }
            }
        """.trimIndent()

        return JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
    }

    private fun parseAnilistMetadata(text: String): AnilistMetadata? {
        return runCatching {
            val media = JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("Media")
                ?: return@runCatching null

            AnilistMetadata(
                title = media.optJSONObject("title")?.preferredAnilistTitle(),
                score = media.optNullableInt("averageScore")?.toDecimalScore(),
                titleCandidates = media.optJSONObject("title")?.anilistTitleCandidates().orEmpty(),
            )
        }.getOrNull()
    }

    private fun Int.toDecimalScore(): String {
        return String.format(Locale.US, "%.1f", this / 10.0)
    }

    private fun JSONObject.preferredAnilistTitle(): String? {
        return anilistTitleCandidates().firstOrNull()
    }

    private fun JSONObject.anilistTitleCandidates(): List<String> {
        return listOf(
            optNullableString("userPreferred"),
            optNullableString("english"),
            optNullableString("romaji"),
            optNullableString("native"),
        )
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private suspend fun fetchMyAnimeListMetadata(syncIds: List<AnimeSyncIds>): MyAnimeListMetadata? {
        val malId = syncIds.firstNotNullOfOrNull { it.malId } ?: return null
        val cacheKey = "MAL:DETAIL:$malId"
        myAnimeListMetadataMemoryCache[cacheKey]?.let { return it }

        val fullText = fetchJikanText(
            url = "https://api.jikan.moe/v4/anime/$malId/full",
            cacheKey = "MAL:FULL:$malId",
        )
        val charactersText = fetchJikanText(
            url = "https://api.jikan.moe/v4/anime/$malId/characters",
            cacheKey = "MAL:CHARACTERS:$malId",
        )
        if (fullText == null && charactersText == null) return null

        val metadata = MyAnimeListMetadata(
            trailerUrl = parseJikanTrailer(fullText),
            characters = parseJikanCharacters(charactersText),
        )

        return metadata.also { myAnimeListMetadataMemoryCache[cacheKey] = it }
    }

    private suspend fun fetchAniZipMetadata(
        syncIds: List<AnimeSyncIds>,
        anilistId: Int? = null,
        malId: Int? = null,
    ): AniZipMetadata? {
        val candidates = buildList {
            anilistId?.let { add("anilist_id" to it.toString()) }
            malId?.let { add("mal_id" to it.toString()) }
            syncIds.forEach { syncId ->
                syncId.anilistId?.let { add("anilist_id" to it.toString()) }
                syncId.malId?.let { add("mal_id" to it.toString()) }
            }
        }.distinct()

        for ((key, value) in candidates) {
            val cacheKey = "ANIZIP:$key:$value"
            aniZipMetadataMemoryCache[cacheKey]?.let { return it }
            val metadata = runCatching {
                val text = getCachedText(cacheKey, TmdbCacheProfile.AnimeMappings) {
                    app.get(
                        "https://api.ani.zip/mappings?$key=$value",
                        headers = mapOf("Accept" to "application/json"),
                        cacheTime = TmdbCacheProfile.AnimeMappings.cacheMinutes,
                        cacheUnit = TimeUnit.MINUTES,
                    ).text
                }
                parseAniZipMetadata(text)
            }.getOrNull()

            if (metadata != null) {
                aniZipMetadataMemoryCache[cacheKey] = metadata
                return metadata
            }
        }

        return null
    }

    private fun parseAniZipMetadata(text: String?): AniZipMetadata? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(text)
            val fallbackPoster = root.optJSONArray("images")?.preferredAniZipImage()
            val episodesJson = root.optJSONObject("episodes") ?: return@runCatching null
            val episodes = mutableMapOf<Int, AniZipEpisodeMetadata>()

            episodesJson.keys().forEach { key ->
                val episodeJson = episodesJson.optJSONObject(key) ?: return@forEach
                val episodeNumber = episodeJson.optNullableString("episode")?.toEpisodeNumber()
                    ?: key.toEpisodeNumber()
                    ?: return@forEach
                val runtime = episodeJson.optNullableInt("runtime")
                    ?: episodeJson.optNullableInt("length")
                episodes[episodeNumber] = AniZipEpisodeMetadata(
                    title = episodeJson.optJSONObject("title")?.preferredAniZipText(),
                    overview = cleanText(episodeJson.optNullableString("overview")),
                    image = episodeJson.optNullableString("image"),
                    runtime = runtime,
                    score = episodeJson.optNullableString("rating")?.toAniZipScore(),
                    airDate = episodeJson.optNullableString("airdate"),
                )
            }

            AniZipMetadata(
                fallbackPoster = fallbackPoster,
                episodes = episodes,
            ).takeIf { it.episodes.isNotEmpty() || it.fallbackPoster != null }
        }.getOrNull()
    }

    private fun JSONArray.preferredAniZipImage(): String? {
        val images = buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
        return images.firstNotNullOfOrNull { image ->
            image.takeIf { it.optNullableString("coverType").equals("Poster", ignoreCase = true) }
                ?.optNullableString("url")
        } ?: images.firstNotNullOfOrNull { it.optNullableString("url") }
    }

    private fun JSONObject.preferredAniZipText(): String? {
        return listOf("it", "en", "x-jat", "ja", "jp", "romaji")
            .firstNotNullOfOrNull { key -> optNullableString(key) }
            ?: keys().asSequence()
                .mapNotNull { key -> optNullableString(key) }
                .firstOrNull()
    }

    private fun String.toEpisodeNumber(): Int? {
        val numeric = trim().replace(',', '.').toDoubleOrNull() ?: return null
        val whole = numeric.toInt()
        return whole.takeIf { numeric == whole.toDouble() }
    }

    private fun String.toAniZipScore(): Score? {
        return trim()
            .replace(',', '.')
            .takeIf(String::isNotBlank)
            ?.let { Score.from10(it) }
    }

    private suspend fun fetchJikanText(url: String, cacheKey: String): String? {
        return runCatching {
            getCachedText(cacheKey, TmdbCacheProfile.MyAnimeListDetail) {
                app.get(
                    url,
                    headers = mapOf("Accept" to "application/json"),
                    cacheTime = TmdbCacheProfile.MyAnimeListDetail.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
                ).text
            }
        }.getOrNull()
    }

    private fun parseJikanTrailer(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("trailer")
                ?.toJikanTrailerUrl()
        }.getOrNull()
    }

    private fun JSONObject.toJikanTrailerUrl(): String? {
        optNullableString("url")?.let { return it }

        val youtubeId = optNullableString("youtube_id")
            ?: optNullableString("embed_url")
                ?.substringAfter("/embed/", "")
                ?.substringBefore("?")
                ?.substringBefore("/")
                ?.takeIf(String::isNotBlank)

        if (youtubeId != null) {
            return "https://www.youtube.com/watch?v=$youtubeId"
        }

        return optNullableString("embed_url")
    }

    private fun parseJikanCharacters(text: String?): List<ActorData> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val items = JSONObject(text).optJSONArray("data") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val character = item.optJSONObject("character") ?: continue
                    val name = character.optNullableString("name") ?: continue
                    val image = character.optJSONObject("images")?.preferredJikanImage()
                    val voiceActor = item.optJSONArray("voice_actors")?.preferredJikanVoiceActor()
                    val role = item.optNullableString("role")?.toJikanRoleLabel()
                    val roleString = listOfNotNull(role, voiceActor?.name?.let { "Voce: $it" })
                        .joinToString(" ")
                        .takeIf(String::isNotBlank)

                    add(
                        ActorData(
                            Actor(name, image),
                            role = item.optNullableString("role")?.toJikanActorRole(),
                            roleString = roleString,
                            voiceActor = voiceActor,
                        )
                    )
                }
            }.distinctBy { it.actor.name }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.preferredJikanImage(): String? {
        return optJSONObject("jpg")?.let { jpg ->
            jpg.optNullableString("image_url") ?: jpg.optNullableString("small_image_url")
        } ?: optJSONObject("webp")?.let { webp ->
            webp.optNullableString("image_url") ?: webp.optNullableString("small_image_url")
        }
    }

    private fun JSONArray.preferredJikanVoiceActor(): Actor? {
        for (index in 0 until length()) {
            val voiceActor = optJSONObject(index) ?: continue
            if (!voiceActor.optNullableString("language").equals("Japanese", ignoreCase = true)) continue
            return voiceActor.optJSONObject("person")?.toJikanActor()
        }

        return optJSONObject(0)?.optJSONObject("person")?.toJikanActor()
    }

    private fun JSONObject.toJikanActor(): Actor? {
        val name = optNullableString("name") ?: return null
        return Actor(name, optJSONObject("images")?.preferredJikanImage())
    }

    private fun String.toJikanRoleLabel(): String? {
        return when (lowercase(Locale.ROOT)) {
            "main" -> "Principale"
            "supporting" -> "Secondario"
            else -> null
        }
    }

    private suspend fun buildAnilistAnimeSearchResponses(
        media: List<AnilistMedia>,
        section: AnilistHomeSection,
        showScores: Boolean = true,
    ): List<SearchResponse> {
        return media
            .map { anilistMedia ->
                anilistMedia.toAnilistOnlySearchResponse(
                    tvType = if (section.isTvSeries) TvType.Anime else TvType.AnimeMovie,
                    showScore = showScores,
                )
            }
            .distinctBy { it.url }
    }

    private suspend fun buildAnilistSearchResponses(media: List<AnilistMedia>): List<SearchResponse> {
        return media
            .map { it.toAnilistOnlySearchResponse() }
            .distinctBy { it.url }
    }

    private fun AnilistMedia.isMovieFormat(): Boolean {
        return format == "MOVIE"
    }

    private fun AnilistMedia.toAnilistOnlySearchResponse(
        tvType: TvType? = null,
        showScore: Boolean = true,
    ): SearchResponse {
        return newAnimeSearchResponse(
            title,
            markAnilistOnlyUrl(this),
            tvType ?: if (isMovieFormat()) TvType.AnimeMovie else TvType.Anime,
        ) {
            this.posterUrl = poster
            calendarEpisode?.let { episode ->
                this.otherName = "Episodio $episode"
                this.episodes = mutableMapOf(DubStatus.Subbed to episode)
            }
            if (showScore) {
                this@toAnilistOnlySearchResponse.score?.let { this.score = Score.from(it, 10) }
            }
        }
    }

    private fun getCurrentAnilistSeason(): AnilistSeason {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val season = when (now.get(Calendar.MONTH) + 1) {
            in 1..3 -> "WINTER"
            in 4..6 -> "SPRING"
            in 7..9 -> "SUMMER"
            else -> "FALL"
        }

        return AnilistSeason(name = season, year = year)
    }

    private fun getTomorrowAnilistDateInt(): Int {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
        }.toAnilistDateInt()
    }

    private fun todayUnixRange(): Pair<Int, Int> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        return (start.timeInMillis / 1000L).toInt() to (end.timeInMillis / 1000L).toInt()
    }

    private fun Calendar.toAnilistDateInt(): Int {
        val year = get(Calendar.YEAR)
        val month = get(Calendar.MONTH) + 1
        val day = get(Calendar.DAY_OF_MONTH)
        return year * 10000 + month * 100 + day
    }

    private fun currentItalianWeekdayName(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Lunedi"
            Calendar.TUESDAY -> "Martedi"
            Calendar.WEDNESDAY -> "Mercoledi"
            Calendar.THURSDAY -> "Giovedi"
            Calendar.FRIDAY -> "Venerdi"
            Calendar.SATURDAY -> "Sabato"
            else -> "Domenica"
        }
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

    private suspend fun parseMediaCards(
        doc: Document,
        isAnime: Boolean = false,
        detectAnime: Boolean = false,
        excludeAnime: Boolean = false,
        fetchMissingScores: Boolean = false,
        showScores: Boolean = true,
        maxItems: Int? = null,
        includeTvCalendarEpisode: Boolean = false,
    ): List<SearchResponse> = coroutineScope {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        val tvCalendarCards = mutableListOf<kotlinx.coroutines.Deferred<SearchResponse>>()

        for (anchor in doc.select("a[href]")) {
            if (maxItems != null && results.size + tvCalendarCards.size >= maxItems) break
            val mediaType = getMediaType(anchor) ?: continue
            val href = anchor.attr("href").substringBefore("?")
            if (!seen.add(href)) continue

            val card = findMediaCard(anchor) ?: anchor
            val title = getCardTitle(card, anchor) ?: continue
            val poster = card.selectFirst("img.poster")?.extractImageUrl()
                ?: anchor.selectFirst("img.poster")?.extractImageUrl()
                ?: card.selectFirst("img.backdrop")?.extractImageUrl()
                ?: anchor.selectFirst("img.backdrop")?.extractImageUrl()
            val cardScore = extractCardScore(card, anchor)
            val itemUrl = normalizeTmdbUrl(href)
            val shouldCheckAnime = isAnime || detectAnime || excludeAnime
            val animeSyncIds = if (shouldCheckAnime) {
                fetchAnimeSyncIds(extractTmdbId(itemUrl), isTvSeries = mediaType == "tv")
            } else {
                emptyList()
            }
            if (excludeAnime && animeSyncIds.isNotEmpty()) continue

            val shouldBuildAnimeCard = isAnime || animeSyncIds.isNotEmpty()

            if (shouldBuildAnimeCard) {
                results += buildAnimeSearchResponses(
                    title = title,
                    itemUrl = itemUrl,
                    poster = poster,
                    mediaType = mediaType,
                    syncIds = animeSyncIds,
                    showScore = showScores,
                )
                continue
            }

            if (includeTvCalendarEpisode && mediaType == "tv") {
                tvCalendarCards += async(Dispatchers.IO) {
                    val calendarMetadata = fetchTmdbCalendarCardMetadata(itemUrl)
                    buildTvSeriesSearchResponse(
                        title = title,
                        itemUrl = itemUrl,
                        poster = poster,
                        tmdbScore = if (showScores) cardScore ?: calendarMetadata?.score else null,
                        calendarEpisode = calendarMetadata?.episode,
                    )
                }
                continue
            }

            val tmdbScore = if (showScores) {
                cardScore ?: if (fetchMissingScores) fetchTmdbScore(itemUrl) else null
            } else {
                null
            }
            results += if (mediaType == "tv") {
                buildTvSeriesSearchResponse(
                    title = title,
                    itemUrl = itemUrl,
                    poster = poster,
                    tmdbScore = tmdbScore,
                )
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                    tmdbScore?.let { this.score = Score.from(it, 10) }
                }
            }
        }

        val mergedResults = results + tvCalendarCards.awaitAll()
        maxItems?.let { mergedResults.take(it) } ?: mergedResults
    }

    private fun buildTvSeriesSearchResponse(
        title: String,
        itemUrl: String,
        poster: String?,
        tmdbScore: String?,
        calendarEpisode: TmdbCalendarEpisode? = null,
    ): SearchResponse {
        val displayTitle = calendarEpisode?.label?.let { "$title - $it" } ?: title
        return newTvSeriesSearchResponse(displayTitle, itemUrl, TvType.TvSeries) {
            this.posterUrl = poster
            calendarEpisode?.episodeNumber?.let { this.episodes = it }
            tmdbScore?.let { this.score = Score.from(it, 10) }
        }
    }

    private suspend fun fetchTmdbCalendarCardMetadata(url: String): TmdbCalendarCardMetadata? {
        return runCatching {
            val doc = getTmdbDocument(url, cacheProfile = TmdbCacheProfile.TvCalendarEpisode)
            TmdbCalendarCardMetadata(
                score = extractTmdbScore(doc),
                episode = extractTmdbCalendarEpisode(doc),
            )
        }.getOrNull()
    }

    private fun extractTmdbCalendarEpisode(doc: Document): TmdbCalendarEpisode? {
        val episodeLink = doc.selectFirst("section.panel.season a.name[href*=/episode/]")
            ?: doc.selectFirst("a.name[href*=/episode/]")
            ?: return null
        val href = episodeLink.attr("href")
        val match = Regex("""/season/(\d+)/episode/(\d+)""").find(href)
        val seasonNumber = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episodeNumber = match?.groupValues?.getOrNull(2)?.toIntOrNull()
        val numberLabel = when {
            seasonNumber != null && episodeNumber != null -> "${seasonNumber}x$episodeNumber"
            episodeNumber != null -> episodeNumber.toString()
            else -> null
        } ?: return null

        return TmdbCalendarEpisode(
            label = "Episodio $numberLabel",
            episodeNumber = episodeNumber,
        )
    }

    private suspend fun buildAnimeSearchResponses(
        title: String,
        itemUrl: String,
        poster: String?,
        mediaType: String,
        syncIds: List<AnimeSyncIds>,
        score: String? = null,
        useAnilistTitle: Boolean = true,
        showScore: Boolean = true,
    ): List<SearchResponse> {
        if (mediaType != "tv" || syncIds.isEmpty()) {
            val anilistMetadata = if (useAnilistTitle && syncIds.isNotEmpty()) {
                fetchAnilistMetadataSafely(syncIds)
            } else {
                null
            }
            val displayTitle = anilistMetadata?.title ?: title
            val displayScore = score ?: anilistMetadata?.score
            return listOf(
                newAnimeSearchResponse(
                    displayTitle,
                    markAnimeUrl(itemUrl, syncIds.firstOrNull()),
                    if (mediaType == "tv") TvType.Anime else TvType.AnimeMovie,
                ) {
                    this.posterUrl = poster
                    if (showScore) {
                        displayScore?.let { this.score = Score.from(it, 10) }
                    }
                }
            )
        }

        return syncIds.map { syncIdsForSeason ->
            val anilistMetadata = if (useAnilistTitle || score == null) {
                fetchAnilistMetadataSafely(listOf(syncIdsForSeason))
            } else {
                null
            }
            val displayTitle = if (useAnilistTitle) {
                anilistMetadata?.title ?: title
            } else {
                title
            }
            val displayScore = score ?: anilistMetadata?.score
            newAnimeSearchResponse(
                displayTitle,
                markAnimeUrl(itemUrl, syncIdsForSeason),
                TvType.Anime,
            ) {
                this.posterUrl = poster
                if (showScore) {
                    displayScore?.let { this.score = Score.from(it, 10) }
                }
            }
        }
    }

    private fun extractCardScore(card: Element, anchor: Element): String? {
        return listOf(card, anchor).firstNotNullOfOrNull { element ->
            extractTmdbScore(element)
        }
    }

    private suspend fun fetchTmdbScore(url: String): String? {
        return runCatching {
            val normalizedUrl = stripStreamCenterParams(normalizeTmdbUrl(url))
            tmdbScoreMemoryCache[normalizedUrl]?.let { return@runCatching it.takeIf(String::isNotBlank) }

            val score = getCachedText(
                cacheKey = "TMDB:SCORE:$normalizedUrl",
                cacheProfile = TmdbCacheProfile.TmdbScore,
                cacheBlank = true,
            ) {
                extractTmdbScore(getTmdbDocument(normalizedUrl, cacheProfile = TmdbCacheProfile.Detail)).orEmpty()
            }
            tmdbScoreMemoryCache[normalizedUrl] = score
            score.takeIf(String::isNotBlank)
        }.getOrNull()
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

    private fun getMediaType(anchor: Element): String? {
        val dataMediaType = anchor.attr("data-media-type")
            .takeIf { it == "movie" || it == "tv" }
        if (dataMediaType != null) return dataMediaType

        val href = anchor.attr("href")
        return when {
            Regex("""/movie/\d+""").containsMatchIn(href) -> "movie"
            Regex("""/tv/\d+""").containsMatchIn(href) -> "tv"
            else -> null
        }
    }

    private fun findMediaCard(anchor: Element): Element? {
        return anchor.parents().firstOrNull { parent ->
            val className = parent.className()
            className.contains("poster-card") ||
                className.contains("media-card") ||
                className.contains("mini_card")
        }
    }

    private fun getCardTitle(card: Element, anchor: Element): String? {
        val title = card.selectFirst("h2 span")?.text()?.trim()
            ?: anchor.selectFirst("h2 span")?.text()?.trim()
            ?: card.selectFirst("a.title bdi")?.text()?.trim()
            ?: anchor.selectFirst("bdi")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: card.selectFirst("img.poster")?.attr("alt")?.trim()
            ?: anchor.selectFirst("img.poster")?.attr("alt")?.trim()
            ?: card.selectFirst("img.backdrop")?.attr("alt")?.trim()
            ?: anchor.selectFirst("img.backdrop")?.attr("alt")?.trim()

        return title?.takeIf { it.isNotBlank() }
    }

    private fun isAnimeSection(url: String): Boolean {
        return url.contains(animeKeywordPath) || parseAnilistSection(url) != null
    }

    private fun isTmdbTvCalendarSection(url: String): Boolean {
        return url.contains("/tv/airing-today")
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

    private fun extractMalIdFromText(text: String): Int? {
        return Regex("""myanimelist\.net/anime/(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun markAnilistOnlyUrl(media: AnilistMedia): String {
        val params = buildList {
            add(animeMarker)
            add("$animeAnilistParam=${media.anilistId}")
            media.malId?.let { add("$animeMalParam=$it") }
        }

        return "$mainUrl${anilistOnlyPath}${media.anilistId}?${params.joinToString("&")}"
    }

    private fun markAnimeUrl(url: String, syncIds: AnimeSyncIds? = null): String {
        val params = buildList {
            if (!url.contains(animeMarker)) add(animeMarker)
            syncIds?.anilistId?.let { add("$animeAnilistParam=$it") }
            syncIds?.malId?.let { add("$animeMalParam=$it") }
            syncIds?.tmdbSeason?.let { add("$animeTmdbSeasonParam=$it") }
            syncIds?.displaySeason?.let { add("$animeDisplaySeasonParam=$it") }
        }
        if (params.isEmpty()) return url

        val separator = if ("?" in url) "&" else "?"
        return "$url$separator${params.joinToString("&")}"
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
        return doc.selectFirst("section.header.poster h2 a")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""
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

    private fun cleanText(text: String?): String? {
        return text
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "-" }
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
        return doc.select("section.keywords li a.rounded")
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

    private fun mapAnilistShowStatus(status: String?): ShowStatus? {
        return when (status) {
            "FINISHED", "CANCELLED" -> ShowStatus.Completed
            "RELEASING" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun AnilistLoadMetadata.toStreamCenterMetadata(): StreamCenterMetadata {
        return StreamCenterMetadata(
            title = title,
            originalTitle = originalTitle,
            plot = plot,
            poster = poster,
            background = background,
            tags = tags,
            year = year,
            tmdbId = null,
            score = score,
            people = emptyList(),
            recommendations = recommendations,
            contentRating = if (isAdult) "18+" else null,
            showStatus = mapAnilistShowStatus(status),
            comingSoon = status == "NOT_YET_RELEASED",
            duration = duration,
            trailerUrl = null,
        )
    }

    private fun buildAnilistFallbackEpisodes(
        metadata: AnilistLoadMetadata,
        aniZipMetadata: AniZipMetadata? = null,
    ): List<Episode> {
        val totalEpisodes = metadata.episodes ?: return emptyList()
        if (totalEpisodes <= 0) return emptyList()

        return (1..totalEpisodes).map { episodeNumber ->
            val aniZipEpisode = aniZipMetadata?.episode(episodeNumber)
            newEpisode(StreamCenterPlaybackData().toJson()) {
                this.name = aniZipEpisode?.title ?: "Episodio $episodeNumber"
                this.season = 1
                this.episode = episodeNumber
                this.posterUrl = aniZipEpisode?.image ?: aniZipMetadata?.fallbackPoster
                this.description = aniZipEpisode?.overview
                this.runTime = aniZipEpisode?.runtime
                this.score = aniZipEpisode?.score
                aniZipEpisode?.airDate?.let { this.addDate(it) }
            }
        }
    }

    private fun String.toJikanActorRole(): ActorRole? {
        return when (lowercase(Locale.ROOT)) {
            "main" -> ActorRole.Main
            "supporting" -> ActorRole.Supporting
            else -> null
        }
    }

    private suspend fun fetchStreamingCommunityTitle(
        metadata: StreamCenterMetadata,
        isTvSeries: Boolean,
    ): StreamingCommunityTitle? {
        val expectedType = if (isTvSeries) "tv" else "movie"
        val expectedTmdbId = metadata.tmdbId?.toIntOrNull()
        val titleCandidates = listOfNotNull(metadata.title, metadata.originalTitle)
            .map { it.replace(Regex("""\(\d{4}\)"""), "").trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val cacheKey = listOf(
            expectedType,
            expectedTmdbId?.toString().orEmpty(),
            titleCandidates.joinToString("|") { it.lowercase(Locale.ROOT) },
        ).joinToString(":")
        if (streamingCommunityTitleMemoryCache.containsKey(cacheKey)) {
            return streamingCommunityTitleMemoryCache[cacheKey]
        }

        val searchCandidates = titleCandidates
            .flatMap { query -> fetchStreamingCommunitySearchResults(query) }
            .filter { it.type == expectedType }
            .distinctBy { it.id }

        var fallback: StreamingCommunityTitle? = null
        for (candidate in searchCandidates.take(8)) {
            val detail = fetchStreamingCommunityTitleDetail(candidate) ?: continue
            if (expectedTmdbId != null && detail.tmdbId == expectedTmdbId) {
                streamingCommunityTitleMemoryCache[cacheKey] = detail
                return detail
            }
            if (fallback == null && detail.matchesStreamingCommunityFallback(metadata, expectedType)) {
                fallback = detail
            }
        }

        return (if (expectedTmdbId == null) fallback else null)
            .also { streamingCommunityTitleMemoryCache[cacheKey] = it }
    }

    private suspend fun fetchStreamingCommunitySearchResults(query: String): List<StreamingCommunityTitle> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        streamingCommunitySearchMemoryCache[normalizedQuery]?.let { return it }

        val text = getCachedText(
            "SC:GET:$streamingCommunityMainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}",
            TmdbCacheProfile.StreamingCommunitySearch,
        ) {
            app.get(
                "$streamingCommunityMainUrl/search",
                params = mapOf("q" to query),
                cacheTime = TmdbCacheProfile.StreamingCommunitySearch.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return parseStreamingCommunitySearchResults(text)
            .also { streamingCommunitySearchMemoryCache[normalizedQuery] = it }
    }

    private fun parseStreamingCommunitySearchResults(text: String): List<StreamingCommunityTitle> {
        return runCatching {
            val json = JSONObject(extractStreamingCommunityPageJson(text) ?: text)
            val titles = json.optJSONArray("data")
                ?: json.optJSONObject("props")?.optJSONArray("titles")
                ?: JSONArray()
            buildList {
                for (index in 0 until titles.length()) {
                    titles.optJSONObject(index)?.toStreamingCommunityTitle()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchStreamingCommunityTitleDetail(
        title: StreamingCommunityTitle,
    ): StreamingCommunityTitle? {
        ensureStreamingCommunityHeaders()
        val url = "$streamingCommunityMainUrl/titles/${title.id}-${title.slug}"
        val memoryKey = "$url:$streamingCommunityInertiaVersion"
        streamingCommunityDetailMemoryCache[memoryKey]?.let { return it }
        val text = getCachedText(
            "SC:GET:$url:$streamingCommunityInertiaVersion",
            TmdbCacheProfile.StreamingCommunityDetail,
        ) {
            app.get(
                url,
                headers = streamingCommunityHeaders,
                cacheTime = TmdbCacheProfile.StreamingCommunityDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return (parseStreamingCommunityTitleDetail(text) ?: title)
            .also { streamingCommunityDetailMemoryCache[memoryKey] = it }
    }

    private fun parseStreamingCommunityTitleDetail(text: String): StreamingCommunityTitle? {
        return runCatching {
            val json = JSONObject(extractStreamingCommunityPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            val title = props.optJSONObject("title")?.toStreamingCommunityTitle() ?: return null
            val loadedSeason = props.optJSONObject("loadedSeason")?.toStreamingCommunitySeason()
            if (loadedSeason == null) {
                title
            } else {
                title.copy(seasons = title.seasons.mergeStreamingCommunitySeason(loadedSeason))
            }
        }.getOrNull()
    }

    private suspend fun fetchStreamingCommunitySeason(
        title: StreamingCommunityTitle,
        seasonNumber: Int,
    ): StreamingCommunitySeason? {
        ensureStreamingCommunityHeaders()
        val url = "$streamingCommunityMainUrl/titles/${title.id}-${title.slug}/season-$seasonNumber"
        val memoryKey = "$url:$streamingCommunityInertiaVersion"
        streamingCommunitySeasonMemoryCache[memoryKey]?.let { return it }
        val text = getCachedText(
            "SC:GET:$url:$streamingCommunityInertiaVersion",
            TmdbCacheProfile.StreamingCommunityDetail,
        ) {
            app.get(
                url,
                headers = streamingCommunityHeaders,
                cacheTime = TmdbCacheProfile.StreamingCommunityDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return runCatching {
            val json = JSONObject(extractStreamingCommunityPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            props.optJSONObject("loadedSeason")?.toStreamingCommunitySeason()
        }.getOrNull()?.also { streamingCommunitySeasonMemoryCache[memoryKey] = it }
    }

    private suspend fun fetchStreamingCommunityEpisodePayloads(
        title: StreamingCommunityTitle,
    ): Map<Pair<Int, Int>, StreamingCommunityPlaybackData> {
        if (title.type != "tv") return emptyMap()
        val cacheKey = "${title.id}:${title.slug}"
        streamingCommunityEpisodePayloadsMemoryCache[cacheKey]?.let { return it }

        val episodes = linkedMapOf<Pair<Int, Int>, StreamingCommunityPlaybackData>()
        for (season in title.seasons) {
            val seasonWithEpisodes = if (season.episodes.isNotEmpty()) {
                season
            } else {
                fetchStreamingCommunitySeason(title, season.number) ?: season
            }

            seasonWithEpisodes.episodes.forEach { episode ->
                episodes[seasonWithEpisodes.number to episode.number] = StreamingCommunityPlaybackData(
                    iframeUrl = "$streamingCommunityMainUrl/iframe/${title.id}?episode_id=${episode.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = seasonWithEpisodes.number,
                    episodeNumber = episode.number,
                )
            }
        }

        return episodes.also { streamingCommunityEpisodePayloadsMemoryCache[cacheKey] = it }
    }

    private fun StreamingCommunityTitle.toStreamingCommunityMoviePlayback(): StreamingCommunityPlaybackData? {
        if (type != "movie") return null
        return StreamingCommunityPlaybackData(
            iframeUrl = "$streamingCommunityMainUrl/iframe/$id&canPlayFHD=1",
            type = type,
            tmdbId = tmdbId,
        )
    }

    private fun hasStreamingCommunitySessionHeaders(): Boolean {
        return streamingCommunityHeaders["Cookie"].orEmpty().isNotBlank() &&
            streamingCommunityInertiaVersion.isNotBlank()
    }

    private fun applyStreamingCommunitySession(cookie: String, xsrfToken: String, inertiaVersion: String) {
        streamingCommunityHeaders["Cookie"] = cookie
        streamingCommunityXsrfToken = xsrfToken
        streamingCommunityInertiaVersion = inertiaVersion
        streamingCommunityHeaders["X-Inertia-Version"] = streamingCommunityInertiaVersion
        if (streamingCommunityXsrfToken.isNotBlank()) {
            streamingCommunityHeaders["X-XSRF-TOKEN"] = streamingCommunityXsrfToken
        } else {
            streamingCommunityHeaders.remove("X-XSRF-TOKEN")
        }
    }

    private fun restoreStreamingCommunitySession(): Boolean {
        val raw = tmdbTextCache.get("SC:SESSION") ?: return false
        return runCatching {
            val json = JSONObject(raw)
            val cookie = json.optString("cookie")
            val inertiaVersion = json.optString("inertiaVersion")
            if (cookie.isBlank() || inertiaVersion.isBlank()) return false

            applyStreamingCommunitySession(
                cookie = cookie,
                xsrfToken = json.optString("xsrfToken"),
                inertiaVersion = inertiaVersion,
            )
            true
        }.getOrDefault(false)
    }

    private fun persistStreamingCommunitySession() {
        if (!hasStreamingCommunitySessionHeaders()) return
        val payload = JSONObject()
            .put("cookie", streamingCommunityHeaders["Cookie"].orEmpty())
            .put("xsrfToken", streamingCommunityXsrfToken)
            .put("inertiaVersion", streamingCommunityInertiaVersion)
            .toString()
        tmdbTextCache.put("SC:SESSION", payload, TmdbCacheProfile.StreamingCommunitySession.ttlMs)
    }

    private suspend fun ensureStreamingCommunityHeaders(forceRefresh: Boolean = false) {
        if (hasStreamingCommunitySessionHeaders() && !forceRefresh) return
        if (!forceRefresh && restoreStreamingCommunitySession()) return

        val response = app.get("$streamingCommunityMainUrl/archive")
        val cookieJar = linkedMapOf<String, String>()
        response.cookies.forEach { cookieJar[it.key] = it.value }

        val csrfResponse = app.get(
            "${streamingCommunityRootUrl}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "$streamingCommunityMainUrl/",
                "X-Requested-With" to "XMLHttpRequest",
            )
        )
        csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

        applyStreamingCommunitySession(
            cookie = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" },
            xsrfToken = cookieJar["XSRF-TOKEN"]
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                .orEmpty(),
            inertiaVersion = response.document
                .select("#app")
                .attr("data-page")
                .substringAfter("\"version\":\"")
                .substringBefore("\""),
        )
        persistStreamingCommunitySession()
    }

    private fun extractStreamingCommunityPageJson(payload: String): String? {
        val trimmedPayload = payload.trimStart()
        if (!trimmedPayload.startsWith("<")) return null
        val dataPageRaw = Jsoup.parse(payload).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    private fun JSONObject.toStreamingCommunityTitle(): StreamingCommunityTitle? {
        val id = optNullableInt("id") ?: return null
        val slug = optNullableString("slug") ?: return null
        val name = optNullableString("name") ?: return null
        val type = optNullableString("type") ?: return null
        val seasons = optJSONArray("seasons")?.let { seasonsJson ->
            buildList {
                for (index in 0 until seasonsJson.length()) {
                    seasonsJson.optJSONObject(index)?.toStreamingCommunitySeason()?.let(::add)
                }
            }
        }.orEmpty()

        return StreamingCommunityTitle(
            id = id,
            slug = slug,
            name = name,
            type = type,
            tmdbId = optNullableInt("tmdb_id"),
            year = optNullableString("release_date")?.substringBefore('-')?.toIntOrNull(),
            seasons = seasons,
        )
    }

    private fun JSONObject.toStreamingCommunitySeason(): StreamingCommunitySeason? {
        val id = optNullableInt("id") ?: return null
        val number = optNullableInt("number") ?: return null
        val episodes = optJSONArray("episodes")?.let { episodesJson ->
            buildList {
                for (index in 0 until episodesJson.length()) {
                    episodesJson.optJSONObject(index)?.toStreamingCommunityEpisode()?.let(::add)
                }
            }
        }.orEmpty()

        return StreamingCommunitySeason(
            id = id,
            number = number,
            episodes = episodes,
        )
    }

    private fun JSONObject.toStreamingCommunityEpisode(): StreamingCommunityEpisode? {
        return StreamingCommunityEpisode(
            id = optNullableInt("id") ?: return null,
            number = optNullableInt("number") ?: return null,
        )
    }

    private fun List<StreamingCommunitySeason>.mergeStreamingCommunitySeason(
        loadedSeason: StreamingCommunitySeason,
    ): List<StreamingCommunitySeason> {
        if (none { it.number == loadedSeason.number || it.id == loadedSeason.id }) {
            return this + loadedSeason
        }

        return map { season ->
            if (season.number == loadedSeason.number || season.id == loadedSeason.id) loadedSeason else season
        }
    }

    private fun StreamingCommunityTitle.matchesStreamingCommunityFallback(
        metadata: StreamCenterMetadata,
        expectedType: String,
    ): Boolean {
        if (type != expectedType) return false
        val titleMatches = normalizeLookupTitle(name) == normalizeLookupTitle(metadata.title) ||
            normalizeLookupTitle(name) == normalizeLookupTitle(metadata.originalTitle)
        val yearMatches = metadata.year == null || year == null || metadata.year == year
        return titleMatches && yearMatches
    }

    private fun normalizeLookupTitle(title: String?): String {
        return title.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private suspend fun fetchAnimeMappings(): JSONArray {
        animeMappingsMemoryCache?.let { return it }
        val responseText = getCachedText("GET:$animeListMappingUrl", TmdbCacheProfile.AnimeMappings) {
            app.get(
                animeListMappingUrl,
                cacheTime = TmdbCacheProfile.AnimeMappings.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return JSONArray(responseText).also { animeMappingsMemoryCache = it }
    }

    private suspend fun fetchAllAnimeSyncIds(): List<AnimeSyncIds> {
        animeSyncIdsListMemoryCache?.let { return it }
        return runCatching {
            val items = fetchAnimeMappings()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val numericTmdbId = item.optNullableInt("themoviedb_id") ?: continue

                    val type = item.optString("type").takeIf(String::isNotBlank)
                    add(
                        AnimeSyncIds(
                            tmdbId = numericTmdbId,
                            tmdbSeason = item.optJSONObject("season")?.optNullableInt("tmdb"),
                            displaySeason = null,
                            anilistId = item.optNullableInt("anilist_id"),
                            malId = item.optNullableInt("mal_id"),
                            type = type,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .also { animeSyncIdsListMemoryCache = it }
    }

    private suspend fun fetchAnimeSyncIdsByTmdb(): Map<String, List<AnimeSyncIds>> {
        animeSyncIdsByTmdbMemoryCache?.let { return it }
        return fetchAllAnimeSyncIds()
            .groupBy { "${it.tmdbId}:${it.isTvSeriesMapping()}" }
            .mapValues { (key, values) ->
                val isTvSeries = key.substringAfter(':').toBoolean()
                values
                    .distinctBy { listOf(it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1) }
                    .mapIndexed { index, syncIds ->
                        syncIds.copy(displaySeason = if (isTvSeries) index + 1 else syncIds.tmdbSeason)
                    }
            }
            .also { animeSyncIdsByTmdbMemoryCache = it }
    }

    private suspend fun fetchAnimeSyncIdsByExternalId(): Map<String, List<AnimeSyncIds>> {
        animeSyncIdsByExternalIdMemoryCache?.let { return it }
        val grouped = mutableMapOf<String, MutableList<AnimeSyncIds>>()

        fun add(key: String, syncIds: AnimeSyncIds) {
            grouped.getOrPut(key) { mutableListOf() } += syncIds
        }

        fetchAnimeSyncIdsByTmdb().values.flatten().forEach { syncIds ->
            val isTvSeries = syncIds.isTvSeriesMapping()
            syncIds.anilistId?.let { add("AL:$it:$isTvSeries", syncIds) }
            syncIds.malId?.let { add("MAL:$it:$isTvSeries", syncIds) }
        }

        return grouped.mapValues { (_, values) ->
            values.distinctBy { listOf(it.tmdbId, it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1) }
        }.also { animeSyncIdsByExternalIdMemoryCache = it }
    }

    private suspend fun fetchAnimeSyncIds(tmdbId: String?, isTvSeries: Boolean): List<AnimeSyncIds> {
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return emptyList()
        val memoryCacheKey = "$numericTmdbId:$isTvSeries"
        animeSyncIdsMemoryCache[memoryCacheKey]?.let { return it }

        return fetchAnimeSyncIdsByTmdb()["$numericTmdbId:$isTvSeries"]
            .orEmpty()
            .also { animeSyncIdsMemoryCache[memoryCacheKey] = it }
    }

    private suspend fun fetchAnimeSyncIdsForAnilist(
        anilistId: Int,
        malId: Int?,
        isTvSeries: Boolean,
    ): List<AnimeSyncIds> {
        val memoryCacheKey = "AL:$anilistId:${malId ?: 0}:$isTvSeries"
        animeSyncIdsMemoryCache[memoryCacheKey]?.let { return it }

        val externalIndex = fetchAnimeSyncIdsByExternalId()
        return (
            externalIndex["AL:$anilistId:$isTvSeries"].orEmpty() +
                malId?.let { externalIndex["MAL:$it:$isTvSeries"].orEmpty() }.orEmpty()
            )
            .distinctBy { listOf(it.tmdbId, it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1) }
            .also { animeSyncIdsMemoryCache[memoryCacheKey] = it }
    }

    private fun AnimeSyncIds.isTvSeriesMapping(): Boolean {
        return !type.equals("Movie", ignoreCase = true)
    }

    private fun pickPrimaryAnimeSyncIds(syncIds: List<AnimeSyncIds>): AnimeSyncIds? {
        return syncIds.firstOrNull { it.tmdbSeason == null } ?: syncIds.firstOrNull()
    }

    private fun selectAnimeSyncIds(
        syncIds: List<AnimeSyncIds>,
        selection: StreamCenterAnimeSelection?,
    ): List<AnimeSyncIds> {
        selection ?: return syncIds
        return syncIds.filter { syncIdsForSeason ->
            (selection.anilistId != null && syncIdsForSeason.anilistId == selection.anilistId) ||
                (selection.malId != null && syncIdsForSeason.malId == selection.malId) ||
                (selection.displaySeason != null && syncIdsForSeason.displaySeason == selection.displaySeason)
        }.ifEmpty { syncIds }
    }

    private fun parseAnimeSelection(url: String): StreamCenterAnimeSelection? {
        val params = parseQueryParams(url)
        val selection = StreamCenterAnimeSelection(
            tmdbSeason = params[animeTmdbSeasonParam]?.toIntOrNull(),
            displaySeason = params[animeDisplaySeasonParam]?.toIntOrNull(),
            anilistId = params[animeAnilistParam]?.toIntOrNull(),
            malId = params[animeMalParam]?.toIntOrNull(),
        )

        return selection.takeIf {
            it.tmdbSeason != null || it.displaySeason != null || it.anilistId != null || it.malId != null
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

    private fun hasAnimeDubSources(
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<SaturnTitleSources>,
    ): Boolean {
        return animeUnitySources.any { it.dubSources.isNotEmpty() } ||
            animeWorldSources.any { it.dubSources.isNotEmpty() } ||
            animeSaturnSources.any { it.dubSources.isNotEmpty() }
    }

    private suspend fun fetchAnimeUnitySources(
        metadata: StreamCenterMetadata,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeUnityTitleSources> {
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = listOfNotNull(metadata.title, metadata.originalTitle)
            .map { it.replace(Regex("""\(\d{4}\)"""), "").trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        return syncIds.mapNotNull { sync ->
            val variants = findAnimeUnityVariants(sync, titleCandidates)
            if (variants.isEmpty()) return@mapNotNull null

            val subAnime = variants.firstOrNull { !it.isDub }
            val dubAnime = variants.firstOrNull { it.isDub }
            val subPage = subAnime?.let { fetchAnimeUnityPageData(it) }
            val dubPage = dubAnime?.let { fetchAnimeUnityPageData(it) }
            val subSources = buildAnimeUnityEpisodeSources(subPage)
            val dubSources = buildAnimeUnityEpisodeSources(dubPage)

            AnimeUnityTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun findAnimeUnityVariants(
        syncIds: AnimeSyncIds,
        titleCandidates: List<String>,
    ): List<AnimeUnityAnime> {
        val candidates = linkedMapOf<Int, AnimeUnityAnime>()
        titleCandidates.forEach { title ->
            fetchAnimeUnityArchive(title).forEach { anime ->
                if (!candidates.containsKey(anime.id)) {
                    candidates[anime.id] = anime
                }
            }
        }

        val exactMatches = candidates.values.filter { anime -> anime.matches(syncIds) }
        if (exactMatches.isEmpty()) return emptyList()

        val matchedContentKeys = exactMatches.map { it.contentKey() }.toSet()
        return candidates.values
            .filter { it.contentKey() in matchedContentKeys || it.matches(syncIds) }
            .distinctBy(AnimeUnityAnime::id)
            .sortedWith(compareBy<AnimeUnityAnime> { if (it.isDub) 1 else 0 }.thenBy { it.id })
    }

    private suspend fun fetchAnimeUnityArchive(title: String): List<AnimeUnityAnime> {
        ensureAnimeUnityHeaders()
        val body = buildAnimeUnityArchiveBody(title)
        val requestBody = body.toRequestBody("application/json;charset=utf-8".toMediaType())
        val cacheKey = "POST:$animeUnityUrl/archivio/get-animes:$body"
        val text = getCachedText(cacheKey, TmdbCacheProfile.AnimeUnityArchive) {
            app.post(
                "$animeUnityUrl/archivio/get-animes",
                headers = animeUnityRequestHeaders(),
                requestBody = requestBody,
                cacheTime = TmdbCacheProfile.AnimeUnityArchive.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }

        return parseAnimeUnityArchive(text)
    }

    private fun buildAnimeUnityArchiveBody(title: String): String {
        return JSONObject().apply {
            put("title", title)
            put("type", false)
            put("year", false)
            put("order", false)
            put("status", false)
            put("genres", false)
            put("season", false)
            put("dubbed", 0)
            put("offset", 0)
        }.toString()
    }

    private fun hasAnimeUnitySessionHeaders(): Boolean {
        return animeUnitySessionHeaders["Cookie"].orEmpty().isNotBlank() &&
            animeUnitySessionHeaders["X-CSRF-Token"].orEmpty().isNotBlank()
    }

    private fun applyAnimeUnitySession(cookie: String, csrfToken: String) {
        animeUnitySessionHeaders.putAll(
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json;charset=utf-8",
                "X-CSRF-Token" to csrfToken,
                "Referer" to animeUnityUrl,
                "Cookie" to cookie,
            )
        )
    }

    private fun restoreAnimeUnitySession(): Boolean {
        val raw = tmdbTextCache.get("AU:SESSION") ?: return false
        return runCatching {
            val json = JSONObject(raw)
            val cookie = json.optString("cookie")
            val csrfToken = json.optString("csrfToken")
            if (cookie.isBlank() || csrfToken.isBlank()) return false

            applyAnimeUnitySession(cookie, csrfToken)
            true
        }.getOrDefault(false)
    }

    private fun persistAnimeUnitySession() {
        if (!hasAnimeUnitySessionHeaders()) return
        val payload = JSONObject()
            .put("cookie", animeUnitySessionHeaders["Cookie"].orEmpty())
            .put("csrfToken", animeUnitySessionHeaders["X-CSRF-Token"].orEmpty())
            .toString()
        tmdbTextCache.put("AU:SESSION", payload, TmdbCacheProfile.AnimeUnitySession.ttlMs)
    }

    private fun animeUnityRequestHeaders(): Map<String, String> {
        return animeUnitySessionHeaders + mapOf("Host" to hostFromUrl(animeUnityUrl))
    }

    private suspend fun ensureAnimeUnityHeaders(forceRefresh: Boolean = false) {
        if (hasAnimeUnitySessionHeaders() && !forceRefresh) return
        if (!forceRefresh && restoreAnimeUnitySession()) return

        val response = app.get("$animeUnityUrl/archivio", headers = animeUnityRequestHeaders())
        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies = listOfNotNull(
            response.cookies["XSRF-TOKEN"]?.let { "XSRF-TOKEN=$it" },
            response.cookies["animeunity_session"]?.let { "animeunity_session=$it" },
        ).joinToString("; ")

        applyAnimeUnitySession(cookies, csrfToken)
        persistAnimeUnitySession()
    }

    private fun parseAnimeUnityArchive(text: String): List<AnimeUnityAnime> {
        return runCatching {
            val records = JSONObject(text).optJSONArray("records") ?: JSONArray()
            buildList {
                for (index in 0 until records.length()) {
                    records.optJSONObject(index)?.toAnimeUnityAnime()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchAnimeUnityPageData(anime: AnimeUnityAnime): AnimeUnityPageData? {
        val url = "$animeUnityUrl/anime/${anime.id}-${anime.slug}"
        val html = getCachedText("GET:$url", TmdbCacheProfile.AnimeUnityDetail) {
            app.get(
                url,
                cacheTime = TmdbCacheProfile.AnimeUnityDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, url)
        val videoPlayer = doc.selectFirst("video-player") ?: return null
        val pageAnime = videoPlayer.attr("anime")
            .takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it).toAnimeUnityAnime() }.getOrNull() }
            ?: anime
        val initialEpisodes = parseAnimeUnityEpisodes(videoPlayer.attr("episodes"))
        val totalEpisodes = videoPlayer.attr("episodes_count").toIntOrNull() ?: initialEpisodes.size
        val allEpisodes = fetchAllAnimeUnityEpisodes(pageAnime, initialEpisodes, totalEpisodes)

        return AnimeUnityPageData(
            anime = pageAnime,
            episodes = allEpisodes,
        )
    }

    private suspend fun fetchAllAnimeUnityEpisodes(
        anime: AnimeUnityAnime,
        initialEpisodes: List<AnimeUnityEpisodeInfo>,
        totalEpisodes: Int,
    ): List<AnimeUnityEpisodeInfo> {
        if (totalEpisodes <= 120) return initialEpisodes

        val episodes = initialEpisodes.toMutableList()
        val pageCount = if (totalEpisodes % 120 == 0) totalEpisodes / 120 else (totalEpisodes / 120) + 1
        for (page in 2..pageCount) {
            val startRange = 1 + (page - 1) * 120
            val endRange = if (page == pageCount) totalEpisodes else page * 120
            val infoUrl = "$animeUnityUrl/info_api/${anime.id}/1?start_range=$startRange&end_range=$endRange"
            val text = getCachedText("GET:$infoUrl", TmdbCacheProfile.AnimeUnityDetail) {
                app.get(
                    infoUrl,
                    cacheTime = TmdbCacheProfile.AnimeUnityDetail.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
                ).text
            }
            episodes += parseAnimeUnityEpisodes(JSONObject(text).optJSONArray("episodes")?.toString().orEmpty())
        }

        return episodes.distinctBy { it.id }
    }

    private fun parseAnimeUnityEpisodes(text: String): List<AnimeUnityEpisodeInfo> {
        return runCatching {
            val episodes = JSONArray(text)
            buildList {
                for (index in 0 until episodes.length()) {
                    val episode = episodes.optJSONObject(index) ?: continue
                    val id = episode.optNullableInt("id") ?: continue
                    val number = episode.optString("number").takeIf(String::isNotBlank) ?: continue
                    add(AnimeUnityEpisodeInfo(id = id, number = number))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun buildAnimeUnityEpisodeSources(pageData: AnimeUnityPageData?): Map<String, String> {
        pageData ?: return emptyMap()
        val baseUrl = "$animeUnityUrl/anime/${pageData.anime.id}-${pageData.anime.slug}"
        return pageData.episodes.mapNotNull { episode ->
            val number = normalizeAnimeUnityEpisodeNumber(episode.number) ?: return@mapNotNull null
            number to "$baseUrl/${episode.id}"
        }.toMap()
    }

    private suspend fun fetchAnimeWorldSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeWorldTitleSources> {
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
        val pageData = titleCandidates
            .flatMap { fetchAnimeWorldSearchResults(it) }
            .distinctBy { it.url }
            .take(12)
            .mapNotNull { fetchAnimeWorldPageData(it) }

        return syncIds.mapNotNull { sync ->
            val matches = pageData.filter { it.matches(sync) }
            if (matches.isEmpty()) return@mapNotNull null

            val subSources = matches
                .filter { !it.searchItem.isDub }
                .mergeAnimeWorldEpisodeSources()
            val dubSources = matches
                .filter { it.searchItem.isDub }
                .mergeAnimeWorldEpisodeSources()

            AnimeWorldTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun fetchAnimeWorldSearchResults(query: String): List<AnimeWorldSearchItem> {
        val url = "$animeWorldUrl/filter?sort=0&keyword=${URLEncoder.encode(query, "UTF-8")}"
        val html = getCachedText("AW:GET:$url", TmdbCacheProfile.AnimeWorldSearch) {
            app.get(
                url,
                headers = headers,
                cacheTime = TmdbCacheProfile.AnimeWorldSearch.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, url)

        return doc.select("div.film-list > .item").mapNotNull { item ->
            val anchor = item.selectFirst("a.name[href]") ?: return@mapNotNull null
            val title = cleanText(anchor.text()) ?: return@mapNotNull null
            val otherTitle = cleanText(anchor.attr("data-jtitle"))
            val itemUrl = toAbsoluteProviderUrl(animeWorldUrl, anchor.attr("href")).trimEnd('/')
            val isDub = item.select(".status .dub").isNotEmpty() ||
                title.contains("(ITA)", ignoreCase = true) ||
                otherTitle.orEmpty().contains("(ITA)", ignoreCase = true)

            AnimeWorldSearchItem(
                url = itemUrl,
                title = title,
                otherTitle = otherTitle,
                isDub = isDub,
            )
        }
    }

    private suspend fun fetchAnimeWorldPageData(item: AnimeWorldSearchItem): AnimeWorldPageData? {
        val html = getCachedText("AW:GET:${item.url}", TmdbCacheProfile.AnimeWorldDetail) {
            app.get(
                item.url,
                headers = headers,
                cacheTime = TmdbCacheProfile.AnimeWorldDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, item.url)
        val isDub = item.isDub || html.contains("window.animeDub = true", ignoreCase = true)
        val label = if (isDub) "[DUB]" else "[SUB]"
        val episodes = parseAnimeWorldEpisodes(doc)
        val episodeSources = episodes.mapNotNull { episode ->
            val number = normalizeAnimeUnityEpisodeNumber(episode.number) ?: return@mapNotNull null
            number to AnimeWorldPlaybackData(
                label = label,
                pageUrl = item.url,
                episodeToken = episode.token,
            )
        }.toMap()

        return AnimeWorldPageData(
            searchItem = item.copy(isDub = isDub),
            anilistId = doc.selectFirst("#anilist-button[href]")
                ?.attr("href")
                ?.substringAfterLast('/')
                ?.toIntOrNull(),
            malId = doc.selectFirst("#mal-button[href]")
                ?.attr("href")
                ?.substringAfterLast('/')
                ?.toIntOrNull(),
            episodeSources = episodeSources,
        ).takeIf { it.episodeSources.isNotEmpty() }
    }

    private fun parseAnimeWorldEpisodes(doc: Document): List<AnimeWorldEpisodeInfo> {
        val preferredAnchors = doc.select(".widget.servers .server[data-name=9] a[data-id][data-episode-num]")
        val anchors = preferredAnchors.ifEmpty {
            doc.select(".widget.servers a[data-id][data-episode-num]")
        }

        return anchors.mapNotNull { anchor ->
            val token = anchor.attr("data-id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val number = anchor.attr("data-episode-num").takeIf(String::isNotBlank)
                ?: anchor.attr("data-num").takeIf(String::isNotBlank)
                ?: anchor.text().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            AnimeWorldEpisodeInfo(number = number, token = token)
        }.distinctBy { normalizeAnimeUnityEpisodeNumber(it.number) ?: it.number }
    }

    private suspend fun fetchHentaiWorldSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
    ): List<HentaiWorldTitleSources> {
        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
        val normalizedCandidates = titleCandidates.map(::sourceTitleDedupKey).toSet()

        return titleCandidates
            .flatMap { fetchHentaiWorldSearchResults(it) }
            .distinctBy { it.url }
            .filter { sourceTitleDedupKey(it.title) in normalizedCandidates }
            .take(4)
            .mapNotNull { fetchHentaiWorldTitleSources(it) }
    }

    private suspend fun fetchHentaiWorldSearchResults(query: String): List<HentaiWorldSearchItem> {
        val url = "$hentaiWorldUrl/archive?search=${URLEncoder.encode(query, "UTF-8")}"
        val html = getCachedText("HW:GET:$url", TmdbCacheProfile.HentaiWorldSearch) {
            app.get(
                url,
                headers = headers,
                cacheTime = TmdbCacheProfile.HentaiWorldSearch.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, url)

        return doc.select("article").mapNotNull { item ->
            val anchor = item.selectFirst("a[href^=/hentai/]") ?: return@mapNotNull null
            val title = cleanText(item.selectFirst("h3")?.text())
                ?: cleanText(anchor.text())
                ?: cleanText(item.selectFirst("img[alt]")?.attr("alt")?.removePrefix("Copertina di "))
                ?: return@mapNotNull null

            HentaiWorldSearchItem(
                url = toAbsoluteProviderUrl(hentaiWorldUrl, anchor.attr("href")).trimEnd('/'),
                title = title,
            )
        }
    }

    private suspend fun fetchHentaiWorldTitleSources(item: HentaiWorldSearchItem): HentaiWorldTitleSources? {
        val html = getCachedText("HW:GET:${item.url}", TmdbCacheProfile.HentaiWorldDetail) {
            app.get(
                item.url,
                headers = headers,
                cacheTime = TmdbCacheProfile.HentaiWorldDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, item.url)
        val sources = doc.select("article.episode-item").mapNotNull { episode ->
            val number = episode.attr("data-episode").takeIf(String::isNotBlank)
                ?: episode.selectFirst("a[href*=/watch/]")?.attr("href")
                    ?.let { Regex("""episode-(\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }
                ?: return@mapNotNull null
            val watchUrl = episode.selectFirst("a[href*=/watch/]")
                ?.attr("href")
                ?.takeIf(String::isNotBlank)
                ?.let { toAbsoluteProviderUrl(hentaiWorldUrl, it) }
                ?: return@mapNotNull null
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return@mapNotNull null
            normalizedNumber to HentaiWorldPlaybackData(watchUrl = watchUrl)
        }.toMap()

        return HentaiWorldTitleSources(sources).takeIf { it.sources.isNotEmpty() }
    }

    private suspend fun fetchAnimeSaturnSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<SaturnTitleSources> {
        if (syncIds.isEmpty()) return emptyList()

        val pageData = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .flatMap {
                fetchSaturnSearchResults(
                    baseUrl = animeSaturnUrl,
                    searchPath = "animelist",
                    itemPath = "/anime/",
                    query = it,
                    cachePrefix = "AS",
                    cacheProfile = TmdbCacheProfile.AnimeSaturnSearch,
                )
            }
            .distinctBy { it.url }
            .take(16)
            .mapNotNull {
                fetchSaturnPageData(
                    baseUrl = animeSaturnUrl,
                    item = it,
                    cachePrefix = "AS",
                    cacheProfile = TmdbCacheProfile.AnimeSaturnDetail,
                )
            }

        return syncIds.mapNotNull { sync ->
            val matches = pageData.filter { it.matches(sync) }
            if (matches.isEmpty()) return@mapNotNull null

            SaturnTitleSources(
                syncIds = sync,
                subSources = matches
                    .filter { !it.searchItem.isDub }
                    .mergeSaturnEpisodeSources(),
                dubSources = matches
                    .filter { it.searchItem.isDub }
                    .mergeSaturnEpisodeSources(),
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun fetchHentaiSaturnSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<SaturnTitleSources> {
        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
        val pageData = titleCandidates
            .flatMap {
                fetchSaturnSearchResults(
                    baseUrl = hentaiSaturnUrl,
                    searchPath = "hentailist",
                    itemPath = "/hentai/",
                    query = it,
                    cachePrefix = "HSAT",
                    cacheProfile = TmdbCacheProfile.HentaiSaturnSearch,
                )
            }
            .distinctBy { it.url }
            .take(12)
            .mapNotNull {
                fetchSaturnPageData(
                    baseUrl = hentaiSaturnUrl,
                    item = it,
                    cachePrefix = "HSAT",
                    cacheProfile = TmdbCacheProfile.HentaiSaturnDetail,
                )
            }

        val idMatches = syncIds.mapNotNull { sync ->
            val matches = pageData.filter { it.matches(sync) }
            if (matches.isEmpty()) return@mapNotNull null

            SaturnTitleSources(
                syncIds = sync,
                subSources = matches.mergeSaturnEpisodeSources(),
            ).takeIf { it.subSources.isNotEmpty() }
        }
        if (idMatches.isNotEmpty()) return idMatches

        val normalizedCandidates = titleCandidates.map(::sourceTitleDedupKey).toSet()
        return pageData
            .filter { sourceTitleDedupKey(it.searchItem.title) in normalizedCandidates }
            .take(4)
            .mapNotNull {
                SaturnTitleSources(
                    syncIds = null,
                    subSources = it.episodeSources,
                ).takeIf { sources -> sources.subSources.isNotEmpty() }
            }
    }

    private suspend fun fetchSaturnSearchResults(
        baseUrl: String,
        searchPath: String,
        itemPath: String,
        query: String,
        cachePrefix: String,
        cacheProfile: TmdbCacheProfile,
    ): List<SaturnSearchItem> {
        val url = "${baseUrl.trimEnd('/')}/$searchPath?search=${URLEncoder.encode(query, "UTF-8")}"
        val html = getCachedText("$cachePrefix:GET:$url", cacheProfile) {
            app.get(
                url,
                headers = headers,
                cacheTime = cacheProfile.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, url)

        return doc.select("li.list-group-item .item-archivio, div.item-archivio")
            .mapNotNull { item ->
                val anchor = item.selectFirst("""h3 a[href*="$itemPath"], a[href*="$itemPath"]""")
                    ?: return@mapNotNull null
                val title = cleanText(anchor.text())
                    ?: cleanText(item.selectFirst("img[alt]")?.attr("alt"))
                    ?: return@mapNotNull null
                val itemUrl = toAbsoluteProviderUrl(baseUrl, anchor.attr("href")).trimEnd('/')
                val isDub = title.contains("(ITA)", ignoreCase = true) ||
                    itemUrl.contains("-ITA", ignoreCase = true)

                SaturnSearchItem(
                    url = itemUrl,
                    title = title,
                    isDub = isDub,
                )
            }
    }

    private suspend fun fetchSaturnPageData(
        baseUrl: String,
        item: SaturnSearchItem,
        cachePrefix: String,
        cacheProfile: TmdbCacheProfile,
    ): SaturnPageData? {
        val html = getCachedText("$cachePrefix:GET:${item.url}", cacheProfile) {
            app.get(
                item.url,
                headers = headers,
                cacheTime = cacheProfile.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, item.url)
        val pageTitle = cleanText(doc.selectFirst("h1")?.text()) ?: item.title
        val isDub = item.isDub || pageTitle.contains("(ITA)", ignoreCase = true)
        val label = if (isDub) "[DUB]" else "[SUB]"

        return SaturnPageData(
            searchItem = item.copy(isDub = isDub),
            anilistId = doc.select("a[href*=anilist.co/anime/]")
                .firstOrNull()
                ?.attr("href")
                ?.let(::extractAnilistIdFromText),
            malId = doc.select("a[href*=myanimelist.net/anime/]")
                .firstOrNull()
                ?.attr("href")
                ?.let(::extractMalIdFromText),
            episodeSources = parseSaturnEpisodes(doc, baseUrl, label),
        ).takeIf { it.episodeSources.isNotEmpty() }
    }

    private fun parseSaturnEpisodes(
        doc: Document,
        baseUrl: String,
        label: String,
    ): Map<String, SaturnPlaybackData> {
        return doc.select("a[href*=/ep/], a[href*=/episode/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                    .takeIf { it.contains("/ep/") || it.contains("/episode/") }
                    ?: return@mapNotNull null
                val number = extractSaturnEpisodeNumber(anchor) ?: return@mapNotNull null
                val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return@mapNotNull null
                val episodeUrl = toAbsoluteProviderUrl(baseUrl, href).trimEnd('/')

                normalizedNumber to SaturnPlaybackData(
                    label = label,
                    episodeUrl = episodeUrl,
                )
            }
            .distinctBy { it.first }
            .toMap()
    }

    private fun extractSaturnEpisodeNumber(anchor: Element): String? {
        val candidates = listOf(
            anchor.attr("data-episode"),
            anchor.attr("data-episode-number"),
            anchor.text(),
            anchor.attr("href"),
        )

        candidates.forEach { candidate ->
            Regex("""(?i)(?:episodio|episode|ep)[\s._-]*(\d+(?:[.,]\d+)?)""")
                .find(candidate)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { return it }
        }

        return null
    }

    private suspend fun fetchEpisodes(
        doc: Document,
        actualUrl: String,
        animeUnitySources: List<AnimeUnityTitleSources> = emptyList(),
        animeWorldSources: List<AnimeWorldTitleSources> = emptyList(),
        animeSaturnSources: List<SaturnTitleSources> = emptyList(),
        hentaiWorldSources: List<HentaiWorldTitleSources> = emptyList(),
        hentaiSaturnSources: List<SaturnTitleSources> = emptyList(),
        targetSeason: Int? = null,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData> = emptyMap(),
        aniZipMetadata: AniZipMetadata? = null,
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
                    runCatching { getTmdbDocument(seasonUrl, cacheProfile = TmdbCacheProfile.Seasons) }
                        .getOrNull()
                        ?.let {
                            parseSeasonEpisodes(
                                seasonDoc = it,
                                fallbackSeason = fallbackSeason,
                                animeUnitySources = animeUnitySources,
                                animeWorldSources = animeWorldSources,
                                animeSaturnSources = animeSaturnSources,
                                hentaiWorldSources = hentaiWorldSources,
                                hentaiSaturnSources = hentaiSaturnSources,
                                streamingCommunityEpisodes = streamingCommunityEpisodes,
                                aniZipMetadata = aniZipMetadata,
                            )
                        }
                        .orEmpty()
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun fetchSeasonUrls(doc: Document, actualUrl: String): List<String> {
        val path = actualUrl.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (!path.startsWith("tv/")) return emptyList()

        val seasonIndex = runCatching {
            getTmdbDocument("$mainUrl/$path/seasons", cacheProfile = TmdbCacheProfile.Seasons)
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
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<SaturnTitleSources>,
        hentaiWorldSources: List<HentaiWorldTitleSources>,
        hentaiSaturnSources: List<SaturnTitleSources>,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
        aniZipMetadata: AniZipMetadata?,
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
            val title = cleanEpisodeTitle(
                card.selectFirst("div.episode_title h3 a")?.text()
                    ?: anchor?.text()
            )

            if (dataUrl.isBlank() && episodeNumber == null && title == null) return@mapNotNull null

            val airDate = parseItalianDateToIso(card.selectFirst("div.date span.date")?.text())
            val runtime = parseRuntime(card.selectFirst("span.runtime")?.text())
            val aniZipEpisode = aniZipMetadata?.episode(episodeNumber)
            val streamingCommunityPlayback = seasonNumber?.let { season ->
                episodeNumber?.let { episode -> streamingCommunityEpisodes[season to episode] }
            }
            val sourcePayload = buildStreamCenterEpisodePayload(
                tmdbUrl = dataUrl,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                hentaiWorldSources = hentaiWorldSources,
                hentaiSaturnSources = hentaiSaturnSources,
                streamingCommunity = streamingCommunityPlayback,
            )
            newEpisode(sourcePayload) {
                this.name = aniZipEpisode?.title ?: title
                this.season = seasonNumber
                this.episode = episodeNumber
                this.posterUrl = aniZipEpisode?.image
                    ?: card.selectFirst("img.backdrop")?.extractImageUrl()
                    ?: card.selectFirst("img")?.extractImageUrl()
                    ?: aniZipMetadata?.fallbackPoster
                this.description = aniZipEpisode?.overview
                    ?: cleanEpisodeDescription(card.selectFirst("div.overview p")?.text())
                this.runTime = aniZipEpisode?.runtime ?: runtime
                this.score = aniZipEpisode?.score
                (aniZipEpisode?.airDate ?: airDate)?.let { this.addDate(it) }
            }
        }
    }

    private fun buildStreamCenterEpisodePayload(
        tmdbUrl: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<SaturnTitleSources>,
        hentaiWorldSources: List<HentaiWorldTitleSources>,
        hentaiSaturnSources: List<SaturnTitleSources>,
        streamingCommunity: StreamingCommunityPlaybackData? = null,
    ): String {
        if (
            animeUnitySources.isEmpty() &&
            animeWorldSources.isEmpty() &&
            animeSaturnSources.isEmpty() &&
            hentaiWorldSources.isEmpty() &&
            hentaiSaturnSources.isEmpty() &&
            streamingCommunity == null
        ) {
            return tmdbUrl
        }

        val seasonSources = when {
            seasonNumber != null -> animeUnitySources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeUnitySources.takeIf { it.size == 1 }?.firstOrNull()

        val animeWorldSeasonSources = when {
            seasonNumber != null -> animeWorldSources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeWorldSources.takeIf { it.size == 1 }?.firstOrNull()

        val animeSaturnSeasonSources = when {
            seasonNumber != null -> animeSaturnSources.firstOrNull { it.syncIds?.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeSaturnSources.takeIf { it.size == 1 }?.firstOrNull()

        val animeUnityPlayback = seasonSources?.playbackForEpisode(episodeNumber?.toString())
        val animeWorldPlaybacks = animeWorldSeasonSources
            ?.playbacksForEpisode(episodeNumber?.toString())
            .orEmpty()
        val animeSaturnPlaybacks = animeSaturnSeasonSources
            ?.playbacksForEpisode(episodeNumber?.toString())
            .orEmpty()
        val hentaiWorldPlaybacks = hentaiWorldSources
            .flatMap { it.playbacksForEpisode(episodeNumber?.toString()) }
            .distinctBy { it.watchUrl }
        val hentaiSaturnPlaybacks = hentaiSaturnSources
            .flatMap { it.playbacksForEpisode(episodeNumber?.toString()) }
            .distinctBy { it.episodeUrl }
        return StreamCenterPlaybackData(
            tmdbUrl = tmdbUrl.takeIf(String::isNotBlank),
            animeUnity = animeUnityPlayback,
            animeWorld = animeWorldPlaybacks,
            animeSaturn = animeSaturnPlaybacks,
            hentaiWorld = hentaiWorldPlaybacks,
            hentaiSaturn = hentaiSaturnPlaybacks,
            streamingCommunity = streamingCommunity,
        ).toJson()
    }

    private fun buildAnimeSourceEpisodes(
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<SaturnTitleSources>,
        hentaiWorldSources: List<HentaiWorldTitleSources>,
        hentaiSaturnSources: List<SaturnTitleSources>,
        seasonNumber: Int?,
        tmdbEpisodes: List<Episode>,
        aniZipMetadata: AniZipMetadata? = null,
    ): List<Episode> {
        val animeUnityTitleSources = when {
            seasonNumber != null -> animeUnitySources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeUnitySources.takeIf { it.size == 1 }?.firstOrNull()
        val animeWorldTitleSources = when {
            seasonNumber != null -> animeWorldSources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeWorldSources.takeIf { it.size == 1 }?.firstOrNull()
        val animeSaturnTitleSources = when {
            seasonNumber != null -> animeSaturnSources.firstOrNull { it.syncIds?.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeSaturnSources.takeIf { it.size == 1 }?.firstOrNull()
        val hentaiWorldTitleSources = hentaiWorldSources
        val hentaiSaturnTitleSources = hentaiSaturnSources
        val episodeNumbers = (
            animeUnityTitleSources?.episodeNumbers().orEmpty() +
                animeWorldTitleSources?.episodeNumbers().orEmpty() +
                animeSaturnTitleSources?.episodeNumbers().orEmpty() +
                hentaiWorldTitleSources.flatMap { it.episodeNumbers() } +
                hentaiSaturnTitleSources.flatMap { it.episodeNumbers() }
            )
            .distinct()
            .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        if (episodeNumbers.isEmpty()) return emptyList()

        val tmdbEpisodesByNumber = tmdbEpisodes.mapNotNull { episode ->
            episode.episode?.let { it to episode }
        }.toMap()

        return episodeNumbers.mapNotNull { number ->
            val playback = animeUnityTitleSources?.playbackForEpisode(number)
            val animeWorldPlaybacks = animeWorldTitleSources?.playbacksForEpisode(number).orEmpty()
            val animeSaturnPlaybacks = animeSaturnTitleSources?.playbacksForEpisode(number).orEmpty()
            val hentaiWorldPlaybacks = hentaiWorldTitleSources
                .flatMap { it.playbacksForEpisode(number) }
                .distinctBy { it.watchUrl }
            val hentaiSaturnPlaybacks = hentaiSaturnTitleSources
                .flatMap { it.playbacksForEpisode(number) }
                .distinctBy { it.episodeUrl }
            if (
                playback == null &&
                animeWorldPlaybacks.isEmpty() &&
                animeSaturnPlaybacks.isEmpty() &&
                hentaiWorldPlaybacks.isEmpty() &&
                hentaiSaturnPlaybacks.isEmpty()
            ) {
                return@mapNotNull null
            }
            val episodeNumber = parseWholeEpisodeNumber(number)
            val tmdbEpisode = episodeNumber?.let { tmdbEpisodesByNumber[it] }
            val aniZipEpisode = aniZipMetadata?.episode(episodeNumber)
            newEpisode(
                StreamCenterPlaybackData(
                    animeUnity = playback,
                    animeWorld = animeWorldPlaybacks,
                    animeSaturn = animeSaturnPlaybacks,
                    hentaiWorld = hentaiWorldPlaybacks,
                    hentaiSaturn = hentaiSaturnPlaybacks,
                ).toJson()
            ) {
                this.season = seasonNumber ?: tmdbEpisode?.season
                this.episode = episodeNumber ?: tmdbEpisode?.episode
                this.name = aniZipEpisode?.title ?: tmdbEpisode?.name ?: "Episodio $number"
                this.posterUrl = aniZipEpisode?.image ?: tmdbEpisode?.posterUrl ?: aniZipMetadata?.fallbackPoster
                this.description = aniZipEpisode?.overview ?: tmdbEpisode?.description
                this.runTime = aniZipEpisode?.runtime ?: tmdbEpisode?.runTime
                this.score = aniZipEpisode?.score
                aniZipEpisode?.airDate?.let { this.addDate(it) } ?: tmdbEpisode?.date?.let { this.date = it }
            }
        }
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

    private fun buildAnimeSourceTitleCandidates(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
    ): List<String> {
        val baseTitles = (
            listOfNotNull(anilistMetadata?.title) +
                anilistMetadata?.titleCandidates.orEmpty() +
                listOfNotNull(metadata.title, metadata.originalTitle)
            )

        return baseTitles
            .flatMap { title ->
                listOf(
                    title,
                    title.replace(Regex("""\(\d{4}\)"""), ""),
                    title.replace(Regex("""\s*-\s*Stagione\s+\d+""", RegexOption.IGNORE_CASE), ""),
                    title.replace(Regex("""\s+Stagione\s+\d+""", RegexOption.IGNORE_CASE), ""),
                )
            }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { sourceTitleDedupKey(it) }
    }

    private fun sourceTitleDedupKey(title: String): String {
        return normalizeSourceTitle(title).ifBlank { title.lowercase(Locale.ROOT) }
    }

    private fun normalizeSourceTitle(title: String): String {
        return title
            .lowercase(Locale.ROOT)
            .replace("&", "and")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\b(movie|the movie|ita|sub ita|subita|tv|ona|ova|special|season|stagione)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun toAbsoluteProviderUrl(baseUrl: String, href: String): String {
        val cleanedHref = href.trim()
        return when {
            cleanedHref.startsWith("http://") || cleanedHref.startsWith("https://") -> cleanedHref
            cleanedHref.startsWith("//") -> "https:$cleanedHref"
            else -> "${baseUrl.trimEnd('/')}/${cleanedHref.trimStart('/')}"
        }
    }

    private fun hostFromUrl(url: String): String {
        return url
            .substringAfter("://", url)
            .substringBefore("/")
            .substringBefore(":")
    }

    private fun AnimeWorldPageData.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId)
    }

    private fun List<AnimeWorldPageData>.mergeAnimeWorldEpisodeSources(): Map<String, AnimeWorldPlaybackData> {
        val merged = linkedMapOf<String, AnimeWorldPlaybackData>()
        forEach { pageData ->
            pageData.episodeSources.forEach { (number, playback) ->
                if (!merged.containsKey(number)) {
                    merged[number] = playback
                }
            }
        }
        return merged
    }

    private fun SaturnPageData.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId)
    }

    private fun List<SaturnPageData>.mergeSaturnEpisodeSources(): Map<String, SaturnPlaybackData> {
        val merged = linkedMapOf<String, SaturnPlaybackData>()
        forEach { pageData ->
            pageData.episodeSources.forEach { (number, playback) ->
                if (!merged.containsKey(number)) {
                    merged[number] = playback
                }
            }
        }
        return merged
    }

    private val AnimeUnityAnime.isDub: Boolean
        get() = dub == 1 || title.orEmpty().contains("(ITA)") ||
            titleEng.orEmpty().contains("(ITA)") ||
            titleIt.orEmpty().contains("(ITA)")

    private fun AnimeUnityAnime.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId)
    }

    private fun AnimeUnityAnime.contentKey(): String {
        return when {
            anilistId != null -> "anilist:$anilistId"
            malId != null -> "mal:$malId"
            else -> "title:${cleanAnimeUnityTitle(displayTitle()).lowercase(Locale.ROOT)}"
        }
    }

    private fun AnimeUnityAnime.displayTitle(): String {
        return titleIt ?: titleEng ?: title ?: slug
    }

    private fun cleanAnimeUnityTitle(title: String): String {
        return title.replace(" (ITA)", "").trim()
    }

    private fun JSONObject.toAnimeUnityAnime(): AnimeUnityAnime? {
        val id = optNullableInt("id") ?: return null
        val slug = optString("slug").takeIf(String::isNotBlank) ?: return null
        return AnimeUnityAnime(
            id = id,
            slug = slug,
            title = optNullableString("title"),
            titleEng = optNullableString("title_eng"),
            titleIt = optNullableString("title_it"),
            dub = optNullableInt("dub") ?: 0,
            anilistId = optNullableInt("anilist_id"),
            malId = optNullableInt("mal_id"),
            episodesCount = optNullableInt("episodes_count"),
            realEpisodesCount = optNullableInt("real_episodes_count"),
        )
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.takeIf { it > 0 }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun formatPlayerSourceName(source: String, label: String? = null, engine: String? = null): String {
        val audioLabel = when (label?.uppercase(Locale.ROOT)) {
            "[DUB]", "DUB", "[ITA]", "ITA" -> "[DUB] \uD83C\uDDEE\uD83C\uDDF9"
            "[SUB]", "SUB" -> "[SUB] \uD83C\uDDEF\uD83C\uDDF5"
            "[SOURCE]", "SOURCE" -> "[Fonte]"
            null, "" -> null
            else -> label
        }
        return listOfNotNull(source, audioLabel, engine?.let { "- $it" }).joinToString(" ")
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
                        sourceName = formatPlayerSourceName("VixCloud", source.label),
                        displayName = formatPlayerSourceName("AnimeUnity", source.label),
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
        val html = getCachedText("GET:$playerUrl", TmdbCacheProfile.AnimeUnityPlayer) {
            app.get(
                playerUrl,
                cacheTime = TmdbCacheProfile.AnimeUnityPlayer.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
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
            getCachedText("AW:GET:$infoUrl", TmdbCacheProfile.AnimeWorldPlayer) {
                app.get(
                    infoUrl,
                    headers = headers + mapOf("Referer" to playbackData.pageUrl),
                    cacheTime = TmdbCacheProfile.AnimeWorldPlayer.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
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
            name = formatPlayerSourceName("AnimeWorld", playbackData.label),
            url = grabber,
            referer = animeWorldUrl,
            callback = callback,
        )
        return true
    }

    private suspend fun loadHentaiWorldLink(
        playbackData: HentaiWorldPlaybackData,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            getCachedText("HW:GET:${playbackData.watchUrl}", TmdbCacheProfile.HentaiWorldPlayer) {
                app.get(
                    playbackData.watchUrl,
                    headers = headers,
                    cacheTime = TmdbCacheProfile.HentaiWorldPlayer.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
                ).text
            }
        }.getOrNull() ?: return false

        val videoUrl = Regex("""const\s+videoUrl\s*=\s*['"]([^'"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""file\s*:\s*['"]([^'"]+)""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
            ?: return false

        emitDirectVideoLink(
            source = "HentaiWorld",
            name = formatPlayerSourceName("HentaiWorld", "[SUB]"),
            url = videoUrl,
            referer = playbackData.watchUrl,
            callback = callback,
        )
        return true
    }

    private suspend fun loadSaturnLink(
        source: String,
        baseUrl: String,
        cacheProfile: TmdbCacheProfile,
        playbackData: SaturnPlaybackData,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val watchUrl = playbackData.watchUrl?.takeIf(String::isNotBlank)
            ?: runCatching {
                val episodeHtml = getCachedText("$source:GET:${playbackData.episodeUrl}", cacheProfile) {
                    app.get(
                        playbackData.episodeUrl,
                        headers = headers + mapOf("Referer" to baseUrl),
                        cacheTime = cacheProfile.cacheMinutes,
                        cacheUnit = TimeUnit.MINUTES,
                    ).text
                }
                extractSaturnVideoUrl(episodeHtml)?.let { directUrl ->
                    emitDirectVideoLink(
                        source = source,
                        name = formatPlayerSourceName(source, playbackData.label),
                        url = toAbsoluteProviderUrl(baseUrl, directUrl),
                        referer = playbackData.episodeUrl,
                        callback = callback,
                    )
                    return true
                }

                Jsoup.parse(episodeHtml, playbackData.episodeUrl)
                    .selectFirst("""a[href*="watch?file="]""")
                    ?.attr("href")
                    ?.takeIf(String::isNotBlank)
                    ?.let { toAbsoluteProviderUrl(baseUrl, it) }
            }.getOrNull()
            ?: return false

        val html = runCatching {
            getCachedText("$source:GET:$watchUrl", cacheProfile) {
                app.get(
                    watchUrl,
                    headers = headers + mapOf("Referer" to playbackData.episodeUrl),
                    cacheTime = cacheProfile.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
                ).text
            }
        }.getOrNull() ?: return false

        val videoUrl = extractSaturnVideoUrl(html)
            ?.let { toAbsoluteProviderUrl(baseUrl, it) }
            ?: return false

        emitDirectVideoLink(
            source = source,
            name = formatPlayerSourceName(source, playbackData.label),
            url = videoUrl,
            referer = watchUrl,
            callback = callback,
        )
        return true
    }

    private fun extractSaturnVideoUrl(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("source[src], video[src]")
            ?.attr("src")
            ?.takeIf(String::isNotBlank)
            ?.let { return cleanSaturnVideoUrl(it) }

        val patterns = listOf(
            Regex("""file\s*:\s*["']([^"']+)["']"""),
            Regex("""src\s*:\s*["']([^"']+\.mp4[^"']*)["']"""),
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf(String::isNotBlank)
                ?.let(::cleanSaturnVideoUrl)
        }
    }

    private fun cleanSaturnVideoUrl(url: String): String {
        return Parser.unescapeEntities(url.replace("\\/", "/"), true).trim()
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
            val html = getCachedText("SC:GET:${playbackData.iframeUrl}", TmdbCacheProfile.StreamingCommunityPlayer) {
                app.get(
                    playbackData.iframeUrl,
                    cacheTime = TmdbCacheProfile.StreamingCommunityPlayer.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
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
                    sourceName = formatPlayerSourceName("VixCloud", "[DUB]"),
                    displayName = formatPlayerSourceName("StreamingCommunity", "[DUB]", "VixCloud"),
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
                StreamCenterVixSrcExtractor(
                    sourceName = formatPlayerSourceName("VixSrc", "[DUB]"),
                    displayName = formatPlayerSourceName("StreamingCommunity", "[DUB]", "VixSrc"),
                ).getUrl(
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
        val path = url.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (!path.startsWith("movie/") && !path.startsWith("tv/")) return null
        return path.substringAfter('/').substringBefore('-').toIntOrNull()?.toString()
    }

    private suspend fun fetchRecommendations(url: String, isAnime: Boolean): List<SearchResponse> {
        val path = url.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (!path.startsWith("movie/") && !path.startsWith("tv/")) return emptyList()

        val recommendationsUrl = "$mainUrl/$path/remote/recommendations"
        val recommendationsDoc = runCatching {
            getTmdbDocument(
                "$recommendationsUrl?version=1&translate=false",
                cacheProfile = TmdbCacheProfile.Recommendations,
            )
        }.getOrNull() ?: return emptyList()

        return parseMediaCards(recommendationsDoc, isAnime)
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean {
        return doc.select("span.next a").isNotEmpty() ||
            doc.select("a[href]").any { it.attr("href").contains("page=${page + 1}") }
    }

    companion object {
        private val tmdbTextCache = ExpiringTextCache(maxEntries = 768)
        private val animeSyncIdsMemoryCache = mutableMapOf<String, List<AnimeSyncIds>>()
        private var animeMappingsMemoryCache: JSONArray? = null
        private var animeSyncIdsListMemoryCache: List<AnimeSyncIds>? = null
        private var animeSyncIdsByTmdbMemoryCache: Map<String, List<AnimeSyncIds>>? = null
        private var animeSyncIdsByExternalIdMemoryCache: Map<String, List<AnimeSyncIds>>? = null
        private val tmdbScoreMemoryCache = mutableMapOf<String, String>()
        private val streamingCommunityTitleMemoryCache = mutableMapOf<String, StreamingCommunityTitle?>()
        private val streamingCommunitySearchMemoryCache = mutableMapOf<String, List<StreamingCommunityTitle>>()
        private val streamingCommunityDetailMemoryCache = mutableMapOf<String, StreamingCommunityTitle>()
        private val streamingCommunitySeasonMemoryCache = mutableMapOf<String, StreamingCommunitySeason>()
        private val streamingCommunityEpisodePayloadsMemoryCache =
            mutableMapOf<String, Map<Pair<Int, Int>, StreamingCommunityPlaybackData>>()
        private val anilistMetadataMemoryCache = mutableMapOf<String, AnilistMetadata>()
        private val myAnimeListMetadataMemoryCache = mutableMapOf<String, MyAnimeListMetadata>()
        private val aniZipMetadataMemoryCache = mutableMapOf<String, AniZipMetadata>()
        private var anilistRateLimitedUntilMs = 0L

        private fun normalizeAnimeUnityEpisodeNumber(number: String?): String? {
            val normalized = number
                ?.trim()
                ?.replace(',', '.')
                ?.takeIf(String::isNotBlank)
                ?: return null
            val numericValue = normalized.toDoubleOrNull() ?: return normalized
            val intValue = numericValue.toInt()

            return if (numericValue == intValue.toDouble()) intValue.toString() else numericValue.toString()
        }

        private fun parseWholeEpisodeNumber(number: String?): Int? {
            val normalized = normalizeAnimeUnityEpisodeNumber(number) ?: return null
            val numericValue = normalized.toDoubleOrNull() ?: return normalized.toIntOrNull()
            val intValue = numericValue.toInt()
            return intValue.takeIf { numericValue == it.toDouble() }
        }

        fun setCacheDirectory(directory: File) {
            tmdbTextCache.setDiskDirectory(directory)
        }

        fun getCacheStats(): StreamCenterCacheStats {
            return tmdbTextCache.stats()
        }

        fun clearSessionCaches() {
            tmdbTextCache.remove("SC:SESSION")
            tmdbTextCache.remove("AU:SESSION")
        }

        fun clearAllCaches() {
            tmdbTextCache.clear()
            animeSyncIdsMemoryCache.clear()
            animeMappingsMemoryCache = null
            animeSyncIdsListMemoryCache = null
            animeSyncIdsByTmdbMemoryCache = null
            animeSyncIdsByExternalIdMemoryCache = null
            tmdbScoreMemoryCache.clear()
            streamingCommunityTitleMemoryCache.clear()
            streamingCommunitySearchMemoryCache.clear()
            streamingCommunityDetailMemoryCache.clear()
            streamingCommunitySeasonMemoryCache.clear()
            streamingCommunityEpisodePayloadsMemoryCache.clear()
            anilistMetadataMemoryCache.clear()
            myAnimeListMetadataMemoryCache.clear()
            aniZipMetadataMemoryCache.clear()
            anilistRateLimitedUntilMs = 0L
        }
    }
}
