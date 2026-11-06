package it.dogior.hadEnough

import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.settings.STREAMING_COMMUNITY_UPDATED_LINK_PAGE
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.runCatchingCancellable
import java.util.Locale
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

private const val REACHABLE_RECHECK_MS = 6 * 60 * 60 * 1000L
private const val UNREACHABLE_RECHECK_MS = 2 * 60 * 1000L
private const val CURRENT_DOMAIN_RETRY_DELAY_MS = 1_500L
private const val DOMAIN_CHECK_WAIT_MS = 8_000L
private const val DOMAIN_PROBE_TIMEOUT_S = 8L
private const val PUBLISHED_LINK_TIMEOUT_S = 10L
private val publishedStreamingCommunityHost = Regex("""streamingcommunity[a-z0-9-]*(?:\.[a-z0-9-]+)+""")

internal enum class SourceReachability { UNKNOWN, REACHABLE, UNREACHABLE }

internal object StreamCenterSourceDomains {
    private class State {
        var checkedAt = 0L
        var reachability = SourceReachability.UNKNOWN
        var inFlight: Deferred<Boolean>? = null
        var generation = 0L
        var startedAt = 0L
    }

    private val states = HashMap<String, State>()
    private var nextGeneration = 0L

    private fun now() = System.currentTimeMillis()

    fun reset() = synchronized(states) { states.clear() }

    fun adoptIfCurrent(prefKey: String, generation: Long, current: String, read: () -> String, write: () -> Unit): Boolean =
        synchronized(states) {
            if (states[prefKey]?.generation != generation || read() != current) false
            else { write(); true }
        }

    fun remainingWait(prefKey: String, check: Deferred<Boolean>): Long = synchronized(states) {
        states[prefKey]?.takeIf { it.inFlight === check }
            ?.let { (DOMAIN_CHECK_WAIT_MS - (SystemClock.elapsedRealtime() - it.startedAt)).coerceAtLeast(0L) }
            ?: 0L
    }

    fun reachability(prefKey: String): SourceReachability = synchronized(states) {
        val state = states[prefKey] ?: return SourceReachability.UNKNOWN
        val expired = state.reachability == SourceReachability.UNREACHABLE &&
            now() - state.checkedAt >= UNREACHABLE_RECHECK_MS
        if (expired) SourceReachability.UNKNOWN else state.reachability
    }

    fun pendingCheck(prefKey: String, force: Boolean, start: (Long) -> Deferred<Boolean>): Deferred<Boolean>? =
        synchronized(states) {
            val state = states.getOrPut(prefKey, ::State)
            state.inFlight?.takeIf { it.isActive }?.let { return it }
            val age = now() - state.checkedAt
            val fresh = when (state.reachability) {
                SourceReachability.REACHABLE -> age < REACHABLE_RECHECK_MS
                SourceReachability.UNREACHABLE -> age < UNREACHABLE_RECHECK_MS
                SourceReachability.UNKNOWN -> false
            }
            if (fresh && !force) return null
            state.checkedAt = now()
            state.startedAt = SystemClock.elapsedRealtime()
            state.generation = ++nextGeneration
            start(state.generation).also { state.inFlight = it }
        }

    fun complete(
        prefKey: String,
        generation: Long,
        reachability: SourceReachability,
        expectedUrl: String,
        currentUrl: () -> String,
        changed: Boolean,
    ) = synchronized(states) {
        if (!changed && currentUrl() != expectedUrl) {
            if (states[prefKey]?.generation == generation) states.remove(prefKey)
            return@synchronized
        }
        states[prefKey]?.takeIf { it.generation == generation }?.apply {
            this.reachability = reachability
            checkedAt = now()
            inFlight = null
        }
        Unit
    }
}

private data class SourceDomainOutcome(val reachability: SourceReachability, val changed: Boolean)

private data class SourceProbe(val responded: Boolean, val validOrigin: String?)

internal fun isSourceReachable(prefKey: String): Boolean =
    StreamCenterSourceDomains.reachability(prefKey) != SourceReachability.UNREACHABLE

internal suspend fun StreamCenter.isSourceReachableNow(prefKey: String): Boolean {
    ensureUpdatedSourceDomain(prefKey)
    return isSourceReachable(prefKey)
}

