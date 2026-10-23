package it.dogior.hadEnough.tmdb

import android.content.SharedPreferences
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.cache.ExpiringCache
import it.dogior.hadEnough.util.StreamCenterHostGate
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.runCatchingCancellable
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class TmdbVerifyResult { VALID, INVALID, NETWORK_ERROR }

internal object StreamCenterTmdbApiClient {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val LANGUAGE = "it-IT"
    private const val TIMEOUT_SECONDS = 15L
    private val responses = ExpiringCache<String, JSONObject>(maxEntries = 256, ttlMillis = 10 * 60 * 1_000L)
    private val userAgent = "MediaClient/1.0"

    fun clearCache() = responses.clear()

    fun isActive(sharedPref: SharedPreferences? = StreamCenterPlugin.activeSharedPref): Boolean =
        StreamCenterTmdbCredential.isActive(sharedPref)

    suspend fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
        sharedPref: SharedPreferences? = StreamCenterPlugin.activeSharedPref,
    ): JSONObject? {
        if (!StreamCenterTmdbCredential.isActive(sharedPref)) return null
        val credential = StreamCenterTmdbCredential.read(sharedPref) ?: return null
        val query = mapOf("language" to LANGUAGE) + params
        val cacheKey = "$path?" + query.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        responses[cacheKey]?.let { return it }
        val response = runCatchingCancellable { request(credential, path, query, sharedPref) }
            .getOrElse { error ->
                StreamCenterLogger.warning(
                    "Richiesta API TMDB non riuscita",
                    mapOf("percorso" to path, "errore" to error.toString()),
                )
                return null
            }
        return when (response.first) {
            in 200..299 -> runCatching { JSONObject(response.second) }.getOrNull()?.also { responses.put(cacheKey, it) }
            401 -> {
                responses.clear()
                if (StreamCenterTmdbCredential.markInvalid(sharedPref)) {
                    CommonActivity.showToast("Chiave TMDB non valida: torno alla lettura del sito", Toast.LENGTH_LONG)
                }
                null
            }
            else -> {
                StreamCenterLogger.warning(
                    "Risposta API TMDB non utilizzabile",
                    mapOf("percorso" to path, "stato_http" to response.first),
                )
                null
            }
        }
    }

    suspend fun getWithEpisodeTitles(path: String, isAnime: Boolean): JSONObject? {
        var json = get(path) ?: return null
        for (language in TmdbEpisodeTitleFallback.apiLanguages(isAnime)) {
            if (!TmdbEpisodeTitleFallback.hasMissingTitles(json)) break
            get(path, mapOf("language" to language))?.let { json = TmdbEpisodeTitleFallback.fill(json, it) }
        }
        return json
    }

    suspend fun verify(
        credential: TmdbCredential,
        sharedPref: SharedPreferences? = StreamCenterPlugin.activeSharedPref,
    ): TmdbVerifyResult {
        val response = runCatchingCancellable { request(credential, "authentication", emptyMap(), sharedPref) }
            .getOrElse { return TmdbVerifyResult.NETWORK_ERROR }
        return when (response.first) {
            in 200..299 -> TmdbVerifyResult.VALID
            401, 403 -> TmdbVerifyResult.INVALID
            else -> TmdbVerifyResult.NETWORK_ERROR
        }
    }

    private suspend fun request(
        credential: TmdbCredential,
        path: String,
        params: Map<String, String>,
        sharedPref: SharedPreferences?,
    ): Pair<Int, String> {
        val query = (if (credential.kind == TmdbCredentialKind.V3) params + ("api_key" to credential.value) else params)
            .entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val url = "$BASE_URL/${path.trimStart('/')}" + if (query.isEmpty()) "" else "?$query"
        val headers = buildMap {
            put("Accept", "application/json")
            put("User-Agent", userAgent)
            if (credential.kind == TmdbCredentialKind.V4) put("Authorization", "Bearer ${credential.value}")
        }
        val response = StreamCenterHostGate.request(url) {
            app.get(url, headers = StreamCenterTmdbUserAgent.headers(headers, sharedPref), cacheTime = 0, timeout = TIMEOUT_SECONDS)
        }
        return response.code to response.text
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
