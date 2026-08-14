package it.dogior.hadEnough.torrent

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.NiceResponse
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.Headers
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

private const val EXT_DEFAULT_CONTAIN_TYPE = "0"

internal enum class StreamCenterExtAvailability(
    val displayName: String,
) {
    AVAILABLE("Disponibile"),
    VERIFICATION_REQUIRED("Verifica Cloudflare richiesta"),
    RATE_LIMITED("Temporaneamente limitato"),
    UNAVAILABLE("Non raggiungibile"),
    INVALID_RESPONSE("Risposta non valida"),
}

internal data class StreamCenterExtDomainStatus(
    val domain: StreamCenterExtDomain,
    val availability: StreamCenterExtAvailability,
    val httpCode: Int? = null,
    val detail: String? = null,
    val responseTimeMs: Long? = null,
) {
    val allowsAutomaticFallback: Boolean
        get() = availability != StreamCenterExtAvailability.AVAILABLE
}

internal data class StreamCenterExtSearchOutcome(
    val domain: StreamCenterExtDomain,
    val status: StreamCenterExtDomainStatus,
    val candidates: List<StreamCenterTorrentCandidate> = emptyList(),
    val executedPlans: List<StreamCenterExtSearchPlan> = emptyList(),
    val appliedLocations: List<StreamCenterExtContainLocation> = emptyList(),
    val rawCandidateCount: Int = 0,
    val eligibleRowCount: Int = 0,
    val requestedMagnetCount: Int = 0,
    val requestedSearchPageCount: Int = 0,
    val fromCache: Boolean = false,
    val partial: Boolean = false,
) {
    val allowsAutomaticFallback: Boolean
        get() = status.allowsAutomaticFallback

    val shouldTryNextDomain: Boolean
        get() = partial || allowsAutomaticFallback || candidates.isEmpty()
}

internal object StreamCenterExtCloudflareSession {
    @Volatile
    private var userAgent: String? = null
    private val responseCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    fun updateUserAgent(value: String?) {
        val resolvedUserAgent = value?.trim()?.takeIf(String::isNotBlank)
        userAgent = resolvedUserAgent
        if (resolvedUserAgent != null) {
            WebViewResolver.webViewUserAgent = resolvedUserAgent
            runCatching {
                (getContext() as? Context)
                    ?.getSharedPreferences(CLOUDFLARE_SESSION_PREFERENCES, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString(CLOUDFLARE_USER_AGENT_KEY, resolvedUserAgent)
                    ?.apply()
            }
        }
    }

    fun isReady(url: String): Boolean = hasVerifiedClearance(url)

    fun hasVerifiedClearance(url: String): Boolean {
        val hasWebViewClearance = parseCookiePairs(cookiesFor(url)).any { (name, _) ->
            name.equals(CLOUDFLARE_CLEARANCE_COOKIE, ignoreCase = true)
        }
        val hasResponseClearance = responseCookies[cookieHost(url)]
            ?.keys
            ?.any { name -> name.equals(CLOUDFLARE_CLEARANCE_COOKIE, ignoreCase = true) }
            ?: false
        return hasWebViewClearance || hasResponseClearance
    }

    fun requestHeaders(url: String): Map<String, String> {
        val resolvedUserAgent = userAgent
            ?: storedUserAgent()?.also { storedUserAgent -> userAgent = storedUserAgent }
            ?: WebViewResolver.webViewUserAgent?.trim()?.takeIf(String::isNotBlank)
            ?: runCatching {
                (getContext() as? Context)?.let(WebSettings::getDefaultUserAgent)
            }.getOrNull()?.trim()?.takeIf(String::isNotBlank)
            ?: FALLBACK_USER_AGENT
        val cookies = linkedMapOf<String, String>()
        parseCookiePairs(cookiesFor(url)).forEach { (name, value) -> cookies[name] = value }
        responseCookies[cookieHost(url)]?.forEach { (name, value) -> cookies[name] = value }
        return buildMap {
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "it-IT,it;q=0.9,en;q=0.7")
            put("User-Agent", resolvedUserAgent)
            cookies.takeIf { pairs -> pairs.isNotEmpty() }?.let { pairs ->
                put("Cookie", pairs.entries.joinToString("; ") { (name, value) -> "$name=$value" })
            }
        }
    }

    fun rememberResponseCookies(url: String, headers: Headers) {
        val host = cookieHost(url)
        val cookies = responseCookiePairs(headers)
        if (cookies.isEmpty()) return
        val hostCookies = responseCookies.computeIfAbsent(host) { ConcurrentHashMap() }
        cookies.take(MAX_COOKIES_PER_HOST).forEach { (name, value) ->
            if (value.isBlank()) hostCookies.remove(name)
            else hostCookies[name] = value.take(MAX_COOKIE_VALUE_LENGTH)
        }
        if (hostCookies.size > MAX_COOKIES_PER_HOST) {
            hostCookies.keys.drop(MAX_COOKIES_PER_HOST).forEach(hostCookies::remove)
        }
    }

    fun headersWithResponseCookies(
        requestHeaders: Map<String, String>,
        responseHeaders: Headers,
    ): Map<String, String> {
        val cookies = linkedMapOf<String, String>()
        parseCookiePairs(requestHeaders["Cookie"]).forEach { (name, value) -> cookies[name] = value }
        responseCookiePairs(responseHeaders).forEach { (name, value) -> cookies[name] = value }
        return requestHeaders.toMutableMap().apply {
            if (cookies.isEmpty()) {
                remove("Cookie")
            } else {
                put("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
            }
        }
    }

    private fun cookiesFor(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)
    }.getOrNull()?.trim()?.takeIf(String::isNotBlank)

