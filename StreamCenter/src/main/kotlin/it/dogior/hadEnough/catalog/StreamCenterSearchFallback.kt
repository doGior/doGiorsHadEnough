package it.dogior.hadEnough.catalog

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class StreamCenterSearchFallback<T> {
    private class Route(var fallback: Boolean = false)
    private val routes = object : LinkedHashMap<String, Route>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Route>?): Boolean = size > 32
    }

    suspend fun search(
        query: String,
        page: Int,
        enabled: Boolean,
        primary: suspend () -> Pair<List<T>, Boolean>,
        fallback: suspend () -> Pair<List<T>, Boolean>,
    ): Pair<List<T>, Boolean> {
        currentCoroutineContext().ensureActive()
        val key = query.trim()
        if (key.isEmpty() || page < 1) return emptyList<T>() to false
        val (route, continuingFallback) = synchronized(routes) {
            val current = if (page == 1) Route().also { routes[key] = it } else routes[key]
            current to (current?.fallback == true)
        }
        if (continuingFallback) {
            return if (enabled) fallback() else emptyList<T>() to false
        }
        val result = primary()
        currentCoroutineContext().ensureActive()
        if (!enabled || page != 1 || result.first.isNotEmpty()) return result
        val fallbackResult = fallback()
        if (fallbackResult.first.isEmpty()) return result
        synchronized(routes) {
            if (routes[key] === route) route?.fallback = true
        }
        return fallbackResult
    }
}
