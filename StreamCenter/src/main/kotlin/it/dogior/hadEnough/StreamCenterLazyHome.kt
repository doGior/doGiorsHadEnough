package it.dogior.hadEnough

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import it.dogior.hadEnough.catalog.StreamCenterTmdbScores
import it.dogior.hadEnough.util.runCatchingCancellable
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal typealias HomeSectionLoader = suspend (Int, MainPageRequest) -> HomePageResponse

internal class StreamCenterLazyHome(private val api: StreamCenter) {
    private class Session(
        val stamp: Long,
        val held: Map<MainPageRequest, String>,
        parallelSections: Int,
        private val api: StreamCenter,
        private val load: HomeSectionLoader,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val permits = Semaphore(parallelSections)
        private val loads = ConcurrentHashMap<MainPageRequest, Deferred<HomePageResponse>>()

        fun start(request: MainPageRequest): Deferred<HomePageResponse> = loads.computeIfAbsent(request) {
            StreamCenterHomeReuse.load(api, 1, request) { permits.withPermit { load(1, request) } }
        }
    }

    @Volatile
    private var session: Session? = null

    @Volatile
    private var previewRequestedAt = 0L

    private val scoreWatcherStarted = AtomicBoolean(false)

    suspend fun page(page: Int, request: MainPageRequest, load: HomeSectionLoader): HomePageResponse {
        startScoreWatcher()
        val stamp = api.lastHomepageRequest
        val progressive = StreamCenterPlugin.isLazyHomeEnabled(api.sharedPref) || StreamCenterHomeReuse.hasFirstPages(api)
        val viewModel = if (page == 1 && stamp > 0L && progressive) {
            withContext(Dispatchers.Main) { homeViewModel() }
        } else {
            null
        }
        if (viewModel == null) return StreamCenterHomeReuse.load(api, page, request) { load(page, request) }.await()
        val current = session(stamp, load, viewModel)
        val pending = current.start(request)
        current.held[request]?.let { return emptyRow(it, request) }
        val remaining = stamp + StreamCenterPlugin.lazyHomeWaitMs(api.sharedPref) - System.currentTimeMillis()
        if (pending.isCompleted || (remaining > 0 && withTimeoutOrNull(remaining) { pending.join() } != null)) {
            return pending.await()
        }
        val title = rowTitle(request)
        current.scope.launch {
            runCatchingCancellable { pending.await() }.getOrNull()?.let { publish(listOf(title to it)) }
        }
        return emptyRow(title, request)
    }

    private fun rowTitle(request: MainPageRequest) =
        StreamCenterPlugin.resolveHomeTitlePlaceholders(request.name, Calendar.getInstance())

    private fun emptyRow(title: String, request: MainPageRequest) =
        newHomePageResponse(HomePageList(title, emptyList(), request.horizontalImages), hasNext = false)

    @Synchronized
    private fun session(stamp: Long, load: HomeSectionLoader, viewModel: HomeViewModel): Session {
        session?.takeIf { it.stamp == stamp }?.let { return it }
        session?.scope?.cancel()
        previewRequestedAt = 0L
        StreamCenterHomeReuse.rememberBanner(shuffledList(viewModel))
        runCatching { shuffledListField?.set(viewModel, emptyList<SearchResponse>()) }
        val requests = api.mainPage.map { MainPageRequest(it.name, it.data, it.horizontalImages) }
        val held = requests.takeIf { all -> all.any { StreamCenterHomeReuse.isReady(api, it) } }.orEmpty()
            .filter { StreamCenterHomeReuse.isReady(api, it) || StreamCenterHomeReuse.isLocal(it) }
            .associateWith(::rowTitle)
        val parallelSections = StreamCenterPlugin.lazyHomeParallelSections(api.sharedPref)
        return Session(stamp, held, parallelSections, api, load).also { created ->
            session = created
            requests.forEach(created::start)
            if (held.isNotEmpty()) created.scope.launch { restore(created) }
        }
    }

    private suspend fun restore(session: Session) {
        val rows = session.held.mapNotNull { (request, title) ->
            runCatchingCancellable { title to session.start(request).await() }.getOrNull()
        }
        publish(rows, StreamCenterHomeReuse.banner(api))
        withContext(Dispatchers.Main) { applyScores() }
    }

    private fun startScoreWatcher() {
        if (!scoreWatcherStarted.compareAndSet(false, true)) return
        api.backgroundScope.launch {
            StreamCenterTmdbScores.updates.collect {
                withContext(Dispatchers.Main) { applyScores() }
                delay(SCORE_REFRESH_MS)
            }
        }
    }

