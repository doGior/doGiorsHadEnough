package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import it.dogior.hadEnough.extensions.ExtensionCall
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.mapChunkedParallel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class CatchUpSnapshot(
    val key: String = "",
    val episodes: List<CatchUpEpisode> = emptyList(),
    val fetchedAt: Long = 0L,
)

internal object StreamCenterCatchUp {
    private const val REFRESH_AFTER_MS = 15 * 60_000L
    private const val RETAIN_FOR_MS = 7 * 24 * 60 * 60_000L
    private const val HOME_WAIT_MS = 1_500L
    private data class Candidate(val id: Int, val card: SearchResponse, val updatedAt: Long)
    private val snapshots = object : LinkedHashMap<String, CatchUpSnapshot>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatchUpSnapshot>?): Boolean = size > 128
    }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryAfter = mutableMapOf<String, Long>()
    private var refreshJob: Job? = null
    private var refreshAccount: String? = null
    private var loadedGeneration = -1L

    private fun key(account: String, api: String, id: Int, dub: DubStatus?, url: String) =
        "$account:$api:$id:$dub:$url"

    @Synchronized
    private fun restoreSnapshots() {
        val generation = StreamCenterMediaCache.generation
        if (loadedGeneration == generation) return
        refreshJob?.cancel()
        snapshots.clear()
        retryAfter.clear()
        val now = System.currentTimeMillis()
        StreamCenterMediaCache.readCatchUp()?.let { json ->
            runCatching { parseJson<List<CatchUpSnapshot>>(json) }.getOrNull()
                ?.filter { now - it.fetchedAt in 0 until RETAIN_FOR_MS }
                ?.takeLast(128)?.forEach { snapshots[it.key] = it }
        }
        loadedGeneration = generation
    }

    @Synchronized
    private fun saveSnapshot(key: String, episodes: List<CatchUpEpisode>, generation: Long) {
        if (generation != StreamCenterMediaCache.generation) return
        snapshots[key] = CatchUpSnapshot(key, episodes, System.currentTimeMillis())
        if (StreamCenterMediaCache.isEnabled()) {
            StreamCenterMediaCache.writeCatchUp(snapshots.values.toList().toJson(), generation)
        }
    }

    fun remember(response: LoadResponse) {
        if (response !is TvSeriesLoadResponse && response !is AnimeLoadResponse) return
        restoreSnapshots()
        val generation = StreamCenterMediaCache.generation
        val id = response.getId()
        val dub = DataStoreHelper.getDub(id)
        saveSnapshot(
            key(DataStoreHelper.currentAccount, response.apiName, id, dub, response.url),
            catchUpEpisodes(response, id, dub), generation,
        )
    }

    suspend fun page(page: Int, count: Int): StreamCenterCatalogPage = withContext(Dispatchers.IO) {
        if (page < 1) return@withContext StreamCenterCatalogPage(emptyList(), false)
        val account = DataStoreHelper.currentAccount
        restoreSnapshots()
        val candidates = candidates()
        val offset = (page.toLong() - 1) * count.coerceAtLeast(1)
        val end = offset + count.coerceAtLeast(1)
        fun matchingCards(): List<SearchResponse> {
            val now = System.currentTimeMillis()
            return candidates.mapNotNull { candidate ->
                if (DataStoreHelper.currentAccount != account) return@mapNotNull null
                val state = DataStoreHelper.getResultWatchState(candidate.id)
                if (state == WatchType.DROPPED || state == WatchType.PLANTOWATCH) return@mapNotNull null
                val resume = DataStoreHelper.getLastWatched(candidate.id)
                val cached = synchronized(this@StreamCenterCatchUp) {
                    snapshots[key(account, candidate.card.apiName, candidate.id, DataStoreHelper.getDub(candidate.id), candidate.card.url)]
                }?.takeIf { now - it.fetchedAt in 0 until RETAIN_FOR_MS }
                if (cached == null) {
                    val progress = resume?.episodeId?.let(::progressFor)
                    return@mapNotNull candidate.card.takeIf {
                        resume?.season != 0 && progress != null && progress.position > 0L && !progress.completed
                    }
                }
                val episodes = cached.episodes
                val progress = mutableMapOf<Int, CatchUpProgress>()
                fun progress(episode: CatchUpEpisode) = progress.getOrPut(episode.id) { progressFor(episode.id) }
                val started = state == WatchType.WATCHING || resume != null || episodes.any { episode ->
                    progress(episode).let { it.markedWatched || it.position > 0L }
                }
                candidate.card.takeIf { started && episodes.any { episode ->
                    episode.season != 0 && episode.dateMillis != null && episode.dateMillis <= now && !progress(episode).completed
                } }
            }
        }
        var matches = matchingCards()
        val refresh = refresh(account, candidates)
        if (matches.size.toLong() <= offset && refresh != null) {
            withTimeoutOrNull(HOME_WAIT_MS) { refresh.join() }
            matches = matchingCards()
        }
        if (DataStoreHelper.currentAccount != account) return@withContext StreamCenterCatalogPage(emptyList(), false)
        StreamCenterCatalogPage(
            matches.drop(offset.coerceAtMost(matches.size.toLong()).toInt()).take(count.coerceAtLeast(1)),
            matches.size.toLong() > end,
        )
    }

    private fun progressFor(id: Int): CatchUpProgress {
        val position = DataStoreHelper.getViewPos(id)
        return CatchUpProgress(
            markedWatched = DataStoreHelper.getVideoWatchState(id) == VideoWatchState.Watched,
            position = position?.position ?: 0L,
            duration = position?.duration ?: 0L,
        )
    }

    @Synchronized
    private fun refresh(account: String, candidates: List<Candidate>): Job? {
        if (refreshJob?.isActive == true) {
            if (refreshAccount == account) return refreshJob
            refreshJob?.cancel()
        }
        val now = System.currentTimeMillis()
        retryAfter.entries.removeAll { it.value <= now }
        val pending = candidates.mapNotNull { candidate ->
            val dub = DataStoreHelper.getDub(candidate.id)
            val key = key(account, candidate.card.apiName, candidate.id, dub, candidate.card.url)
            if (snapshots[key]?.let { now - it.fetchedAt < REFRESH_AFTER_MS } == true || key in retryAfter) null
            else Triple(candidate, dub, key)
        }.sortedByDescending { (candidate) ->
            DataStoreHelper.getResultWatchState(candidate.id) == WatchType.WATCHING ||
                DataStoreHelper.getLastWatched(candidate.id) != null
        }
        if (pending.isEmpty()) return null
        val generation = loadedGeneration
        refreshAccount = account
        return backgroundScope.launch {
            withTimeoutOrNull(60_000L) {
                pending.mapChunkedParallel(3) { (candidate, dub, key) ->
                    if (DataStoreHelper.currentAccount != account || StreamCenterMediaCache.generation != generation) {
                        return@mapChunkedParallel null
                    }
                    synchronized(this@StreamCenterCatchUp) { retryAfter[key] = System.currentTimeMillis() + 2 * 60_000L }
                    withTimeoutOrNull(12_000L) {
                        StreamCenterVpnGuard.requireInternetAccess(StreamCenterPlugin.activeSharedPref)
                        val provider = getApiFromNameNull(candidate.card.apiName) ?: return@withTimeoutOrNull null
                        val response = withContext(ExtensionCall) { provider.load(candidate.card.url) }
                            ?: return@withTimeoutOrNull null
                        saveSnapshot(key, catchUpEpisodes(response, candidate.id, dub), generation)
                    }
                }
            }
        }.also { refreshJob = it }
    }

    private suspend fun candidates(): List<Candidate> {
        val library = buildList {
            addAll(DataStoreHelper.getAllBookmarkedData())
            addAll(DataStoreHelper.getAllSubscriptions())
            addAll(DataStoreHelper.getAllFavorites())
        }.mapNotNull { item -> item.id?.let { Candidate(it, item, item.latestUpdatedTime) } }
        val history = DataStoreHelper.getAllResumeStateIds().orEmpty().mapNotNull { id ->
            val resume = DataStoreHelper.getLastWatched(id) ?: return@mapNotNull null
            val header = getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, id.toString())
                ?: getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE_BACKUP, id.toString())
                ?: return@mapNotNull null
            val provider = getApiFromNameNull(header.apiName) ?: return@mapNotNull null
            Candidate(id, provider.newTvSeriesSearchResponse(header.name, header.url, header.type) {
                posterUrl = header.poster
                this.id = id
            }, resume.updateTime)
        }
        return (library + history).sortedByDescending { it.updatedAt }
            .distinctBy { it.card.apiName to it.id }
            .filter { it.card.type in setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon) }
            .filter { DataStoreHelper.getResultWatchState(it.id) !in setOf(WatchType.DROPPED, WatchType.PLANTOWATCH) }
    }
}

