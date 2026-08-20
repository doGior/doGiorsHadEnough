package it.dogior.hadEnough.settings

import it.dogior.hadEnough.util.StreamCenterVpnGuard
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
        StreamCenterVpnGuard.requireInternetAccess()
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
                .filter { element -> isWebUrl(element.absUrl("href").trim()) }
                .filterNot(::isSvgIcon)
                .maxByOrNull(::iconResolutionScore)
                ?.absUrl("href")
                ?.trim()
                ?.takeIf(::isWebUrl)
        }.getOrNull() ?: "$origin/favicon.ico"
    }

    private fun iconResolutionScore(element: org.jsoup.nodes.Element): Int {
        val rel = element.attr("rel").lowercase(Locale.ROOT)
        val href = element.absUrl("href").lowercase(Locale.ROOT)
        if (rel.contains("mask-icon")) return Int.MIN_VALUE
        val declaredSize = element.attr("sizes").lowercase(Locale.ROOT)
            .split(Regex("[\\s,]+"))
            .mapNotNull { token -> token.substringBefore('x').toIntOrNull() }
            .maxOrNull() ?: 0
        var score = declaredSize
        when {
            href.endsWith(".png") || href.endsWith(".webp") -> score += 64
            href.endsWith(".jpg") || href.endsWith(".jpeg") -> score += 16
            href.endsWith(".ico") -> score -= 64
        }
        if (rel.contains("apple-touch-icon")) score += 180
        return score
    }

    private fun isSvgIcon(element: org.jsoup.nodes.Element): Boolean {
        val href = element.absUrl("href").lowercase(Locale.ROOT).substringBefore('?').substringBefore('#')
        val type = element.attr("type").lowercase(Locale.ROOT)
        return href.endsWith(".svg") || type.contains("svg")
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
