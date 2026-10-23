package it.dogior.hadEnough.util

import com.lagradost.nicehttp.NiceResponse
import it.dogior.hadEnough.tmdb.StreamCenterTmdbChallenge
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal object StreamCenterHostGate {
    private class Policy(val maxConcurrent: Int, val minIntervalMs: Long)

    private class HostState(val domain: String, val policy: Policy) {
        val permits = Semaphore(policy.maxConcurrent)
        val pacing = Mutex()
        var nextSlotAt = 0L
        @Volatile
        var cooldownUntil = 0L
    }

    private val policies = linkedMapOf(
        "api.themoviedb.org" to Policy(maxConcurrent = 4, minIntervalMs = 50L),
        "themoviedb.org" to Policy(maxConcurrent = 3, minIntervalMs = 120L),
        "anilist.co" to Policy(maxConcurrent = 1, minIntervalMs = 300L),
        "jikan.moe" to Policy(maxConcurrent = 1, minIntervalMs = 1_000L),
        "myanimelist.net" to Policy(maxConcurrent = 2, minIntervalMs = 500L),
        "kitsu.io" to Policy(maxConcurrent = 2, minIntervalMs = 250L),
        "kitsu.app" to Policy(maxConcurrent = 2, minIntervalMs = 250L),
        "ani.zip" to Policy(maxConcurrent = 2, minIntervalMs = 200L),
        "simkl.com" to Policy(maxConcurrent = 2, minIntervalMs = 250L),
        "simkl.in" to Policy(maxConcurrent = 2, minIntervalMs = 250L),
        "anibridge.eliasbenb.dev" to Policy(maxConcurrent = 1, minIntervalMs = 1_000L),
    )
    private val states = ConcurrentHashMap<String, HostState>()

    suspend fun request(url: String, send: suspend () -> NiceResponse): NiceResponse {
        val state = stateFor(url) ?: return send()
        return state.permits.withPermit {
            awaitCooldown(state)
            awaitSlot(state)
            val response = send()
            if (response.code == 429 || response.code == 503) startCooldown(state, response)
            if (StreamCenterTmdbChallenge.capture(response)) send() else response
        }
    }

    private fun stateFor(url: String): HostState? {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').substringBefore('?').lowercase()
        val (domain, policy) = policies.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.toPair()
            ?: return null
        return states.getOrPut(domain) { HostState(domain, policy) }
    }

    private suspend fun awaitCooldown(state: HostState) {
        val wait = state.cooldownUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
    }

    private suspend fun awaitSlot(state: HostState) {
        val wait = state.pacing.withLock {
            val now = System.currentTimeMillis()
            val slot = maxOf(now, state.nextSlotAt)
            state.nextSlotAt = slot + state.policy.minIntervalMs
            slot - now
        }
        if (wait > 0) delay(wait)
    }

    private fun startCooldown(state: HostState, response: NiceResponse) {
        val retryAfterMs = response.headers["Retry-After"]?.trim()?.toLongOrNull()?.times(1_000L)
        val cooldownMs = (retryAfterMs ?: DEFAULT_COOLDOWN_MS).coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS)
        val until = System.currentTimeMillis() + cooldownMs
        if (until <= state.cooldownUntil) return
        state.cooldownUntil = until
        StreamCenterLogger.warning(
            "Sito sovraccarico: richieste in pausa",
            mapOf(
                "dominio" to state.domain,
                "stato_http" to response.code,
                "pausa_ms" to cooldownMs,
                "retry_after" to response.headers["Retry-After"],
            ),
        )
    }

    private const val DEFAULT_COOLDOWN_MS = 5_000L
    private const val MIN_COOLDOWN_MS = 1_000L
    private const val MAX_COOLDOWN_MS = 60_000L
}