internal data class CatchUpEpisode(val id: Int, val season: Int?, val dateMillis: Long?)
internal data class CatchUpProgress(val markedWatched: Boolean, val position: Long, val duration: Long) {
    val completed: Boolean get() = markedWatched || (duration > 0 && position.toDouble() / duration >= 0.95)
}

internal fun catchUpEpisodes(response: LoadResponse, parentId: Int, preferredDub: DubStatus?): List<CatchUpEpisode> {
    fun Episode.snapshot(id: Int) = CatchUpEpisode(id, season, date)
    return when (response) {
        is TvSeriesLoadResponse -> response.episodes.sortedBy {
            (it.season?.times(10_000) ?: 0) + (it.episode ?: 0)
        }.mapIndexed { index, episode ->
            episode.snapshot(parentId + (episode.season?.times(100_000) ?: 0) + (episode.episode ?: index + 1) + 1)
        }
        is AnimeLoadResponse -> {
            val dub = preferredDub?.takeIf { response.episodes[it]?.isNotEmpty() == true }
                ?: DubStatus.Subbed.takeIf { response.episodes[it]?.isNotEmpty() == true }
                ?: response.episodes.entries.firstOrNull { it.value.isNotEmpty() }?.key
            response.episodes[dub].orEmpty().mapIndexed { index, episode ->
                episode.snapshot(parentId + (episode.episode ?: index + 1) + (dub?.id ?: 0) * 1_000_000 +
                    (episode.season?.times(10_000) ?: 0))
            }
        }
        else -> emptyList()
    }.distinctBy { it.id }
}
