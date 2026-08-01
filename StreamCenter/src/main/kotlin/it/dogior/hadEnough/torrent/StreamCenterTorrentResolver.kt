package it.dogior.hadEnough.torrent

import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.util.StreamCenterLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal object StreamCenterTorrentResolver {
    private const val LOG_TAG = "StreamCenterTorrent"
    private const val MAX_RESULTS_PER_SOURCE = 8

    private val clients: Map<String, StreamCenterTorrentSourceClient> = mapOf(
        StreamCenterTorrentSources.SUKEBEI_NYAA_KEY to StreamCenterNyaaTorrentClient,
        StreamCenterTorrentSources.NYAA_KEY to StreamCenterNyaaTorrentClient,
        StreamCenterTorrentSources.TORRENT_GALAXY_KEY to StreamCenterTorrentGalaxyClient,
        StreamCenterTorrentSources.APIBAY_KEY to StreamCenterApiBayTorrentClient,
        StreamCenterTorrentSources.EXT_KEY to StreamCenterExtTorrentClient,
    )

    suspend fun loadSource(
        definition: StreamCenterTorrentSourceDefinition,
        sourceUrl: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        callback: (ExtractorLink) -> Unit,
        stopAfterFirstResult: Boolean = false,
        logTabName: String,
    ): Boolean {
        if (!definition.supports(context)) return false
        val client = clients[definition.key] ?: return false
        val sourceContext = if (definition.key == StreamCenterTorrentSources.SUKEBEI_NYAA_KEY) {
            context
        } else {
            context.copy(japaneseTitle = null)
        }
        val startedAt = System.currentTimeMillis()
        val plannedQueries = if (client === StreamCenterNyaaTorrentClient) {
            StreamCenterTorrentQueryBuilder.buildForNyaa(
                definition,
                sourceContext,
                filters.copy(language = StreamCenterTorrentLanguageFilter.ANY),
            )
        } else {
            emptyList()
        }
        StreamCenterLogger.logMetadata(
            tabName = logTabName,
            source = definition.title,
            action = "Ricerca Torrent avviata",
            metadata = mapOf(
                "fonte_torrent" to definition.title,
                "tipo_contenuto" to when {
                    sourceContext.isAnime -> "anime"
                    sourceContext.isMovie -> "film"
                    else -> "serie_tv"
                },
                "stagione" to sourceContext.season,
                "episodio" to sourceContext.episode,
                "numeri_episodio_ricerca" to sourceContext.episodeNumbersForSearch(),
                "titoli_latini_disponibili" to sourceContext.titles.size,
                "titolo_inglese_tmdb_disponibile" to !sourceContext.englishTitle.isNullOrBlank(),
                "titolo_giapponese_disponibile" to !sourceContext.japaneseTitle.isNullOrBlank(),
                "query_previste" to plannedQueries.size,
                "query_giapponesi_previste" to plannedQueries.count(::containsJapaneseScript),
                "cloudflare_verificato" to if (
                    definition.key == StreamCenterTorrentSources.EXT_KEY
                ) {
                    StreamCenterExtCloudflareSession.isReady(sourceUrl)
                } else {
                    null
                },
                "filtro_lingua" to filters.language.preferenceValue,
                "risoluzione_minima" to filters.minimumResolution,
                "seed_minimi" to filters.minimumSeeders,
                "dimensione_massima_byte" to filters.maximumSizeBytes,
                "escludi_copie_cinema" to filters.excludeCinemaCopies,
                "primo_risultato_sufficiente" to stopAfterFirstResult,
            ),
        )

        var timedOut = false
        var failure: Throwable? = null
        var nyaaDiagnostics: StreamCenterNyaaSearchDiagnostics? = null
        var executedLanguagePasses = 0
        var italianPriorityCandidateCount = 0
        var languageFallbackAttempted = false
        var languageFallbackUsed = false
        var appliedFilters = filters.languageSearchPasses().first()
        var cachedNyaaCandidates: List<StreamCenterTorrentCandidate>? = null
        var candidates = try {
            withTimeoutOrNull(TORRENT_SOURCE_TIMEOUT_MS) {
                var selectedCandidates = emptyList<StreamCenterTorrentCandidate>()
                for ((index, passFilters) in filters.languageSearchPasses().withIndex()) {
                    executedLanguagePasses++
                    languageFallbackAttempted = index > 0
                    appliedFilters = passFilters
                    val passCandidates = if (client === StreamCenterNyaaTorrentClient) {
                        val discoveredCandidates = cachedNyaaCandidates ?: StreamCenterNyaaTorrentClient
                            .searchWithDiagnostics(
                                definition,
                                sourceUrl,
                                sourceContext,
                                passFilters.copy(language = StreamCenterTorrentLanguageFilter.ANY),
                            )
                            .also { result ->
                                nyaaDiagnostics = result.diagnostics
                            }
                            .candidates
                            .also { result -> cachedNyaaCandidates = result }
                        discoveredCandidates.filter { candidate ->
                            candidate.isEligibleFor(sourceContext, passFilters)
                        }
                    } else {
                        client.search(definition, sourceUrl, sourceContext, passFilters)
                    }
                    if (
                        index == 0 &&
                        filters.language == StreamCenterTorrentLanguageFilter.PRIORITIZE_ITALIAN
                    ) {
                        italianPriorityCandidateCount = passCandidates.size
                    }
                    if (passCandidates.isNotEmpty()) {
                        selectedCandidates = passCandidates
                        languageFallbackUsed = index > 0
                        break
                    }
                }
                selectedCandidates
            } ?: emptyList<StreamCenterTorrentCandidate>().also { timedOut = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
            Log.w(
                LOG_TAG,
                "${definition.title}: ricerca fallita (${error.javaClass.simpleName})",
            )
            emptyList()
        }

        val limit = if (stopAfterFirstResult) 1 else MAX_RESULTS_PER_SOURCE
        val inspectionLimit = if (stopAfterFirstResult) 3 else MAX_BATCH_INSPECTIONS
        var candidateResolution = resolveCandidates(
            candidates = candidates,
            context = sourceContext,
            filters = appliedFilters,
            inspectionLimit = inspectionLimit,
        )
        if (
            candidateResolution.resolvedCandidates.isEmpty() &&
            filters.language == StreamCenterTorrentLanguageFilter.PRIORITIZE_ITALIAN &&
            !languageFallbackUsed &&
            !timedOut
        ) {
            languageFallbackAttempted = true
            executedLanguagePasses++
            val fallbackFilters = filters.copy(language = StreamCenterTorrentLanguageFilter.ANY)
            val fallbackCandidates = try {
                withTimeoutOrNull(TORRENT_SOURCE_TIMEOUT_MS) {
                    if (client === StreamCenterNyaaTorrentClient) {
                        val discoveredCandidates = cachedNyaaCandidates
                            ?: StreamCenterNyaaTorrentClient.searchWithDiagnostics(
                                definition,
                                sourceUrl,
                                sourceContext,
                                fallbackFilters,
                            ).also { result ->
                                nyaaDiagnostics = result.diagnostics
                            }.candidates.also { result ->
                                cachedNyaaCandidates = result
                            }
                        discoveredCandidates.filter { candidate ->
                            candidate.isEligibleFor(sourceContext, fallbackFilters)
                        }
                    } else {
                        client.search(definition, sourceUrl, sourceContext, fallbackFilters)
                    }
                } ?: emptyList<StreamCenterTorrentCandidate>().also { timedOut = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
                emptyList()
            }
            val attemptedCandidateKeys = candidates.mapTo(hashSetOf()) { candidate ->
                candidate.batchKey()
            }
            val newFallbackCandidates = fallbackCandidates.filterNot { candidate ->
                candidate.batchKey() in attemptedCandidateKeys
            }
            val fallbackResolution = resolveCandidates(
                candidates = newFallbackCandidates,
                context = sourceContext,
                filters = fallbackFilters,
                inspectionLimit = inspectionLimit,
            )
            candidates = newFallbackCandidates
            appliedFilters = fallbackFilters
            languageFallbackUsed = fallbackResolution.resolvedCandidates.isNotEmpty()
            candidateResolution = fallbackResolution.copy(
                batchPreparation = fallbackResolution.batchPreparation.withDiagnosticsFrom(
                    candidateResolution.batchPreparation,
                ),
            )
        }
        val eligibleCandidates = candidateResolution.eligibleCandidates
        val batchPreparation = candidateResolution.batchPreparation
        val resolvedCandidates = candidateResolution.resolvedCandidates
        val uniqueCandidates = resolvedCandidates.distinctBy(ResolvedCandidate::infoHash)
        val links = uniqueCandidates.asSequence()
            .sortedWith(
                compareByDescending<ResolvedCandidate> { it.candidate.seeders ?: -1 }
                    .thenBy { it.candidate.title.lowercase() },
            )
            .take(limit)
            .toList()

        links.forEach { result ->
            callback(
                newExtractorLink(
                    source = definition.title,
                    name = result.candidate.displayName(definition),
                    url = result.magnet,
                    type = ExtractorLinkType.MAGNET,
                ) {
                    quality = qualityFromTorrentTitle(
                        listOfNotNull(
                            result.candidate.title,
                            result.candidate.selectedFileName,
                        ).joinToString(" "),
                    )
                },
            )
        }
        val allNyaaQueriesFailed = nyaaDiagnostics?.let { diagnostics ->
            diagnostics.executedQueries.isNotEmpty() &&
                diagnostics.failedQueries.size == diagnostics.executedQueries.size
        } == true
        StreamCenterLogger.logMetadata(
            tabName = logTabName,
            source = definition.title,
            action = when {
                failure != null -> "Ricerca Torrent non riuscita"
                timedOut -> "Ricerca Torrent scaduta"
                else -> "Ricerca Torrent completata"
            },
            metadata = mapOf(
                "fonte_torrent" to definition.title,
                "query_previste" to (nyaaDiagnostics?.plannedQueries?.size ?: plannedQueries.size),
                "query_eseguite" to nyaaDiagnostics?.executedQueries?.size,
                "query_fallite" to nyaaDiagnostics?.failedQueries?.size,
                "query_giapponese_usata" to (
                    definition.key == StreamCenterTorrentSources.SUKEBEI_NYAA_KEY &&
                        nyaaDiagnostics?.executedQueries.orEmpty().any(::containsJapaneseScript)
                    ),
                "candidati_rss" to nyaaDiagnostics?.rawCandidateCount,
                "candidati_rss_idonei" to nyaaDiagnostics?.eligibleCandidateCount,
                "candidati_ricevuti" to candidates.size,
                "candidati_idonei" to eligibleCandidates.size,
                "batch_rilevati" to batchPreparation.detectedCount,
                "batch_ispezionati" to batchPreparation.inspectedCount,
                "batch_risolti" to batchPreparation.resolvedCount,
                "batch_scartati" to batchPreparation.rejectedCount,
                "motivi_scarto_batch" to batchPreparation.failures,
                "file_batch_selezionati" to batchPreparation.selectedFiles,
                "passaggi_lingua_eseguiti" to executedLanguagePasses,
                "candidati_priorita_italiano" to italianPriorityCandidateCount,
                "fallback_lingua_tentato" to languageFallbackAttempted,
                "fallback_lingua_usato" to languageFallbackUsed,
                "filtro_lingua_effettivo" to appliedFilters.language.preferenceValue,
                "magnet_validi" to resolvedCandidates.size,
                "duplicati_rimossi" to (resolvedCandidates.size - uniqueCandidates.size),
                "risultati_emessi" to links.size,
                "timeout" to timedOut,
                "tutte_le_query_fallite" to allNyaaQueriesFailed,
                "durata_ms" to (System.currentTimeMillis() - startedAt),
                "tipo_errore" to failure?.javaClass?.simpleName,
            ),
            level = when {
                failure != null -> StreamCenterLogger.Level.ERROR
                timedOut || allNyaaQueriesFailed -> StreamCenterLogger.Level.WARNING
                else -> StreamCenterLogger.Level.INFO
            },
            throwable = failure,
        )
        return links.isNotEmpty()
    }

    private fun StreamCenterTorrentCandidate.displayName(
        definition: StreamCenterTorrentSourceDefinition,
    ): String {
        val details = buildList {
            this@displayName.size?.takeIf(String::isNotBlank)?.let(::add)
            seeders?.let { add("$it seed") }
            leechers?.let { add("$it peer") }
            selectedFileName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.take(100)
                ?.let { fileName -> add("File: $fileName") }
        }
        return buildString {
            append("[Torrent · ")
            append(definition.title)
            append("] ")
            append(title.take(180))
            if (details.isNotEmpty()) {
                append(" · ")
                append(details.joinToString(" · "))
            }
        }
    }

    private data class ResolvedCandidate(
        val candidate: StreamCenterTorrentCandidate,
        val magnet: String,
        val infoHash: String,
    )

    private suspend fun resolveCandidates(
        candidates: List<StreamCenterTorrentCandidate>,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        inspectionLimit: Int,
    ): CandidateResolution {
        val eligibleCandidates = candidates.filter { candidate ->
            candidate.isEligibleFor(context, filters)
        }
        val batchPreparation = prepareBatchCandidates(
            candidates = eligibleCandidates,
            context = context,
            inspectionLimit = inspectionLimit,
        )
        val resolvedCandidates = batchPreparation.candidates
            .filter { candidate -> candidate.isEligibleFor(context, filters) }
            .mapNotNull { candidate ->
                val magnet = StreamCenterTorrentMagnet.build(candidate) ?: return@mapNotNull null
                val hash = StreamCenterTorrentMagnet.infoHash(magnet) ?: return@mapNotNull null
                ResolvedCandidate(candidate, magnet, hash)
            }
        return CandidateResolution(
            eligibleCandidates = eligibleCandidates,
            batchPreparation = batchPreparation,
            resolvedCandidates = resolvedCandidates,
        )
    }

    private suspend fun prepareBatchCandidates(
        candidates: List<StreamCenterTorrentCandidate>,
        context: StreamCenterTorrentPlaybackContext,
        inspectionLimit: Int,
    ): BatchPreparation = coroutineScope {
        val ranked = candidates.sortedWith(
            compareByDescending<StreamCenterTorrentCandidate> { it.seeders ?: -1 }
                .thenBy { it.title.lowercase() },
        )
        val batchCandidates = ranked.filter { candidate ->
            StreamCenterTorrentMatchPolicy.isBatchCandidate(candidate.title, context)
        }
        val inspected = batchCandidates.take(inspectionLimit.coerceAtLeast(1))
        val semaphore = Semaphore(BATCH_RESOLUTION_CONCURRENCY)
        val resolutions = inspected.map { candidate ->
            async {
                semaphore.withPermit {
                    candidate.batchKey() to StreamCenterTorrentBatchResolver.resolve(candidate, context)
                }
            }
        }.awaitAll().toMap()
        val failures = linkedMapOf<String, Int>()
        val selectedFiles = mutableListOf<String>()
        val prepared = ranked.mapNotNull { candidate ->
            if (!StreamCenterTorrentMatchPolicy.isBatchCandidate(candidate.title, context)) {
                return@mapNotNull candidate
            }
            val resolution = resolutions[candidate.batchKey()]
            if (resolution == null) {
                failures.increment("limite_ispezione_raggiunto")
                return@mapNotNull null
            }
            resolution.failure?.let { reason ->
                failures.increment(reason)
                return@mapNotNull null
            }
            resolution.candidate?.also { resolved ->
                resolved.selectedFileName?.let(selectedFiles::add)
            }
        }
        BatchPreparation(
            candidates = prepared,
            detectedCount = batchCandidates.size,
            inspectedCount = inspected.size,
            resolvedCount = resolutions.values.count { it.candidate != null },
            rejectedCount = batchCandidates.size - resolutions.values.count { it.candidate != null },
            failures = failures,
            selectedFiles = selectedFiles.take(MAX_LOGGED_BATCH_FILES),
        )
    }

    private fun StreamCenterTorrentCandidate.batchKey(): String =
        StreamCenterTorrentMagnet.infoHash(infoHash ?: magnetUrl)
            ?: "$title|${fileMetadataRequest?.url.orEmpty()}"

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private data class BatchPreparation(
        val candidates: List<StreamCenterTorrentCandidate>,
        val detectedCount: Int,
        val inspectedCount: Int,
        val resolvedCount: Int,
        val rejectedCount: Int,
        val failures: Map<String, Int>,
        val selectedFiles: List<String>,
    )

    private fun BatchPreparation.withDiagnosticsFrom(
        previous: BatchPreparation,
    ): BatchPreparation {
        val mergedFailures = previous.failures.toMutableMap()
        failures.forEach { (reason, count) ->
            mergedFailures[reason] = (mergedFailures[reason] ?: 0) + count
        }
        return copy(
            detectedCount = previous.detectedCount + detectedCount,
            inspectedCount = previous.inspectedCount + inspectedCount,
            resolvedCount = previous.resolvedCount + resolvedCount,
            rejectedCount = previous.rejectedCount + rejectedCount,
            failures = mergedFailures,
            selectedFiles = (previous.selectedFiles + selectedFiles)
                .take(MAX_LOGGED_BATCH_FILES),
        )
    }

    private data class CandidateResolution(
        val eligibleCandidates: List<StreamCenterTorrentCandidate>,
        val batchPreparation: BatchPreparation,
        val resolvedCandidates: List<ResolvedCandidate>,
    )

    private const val MAX_BATCH_INSPECTIONS = 6
    private const val BATCH_RESOLUTION_CONCURRENCY = 3
    private const val MAX_LOGGED_BATCH_FILES = 6

}
