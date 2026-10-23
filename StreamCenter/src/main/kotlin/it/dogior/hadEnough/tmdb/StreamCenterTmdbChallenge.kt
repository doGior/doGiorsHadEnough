package it.dogior.hadEnough.tmdb

import com.lagradost.nicehttp.NiceResponse
import it.dogior.hadEnough.util.StreamCenterLogger
import java.util.concurrent.ConcurrentHashMap

internal object StreamCenterTmdbChallenge {
    internal data class Pass(val cookieName: String, val token: String, val expiresAtSeconds: Long?)

    private val COOKIE_NAME = Regex("""\bdata-cookie="([A-Za-z0-9_-]+)"""")
    private val TOKEN = Regex("""\bdata-token="([A-Za-z0-9._-]+)"""")
    private val EXPIRY = Regex("""^(\d{9,})\.""")
    private const val EXPIRY_MARGIN_SECONDS = 60L
    private val passes = ConcurrentHashMap<String, Pass>()

    fun isWebUrl(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').substringBefore('?').lowercase()
        return host == "themoviedb.org" || host == "www.themoviedb.org"
    }

    fun withPass(headers: Map<String, String>, nowSeconds: Long = currentSeconds()): Map<String, String> {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value.orEmpty()
        val pass = passes[userAgent]?.takeIf { it.isValid(nowSeconds) } ?: return headers
        val cookies = headers.entries.filter { it.key.equals("Cookie", ignoreCase = true) }
            .map { it.value }
            .filter(String::isNotBlank)
            .filterNot { it.trimStart().startsWith("${pass.cookieName}=") }
        return headers.filterKeys { !it.equals("Cookie", ignoreCase = true) } +
            ("Cookie" to (cookies + "${pass.cookieName}=${pass.token}").joinToString("; "))
    }

    fun capture(response: NiceResponse): Boolean {
        val request = response.okhttpResponse.request
        return capture(request.url.toString(), request.header("User-Agent"), response.code) { response.text }
    }

    fun capture(url: String, userAgent: String?, code: Int, body: () -> String): Boolean {
        if (code != 403 || !isWebUrl(url)) return false
        val pass = runCatching { parse(body()) }.getOrNull() ?: return false
        remember(userAgent.orEmpty(), pass)
        StreamCenterLogger.logMenu(
            action = "Verifica TMDB superata",
            metadata = mapOf("scadenza_unix" to pass.expiresAtSeconds),
        )
        return true
    }

    fun parse(body: String): Pass? {
        val cookieName = COOKIE_NAME.find(body)?.groupValues?.get(1) ?: return null
        val token = TOKEN.find(body)?.groupValues?.get(1) ?: return null
        return Pass(cookieName, token, EXPIRY.find(token)?.groupValues?.get(1)?.toLongOrNull())
    }

    fun remember(userAgent: String, pass: Pass) {
        passes[userAgent] = pass
    }

    fun clear() = passes.clear()

    private fun Pass.isValid(nowSeconds: Long): Boolean =
        expiresAtSeconds == null || expiresAtSeconds - EXPIRY_MARGIN_SECONDS > nowSeconds

    private fun currentSeconds(): Long = System.currentTimeMillis() / 1_000L
}
