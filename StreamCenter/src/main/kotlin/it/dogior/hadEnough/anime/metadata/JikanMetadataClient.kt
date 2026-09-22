package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal class JikanMetadataClient(
    private val httpClient: AnimeMetadataHttpClient,
) {
    suspend fun fetchNativeTitle(malId: Int?): String? {
        val resolvedMalId = malId ?: run {
            MetadataLog.info(
                SOURCE,
                "Recupero titolo giapponese ignorato",
                mapOf("motivo" to "id_myanimelist_assente"),
            )
            return null
        }
        nativeTitleCache[resolvedMalId]?.let { cached ->
            MetadataLog.info(
                SOURCE,
                "Titolo giapponese recuperato dalla cache",
                mapOf("id_myanimelist" to resolvedMalId, "cache" to true),
            )
            return cached
        }
        val details = mapOf("id_myanimelist" to resolvedMalId)
        MetadataLog.info(SOURCE, "Recupero titolo giapponese avviato", details)
        val text = httpClient.getText(
            url = "$API_URL/anime/$resolvedMalId",
            accept = "application/json",
            source = SOURCE,
            operation = "Titolo giapponese MyAnimeList",
            details = details,
            beforeRequest = ::throttle,
        ) ?: run {
            MetadataLog.warning(SOURCE, "Titolo giapponese non disponibile", details)
            return null
        }
        val rootResult = runCatching { JSONObject(text) }
        val data = rootResult.getOrNull()?.optJSONObject("data") ?: run {
            MetadataLog.failure(
                source = SOURCE,
                action = "Risposta titolo giapponese non valida",
                error = rootResult.exceptionOrNull(),
                details = details,
            )
            return null
        }
        val directTitle = data.optNullableString("title_japanese")
        val titles = data.optJSONArray("titles")
        val typedTitle = if (directTitle == null && titles != null) {
            (0 until titles.length())
                .asSequence()
                .mapNotNull(titles::optJSONObject)
                .firstOrNull { entry ->
                    entry.optNullableString("type").equals("Japanese", ignoreCase = true)
                }
                ?.optNullableString("title")
        } else {
            null
        }
        val title = cleanText(directTitle ?: typedTitle)
        if (title != null) nativeTitleCache[resolvedMalId] = title
        MetadataLog.info(
            SOURCE,
            "Titolo giapponese elaborato",
            details + mapOf("titolo_disponibile" to (title != null)),
        )
        return title
    }

    private suspend fun throttle() {
        requestMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequestAtMs)
            if (wait > 0) {
                MetadataLog.info(
                    SOURCE,
                    "Attesa rate limit",
                    mapOf("attesa_ms" to wait, "intervallo_minimo_ms" to MIN_INTERVAL_MS),
                )
                delay(wait)
            }
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    private companion object {
        const val SOURCE = "Jikan"
        const val API_URL = "https://api.jikan.moe/v4"
        const val MIN_INTERVAL_MS = 400L
        val requestMutex = Mutex()
        var lastRequestAtMs = 0L
    }

    private val nativeTitleCache = ConcurrentHashMap<Int, String>()
}