internal suspend fun StreamCenter.isSourceAvailable(prefKey: String): Boolean =
    isSourceEnabled(prefKey) && isSourceReachableNow(prefKey)

internal suspend fun StreamCenter.ensureUpdatedSourceDomain(
    prefKey: String,
    force: Boolean = false,
    waitDeadlineAt: Long? = null,
): Boolean {
    val check = StreamCenterSourceDomains.pendingCheck(prefKey, force) { generation ->
        backgroundScope.async {
            val expectedUrl = StreamCenterPlugin.getSourceBaseUrl(sharedPref, prefKey)
            val outcome = runCatchingCancellable { refreshSourceDomain(prefKey, generation) }.getOrNull()
            StreamCenterSourceDomains.complete(
                prefKey, generation, outcome?.reachability ?: SourceReachability.UNKNOWN,
                expectedUrl, { StreamCenterPlugin.getSourceBaseUrl(sharedPref, prefKey) }, outcome?.changed == true,
            )
            outcome?.changed == true
        }
    } ?: return false
    val remaining = minOf(
        StreamCenterSourceDomains.remainingWait(prefKey, check),
        waitDeadlineAt?.minus(SystemClock.elapsedRealtime())?.coerceAtLeast(0L) ?: DOMAIN_CHECK_WAIT_MS,
    )
    return if (remaining > 0) {
        try {
            withTimeoutOrNull(remaining) { check.await() } ?: false
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            false
        }
    } else false
}

internal suspend fun <T> StreamCenter.withSourceDomain(prefKey: String, request: suspend () -> T): T {
    val waitDeadlineAt = SystemClock.elapsedRealtime() + DOMAIN_CHECK_WAIT_MS
    ensureUpdatedSourceDomain(prefKey, waitDeadlineAt = waitDeadlineAt)
    val first = runCatchingCancellable { request() }
    if (first.getOrNull() != null) return first.getOrThrow()
    return if (ensureUpdatedSourceDomain(prefKey, force = true, waitDeadlineAt = waitDeadlineAt)) request() else first.getOrThrow()
}

internal fun StreamCenter.resetSourceSession(prefKey: String) {
    when (prefKey) {
        StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY -> streamingCommunityClient.resetSession()
        StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY -> animeUnitySourceClient.resetSession()
    }
}

internal suspend fun fetchStreamingCommunityPublishedUrl(): String? {
    val page = app.get(STREAMING_COMMUNITY_UPDATED_LINK_PAGE, timeout = PUBLISHED_LINK_TIMEOUT_S).document
    return page.select("a[href]")
        .asSequence()
        .mapNotNull { it.absUrl("href").trim().toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) }
        .firstOrNull(publishedStreamingCommunityHost::matches)
        ?.let { "https://$it" }
}

