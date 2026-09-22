package it.dogior.hadEnough.cache

import android.content.SharedPreferences
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.anime.metadata.AnimeSeriesEntry
import it.dogior.hadEnough.tracking.StreamCenterTrackingIds
import it.dogior.hadEnough.util.StreamCenterLogger
import java.io.File
import java.util.Calendar
import java.util.Locale

internal data class CachedActor(
    val name: String = "",
    val image: String? = null,
    val role: String? = null,
    val actorRole: String? = null,
    val voiceActorName: String? = null,
    val voiceActorImage: String? = null,
) {
    constructor(data: ActorData) : this(
        data.actor.name, data.actor.image, data.roleString, data.role?.name,
        data.voiceActor?.name, data.voiceActor?.image,
    )

    fun toActorData() = ActorData(
        actor = Actor(name, image),
        role = actorRole?.let { runCatching { ActorRole.valueOf(it) }.getOrNull() },
        roleString = role,
        voiceActor = voiceActorName?.takeIf(String::isNotBlank)?.let { Actor(it, voiceActorImage) },
    )
}

internal data class CachedSearchItem(
    val name: String = "",
    val url: String = "",
    val type: String? = null,
    val posterUrl: String? = null,
)

internal data class CachedSeason(
    val season: Int = 0,
    val name: String? = null,
    val displaySeason: Int? = null,
)

internal data class CachedEpisode(
    val data: String = "",
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val runTime: Int? = null,
    val dateMillis: Long? = null,
    val score: Int? = null,
)

internal data class CachedMediaEntry(
    val schemaVersion: Int,
    val key: String = "",
    val url: String = "",
    val type: String = StreamCenterMediaCache.TYPE_MOVIE,
    val title: String = "",
    val posterUrl: String? = null,
    val backgroundPosterUrl: String? = null,
    val logoUrl: String? = null,
    val plot: String? = null,
    val tags: List<String> = emptyList(),
    val year: Int? = null,
    val duration: Int? = null,
    val contentRating: String? = null,
    val score: String? = null,
    val showStatus: String? = null,
    val comingSoon: Boolean = false,
    val trailerUrl: String? = null,
    val trailerCheckedAtMillis: Long = 0L,
    val trackingIds: StreamCenterTrackingIds? = null,
    val actors: List<CachedActor> = emptyList(),
    val recommendations: List<CachedSearchItem> = emptyList(),
    val seasons: List<CachedSeason> = emptyList(),
    val episodes: List<CachedEpisode> = emptyList(),
    val movieDataUrl: String? = null,
    val animeType: String? = null,
    val animeMovie: Boolean = false,
    val animeTitlePreference: String? = null,
    val englishTitle: String? = null,
    val nativeTitle: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val animeRelations: List<AnimeSeriesEntry> = emptyList(),
    val animeRelationsExpiresAt: Long = 0L,
    val cachedAtMillis: Long = 0L,
    val expiresAtMillis: Long = 0L,
)

internal data class ScPointerIndex(
    val pointers: Map<String, String> = emptyMap(),
)

internal data class CacheStats(
    val entryCount: Int,
    val totalBytes: Long,
    val maxEntries: Int,
    val maxBytes: Long,
)

internal data class CachedMediaSummary(
    val key: String,
    val title: String,
    val type: String,
    val episodeCount: Int,
    val cachedAtMillis: Long,
    val expiresAtMillis: Long,
    val sizeBytes: Long,
    val showStatus: String?,
    val animeMovie: Boolean = false,
)

internal object StreamCenterMediaCache {
    const val SCHEMA_VERSION = 6
    const val TYPE_MOVIE = "movie"
    const val TYPE_SERIES = "series"
    const val TYPE_ANIME = "anime"

    private const val DIRECTORY_NAME = "streamcenter_media_cache"
    private const val POINTERS_FILE_NAME = "sc_pointers.json"
    private const val CATCH_UP_FILE_NAME = "catch_up.json"
    @Volatile
    var generation: Long = 0L
        private set
    private const val WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private val MEDIA_PATH_REGEX = Regex("/(movie|tv)/(\\d+)", RegexOption.IGNORE_CASE)
    private var recoveredDirectory: File? = null

    private fun isReservedFile(name: String): Boolean =
        name == POINTERS_FILE_NAME || name == CATCH_UP_FILE_NAME

    @Synchronized
    fun readCatchUp(): String? = if (isEnabled()) {
        directory()?.let { File(it, CATCH_UP_FILE_NAME) }?.takeIf(File::exists)
            ?.let { runCatching { AtomicTextFile.read(it) }.getOrNull() }
    } else null

    @Synchronized
    fun writeCatchUp(value: String, expectedGeneration: Long) {
        if (!isEnabled() || generation != expectedGeneration) return
        directory()?.let { runCatching { AtomicTextFile.write(File(it, CATCH_UP_FILE_NAME), value) } }
    }

    private fun isMediaFile(file: File): Boolean =
        file.isFile && file.name.endsWith(".json") && !isReservedFile(file.name)

