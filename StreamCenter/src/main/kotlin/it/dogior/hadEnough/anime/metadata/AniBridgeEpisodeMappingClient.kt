package it.dogior.hadEnough.anime.metadata

import android.util.JsonReader
import android.util.JsonToken
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

internal data class TmdbAnimeEpisodeReference(
    val tmdbId: Int,
    val season: Int,
    val episode: Int,
)

internal class AniBridgeEpisodeMappingClient(
    private val headers: Map<String, String>,
    private val cacheDirectory: () -> File?,
) {
    private val mappingCache = ConcurrentHashMap<String, Map<Int, TmdbAnimeEpisodeReference>>()
    private val archiveMutex = Mutex()

    suspend fun fetch(
        anilistId: Int,
        episodeNumbers: Set<Int>,
    ): Map<Int, TmdbAnimeEpisodeReference> {
        if (anilistId <= 0 || episodeNumbers.isEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Risoluzione mappature episodi ignorata",
                buildMap<String, Any?> {
                    put("motivo", if (anilistId <= 0) "id_anilist_non_valido" else "nessun_episodio")
                    if (anilistId > 0) put("id_anilist", anilistId)
                    put("episodi_richiesti", episodeNumbers.size)
                },
            )
            return emptyMap()
        }
        val requestDetails = mapOf(
            "id_anilist" to anilistId,
            "episodi_richiesti" to episodeNumbers.size,
        )
        val cacheKey = "$anilistId:${episodeNumbers.sorted().joinToString(",")}"
        mappingCache[cacheKey]?.let { cached ->
            MetadataLog.info(
                SOURCE,
                "Mappature episodi ottenute dalla cache",
                requestDetails + mapOf("mappature_risolte" to cached.size),
            )
            return cached
        }

        MetadataLog.info(SOURCE, "Risoluzione mappature episodi avviata", requestDetails)
        val mapping = withContext(Dispatchers.IO) {
            archiveFile()?.let { archive ->
                parseMappings(archive, anilistId, episodeNumbers)
            }.orEmpty()
        }
        if (mapping.isEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Nessuna mappatura episodi trovata",
                requestDetails,
            )
            return mapping
        }
        val resolved = mappingCache.putIfAbsent(cacheKey, mapping) ?: mapping
        MetadataLog.info(
            SOURCE,
            "Mappature episodi elaborate",
            requestDetails + mapOf(
                "mappature_risolte" to resolved.size,
                "serie_tmdb_coinvolte" to resolved.values.map(TmdbAnimeEpisodeReference::tmdbId).distinct().size,
                "stagioni_tmdb_coinvolte" to resolved.values.map { it.tmdbId to it.season }.distinct().size,
            ),
        )
        return resolved
    }

    private suspend fun archiveFile(): File? {
        val directory = cacheDirectory() ?: run {
            MetadataLog.warning(SOURCE, "Archivio mappature non disponibile", mapOf("motivo" to "cache_non_configurata"))
            return null
        }
        return archiveMutex.withLock {
            if (!directory.exists() && !directory.mkdirs()) {
                MetadataLog.error(SOURCE, "Archivio mappature non disponibile", mapOf("motivo" to "creazione_cache_non_riuscita"))
                return@withLock null
            }
            val archive = File(directory, ARCHIVE_FILE_NAME)
            if (archive.isFresh()) {
                MetadataLog.info(
                    SOURCE,
                    "Archivio mappature ottenuto dalla cache locale",
                    mapOf(
                        "dimensione_archivio_byte" to archive.length(),
                        "eta_archivio_ms" to (System.currentTimeMillis() - archive.lastModified()).coerceAtLeast(0L),
                    ),
                )
                return@withLock archive
            }
            MetadataLog.info(
                SOURCE,
                "Aggiornamento archivio mappature necessario",
                mapOf("archivio_locale_utilizzabile" to (archive.isFile && archive.length() >= MIN_ARCHIVE_SIZE_BYTES)),
            )
            downloadArchive(directory, archive)
                ?: archive.takeIf { it.isFile && it.length() >= MIN_ARCHIVE_SIZE_BYTES }?.also {
                    MetadataLog.warning(
                        SOURCE,
                        "Usato archivio mappature locale non aggiornato",
                        mapOf("dimensione_archivio_byte" to it.length()),
                    )
                }
        }
    }

    private suspend fun downloadArchive(directory: File, archive: File): File? {
        val temporary = File(directory, ".${ARCHIVE_FILE_NAME}.${System.nanoTime()}.tmp")
        return try {
            MetadataLog.info(
                SOURCE,
                "Download archivio mappature avviato",
                mapOf("timeout_secondi" to ARCHIVE_TIMEOUT_SECONDS),
            )
            val responseResult = runCatching {
                app.get(
                ARCHIVE_URL,
                headers = headers + mapOf("Accept" to "application/zip"),
                cacheTime = 0,
                timeout = ARCHIVE_TIMEOUT_SECONDS,
                )
            }
            val response = responseResult.getOrNull() ?: run {
                MetadataLog.failure(
                    source = SOURCE,
                    action = "Download archivio mappature non riuscito",
                    error = responseResult.exceptionOrNull(),
                    details = mapOf("motivo" to "errore_di_rete"),
                )
                return null
            }
            if (response.code !in 200..299) {
                MetadataLog.warning(
                    SOURCE,
                    "Download archivio mappature non riuscito",
                    mapOf("motivo" to "stato_http_non_valido", "stato_http" to response.code),
                )
                return null
            }
            response.body.byteStream().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (temporary.length() < MIN_ARCHIVE_SIZE_BYTES) {
                MetadataLog.warning(
                    SOURCE,
                    "Download archivio mappature scartato",
                    mapOf(
                        "motivo" to "archivio_troppo_piccolo",
                        "dimensione_archivio_byte" to temporary.length(),
                    ),
                )
                return null
            }
            if (archive.exists() && !archive.delete()) {
                MetadataLog.error(
                    SOURCE,
                    "Aggiornamento archivio mappature non riuscito",
                    mapOf("motivo" to "sostituzione_archivio_non_riuscita"),
                )
                return null
            }
            if (!temporary.renameTo(archive)) {
                temporary.copyTo(archive, overwrite = true)
            }
            archive.takeIf(File::isFile)?.also {
                MetadataLog.info(
                    SOURCE,
                    "Download archivio mappature completato",
                    mapOf("stato_http" to response.code, "dimensione_archivio_byte" to it.length()),
                )
            }
        } catch (error: Throwable) {
            MetadataLog.failure(
                source = SOURCE,
                action = "Download archivio mappature non riuscito",
                error = error,
                details = mapOf("motivo" to "errore_durante_salvataggio_archivio"),
            )
            null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun parseMappings(
        archive: File,
        anilistId: Int,
        episodeNumbers: Set<Int>,
    ): Map<Int, TmdbAnimeEpisodeReference> {
        val result = runCatching {
            archive.inputStream().buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && (entry.isDirectory || !entry.name.endsWith(".json", true))) {
                        entry = zip.nextEntry
                    }
                    if (entry == null) {
                        emptyMap()
                    } else {
                        JsonReader(InputStreamReader(zip, Charsets.UTF_8)).use { reader ->
                            readProvenance(reader, anilistId, episodeNumbers)
                        }
                    }
                }
            }
        }
        val mappings = result.getOrElse { error ->
            MetadataLog.failure(
                source = SOURCE,
                action = "Lettura archivio mappature non riuscita",
                error = error,
                details = mapOf("id_anilist" to anilistId, "episodi_richiesti" to episodeNumbers.size),
            )
            emptyMap()
        }
        MetadataLog.info(
            SOURCE,
            "Archivio mappature elaborato",
            mapOf(
                "id_anilist" to anilistId,
                "episodi_richiesti" to episodeNumbers.size,
                "mappature_trovate" to mappings.size,
            ),
        )
        return mappings
    }

    private fun readProvenance(
        reader: JsonReader,
        anilistId: Int,
        episodeNumbers: Set<Int>,
    ): Map<Int, TmdbAnimeEpisodeReference> {
        var dictionary: ProvenanceDictionary? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "dict" -> dictionary = readDictionary(reader)
                "mappings" -> return dictionary?.let {
                    readMappings(reader, it, anilistId, episodeNumbers)
                } ?: run {
                    reader.skipValue()
                    emptyMap()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return emptyMap()
    }

    private fun readDictionary(reader: JsonReader): ProvenanceDictionary {
        var actions = emptyList<String>()
        var descriptors = emptyList<String>()
        var ranges = emptyList<EpisodeRange>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "actions" -> actions = readStringArray(reader)
                "descriptors" -> descriptors = readStringArray(reader)
                "ranges" -> ranges = readRanges(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ProvenanceDictionary(actions, descriptors, ranges)
    }

    private fun readStringArray(reader: JsonReader): List<String> {
        val values = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.nextStringOrNull()?.let(values::add)
        }
        reader.endArray()
        return values
    }

    private fun readRanges(reader: JsonReader): List<EpisodeRange> {
        val ranges = mutableListOf<EpisodeRange>()
        reader.beginArray()
        while (reader.hasNext()) {
            var source: String? = null
            var target: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "s" -> source = reader.nextStringOrNull()
                    "t" -> target = reader.nextStringOrNull()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            ranges += EpisodeRange(source.orEmpty(), target.orEmpty())
        }
        reader.endArray()
        return ranges
    }

    private fun readMappings(
        reader: JsonReader,
        dictionary: ProvenanceDictionary,
        anilistId: Int,
        episodeNumbers: Set<Int>,
    ): Map<Int, TmdbAnimeEpisodeReference> {
        val anilistIndex = dictionary.descriptors.indexOfFirst {
            it.equals("anilist:$anilistId", ignoreCase = true)
        }
        if (anilistIndex < 0) {
            reader.skipValue()
            return emptyMap()
        }

        val candidates = linkedMapOf<Int, MutableSet<TmdbAnimeEpisodeReference>>()
        reader.beginArray()
        while (reader.hasNext()) {
            var sourceIndex: Int? = null
            var targetIndex: Int? = null
            var isPresent = false
            var rangeIndexes = emptyList<Int>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "s" -> sourceIndex = reader.nextIntOrNull()
                    "t" -> targetIndex = reader.nextIntOrNull()
                    "p" -> isPresent = reader.nextBooleanOrFalse()
                    "ev" -> rangeIndexes = readActiveRangeIndexes(reader, dictionary.actions)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (!isPresent || sourceIndex == null || targetIndex == null) continue
            val sourceDescriptor = dictionary.descriptors.getOrNull(sourceIndex) ?: continue
            val targetDescriptor = dictionary.descriptors.getOrNull(targetIndex) ?: continue
            when {
                sourceIndex == anilistIndex -> {
                    parseTmdbDescriptor(targetDescriptor)?.let { tmdb ->
                        rangeIndexes.forEach { rangeIndex ->
                            dictionary.ranges.getOrNull(rangeIndex)?.let { range ->
                                addPairs(
                                    candidates = candidates,
                                    sourceRange = range.source,
                                    targetRange = range.target,
                                    tmdbId = tmdb.first,
                                    season = tmdb.second,
                                    episodeNumbers = episodeNumbers,
                                )
                            }
                        }
                    }
                }
                targetIndex == anilistIndex -> {
                    parseTmdbDescriptor(sourceDescriptor)?.let { tmdb ->
                        rangeIndexes.forEach { rangeIndex ->
                            dictionary.ranges.getOrNull(rangeIndex)?.let { range ->
                                addPairs(
                                    candidates = candidates,
                                    sourceRange = range.target,
                                    targetRange = range.source,
                                    tmdbId = tmdb.first,
                                    season = tmdb.second,
                                    episodeNumbers = episodeNumbers,
                                )
                            }
                        }
                    }
                }
            }
        }
        reader.endArray()
        return candidates.mapNotNull { (episode, references) ->
            references.singleOrNull()?.let { episode to it }
        }.toMap()
    }

    private fun readActiveRangeIndexes(
        reader: JsonReader,
        actions: List<String>,
    ): List<Int> {
        val indexes = linkedSetOf<Int>()
        reader.beginArray()
        while (reader.hasNext()) {
            var actionIndex: Int? = null
            var rangeIndex: Int? = null
            var isEffective = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "a" -> actionIndex = reader.nextIntOrNull()
                    "r" -> rangeIndex = reader.nextIntOrNull()
                    "e" -> isEffective = reader.nextBooleanOrFalse()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (!isEffective) continue
            val action = actionIndex?.let(actions::getOrNull)?.trim()?.lowercase()
            when (action) {
                "add" -> rangeIndex?.let(indexes::add)
                "remove" -> rangeIndex?.let(indexes::remove)
            }
        }
        reader.endArray()
        return indexes.toList()
    }

    private fun addPairs(
        candidates: MutableMap<Int, MutableSet<TmdbAnimeEpisodeReference>>,
        sourceRange: String,
        targetRange: String,
        tmdbId: Int,
        season: Int,
        episodeNumbers: Set<Int>,
    ) {
        if ('|' in sourceRange || '|' in targetRange) return
        val sourceSegments = sourceRange.split(',').map(String::trim).filter(String::isNotBlank)
        val targetSegments = targetRange.split(',').map(String::trim).filter(String::isNotBlank)
        if (sourceSegments.isEmpty() || sourceSegments.size != targetSegments.size) return

        sourceSegments.zip(targetSegments).forEach { (sourceText, targetText) ->
            val source = parseRange(sourceText) ?: return@forEach
            val target = parseRange(targetText) ?: return@forEach
            if (!source.isOneToOneWith(target)) return@forEach
            episodeNumbers.asSequence()
                .filter { episode -> source.contains(episode) }
                .forEach { episode ->
                    val targetEpisode = target.start + (episode - source.start)
                    if (target.contains(targetEpisode)) {
                        candidates.getOrPut(episode) { linkedSetOf() } += TmdbAnimeEpisodeReference(
                            tmdbId = tmdbId,
                            season = season,
                            episode = targetEpisode,
                        )
                    }
                }
        }
    }

    private fun parseTmdbDescriptor(value: String): Pair<Int, Int>? {
        val match = TMDB_DESCRIPTOR.matchEntire(value) ?: return null
        val tmdbId = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val season = match.groupValues[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return tmdbId to season
    }

    private fun parseRange(value: String): EpisodeNumberRange? {
        val match = RANGE.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val end = match.groupValues[2].toIntOrNull()?.takeIf { it >= start }
        return EpisodeNumberRange(start, end)
    }

    private fun File.isFresh(): Boolean {
        if (!isFile || length() < MIN_ARCHIVE_SIZE_BYTES) return false
        val age = System.currentTimeMillis() - lastModified()
        return age in 0..ARCHIVE_MAX_AGE_MS
    }

    private fun JsonReader.nextStringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.nextIntOrNull(): Int? = nextStringOrNull()?.toIntOrNull()

    private fun JsonReader.nextBooleanOrFalse(): Boolean = when (peek()) {
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.STRING -> nextString().equals("true", ignoreCase = true)
        JsonToken.NULL -> {
            nextNull()
            false
        }
        else -> {
            skipValue()
            false
        }
    }

    private data class ProvenanceDictionary(
        val actions: List<String>,
        val descriptors: List<String>,
        val ranges: List<EpisodeRange>,
    )

    private data class EpisodeRange(
        val source: String,
        val target: String,
    )

    private data class EpisodeNumberRange(
        val start: Int,
        val end: Int?,
    ) {
        fun contains(value: Int): Boolean = value >= start && (end == null || value <= end)

        fun isOneToOneWith(other: EpisodeNumberRange): Boolean {
            return when {
                end == null || other.end == null -> end == null && other.end == null
                else -> (end - start) == (other.end - other.start)
            }
        }
    }

    private companion object {
        const val SOURCE = "AniBridge"
        const val ARCHIVE_URL = "https://mappings.anibridge.eliasbenb.dev/data/provenance.zip"
        const val ARCHIVE_FILE_NAME = "streamcenter-anibridge-provenance.zip"
        const val ARCHIVE_TIMEOUT_SECONDS = 30L
        const val MIN_ARCHIVE_SIZE_BYTES = 100_000L
        const val ARCHIVE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        val TMDB_DESCRIPTOR = Regex("""tmdb_show:(\d+):s(\d+)""", RegexOption.IGNORE_CASE)
        val RANGE = Regex("""(\d+)(?:-(\d*)?)?""")
    }
}
