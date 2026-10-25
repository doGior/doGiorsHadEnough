package it.dogior.hadEnough.playback

import android.content.Context
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.cache.AtomicTextFile
import it.dogior.hadEnough.cache.ExpiringCache
import it.dogior.hadEnough.util.StreamCenterLogger
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors

internal data class PlaybackSource(val group: PlaybackSourceGroup, val key: String, val name: String)

internal data class PlaybackOrigin(val source: PlaybackSource, val host: String, val declaredHeight: Int?)

internal enum class PlaybackOutcome { STARTED, FAILED, DEAD, TIMEOUT }

internal enum class PlaybackTier(val label: String?, val bonus: Int, val title: String) {
    RELIABLE("Affidabile", 10, "Affidabile"),
    NORMAL(null, 0, "Da valutare"),
    POOR("Scarso", -9, "Poco affidabile"),
}

internal data class PlaybackSample(
    val atMillis: Long = 0L,
    val outcome: String = PlaybackOutcome.STARTED.name,
    val startupMs: Long? = null,
    val stalls: Int = 0,
    val stallMs: Long = 0L,
    val height: Int? = null,
    val declaredHeight: Int? = null,
) {
    val started: Boolean get() = outcome == PlaybackOutcome.STARTED.name
}

internal data class PlaybackInsightEntry(
    val sourceKey: String = "",
    val sourceName: String = "",
    val group: String = "",
    val host: String = "",
    val samples: List<PlaybackSample> = emptyList(),
) {
    val lastAtMillis: Long get() = samples.maxOfOrNull { it.atMillis } ?: 0L
}

internal data class PlaybackInsightSnapshot(
    val schemaVersion: Int = StreamCenterPlaybackInsights.SCHEMA_VERSION,
    val entries: List<PlaybackInsightEntry> = emptyList(),
)

internal data class PlaybackInsightSummary(
    val sourceName: String,
    val host: String,
    val attempts: Int,
    val started: Int,
    val medianStartupMs: Long?,
    val stallsPerPlay: Double,
    val height: Int?,
    val tier: PlaybackTier,
    val score: Int?,
    val missingSamples: Int,
    val lastAtMillis: Long,
)

private class PlaybackMetrics(samples: List<PlaybackSample>, val group: PlaybackSourceGroup) {
    val recent = samples.takeLast(StreamCenterPlaybackInsights.RECENT_SAMPLES)
    val plays = recent.filter(PlaybackSample::started)
    val successRate = if (recent.isEmpty()) 0.0 else plays.size.toDouble() / recent.size
    val medianStartupMs = median(plays.mapNotNull(PlaybackSample::startupMs))
    val stallsPerPlay = plays.map(PlaybackSample::stalls).average().takeUnless(Double::isNaN) ?: 0.0
    val medianHeight = median(plays.mapNotNull { it.height?.toLong() })?.toInt()
    val qualityMismatch = plays.count { sample ->
        val declared = sample.declaredHeight ?: return@count false
        val measured = sample.height ?: return@count false
        measured < declared * QUALITY_MISMATCH_RATIO
    } * 2 > plays.size
    val startupLimitMs = if (group == PlaybackSourceGroup.TORRENT) TORRENT_RELIABLE_STARTUP_MS else HTTP_RELIABLE_STARTUP_MS
    val rated = recent.size >= StreamCenterPlaybackInsights.MIN_SAMPLES

    val tier: PlaybackTier get() {
        if (!rated) return PlaybackTier.NORMAL
        val lastTwoFailed = recent.takeLast(2).none(PlaybackSample::started)
        if (successRate < POOR_SUCCESS_RATE || lastTwoFailed) return PlaybackTier.POOR
        val startup = medianStartupMs ?: return PlaybackTier.NORMAL
        val reliable = successRate >= RELIABLE_SUCCESS_RATE && recent.last().started &&
            startup <= startupLimitMs && stallsPerPlay <= RELIABLE_MAX_STALLS && !qualityMismatch
        return if (reliable) PlaybackTier.RELIABLE else PlaybackTier.NORMAL
    }

    val score: Int? get() {
        if (!rated) return null
        val speed = medianStartupMs?.let { 1.0 - (it.toDouble() / (startupLimitMs * 2)).coerceIn(0.0, 1.0) } ?: 0.0
        val smoothness = 1.0 - (stallsPerPlay / MAX_SCORED_STALLS).coerceIn(0.0, 1.0)
        val quality = if (qualityMismatch) 0.0 else 1.0
        return ((successRate * 0.55 + speed * 0.25 + smoothness * 0.15 + quality * 0.05) * 100).toInt()
    }

    private companion object {
        const val HTTP_RELIABLE_STARTUP_MS = 8_000L
        const val TORRENT_RELIABLE_STARTUP_MS = 25_000L
        const val RELIABLE_SUCCESS_RATE = 0.8
        const val POOR_SUCCESS_RATE = 0.5
        const val RELIABLE_MAX_STALLS = 2.0
        const val MAX_SCORED_STALLS = 4.0
        const val QUALITY_MISMATCH_RATIO = 0.6

        fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}

internal object StreamCenterPlaybackInsights {
    const val SCHEMA_VERSION = 1
    const val RETENTION_DAYS = 90
    const val TAB = "Riproduzione con un tasto"
    private const val FILE_NAME = "streamcenter_playback_insights.json"
    private const val MAX_SAMPLES = 12
    private const val MAX_ENTRIES = 200
    const val RECENT_SAMPLES = 10
    const val MIN_SAMPLES = 3
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StreamCenterPlaybackInsights").apply { isDaemon = true }
    }
    private val origins = ExpiringCache<String, PlaybackOrigin>(maxEntries = 600, ttlMillis = 12L * 60L * 60L * 1000L)
    private var appContext: Context? = null
    private var isEnabled: () -> Boolean = { false }
    private var entries: MutableMap<String, PlaybackInsightEntry>? = null

