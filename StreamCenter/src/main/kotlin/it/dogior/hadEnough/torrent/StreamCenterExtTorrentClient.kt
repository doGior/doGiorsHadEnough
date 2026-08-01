package it.dogior.hadEnough.torrent

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal object StreamCenterExtCloudflareSession {
    @Volatile
    private var userAgent: String? = null

    fun updateUserAgent(value: String?) {
        userAgent = value?.trim()?.takeIf(String::isNotBlank).also { currentUserAgent ->
            if (currentUserAgent != null) {
                WebViewResolver.webViewUserAgent = currentUserAgent
            }
        }
    }

    fun isReady(url: String): Boolean = requestHeaders(url) != null

    fun requestHeaders(url: String): Map<String, String>? {
        val currentUserAgent = userAgent
            ?: WebViewResolver.webViewUserAgent?.trim()?.takeIf(String::isNotBlank)
            ?: runCatching {
                (getContext() as? Context)?.let(WebSettings::getDefaultUserAgent)
            }.getOrNull()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(url)
        }.getOrNull()?.takeIf { value ->
            value.split(';').any { cookie ->
                cookie.trim().startsWith("cf_clearance=", ignoreCase = true)
            }
        } ?: return null
        return mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.7",
            "Cookie" to cookies,
            "User-Agent" to currentUserAgent,
        )
    }
}

