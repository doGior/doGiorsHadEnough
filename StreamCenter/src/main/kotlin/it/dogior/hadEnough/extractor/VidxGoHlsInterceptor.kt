package it.dogior.hadEnough.extractor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

internal class VidxGoHlsInterceptor(
    initialUrl: String,
    private val renewUrl: () -> String?,
    private val fetchPlaylist: (HttpUrl) -> Response,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : Interceptor {
    private data class Resource(val path: List<String>, val playlist: Boolean)
    private data class Attempt(val resource: Resource, val url: HttpUrl, val generation: Long)

    private val lock = Any()
    private val root = Resource(emptyList(), true)
    private val aliases = mutableMapOf<HttpUrl, Resource>()
    private val currentUrls = mutableMapOf<Resource, HttpUrl>()
    private var generation = 0L
    private var lastRenewal: Long? = null

    init {
        val url = requireNotNull(initialUrl.toHttpUrlOrNull())
        aliases[url] = root
        currentUrls[root] = url
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val attempt = synchronized(lock) {
            aliases[request.url]?.let { resource -> Attempt(resource, resolve(resource), generation) }
        } ?: return chain.proceed(request)
        var response = chain.proceed(request.newBuilder().url(attempt.url).build())
        var active = attempt
        if (response.code == 401 || response.code == 403) {
            val retry = try {
                synchronized(lock) {
                    if (chain.call().isCanceled()) throw InterruptedIOException("Playback cancelled")
                    recover(attempt)
                }
            } catch (error: IOException) {
                response.close()
                throw error
            }
            if (retry != null) {
                response.close()
                active = retry
                response = chain.proceed(request.newBuilder().url(retry.url)
                    .header("Cache-Control", "no-cache").build())
            }
        }
        if (response.isSuccessful && active.resource.playlist) {
            try {
                val references = readPlaylist(response)
                synchronized(lock) {
                    remember(active.resource, response.request.url, references, active.generation == generation)
                }
            } catch (error: IOException) {
                response.close()
                throw error
            }
        }
        return response
    }

    private fun recover(attempt: Attempt): Attempt? {
        try {
            if (attempt.generation == generation) {
                val now = nowMillis()
                if (lastRenewal?.let { now - it < 5_000L } == true) return null
                lastRenewal = now
                val renewed = renewUrl()?.toHttpUrlOrNull() ?: return null
                generation++
                currentUrls.clear()
                currentUrls[root] = renewed
                aliases[renewed] = root
            }
            return Attempt(attempt.resource, resolve(attempt.resource), generation)
        } catch (error: Exception) {
            if (error is InterruptedIOException || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("VidxGo renewal interrupted").apply { initCause(error) }
            }
            return null
        }
    }

    private fun resolve(resource: Resource): HttpUrl {
        currentUrls[resource]?.let { return it }
        if (resource.path.isEmpty() || resource.path.size > 8) throw IOException("Unknown VidxGo resource")
        val parent = Resource(resource.path.dropLast(1), true)
        val parentUrl = resolve(parent)
        fetchPlaylist(parentUrl).use { response ->
            if (!response.isSuccessful) throw IOException("VidxGo playlist HTTP ${response.code}")
            remember(parent, response.request.url, readPlaylist(response))
        }
        return currentUrls[resource] ?: throw IOException("Resource missing from renewed VidxGo playlist")
    }

    private fun remember(parent: Resource, url: HttpUrl, references: List<HlsReference>, current: Boolean = true) {
        if (current) currentUrls[parent] = url
        aliases[url] = parent
        for (reference in references) {
            val child = Resource(parent.path + reference.key, reference.playlist)
            if (current) currentUrls[child] = reference.url
            aliases[reference.url] = child
        }
    }

    private fun readPlaylist(response: Response): List<HlsReference> {
        val bytes = response.peekBody(MAX_PLAYLIST_BYTES + 1).bytes()
        if (bytes.size > MAX_PLAYLIST_BYTES) throw IOException("VidxGo playlist too large")
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("Invalid VidxGo playlist")
        return parseHlsReferences(response.request.url, text)
    }

    companion object {
        private const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
    }
}

internal data class HlsReference(val key: String, val url: HttpUrl, val playlist: Boolean)

internal fun parseHlsReferences(baseUrl: HttpUrl, text: String): List<HlsReference> {
    val references = mutableListOf<HlsReference>()
    var sequence = 0L
    var variant: String? = null
    val attributes = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    fun values(line: String): Map<String, String> = attributes.findAll(line.substringAfter(':'))
        .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }
    fun identity(tag: String, values: Map<String, String>): String = tag + values
        .filterKeys { it != "URI" }.toSortedMap().entries.joinToString { "${it.key}=${it.value}" }
    fun add(key: String, uri: String?, playlist: Boolean) {
        val url = uri?.let(baseUrl::resolve) ?: return
        references += HlsReference(key, url, playlist)
    }
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0L
            line.startsWith("#EXT-X-STREAM-INF:") -> variant = identity("variant:", values(line))
            line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> {
                val fields = values(line)
                add(identity(line.substringBefore(':'), fields), fields["URI"], true)
            }
            line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-MAP:") ||
                line.startsWith("#EXT-X-SESSION-KEY:") -> {
                val fields = values(line)
                add("${line.substringBefore(':')}:$sequence:${fields["KEYFORMAT"].orEmpty()}", fields["URI"], false)
            }
            line.isNotEmpty() && !line.startsWith('#') -> {
                add(variant ?: "segment:$sequence", line, variant != null)
                if (variant == null) sequence++
                variant = null
            }
        }
    }
    return references
}