    fun install(context: Context, enabled: () -> Boolean) {
        appContext = context.applicationContext
        isEnabled = enabled
        StreamCenterPlayerHooks.register(context, "playback_insights", PlaybackInsightSessionFactory)
    }

    fun enabled(): Boolean = runCatching { isEnabled() }.getOrDefault(false)

    fun originFor(source: PlaybackSource, link: ExtractorLink): PlaybackOrigin =
        PlaybackOrigin(source, hostOf(link.url), link.quality.takeIf { it > 0 })

    fun remember(link: ExtractorLink, origin: PlaybackOrigin) {
        if (enabled()) origins.put(link.url, origin)
    }

    fun originOf(url: String): PlaybackOrigin? = origins[url]

    fun tierFor(origin: PlaybackOrigin): PlaybackTier {
        if (!enabled()) return PlaybackTier.NORMAL
        val samples = synchronized(this) {
            val all = loaded()
            all[entryKey(origin.source.key, origin.host)]?.samples?.takeIf { it.size >= MIN_SAMPLES }
                ?: all.values.filter { it.sourceKey == origin.source.key }
                    .flatMap { it.samples }
                    .sortedBy { it.atMillis }
        }
        return PlaybackMetrics(samples, origin.source.group).tier
    }

    fun record(origin: PlaybackOrigin, sample: PlaybackSample) {
        writer.execute {
            runCatching {
                synchronized(this) {
                    val all = loaded()
                    val key = entryKey(origin.source.key, origin.host)
                    val current = all[key] ?: PlaybackInsightEntry(
                        sourceKey = origin.source.key,
                        group = origin.source.group.name,
                        host = origin.host,
                    )
                    all[key] = current.copy(
                        sourceName = origin.source.name,
                        samples = (current.samples + sample).takeLast(MAX_SAMPLES),
                    )
                    prune(all)
                    persist(all)
                }
            }.onFailure {
                StreamCenterLogger.warning("Statistiche di riproduzione non salvate", mapOf("errore" to it.toString()))
            }
        }
    }

    fun summaries(): List<PlaybackInsightSummary> = synchronized(this) {
        val all = loaded()
        if (prune(all)) persist(all)
        all.values.map { entry ->
            val metrics = PlaybackMetrics(entry.samples, groupOf(entry.group))
            PlaybackInsightSummary(
                sourceName = entry.sourceName.ifBlank { entry.sourceKey },
                host = entry.host,
                attempts = metrics.recent.size,
                started = metrics.plays.size,
                medianStartupMs = metrics.medianStartupMs,
                stallsPerPlay = metrics.stallsPerPlay,
                height = metrics.medianHeight,
                tier = metrics.tier,
                score = metrics.score,
                missingSamples = (MIN_SAMPLES - metrics.recent.size).coerceAtLeast(0),
                lastAtMillis = entry.lastAtMillis,
            )
        }.sortedWith(
            compareBy<PlaybackInsightSummary> { it.tier.ordinal }
                .thenByDescending { it.score ?: -1 }
                .thenByDescending { it.lastAtMillis },
        )
    }

    fun clear() = synchronized(this) {
        entries = mutableMapOf()
        origins.clear()
        file()?.let { target ->
            listOf(target, File(target.path + ".bak"), File(target.path + ".new")).forEach(File::delete)
        }
        Unit
    }

    private fun groupOf(name: String): PlaybackSourceGroup =
        PlaybackSourceGroup.entries.firstOrNull { it.name == name } ?: PlaybackSourceGroup.HTTPS

    private fun hostOf(url: String): String {
        if (url.startsWith("magnet:", ignoreCase = true)) return "p2p"
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return "sconosciuto"
        return host.removePrefix("www.")
    }

    private fun entryKey(sourceKey: String, host: String) = "$sourceKey|$host"

    private fun prune(all: MutableMap<String, PlaybackInsightEntry>): Boolean {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS
        var changed = false
        all.entries.toList().forEach { (key, entry) ->
            val kept = entry.samples.filter { it.atMillis >= cutoff }.takeLast(MAX_SAMPLES)
            when {
                kept.isEmpty() -> all.remove(key).also { changed = true }
                kept.size != entry.samples.size -> all.put(key, entry.copy(samples = kept)).also { changed = true }
            }
        }
        if (all.size > MAX_ENTRIES) {
            all.entries.sortedBy { it.value.lastAtMillis }.take(all.size - MAX_ENTRIES).forEach { all.remove(it.key) }
            changed = true
        }
        return changed
    }

    private fun loaded(): MutableMap<String, PlaybackInsightEntry> {
        entries?.let { return it }
        val snapshot = file()?.takeIf { it.exists() || File(it.path + ".bak").exists() }
            ?.let { runCatching { parseJson<PlaybackInsightSnapshot>(AtomicTextFile.read(it)) }.getOrNull() }
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
        return snapshot?.entries.orEmpty()
            .associateByTo(mutableMapOf()) { entryKey(it.sourceKey, it.host) }
            .also { entries = it }
    }

    private fun persist(all: Map<String, PlaybackInsightEntry>) {
        val target = file() ?: return
        AtomicTextFile.write(target, PlaybackInsightSnapshot(entries = all.values.toList()).toJson())
    }

    private fun file(): File? = appContext?.filesDir?.let { File(it, FILE_NAME) }
}