internal object StreamCenterExtTorrentClient : StreamCenterTorrentSourceClient {
    override suspend fun search(
        definition: StreamCenterTorrentSourceDefinition,
        sourceUrl: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<StreamCenterTorrentCandidate> = supervisorScope {
        val baseUrl = cleanTorrentBaseUrl(sourceUrl)
        val headers = StreamCenterExtCloudflareSession.requestHeaders(baseUrl)
            ?: return@supervisorScope emptyList()
        val queries = (
            StreamCenterTorrentQueryBuilder.buildItalianVariants(context, filters, limit = 2) +
                StreamCenterTorrentQueryBuilder.buildBatchItalianVariants(
                    context,
                    filters,
                    limit = 2,
                )
            ).distinctBy(String::lowercase)
        queries.map { query ->
            async {
                searchQuery(
                    definition = definition,
                    baseUrl = baseUrl,
                    headers = headers,
                    query = query,
                    context = context,
                    filters = filters,
                )
            }
        }.awaitAll()
            .flatten()
            .filter { candidate -> candidate.isEligibleFor(context, filters) }
            .distinctBy { candidate ->
                StreamCenterTorrentMagnet.infoHash(candidate.infoHash ?: candidate.magnetUrl)
            }
    }

    private suspend fun searchQuery(
        definition: StreamCenterTorrentSourceDefinition,
        baseUrl: String,
        headers: Map<String, String>,
        query: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<StreamCenterTorrentCandidate> {
        val response = try {
            StreamCenterTorrentRequestGate.rateLimited(definition.key, REQUEST_INTERVAL_MS) {
                app.get(
                    url = "$baseUrl/browse/?q=${encodeTorrentPathValue(query)}&sort=seeds&order=desc",
                    headers = headers,
                    cacheTime = 0,
                    timeout = 9L,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return emptyList()
        }
        if (response.code !in 200..299) return emptyList()
        val rows = parseListing(response.text, baseUrl)
            .asSequence()
            .filter { row ->
                StreamCenterTorrentCandidate(
                    title = row.title,
                    size = row.size,
                    sizeBytes = row.sizeBytes,
                    seeders = row.seeders,
                ).isEligibleFor(context, filters)
            }
            .take(MAX_DETAIL_REQUESTS)
            .toList()
        return supervisorScope {
            rows.map { row ->
                async { loadDetails(baseUrl, headers, row) }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun loadDetails(
        baseUrl: String,
        headers: Map<String, String>,
        row: ExtListingRow,
    ): StreamCenterTorrentCandidate? {
        val response = try {
            detailSemaphore.withPermit {
                app.get(
                    url = row.detailUrl,
                    headers = headers,
                    cacheTime = 0,
                    timeout = 9L,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299) return null
        val document = Jsoup.parse(response.text, row.detailUrl)
        val magnetUrl = inlineMagnet(response.text)
            ?: requestMagnet(baseUrl, row.detailUrl, headers, response.text, document)
            ?: return null
        val infoHash = StreamCenterTorrentMagnet.infoHash(magnetUrl) ?: return null
        return StreamCenterTorrentCandidate(
            title = row.title,
            infoHash = infoHash,
            magnetUrl = magnetUrl,
            size = row.size,
            sizeBytes = row.sizeBytes,
            seeders = row.seeders,
            availableFiles = parseFiles(document),
        )
    }

    private suspend fun requestMagnet(
        baseUrl: String,
        detailUrl: String,
        headers: Map<String, String>,
        html: String,
        document: Document,
    ): String? {
        val pageToken = PAGE_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val csrfToken = CSRF_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val torrentId = document.selectFirst(".download-btn-magnet[data-id], .download-btn-torrent[data-id]")
            ?.attr("data-id")
            ?.trim()
            ?.takeIf { value -> value.all(Char::isDigit) }
            ?: return null
        val timestamp = System.currentTimeMillis() / 1_000L
        val signature = sha256("$torrentId|$timestamp|$pageToken")
        val response = try {
            detailSemaphore.withPermit {
                app.post(
                    url = "$baseUrl/ajax/getTorrentMagnet.php",
                    headers = headers + mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Referer" to detailUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                    data = mapOf(
                        "torrent_id" to torrentId,
                        "download_type" to "magnet",
                        "timestamp" to timestamp.toString(),
                        "hmac" to signature,
                        "sessid" to csrfToken,
                    ),
                    cacheTime = 0,
                    timeout = 8L,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        if (response.code !in 200..299) return null
        val payload = runCatching { JSONObject(response.text) }.getOrNull() ?: return null
        if (!payload.optBoolean("success")) return null
        val magnetUrl = payload.optString("url").trim()
        if (magnetUrl.startsWith("magnet:", ignoreCase = true)) return magnetUrl
        return StreamCenterTorrentMagnet.infoHash(payload.optString("hash"))
            ?.let { hash -> "magnet:?xt=urn:btih:$hash" }
    }

    internal fun parseListing(html: String, baseUrl: String): List<ExtListingRow> {
        val document = Jsoup.parse(html, baseUrl)
        return buildList {
            for (row in document.select("table.table-striped.table-hover tbody tr")) {
                val link = row.selectFirst("a.torrent-title-link")
                    ?: row.selectFirst("td.text-left .float-left a")
                    ?: continue
                val title = cleanDisplayText(link.select("b").joinToString("") { it.text() })
                    .ifBlank { cleanDisplayText(link.text()) }
                if (title.isBlank()) continue
                val detailUrl = sameOriginUrl(baseUrl, link.attr("href")) ?: continue
                val size = row.select("td.nowrap-td .add-block-wrapper")
                    .firstOrNull { wrapper ->
                        wrapper.selectFirst("span.add-block")?.text()
                            ?.contains("size", ignoreCase = true) == true
                    }
                    ?.select("span:not(.add-block)")
                    ?.text()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                add(
                    ExtListingRow(
                        title = title,
                        detailUrl = detailUrl,
                        size = size,
                        sizeBytes = StreamCenterTorrentMetadata.parseSizeBytes(size),
                        seeders = parsePositiveInt(
                            row.selectFirst("td .add-block-wrapper span.text-success")?.text(),
                        ),
                    ),
                )
                if (this.size >= MAX_LISTING_ROWS) break
            }
        }
    }

    internal fun parseFiles(document: Document): List<StreamCenterTorrentFile>? {
        var fileIndex = 0
        return buildList {
            for (row in document.select("#torrent_files table tr")) {
                val fileCell = row.selectFirst("td.file-name-line-td") ?: continue
                val currentIndex = fileIndex++
                val path = fileCell.selectFirst("span.folder-name a")
                    ?.text()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: cleanDisplayText(fileCell.text()).takeIf(String::isNotBlank)
                    ?: continue
                val sizeText = row.select("td.file-size-td div.file-size")
                    .map { it.text().trim() }
                    .filter(String::isNotBlank)
                    .lastOrNull()
                add(
                    StreamCenterTorrentFile(
                        index = currentIndex,
                        path = path,
                        sizeBytes = StreamCenterTorrentMetadata.parseSizeBytes(sizeText),
                    ),
                )
                if (fileIndex >= MAX_FILE_ROWS) break
            }
        }.takeIf(List<StreamCenterTorrentFile>::isNotEmpty)
    }

    internal fun inlineMagnet(html: String): String? = MAGNET_REGEX.find(html)
        ?.value
        ?.replace("&amp;", "&")
        ?.takeIf { value -> StreamCenterTorrentMagnet.infoHash(value) != null }

    private fun sameOriginUrl(baseUrl: String, href: String): String? {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val target = runCatching { base.resolve(href.trim()) }.getOrNull() ?: return null
        if (target.scheme !in setOf("http", "https")) return null
        if (!target.host.equals(base.host, ignoreCase = true)) return null
        return target.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    internal data class ExtListingRow(
        val title: String,
        val detailUrl: String,
        val size: String?,
        val sizeBytes: Long?,
        val seeders: Int?,
    )

    private const val REQUEST_INTERVAL_MS = 750L
    private const val MAX_LISTING_ROWS = 50
    private const val MAX_DETAIL_REQUESTS = 8
    private const val MAX_FILE_ROWS = 2_000
    private val detailSemaphore = Semaphore(2)
    private val PAGE_TOKEN_REGEX = Regex(
        """window\.pageToken\s*=\s*(?:\\'|')([a-fA-F0-9]{32})(?:\\'|')""",
    )
    private val CSRF_TOKEN_REGEX = Regex(
        """window\.csrfToken\s*=\s*(?:\\'|')([a-fA-F0-9]{32})(?:\\'|')""",
    )
    private val MAGNET_REGEX = Regex(
        """magnet:\?[^\s\"'<>]+""",
        RegexOption.IGNORE_CASE,
    )
}
