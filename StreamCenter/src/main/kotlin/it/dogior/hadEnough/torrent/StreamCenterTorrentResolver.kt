package it.dogior.hadEnough.torrent

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.util.StreamCenterLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal object StreamCenterTorrentResolver {
    suspend fun load(
        domains: List<StreamCenterExtDomain>,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        callback: (ExtractorLink) -> Unit,
        performanceMode: Boolean,
        logTabName: String,
    ): Boolean {
        if (domains.isEmpty()) return false
        val startedAt = System.currentTimeMillis()
        var timedOut = false
        var finalOutcome: StreamCenterExtSearchOutcome? = null
        var resultOutcome: StreamCenterExtSearchOutcome? = null
        val attemptedDomains = mutableListOf<Map<String, Any?>>()
        val resultLimit = filters.resultLimit.coerceAtLeast(1)
        val resolvedByHash = LinkedHashMap<String, ResolvedCandidate>()
        val sourceTimeoutMs = if (performanceMode) {
            TORRENT_PERFORMANCE_TOTAL_TIMEOUT_MS
        } else {
            TORRENT_SOURCE_TIMEOUT_MS
        }

        fun resolvedCandidate(candidate: StreamCenterTorrentCandidate): ResolvedCandidate? {
            if (!candidate.isEligibleFor(context, filters)) return null
            val magnet = StreamCenterTorrentMagnet.build(candidate) ?: return null
            val infoHash = StreamCenterTorrentMagnet.infoHash(magnet) ?: return null
            return ResolvedCandidate(candidate, magnet, infoHash)
        }

        try {
            withTimeoutOrNull(sourceTimeoutMs) {
                for ((domainIndex, domain) in domains.withIndex()) {
                    if (resolvedByHash.size >= resultLimit) break
                    val remainingResults = (resultLimit - resolvedByHash.size).coerceAtLeast(1)
                    StreamCenterLogger.logMetadata(
                        tabName = logTabName,
                        source = "Torrent · EXT",
                        action = "Ricerca EXT avviata",
                        metadata = searchStartMetadata(domain, context, filters),
                    )
                    val attemptBudgetMs = domainAttemptBudgetMs(
                        startedAt = startedAt,
                        domainIndex = domainIndex,
                        domainCount = domains.size,
                        sourceTimeoutMs = sourceTimeoutMs,
                    )
                    val clientBudgetMs = (attemptBudgetMs - DOMAIN_RESULT_RESERVE_MS)
                        .takeIf { budget -> budget > 0L }
                        ?: attemptBudgetMs
                    val outcome = withTimeoutOrNull(attemptBudgetMs) {
                        StreamCenterExtTorrentClient.search(
                            domain = domain,
                            context = context,
                            filters = filters,
                            desiredResults = remainingResults,
                            timeBudgetMs = clientBudgetMs,
                        )
                    } ?: StreamCenterExtSearchOutcome(
                        domain = domain,
                        status = StreamCenterExtDomainStatus(
                            domain = domain,
                            availability = StreamCenterExtAvailability.UNAVAILABLE,
                            detail = "timeout_dominio_${attemptBudgetMs}ms",
                        ),
                    )
                    val domainResolved = outcome.candidates.mapNotNull { candidate ->
                        resolvedCandidate(candidate)
                    }
                    domainResolved.forEach { resolved ->
                        if (!resolvedByHash.containsKey(resolved.infoHash)) {
                            resolvedByHash[resolved.infoHash] = resolved
                        }
                    }
                    if (domainResolved.isNotEmpty()) {
                        resultOutcome = outcome
                    }
                    val shouldTryNextDomain =
                        outcome.shouldTryNextDomain || domainResolved.isEmpty()
                    attemptedDomains += mapOf(
                        "dominio" to domain.baseUrl,
                        "ruolo" to domain.title,
                        "stato" to outcome.status.availability.name.lowercase(Locale.ROOT),
                        "http" to outcome.status.httpCode,
                        "query" to outcome.executedPlans.size,
                        "budget_dominio_ms" to attemptBudgetMs,
                        "budget_client_ms" to clientBudgetMs,
                        "richieste_ricerca" to outcome.requestedSearchPageCount,
                        "candidati_grezzi" to outcome.rawCandidateCount,
                        "righe_idonee" to outcome.eligibleRowCount,
                        "richieste_magnet" to outcome.requestedMagnetCount,
                        "candidati_con_magnet" to outcome.candidates.size,
                        "cache" to outcome.fromCache,
                        "parziale" to outcome.partial,
                        "magnet_pronti" to domainResolved.size,
                    )
                    StreamCenterLogger.logMetadata(
                        tabName = logTabName,
                        source = "Torrent · EXT",
                        action = "Tentativo dominio EXT completato",
                        metadata = mapOf(
                            "dominio" to domain.baseUrl,
                            "stato" to outcome.status.availability.name.lowercase(Locale.ROOT),
                            "http" to outcome.status.httpCode,
                            "dettaglio" to outcome.status.detail,
                            "budget_dominio_ms" to attemptBudgetMs,
                            "budget_client_ms" to clientBudgetMs,
                            "piani_ricerca" to outcome.executedPlans.map { plan ->
                                mapOf(
                                    "caratteri_query" to plan.query.length,
                                    "strategia" to plan.reason,
                                    "batch" to plan.batchSearch,
                                )
                            },
                            "campi_ricercati" to outcome.appliedLocations.map { it.preferenceValue },
                            "richieste_ricerca" to outcome.requestedSearchPageCount,
                            "candidati_grezzi" to outcome.rawCandidateCount,
                            "righe_idonee" to outcome.eligibleRowCount,
                            "richieste_magnet" to outcome.requestedMagnetCount,
                            "candidati_con_magnet" to outcome.candidates.size,
                            "cache" to outcome.fromCache,
                            "risultati_parziali" to outcome.partial,
                            "magnet_pronti" to domainResolved.size,
                            "fallback_successivo" to shouldTryNextDomain,
                        ),
                        level = outcome.status.availability.logLevel(),
                    )
                    finalOutcome = outcome
                    if (resolvedByHash.size >= resultLimit) break
                    if (shouldTryNextDomain) continue
                    break
                }
            } ?: run { timedOut = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            StreamCenterLogger.logTabError(
                tabName = logTabName,
                action = "Ricerca Torrent EXT non riuscita",
                throwable = error,
                metadata = mapOf("tentativi_dominio" to attemptedDomains),
            )
        }

        val emitDomain = resultOutcome?.domain ?: finalOutcome?.domain
        val ranked = resolvedByHash.values
            .asSequence()
            .map { resolved ->
                RankedResolvedCandidate(
                    resolved = resolved,
                    ranking = StreamCenterTorrentFilterEngine.rank(resolved.candidate, context, filters),
                )
            }
            .sortedWith(resultComparator)
            .take(resultLimit)
            .map(RankedResolvedCandidate::resolved)
            .toList()
        ranked.forEach { resolved ->
            callback(
                newExtractorLink(
                    source = "EXT",
                    name = resolved.candidate.displayName(emitDomain),
                    url = resolved.magnet,
                    type = ExtractorLinkType.MAGNET,
                ) {
                    quality = qualityFromTorrentTitle(
                        listOfNotNull(resolved.candidate.title, resolved.candidate.selectedFileName)
                            .joinToString(" "),
                    )
                },
            )
        }
        val effectiveOutcome = resultOutcome ?: finalOutcome
        StreamCenterLogger.logMetadata(
            tabName = logTabName,
            source = "Torrent · EXT",
            action = when {
                timedOut -> "Ricerca Torrent EXT scaduta"
                ranked.isEmpty() && effectiveOutcome?.status?.availability != StreamCenterExtAvailability.AVAILABLE ->
                    "Ricerca Torrent EXT non riuscita"
                else -> "Ricerca Torrent EXT completata"
            },
            metadata = mapOf(
                "categoria_ext" to context.extCategory().displayName,
                "domini_tentati" to attemptedDomains,
                "dominio_usato" to resultOutcome?.domain?.baseUrl,
                "stato_finale" to effectiveOutcome?.status?.availability?.name?.lowercase(Locale.ROOT),
                "candidati_idonei" to resolvedByHash.size,
                "magnet_validi" to resolvedByHash.size,
                "risultati_emessi" to ranked.size,
                "limite_risultati" to resultLimit,
                "timeout" to timedOut,
                "durata_ms" to (System.currentTimeMillis() - startedAt),
            ),
            level = when {
                timedOut || (ranked.isEmpty() && effectiveOutcome?.status?.availability != StreamCenterExtAvailability.AVAILABLE) ->
                    StreamCenterLogger.Level.WARNING
                else -> StreamCenterLogger.Level.INFO
            },
        )
        return ranked.isNotEmpty()
    }

    private fun domainAttemptBudgetMs(
        startedAt: Long,
        domainIndex: Int,
        domainCount: Int,
        sourceTimeoutMs: Long,
    ): Long {
        val elapsedMs = System.currentTimeMillis() - startedAt
        val remainingMs = (sourceTimeoutMs - elapsedMs).coerceAtLeast(1L)
        if (domainIndex >= domainCount - 1) return remainingMs
        val fallbackReserveMs = minOf(DOMAIN_FALLBACK_RESERVE_MS, remainingMs / 3)
        return (remainingMs - fallbackReserveMs).coerceAtLeast(1L)
    }

    private fun searchStartMetadata(
        domain: StreamCenterExtDomain,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): Map<String, Any?> = mapOf(
        "dominio" to domain.baseUrl,
        "ruolo_dominio" to domain.title,
        "categoria_ext" to context.extCategory().displayName,
        "stagione" to context.season,
        "episodio" to context.episode,
        "numeri_episodio_ricerca" to context.episodeNumbersForSearch(),
        "titoli_disponibili" to context.titles.size,
        "titolo_inglese_disponibile" to !context.englishTitle.isNullOrBlank(),
        "titolo_giapponese_disponibile" to !context.japaneseTitle.isNullOrBlank(),
        "campo_ricerca" to filters.containLocation.preferenceValue,
        "filtro_lingua" to filters.language.preferenceValue,
        "limite_risultati" to filters.resultLimit,
        "dimensione_minima_byte" to filters.minimumSizeBytes,
        "dimensione_massima_byte" to filters.maximumSizeBytes,
        "seed_minimi" to filters.minimumSeeders,
        "seed_massimi" to filters.maximumSeeders,
        "risoluzione_minima" to filters.minimumResolution,
        "escludi_copie_cinema" to filters.excludeCinemaCopies,
        "codec_video_esclusi" to filters.blockedVideoCodecs.map { codec -> codec.displayName },
        "numero_termini_esclusi" to filters.excludedTerms
            .split(',', ';', '\n')
            .count { term -> term.isNotBlank() },
        "sorgenti_ext" to StreamCenterExtReleaseSources.forCategory(context.extCategory()).map { source -> source.title },
        "cookie_cloudflare_presente" to StreamCenterExtCloudflareSession.hasVerifiedClearance(domain.baseUrl),
    )

    private fun StreamCenterTorrentCandidate.displayName(domain: StreamCenterExtDomain?): String {
        val releaseName = StreamCenterExtReleaseSources.byId(extReleaseSourceId)?.title
            ?: domain?.let { endpoint ->
                when (endpoint) {
                    StreamCenterExtDomain.PRIMARY -> "EXT"
                    StreamCenterExtDomain.SECONDARY -> "EXT"
                    StreamCenterExtDomain.PROXY -> "Proxy"
                }
            }
            ?: "EXT"
        val resolution = StreamCenterTorrentMetadata.resolution(
            listOfNotNull(title, selectedFileName).joinToString(" "),
        )
        val codecDetection = StreamCenterTorrentVideoCodecDetector.detect(this)
        val summary = buildList {
            add(releaseName)
            resolution?.let { value -> add("${value}p") }
            this@displayName.size
                ?.takeIf { value -> value.isNotBlank() }
                ?.let(::add)
            seeders?.let { value -> add("🌱 $value") }
            leechers?.let { value -> add("🪱 $value") }
        }.joinToString(" · ")
        val details = buildList {
            add("Titolo: $title")
            selectedFileName?.takeIf { value -> value.isNotBlank() }?.let { fileName ->
                add("File: $fileName")
            }
            codecDetection.codecs
                .takeIf { codecs -> codecs.isNotEmpty() }
                ?.joinToString(" + ") { codec -> codec.displayName }
                ?.let { codecs -> add("Codec: $codecs") }
        }
        return (listOf(summary) + details).joinToString("\n")
    }

    private fun StreamCenterExtAvailability.logLevel(): StreamCenterLogger.Level = when (this) {
        StreamCenterExtAvailability.AVAILABLE -> StreamCenterLogger.Level.INFO
        StreamCenterExtAvailability.VERIFICATION_REQUIRED,
        StreamCenterExtAvailability.RATE_LIMITED -> StreamCenterLogger.Level.WARNING
        StreamCenterExtAvailability.UNAVAILABLE,
        StreamCenterExtAvailability.INVALID_RESPONSE -> StreamCenterLogger.Level.WARNING
    }

    private data class ResolvedCandidate(
        val candidate: StreamCenterTorrentCandidate,
        val magnet: String,
        val infoHash: String,
    )

    private data class RankedResolvedCandidate(
        val resolved: ResolvedCandidate,
        val ranking: StreamCenterTorrentCandidateRanking,
    )

    private val resultComparator = compareByDescending<RankedResolvedCandidate> { ranked ->
        ranked.ranking.languagePriority
    }.thenByDescending { ranked ->
        ranked.ranking.score
    }.thenByDescending { ranked -> ranked.resolved.candidate.seeders ?: -1 }
        .thenBy { ranked -> ranked.resolved.candidate.title.lowercase(Locale.ROOT) }

    private const val DOMAIN_FALLBACK_RESERVE_MS = 4_500L
    private const val DOMAIN_RESULT_RESERVE_MS = 5_000L
}
