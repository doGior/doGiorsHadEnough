package it.dogior.hadEnough.torrent

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.api.getContext
import it.dogior.hadEnough.StreamCenterPlugin
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

internal object StreamCenterExtCloudflareSession {
    private const val PREFERENCES = "streamcenter_ext_cloudflare_session"
    private const val USER_AGENT_KEY = "verified_user_agent"
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun context(): Context? = StreamCenterPlugin.activeContext
        ?: runCatching { getContext() as? Context }.getOrNull()

    private fun preferences() = context()?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun endpointHost(url: String): String? = url.toHttpUrlOrNull()?.host?.removePrefix("www.")

    private fun storedUserAgent(url: String): String? {
        val prefs = preferences() ?: return null
        return (prefs.getString("$USER_AGENT_KEY:${endpointHost(url)}", null)
            ?: prefs.getString(USER_AGENT_KEY, null))?.trim()?.takeIf(String::isNotBlank)
    }

    fun verificationUserAgent(url: String, browserUserAgent: String): String =
        if (hasClearanceCookie(url)) storedUserAgent(url) ?: browserUserAgent else browserUserAgent

    fun beginVerification(url: String, userAgent: String) {
        val host = endpointHost(url) ?: return
        if (userAgent.isBlank()) return
        preferences()?.edit()?.putString("$USER_AGENT_KEY:$host", userAgent.trim())?.apply()
    }

    fun clear() {
        preferences()?.edit()?.clear()?.apply()
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies { cookieManager.flush() }
        }
    }

    fun hasClearanceCookie(url: String): Boolean = clearanceCookie(url) != null

    fun clearanceCookie(url: String): String? = StreamCenterExtSessionCookies.clearance(cookiesFor(url))

    fun requestHeaders(url: String): Map<String, String> = buildMap {
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "it-IT,it;q=0.9,en;q=0.7")
        put("User-Agent", storedUserAgent(url) ?: runCatching {
            context()?.let(WebSettings::getDefaultUserAgent)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: FALLBACK_USER_AGENT)
        cookiesFor(url)?.let { put("Cookie", it) }
    }

    fun refreshRequestHeaders(url: String, headers: Map<String, String>): Map<String, String> {
        val current = requestHeaders(url)
        val cookies = StreamCenterExtSessionCookies.merge(
            browserCookies = current["Cookie"],
            responseCookies = StreamCenterExtSessionCookies.parse(headers.entries
                .firstOrNull { it.key.equals("Cookie", true) }?.value),
        )
        return headers.filterKeys { name ->
            !name.equals("Cookie", true) && !name.equals("User-Agent", true)
        }.toMutableMap().apply {
            put("User-Agent", current.getValue("User-Agent"))
            if (cookies.isNotEmpty()) put("Cookie", cookieHeader(cookies))
        }
    }

    @Synchronized
    fun rememberResponseCookies(response: Response) {
        val responses = generateSequence(response) { it.priorResponse }.toList().asReversed()
        if (responses.none { it.headers.values("Set-Cookie").isNotEmpty() }) return
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        var changed = false
        responses.forEach { hop ->
            val url = hop.request.url
            if (!url.isHttps || StreamCenterExtDomain.entries.none {
                    it.baseUrl.toHttpUrlOrNull()?.host == url.host.removePrefix("www.")
                }) return@forEach
            val sentCookieHeader = hop.request.header("Cookie")
            val currentCookieHeader = manager.getCookie(url.toString())
            val sentCookies = StreamCenterExtSessionCookies.parse(sentCookieHeader)
            val currentCookies = StreamCenterExtSessionCookies.parse(currentCookieHeader)
            val sameClearance = StreamCenterExtSessionCookies.clearance(sentCookieHeader) ==
                StreamCenterExtSessionCookies.clearance(currentCookieHeader)
            hop.headers.values("Set-Cookie").forEach cookie@ { header ->
                val cookie = Cookie.parse(url, header) ?: return@cookie
                if (StreamCenterExtSessionCookies.isCloudflareCookie(cookie.name)) {
                    if (!sameClearance || currentCookies[cookie.name] != sentCookies[cookie.name] ||
                        hop.request.header("User-Agent") != requestHeaders(url.toString())["User-Agent"]) {
                        return@cookie
                    }
                }
                manager.setCookie(url.toString(), header)
                changed = true
            }
        }
        if (changed) manager.flush()
    }

    fun headersWithResponseCookies(
        url: String,
        requestHeaders: Map<String, String>,
        responseHeaders: Headers,
    ): Map<String, String> {
        val cookies = StreamCenterExtSessionCookies.parse(requestHeaders["Cookie"]).toMutableMap()
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl != null) responseHeaders.values("Set-Cookie").forEach { header ->
            val cookie = Cookie.parse(parsedUrl, header) ?: return@forEach
            cookies[cookie.name] = if (cookie.expiresAt <= System.currentTimeMillis()) "" else cookie.value
        }
        return refreshRequestHeaders(url, requestHeaders + ("Cookie" to cookieHeader(cookies)))
    }

    private fun cookieHeader(cookies: Map<String, String>) =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    private fun cookiesFor(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)
    }.getOrNull()?.trim()?.takeIf(String::isNotBlank)
}
