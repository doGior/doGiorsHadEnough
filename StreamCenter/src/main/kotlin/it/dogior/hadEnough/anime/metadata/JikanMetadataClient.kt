package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.model.MalEpisodeExtra
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal class JikanMetadataClient(
    private val httpClient: AnimeMetadataHttpClient,
) {
    suspend fun fetchEpisodeExtras(
        malId: Int,
        targetEpisodeCount: Int? = null,
    ): Map<Int, MalEpisodeExtra> {
        val baseDetails = buildMap<String, Any?> {
            put("id_myanimelist", malId)
            targetEpisodeCount?.let { put("episodi_obiettivo", it) }
        }
        val result = linkedMapOf<Int, MalEpisodeExtra>()
        var page = 1
        val maxPages = if (targetEpisodeCount == null) DEFAULT_MAX_PAGES else EXTENDED_MAX_PAGES
        var pagesCompleted = 0
        var stopReason = "limite_pagine_raggiunto"
        MetadataLog.info(
            SOURCE,
            "Recupero dettagli episodi avviato",
            baseDetails + mapOf("pagine_massime" to maxPages),
        )
        while (page <= maxPages) {
            val text = httpClient.getText(
                url = "$API_URL/anime/$malId/episodes?page=$page",
                accept = "application/json",
                source = SOURCE,
                operation = "Dettagli episodi Jikan",
                details = baseDetails + mapOf("pagina" to page),
                beforeRequest = ::throttle,
            )
            if (text == null) {
                stopReason = "risposta_non_disponibile"
                break
            }
            val jsonResult = runCatching { JSONObject(text) }
            val json = jsonResult.getOrNull()
            if (json == null) {
                stopReason = "json_non_interpretabile"
                MetadataLog.failure(
                    source = SOURCE,
                    action = "Risposta dettagli episodi non valida",
                    error = jsonResult.exceptionOrNull(),
                    details = baseDetails + mapOf("pagina" to page),
                )
                break
            }
            val data = json.optJSONArray("data")
            if (data == null) {
                stopReason = "campo_dati_assente"
                MetadataLog.warning(
                    SOURCE,
                    "Pagina dettagli episodi priva di dati",
                    baseDetails + mapOf("pagina" to page),
                )
                break
            }
            if (data.length() == 0) {
                stopReason = "pagina_vuota"
                break
            }
            for (index in 0 until data.length()) {
                val entry = data.optJSONObject(index) ?: continue
                val number = entry.optNullableInt("mal_id") ?: continue
                if (result.containsKey(number)) continue
                result[number] = MalEpisodeExtra(
                    title = entry.optNullableString("title"),
                    score = entry.optDouble("score", 0.0).takeIf { it > 0.0 },
                    airedDate = entry.optNullableString("aired")?.substringBefore("T"),
                    filler = entry.optBoolean("filler", false),
                    recap = entry.optBoolean("recap", false),
                )
            }
            pagesCompleted++
            if (targetEpisodeCount != null && (result.keys.maxOrNull() ?: 0) >= targetEpisodeCount) {
                stopReason = "obiettivo_episodi_raggiunto"
                break
            }
            if (json.optJSONObject("pagination")?.optBoolean("has_next_page", false) != true) {
                stopReason = "nessuna_pagina_successiva"
                break
            }
            page++
        }
        MetadataLog.info(
            SOURCE,
            "Recupero dettagli episodi completato",
            baseDetails + mapOf(
                "episodi_con_dettagli" to result.size,
                "pagine_completate" to pagesCompleted,
                "motivo_terminazione" to stopReason,
            ),
        )
        return result
    }

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
        const val DEFAULT_MAX_PAGES = 5
        const val EXTENDED_MAX_PAGES = 30
        const val MIN_INTERVAL_MS = 400L
        val requestMutex = Mutex()
        var lastRequestAtMs = 0L
    }

    private val nativeTitleCache = ConcurrentHashMap<Int, String>()
}