    fun isEnabled(sharedPref: SharedPreferences? = StreamCenterPlugin.activeSharedPref): Boolean =
        StreamCenterPlugin.isMediaCacheEnabled(sharedPref)

    fun maxEntries(): Int = StreamCenterPlugin.mediaCacheMaxEntries(StreamCenterPlugin.activeSharedPref)

    fun maxBytes(): Long =
        StreamCenterPlugin.mediaCacheMaxMb(StreamCenterPlugin.activeSharedPref).toLong() * 1024L * 1024L

    @Synchronized
    fun applyLimits() = enforceLimits()

    fun keyFor(url: String): String? {
        val match = MEDIA_PATH_REGEX.find(url) ?: return null
        val type = if (match.groupValues[1].equals("tv", ignoreCase = true)) TYPE_SERIES else TYPE_MOVIE
        return "$type-${match.groupValues[2]}"
    }

    fun animeKey(anilistId: Int?, malId: Int?): String? = when {
        anilistId != null && anilistId > 0 -> "$TYPE_ANIME-al$anilistId"
        malId != null && malId > 0 -> "$TYPE_ANIME-mal$malId"
        else -> null
    }

    fun animeEpisodesKey(anilistId: Int): String = "anime-episodes-al$anilistId"

    fun readAnimeRelations(anilistId: Int?, malId: Int?): List<AnimeSeriesEntry>? =
        listOfNotNull(animeKey(anilistId, malId), anilistId?.let(::animeEpisodesKey))
            .firstNotNullOfOrNull { key ->
                readByKey(key)?.takeIf { it.animeRelationsExpiresAt > System.currentTimeMillis() }
                    ?.animeRelations?.takeIf { it.isNotEmpty() }
            }

    @Synchronized
    fun rememberAnimeRelations(entries: List<AnimeSeriesEntry>) {
        if (entries.isEmpty()) return
        val cachedReleases = entries.flatMap { release ->
            listOfNotNull(animeKey(release.id, release.malId), animeEpisodesKey(release.id))
        }.distinct().mapNotNull(::readByKey)
        val now = System.currentTimeMillis()
        val expiresAt = cachedReleases.filter { it.animeRelations == entries && it.animeRelationsExpiresAt > now }
            .minOfOrNull { it.animeRelationsExpiresAt } ?: (now + 30 * 60_000L)
        cachedReleases.forEach { cached ->
            if (cached.animeRelations != entries || cached.animeRelationsExpiresAt != expiresAt) {
                writeMedia(cached.copy(animeRelations = entries, animeRelationsExpiresAt = expiresAt))
            }
        }
    }

    fun readMedia(url: String): CachedMediaEntry? = readByKey(keyFor(url))

    @Synchronized
    fun readByKey(key: String?): CachedMediaEntry? {
        val safeKey = key ?: return null
        val file = mediaFile(safeKey)?.takeIf(File::exists) ?: return null
        val entry = runCatching { parseJson<CachedMediaEntry>(AtomicTextFile.read(file)) }.getOrNull()
            ?: run { file.delete(); return null }
        if (entry.schemaVersion != SCHEMA_VERSION || System.currentTimeMillis() >= entry.expiresAtMillis) {
            file.delete()
            return null
        }
        return entry
    }

    @Synchronized
    fun writeMedia(entry: CachedMediaEntry) {
        val file = mediaFile(entry.key)
        if (file == null) {
            StreamCenterLogger.logMenu(
                action = "Salvataggio cache saltato",
                metadata = mapOf("chiave" to entry.key, "motivo" to "chiave_non_valida"),
            )
            return
        }
        runCatching { AtomicTextFile.write(file, entry.toJson()) }
            .onSuccess {
                enforceLimits()
                StreamCenterLogger.logMenu(
                    action = "Scheda salvata in cache",
                    metadata = mapOf(
                        "chiave" to entry.key,
                        "tipo" to entry.type,
                        "episodi" to entry.episodes.size,
                        "scadenza_ms" to entry.expiresAtMillis,
                        "byte" to file.length(),
                    ),
                )
            }
            .onFailure { error ->
                StreamCenterLogger.logMenuError(
                    action = "Salvataggio cache non riuscito",
                    throwable = error,
                    metadata = mapOf("chiave" to entry.key, "tipo" to entry.type),
                )
            }
    }

    @Synchronized
    fun updateTrailer(key: String, trailerUrl: String?, checkedAt: Long, expectedGeneration: Long) {
        if (!isEnabled() || generation != expectedGeneration) return
        val current = readByKey(key) ?: return
        writeMedia(current.copy(trailerUrl = trailerUrl, trailerCheckedAtMillis = checkedAt))
    }

    @Synchronized
    fun readRaw(key: String): CachedMediaEntry? {
        val file = mediaFile(key)?.takeIf(File::exists) ?: return null
        return runCatching { parseJson<CachedMediaEntry>(AtomicTextFile.read(file)) }.getOrNull()
    }

