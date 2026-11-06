package it.dogior.hadEnough.playback

import android.os.SystemClock
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

internal enum class DiscoveryOutcome(val label: String) {
    FOUND("Trovata"),
    EMPTY("Nessun link"),
    ERROR("Errore"),
    TIMEOUT("Tempo scaduto"),
}

private const val PROVIDER_KEY_SEPARATOR = "::"
private val PROVIDER_LABEL = Regex("^\\p{L}[\\p{L}\\p{N}]+")

private fun providerLabel(text: String): String? = PROVIDER_LABEL.find(text.trim())?.value

internal fun PlaybackSource.forLink(link: ExtractorLink): PlaybackSource {
    if (group != PlaybackSourceGroup.EXTENSION) return this
    val provider = listOfNotNull(link.source.trim(), providerLabel(link.name))
        .firstOrNull { it.isNotEmpty() && !it.equals(name, ignoreCase = true) } ?: return this
    return copy(key = "$key$PROVIDER_KEY_SEPARATOR${provider.lowercase()}", name = "$name · $provider")
}

internal data class DiscoverySample(
    val atMillis: Long = 0L,
    val outcome: String = DiscoveryOutcome.FOUND.name,
    val firstLinkMs: Long? = null,
    val durationMs: Long = 0L,
    val links: Int = 0,
) {
    val found: Boolean get() = outcome == DiscoveryOutcome.FOUND.name
}

internal data class SourceDiscoveryEntry(
    val sourceKey: String = "",
    val sourceName: String = "",
    val group: String = "",
    val samples: List<DiscoverySample> = emptyList(),
)

internal data class SourceDiscoverySummary(
    val sourceName: String,
    val group: PlaybackSourceGroup,
    val attempts: Int,
    val found: Int,
    val medianFirstLinkMs: Long?,
    val fastestFirstLinkMs: Long?,
    val medianDurationMs: Long?,
    val averageLinks: Double,
    val outcomes: Map<DiscoveryOutcome, Int>,
    val lastAtMillis: Long,
    val isProvider: Boolean,
) {
    val foundRate: Double get() = if (attempts == 0) 0.0 else found.toDouble() / attempts

    companion object {
        private const val MIN_ATTEMPTS = 3
        private const val USUALLY_FOUND_RATE = 0.5

        val ranking: Comparator<SourceDiscoverySummary> =
            compareByDescending<SourceDiscoverySummary> { it.attempts >= MIN_ATTEMPTS }
                .thenByDescending { it.foundRate >= USUALLY_FOUND_RATE }
                .thenBy { it.medianFirstLinkMs ?: Long.MAX_VALUE }
                .thenByDescending { it.foundRate }
                .thenByDescending { it.lastAtMillis }

        fun of(entry: SourceDiscoveryEntry, group: PlaybackSourceGroup): SourceDiscoverySummary {
            val samples = entry.samples
            val hits = samples.filter(DiscoverySample::found)
            val firstLinks = hits.mapNotNull(DiscoverySample::firstLinkMs)
            return SourceDiscoverySummary(
                sourceName = entry.sourceName.ifBlank { entry.sourceKey },
                group = group,
                attempts = samples.size,
                found = hits.size,
                medianFirstLinkMs = medianLong(firstLinks),
                fastestFirstLinkMs = firstLinks.minOrNull(),
                medianDurationMs = medianLong(samples.map(DiscoverySample::durationMs)),
                averageLinks = hits.map(DiscoverySample::links).average().takeUnless(Double::isNaN) ?: 0.0,
                outcomes = DiscoveryOutcome.entries.associateWith { outcome -> samples.count { it.outcome == outcome.name } },
                lastAtMillis = samples.maxOfOrNull(DiscoverySample::atMillis) ?: 0L,
                isProvider = PROVIDER_KEY_SEPARATOR in entry.sourceKey,
            )
        }

    }
}

internal class SourceDiscoveryTracker {
    private class ProviderProgress(val source: PlaybackSource, val firstLinkAt: Long) {
        var links = 0
    }

    private class Progress(val source: PlaybackSource, val startedAt: Long) {
        var firstLinkAt: Long? = null
        var links = 0
        var running = 0
        var failed = false
        var cancelled = false
        var timedOut = false
        var skippedForCasting = false
        var finishedAt = startedAt
        val providers = linkedMapOf<String, ProviderProgress>()
    }

    private val progress = ConcurrentHashMap<String, Progress>()

    suspend fun <T> track(source: PlaybackSource, task: suspend () -> T): T {
        val entry = progress.computeIfAbsent(source.key) { Progress(source, SystemClock.elapsedRealtime()) }
        synchronized(entry) { entry.running++ }
        var failed = false
        var cancelled = false
        var timedOut = false
        try {
            return task()
        } catch (error: TimeoutCancellationException) {
            timedOut = true
            throw error
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            synchronized(entry) {
                entry.running--
                entry.failed = entry.failed || failed
                entry.cancelled = entry.cancelled || cancelled
                entry.timedOut = entry.timedOut || timedOut
                entry.finishedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    fun link(source: PlaybackSource, link: ExtractorLink) {
        val entry = progress[source.key] ?: return
        val provider = source.forLink(link).takeUnless { it == source }
        val now = SystemClock.elapsedRealtime()
        synchronized(entry) {
            entry.links++
            if (entry.firstLinkAt == null) entry.firstLinkAt = now
            provider?.let { entry.providers.getOrPut(it.key) { ProviderProgress(it, now) }.links++ }
        }
    }

    fun fail(sourceKey: String, error: Throwable? = null) {
        val entry = progress[sourceKey] ?: return
        synchronized(entry) {
            if (error is TimeoutCancellationException) entry.timedOut = true else entry.failed = true
        }
    }

    fun timeout(sourceKey: String) {
        val entry = progress[sourceKey] ?: return
        synchronized(entry) { entry.timedOut = true }
    }

    fun skippedForCasting(sourceKey: String) {
        val entry = progress[sourceKey] ?: return
        synchronized(entry) { entry.skippedForCasting = true }
    }

    fun commit() {
        val finished = progress.values.toList()
        progress.clear()
        val now = System.currentTimeMillis()
        val samples = mutableListOf<Pair<PlaybackSource, DiscoverySample>>()
        finished.forEach { entry ->
            samples += synchronized(entry) {
                if (entry.running > 0) return@forEach
                if (entry.cancelled && !entry.timedOut && entry.links == 0) return@forEach
                if (entry.skippedForCasting && entry.links == 0 && !entry.failed && !entry.timedOut) return@forEach
                val sample = DiscoverySample(
                    atMillis = now,
                    outcome = when {
                        entry.links > 0 -> DiscoveryOutcome.FOUND
                        entry.timedOut -> DiscoveryOutcome.TIMEOUT
                        entry.failed -> DiscoveryOutcome.ERROR
                        else -> DiscoveryOutcome.EMPTY
                    }.name,
                    firstLinkMs = entry.firstLinkAt?.minus(entry.startedAt),
                    durationMs = entry.finishedAt - entry.startedAt,
                    links = entry.links,
                )
                listOf(entry.source to sample) + entry.providers.values.map { provider ->
                    provider.source to sample.copy(
                        firstLinkMs = provider.firstLinkAt - entry.startedAt,
                        links = provider.links,
                    )
                }
            }
        }
        StreamCenterPlaybackInsights.recordDiscoveries(samples)
    }
}
