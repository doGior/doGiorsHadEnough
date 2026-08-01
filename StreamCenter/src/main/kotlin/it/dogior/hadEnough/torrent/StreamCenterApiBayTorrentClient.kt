package it.dogior.hadEnough.torrent

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray

internal object StreamCenterApiBayTorrentClient : StreamCenterTorrentSourceClient {
    private const val MAX_RESULTS_PER_QUERY = 100
    private const val COOLDOWN_MS = 60_000L

    override suspend fun search(
        definition: StreamCenterTorrentSourceDefinition,
        sourceUrl: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<StreamCenterTorrentCandidate> = supervisorScope {
        if (StreamCenterTorrentRequestGate.isCoolingDown(definition.key)) {
            return@supervisorScope emptyList()
        }
        val baseUrl = cleanTorrentBaseUrl(sourceUrl)
        (
            StreamCenterTorrentQueryBuilder.buildItalianVariants(context, filters, limit = 4) +
                StreamCenterTorrentQueryBuilder.buildBatchItalianVariants(
                    context,
                    filters,
                    limit = 2,
                )
            )
            .distinctBy(String::lowercase)
            .map { query ->
                async { searchQuery(definition, baseUrl, query) }
            }
            .awaitAll()
            .flatten()
            .filter { candidate -> candidate.isEligibleFor(context, filters) }
            .distinctBy { candidate ->
                StreamCenterTorrentMagnet.infoHash(candidate.infoHash ?: candidate.magnetUrl)
            }
    }

    private suspend fun searchQuery(
        definition: StreamCenterTorrentSourceDefinition,
        baseUrl: String,
        query: String,
    ): List<StreamCenterTorrentCandidate> {
        val response = try {
            app.get(
                url = "$baseUrl/q.php?q=${encodeTorrentPathValue(query)}",
                headers = mapOf("Accept" to "application/json"),
                cacheTime = 0,
                timeout = 7L,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return emptyList()
        }
        if (response.code == 429) {
            StreamCenterTorrentRequestGate.startCooldown(definition.key, COOLDOWN_MS)
            return emptyList()
        }
        if (response.code !in 200..299) return emptyList()

        val entries = runCatching { JSONArray(response.text) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(entries.length(), MAX_RESULTS_PER_QUERY)) {
                val entry = entries.optJSONObject(index) ?: continue
                val title = cleanDisplayText(entry.optString("name"))
                if (title.equals("No results returned", ignoreCase = true)) continue
                val infoHash = StreamCenterTorrentMagnet.infoHash(entry.optString("info_hash"))
                    ?: continue
                val torrentId = entry.optString("id").trim().takeIf(String::isNotBlank)
                val bytes = entry.optString("size")
                    .replace(",", "")
                    .toLongOrNull()
                    ?.takeIf { it > 0L }
                add(
                    StreamCenterTorrentCandidate(
                        title = title,
                        infoHash = infoHash,
                        size = bytes?.let(::formatTorrentBytes),
                        sizeBytes = bytes,
                        seeders = parsePositiveInt(entry.opt("seeders")),
                        leechers = parsePositiveInt(entry.opt("leechers")),
                        fileMetadataRequest = torrentId?.let { id ->
                            StreamCenterTorrentFileMetadataRequest(
                                url = "$baseUrl/f.php?id=${encodeTorrentPathValue(id)}",
                                format = StreamCenterTorrentFileMetadataFormat.APIBAY,
                                expectedInfoHash = infoHash,
                            )
                        },
                    ),
                )
            }
        }
    }
}
