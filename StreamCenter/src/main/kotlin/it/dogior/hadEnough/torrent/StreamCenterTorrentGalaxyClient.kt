package it.dogior.hadEnough.torrent

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

internal object StreamCenterTorrentGalaxyClient : StreamCenterTorrentSourceClient {
    private const val MAX_PARSED_RESULTS = 24

    override suspend fun search(
        definition: StreamCenterTorrentSourceDefinition,
        sourceUrl: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<StreamCenterTorrentCandidate> = supervisorScope {
        val baseUrl = cleanTorrentBaseUrl(sourceUrl)
        StreamCenterTorrentQueryBuilder.buildForFilters(context, filters, baseLimit = 2)
            .map { query ->
                async { searchQuery(baseUrl, query, context, filters) }
            }
            .awaitAll()
            .flatten()
            .distinctBy { candidate ->
                StreamCenterTorrentMagnet.infoHash(candidate.infoHash ?: candidate.magnetUrl)
            }
            .take(MAX_PARSED_RESULTS)
    }

    private suspend fun searchQuery(
        baseUrl: String,
        query: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<StreamCenterTorrentCandidate> {
        val category = if (context.isMovie) "Movies" else "TV"
        val encodedQuery = encodeTorrentPathValue(query)
        val urls = listOf(
            "$baseUrl/get-posts/keywords:$encodedQuery:category:$category:format:json/",
            "$baseUrl/get-posts/keywords:$encodedQuery:format:json",
        )
        for (url in urls) {
            val body = try {
                app.get(
                    url = url,
                    headers = JSON_HEADERS,
                    cacheTime = 0,
                    timeout = 4L,
                ).text
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                continue
            }
            val candidates = parseEntries(body)
                .asSequence()
                .filter { entry -> entry.matchesImdb(context.imdbId) }
                .filter { entry -> entry.matchesCategory(category) }
                .mapNotNull { entry -> entry.toCandidate() }
                .filter { candidate -> candidate.isEligibleFor(context, filters) }
                .take(MAX_PARSED_RESULTS)
                .toList()
            if (candidates.isNotEmpty()) return candidates
        }
        return emptyList()
    }

    private fun parseEntries(body: String): List<JSONObject> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val entries = mutableListOf<JSONObject>()
        runCatching {
            when (trimmed.first()) {
                '[' -> collectArray(JSONArray(trimmed), entries)
                '{' -> collectObject(JSONObject(trimmed), entries)
            }
        }
        return entries
    }

    private fun collectArray(array: JSONArray, target: MutableList<JSONObject>) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> collectObject(value, target)
                is JSONArray -> collectArray(value, target)
            }
        }
    }

    private fun collectObject(value: JSONObject, target: MutableList<JSONObject>) {
        val hasTorrentHash = listOf("h", "hash", "infoHash", "info_hash", "magnet")
            .any(value::has)
        val hasTitle = listOf("n", "name", "title").any(value::has)
        if (hasTitle && hasTorrentHash) {
            target += value
            return
        }

        listOf("posts", "results", "data", "torrents", "items").forEach { key ->
            when (val nested = value.opt(key)) {
                is JSONArray -> collectArray(nested, target)
                is JSONObject -> collectObject(nested, target)
            }
        }
    }

    private fun JSONObject.firstText(vararg keys: String): String? {
        keys.forEach { key ->
            val value = opt(key)
            if (value != null && value !== JSONObject.NULL) {
                value.toString().trim().takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.toCandidate(): StreamCenterTorrentCandidate? {
        val title = firstText("n", "name", "title") ?: return null
        val hashOrMagnet = firstText("h", "hash", "magnet", "infoHash", "info_hash")
        val infoHash = StreamCenterTorrentMagnet.infoHash(hashOrMagnet) ?: return null
        return StreamCenterTorrentCandidate(
            title = cleanDisplayText(title),
            infoHash = infoHash,
            magnetUrl = hashOrMagnet?.takeIf {
                it.startsWith("magnet:?", ignoreCase = true)
            },
            size = readSize(),
            sizeBytes = readSizeBytes(),
            seeders = parsePositiveInt(opt("se"))
                ?: parsePositiveInt(opt("seeders")),
            leechers = parsePositiveInt(opt("le"))
                ?: parsePositiveInt(opt("leechers")),
        )
    }

    private fun JSONObject.matchesImdb(expectedImdbId: String?): Boolean {
        val expected = expectedImdbId
            ?.trim()
            ?.lowercase()
            ?.removePrefix("tt")
            ?.takeIf(String::isNotBlank)
            ?: return true
        val actual = firstText("i", "imdb", "imdbId")
            ?.trim()
            ?.lowercase()
            ?.removePrefix("tt")
            ?.takeIf(String::isNotBlank)
            ?: return true
        return actual == expected
    }

    private fun JSONObject.matchesCategory(expected: String): Boolean {
        val actual = firstText("c", "category")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return true
        return actual.equals(expected, ignoreCase = true)
    }

    private fun JSONObject.readSize(): String? {
        val value = opt("s")
        return when (value) {
            is Number -> value.toLong().takeIf { it > 0 }?.let(::formatTorrentBytes)
            null, JSONObject.NULL -> firstText("size")
            else -> value.toString()
                .trim()
                .takeIf(String::isNotBlank)
                ?.let { raw ->
                    raw.toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?.let(::formatTorrentBytes)
                        ?: raw
                }
        }
    }

    private fun JSONObject.readSizeBytes(): Long? {
        val value = opt("s")
        return when (value) {
            is Number -> value.toLong().takeIf { bytes -> bytes > 0L }
            null, JSONObject.NULL -> StreamCenterTorrentMetadata.parseSizeBytes(firstText("size"))
            else -> StreamCenterTorrentMetadata.parseSizeBytes(value.toString())
                ?: StreamCenterTorrentMetadata.parseSizeBytes(firstText("size"))
        }
    }

    private val JSON_HEADERS = mapOf(
        "Accept" to "application/json, text/plain;q=0.9, */*;q=0.5",
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "Chrome/124.0.0.0 Safari/537.36",
    )
}
