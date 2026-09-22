@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package it.dogior.hadEnough.playback

import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.Qualities
import it.dogior.hadEnough.util.StreamCenterLogger

internal enum class PlaybackSourceGroup(val label: String) {
    TORRENT("Torrent"), HTTPS("HTTPS"), EXTENSION("Estensioni"),
}

internal class OrderedPlaybackLinks(private val dedupKey: (ExtractorLink) -> String) {
    private data class Result(val group: PlaybackSourceGroup, val link: ExtractorLink)
    private val results = linkedMapOf<String, Result>()

    val size: Int get() = synchronized(results) { results.size }

    fun add(group: PlaybackSourceGroup, link: ExtractorLink) = synchronized(results) {
        val key = dedupKey(link)
        val previous = results[key]
        if (previous == null || group.ordinal < previous.group.ordinal) results[key] = Result(group, link)
    }

    fun emit(callback: (PlaybackSourceGroup, ExtractorLink) -> Unit) {
        val snapshot = synchronized(results) {
            results.values.sortedWith(compareBy<Result> { it.group.ordinal }.thenByDescending { it.link.quality })
        }
        snapshot.forEach { callback(it.group, it.link) }
    }
}

internal interface PlaybackPriorityStore {
    fun profiles(): List<Int>
    fun qualityPriority(profile: Int, quality: Qualities): Int
    fun sourcePriority(profile: Int, source: String): Int
    fun setSourcePriority(profile: Int, source: String, priority: Int)
}

private object CloudStreamPlaybackPriorities : PlaybackPriorityStore {
    override fun profiles() = QualityDataHelper.getProfiles().map { it.id }
    override fun qualityPriority(profile: Int, quality: Qualities) = QualityDataHelper.getQualityPriority(profile, quality)
    override fun sourcePriority(profile: Int, source: String) = QualityDataHelper.getSourcePriority(profile, source)
    override fun setSourcePriority(profile: Int, source: String, priority: Int) =
        QualityDataHelper.setSourcePriority(profile, source, priority)
}

internal class StreamCenterSourceOrder(private val store: PlaybackPriorityStore = CloudStreamPlaybackPriorities) {
    private val profiles by lazy { store.profiles().associateWith { id ->
        Qualities.entries.associateWith { store.qualityPriority(id, it) }
    } }
    private val configuredSources = mutableSetOf<String>()
    private var available = true

    fun prepare(group: PlaybackSourceGroup, link: ExtractorLink, playbackProvider: String? = null): ExtractorLink {
        if (!available && playbackProvider == null) return link
        val quality = Qualities.entries.minBy { kotlin.math.abs(it.value.toLong() - link.quality) }
        val qualityLabel = if (quality == Qualities.Unknown) "N/D" else "${quality.value}p"
        val source = playbackProvider ?: "StreamCenter · ${group.label} · $qualityLabel"
        try {
            if (available && configuredSources.add(source)) {
                profiles.forEach { (profile, priorities) ->
                    val original = priorities.getValue(quality)
                    val visiblePriorities = priorities.values.filter { it >= -1 }.distinct().sorted()
                    val qualityRank = if (visiblePriorities.size <= 1) 0 else
                        (visiblePriorities.indexOf(original).coerceAtLeast(0) * 2 / (visiblePriorities.size - 1))
                    val total = if (original < -1) original + 1 else (2 - group.ordinal) * 3 + qualityRank
                    val priority = total - original
                    if (store.sourcePriority(profile, source) != priority) store.setSourcePriority(profile, source, priority)
                }
            }
        } catch (error: Exception) {
            available = false
            StreamCenterLogger.warning("Priorità player non disponibile", mapOf("errore" to error.toString()))
            if (playbackProvider == null) return link
        } catch (error: LinkageError) {
            available = false
            StreamCenterLogger.warning("Priorità player non compatibile", mapOf("errore" to error.toString()))
            if (playbackProvider == null) return link
        }
        return when (link) {
            is ExtractorLinkPlayList -> link.copy(source = source)
            is DrmExtractorLink -> DrmExtractorLink(
                source = source, name = link.name, url = link.url, referer = link.referer,
                quality = link.quality, type = link.type, headers = link.headers, extractorData = link.extractorData,
                kid = link.kid, key = link.key, uuid = link.uuid, kty = link.kty,
                keyRequestParameters = link.keyRequestParameters, licenseUrl = link.licenseUrl,
            ).apply { audioTracks = link.audioTracks }
            else -> ExtractorLink(
                source = source, name = link.name, url = link.url, referer = link.referer,
                quality = link.quality, headers = link.headers, extractorData = link.extractorData,
                type = link.type, audioTracks = link.audioTracks,
            )
        }
    }
}
