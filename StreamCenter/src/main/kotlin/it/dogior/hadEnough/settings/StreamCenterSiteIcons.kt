package it.dogior.hadEnough.settings

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object StreamCenterSiteIcons {
    private val resolvedUrls = ConcurrentHashMap<String, String>()
    private val resolutionLocks = ConcurrentHashMap<String, Any>()

    fun cached(siteUrl: String): String? {
        return origin(siteUrl)?.let(resolvedUrls::get)
    }

    fun resolve(siteUrl: String): String? {
        val origin = origin(siteUrl) ?: return null
        resolvedUrls[origin]?.let { return it }
        val lock = resolutionLocks.computeIfAbsent(origin) { Any() }
        return synchronized(lock) {
            resolvedUrls[origin] ?: discover(origin).also { resolvedUrls[origin] = it }
        }.also {
            resolutionLocks.remove(origin, lock)
        }
    }

    private fun discover(origin: String): String {
        return runCatching {
            Jsoup.connect(origin)
                .userAgent("Mozilla/5.0 (Android 14; Mobile)")
                .timeout(8_000)
                .maxBodySize(512_000)
                .ignoreHttpErrors(true)
                .followRedirects(true)
                .get()
                .select("link[href]")
                .asSequence()
                .filter { element -> element.attr("rel").lowercase(Locale.ROOT).contains("icon") }
                .sortedByDescending { element ->
                    val href = element.attr("href").lowercase(Locale.ROOT)
                    when {
                        href.endsWith(".png") || href.endsWith(".webp") -> 3
                        element.attr("rel").lowercase(Locale.ROOT).contains("apple-touch-icon") -> 2
                        href.endsWith(".jpg") || href.endsWith(".jpeg") -> 1
                        else -> 0
                    }
                }
                .map { element -> element.absUrl("href").trim() }
                .firstOrNull(::isWebUrl)
        }.getOrNull() ?: "$origin/favicon.ico"
    }

    private fun origin(siteUrl: String): String? = runCatching {
        val uri = URI(siteUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "https" && scheme != "http") || uri.host.isNullOrBlank()) return@runCatching null
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        "$scheme://${uri.host}$port"
    }.getOrNull()

    private fun isWebUrl(value: String): Boolean {
        return runCatching {
            URI(value).scheme?.lowercase(Locale.ROOT) in setOf("https", "http")
        }.getOrDefault(false)
    }
}
