package it.dogior.hadEnough.tmdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal data class TmdbPage(val code: Int, val text: String)

internal object StreamCenterTmdbPages {
    private class Entry(val page: Deferred<TmdbPage>, val createdAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > MAX_ENTRIES
    }

    suspend fun page(url: String, fetch: suspend () -> TmdbPage): TmdbPage {
        val entry = synchronized(entries) {
            entries[url]?.takeIf(::isUsable) ?: start(url, fetch)
        }
        val page = entry.page.await()
        if (!isReusable(page)) forget(url, entry)
        return page
    }

    fun prefetch(url: String, fetch: suspend () -> TmdbPage) {
        synchronized(entries) {
            if (entries[url]?.let(::isUsable) != true) start(url, fetch)
        }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    private fun start(url: String, fetch: suspend () -> TmdbPage): Entry =
        Entry(scope.async { fetch() }, System.currentTimeMillis()).also { entries[url] = it }

    private fun isUsable(entry: Entry): Boolean =
        System.currentTimeMillis() - entry.createdAt <= TTL_MS && !entry.page.isCancelled

    private fun isReusable(page: TmdbPage): Boolean =
        (page.code in 200..299 || page.code == 404) && StreamCenterTmdbChallenge.parse(page.text) == null

    private fun forget(url: String, entry: Entry) = synchronized(entries) {
        if (entries[url] === entry) entries.remove(url)
    }

    private const val TTL_MS = 10L * 60L * 1_000L
    private const val MAX_ENTRIES = 120
}