    private fun storedUserAgent(): String? = runCatching {
        (getContext() as? Context)
            ?.getSharedPreferences(CLOUDFLARE_SESSION_PREFERENCES, Context.MODE_PRIVATE)
            ?.getString(CLOUDFLARE_USER_AGENT_KEY, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun parseCookiePairs(value: String?): List<Pair<String, String>> = value
        ?.split(';')
        ?.mapNotNull { entry ->
            val name = entry.substringBefore('=', "").trim()
            val content = entry.substringAfter('=', "").trim()
            name.takeIf(String::isNotBlank)?.let { cookieName -> cookieName to content }
        }
        .orEmpty()

    private fun responseCookiePairs(headers: Headers): List<Pair<String, String>> = headers
        .values("Set-Cookie")
        .mapNotNull { header ->
            val pair = header.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            name.takeIf(String::isNotBlank)?.let { cookieName -> cookieName to value }
        }

    private fun cookieHost(url: String): String = runCatching { URI(url).host }
        .getOrNull()
        ?.lowercase(Locale.ROOT)
        .orEmpty()

    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val CLOUDFLARE_SESSION_PREFERENCES = "streamcenter_ext_cloudflare_session"
    private const val CLOUDFLARE_USER_AGENT_KEY = "verified_user_agent"
    private const val CLOUDFLARE_CLEARANCE_COOKIE = "cf_clearance"
    private const val MAX_COOKIES_PER_HOST = 32
    private const val MAX_COOKIE_VALUE_LENGTH = 4_096
}

internal object StreamCenterExtTorrentClient {
    private val positiveSearchCache = ConcurrentHashMap<SearchCacheKey, CachedSearchOutcome>()
    private val searchFlights = ConcurrentHashMap<SearchCacheKey, SearchFlight>()

    suspend fun checkAvailability(domain: StreamCenterExtDomain): StreamCenterExtDomainStatus {
        StreamCenterVpnGuard.requireInternetAccess()
        if (StreamCenterTorrentRequestGate.isCoolingDown(requestGateKey(domain))) {
            val remainingMs = StreamCenterTorrentRequestGate.remainingCooldownMs(requestGateKey(domain))
            return StreamCenterExtDomainStatus(
                domain = domain,
                availability = StreamCenterExtAvailability.RATE_LIMITED,
                detail = "cooldown_attivo_${remainingMs}ms",
            )
        }
        val startedAt = System.currentTimeMillis()
        val baseUrl = domain.baseUrl
        val response = try {
            retryTransientRequest {
                StreamCenterTorrentRequestGate.rateLimited(
                    sourceKey = requestGateKey(domain),
                    minimumIntervalMs = REQUEST_INTERVAL_MS,
                ) {
                    StreamCenterVpnGuard.requireInternetAccess()
                    app.get(
                        url = "$baseUrl/advanced/",
                        headers = StreamCenterExtCloudflareSession.requestHeaders(baseUrl),
                        cacheTime = 0,
                        timeout = AVAILABILITY_TIMEOUT_SECONDS,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return StreamCenterExtDomainStatus(
                domain = domain,
                availability = StreamCenterExtAvailability.UNAVAILABLE,
                detail = error.javaClass.simpleName,
                responseTimeMs = System.currentTimeMillis() - startedAt,
            )
        }
        StreamCenterExtCloudflareSession.rememberResponseCookies(baseUrl, response.headers)
        return classifyResponse(
            domain = domain,
            httpCode = response.code,
            headers = response.headers,
            html = response.text,
            expectedMarkup = { document ->
                document.selectFirst("input[name=q], select[name=contain_type]") != null
            },
        ).copy(responseTimeMs = System.currentTimeMillis() - startedAt)
    }

    suspend fun search(
        domain: StreamCenterExtDomain,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        desiredResults: Int? = null,
        timeBudgetMs: Long? = null,
    ): StreamCenterExtSearchOutcome = supervisorScope {
        StreamCenterVpnGuard.requireInternetAccess()
        val candidateTarget = desiredResults?.coerceAtLeast(1)
        val startedNanos = System.nanoTime()
        val deadlineNanos = timeBudgetMs
            ?.coerceAtLeast(1L)
            ?.let { budget -> startedNanos + budget * NANOS_PER_MILLISECOND }
        val searchDeadlineNanos = timeBudgetMs?.let { budget ->
            val searchBudgetMs = (budget / SEARCH_PHASE_BUDGET_DIVISOR)
                .coerceAtLeast(MIN_SEARCH_PHASE_BUDGET_MS)
                .coerceAtMost(MAX_SEARCH_PHASE_BUDGET_MS)
            minOf(
                requireNotNull(deadlineNanos),
                startedNanos + searchBudgetMs * NANOS_PER_MILLISECOND,
            )
        }
        val cacheKey = SearchCacheKey(
            domain = domain,
            titles = context.titles,
            englishTitle = context.englishTitle,
            japaneseTitle = context.japaneseTitle,
            year = context.year,
            isAnime = context.isAnime,
            isMovie = context.isMovie,
            season = context.season,
            episode = context.episode,
            episodeCoordinates = context.episodeCoordinatesForSearch(),
            filters = filters,
            desiredResults = candidateTarget,
            imdbId = context.imdbId,
        )
        cachedSearchOutcome(cacheKey)?.let { cached -> return@supervisorScope cached }
        val searchFlight = acquireSearchFlight(cacheKey)
        try {
            searchFlight.mutex.withLock flight@ {
                cachedSearchOutcome(cacheKey)?.let { cached -> return@flight cached }
        if (StreamCenterTorrentRequestGate.isCoolingDown(requestGateKey(domain))) {
            val remainingMs = StreamCenterTorrentRequestGate.remainingCooldownMs(requestGateKey(domain))
            return@flight StreamCenterExtSearchOutcome(
                domain = domain,
                status = StreamCenterExtDomainStatus(
                    domain = domain,
                    availability = StreamCenterExtAvailability.RATE_LIMITED,
                    detail = "cooldown_attivo_${remainingMs}ms",
                ),
            )
        }
        val category = context.extCategory()
        val enabledSources = StreamCenterExtReleaseSources.forCategory(category)
        val plans = StreamCenterExtSearchPlanner.plans(context)
        if (plans.isEmpty()) {
            return@flight StreamCenterExtSearchOutcome(
                domain = domain,
                status = StreamCenterExtDomainStatus(
                    domain = domain,
                    availability = StreamCenterExtAvailability.INVALID_RESPONSE,
                    detail = "Nessuna frase di ricerca valida",
                ),
            )
        }
        val schedule = searchSchedule(plans, filters.containLocation)
        val rows = mutableListOf<ExtListingRow>()
        val executedPlans = linkedSetOf<StreamCenterExtSearchPlan>()
        val appliedLocations = linkedSetOf<StreamCenterExtContainLocation>()
        var searchPageRequests = 0
        var interruptedStatus: StreamCenterExtDomainStatus? = null

        fun remainingBudgetMs(): Long? = deadlineNanos?.let { deadline ->
            ((deadline - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
        }

        fun remainingSearchBudgetMs(): Long? = searchDeadlineNanos?.let { deadline ->
            ((deadline - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
        }

        fun budgetExpiredStatus(): StreamCenterExtDomainStatus = StreamCenterExtDomainStatus(
            domain = domain,
            availability = StreamCenterExtAvailability.UNAVAILABLE,
            detail = "budget_ricerca_esaurito",
        )

        val imdbDiscoveryRows = if (context.imdbId != null) discoverByImdb(domain, context) else emptyList()
        val imdbDiscoverySucceeded = imdbDiscoveryRows.any { row ->
            row.isEligibleForDiscovery(context, filters)
        }
        if (imdbDiscoveryRows.isNotEmpty()) {
            rows += imdbDiscoveryRows
            executedPlans += StreamCenterExtSearchPlan(
                query = "imdb:${normalizeImdbId(context.imdbId)}",
                reason = "ricerca per ID IMDB",
                batchSearch = false,
            )
            appliedLocations += StreamCenterExtContainLocation.TITLE
            searchPageRequests += 2
        }

        searchPlans@ for ((plan, location) in schedule.takeIf { !imdbDiscoverySucceeded }.orEmpty()) {
            executedPlans += plan
            appliedLocations += location
            var pageNumber = 1
            while (true) {
                val request = ExtSearchRequest(
                    query = plan.query,
                    category = category,
                    location = location,
                    filters = filters,
                    releaseSources = enabledSources,
                    page = pageNumber,
                )
                searchPageRequests++
                val totalRequestBudgetMs = remainingBudgetMs()?.minus(CLIENT_RETURN_RESERVE_MS)
                val searchRequestBudgetMs = remainingSearchBudgetMs()
                val requestBudgetMs = listOfNotNull(totalRequestBudgetMs, searchRequestBudgetMs)
                    .minOrNull()
                if (requestBudgetMs != null && requestBudgetMs <= 0L) {
                    interruptedStatus = budgetExpiredStatus()
                    break@searchPlans
                }
                val page = if (requestBudgetMs == null) {
                    searchPage(domain, request)
                } else {
                    withTimeoutOrNull(requestBudgetMs) { searchPage(domain, request) }
                }
                if (page == null) {
                    interruptedStatus = budgetExpiredStatus()
                    break@searchPlans
                }
                if (page.status.availability != StreamCenterExtAvailability.AVAILABLE) {
                    interruptedStatus = page.status
                    if (rows.isEmpty()) {
                        return@flight StreamCenterExtSearchOutcome(
                            domain = domain,
                            status = page.status,
                            executedPlans = executedPlans.toList(),
                            appliedLocations = appliedLocations.toList(),
                            requestedSearchPageCount = searchPageRequests,
                        )
                    }
                    break@searchPlans
                }
                rows += page.rows
                if (hasCandidateBudget(rows, context, filters, candidateTarget)) break
                if (!page.hasNextPage || pageNumber == Int.MAX_VALUE) break
                pageNumber++
            }
            val searchedEveryRequestedLocation = filters.containLocation !=
                StreamCenterExtContainLocation.TITLE_AND_FILES || appliedLocations.size >= 2
            if (
                searchedEveryRequestedLocation &&
                hasCandidateBudget(rows, context, filters, candidateTarget)
            ) {
                break
            }
        }

        val mergedRows = mergeListingRows(rows)
        val eligibleRows = mergedRows
            .asSequence()
            .filter { row -> row.isEligibleForDiscovery(context, filters) }
            .map { row ->
                RankedExtListingRow(
                    row = row,
                    ranking = StreamCenterTorrentFilterEngine.rank(
                        row.toCandidateMetadata(),
                        context,
                        filters,
                    ),
                )
            }
            .sortedWith(
                compareByDescending<RankedExtListingRow> { ranked -> ranked.ranking.languagePriority }
                    .thenByDescending { ranked -> ranked.ranking.score }
                    .thenBy { ranked -> ranked.row.title.lowercase(Locale.ROOT) },
            )
            .map(RankedExtListingRow::row)
            .toList()
        val candidates = mutableListOf<StreamCenterTorrentCandidate>()
        var requestedCandidateCount = 0
        val rowsToLoad = extCandidateRowsToLoad(eligibleRows, candidateTarget)
        val directRows = rowsToLoad.filter { row -> row.canLoadWithoutNetwork(context, filters) }
        val networkRows = rowsToLoad.filterNot { row -> row.canLoadWithoutNetwork(context, filters) }
        for (chunk in (directRows + networkRows).chunked(CANDIDATE_REQUEST_CONCURRENCY)) {
            val remainingMs = remainingBudgetMs()
            val requestBudgetMs = remainingMs?.minus(CLIENT_RETURN_RESERVE_MS)
            if (requestBudgetMs != null && requestBudgetMs <= 0L) {
                interruptedStatus = budgetExpiredStatus()
                break
            }
            requestedCandidateCount += chunk.size
            val completedCandidates = ConcurrentLinkedQueue<StreamCenterTorrentCandidate>()
            val loadChunk: suspend () -> Unit = {
                chunk.map { row ->
                    async {
                        loadCandidate(
                            domain = domain,
                            row = row,
                            context = context,
                            filters = filters,
                        )?.let(completedCandidates::add)
                    }
                }.awaitAll()
            }
            val completedEntireChunk = if (requestBudgetMs == null) {
                loadChunk()
                true
            } else {
                withTimeoutOrNull(requestBudgetMs) { loadChunk() } != null
            }
            completedCandidates.forEach { candidate ->
                val hash = StreamCenterTorrentMagnet.infoHash(candidate.magnetUrl)
                    ?: StreamCenterTorrentMagnet.infoHash(candidate.infoHash)
                if (hash != null && candidates.none { current ->
                        StreamCenterTorrentMagnet.infoHash(current.magnetUrl) == hash ||
                            StreamCenterTorrentMagnet.infoHash(current.infoHash) == hash
                    }
                ) {
                    candidates += candidate
                }
            }
            if (!completedEntireChunk) {
                interruptedStatus = budgetExpiredStatus()
                break
            }
            if (candidateTarget != null && candidates.size >= candidateTarget) break
        }

        val hasPartialResults = interruptedStatus != null && candidates.isNotEmpty()
        val finalStatus = when {
            hasPartialResults -> StreamCenterExtDomainStatus(
                domain = domain,
                availability = StreamCenterExtAvailability.AVAILABLE,
                httpCode = interruptedStatus.httpCode,
                detail = "risultati_parziali_${interruptedStatus.availability.name.lowercase(Locale.ROOT)}",
            )
            interruptedStatus != null && candidates.isEmpty() -> interruptedStatus
            else -> StreamCenterExtDomainStatus(
                domain = domain,
                availability = StreamCenterExtAvailability.AVAILABLE,
            )
        }

        StreamCenterExtSearchOutcome(
            domain = domain,
            status = finalStatus,
            candidates = candidateTarget?.let(candidates::take) ?: candidates,
            executedPlans = executedPlans.toList(),
            appliedLocations = appliedLocations.toList(),
            rawCandidateCount = rows.size,
            eligibleRowCount = eligibleRows.size,
            requestedMagnetCount = requestedCandidateCount,
            requestedSearchPageCount = searchPageRequests,
            partial = hasPartialResults,
        ).also { outcome -> rememberSearchOutcome(cacheKey, outcome) }
            }
        } finally {
            releaseSearchFlight(cacheKey, searchFlight)
        }
    }

    private fun cachedSearchOutcome(key: SearchCacheKey): StreamCenterExtSearchOutcome? {
        val cached = positiveSearchCache[key] ?: return null
        val ageMs = System.currentTimeMillis() - cached.storedAtMs
        if (ageMs !in 0..POSITIVE_SEARCH_CACHE_TTL_MS) {
            positiveSearchCache.remove(key, cached)
            return null
        }
        return cached.outcome.copy(
            status = cached.outcome.status.copy(detail = "cache_${ageMs}ms"),
            requestedMagnetCount = 0,
            requestedSearchPageCount = 0,
            fromCache = true,
        )
    }

    private fun acquireSearchFlight(key: SearchCacheKey): SearchFlight = requireNotNull(
        searchFlights.compute(key) { _, current ->
            (current ?: SearchFlight()).also { flight -> flight.users.incrementAndGet() }
        },
    )

    private fun releaseSearchFlight(key: SearchCacheKey, flight: SearchFlight) {
        if (flight.users.decrementAndGet() == 0) searchFlights.remove(key, flight)
    }

    private fun rememberSearchOutcome(
        key: SearchCacheKey,
        outcome: StreamCenterExtSearchOutcome,
    ) {
        if (
            outcome.partial ||
            outcome.candidates.isEmpty() ||
            outcome.status.availability != StreamCenterExtAvailability.AVAILABLE
        ) {
            return
        }
        positiveSearchCache[key] = CachedSearchOutcome(
            outcome = outcome,
            storedAtMs = System.currentTimeMillis(),
        )
        if (positiveSearchCache.size <= MAX_POSITIVE_SEARCH_CACHE_ENTRIES) return
        positiveSearchCache.entries
            .sortedBy { entry -> entry.value.storedAtMs }
            .take(positiveSearchCache.size - MAX_POSITIVE_SEARCH_CACHE_ENTRIES)
            .forEach { entry -> positiveSearchCache.remove(entry.key, entry.value) }
    }

    private suspend fun searchPage(
        domain: StreamCenterExtDomain,
        request: ExtSearchRequest,
    ): ExtSearchPageResult {
        val url = request.toUrl(domain.baseUrl)
        val requestHeaders = StreamCenterExtCloudflareSession.requestHeaders(domain.baseUrl)
        val response = try {
            retryTransientRequest {
                StreamCenterTorrentRequestGate.rateLimited(
                    sourceKey = requestGateKey(domain),
                    minimumIntervalMs = REQUEST_INTERVAL_MS,
                ) {
                    StreamCenterVpnGuard.requireInternetAccess()
                    app.get(
                        url = url,
                        headers = requestHeaders,
                        cacheTime = 0,
                        timeout = SEARCH_TIMEOUT_SECONDS,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return ExtSearchPageResult(
                status = StreamCenterExtDomainStatus(
                    domain = domain,
                    availability = StreamCenterExtAvailability.UNAVAILABLE,
                    detail = error.javaClass.simpleName,
                ),
            )
        }
        StreamCenterExtCloudflareSession.rememberResponseCookies(url, response.headers)
        val status = classifyResponse(
            domain = domain,
            httpCode = response.code,
            headers = response.headers,
            html = response.text,
            expectedMarkup = ::isSearchPage,
        )
        if (status.availability != StreamCenterExtAvailability.AVAILABLE) {
            return ExtSearchPageResult(status = status)
        }
        val document = Jsoup.parse(response.text, url)
        val session = parseSearchSession(
            html = response.text,
            document = document,
            refererUrl = url,
            sessionHeaders = StreamCenterExtCloudflareSession.headersWithResponseCookies(
                requestHeaders = requestHeaders,
                responseHeaders = response.headers,
            ),
        )
        return ExtSearchPageResult(
            status = status,
            rows = parseListing(document, domain.baseUrl).map { row ->
                row.copy(
                    searchSession = session,
                    locations = setOf(request.location),
                )
            },
            hasNextPage = extHasNextSearchPage(document, request.page),
        )
    }


    private suspend fun discoverByImdb(
        domain: StreamCenterExtDomain,
        context: StreamCenterTorrentPlaybackContext,
    ): List<ExtListingRow> {
        val imdbId = normalizeImdbId(context.imdbId) ?: return emptyList()
        val base = domain.baseUrl.trimEnd('/')
        val searchPage = fetchExtPage(domain, "$base/browse/?q=$imdbId") ?: return emptyList()
        val mediaPath = extractMediaPath(searchPage.document, context) ?: return emptyList()
        val mediaBaseUrl = sameOriginUrl(domain.baseUrl, mediaPath) ?: return emptyList()
        val mediaPage = fetchExtPage(domain, extMediaPageUrl(mediaBaseUrl, context)) ?: return emptyList()
        val session = parseSearchSession(
            html = mediaPage.html,
            document = mediaPage.document,
            refererUrl = mediaPage.url,
            sessionHeaders = mediaPage.sessionHeaders,
        )
        return parseListing(mediaPage.document, domain.baseUrl).map { row ->
            row.copy(
                searchSession = session,
                locations = setOf(StreamCenterExtContainLocation.TITLE),
            )
        }
    }

    private fun normalizeImdbId(value: String?): String? {
        val digits = value?.trim()?.lowercase(Locale.ROOT)?.removePrefix("tt") ?: return null
        if (digits.length < 5 || !digits.all(Char::isDigit)) return null
        return "tt$digits"
    }

    private fun extractMediaPath(
        document: Document,
        context: StreamCenterTorrentPlaybackContext,
    ): String? {
        val candidates = document.select("a[href]")
            .asSequence()
            .mapNotNull { anchor -> anchor.attr("href").trim().takeIf(String::isNotBlank) }
            .mapNotNull { href ->
                EXT_MEDIA_PATH_REGEX.find(href.substringBefore('?'))
                    ?.let { match -> href to match.groupValues[1].lowercase(Locale.ROOT) }
            }
            .distinctBy { entry -> entry.first }
            .toList()
        if (candidates.isEmpty()) return null
        val preferredType = if (context.isMovie && context.episode == null) "m" else "s"
        return (candidates.firstOrNull { entry -> entry.second == preferredType } ?: candidates.first())
            .first
    }

    private fun extMediaPageUrl(
        mediaBaseUrl: String,
        context: StreamCenterTorrentPlaybackContext,
    ): String {
        val base = mediaBaseUrl.trimEnd('/')
        val parameters = buildList {
            val episode = context.episode
            if (!context.isMovie && episode != null) {
                val coordinates = context.episodeCoordinatesForSearch()
                val canonical = coordinates
                    .firstOrNull { coordinate ->
                        coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.SEASON &&
                            (coordinate.season ?: 0) > 0
                    }
                    ?: coordinates.firstOrNull { coordinate ->
                        coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.LOCAL
                    }
                val season = canonical?.season?.takeIf { value -> value > 0 }
                    ?: context.season?.takeIf { value -> value > 0 }
                    ?: 1
                val resolvedEpisode = canonical?.episode?.takeIf { value -> value > 0 } ?: episode
                add("s=$season")
                add("e=$resolvedEpisode")
            }
            add("sort=seeds")
            add("order=desc")
            add("page_size=$EXT_PAGE_SIZE")
        }
        return "$base/torrents/?${parameters.joinToString("&")}"
    }

    private suspend fun fetchExtPage(
        domain: StreamCenterExtDomain,
        url: String,
    ): ExtFetchedPage? {
        val requestHeaders = StreamCenterExtCloudflareSession.requestHeaders(domain.baseUrl)
        val response = try {
            retryTransientRequest {
                StreamCenterTorrentRequestGate.rateLimited(
                    sourceKey = requestGateKey(domain),
                    minimumIntervalMs = REQUEST_INTERVAL_MS,
                ) {
                    StreamCenterVpnGuard.requireInternetAccess()
                    app.get(
                        url = url,
                        headers = requestHeaders,
                        cacheTime = 0,
                        timeout = SEARCH_TIMEOUT_SECONDS,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return null
        }
        StreamCenterExtCloudflareSession.rememberResponseCookies(url, response.headers)
        val status = classifyResponse(
            domain = domain,
            httpCode = response.code,
            headers = response.headers,
            html = response.text,
            expectedMarkup = ::isSearchPage,
        )
        if (status.availability != StreamCenterExtAvailability.AVAILABLE) return null
        return ExtFetchedPage(
            document = Jsoup.parse(response.text, url),
            html = response.text,
            url = url,
            sessionHeaders = StreamCenterExtCloudflareSession.headersWithResponseCookies(
                requestHeaders = requestHeaders,
                responseHeaders = response.headers,
            ),
        )
    }

    private data class ExtFetchedPage(
        val document: Document,
        val html: String,
        val url: String,
        val sessionHeaders: Map<String, String>,
    )

    private fun hasCandidateBudget(
        rows: List<ExtListingRow>,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        desiredResults: Int?,
    ): Boolean {
        val eligibleCount = mergeListingRows(rows).count { row ->
            row.isEligibleForDiscovery(context, filters)
        }
        return extShouldStopDiscovery(eligibleCount, desiredResults)
    }

    private fun mergeListingRows(rows: List<ExtListingRow>): List<ExtListingRow> {
        val merged = linkedMapOf<String, ExtListingRow>()
        rows.forEach { row ->
            val key = row.deduplicationKey()
            val current = merged[key]
            merged[key] = if (current == null) {
                row
            } else {
                current.copy(
                    locations = current.locations + row.locations,
                    searchSession = current.searchSession ?: row.searchSession,
                )
            }
        }
        return merged.values.toList()
    }

    private fun ExtListingRow.isEligibleForDiscovery(
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): Boolean {
        val metadata = toCandidateMetadata()
        if (StreamCenterExtContainLocation.TITLE in locations && metadata.isEligibleFor(context, filters)) {
            return true
        }
        if (StreamCenterExtContainLocation.FILES !in locations || !filters.hasValidBounds()) return false
        return true
    }

    private fun ExtListingRow.requiresFileInspection(
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): Boolean = StreamCenterTorrentMatchPolicy.isBatchCandidate(title, context) ||
        (
            StreamCenterExtContainLocation.FILES in locations &&
                (
                    StreamCenterExtContainLocation.TITLE !in locations ||
                        !toCandidateMetadata().isEligibleFor(context, filters)
                    )
            )

    private fun ExtListingRow.canLoadWithoutNetwork(
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): Boolean = StreamCenterTorrentMagnet.infoHash(hash) != null &&
        !requiresFileInspection(context, filters)

    private suspend fun loadCandidate(
        domain: StreamCenterExtDomain,
        row: ExtListingRow,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): StreamCenterTorrentCandidate? {
        val listingInfoHash = StreamCenterTorrentMagnet.infoHash(row.hash)
        val needsFileInspection = row.requiresFileInspection(context, filters)
        val headers = row.searchSession?.sessionHeaders
            ?: StreamCenterExtCloudflareSession.requestHeaders(domain.baseUrl)
        val searchMagnet = if (listingInfoHash == null) {
            row.searchSession?.let { session -> requestSearchMagnet(domain, row, session, headers) }
        } else {
            null
        }
        val detailHeaders = searchMagnet?.sessionHeaders ?: headers
        val detail = if (
            needsFileInspection || (listingInfoHash == null && searchMagnet == null)
        ) {
            loadDetails(
                domain = domain,
                row = row,
                headers = detailHeaders,
                resolveMagnet = listingInfoHash == null && searchMagnet == null,
            )
        } else {
            null
        }
        val resolvedMagnet = searchMagnet?.url ?: detail?.magnetUrl
        val infoHash = listingInfoHash ?: StreamCenterTorrentMagnet.infoHash(resolvedMagnet) ?: return null
        val candidate = StreamCenterTorrentCandidate(
            title = row.title,
            infoHash = infoHash,
            magnetUrl = resolvedMagnet,
            size = row.size,
            sizeBytes = row.sizeBytes,
            seeders = row.seeders,
            leechers = row.leechers,
            extReleaseSourceId = row.extReleaseSourceId,
            availableFiles = detail?.availableFiles,
        )
        return if (needsFileInspection) {
            StreamCenterTorrentBatchResolver.resolve(candidate, context).candidate
        } else {
            candidate
        }
    }

    private suspend fun loadDetails(
        domain: StreamCenterExtDomain,
        row: ExtListingRow,
        headers: Map<String, String>,
        resolveMagnet: Boolean,
    ): ExtDetail? {
        val response = try {
            retryTransientRequest {
                StreamCenterTorrentRequestGate.rateLimited(
                    sourceKey = requestGateKey(domain),
                    minimumIntervalMs = REQUEST_INTERVAL_MS,
                ) {
                    StreamCenterVpnGuard.requireInternetAccess()
                    app.get(
                        url = row.detailUrl,
                        headers = headers,
                        cacheTime = 0,
                        timeout = DETAIL_TIMEOUT_SECONDS,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        StreamCenterExtCloudflareSession.rememberResponseCookies(row.detailUrl, response.headers)
        if (response.code !in 200..299) return null
        val document = Jsoup.parse(response.text, row.detailUrl)
        val detailHeaders = StreamCenterExtCloudflareSession.headersWithResponseCookies(
            requestHeaders = headers,
            responseHeaders = response.headers,
        )
        return ExtDetail(
            magnetUrl = if (resolveMagnet) {
                inlineMagnet(response.text)
                    ?: requestDetailMagnet(domain, row.detailUrl, detailHeaders, response.text, document)
            } else {
                null
            },
            availableFiles = parseFiles(document),
        )
    }

    private suspend fun requestSearchMagnet(
        domain: StreamCenterExtDomain,
        row: ExtListingRow,
        session: ExtSearchSession,
        headers: Map<String, String>,
    ): ExtMagnetResponse? {
        val torrentId = row.torrentId ?: return null
        val timestamp = System.currentTimeMillis() / 1_000L
        val response = try {
            StreamCenterTorrentRequestGate.rateLimited(
                sourceKey = requestGateKey(domain),
                minimumIntervalMs = REQUEST_INTERVAL_MS,
            ) {
                StreamCenterVpnGuard.requireInternetAccess()
                app.post(
                    url = "${domain.baseUrl}/ajax/getSearchMagnet.php",
                    headers = headers + mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Referer" to session.refererUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                    data = buildMap {
                        put("torrent_id", torrentId)
                        put("hash", row.hash.orEmpty())
                        put("name", row.searchDataName)
                        put("timestamp", timestamp.toString())
                        put("hmac", sha256("$torrentId|$timestamp|${session.pageToken}"))
                        put("sessid", session.csrfToken)
                    },
                    cacheTime = 0,
                    timeout = DETAIL_TIMEOUT_SECONDS,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        StreamCenterExtCloudflareSession.rememberResponseCookies(domain.baseUrl, response.headers)
        if (response.code !in 200..299) return null
        val magnetUrl = parseMagnetPayload(response.text) ?: return null
        return ExtMagnetResponse(
            url = magnetUrl,
            sessionHeaders = StreamCenterExtCloudflareSession.headersWithResponseCookies(
                requestHeaders = headers,
                responseHeaders = response.headers,
            ),
        )
    }

    private suspend fun requestDetailMagnet(
        domain: StreamCenterExtDomain,
        detailUrl: String,
        headers: Map<String, String>,
        html: String,
        document: Document,
    ): String? {
        val pageToken = PAGE_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val csrfToken = document.selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: CSRF_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val torrentId = document.selectFirst(".download-btn-magnet[data-id], .download-btn-torrent[data-id]")
            ?.attr("data-id")
            ?.trim()
            ?.takeIf { value -> value.all(Char::isDigit) }
            ?: return null
        val timestamp = System.currentTimeMillis() / 1_000L
        val response = try {
            StreamCenterTorrentRequestGate.rateLimited(
                sourceKey = requestGateKey(domain),
                minimumIntervalMs = REQUEST_INTERVAL_MS,
            ) {
                StreamCenterVpnGuard.requireInternetAccess()
                app.post(
                    url = "${domain.baseUrl}/ajax/getTorrentMagnet.php",
                    headers = headers + mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Referer" to detailUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                    data = mapOf(
                        "torrent_id" to torrentId,
                        "download_type" to "magnet",
                        "timestamp" to timestamp.toString(),
                        "hmac" to sha256("$torrentId|$timestamp|$pageToken"),
                        "sessid" to csrfToken,
                    ),
                    cacheTime = 0,
                    timeout = DETAIL_TIMEOUT_SECONDS,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        StreamCenterExtCloudflareSession.rememberResponseCookies(detailUrl, response.headers)
        if (response.code !in 200..299) return null
        return parseMagnetPayload(response.text)
    }

    private fun parseMagnetPayload(value: String): String? {
        val payload = runCatching { JSONObject(value) }.getOrNull() ?: return null
        if (payload.has("success") && !payload.optBoolean("success")) return null
        val nested = payload.optJSONObject("data")
        val magnetUrl = listOf("url", "magnet", "magnet_url")
            .asSequence()
            .flatMap { key -> sequenceOf(payload.optString(key), nested?.optString(key)) }
            .filterNotNull()
            .map(String::trim)
            .firstOrNull { candidate -> candidate.startsWith("magnet:", ignoreCase = true) }
        if (magnetUrl != null) return magnetUrl
        val hashValue = sequenceOf(payload.optString("hash"), nested?.optString("hash"))
            .filterNotNull()
            .firstOrNull { candidate -> candidate.isNotBlank() }
        return StreamCenterTorrentMagnet.infoHash(hashValue)
            ?.let { hash -> "magnet:?xt=urn:btih:$hash" }
    }

    private fun parseListing(document: Document, baseUrl: String): List<ExtListingRow> = buildList {
        val listingRows = document.select(
            "table.table-striped.table-hover tbody tr, table.table-striped tbody tr, " +
                "table#torrents tbody tr, tr[data-torrent-id]",
        ).distinct()
        for (row in listingRows) {
            val titleLink = extListingTitleLink(row) ?: continue
            val title = extListingTitle(titleLink)
            if (title.isBlank()) continue
            val detailUrl = sameOriginUrl(baseUrl, titleLink.attr("href")) ?: continue
            val magnetButton = row.selectFirst("a.search-magnet-btn")
            val sourceElement = metricElement(row, "Source")
            add(
                ExtListingRow(
                    title = title,
                    detailUrl = detailUrl,
                    torrentId = magnetButton?.attr("data-id")?.trim()?.takeIf(String::isNotBlank),
                    hash = magnetButton?.attr("data-hash")?.trim()?.takeIf(String::isNotBlank)
                        ?: titleLink.attr("data-hash").trim().takeIf(String::isNotBlank),
                    size = metricText(row, "Size"),
                    sizeBytes = StreamCenterTorrentMetadata.parseSizeBytes(metricText(row, "Size")),
                    seeders = parsePositiveInt(metricText(row, "Seeds")),
                    leechers = parsePositiveInt(metricText(row, "Leechs")),
                    extReleaseSourceId = sourceId(sourceElement),
                    searchDataName = magnetButton?.attr("data-name").orEmpty(),
                ),
            )
        }
    }

    private fun ExtListingRow.toCandidateMetadata(): StreamCenterTorrentCandidate =
        StreamCenterTorrentCandidate(
            title = title,
            size = size,
            sizeBytes = sizeBytes,
            seeders = seeders,
            leechers = leechers,
            extReleaseSourceId = extReleaseSourceId,
        )

    private fun isSearchPage(document: Document): Boolean = document.selectFirst(
        "table.table-striped, .torrent-title-link, .search-magnet-btn, " +
            "form[action*=browse], input[name=q], .search-form",
    ) != null

    private fun metricElement(row: Element, label: String): Element? = row
        .select(".add-block-wrapper")
        .firstOrNull { wrapper ->
            wrapper.selectFirst(".add-block")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }

    private fun metricText(row: Element, label: String): String? = metricElement(row, label)
        ?.select("span:not(.add-block)")
        ?.joinToString(" ") { element -> element.text().trim() }
        ?.let(::cleanDisplayText)
        ?.takeIf(String::isNotBlank)

    private fun sourceId(sourceElement: Element?): Int? {
        val explicitId = listOfNotNull(
            sourceElement?.attr("data-source-id"),
            sourceElement?.selectFirst("[data-source-id]")?.attr("data-source-id"),
            sourceElement?.selectFirst("a[data-id]")?.attr("data-id"),
        ).firstNotNullOfOrNull { value -> value.trim().toIntOrNull() }
        if (StreamCenterExtReleaseSources.byId(explicitId) != null) return explicitId
        val marker = listOfNotNull(
            sourceElement?.selectFirst("img")?.attr("src"),
            sourceElement?.selectFirst("a")?.attr("href"),
            sourceElement?.text(),
        ).joinToString(" ") { value ->
            runCatching {
                val uri = URI(value)
                listOfNotNull(uri.path, uri.query).joinToString(" ").ifBlank { value }
            }.getOrDefault(value)
        }.lowercase(Locale.ROOT)
        return sourceMarkers.entries
            .sortedByDescending { entry -> entry.key.length }
            .firstOrNull { (needle, _) -> needle in marker }
            ?.value
    }

    private fun parseSearchSession(
        html: String,
        document: Document,
        refererUrl: String,
        sessionHeaders: Map<String, String>,
    ): ExtSearchSession? {
        val pageToken = SEARCH_PAGE_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val csrfToken = document.selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: CSRF_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        return ExtSearchSession(pageToken, csrfToken, refererUrl, sessionHeaders)
    }

    private fun parseFiles(document: Document): List<StreamCenterTorrentFile>? {
        var fileIndex = 0
        return buildList {
            for (row in document.select("#torrent_files table tr")) {
                val fileCell = row.selectFirst("td.file-name-line-td") ?: continue
                val currentIndex = listOf(
                    row.attr("data-file-index"),
                    fileCell.attr("data-file-index"),
                    row.attr("data-index"),
                ).firstNotNullOfOrNull { value -> value.trim().toIntOrNull() }
                    ?.takeIf { index -> index in 0..MAX_FILE_INDEX }
                    ?: fileIndex
                fileIndex = maxOf(fileIndex + 1, currentIndex + 1)
                val path = cleanDisplayText(fileCell.text()).takeIf(String::isNotBlank)
                    ?: continue
                val sizeText = row.select("td.file-size-td div.file-size")
                    .map { cell -> cell.text().trim() }
                    .filter(String::isNotBlank)
                    .lastOrNull()
                add(
                    StreamCenterTorrentFile(
                        index = currentIndex,
                        path = path,
                        sizeBytes = StreamCenterTorrentMetadata.parseSizeBytes(sizeText),
                    ),
                )
                if (size >= MAX_FILE_ROWS) break
            }
        }.takeIf(List<StreamCenterTorrentFile>::isNotEmpty)
    }

    private fun inlineMagnet(html: String): String? = MAGNET_REGEX.find(html)
        ?.value
        ?.replace("&amp;", "&")
        ?.takeIf { value -> StreamCenterTorrentMagnet.infoHash(value) != null }

    private fun classifyResponse(
        domain: StreamCenterExtDomain,
        httpCode: Int,
        headers: Headers,
        html: String,
        expectedMarkup: (Document) -> Boolean,
    ): StreamCenterExtDomainStatus {
        val challenge = headers["Cf-Mitigated"]?.contains("challenge", ignoreCase = true) == true ||
            html.contains("cf-chl", ignoreCase = true) ||
            html.contains("just a moment", ignoreCase = true)
        val availability = when {
            httpCode == 429 -> StreamCenterExtAvailability.RATE_LIMITED
            httpCode in 200..299 && expectedMarkup(Jsoup.parse(html)) -> StreamCenterExtAvailability.AVAILABLE
            httpCode == 401 || httpCode == 403 || challenge -> StreamCenterExtAvailability.VERIFICATION_REQUIRED
            httpCode in 200..299 -> StreamCenterExtAvailability.INVALID_RESPONSE
            else -> StreamCenterExtAvailability.UNAVAILABLE
        }
        if (availability == StreamCenterExtAvailability.RATE_LIMITED) {
            val retryAfterMs = headers["Retry-After"]
                ?.trim()
                ?.toLongOrNull()
                ?.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
                ?.times(1_000L)
                ?: RATE_LIMIT_COOLDOWN_MS
            StreamCenterTorrentRequestGate.startCooldown(requestGateKey(domain), retryAfterMs)
        }
        return StreamCenterExtDomainStatus(
            domain = domain,
            availability = availability,
            httpCode = httpCode,
            detail = if (availability == StreamCenterExtAvailability.RATE_LIMITED) {
                "retry_dopo_${StreamCenterTorrentRequestGate.remainingCooldownMs(requestGateKey(domain))}ms"
            } else {
                null
            },
        )
    }

    private fun searchSchedule(
        plans: List<StreamCenterExtSearchPlan>,
        location: StreamCenterExtContainLocation,
    ): List<Pair<StreamCenterExtSearchPlan, StreamCenterExtContainLocation>> = when (location) {
        StreamCenterExtContainLocation.TITLE -> plans.map { plan -> plan to StreamCenterExtContainLocation.TITLE }
        StreamCenterExtContainLocation.FILES -> plans.map { plan -> plan to StreamCenterExtContainLocation.FILES }
        StreamCenterExtContainLocation.TITLE_AND_FILES -> buildList {
            val titlePlans = plans.take(TITLE_AND_FILES_TITLE_BUDGET)
            val filePlans = plans.asSequence()
                .filterNot(StreamCenterExtSearchPlan::batchSearch)
                .take(TITLE_AND_FILES_FILE_BUDGET)
                .toList()
            titlePlans.forEachIndexed { index, plan ->
                add(plan to StreamCenterExtContainLocation.TITLE)
                if (index < TITLE_AND_FILES_INTERLEAVED_FILE_REQUESTS) {
                    filePlans.getOrNull(index)?.let { filePlan ->
                        add(filePlan to StreamCenterExtContainLocation.FILES)
                    }
                }
            }
            filePlans.drop(TITLE_AND_FILES_INTERLEAVED_FILE_REQUESTS).forEach { plan ->
                add(plan to StreamCenterExtContainLocation.FILES)
            }
        }
    }

    private fun sameOriginUrl(baseUrl: String, href: String): String? {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val target = runCatching { base.resolve(href.trim()) }.getOrNull() ?: return null
        if (target.scheme !in setOf("http", "https")) return null
        if (!target.host.equals(base.host, ignoreCase = true)) return null
        return target.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun requestGateKey(domain: StreamCenterExtDomain): String =
        "torrent-ext-${domain.preferenceValue}"

    private suspend fun retryTransientRequest(block: suspend () -> NiceResponse): NiceResponse {
        var lastFailure: Throwable? = null
        repeat(TRANSIENT_REQUEST_ATTEMPTS) { attempt ->
            try {
                val response = block()
                val retryableStatus = response.code == 408 || response.code in 500..599
                if (!retryableStatus || attempt == TRANSIENT_REQUEST_ATTEMPTS - 1) return response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt == TRANSIENT_REQUEST_ATTEMPTS - 1) throw error
            }
            delay(RETRY_BASE_DELAY_MS * (attempt + 1))
        }
        throw lastFailure ?: IllegalStateException("Richiesta EXT non completata")
    }

    private data class ExtSearchRequest(
        val query: String,
        val category: StreamCenterExtCategory,
        val location: StreamCenterExtContainLocation,
        val filters: StreamCenterTorrentFilterSettings,
        val releaseSources: List<StreamCenterExtReleaseSource>,
        val page: Int = 1,
    ) {
        fun toUrl(baseUrl: String): String {
            val parameters = buildList {
                add("q" to query)
                if (page > 1) add("page" to page.toString())
                add("page_size" to EXT_PAGE_SIZE.toString())
                add("contain_type" to EXT_DEFAULT_CONTAIN_TYPE)
                add("contain_in" to if (location == StreamCenterExtContainLocation.FILES) "1" else "0")
                add("cat" to category.id.toString())
                if (location != StreamCenterExtContainLocation.FILES) {
                    filters.minimumSizeBytes?.let { bytes -> addAll(sizeParameters(bytes, true)) }
                    filters.maximumSizeBytes?.let { bytes -> addAll(sizeParameters(bytes, false)) }
                }
                filters.minimumSeeders?.let { seeders -> add("seeders_min" to seeders.toString()) }
                filters.maximumSeeders?.let { seeders -> add("seeders_max" to seeders.toString()) }
                filters.excludedTerms.takeIf(String::isNotBlank)?.let { terms ->
                    add("exclude_name" to terms)
                    add("exclude_type" to "0")
                }
                val categorySourceIds = StreamCenterExtReleaseSources.forCategory(category)
                    .mapTo(linkedSetOf()) { source -> source.id }
                val selectedSourceIds = releaseSources.mapTo(linkedSetOf()) { source -> source.id }
                if (selectedSourceIds != categorySourceIds) {
                    releaseSources.forEach { source -> add("source[]" to source.id.toString()) }
                }
                add("sort" to "seeds")
                add("order" to "desc")
            }
            return parameters.joinToString(
                separator = "&",
                prefix = "${baseUrl.trimEnd('/')}/browse/?",
            ) { (key, value) -> "${encodeTorrentPathValue(key)}=${encodeTorrentPathValue(value)}" }
        }

        private fun sizeParameters(bytes: Long, minimum: Boolean): List<Pair<String, String>> {
            if (bytes <= 0L) return emptyList()
            val unit = if (bytes >= GIBIBYTE) 2 else 0
            val divisor = if (unit == 2) GIBIBYTE.toDouble() else MEBIBYTE.toDouble()
            val amount = bytes.toDouble() / divisor
            val formatted = "%.3f".format(Locale.ROOT, amount).trimEnd('0').trimEnd('.')
            val prefix = if (minimum) "size_from" else "size_to"
            return listOf(
                prefix to formatted,
                "${prefix}_format" to unit.toString(),
            )
        }
    }

    private data class ExtSearchPageResult(
        val status: StreamCenterExtDomainStatus,
        val rows: List<ExtListingRow> = emptyList(),
        val hasNextPage: Boolean = false,
    )

    private data class SearchCacheKey(
        val domain: StreamCenterExtDomain,
        val titles: List<String>,
        val englishTitle: String?,
        val japaneseTitle: String?,
        val year: Int?,
        val isAnime: Boolean,
        val isMovie: Boolean,
        val season: Int?,
        val episode: Int?,
        val episodeCoordinates: List<StreamCenterTorrentEpisodeCoordinate>,
        val filters: StreamCenterTorrentFilterSettings,
        val desiredResults: Int?,
        val imdbId: String?,
    )

    private class SearchFlight(
        val mutex: Mutex = Mutex(),
        val users: AtomicInteger = AtomicInteger(),
    )

    private data class CachedSearchOutcome(
        val outcome: StreamCenterExtSearchOutcome,
        val storedAtMs: Long,
    )

    private data class ExtSearchSession(
        val pageToken: String,
        val csrfToken: String,
        val refererUrl: String,
        val sessionHeaders: Map<String, String>,
    )

    private data class ExtDetail(
        val magnetUrl: String?,
        val availableFiles: List<StreamCenterTorrentFile>?,
    )

    private data class ExtMagnetResponse(
        val url: String,
        val sessionHeaders: Map<String, String>,
    )

    private data class ExtListingRow(
        val title: String,
        val detailUrl: String,
        val torrentId: String?,
        val hash: String?,
        val size: String?,
        val sizeBytes: Long?,
        val seeders: Int?,
        val leechers: Int?,
        val extReleaseSourceId: Int?,
        val searchDataName: String,
        val searchSession: ExtSearchSession? = null,
        val locations: Set<StreamCenterExtContainLocation> = emptySet(),
    ) {
        fun deduplicationKey(): String = StreamCenterTorrentMagnet.infoHash(hash)
            ?: torrentId
            ?: "$detailUrl|${title.lowercase(Locale.ROOT)}"
    }

    private data class RankedExtListingRow(
        val row: ExtListingRow,
        val ranking: StreamCenterTorrentCandidateRanking,
    )

    private const val REQUEST_INTERVAL_MS = 500L
    private const val POSITIVE_SEARCH_CACHE_TTL_MS = 90_000L
    private const val MAX_POSITIVE_SEARCH_CACHE_ENTRIES = 8
    private const val TRANSIENT_REQUEST_ATTEMPTS = 2
    private const val RETRY_BASE_DELAY_MS = 250L
    private const val AVAILABILITY_TIMEOUT_SECONDS = 8L
    private const val SEARCH_TIMEOUT_SECONDS = 10L
    private const val DETAIL_TIMEOUT_SECONDS = 9L
    private const val RATE_LIMIT_COOLDOWN_MS = 20_000L
    private const val MAX_RETRY_AFTER_SECONDS = 600L
    private const val CANDIDATE_REQUEST_CONCURRENCY = 3
    private const val CLIENT_RETURN_RESERVE_MS = 2_000L
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private const val SEARCH_PHASE_BUDGET_DIVISOR = 4L
    private const val MIN_SEARCH_PHASE_BUDGET_MS = 10_000L
    private const val MAX_SEARCH_PHASE_BUDGET_MS = 20_000L
    private const val TITLE_AND_FILES_TITLE_BUDGET = 8
    private const val TITLE_AND_FILES_FILE_BUDGET = 4
    private const val TITLE_AND_FILES_INTERLEAVED_FILE_REQUESTS = 2
    private const val MAX_FILE_ROWS = 2_000
    private const val MAX_FILE_INDEX = 100_000
    private const val MEBIBYTE = 1_048_576L
    private const val GIBIBYTE = 1_073_741_824L
    private val SEARCH_PAGE_TOKEN_REGEX = Regex(
        """(?:searchPageToken|pageToken)\s*=\s*(?:\\'|')([a-fA-F0-9]{32})(?:\\'|')""",
    )
    private val PAGE_TOKEN_REGEX = Regex(
        """window\.pageToken\s*=\s*(?:\\'|')([a-fA-F0-9]{32})(?:\\'|')""",
    )
    private val CSRF_TOKEN_REGEX = Regex(
        """(?:window\.csrfToken|csrfToken)\s*=\s*(?:\\'|')([a-fA-F0-9]{32})(?:\\'|')""",
    )
    private val MAGNET_REGEX = Regex(
        """magnet:\?[^\s\"'<>]+""",
        RegexOption.IGNORE_CASE,
    )
    private val sourceMarkers = mapOf(
        "1337x" to 1,
        "eztv" to 2,
        "yts" to 3,
        "thepiratebay" to 4,
        "ilcorsaronero" to 5,
        "rarbg" to 7,
        "ext" to 8,
        "nyaa" to 9,
        "oxtorrent" to 10,
        "dontorrent" to 11,
        "sktorrent" to 12,
        "polskie" to 13,
        "yggtorrent" to 14,
        "rutracker" to 16,
    )
}

internal fun extListingTitle(titleLink: Element): String = cleanDisplayText(titleLink.text())

internal fun extListingTitleLink(row: Element): Element? =
    row.selectFirst("a.torrent-title-link") ?: row.selectFirst("td.text-left .float-left a")

internal fun extHasNextSearchPage(document: Document, currentPage: Int): Boolean = document
    .select(".page-select-block ul.pages a[href*='page=']")
    .asSequence()
    .mapNotNull { link -> EXT_PAGE_QUERY_PARAMETER_REGEX.find(link.attr("href")) }
    .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
    .any { page -> page > currentPage }

internal fun <T> extCandidateRowsToLoad(rows: List<T>, desiredResults: Int?): List<T> {
    val target = desiredResults?.coerceAtLeast(1) ?: return rows
    return rows.take(maxOf(target + EXT_CANDIDATE_REPLACEMENT_BUDGET, target * 2))
}

internal fun extShouldStopDiscovery(eligibleCount: Int, desiredResults: Int?): Boolean {
    val target = desiredResults?.coerceAtLeast(1) ?: return false
    return eligibleCount >= maxOf(target, target * 2)
}

private val EXT_PAGE_QUERY_PARAMETER_REGEX = Regex("""(?:[?&]|&amp;)page=(\d+)""")
private val EXT_MEDIA_PATH_REGEX = Regex("""-([sm])\d+/?$""", RegexOption.IGNORE_CASE)
private const val EXT_CANDIDATE_REPLACEMENT_BUDGET = 4
private const val EXT_PAGE_SIZE = 100
