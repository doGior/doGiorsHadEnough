package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import it.dogior.hadEnough.anime.source.ANIME_UNITY_SESSION_PREF
import it.dogior.hadEnough.serie_movie.STREAMING_COMMUNITY_SESSION_PREF
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal object StreamCenterHomeReuse {
    private const val WINDOW_MS = 5 * 60_000L
    private val localSections = setOf(REMOTE_QUEUE_SECTION_DATA, "library:catch_up")
    private val runtimeKeys = setOf(ANIME_UNITY_SESSION_PREF, STREAMING_COMMUNITY_SESSION_PREF)

    private data class Key(val api: String, val page: Int, val request: MainPageRequest)

    private class Entry {
        lateinit var response: Deferred<HomePageResponse>

        @Volatile
        var savedAt = 0L

        fun isFresh(now: Long) = savedAt > 0L && now - savedAt < WINDOW_MS
        fun isUsable(now: Long) = !response.isCompleted || isFresh(now)
    }

    private val entries = ConcurrentHashMap<Key, Entry>()
    private val banners = ConcurrentHashMap<String, List<String>>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key !in runtimeKeys) clear()
    }

    fun install(preferences: SharedPreferences?) {
        preferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun load(
        api: StreamCenter,
        page: Int,
        request: MainPageRequest,
        loader: suspend () -> HomePageResponse,
    ): Deferred<HomePageResponse> {
        if (isLocal(request)) return api.backgroundScope.async { loader() }
        val now = System.currentTimeMillis()
        entries.values.removeAll { !it.isUsable(now) }
        val key = Key(api.name, page, request)
        return entries.compute(key) { _, known ->
            known?.takeIf { it.isUsable(now) } ?: start(api, key, loader)
        }!!.response
    }

    private fun start(api: StreamCenter, key: Key, loader: suspend () -> HomePageResponse) = Entry().also { entry ->
        entry.response = api.backgroundScope.async {
            loader().also { response ->
                if (response.items.any { it.list.isNotEmpty() }) entry.savedAt = System.currentTimeMillis()
            }
        }
        entry.response.invokeOnCompletion { if (entry.savedAt == 0L) entries.remove(key, entry) }
    }

    fun isLocal(request: MainPageRequest) = request.data in localSections

    fun isReady(api: StreamCenter, request: MainPageRequest) =
        entries[Key(api.name, 1, request)]?.isFresh(System.currentTimeMillis()) == true

    fun hasFirstPages(api: StreamCenter): Boolean {
        val now = System.currentTimeMillis()
        return entries.any { (key, entry) -> key.api == api.name && key.page == 1 && entry.isFresh(now) }
    }

    fun rememberBanner(items: List<SearchResponse>) {
        val owner = items.firstOrNull()?.apiName ?: return
        banners[owner] = items.map { it.url }
    }

    fun banner(api: StreamCenter): List<String> = banners[api.name].orEmpty()

    private fun clear() {
        entries.clear()
        banners.clear()
    }
}
