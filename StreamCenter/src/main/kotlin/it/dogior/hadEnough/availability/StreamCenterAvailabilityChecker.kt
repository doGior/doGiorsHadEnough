package it.dogior.hadEnough.availability

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.StreamCenterPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal object StreamCenterAvailabilityChecker {
    private data class CheckResult(
        val available: Boolean,
        val detail: String? = null,
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5L, TimeUnit.SECONDS)
        .readTimeout(7L, TimeUnit.SECONDS)
        .writeTimeout(7L, TimeUnit.SECONDS)
        .callTimeout(9L, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
    )

    suspend fun check(
        sharedPref: SharedPreferences?,
        onProgress: suspend (
            name: String,
            isRunning: Boolean,
            result: Boolean?,
            detail: String?,
        ) -> Unit,
    ): List<Pair<String, Boolean>> = coroutineScope {
        fun sourceUrl(key: String) = StreamCenterPlugin.getSourceBaseUrl(sharedPref, key)

        val checks: List<Pair<String, suspend () -> CheckResult>> = listOf(
            "AniList" to ::isAnilistAvailable,
            "MyAnimeList (Jikan)" to ::isJikanAvailable,
            "Kitsu" to {
                jsonApiReachable("https://kitsu.io/api/edge/anime/1", "application/vnd.api+json")
            },
            "AniZip" to {
                jsonApiReachable(
                    "https://api.ani.zip/mappings?anilist_id=1",
                    "application/json",
                    expectedKey = "episodes",
                )
            },
            "TMDB" to { urlReachable("https://www.themoviedb.org") },
            "StreamingCommunity" to {
                urlReachable("${sourceUrl(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)}/it")
            },
            "AnimeUnity" to { urlReachable(sourceUrl(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)) },
            "AnimeWorld" to { urlReachable(sourceUrl(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) },
            "AnimeSaturn" to { urlReachable(sourceUrl(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) },
        )

        checks.map { (name, check) ->
            async(Dispatchers.IO) {
                onProgress(name, true, null, null)
                val result = runCatching { check() }
                    .getOrElse { error -> CheckResult(false, failureDetail(error)) }
                onProgress(name, false, result.available, result.detail)
                name to result.available
            }
        }.awaitAll()
    }

    private suspend fun isAnilistAvailable(): CheckResult {
        val body = JSONObject()
            .put("query", "query { Media(id: 1, type: ANIME) { id } }")
            .toString()
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = execute(request)
        if (response.code !in 200..299) return CheckResult(false, "HTTP ${response.code}")
        return if (JSONObject(response.body).optJSONObject("data")?.opt("Media") != null) {
            CheckResult(true)
        } else {
            CheckResult(false, "Risposta API non valida")
        }
    }

    private suspend fun isJikanAvailable(): CheckResult {
        val response = app.get(
            "https://api.jikan.moe/v4/anime/1",
            headers = requestHeaders + ("Accept" to "application/json"),
            cacheTime = 0,
            timeout = 10L,
        )
        return when (response.code) {
            429 -> CheckResult(true, "Limite temporaneo di richieste (HTTP 429)")
            in 200..299 -> jikanJsonResult(response.text)
            else -> CheckResult(false, "HTTP ${response.code}")
        }
    }

    private fun jikanJsonResult(body: String): CheckResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return CheckResult(false, "JSON non valido: ${body.trim().take(100)}")
        if (json.has("data")) return CheckResult(true)
        val status = json.optInt("status", 0)
        val type = json.optString("type").trim()
        val message = json.optString("message").trim()
        val detail = listOf(type, message).filter(String::isNotBlank).joinToString(": ")
        if (status == 429 || detail.contains("rate", ignoreCase = true)) {
            return CheckResult(true, "Limite temporaneo di richieste (Jikan 429)")
        }
        if (type.equals("UpstreamException", ignoreCase = true) ||
            detail.contains("MyAnimeList.net", ignoreCase = true)
        ) {
            return CheckResult(true, "Errore temporaneo upstream MyAnimeList")
        }
        return CheckResult(
            false,
            detail.takeIf(String::isNotBlank) ?: "JSON senza dati: ${json.keys().asSequence().toList()}",
        )
    }

    private suspend fun jsonApiReachable(
        url: String,
        accept: String,
        expectedKey: String = "data",
    ): CheckResult {
        val response = execute(request(url, requestHeaders + ("Accept" to accept)))
        if (response.code !in 200..299) return CheckResult(false, "HTTP ${response.code}")
        return if (JSONObject(response.body).has(expectedKey)) {
            CheckResult(true)
        } else {
            CheckResult(false, "Risposta JSON senza '$expectedKey'")
        }
    }

    private suspend fun urlReachable(url: String): CheckResult {
        if (url.isBlank()) return CheckResult(false, "URL non configurato")
        return httpClient.newCall(request(url, requestHeaders)).execute().use { response ->
            if (response.code in 200..399) CheckResult(true) else CheckResult(false, "HTTP ${response.code}")
        }
    }

    private fun request(url: String, headers: Map<String, String>): Request {
        return Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
    }

    private fun execute(request: Request): HttpResponse {
        return httpClient.newCall(request).execute().use { response ->
            HttpResponse(response.code, response.body.string())
        }
    }

    private fun failureDetail(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "Timeout di rete"
        is UnknownHostException -> "Host non trovato"
        is SSLException -> "Errore TLS/SSL"
        else -> error.message?.trim()?.takeIf(String::isNotBlank)
            ?.take(120)
            ?: error.javaClass.simpleName
    }

    private data class HttpResponse(
        val code: Int,
        val body: String,
    )
}