private suspend fun StreamCenter.refreshSourceDomain(prefKey: String, generation: Long): SourceDomainOutcome {
    StreamCenterVpnGuard.requireInternetAccess(sharedPref)
    val source = StreamCenterPlugin.streamingSources.firstOrNull { it.key == prefKey }
        ?: return SourceDomainOutcome(SourceReachability.UNKNOWN, false)
    val current = StreamCenterPlugin.getSourceBaseUrl(sharedPref, prefKey)
    if (current.isBlank()) return SourceDomainOutcome(SourceReachability.UNKNOWN, false)
    val autoUpdate = StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref)
    val isStreamingCommunity = prefKey == StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY

    fun adopt(url: String, origin: String): Boolean {
        if (!autoUpdate || hostOf(url).equals(hostOf(current), ignoreCase = true)) return false
        if (!StreamCenterSourceDomains.adoptIfCurrent(
                prefKey, generation, current,
                { StreamCenterPlugin.getSourceBaseUrl(sharedPref, prefKey) },
                { StreamCenterPlugin.setSourceBaseUrl(sharedPref, prefKey, url) },
            )) return false
        resetSourceSession(prefKey)
        StreamCenterLogger.logMenu(
            action = "Link fonte aggiornato automaticamente",
            metadata = mapOf("fonte" to source.title, "precedente" to current, "nuovo" to url, "origine" to origin),
        )
        return true
    }

    val currentProbe = probeSourceDomain(source, current, trustSameHost = true).let { first ->
        if (first.responded) first
        else {
            delay(CURRENT_DOMAIN_RETRY_DELAY_MS)
            probeSourceDomain(source, current, trustSameHost = true)
        }
    }
    currentProbe.validOrigin?.let { resolved ->
        return SourceDomainOutcome(SourceReachability.REACHABLE, adopt(resolved, "redirect"))
    }
    if (autoUpdate && isStreamingCommunity) {
        val published = runCatchingCancellable { fetchStreamingCommunityPublishedUrl() }.getOrNull()
        if (published != null && domainKey(published) != domainKey(current)) {
            val probe = probeSourceDomain(source, published, trustSameHost = false)
            val changed = adopt(probe.validOrigin ?: published, "telegraph")
            return if (probe.validOrigin != null) SourceDomainOutcome(SourceReachability.REACHABLE, changed)
            else unreachableSource(source, published, changed)
        }
    }
    val fallback = source.defaultUrl.trimEnd('/')
    if (autoUpdate && domainKey(fallback) != domainKey(current)) {
        probeSourceDomain(source, fallback, trustSameHost = false).validOrigin?.let { resolved ->
            return SourceDomainOutcome(SourceReachability.REACHABLE, adopt(resolved, "predefinito"))
        }
    }
    if (currentProbe.responded && !isStreamingCommunity) {
        return SourceDomainOutcome(SourceReachability.REACHABLE, false)
    }
    return unreachableSource(source, current, changed = false)
}

private fun unreachableSource(source: StreamCenterStreamingSource, url: String, changed: Boolean): SourceDomainOutcome {
    StreamCenterLogger.logMenu(
        action = "Fonte non raggiungibile: uso le altre fonti",
        metadata = mapOf("fonte" to source.title, "link" to url, "ricontrollo_tra_s" to UNREACHABLE_RECHECK_MS / 1000),
        level = StreamCenterLogger.Level.WARNING,
    )
    return SourceDomainOutcome(SourceReachability.UNREACHABLE, changed)
}

private suspend fun StreamCenter.probeSourceDomain(
    source: StreamCenterStreamingSource,
    baseUrl: String,
    trustSameHost: Boolean,
): SourceProbe {
    val failed = SourceProbe(responded = false, validOrigin = null)
    val isStreamingCommunity = source.key == StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY
    val probeUrl = if (isStreamingCommunity) "$baseUrl/it" else baseUrl
    val response = runCatchingCancellable {
        app.get(probeUrl, headers = headers, timeout = DOMAIN_PROBE_TIMEOUT_S)
    }.getOrNull() ?: return failed
    val finalUrl = response.url.toHttpUrlOrNull() ?: return failed
    if (!finalUrl.isHttps && !(trustSameHost && baseUrl.startsWith("http://", ignoreCase = true))) return failed
    if (response.code !in 200..299) return SourceProbe(responded = true, validOrigin = null)
    val origin = "${if (finalUrl.isHttps) "https" else "http"}://${finalUrl.host}"
    val needsValidation = isStreamingCommunity || !trustSameHost || domainKey(finalUrl.host) != domainKey(baseUrl)
    if (!needsValidation) return SourceProbe(responded = true, validOrigin = origin)
    val body = runCatchingCancellable { response.text }.getOrNull()
        ?: return SourceProbe(responded = true, validOrigin = null)
    return SourceProbe(responded = true, validOrigin = origin.takeIf { looksLikeSource(source, body) })
}

private fun looksLikeSource(source: StreamCenterStreamingSource, html: String): Boolean {
    val document = Jsoup.parse(html)
    if (source.key == StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) {
        return document.selectFirst("#app[data-page]") != null
    }
    val expected = sourceToken(source.title)
    return listOf(
        document.title(),
        document.selectFirst("meta[property=og:site_name]")?.attr("content").orEmpty(),
    ).any { sourceToken(it).contains(expected) }
}

private fun sourceToken(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

private fun domainKey(urlOrHost: String): String =
    urlOrHost.substringAfter("://").substringBefore('/').substringBefore(':')
        .lowercase(Locale.ROOT).removePrefix("www.")
