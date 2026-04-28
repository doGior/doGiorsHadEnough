package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDub
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Str = BooleanOrString.AsString

@Suppress("unused")
class AnimeUnity(
    private val sharedPref: SharedPreferences?,
) : MainAPI() {
    override var mainUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        get() = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        set(value) {
            field = value
        }
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true
    override val supportedSyncNames = setOf(
        SyncIdName.Anilist,
        SyncIdName.MyAnimeList,
    )

    private class ExpiringMemoryCache<K, V>(
        private val ttlMs: Long,
        private val maxSize: Int,
    ) {
        private data class Entry<V>(
            val value: V,
            val timestampMs: Long,
        )

        private val values = object : LinkedHashMap<K, Entry<V>>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
                return size > maxSize
            }
        }

        fun get(key: K): V? {
            val now = System.currentTimeMillis()
            synchronized(values) {
                val entry = values[key] ?: return null
                if (now - entry.timestampMs > ttlMs) {
                    values.remove(key)
                    return null
                }

                return entry.value
            }
        }

        fun put(key: K, value: V) {
            synchronized(values) {
                values[key] = Entry(value, System.currentTimeMillis())
            }
        }

        fun clear() {
            synchronized(values) {
                values.clear()
            }
        }
    }

    companion object {
        @Suppress("ConstPropertyName")
        const val mainUrl = "https://www.animeunity.so"
        const val ARCHIVE_BATCH_SIZE = 30
        const val advancedSearchSectionName = "Ricerca avanzata"
        const val latestEpisodesSectionName = "Ultimi Episodi"
        const val calendarSectionName = "Calendario"
        const val randomSectionName = "Random"
        const val ongoingSectionName = "In Corso"
        const val popularSectionName = "Popolari"
        const val bestSectionName = "I migliori"
        const val upcomingSectionName = "In Arrivo"
        const val noEpisodeDescription = "Nessuna descrizione"
        
        var name = "AnimeUnity"
        var headers = mapOf(
            "Host" to mainUrl.toHttpUrl().host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        ).toMutableMap()

        private const val CACHE_SCHEMA = "animeunity-v2"
        private const val MINUTE_MS = 60 * 1000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS
        private const val LATEST_CACHE_TTL_MS = 30 * MINUTE_MS
        private const val HOME_DYNAMIC_CACHE_TTL_MS = 2 * DAY_MS
        private const val HOME_STATIC_CACHE_TTL_MS = 7 * DAY_MS
        private const val DETAIL_ONGOING_CACHE_TTL_MS = DAY_MS
        private const val DETAIL_COMPLETED_CACHE_TTL_MS = 14 * DAY_MS
        private const val EXTERNAL_CACHE_TTL_MS = 24 * HOUR_MS
        private const val SESSION_HEADERS_CACHE_TTL_MS = 15 * HOUR_MS
        private const val PLAYER_EMBED_CACHE_TTL_MS = HOUR_MS
        private const val CACHE_PRIORITY_VOLATILE = 0
        private const val CACHE_PRIORITY_STANDARD = 1
        private const val CACHE_PRIORITY_IMPORTANT = 2
        private const val CACHE_PRIORITY_DETAIL = 3

        private val archiveBatchCache =
            ExpiringMemoryCache<String, ApiResponse>(HOME_DYNAMIC_CACHE_TTL_MS, 256)
        private val animePageDataCache =
            ExpiringMemoryCache<String, AnimePageData>(DETAIL_ONGOING_CACHE_TTL_MS, 128)
        private val animeLoadDataCache =
            ExpiringMemoryCache<String, AnimeLoadCacheData>(DETAIL_ONGOING_CACHE_TTL_MS, 64)
        private val aniZipMetadataCache =
            ExpiringMemoryCache<String, AniZipMetadata>(EXTERNAL_CACHE_TTL_MS, 128)
        private val anilistPosterCache =
            ExpiringMemoryCache<Int, String>(EXTERNAL_CACHE_TTL_MS, 256)
        private val trailerUrlCache =
            ExpiringMemoryCache<String, String>(EXTERNAL_CACHE_TTL_MS, 128)
        private val playerEmbedUrlCache =
            ExpiringMemoryCache<String, String>(PLAYER_EMBED_CACHE_TTL_MS, 256)
        private val revalidatingKeys = mutableSetOf<String>()
        private val randomSessionSeenIds = mutableMapOf<String, MutableSet<Int>>()
        private val homeSectionConfigs = listOf(
            HomeSectionConfig(
                key = AnimeUnitySections.LATEST,
                path = "",
                enabledPrefKey = AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES,
                titlePrefKey = AnimeUnityPlugin.PREF_LATEST_TITLE,
                defaultTitle = latestEpisodesSectionName,
                countPrefKey = AnimeUnityPlugin.PREF_LATEST_COUNT,
            ),
            HomeSectionConfig(
                key = AnimeUnitySections.CALENDAR,
                path = "calendario",
                enabledPrefKey = AnimeUnityPlugin.PREF_SHOW_CALENDAR,
                titlePrefKey = AnimeUnityPlugin.PREF_CALENDAR_TITLE,
                defaultTitle = calendarSectionName,
                countPrefKey = AnimeUnityPlugin.PREF_CALENDAR_COUNT,
            ),
            HomeSectionConfig(
                key = AnimeUnitySections.ONGOING,
                path = "archivio/",
                enabledPrefKey = AnimeUnityPlugin.PREF_SHOW_ONGOING,
                titlePrefKey = AnimeUnityPlugin.PREF_ONGOING_TITLE,
                defaultTitle = ongoingSectionName,
                countPrefKey = AnimeUnityPlugin.PREF_ONGOING_COUNT,
            ),
            HomeSectionConfig(
                key = AnimeUnitySections.POPULAR,
                path = "archivio/",
                enabledPrefKey = AnimeUnityPlugin.PREF_SHOW_POPULAR,
                titlePrefKey = AnimeUnityPlugin.PREF_POPULAR_TITLE,
                defaultTitle = popularSectionName,
                countPrefKey = AnimeUnityPlugin.PREF_POPULAR_COUNT,
            ),
            HomeSectionConfig(
                key = AnimeUnitySections.BEST,
                path = "archivio/",
                enabledPrefKey = AnimeUnityPlugin.PREF_SHOW_BEST,
                titlePrefKey = AnimeUnityPlugin.PREF_BEST_TITLE,
                defaultTitle = bestSectionName,
                countPrefKey = AnimeUnityPlugin.PREF_BEST_COUNT,
            ),
            HomeSectionConfig(
                key = AnimeUnitySections.UPCOMING,
                path = "archivio/",
                enabledPrefKey = AnimeUnityPlugin.PREF_SHOW_UPCOMING,
                titlePrefKey = AnimeUnityPlugin.PREF_UPCOMING_TITLE,
                defaultTitle = upcomingSectionName,
                countPrefKey = AnimeUnityPlugin.PREF_UPCOMING_COUNT,
            ),
            HomeSectionConfig(
                key = AnimeUnitySections.RANDOM,
                path = "archivio/",
                enabledPrefKey = AnimeUnityPlugin.PREF_SHOW_RANDOM,
                titlePrefKey = AnimeUnityPlugin.PREF_RANDOM_TITLE,
                defaultTitle = randomSectionName,
                countPrefKey = AnimeUnityPlugin.PREF_RANDOM_COUNT,
                defaultCount = AnimeUnityPlugin.DEFAULT_RANDOM_COUNT,
            ),
        )
        private val homeSectionConfigByKey = homeSectionConfigs.associateBy { it.key }
        private val requestDataSectionKeys = setOf(
            AnimeUnitySections.ADVANCED,
            AnimeUnitySections.ONGOING,
            AnimeUnitySections.POPULAR,
            AnimeUnitySections.BEST,
            AnimeUnitySections.UPCOMING,
        )
        private val staleWhileRevalidateSectionKeys = setOf(
            AnimeUnitySections.LATEST,
            AnimeUnitySections.ONGOING,
            AnimeUnitySections.POPULAR,
            AnimeUnitySections.BEST,
            AnimeUnitySections.UPCOMING,
        )

        fun clearAllCaches() {
            archiveBatchCache.clear()
            animePageDataCache.clear()
            animeLoadDataCache.clear()
            aniZipMetadataCache.clear()
            anilistPosterCache.clear()
            trailerUrlCache.clear()
            playerEmbedUrlCache.clear()
            synchronized(revalidatingKeys) {
                revalidatingKeys.clear()
            }
            synchronized(randomSessionSeenIds) {
                randomSessionSeenIds.clear()
            }
            AnimeUnityCache.clear()
        }
    }

    private data class ArchivePageResult(
        val titles: List<Anime>,
        val hasNextPage: Boolean,
    )

    private data class MainPageSectionData(
        val key: String,
        val baseUrl: String,
    )

    private data class HomeSectionConfig(
        val key: String,
        val path: String,
        val enabledPrefKey: String,
        val titlePrefKey: String,
        val defaultTitle: String,
        val countPrefKey: String,
        val defaultCount: Int = AnimeUnityPlugin.DEFAULT_SECTION_COUNT,
    )

    private data class GroupedAnimeCard(
        val anime: Anime,
        val badges: EpisodeBadgeState,
    )

    private data class GroupedLatestEpisodeCard(
        val anime: LatestEpisodeAnime,
        val badges: EpisodeBadgeState,
        val episodeNumber: String,
        val score: String?,
    )

    private data class CalendarAnimeItem(
        val anime: Anime,
        val episodeNumber: Int?,
    )

    private data class GroupedCalendarAnimeCard(
        val anime: Anime,
        val badges: EpisodeBadgeState,
        val episodeNumber: Int?,
    )

    private data class EpisodeBadgeState(
        val hasSub: Boolean,
        val hasDub: Boolean,
        val subEpisodeCount: Int? = null,
        val dubEpisodeCount: Int? = null,
    )

    private data class AnimePageData(
        val anime: Anime,
        val relatedAnime: List<Anime>,
        val episodes: List<Episode>,
    )

    private data class EpisodeSource(
        val number: String,
        val url: String,
    )

    private data class EpisodeMetadataIndex(
        val byExactNumber: Map<String, AniZipEpisode>,
        val byNormalizedNumber: Map<String, AniZipEpisode>,
    )

    private data class EpisodePlaybackData(
        val preferredUrl: String,
        val subUrl: String?,
        val dubUrl: String?,
    )

    private data class AnimeLoadCacheData(
        val currentPageData: AnimePageData,
        val variants: List<Anime>,
        val subPageData: AnimePageData?,
        val dubPageData: AnimePageData?,
        val episodeMetadata: AniZipMetadata?,
        val trailerUrl: String?,
        val fingerprint: String,
    )

    private data class SessionHeadersCacheData(
        val headers: Map<String, String>,
        val savedAtMs: Long,
    )

    private data class PlayerSourceOption(
        val label: String,
        val url: String,
    )

    override val mainPage: List<MainPageData>
        get() = buildSectionNamesList()

    private fun encodeMainPageSectionData(sectionKey: String, baseUrl: String): String {
        return "$sectionKey|$baseUrl"
    }

    private fun decodeMainPageSectionData(data: String): MainPageSectionData {
        val separatorIndex = data.indexOf('|')
        return if (separatorIndex == -1) {
            MainPageSectionData(key = data, baseUrl = data)
        } else {
            MainPageSectionData(
                key = data.substring(0, separatorIndex),
                baseUrl = data.substring(separatorIndex + 1),
            )
        }
    }

    private fun getSectionDisplayTitle(sectionKey: String): String {
        if (sectionKey == AnimeUnitySections.ADVANCED) return advancedSearchSectionName

        val config = homeSectionConfigByKey[sectionKey] ?: return advancedSearchSectionName
        return AnimeUnityPlugin.getConfiguredSectionTitle(
            sharedPref,
            config.titlePrefKey,
            config.defaultTitle,
        )
    }

    private fun buildSectionNamesList(): List<MainPageData> {
        val order = AnimeUnityPlugin.getConfiguredSectionOrder(sharedPref)
        val sections = buildList {
            if (AnimeUnityPlugin.isAdvancedSearchEnabled(sharedPref)) {
                add(AnimeUnitySections.ADVANCED)
            }
            addAll(order.split(","))
        }
        
        return mainPageOf(
            *sections.mapNotNull { section ->
                when (section) {
                    AnimeUnitySections.ADVANCED ->
                        encodeMainPageSectionData(
                            AnimeUnitySections.ADVANCED,
                            "$mainUrl/archivio/",
                        ) to advancedSearchSectionName
                    else -> homeSectionConfigByKey[section]
                        ?.takeIf { config -> isSectionEnabled(config.enabledPrefKey) }
                        ?.let { config ->
                            encodeMainPageSectionData(
                                config.key,
                                "$mainUrl/${config.path}",
                            ) to getSectionDisplayTitle(config.key)
                        }
                }
            }.toTypedArray()
        )
    }

    private fun isSectionEnabled(prefKey: String): Boolean {
        return sharedPref?.getBoolean(prefKey, true) ?: true
    }

    private fun getSectionCount(sectionKey: String): Int {
        val (key, defaultCount) = if (sectionKey == AnimeUnitySections.ADVANCED) {
            AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT to AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT
        } else {
            val config = homeSectionConfigByKey[sectionKey]
                ?: return AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            config.countPrefKey to config.defaultCount
        }

        return (sharedPref?.getInt(key, defaultCount)
            ?: defaultCount).coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
    }

    private fun shouldShowScore(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_SCORE, true) ?: true
    }

    private fun shouldShowDubSub(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, true) ?: true
    }

    private fun shouldShowEpisodeNumber(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, true) ?: true
    }

    private fun shouldUseUnifiedDubSubCards(): Boolean {
        return AnimeUnityPlugin.shouldUseUnifiedDubSubCards(sharedPref)
    }

    private fun displayCacheKey(): String {
        return listOf(
            shouldShowScore(),
            shouldShowDubSub(),
            shouldShowEpisodeNumber(),
            shouldUseUnifiedDubSubCards(),
        ).joinToString(":")
    }

    private fun homeCacheKey(
        sectionData: MainPageSectionData,
        page: Int,
        sectionTitle: String,
    ): String {
        val sectionRequestDataKey = if (sectionData.key in requestDataSectionKeys) {
            getDataPerHomeSection(sectionData.key).toString()
        } else {
            ""
        }
        val dayKey = if (sectionData.key == AnimeUnitySections.CALENDAR) {
            normalizeDayName(getCurrentItalianDayName())
        } else {
            ""
        }

        return listOf(
            CACHE_SCHEMA,
            "home",
            mainUrl,
            sectionData.key,
            sectionData.baseUrl,
            page,
            sectionTitle,
            getSectionCount(sectionData.key),
            displayCacheKey(),
            sectionRequestDataKey,
            dayKey,
        ).joinToString("|")
    }

    private fun animeUrlPathKey(url: String): String {
        val animePath = url.substringAfter("/anime/", missingDelimiterValue = "")
        return if (animePath.isNotBlank()) "/anime/${animePath.substringBefore('?')}" else url
    }

    private fun loadCacheKey(url: String): String {
        return listOf(
            CACHE_SCHEMA,
            "load",
            mainUrl,
            animeUrlPathKey(url),
            shouldUseUnifiedDubSubCards(),
        ).joinToString("|")
    }

    private fun legacyLoadCacheKey(url: String): String {
        return listOf(
            CACHE_SCHEMA,
            "load",
            mainUrl,
            animeUrlPathKey(url),
            displayCacheKey(),
        ).joinToString("|")
    }

    private fun loadCacheKeys(url: String): List<String> {
        return listOf(loadCacheKey(url), legacyLoadCacheKey(url)).distinct()
    }

    private fun archiveBatchCacheKey(url: String, requestData: RequestData): String {
        return listOf(CACHE_SCHEMA, "archive", mainUrl, url, requestData.toString()).joinToString("|")
    }

    private fun animePageDataCacheKey(url: String): String {
        return listOf(CACHE_SCHEMA, "anime-page", mainUrl, animeUrlPathKey(url)).joinToString("|")
    }

    private fun sessionHeadersCacheKey(): String {
        return listOf(CACHE_SCHEMA, "session-headers", mainUrl.toHttpUrl().host).joinToString("|")
    }

    private fun playerEmbedCacheKey(url: String): String {
        return listOf(CACHE_SCHEMA, "player-embed", mainUrl, animeUrlPathKey(url)).joinToString("|")
    }

    private fun aniZipMetadataCacheKey(malId: Int?, anilistId: Int?): String {
        return listOf(CACHE_SCHEMA, "anizip", "mal:${malId ?: ""}", "anilist:${anilistId ?: ""}")
            .joinToString("|")
    }

    private fun trailerUrlCacheKey(anime: Anime): String {
        return listOf(
            CACHE_SCHEMA,
            "trailer",
            anime.id,
            anime.malId ?: "",
            anime.anilistId ?: "",
            anime.title ?: "",
            anime.titleEng ?: "",
            anime.titleIt ?: "",
        ).joinToString("|")
    }

    private fun getHomeCacheTtlMs(sectionKey: String): Long {
        return when (sectionKey) {
            AnimeUnitySections.LATEST -> LATEST_CACHE_TTL_MS
            AnimeUnitySections.ONGOING,
            AnimeUnitySections.POPULAR -> HOME_DYNAMIC_CACHE_TTL_MS
            AnimeUnitySections.BEST,
            AnimeUnitySections.UPCOMING -> HOME_STATIC_CACHE_TTL_MS
            else -> HOME_DYNAMIC_CACHE_TTL_MS
        }
    }

    private fun getHomeCacheExpirationMs(sectionKey: String): Long? {
        if (sectionKey != AnimeUnitySections.CALENDAR) return null

        val calendar = java.util.Calendar.getInstance(Locale.ITALY).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun getHomeCachePriority(sectionKey: String): Int {
        return when (sectionKey) {
            AnimeUnitySections.ONGOING,
            AnimeUnitySections.POPULAR,
            AnimeUnitySections.BEST,
            AnimeUnitySections.UPCOMING -> CACHE_PRIORITY_STANDARD
            else -> CACHE_PRIORITY_VOLATILE
        }
    }

    private fun getAnimeDetailTtlMs(anime: Anime): Long {
        return if (getShowStatus(anime.status) == ShowStatus.Completed) {
            DETAIL_COMPLETED_CACHE_TTL_MS
        } else {
            DETAIL_ONGOING_CACHE_TTL_MS
        }
    }

    private fun shouldUseStaleWhileRevalidate(sectionKey: String): Boolean {
        return sectionKey in staleWhileRevalidateSectionKeys
    }

    private fun ArchivePageResult.firstIdentity(): String? {
        val anime = titles.firstOrNull() ?: return null
        return "${anime.id}:${anime.slug}:${anime.episodesCount}:${anime.realEpisodesCount}:${anime.score}"
    }

    private fun List<LatestEpisodeItem>.firstIdentity(): String? {
        val item = firstOrNull() ?: return null
        return "${item.id}:${item.animeId}:${item.number}:${item.anime.score}"
    }

    private fun AnimeLoadCacheData.primaryAnime(): Anime {
        return subPageData?.anime ?: dubPageData?.anime ?: currentPageData.anime
    }

    private fun AnimeLoadCacheData.totalEpisodeCount(): Int {
        val subCount = subPageData?.episodes?.size ?: 0
        val dubCount = dubPageData?.episodes?.size ?: 0
        return maxOf(subCount, dubCount, currentPageData.episodes.size)
    }

    private fun buildAnimeLoadFingerprint(
        primaryAnime: Anime,
        subPageData: AnimePageData?,
        dubPageData: AnimePageData?,
        episodeMetadata: AniZipMetadata?,
    ): String {
        val subCount = subPageData?.episodes?.size ?: 0
        val dubCount = dubPageData?.episodes?.size ?: 0
        val metadataCount = episodeMetadata?.episodes?.size ?: 0
        return listOf(
            primaryAnime.id,
            primaryAnime.status,
            primaryAnime.score ?: "",
            primaryAnime.realEpisodesCount ?: "",
            primaryAnime.episodesCount,
            subCount,
            dubCount,
            metadataCount,
        ).joinToString(":")
    }

    private inline fun <reified T> readDiskCache(key: String, allowExpired: Boolean = false): T? {
        val payload = AnimeUnityCache.read(key, allowExpired)?.payload ?: return null
        return runCatching { parseJson<T>(payload) }.getOrNull()
            ?: run {
                AnimeUnityCache.remove(key)
                null
            }
    }

    private inline fun <reified T> readDiskCacheRecord(
        key: String,
        allowExpired: Boolean = false,
    ): Pair<T, AnimeUnityCache.CacheRecord>? {
        val record = AnimeUnityCache.read(key, allowExpired) ?: return null
        val value = runCatching { parseJson<T>(record.payload) }.getOrNull()
            ?: run {
                AnimeUnityCache.remove(key)
                return null
            }
        return value to record
    }

    private fun readDiskTextCache(key: String, allowExpired: Boolean = false): String? {
        return AnimeUnityCache.read(key, allowExpired)?.payload
    }

    private fun writeDiskCache(
        key: String,
        namespace: String,
        payload: Any,
        ttlMs: Long,
        expiresAtMs: Long? = null,
        priority: Int = 0,
        pinned: Boolean = false,
    ) {
        AnimeUnityCache.write(
            key = key,
            namespace = namespace,
            payload = payload.toJson(),
            ttlMs = ttlMs,
            expiresAtMs = expiresAtMs,
            priority = priority,
            pinned = pinned,
        )
    }

    private fun writeDiskTextCache(
        key: String,
        namespace: String,
        payload: String,
        ttlMs: Long,
        expiresAtMs: Long? = null,
        priority: Int = 0,
        pinned: Boolean = false,
    ) {
        AnimeUnityCache.write(
            key = key,
            namespace = namespace,
            payload = payload,
            ttlMs = ttlMs,
            expiresAtMs = expiresAtMs,
            priority = priority,
            pinned = pinned,
        )
    }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Result.failure(throwable)
        }
    }

    private suspend fun <A, B> Iterable<A>.safeAmap(
        concurrency: Int = 5,
        transform: suspend (A) -> B?,
    ): List<B> = supervisorScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        map { item ->
            async<B?>(Dispatchers.IO) {
                var acquiredPermit = false
                try {
                    semaphore.acquire()
                    acquiredPermit = true
                    transform(item)
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    null
                } finally {
                    if (acquiredPermit) semaphore.release()
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun runLimitedAsync(
        concurrency: Int = 7,
        vararg tasks: suspend () -> Unit,
    ) = supervisorScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        tasks.map { task ->
            async(Dispatchers.IO) {
                var acquiredPermit = false
                try {
                    semaphore.acquire()
                    acquiredPermit = true
                    task()
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                } finally {
                    if (acquiredPermit) semaphore.release()
                }
            }
        }.awaitAll()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun revalidateInBackground(
        key: String,
        block: suspend () -> Unit,
    ) {
        synchronized(revalidatingKeys) {
            if (!revalidatingKeys.add(key)) return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                block()
            } finally {
                synchronized(revalidatingKeys) {
                    revalidatingKeys.remove(key)
                }
            }
        }
    }

    private fun withoutDubSuffix(title: String): String {
        return title.replace(" (ITA)", "")
    }

    private fun getAnimeTitle(anime: Anime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title ?: anime.slug
    }

    private fun getAnimeTitle(anime: LatestEpisodeAnime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title ?: anime.slug
    }

    private fun isDubAnime(anime: Anime): Boolean {
        return anime.dub == 1 || getAnimeTitle(anime).contains("(ITA)")
    }

    private fun isDubAnime(anime: LatestEpisodeAnime): Boolean {
        return anime.dub == 1 || getAnimeTitle(anime).contains("(ITA)")
    }

    private fun positiveEpisodeCount(count: Int?): Int? {
        return count?.takeIf { it > 0 }
    }

    private fun getEpisodeCount(anime: Anime): Int? {
        return positiveEpisodeCount(anime.realEpisodesCount)
            ?: positiveEpisodeCount(anime.episodesCount)
    }

    private fun getEpisodeCount(anime: LatestEpisodeAnime): Int? {
        return positiveEpisodeCount(anime.realEpisodesCount)
            ?: positiveEpisodeCount(anime.episodesCount)
    }

    private fun buildAnimeBadgeState(anime: Anime): EpisodeBadgeState {
        val episodeCount = getEpisodeCount(anime)
        return if (isDubAnime(anime)) {
            EpisodeBadgeState(hasSub = false, hasDub = true, dubEpisodeCount = episodeCount)
        } else {
            EpisodeBadgeState(hasSub = true, hasDub = false, subEpisodeCount = episodeCount)
        }
    }

    private fun buildLatestEpisodeBadgeState(item: LatestEpisodeItem): EpisodeBadgeState {
        val episodeNumber = positiveEpisodeCount(item.number.toIntOrNull())
        return if (isDubAnime(item.anime)) {
            EpisodeBadgeState(hasSub = false, hasDub = true, dubEpisodeCount = episodeNumber)
        } else {
            EpisodeBadgeState(hasSub = true, hasDub = false, subEpisodeCount = episodeNumber)
        }
    }

    private fun getFirstAvailableScore(items: List<LatestEpisodeItem>): String? {
        return items.firstNotNullOfOrNull { item ->
            item.anime.score?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private fun combineAnimeBadgeStates(animes: List<Anime>): EpisodeBadgeState {
        val subEpisodeCount = animes
            .filterNot(::isDubAnime)
            .mapNotNull(::getEpisodeCount)
            .maxOrNull()
        val dubEpisodeCount = animes
            .filter(::isDubAnime)
            .mapNotNull(::getEpisodeCount)
            .maxOrNull()

        return EpisodeBadgeState(
            hasSub = animes.any { !isDubAnime(it) },
            hasDub = animes.any(::isDubAnime),
            subEpisodeCount = subEpisodeCount,
            dubEpisodeCount = dubEpisodeCount,
        )
    }

    private fun combineCalendarBadgeStates(items: List<CalendarAnimeItem>): EpisodeBadgeState {
        val subEpisodeNumber = items
            .filterNot { isDubAnime(it.anime) }
            .mapNotNull { positiveEpisodeCount(it.episodeNumber) }
            .maxOrNull()
        val dubEpisodeNumber = items
            .filter { isDubAnime(it.anime) }
            .mapNotNull { positiveEpisodeCount(it.episodeNumber) }
            .maxOrNull()

        return EpisodeBadgeState(
            hasSub = items.any { !isDubAnime(it.anime) },
            hasDub = items.any { isDubAnime(it.anime) },
            subEpisodeCount = subEpisodeNumber,
            dubEpisodeCount = dubEpisodeNumber,
        )
    }

    private fun EpisodeBadgeState.withEpisodeNumber(episodeNumber: Int?): EpisodeBadgeState {
        val normalizedEpisodeNumber = positiveEpisodeCount(episodeNumber) ?: return this
        return copy(
            subEpisodeCount = if (hasSub) normalizedEpisodeNumber else null,
            dubEpisodeCount = if (hasDub) normalizedEpisodeNumber else null,
        )
    }

    private fun Anime.contentKey(): String {
        return when {
            anilistId != null -> "anilist:$anilistId"
            malId != null -> "mal:$malId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun LatestEpisodeAnime.contentKey(): String {
        return when {
            anilistId != null -> "anilist:$anilistId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun Anime.cardKey(): String {
        return if (shouldUseUnifiedDubSubCards()) contentKey() else "anime:$id"
    }

    private fun preferAnime(primary: Anime, candidate: Anime): Anime {
        val primaryIsDub = isDubAnime(primary)
        val candidateIsDub = isDubAnime(candidate)

        return when {
            primaryIsDub && !candidateIsDub -> candidate
            !primaryIsDub && candidateIsDub -> primary
            (getEpisodeCount(candidate) ?: 0) > (getEpisodeCount(primary) ?: 0) -> candidate
            else -> primary
        }
    }

    private fun preferAnime(primary: LatestEpisodeAnime, candidate: LatestEpisodeAnime): LatestEpisodeAnime {
        val primaryIsDub = isDubAnime(primary)
        val candidateIsDub = isDubAnime(candidate)

        return when {
            primaryIsDub && !candidateIsDub -> candidate
            !primaryIsDub && candidateIsDub -> primary
            (getEpisodeCount(candidate) ?: 0) > (getEpisodeCount(primary) ?: 0) -> candidate
            else -> primary
        }
    }

    private fun groupAnimeCards(animes: List<Anime>): List<GroupedAnimeCard> {
        if (animes.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return animes.map { anime ->
                GroupedAnimeCard(
                    anime = anime,
                    badges = buildAnimeBadgeState(anime),
                )
            }
        }

        return animes
            .groupBy { it.contentKey() }
            .values
            .map { variants ->
                GroupedAnimeCard(
                    anime = variants.reduce { primary, candidate -> preferAnime(primary, candidate) },
                    badges = combineAnimeBadgeStates(variants),
                )
            }
    }

    private fun groupCalendarAnimeCards(items: List<CalendarAnimeItem>): List<GroupedCalendarAnimeCard> {
        if (items.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return items.map { item ->
                GroupedCalendarAnimeCard(
                    anime = item.anime,
                    badges = buildAnimeBadgeState(item.anime).withEpisodeNumber(item.episodeNumber),
                    episodeNumber = item.episodeNumber,
                )
            }
        }

        return items
            .groupBy { it.anime.contentKey() }
            .values
            .map { variants ->
                val latestEpisode = variants.maxWithOrNull(
                    compareBy<CalendarAnimeItem>(
                        { it.episodeNumber ?: Int.MIN_VALUE },
                        { getAnimeTitle(it.anime) }
                    )
                ) ?: variants.first()

                GroupedCalendarAnimeCard(
                    anime = variants.map { it.anime }.reduce { primary, candidate -> preferAnime(primary, candidate) },
                    badges = combineCalendarBadgeStates(variants),
                    episodeNumber = latestEpisode.episodeNumber,
                )
            }
    }

    private fun groupLatestEpisodeCards(items: List<LatestEpisodeItem>): List<GroupedLatestEpisodeCard> {
        if (items.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return items.map { item ->
                GroupedLatestEpisodeCard(
                    anime = item.anime,
                    badges = buildLatestEpisodeBadgeState(item),
                    episodeNumber = item.number,
                    score = item.anime.score,
                )
            }
        }

        return items
            .groupBy { it.anime.contentKey() }
            .values
            .map { variants ->
                val latestEpisode = variants.first()

                GroupedLatestEpisodeCard(
                    anime = latestEpisode.anime,
                    badges = buildLatestEpisodeBadgeState(latestEpisode),
                    episodeNumber = latestEpisode.number,
                    score = latestEpisode.anime.score?.takeIf(String::isNotBlank)
                        ?: getFirstAvailableScore(variants),
                )
            }
    }

    private fun getShowStatus(status: String?): ShowStatus? {
        return when (status?.trim()?.lowercase(Locale.ROOT)) {
            "terminato" -> ShowStatus.Completed
            "in corso" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun getStatusTag(status: String?): String? {
        val normalizedStatus = status?.trim().orEmpty()
        if (normalizedStatus.isBlank()) return null
        return if (getShowStatus(normalizedStatus) == null) "Stato: $normalizedStatus" else null
    }

    private fun buildDisplayTitle(title: String, episodeNumber: Int?): String {
        val baseTitle = withoutDubSuffix(title)

        return if (!shouldShowDubSub() && shouldShowEpisodeNumber() && episodeNumber != null) {
            "$baseTitle - Ep. $episodeNumber"
        } else {
            baseTitle
        }
    }

    private fun applyCardDisplayState(
        response: AnimeSearchResponse,
        badges: EpisodeBadgeState,
        poster: String?,
        score: String?,
    ) {
        if (shouldShowDubSub()) {
            if (shouldShowEpisodeNumber()) {
                if (badges.hasSub) {
                    badges.subEpisodeCount?.let(response::addSub) ?: response.addDubStatus(DubStatus.Subbed)
                }
                if (badges.hasDub) {
                    badges.dubEpisodeCount?.let(response::addDub) ?: response.addDubStatus(DubStatus.Dubbed)
                }
            } else {
                if (badges.hasSub) {
                    response.addDubStatus(DubStatus.Subbed)
                }
                if (badges.hasDub) {
                    response.addDubStatus(DubStatus.Dubbed)
                }
            }
        }

        response.addPoster(poster)

        if (shouldShowScore()) {
            score?.let {
                response.score = Score.from(it, 10)
            }
        }
    }

    private suspend fun ensureHeadersAndCookies(forceReset: Boolean = false) {
        val currentHost = mainUrl.toHttpUrl().host
        val shouldRefreshHeaders = forceReset ||
            headers["Host"] != currentHost ||
            headers["Referer"] != mainUrl ||
            !headers.containsKey("Cookie")

        if (shouldRefreshHeaders) {
            resetHeadersAndCookies()
            setupHeadersAndCookies()
        }
    }

    private suspend fun setupHeadersAndCookies(forceNetwork: Boolean = false) {
        val currentHost = mainUrl.toHttpUrl().host
        val cacheKey = sessionHeadersCacheKey()
        if (!forceNetwork) {
            readDiskCache<SessionHeadersCacheData>(cacheKey)?.let { cachedSession ->
                val cachedHeaders = cachedSession.headers
                val isFresh = System.currentTimeMillis() - cachedSession.savedAtMs < SESSION_HEADERS_CACHE_TTL_MS
                val isForCurrentHost = cachedHeaders["Host"] == currentHost &&
                    cachedHeaders["Referer"] == mainUrl
                val hasSessionData = cachedHeaders["Cookie"].orEmpty().isNotBlank() &&
                    cachedHeaders["X-CSRF-Token"].orEmpty().isNotBlank()

                if (isFresh && isForCurrentHost && hasSessionData) {
                    headers.putAll(cachedHeaders)
                    return
                }
            }
        }

        val response = app.get("$mainUrl/archivio", headers = headers)

        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies =
            "XSRF-TOKEN=${response.cookies["XSRF-TOKEN"]}; animeunity_session=${response.cookies["animeunity_session"]}"
        val h = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json;charset=utf-8",
            "X-CSRF-Token" to csrfToken,
            "Referer" to mainUrl,
            "Cookie" to cookies
        )
        headers.putAll(h)
        writeDiskCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_EXTERNAL,
            payload = SessionHeadersCacheData(
                headers = headers.toMap(),
                savedAtMs = System.currentTimeMillis(),
            ),
            ttlMs = SESSION_HEADERS_CACHE_TTL_MS,
            priority = CACHE_PRIORITY_IMPORTANT,
        )
    }

    private fun resetHeadersAndCookies() {
        if (headers.isNotEmpty()) {
            headers.clear()
        }
        headers["Host"] = mainUrl.toHttpUrl().host
        headers["User-Agent"] =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
    }

    private suspend fun searchResponseBuilder(
        objectList: List<Anime>,
        episodeNumber: Int? = null,
        limit: Int? = null,
    ): List<SearchResponse> {
        val groupedCards = groupAnimeCards(objectList).let { cards ->
            limit?.let(cards::take) ?: cards
        }

        return groupedCards.safeAmap(concurrency = 6) { entry ->
            val anime = entry.anime
            val title = getAnimeTitle(anime)
            val poster = runCatchingCancellable { getImage(anime.imageUrl, anime.anilistId) }.getOrNull()
            val badges = entry.badges.withEpisodeNumber(episodeNumber)

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, episodeNumber),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    badges = badges,
                    poster = poster,
                    score = anime.score
                )
            }
        }
    }

    private suspend fun fetchArchiveBatch(url: String, requestData: RequestData): ApiResponse {
        val cacheKey = archiveBatchCacheKey(url, requestData)
        archiveBatchCache.get(cacheKey)?.let { return it }
        readDiskCache<ApiResponse>(cacheKey)?.let { cachedResponse ->
            archiveBatchCache.put(cacheKey, cachedResponse)
            return cachedResponse
        }

        val response = app.post(url, headers = headers, requestBody = requestData.toRequestBody())
        val parsedResponse = parseJson<ApiResponse>(response.text)
        archiveBatchCache.put(cacheKey, parsedResponse)
        writeDiskCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_ARCHIVE,
            payload = parsedResponse,
            ttlMs = HOME_DYNAMIC_CACHE_TTL_MS,
            priority = CACHE_PRIORITY_STANDARD,
        )
        return parsedResponse
    }

    private suspend fun fetchArchiveSectionPage(
        url: String,
        requestData: RequestData,
        page: Int,
        sectionCount: Int,
    ): ArchivePageResult {
        val collectedTitles = mutableListOf<Anime>()
        val uniqueContentKeys = linkedSetOf<String>()
        var nextOffset = (page - 1) * sectionCount
        var total = 0

        while (uniqueContentKeys.size < sectionCount) {
            val responseObject = fetchArchiveBatch(url, requestData.copy(offset = nextOffset))
            total = responseObject.total

            val batchTitles = responseObject.titles.orEmpty()
            if (batchTitles.isEmpty()) break

            collectedTitles += batchTitles
            batchTitles.forEach { uniqueContentKeys += it.cardKey() }
            nextOffset += batchTitles.size

            if (nextOffset >= total || batchTitles.size < ARCHIVE_BATCH_SIZE) {
                break
            }
        }

        return ArchivePageResult(
            titles = collectedTitles,
            hasNextPage = nextOffset < total,
        )
    }

    private suspend fun fetchRandomTitles(
        url: String,
        sectionCount: Int,
        excludedIds: Set<Int> = emptySet(),
    ): Pair<List<Anime>, Int> {
        val requestData = RequestData(dubbed = 0)
        val initialResponse = fetchArchiveBatch(url, requestData.copy(offset = 0))
        val total = initialResponse.total
        val collectedTitles = linkedMapOf<Int, Anime>()
        val requestedOffsets = mutableSetOf<Int>()

        fun collectBatch(batch: List<Anime>) {
            batch.filterNot { it.id in excludedIds }.forEach { anime ->
                collectedTitles.putIfAbsent(anime.id, anime)
            }
        }

        if (total <= 0) return emptyList<Anime>() to 0

        if (total <= ARCHIVE_BATCH_SIZE) {
            collectBatch(initialResponse.titles.orEmpty())
            return collectedTitles.values.shuffled() to total
        }

        val maxRequests = when {
            sectionCount <= 10 -> 2
            sectionCount <= 20 -> 3
            else -> 4
        }

        repeat(maxRequests) {
            if (collectedTitles.size >= sectionCount * 2) return@repeat

            val maxOffset = (total - ARCHIVE_BATCH_SIZE).coerceAtLeast(0)
            val randomOffset = if (maxOffset == 0) 0 else (0..maxOffset).random()
            
            if (requestedOffsets.add(randomOffset)) {
                try {
                    val batch = fetchArchiveBatch(url, requestData.copy(offset = randomOffset)).titles
                    if (!batch.isNullOrEmpty()) {
                        collectBatch(batch)
                    }
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                }
            }
        }

        if (collectedTitles.size < sectionCount) {
            collectBatch(initialResponse.titles.orEmpty())
        }

        val result = collectedTitles.values.toList().shuffled()
        return result to total
    }

    private suspend fun latestEpisodesResponseBuilder(
        objectList: List<LatestEpisodeItem>,
        limit: Int,
    ): List<SearchResponse> {
        return groupLatestEpisodeCards(objectList).take(limit).safeAmap(concurrency = 6) { entry ->
            val anime = entry.anime
            val title = getAnimeTitle(anime)
            val poster = runCatchingCancellable { getImage(anime.imageUrl, anime.anilistId) }.getOrNull()

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, entry.episodeNumber.toIntOrNull()),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    badges = entry.badges,
                    poster = poster,
                    score = entry.score
                )
            }
        }
    }

    private suspend fun calendarResponseBuilder(
        items: List<CalendarAnimeItem>,
        limit: Int,
    ): List<SearchResponse> {
        return groupCalendarAnimeCards(items).take(limit).safeAmap(concurrency = 6) { entry ->
            val anime = entry.anime
            val title = getAnimeTitle(anime)
            val poster = runCatchingCancellable { getImage(anime.imageUrl, anime.anilistId) }.getOrNull()

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, entry.episodeNumber),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    badges = entry.badges,
                    poster = poster,
                    score = anime.score,
                )
            }
        }
    }

    private fun getImageCdnHost(): String {
        val host = mainUrl.toHttpUrl().host
        return when {
            host == "animeunity.so" -> "img.animeunity.so"
            host.startsWith("www.") -> host.replaceFirst("www.", "img.")
            host.startsWith("img.") -> host
            else -> "img.$host"
        }
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        if (!imageUrl.isNullOrEmpty()) {
            val fileName = imageUrl.substringAfterLast("/")
            if (fileName.isNotBlank()) {
                return "https://${getImageCdnHost()}/anime/$fileName"
            }
        }
        return anilistId?.let { getAnilistPoster(it) }
    }

    private fun getAnimeUrl(anime: Anime): String {
        return "$mainUrl/anime/${anime.id}-${anime.slug}"
    }

    private fun getEpisodeUrl(anime: Anime, episode: Episode): String {
        return "${getAnimeUrl(anime)}/${episode.id}"
    }

    private fun parseEpisodeSortValue(number: String?): Double? {
        return number
            ?.trim()
            ?.replace(',', '.')
            ?.toDoubleOrNull()
    }

    private fun buildEpisodeDisplayName(number: String): String {
        return "Episodio $number"
    }

    private fun normalizeEpisodeNumber(number: String?): String? {
        val normalized = number
            ?.trim()
            ?.replace(',', '.')
            ?.takeIf(String::isNotBlank)
            ?: return null
        val numericValue = normalized.toDoubleOrNull() ?: return normalized
        val intValue = numericValue.toInt()

        return if (numericValue == intValue.toDouble()) intValue.toString() else numericValue.toString()
    }

    private fun buildEpisodeMetadataIndex(metadata: AniZipMetadata?): EpisodeMetadataIndex {
        val exactEpisodes = linkedMapOf<String, AniZipEpisode>()
        val normalizedEpisodes = linkedMapOf<String, AniZipEpisode>()

        metadata?.episodes.orEmpty().forEach { (key, episode) ->
            key.takeIf(String::isNotBlank)?.let { exactEpisodes.putIfAbsent(it, episode) }
            normalizeEpisodeNumber(key)?.let { normalizedEpisodes.putIfAbsent(it, episode) }

            val episodeNumber = episode.episode?.trim()?.takeIf(String::isNotBlank)
            episodeNumber?.let { exactEpisodes.putIfAbsent(it, episode) }
            episodeNumber?.let(::normalizeEpisodeNumber)?.let {
                normalizedEpisodes.putIfAbsent(it, episode)
            }
        }

        return EpisodeMetadataIndex(
            byExactNumber = exactEpisodes,
            byNormalizedNumber = normalizedEpisodes,
        )
    }

    private fun getAniZipEpisode(metadataIndex: EpisodeMetadataIndex, number: String): AniZipEpisode? {
        return metadataIndex.byExactNumber[number]
            ?: normalizeEpisodeNumber(number)?.let(metadataIndex.byNormalizedNumber::get)
    }

    private fun getAniZipEpisodeTitle(metadata: AniZipEpisode?): String? {
        val titles = metadata?.title ?: return null

        return listOf("it", "en", "x-jat", "ja")
            .firstNotNullOfOrNull { language -> titles[language]?.trim()?.takeIf(String::isNotBlank) }
            ?: titles.values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) }
    }

    private fun cleanExternalEpisodeText(text: String?): String? {
        return text
            ?.replace("<[^>]+>".toRegex(), " ")
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun getAniZipEpisodeDescription(metadata: AniZipEpisode?): String {
        return cleanExternalEpisodeText(metadata?.overview)
            ?: cleanExternalEpisodeText(metadata?.summary)
            ?: noEpisodeDescription
    }

    private fun getAniZipEpisodeAirDate(metadata: AniZipEpisode?): String? {
        return metadata?.airDateUtc
            ?.substringBefore("T")
            ?.takeIf(String::isNotBlank)
            ?: metadata?.airDate?.takeIf(String::isNotBlank)
    }

    private suspend fun fetchAniZipMetadata(malId: Int?, anilistId: Int?): AniZipMetadata? {
        val cacheKey = aniZipMetadataCacheKey(malId, anilistId)
        aniZipMetadataCache.get(cacheKey)?.let { return it }
        readDiskCache<AniZipMetadata>(cacheKey)?.let { cachedMetadata ->
            aniZipMetadataCache.put(cacheKey, cachedMetadata)
            return cachedMetadata
        }

        val identifiers = buildList {
            malId?.let { add("mal_id" to it) }
            anilistId?.let { add("anilist_id" to it) }
        }

        identifiers.forEach { (parameter, id) ->
            val url = "https://api.ani.zip/mappings".toHttpUrl().newBuilder()
                .addQueryParameter(parameter, id.toString())
                .build()
                .toString()
            val response = runCatchingCancellable { app.get(url).text }.getOrNull() ?: return@forEach
            val metadata = runCatching { parseJson<AniZipMetadata>(response) }.getOrNull()

            metadata?.let { resolvedMetadata ->
                if (
                    resolvedMetadata.episodes?.isNotEmpty() == true ||
                    resolvedMetadata.mappings?.hasSyncIds() == true
                ) {
                    aniZipMetadataCache.put(cacheKey, resolvedMetadata)
                    writeDiskCache(
                        key = cacheKey,
                        namespace = AnimeUnityCache.NAMESPACE_EXTERNAL,
                        payload = resolvedMetadata,
                        ttlMs = EXTERNAL_CACHE_TTL_MS,
                        priority = CACHE_PRIORITY_STANDARD,
                    )
                    return resolvedMetadata
                }
            }
        }

        return null
    }

    private fun AniZipMappings.hasSyncIds(): Boolean {
        return malId != null || anilistId != null || kitsuId != null
    }

    private fun buildPlayerSourceOptions(playbackData: EpisodePlaybackData): List<PlayerSourceOption> {
        val orderedSources = mutableListOf<PlayerSourceOption>()
        val seenUrls = linkedSetOf<String>()

        fun addSource(url: String?, label: String) {
            val normalizedUrl = url?.takeIf(String::isNotBlank) ?: return
            if (seenUrls.add(normalizedUrl)) {
                orderedSources += PlayerSourceOption(label = label, url = normalizedUrl)
            }
        }

        when (playbackData.preferredUrl) {
            playbackData.dubUrl -> {
                addSource(playbackData.dubUrl, "[DUB]")
                addSource(playbackData.subUrl, "[SUB]")
            }
            playbackData.subUrl -> {
                addSource(playbackData.subUrl, "[SUB]")
                addSource(playbackData.dubUrl, "[DUB]")
            }
            else -> {
                addSource(playbackData.preferredUrl, "[SOURCE]")
                addSource(playbackData.dubUrl, "[DUB]")
                addSource(playbackData.subUrl, "[SUB]")
            }
        }

        return orderedSources
    }

    private suspend fun getPlayerEmbedUrl(playerUrl: String): String? {
        val cacheKey = playerEmbedCacheKey(playerUrl)
        playerEmbedUrlCache.get(cacheKey)?.let { return it }
        readDiskTextCache(cacheKey)?.let { cachedEmbedUrl ->
            playerEmbedUrlCache.put(cacheKey, cachedEmbedUrl)
            return cachedEmbedUrl
        }

        val embedUrl = runCatchingCancellable {
            app.get(playerUrl).document
                .select("video-player")
                .attr("embed_url")
                .takeIf(String::isNotBlank)
        }.getOrNull()

        if (embedUrl != null) {
            playerEmbedUrlCache.put(cacheKey, embedUrl)
            writeDiskTextCache(
                key = cacheKey,
                namespace = AnimeUnityCache.NAMESPACE_EXTERNAL,
                payload = embedUrl,
                ttlMs = PLAYER_EMBED_CACHE_TTL_MS,
                priority = CACHE_PRIORITY_STANDARD,
            )
        }

        return embedUrl
    }

    private fun buildEpisodeSourceMap(anime: Anime?, episodes: List<Episode>): LinkedHashMap<String, EpisodeSource> {
        val sourceAnime = anime ?: return linkedMapOf()
        val sourceMap = linkedMapOf<String, EpisodeSource>()

        episodes.forEach { episode ->
            val rawNumber = episode.number.trim()
            if (!sourceMap.containsKey(rawNumber)) {
                sourceMap[rawNumber] = EpisodeSource(
                    number = rawNumber,
                    url = getEpisodeUrl(sourceAnime, episode),
                )
            }
        }

        return sourceMap
    }

    private fun buildEpisodes(
        episodes: LinkedHashMap<String, EpisodeSource>,
        subEpisodes: LinkedHashMap<String, EpisodeSource>,
        dubEpisodes: LinkedHashMap<String, EpisodeSource>,
        episodeMetadataIndex: EpisodeMetadataIndex,
        episodeFallbackPosterUrl: String?,
    ): List<com.lagradost.cloudstream3.Episode> {
        return episodes.keys
            .sortedWith(
                compareBy<String>(
                    { parseEpisodeSortValue(it) ?: Double.POSITIVE_INFINITY },
                    { it }
                )
            )
            .mapNotNull { episodeNumber ->
                val source = episodes[episodeNumber] ?: return@mapNotNull null
                val metadata = getAniZipEpisode(episodeMetadataIndex, source.number)
                val episodeName = getAniZipEpisodeTitle(metadata) ?: buildEpisodeDisplayName(source.number)
                val playbackData = EpisodePlaybackData(
                    preferredUrl = source.url,
                    subUrl = subEpisodes[episodeNumber]?.url,
                    dubUrl = dubEpisodes[episodeNumber]?.url,
                )
                newEpisode(playbackData) {
                    this.episode = source.number.toIntOrNull()
                    this.name = episodeName
                    metadata?.rating
                        ?.takeIf(String::isNotBlank)
                        ?.let { this.score = Score.from(it, 10) }
                    this.posterUrl = metadata?.image?.takeIf(String::isNotBlank) ?: episodeFallbackPosterUrl
                    this.description = getAniZipEpisodeDescription(metadata)
                    getAniZipEpisodeAirDate(metadata)?.let { this.addDate(it) }
                    this.runTime = metadata?.runtime
                }
            }
    }

    private suspend fun parseAnimePageData(animePage: org.jsoup.nodes.Document): AnimePageData {
        val relatedAnimeJsonArray = animePage.select("layout-items").attr("items-json")
        val relatedAnime = relatedAnimeJsonArray
            .takeIf(String::isNotBlank)
            ?.let { runCatching { parseJson<List<Anime>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

        val videoPlayer = animePage.select("video-player")
        val anime = parseJson<Anime>(videoPlayer.attr("anime"))
        val initialEpisodes = parseJson<List<Episode>>(videoPlayer.attr("episodes"))
        val totalEpisodes = videoPlayer.attr("episodes_count").toIntOrNull() ?: initialEpisodes.size

        return AnimePageData(
            anime = anime,
            relatedAnime = relatedAnime,
            episodes = getAllEpisodes(anime, initialEpisodes, totalEpisodes),
        )
    }

    private suspend fun fetchAnimePageData(url: String, forceRefresh: Boolean = false): AnimePageData {
        val cacheKey = animePageDataCacheKey(url)
        if (!forceRefresh) {
            animePageDataCache.get(cacheKey)?.let { return it }
            readDiskCache<AnimePageData>(cacheKey)?.let { cachedPageData ->
                animePageDataCache.put(cacheKey, cachedPageData)
                return cachedPageData
            }
        }

        val animePage = app.get(url).document
        val pageData = parseAnimePageData(animePage)
        animePageDataCache.put(cacheKey, pageData)
        writeDiskCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_ANIME_PAGE,
            payload = pageData,
            ttlMs = getAnimeDetailTtlMs(pageData.anime),
            priority = CACHE_PRIORITY_IMPORTANT,
            pinned = true,
        )
        return pageData
    }

    private suspend fun getAllEpisodes(
        anime: Anime,
        initialEpisodes: List<Episode>,
        totalEpisodes: Int,
    ): List<Episode> {
        val episodes = initialEpisodes.toMutableList()
        val isEpisodeNumberMultipleOfRange = totalEpisodes % 120 == 0
        val range = if (isEpisodeNumberMultipleOfRange) totalEpisodes / 120 else (totalEpisodes / 120) + 1

        if (totalEpisodes > 120) {
            for (i in 2..range) {
                val endRange = if (i == range) totalEpisodes else i * 120
                val infoUrl = "$mainUrl/info_api/${anime.id}/1?start_range=${1 + (i - 1) * 120}&end_range=${endRange}"
                val info = app.get(infoUrl).text
                val animeInfo = parseJson<AnimeInfo>(info)
                episodes.addAll(animeInfo.episodes)
            }
        }

        return episodes
    }

    private suspend fun findAnimeVariants(currentAnime: Anime): List<Anime> {
        val searchTitle = withoutDubSuffix(getAnimeTitle(currentAnime))
        val responseObject = fetchArchiveBatch(
            "$mainUrl/archivio/get-animes",
            RequestData(title = searchTitle, dubbed = 0)
        )

        return (responseObject.titles.orEmpty() + currentAnime)
            .filter { it.contentKey() == currentAnime.contentKey() }
            .distinctBy(Anime::id)
    }

    private suspend fun getAnilistPoster(anilistId: Int): String {
        anilistPosterCache.get(anilistId)?.let { return it }
        val cacheKey = listOf(CACHE_SCHEMA, "anilist-poster", anilistId).joinToString("|")
        readDiskTextCache(cacheKey)?.let { cachedPoster ->
            anilistPosterCache.put(anilistId, cachedPoster)
            return cachedPoster
        }

        val query = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                coverImage {
                    large
                    medium
                }
            }
        }
    """.trimIndent()

        val body = mapOf("query" to query, "variables" to """{"id":$anilistId}""")
        val response = app.post("https://graphql.anilist.co", data = body)
        val anilistObj = parseJson<AnilistResponse>(response.text)

        val poster = anilistObj.data.media.coverImage?.let { coverImage ->
            coverImage.extraLarge ?: coverImage.large ?: coverImage.medium
        } ?: throw IllegalStateException("No valid image found")

        anilistPosterCache.put(anilistId, poster)
        writeDiskTextCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_EXTERNAL,
            payload = poster,
            ttlMs = EXTERNAL_CACHE_TTL_MS,
            priority = CACHE_PRIORITY_STANDARD,
        )
        return poster
    }

    private suspend fun getTrailerUrl(anime: Anime): String? {
        val cacheKey = trailerUrlCacheKey(anime)
        trailerUrlCache.get(cacheKey)?.let { return it }
        readDiskTextCache(cacheKey)?.let { cachedTrailer ->
            trailerUrlCache.put(cacheKey, cachedTrailer)
            return cachedTrailer
        }

        val trailerUrl = getAniListTrailer(anime) ?: getJikanTrailer(anime)
        if (trailerUrl != null) {
            trailerUrlCache.put(cacheKey, trailerUrl)
            writeDiskTextCache(
                key = cacheKey,
                namespace = AnimeUnityCache.NAMESPACE_EXTERNAL,
                payload = trailerUrl,
                ttlMs = EXTERNAL_CACHE_TTL_MS,
                priority = CACHE_PRIORITY_STANDARD,
            )
        }
        return trailerUrl
    }

    private suspend fun getAniListTrailer(anime: Anime): String? {
        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title

        val (query, variables) = when {
            anime.anilistId != null -> {
                """
                query (${'$'}id: Int) {
                    Media(id: ${'$'}id, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"id":${anime.anilistId}}"""
            }

            anime.malId != null -> {
                """
                query (${'$'}idMal: Int) {
                    Media(idMal: ${'$'}idMal, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"idMal":${anime.malId}}"""
            }

            !searchTitle.isNullOrBlank() -> {
                """
                query (${'$'}search: String) {
                    Media(search: ${'$'}search, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"search":${org.json.JSONObject.quote(searchTitle)}}"""
            }

            else -> return null
        }

        val body = mapOf("query" to query, "variables" to variables)
        val response = runCatchingCancellable { app.post("https://graphql.anilist.co", data = body).text }.getOrNull()
            ?: return null
        val media = runCatching { parseJson<AnilistResponse>(response).data.media }.getOrNull()
            ?: return null

        return normalizeAniListTrailerUrl(media.trailer)
    }

    private fun normalizeAniListTrailerUrl(trailer: AnilistTrailer?): String? {
        if (trailer?.site?.equals("youtube", ignoreCase = true) == true && !trailer.id.isNullOrBlank()) {
            return "https://www.youtube.com/watch?v=${trailer.id}"
        }

        return null
    }

    private fun normalizeTrailerUrl(trailer: JikanTrailer?): String? {
        val directUrl = trailer?.url?.takeIf(String::isNotBlank)
        if (directUrl != null) return directUrl

        val youtubeId = trailer?.youtubeId?.takeIf(String::isNotBlank)
            ?: trailer?.embedUrl
                ?.substringAfter("/embed/", "")
                ?.substringBefore("?")
                ?.substringBefore("/")
                ?.takeIf(String::isNotBlank)

        if (youtubeId != null) {
            return "https://www.youtube.com/watch?v=$youtubeId"
        }

        return trailer?.embedUrl?.takeIf(String::isNotBlank)
    }

    private fun normalizeJikanTitle(title: String): String {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
    }

    private suspend fun getJikanTrailer(anime: Anime): String? {
        anime.malId?.let { malId ->
            val url = "https://api.jikan.moe/v4/anime/$malId/full"
            val response = runCatchingCancellable { app.get(url).text }.getOrNull() ?: return null
            val trailer = runCatching { parseJson<JikanFullResponse>(response).data.trailer }.getOrNull()
            return normalizeTrailerUrl(trailer)
        }

        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title ?: return null
        val searchUrl = "https://api.jikan.moe/v4/anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchTitle)
            .addQueryParameter("limit", "5")
            .build()
            .toString()

        val response = runCatchingCancellable { app.get(searchUrl).text }.getOrNull() ?: return null
        val candidates = runCatching { parseJson<JikanSearchResponse>(response).data }.getOrNull().orEmpty()
        if (candidates.isEmpty()) return null

        val searchTitles = listOfNotNull(anime.titleIt, anime.titleEng, anime.title)
            .map { normalizeJikanTitle(it) }
            .filter { it.isNotBlank() }

        return candidates.firstNotNullOfOrNull { candidate ->
            val trailerUrl = normalizeTrailerUrl(candidate.trailer) ?: return@firstNotNullOfOrNull null
            val candidateTitles = buildList {
                add(candidate.title)
                candidate.titleEnglish?.let(::add)
                candidate.titleJapanese?.let(::add)
                addAll(candidate.titleSynonyms.orEmpty())
            }.map(::normalizeJikanTitle)

            trailerUrl.takeIf {
                searchTitles.isEmpty() || searchTitles.any(candidateTitles::contains)
            }
        } ?: candidates.firstNotNullOfOrNull { normalizeTrailerUrl(it.trailer) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionData = decodeMainPageSectionData(request.data)
        val cacheKey = homeCacheKey(sectionData, page, request.name)

        return when (sectionData.key) {
            AnimeUnitySections.LATEST -> getLatestEpisodesMainPage(page, request.name, cacheKey)
            AnimeUnitySections.CALENDAR -> getCalendarMainPage(page, request.name, sectionData.baseUrl, cacheKey)
            AnimeUnitySections.RANDOM -> getRandomMainPage(page, request.name, sectionData.baseUrl)
            else -> getArchiveMainPage(page, request.name, sectionData, cacheKey)
        }
    }

    private fun buildHomePageResponse(
        sectionTitle: String,
        responses: List<SearchResponse>,
        hasNextPage: Boolean = false,
    ): HomePageResponse {
        return newHomePageResponse(
            HomePageList(
                name = sectionTitle,
                list = responses,
                isHorizontalImages = false,
            ),
            hasNextPage,
        )
    }

    private fun emptyHomePageResponse(sectionTitle: String): HomePageResponse {
        return buildHomePageResponse(sectionTitle, emptyList())
    }

    private suspend fun getArchiveMainPage(
        page: Int,
        sectionTitle: String,
        sectionData: MainPageSectionData,
        cacheKey: String,
    ): HomePageResponse {
        if (page > 1) {
            return emptyHomePageResponse(sectionTitle)
        }

        val sectionCount = getSectionCount(sectionData.key)
        val allowStaleCache = shouldUseStaleWhileRevalidate(sectionData.key)
        val cachedArchivePage = readDiskCache<ArchivePageResult>(cacheKey, allowExpired = allowStaleCache)
        if (cachedArchivePage != null) {
            if (allowStaleCache) {
                revalidateArchiveMainPage(cacheKey, sectionData, page)
            }
            return buildArchiveHomePageResponse(sectionTitle, cachedArchivePage, sectionCount)
        }

        val archivePage = fetchArchiveMainPageData(sectionData, page, sectionCount)
        writeDiskCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_HOME,
            payload = archivePage,
            ttlMs = getHomeCacheTtlMs(sectionData.key),
            priority = getHomeCachePriority(sectionData.key),
        )
        return buildArchiveHomePageResponse(sectionTitle, archivePage, sectionCount)
    }

    private suspend fun buildArchiveHomePageResponse(
        sectionTitle: String,
        archivePage: ArchivePageResult,
        sectionCount: Int,
    ): HomePageResponse {
        val responses = if (archivePage.titles.isNotEmpty()) {
            searchResponseBuilder(archivePage.titles, limit = sectionCount)
        } else {
            emptyList()
        }
        return buildHomePageResponse(sectionTitle, responses)
    }

    private suspend fun fetchArchiveMainPageData(
        sectionData: MainPageSectionData,
        page: Int,
        sectionCount: Int = getSectionCount(sectionData.key),
    ): ArchivePageResult {
        val url = sectionData.baseUrl + "get-animes"
        ensureHeadersAndCookies()

        val requestData = getDataPerHomeSection(sectionData.key)
        return fetchArchiveSectionPage(url, requestData, page, sectionCount)
    }

    private fun revalidateArchiveMainPage(
        cacheKey: String,
        sectionData: MainPageSectionData,
        page: Int,
    ) {
        val cachedFirstIdentity = readDiskCache<ArchivePageResult>(
            cacheKey,
            allowExpired = true,
        )?.firstIdentity()

        revalidateInBackground(cacheKey) {
            val freshArchivePage = fetchArchiveMainPageData(sectionData, page)
            if (freshArchivePage.firstIdentity() != cachedFirstIdentity) {
                writeDiskCache(
                    key = cacheKey,
                    namespace = AnimeUnityCache.NAMESPACE_HOME,
                    payload = freshArchivePage,
                    ttlMs = getHomeCacheTtlMs(sectionData.key),
                    priority = getHomeCachePriority(sectionData.key),
                )
            }
        }
    }

    private suspend fun getLatestEpisodesMainPage(
        page: Int,
        sectionTitle: String,
        cacheKey: String,
    ): HomePageResponse {
        val sectionCount = getSectionCount(AnimeUnitySections.LATEST)
        if (page > 1) {
            return emptyHomePageResponse(sectionTitle)
        }

        val cachedLatestEpisodes = readDiskCache<List<LatestEpisodeItem>>(
            cacheKey,
            allowExpired = true,
        )
        if (cachedLatestEpisodes != null) {
            revalidateLatestEpisodes(cacheKey)
            return buildLatestEpisodesHomePageResponse(sectionTitle, cachedLatestEpisodes, sectionCount)
        }

        val latestEpisodes = fetchLatestEpisodes()
        writeDiskCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_HOME,
            payload = latestEpisodes,
            ttlMs = LATEST_CACHE_TTL_MS,
            priority = getHomeCachePriority(AnimeUnitySections.LATEST),
        )
        return buildLatestEpisodesHomePageResponse(sectionTitle, latestEpisodes, sectionCount)
    }

    private suspend fun buildLatestEpisodesHomePageResponse(
        sectionTitle: String,
        latestEpisodes: List<LatestEpisodeItem>,
        sectionCount: Int,
    ): HomePageResponse {
        return buildHomePageResponse(
            sectionTitle,
            latestEpisodesResponseBuilder(latestEpisodes, sectionCount),
        )
    }

    private suspend fun fetchLatestEpisodes(): List<LatestEpisodeItem> {
        val latestEpisodesJson = app.get("$mainUrl/?page=1").document
            .selectFirst("#ultimi-episodi layout-items")
            ?.attr("items-json")
            .orEmpty()

        return latestEpisodesJson
            .takeIf(String::isNotBlank)
            ?.let { json ->
                runCatching { parseJson<LatestEpisodesPage>(json).episodes }.getOrDefault(emptyList())
            }
            ?: emptyList()
    }

    private fun revalidateLatestEpisodes(cacheKey: String) {
        val cachedFirstIdentity = readDiskCache<List<LatestEpisodeItem>>(
            cacheKey,
            allowExpired = true,
        )?.firstIdentity()

        revalidateInBackground(cacheKey) {
            val freshEpisodes = fetchLatestEpisodes()
            if (freshEpisodes.firstIdentity() != cachedFirstIdentity) {
                writeDiskCache(
                    key = cacheKey,
                    namespace = AnimeUnityCache.NAMESPACE_HOME,
                    payload = freshEpisodes,
                    ttlMs = LATEST_CACHE_TTL_MS,
                    priority = getHomeCachePriority(AnimeUnitySections.LATEST),
                )
            }
        }
    }

    private suspend fun getCalendarMainPage(
        page: Int,
        sectionTitle: String,
        requestUrl: String,
        cacheKey: String,
    ): HomePageResponse {
        val currentDay = getCurrentItalianDayName()
        val calendarTitle = "$sectionTitle ($currentDay)"
        val sectionCount = getSectionCount(AnimeUnitySections.CALENDAR)

        if (page > 1) {
            return emptyHomePageResponse(calendarTitle)
        }

        val cachedCalendarAnime = readDiskCache<List<CalendarAnimeItem>>(cacheKey)
        if (cachedCalendarAnime != null) {
            return buildCalendarHomePageResponse(calendarTitle, cachedCalendarAnime, sectionCount)
        }

        val calendarAnime = fetchCalendarAnime(requestUrl, currentDay)
        writeDiskCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_HOME,
            payload = calendarAnime,
            ttlMs = DAY_MS,
            expiresAtMs = getHomeCacheExpirationMs(AnimeUnitySections.CALENDAR),
            priority = getHomeCachePriority(AnimeUnitySections.CALENDAR),
        )

        return buildCalendarHomePageResponse(calendarTitle, calendarAnime, sectionCount)
    }

    private suspend fun fetchCalendarAnime(
        requestUrl: String,
        currentDay: String,
    ): List<CalendarAnimeItem> {
        return app.get(requestUrl).document
            .select("calendario-item")
            .mapNotNull { item ->
                val animeJson = item.attr("a")
                if (animeJson.isBlank()) return@mapNotNull null

                val anime = runCatching { parseJson<Anime>(animeJson) }.getOrNull() ?: return@mapNotNull null
                val episodeNumber = extractCalendarEpisodeNumber(item, anime)

                if (normalizeDayName(anime.day) == normalizeDayName(currentDay)) {
                    CalendarAnimeItem(anime, episodeNumber)
                } else {
                    null
                }
            }
    }

    private suspend fun buildCalendarHomePageResponse(
        calendarTitle: String,
        calendarAnime: List<CalendarAnimeItem>,
        sectionCount: Int,
    ): HomePageResponse {
        return buildHomePageResponse(
            calendarTitle,
            calendarResponseBuilder(calendarAnime, sectionCount),
        )
    }

    private fun extractCalendarEpisodeNumber(item: Element, anime: Anime): Int? {
        item.attr("episodes_count")
            .trim()
            .toIntOrNull()
            ?.let { releasedEpisodes ->
                return releasedEpisodes + 1
            }

        return anime.episodes
            ?.mapNotNull { it.number.toIntOrNull() }
            ?.maxOrNull()
            ?.plus(1)
    }

    private suspend fun getRandomMainPage(
        page: Int,
        sectionTitle: String,
        requestUrl: String,
    ): HomePageResponse {
        if (page > 1) {
            return emptyHomePageResponse(sectionTitle)
        }

        val url = "${requestUrl}get-animes"
        ensureHeadersAndCookies()

        val sessionKey = "$mainUrl|$requestUrl|${displayCacheKey()}"
        val seenIds = synchronized(randomSessionSeenIds) {
            randomSessionSeenIds.getOrPut(sessionKey) { mutableSetOf() }
        }
        val sectionCount = getSectionCount(AnimeUnitySections.RANDOM)
        val (titles, total) = fetchRandomTitles(url, sectionCount, seenIds)
        synchronized(randomSessionSeenIds) {
            val updatedSeenIds = randomSessionSeenIds.getOrPut(sessionKey) { mutableSetOf() }
            titles.forEach { updatedSeenIds += it.id }
            if (total > 0 && updatedSeenIds.size >= total) {
                updatedSeenIds.clear()
                titles.forEach { updatedSeenIds += it.id }
            }
        }

        return buildHomePageResponse(
            sectionTitle,
            searchResponseBuilder(titles, limit = sectionCount),
        )
    }

    private fun getCurrentItalianDayName(): String {
        val formatter = SimpleDateFormat("EEEE", Locale.ITALIAN)
        return formatter.format(Date()).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString()
        }
    }

    private fun normalizeDayName(dayName: String?): String {
        return Normalizer.normalize(dayName.orEmpty(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun getDataPerHomeSection(section: String) = when (section) {
        AnimeUnitySections.ADVANCED -> AnimeUnityPlugin.getAdvancedSearchRequestData(sharedPref)
        AnimeUnitySections.POPULAR -> RequestData(orderBy = Str("Popolarità"), dubbed = 0)
        AnimeUnitySections.UPCOMING -> RequestData(status = Str("In Uscita"), dubbed = 0)
        AnimeUnitySections.BEST -> RequestData(orderBy = Str("Valutazione"), dubbed = 0)
        AnimeUnitySections.ONGOING -> RequestData(orderBy = Str("Popolarità"), status = Str("In Corso"), dubbed = 0)
        else -> RequestData()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/archivio/get-animes"
        ensureHeadersAndCookies(forceReset = true)

        val requestBody = RequestData(title = query, dubbed = 0).toRequestBody()
        val response = app.post(url, headers = headers, requestBody = requestBody)

        val responseObject = parseJson<ApiResponse>(response.text)
        val titles = responseObject.titles ?: emptyList()

        return searchResponseBuilder(titles)
    }

    override suspend fun load(url: String): LoadResponse {
        val cacheKeys = loadCacheKeys(url)
        val cacheKey = cacheKeys.first()
        animeLoadDataCache.get(cacheKey)?.let { cachedLoadData ->
            return buildAnimeLoadResponse(cachedLoadData)
        }

        val cachedData = cacheKeys.firstNotNullOfOrNull { candidateKey ->
            readDiskCacheRecord<AnimeLoadCacheData>(
                candidateKey,
                allowExpired = true,
            )?.let { candidateKey to it }
        }
        if (cachedData != null) {
            val (resolvedCacheKey, cachedRecord) = cachedData
            val (loadData, record) = cachedRecord
            val isCompleted = getShowStatus(loadData.primaryAnime().status) == ShowStatus.Completed
            if (!record.isExpired || !isCompleted) {
                animeLoadDataCache.put(cacheKey, loadData)
                if (resolvedCacheKey != cacheKey) {
                    writeAnimeLoadCacheData(cacheKey, loadData)
                }
                if (!isCompleted) {
                    revalidateAnimeLoad(cacheKey, url, loadData)
                }
                return buildAnimeLoadResponse(loadData)
            }
        }

        val loadData = fetchAnimeLoadCacheData(url, forceRefresh = false)
        writeAnimeLoadCacheData(cacheKey, loadData)
        return buildAnimeLoadResponse(loadData)
    }

    private suspend fun fetchAnimeLoadCacheData(
        url: String,
        forceRefresh: Boolean,
    ): AnimeLoadCacheData {
        ensureHeadersAndCookies(forceReset = forceRefresh)
        val currentPageData = fetchAnimePageData(url, forceRefresh = forceRefresh)
        val currentAnime = currentPageData.anime
        val shouldMergeVariants = shouldUseUnifiedDubSubCards()
        val variants = if (shouldMergeVariants) findAnimeVariants(currentAnime) else listOf(currentAnime)

        val subAnime = variants.firstOrNull { !isDubAnime(it) } ?: currentAnime.takeIf { !isDubAnime(it) }
        val dubAnime = variants.firstOrNull { isDubAnime(it) } ?: currentAnime.takeIf { isDubAnime(it) }

        val (subPageData, dubPageData) = coroutineScope {
            val subPageDeferred = async(Dispatchers.IO) {
                when {
                    subAnime == null -> null
                    subAnime.id == currentAnime.id -> currentPageData
                    !shouldMergeVariants -> null
                    else -> fetchAnimePageData(getAnimeUrl(subAnime), forceRefresh = forceRefresh)
                }
            }
            val dubPageDeferred = async(Dispatchers.IO) {
                when {
                    dubAnime == null -> null
                    dubAnime.id == currentAnime.id -> currentPageData
                    !shouldMergeVariants -> null
                    else -> fetchAnimePageData(getAnimeUrl(dubAnime), forceRefresh = forceRefresh)
                }
            }

            subPageDeferred.await() to dubPageDeferred.await()
        }

        val primaryAnime = subPageData?.anime ?: dubPageData?.anime ?: currentAnime
        val primaryMalId = primaryAnime.malId ?: variants.firstNotNullOfOrNull { it.malId }
        val primaryAniListId = primaryAnime.anilistId ?: variants.firstNotNullOfOrNull { it.anilistId }
        val (episodeMetadata, trailerUrl) = coroutineScope {
            val episodeMetadataDeferred = async(Dispatchers.IO) {
                fetchAniZipMetadata(primaryMalId, primaryAniListId)
            }
            val trailerUrlDeferred = async(Dispatchers.IO) {
                getTrailerUrl(primaryAnime)
            }

            episodeMetadataDeferred.await() to trailerUrlDeferred.await()
        }

        return AnimeLoadCacheData(
            currentPageData = currentPageData,
            variants = variants,
            subPageData = subPageData,
            dubPageData = dubPageData,
            episodeMetadata = episodeMetadata,
            trailerUrl = trailerUrl,
            fingerprint = buildAnimeLoadFingerprint(
                primaryAnime = primaryAnime,
                subPageData = subPageData,
                dubPageData = dubPageData,
                episodeMetadata = episodeMetadata,
            ),
        )
    }

    private fun writeAnimeLoadCacheData(cacheKey: String, loadData: AnimeLoadCacheData) {
        val primaryAnime = loadData.primaryAnime()
        val isCompleted = getShowStatus(primaryAnime.status) == ShowStatus.Completed
        animeLoadDataCache.put(cacheKey, loadData)
        writeDiskCache(
            key = cacheKey,
            namespace = AnimeUnityCache.NAMESPACE_DETAIL,
            payload = loadData,
            ttlMs = getAnimeDetailTtlMs(primaryAnime),
            priority = if (isCompleted) CACHE_PRIORITY_IMPORTANT else CACHE_PRIORITY_DETAIL,
            pinned = true,
        )
    }

    private fun revalidateAnimeLoad(
        cacheKey: String,
        url: String,
        cachedData: AnimeLoadCacheData,
    ) {
        revalidateInBackground(cacheKey) {
            val freshData = fetchAnimeLoadCacheData(url, forceRefresh = true)
            if (
                freshData.totalEpisodeCount() != cachedData.totalEpisodeCount() ||
                freshData.fingerprint != cachedData.fingerprint
            ) {
                writeAnimeLoadCacheData(cacheKey, freshData)
            }
        }
    }

    private suspend fun buildAnimeLoadResponse(loadData: AnimeLoadCacheData): LoadResponse {
        val currentPageData = loadData.currentPageData
        val variants = loadData.variants
        val subPageData = loadData.subPageData
        val dubPageData = loadData.dubPageData
        val primaryAnime = loadData.primaryAnime()
        val primaryMalId = primaryAnime.malId ?: variants.firstNotNullOfOrNull { it.malId }
        val primaryAniListId = primaryAnime.anilistId ?: variants.firstNotNullOfOrNull { it.anilistId }
        val title = getAnimeTitle(primaryAnime)
        val animePoster = getImage(primaryAnime.imageUrl, primaryAniListId)
        val episodeFallbackPosterUrl = primaryAnime.cover?.let(::getBanner) ?: animePoster
        val episodeMetadata = loadData.episodeMetadata
        val episodeMetadataIndex = buildEpisodeMetadataIndex(episodeMetadata)
        val syncMalId = primaryMalId ?: episodeMetadata?.mappings?.malId
        val syncAniListId = primaryAniListId ?: episodeMetadata?.mappings?.anilistId
        val syncKitsuId = episodeMetadata?.mappings?.kitsuId
        val relatedAnimes = groupAnimeCards(currentPageData.relatedAnime).safeAmap(concurrency = 6) { entry ->
            val anime = entry.anime
            val relatedTitle = getAnimeTitle(anime)
            val poster = runCatchingCancellable { getImage(anime.imageUrl, anime.anilistId) }.getOrNull()
            newAnimeSearchResponse(
                name = withoutDubSuffix(relatedTitle),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = if (anime.type == "TV") TvType.Anime
                else if (anime.type == "Movie" || anime.episodesCount == 1) TvType.AnimeMovie
                else TvType.OVA
            ) {
                applyCardDisplayState(
                    response = this,
                    badges = entry.badges,
                    poster = poster,
                    score = null,
                )
            }
        }

        val subEpisodeMap = buildEpisodeSourceMap(subPageData?.anime, subPageData?.episodes.orEmpty())
        val dubEpisodeMap = buildEpisodeSourceMap(dubPageData?.anime, dubPageData?.episodes.orEmpty())
        val subEpisodes = buildEpisodes(
            episodes = subEpisodeMap,
            subEpisodes = subEpisodeMap,
            dubEpisodes = dubEpisodeMap,
            episodeMetadataIndex = episodeMetadataIndex,
            episodeFallbackPosterUrl = episodeFallbackPosterUrl,
        )
        val dubEpisodes = buildEpisodes(
            episodes = dubEpisodeMap,
            subEpisodes = subEpisodeMap,
            dubEpisodes = dubEpisodeMap,
            episodeMetadataIndex = episodeMetadataIndex,
            episodeFallbackPosterUrl = episodeFallbackPosterUrl,
        )
        val hasSub = subEpisodeMap.isNotEmpty()
        val hasDub = dubEpisodeMap.isNotEmpty()
        val trailerUrl = loadData.trailerUrl

        return newAnimeLoadResponse(
            name = title.replace(" (ITA)", ""),
            url = getAnimeUrl(primaryAnime),
            type = if (primaryAnime.type == "TV") TvType.Anime
            else if (primaryAnime.type == "Movie" || primaryAnime.episodesCount == 1) TvType.AnimeMovie
            else TvType.OVA,
        ) {
            this.posterUrl = animePoster
            primaryAnime.cover?.let { this.backgroundPosterUrl = getBanner(it) }
            this.year = primaryAnime.date.toIntOrNull()
            addScore(primaryAnime.score)
            addDuration(primaryAnime.episodesLength.toString() + " minuti")
            when {
                hasSub && hasDub -> {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
                hasDub -> {
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
                hasSub -> {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                }
            }
            addAniListId(syncAniListId)
            addMalId(syncMalId)
            addKitsuId(syncKitsuId)
            if (trailerUrl != null) {
                addTrailer(trailerUrl)
            }
            this.showStatus = getShowStatus(primaryAnime.status)
            this.plot = primaryAnime.plot
            val audioTag = when {
                hasSub && hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano / \uD83C\uDDEF\uD83C\uDDF5  Giapponese"
                hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano"
                else -> "\uD83C\uDDEF\uD83C\uDDF5  Giapponese"
            }
            this.tags = listOfNotNull(audioTag, getStatusTag(primaryAnime.status)) + primaryAnime.genres.map { genre ->
                genre.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            this.comingSoon = primaryAnime.status == "In uscita prossimamente"
            this.recommendations = relatedAnimes
        }
    }

    private fun getBanner(imageUrl: String): String {
        if (imageUrl.isNotEmpty()) {
            val fileName = imageUrl.substringAfterLast("/")
            if (fileName.isNotBlank()) {
                return "https://${getImageCdnHost()}/anime/$fileName"
            }
        }
        return imageUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playbackData = runCatching { parseJson<EpisodePlaybackData>(data) }.getOrNull()
        val playerSources = playbackData?.let(::buildPlayerSourceOptions)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(PlayerSourceOption(label = "[SOURCE]", url = data))

        val shouldLabelSources = playerSources.size > 1

        val sourceTasks = playerSources.map { playerSource ->
            suspend {
                val sourceUrl = getPlayerEmbedUrl(playerSource.url)
                if (!sourceUrl.isNullOrBlank()) {
                    val sourceSuffix = if (shouldLabelSources) " ${playerSource.label}" else ""
                    VixCloudExtractor(
                        sourceName = "VixCloud$sourceSuffix",
                        displayName = "AnimeUnity$sourceSuffix",
                    ).getUrl(
                        url = sourceUrl,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }
        }
        runLimitedAsync(concurrency = 2, *sourceTasks.toTypedArray())

        return true
    }
}