    @Synchronized
    fun deleteMedia(key: String): Boolean {
        val deleted = mediaFile(key)?.delete() ?: false
        if (deleted) prunePointers()
        return deleted
    }

    @Synchronized
    fun listSummaries(): List<CachedMediaSummary> {
        val directory = directory() ?: return emptyList()
        val files = directory.listFiles(::isMediaFile) ?: return emptyList()
        return files.mapNotNull { file ->
            val entry = runCatching { parseJson<CachedMediaEntry>(AtomicTextFile.read(file)) }.getOrNull()
                ?: return@mapNotNull null
            CachedMediaSummary(
                key = entry.key.ifBlank { file.nameWithoutExtension },
                title = entry.title.ifBlank { file.nameWithoutExtension },
                type = entry.type,
                episodeCount = entry.episodes.size,
                cachedAtMillis = entry.cachedAtMillis,
                expiresAtMillis = entry.expiresAtMillis,
                sizeBytes = file.length(),
                showStatus = entry.showStatus,
                animeMovie = entry.animeMovie,
            )
        }.sortedByDescending(CachedMediaSummary::cachedAtMillis)
    }

    @Synchronized
    fun clearAll() {
        generation++
        directory()?.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun stats(): CacheStats {
        val directory = directory() ?: return CacheStats(0, 0L, maxEntries(), maxBytes())
        val files = directory.listFiles()?.filter(File::isFile)
            ?: return CacheStats(0, 0L, maxEntries(), maxBytes())
        val entryCount = files.count(::isMediaFile)
        val totalBytes = files.sumOf(File::length)
        return CacheStats(entryCount, totalBytes, maxEntries(), maxBytes())
    }

    @Synchronized
    fun cleanup(): Int {
        val directory = directory() ?: return 0
        val now = System.currentTimeMillis()
        var removed = 0
        directory.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val stale = when {
                file.name == POINTERS_FILE_NAME -> false
                file.name.endsWith(".json") -> {
                    val entry = runCatching { parseJson<CachedMediaEntry>(AtomicTextFile.read(file)) }.getOrNull()
                    entry == null || entry.schemaVersion != SCHEMA_VERSION || now >= entry.expiresAtMillis
                }
                else -> true
            }
            if (stale && file.delete()) removed++
        }
        prunePointers()
        return removed
    }

    @Synchronized
    fun writeScPointer(scId: String, mediaKey: String?) {
        if (scId.isBlank() || mediaKey.isNullOrBlank()) return
        writePointers(readPointers().toMutableMap().apply { put(scId, mediaKey) })
    }

    @Synchronized
    fun resolveScPointer(scId: String): String? {
        if (scId.isBlank()) return null
        return readPointers()[scId]
    }

    private fun prunePointers() {
        val current = readPointers()
        if (current.isEmpty()) return
        val alive = current.filterValues { key -> mediaFile(key)?.exists() == true }
        if (alive.size != current.size) writePointers(alive)
    }

    private fun readPointers(): Map<String, String> {
        val file = pointersFile()?.takeIf(File::exists) ?: return emptyMap()
        return runCatching { parseJson<ScPointerIndex>(AtomicTextFile.read(file)).pointers }.getOrDefault(emptyMap())
    }

    private fun writePointers(pointers: Map<String, String>) {
        val file = pointersFile() ?: return
        runCatching { AtomicTextFile.write(file, ScPointerIndex(pointers).toJson()) }
    }

    private fun pointersFile(): File? = directory()?.let { File(it, POINTERS_FILE_NAME) }

    private fun enforceLimits() {
        val directory = directory() ?: return
        val files = directory.listFiles(::isMediaFile)?.toMutableList() ?: return
        val maxEntries = maxEntries()
        val maxBytes = maxBytes()
        var totalBytes = files.sumOf(File::length)
        if (files.size <= maxEntries && totalBytes <= maxBytes) return
        files.sortBy(File::lastModified)
        var count = files.size
        for (file in files) {
            if (count <= maxEntries && totalBytes <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                count--
            }
        }
        prunePointers()
    }

    fun computeMediaExpiry(now: Long, showStatus: String?, nextAirDateMillis: Long?): Long {
        val ongoing = showStatus.equals("Ongoing", ignoreCase = true)
        if (!ongoing) return now + WEEK_MILLIS
        if (nextAirDateMillis != null && nextAirDateMillis > now) {
            return minOf(nextAirDateMillis, now + WEEK_MILLIS)
        }
        return nextMidnightMillis(now)
    }

    fun nextMidnightMillis(now: Long): Long {
        val calendar = Calendar.getInstance(Locale.getDefault()).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun mediaFile(key: String): File? {
        val safeKey = key.takeIf { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() || char == '-' } }
            ?: return null
        return directory()?.let { File(it, "$safeKey.json") }
    }

    private fun directory(): File? {
        val context = StreamCenterPlugin.activeContext ?: return null
        val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) return null
        if (recoveredDirectory != directory) {
            if (runCatching { AtomicTextFile.recoverDirectory(directory) }.isFailure) return null
            recoveredDirectory = directory
        }
        return directory
    }
}
