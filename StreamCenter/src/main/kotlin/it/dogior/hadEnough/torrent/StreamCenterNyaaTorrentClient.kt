package it.dogior.hadEnough.torrent

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class StreamCenterNyaaSearchDiagnostics(
    val plannedQueries: List<String>,
    val executedQueries: List<String>,
    val failedQueries: List<String>,
    val rawCandidateCount: Int,
    val eligibleCandidateCount: Int,
    val uniqueCandidateCount: Int,
)

internal data class StreamCenterNyaaSearchResult(
    val candidates: List<StreamCenterTorrentCandidate>,
    val diagnostics: StreamCenterNyaaSearchDiagnostics,
)

internal object StreamCenterNyaaTorrentClient : StreamCenterTorrentSourceClient {
    private const val MAX_PARSED_RESULTS = 24

    override suspend fun search(
        definition: StreamCenterTorrentSourceDefinition,
        sourceUrl: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<StreamCenterTorrentCandidate> =
        searchWithDiagnostics(definition, sourceUrl, context, filters).candidates

    suspend fun searchWithDiagnostics(
        definition: StreamCenterTorrentSourceDefinition,
        sourceUrl: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): StreamCenterNyaaSearchResult {
        val plannedQueries = StreamCenterTorrentQueryBuilder.buildForNyaa(
            definition = definition,
            context = context,
            filters = filters,
        )
        val executedQueries = mutableListOf<String>()
        val failedQueries = mutableListOf<String>()
        var rawCandidateCount = 0
        var eligibleCandidateCount = 0
        val results = mutableListOf<StreamCenterTorrentCandidate>()
        val seenHashes = mutableSetOf<String>()

        fun currentResult(): StreamCenterNyaaSearchResult = StreamCenterNyaaSearchResult(
            candidates = results.toList(),
            diagnostics = StreamCenterNyaaSearchDiagnostics(
                plannedQueries = plannedQueries,
                executedQueries = executedQueries.toList(),
                failedQueries = failedQueries.toList(),
                rawCandidateCount = rawCandidateCount,
                eligibleCandidateCount = eligibleCandidateCount,
                uniqueCandidateCount = results.size,
            ),
        )

        val category = torrentUrlQueryParameter(sourceUrl, "c")
            ?.takeIf { value -> NYAA_CATEGORY.matches(value) }
            ?: definition.nyaaCategory
            ?: return currentResult()
        val baseUrl = cleanTorrentBaseUrl(sourceUrl)
        if (baseUrl.isBlank()) return currentResult()

        for (query in plannedQueries) {
            val url = "$baseUrl/?page=rss&c=$category&f=0&q=${encodeTorrentPathValue(query)}"
            executedQueries += query
            val items = try {
                val body = app.get(
                    url = url,
                    headers = RSS_HEADERS,
                    timeout = 6L,
                ).text
                Jsoup.parse(body, baseUrl, Parser.xmlParser()).getElementsByTag("item")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failedQueries += query
                continue
            }

            for (item in items) {
                val title = item.firstDirectText("title") ?: continue
                val infoHash = item.nyaaText("infoHash") ?: continue
                val size = item.nyaaText("size")
                val torrentUrl = item.firstDirectText("link")
                    ?.let { link -> NYAA_DOWNLOAD_PATH.find(link)?.groupValues?.getOrNull(1) }
                    ?.let { torrentId -> "$baseUrl/download/$torrentId.torrent" }
                rawCandidateCount++
                val candidate = StreamCenterTorrentCandidate(
                    title = cleanDisplayText(title),
                    infoHash = infoHash,
                    size = size,
                    sizeBytes = StreamCenterTorrentMetadata.parseSizeBytes(size),
                    seeders = parsePositiveInt(item.nyaaText("seeders")),
                    leechers = parsePositiveInt(item.nyaaText("leechers")),
                    fileMetadataRequest = torrentUrl?.let { url ->
                        StreamCenterTorrentFileMetadataRequest(
                            url = url,
                            format = StreamCenterTorrentFileMetadataFormat.TORRENT,
                            expectedInfoHash = infoHash,
                        )
                    },
                )
                if (!candidate.isEligibleFor(context, filters)) continue
                eligibleCandidateCount++
                val normalizedHash = StreamCenterTorrentMagnet.infoHash(infoHash) ?: continue
                if (!seenHashes.add(normalizedHash)) continue
                results += candidate
                if (results.size >= MAX_PARSED_RESULTS) return currentResult()
            }
        }
        return currentResult()
    }

    private fun Element.firstDirectText(tag: String): String? =
        getElementsByTag(tag)
            .firstOrNull()
            ?.text()
            ?.takeIf(String::isNotBlank)

    private fun Element.nyaaText(localName: String): String? =
        allElements
            .firstOrNull { element ->
                element.tagName()
                    .substringAfter(':', element.tagName())
                    .equals(localName, ignoreCase = true)
            }
            ?.text()
            ?.takeIf(String::isNotBlank)

    private val RSS_HEADERS = mapOf(
        "Accept" to "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5",
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "Chrome/124.0.0.0 Safari/537.36",
    )
    private val NYAA_CATEGORY = Regex("""\d+_\d+""")
    private val NYAA_DOWNLOAD_PATH = Regex("""/download/(\d+)\.torrent(?:$|[?#])""")
}