    private fun applyScores() {
        if (!api.showCardScores) return
        val viewModel = homeViewModel() ?: return
        val rows = (viewModel.page.value as? Resource.Success)?.value ?: return
        var changed = false
        rows.values.forEach { row ->
            val items = row.list.list
            val scored = items.map(::withKnownScore)
            if (scored.indices.any { scored[it] !== items[it] }) {
                row.list = row.list.copy(list = CopyOnWriteArrayList(scored))
                changed = true
            }
        }
        if (changed) (viewModel.page as? MutableLiveData)?.value = Resource.Success(rows)
    }

    private fun withKnownScore(item: SearchResponse): SearchResponse {
        if (item.score != null || item.apiName != api.name) return item
        val score = StreamCenterTmdbScores.cached(item.url) ?: return item
        return when (item) {
            is MovieSearchResponse -> item.copy(score = score)
            is TvSeriesSearchResponse -> item.copy(score = score)
            else -> item
        }
    }

    private suspend fun publish(loaded: List<Pair<String, HomePageResponse>>, bannerOrder: List<String> = emptyList()) {
        repeat(PUBLISH_ATTEMPTS) {
            if (withContext(Dispatchers.Main) { replaceRows(loaded, bannerOrder) }) return
            delay(PUBLISH_RETRY_MS)
        }
    }

    private fun replaceRows(loaded: List<Pair<String, HomePageResponse>>, bannerOrder: List<String>): Boolean {
        val viewModel = homeViewModel() ?: return true
        @Suppress("UNCHECKED_CAST")
        val rows = (viewModel.page.value as? Resource.Success)?.value
            as? MutableMap<String, HomeViewModel.ExpandableHomepageList> ?: return false
        val waiting = loaded.filter { (title) -> rows[title]?.list?.list?.isEmpty() == true }
        if (waiting.isEmpty()) return true
        val items = waiting.flatMap { (title, response) -> placeRow(rows, title, response) }
        (viewModel.page as? MutableLiveData)?.value = Resource.Success(rows)
        extendPreview(viewModel, items, bannerOrder)
        return true
    }

    private fun placeRow(
        rows: MutableMap<String, HomeViewModel.ExpandableHomepageList>,
        title: String,
        response: HomePageResponse,
    ): List<SearchResponse> {
        val context = StreamCenterPlugin.activeContext
        val replacement = response.items.map { list ->
            val shown = context?.filterHomePageListByFilmQuality(list) ?: list
            shown.name to HomeViewModel.ExpandableHomepageList(
                shown.copy(list = CopyOnWriteArrayList(shown.list)),
                1,
                response.hasNext,
            )
        }
        val sameKey = replacement.singleOrNull()?.takeIf { it.first == title }
        if (sameKey != null) {
            rows[title] = sameKey.second
        } else {
            val reordered = rows.entries.flatMap { (key, row) -> if (key == title) replacement else listOf(key to row) }
            rows.clear()
            rows.putAll(reordered)
        }
        return replacement.flatMap { it.second.list.list }
    }

    @Suppress("UNCHECKED_CAST")
    private fun shuffledList(viewModel: HomeViewModel): List<SearchResponse> =
        runCatching { shuffledListField?.get(viewModel) as? List<SearchResponse> }.getOrNull().orEmpty()

    private fun extendPreview(viewModel: HomeViewModel, items: List<SearchResponse>, bannerOrder: List<String>) {
        val field = shuffledListField ?: return
        val current = shuffledList(viewModel)
        val known = current.mapTo(HashSet()) { it.url }
        val rank = bannerOrder.withIndex().associate { (index, url) -> url to index }
        val added = items.filter { known.add(it.url) }.shuffled().sortedBy { rank[it.url] ?: Int.MAX_VALUE }
        if (added.isEmpty()) return
        val extended = current + added
        runCatching { field.set(viewModel, extended) }.getOrElse { return }
        (viewModel.randomItems as? MutableLiveData)?.value = extended
        val shown = (viewModel.preview.value as? Resource.Success)?.value?.second
        val now = System.currentTimeMillis()
        if (shown.isNullOrEmpty() && now - previewRequestedAt > PREVIEW_RETRY_MS) {
            previewRequestedAt = now
            viewModel.loadMoreHomeScrollResponses()
        }
    }

    private fun homeViewModel(): HomeViewModel? {
        val activity = CommonActivity.activity as? FragmentActivity ?: return null
        return runCatching { ViewModelProvider(activity)[HomeViewModel::class.java] }.getOrNull()
            ?.takeIf { it.repo?.api === api }
    }

    private companion object {
        const val PUBLISH_ATTEMPTS = 120
        const val PUBLISH_RETRY_MS = 250L
        const val SCORE_REFRESH_MS = 1_000L
        const val PREVIEW_RETRY_MS = 5_000L
        val shuffledListField by lazy {
            runCatching {
                HomeViewModel::class.java.getDeclaredField("currentShuffledList").apply { isAccessible = true }
            }.getOrNull()
        }
    }
}
