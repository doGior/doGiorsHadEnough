package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal class KitsuMetadataClient(
    private val httpClient: AnimeMetadataHttpClient,
) {
    suspend fun request(
        path: String,
        operation: String = requestOperation(path),
        details: Map<String, Any?> = emptyMap(),
    ): JSONObject? {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$API_URL/${path.trimStart('/')}"
        }
        val requestDetails = details + mapOf("endpoint" to requestEndpoint(path))
        val text = httpClient.getText(
            url = url,
            accept = ACCEPT,
            source = SOURCE,
            operation = operation,
            details = requestDetails,
        ) ?: run {
            MetadataLog.warning(SOURCE, "Risposta API Kitsu non disponibile", requestDetails)
            return null
        }
        val jsonResult = runCatching { JSONObject(text) }
        val json = jsonResult.getOrNull() ?: run {
            MetadataLog.failure(
                source = SOURCE,
                action = "Risposta API Kitsu non valida",
                error = jsonResult.exceptionOrNull(),
                details = requestDetails + mapOf("motivo" to "json_non_interpretabile"),
            )
            return null
        }
        val data = json.optJSONArray("data")
        MetadataLog.info(
            SOURCE,
            "Risposta API Kitsu elaborata",
            requestDetails + buildMap<String, Any?> {
                put("dato_principale_presente", json.has("data"))
                data?.let { put("elementi_nella_pagina", it.length()) }
            },
        )
        return json
    }

    suspend fun fetchNativeTitle(kitsuId: Int?): String? {
        val resolvedKitsuId = kitsuId ?: run {
            MetadataLog.info(
                SOURCE,
                "Recupero titolo giapponese ignorato",
                mapOf("motivo" to "id_kitsu_assente"),
            )
            return null
        }
        val details = mapOf("id_kitsu" to resolvedKitsuId)
        MetadataLog.info(SOURCE, "Recupero titolo giapponese avviato", details)
        val attributes = fetchAnimeAttributes(
            kitsuId = resolvedKitsuId,
            operation = "Titolo giapponese Kitsu",
            details = details,
        ) ?: run {
            MetadataLog.warning(SOURCE, "Titolo giapponese non disponibile", details)
            return null
        }
        val titles = attributes.optJSONObject("titles")
        val title = listOf("ja_jp", "ja-jp", "ja")
            .firstNotNullOfOrNull { language -> titles?.optNullableString(language) }
            ?.let(::cleanText)
        MetadataLog.info(
            SOURCE,
            "Titolo giapponese elaborato",
            details + mapOf("titolo_disponibile" to (title != null)),
        )
        return title
    }

    suspend fun resolveAnimeId(malId: Int?, anilistId: Int?): Int? {
        val lookups = listOfNotNull(
            malId?.let { "myanimelist/anime" to it },
            anilistId?.let { "anilist/anime" to it },
        )
        if (lookups.isEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Risoluzione identificativo Kitsu ignorata",
                mapOf("motivo" to "id_anilist_e_mal_assenti"),
            )
            return null
        }
        val baseDetails = buildMap<String, Any?> {
            malId?.let { put("id_myanimelist", it) }
            anilistId?.let { put("id_anilist", it) }
        }
        MetadataLog.info(SOURCE, "Risoluzione identificativo Kitsu avviata", baseDetails)
        for ((site, externalId) in lookups) {
            val encodedSite = site.replace("/", "%2F")
            val path = "mappings?filter%5BexternalSite%5D=$encodedSite" +
                "&filter%5BexternalId%5D=$externalId&include=item"
            val lookupDetails = baseDetails + mapOf(
                "origine_identificativo" to if (site.startsWith("myanimelist")) "MyAnimeList" else "AniList",
                "id_origine" to externalId,
            )
            val result = runCatching {
                val json = request(
                    path,
                    operation = "Risoluzione identificativo Kitsu",
                    details = lookupDetails,
                ) ?: return@runCatching null
                json.optJSONArray("data")?.optJSONObject(0)
                    ?.optJSONObject("relationships")
                    ?.optJSONObject("item")
                    ?.optJSONObject("data")
                    ?.optNullableString("id")
                    ?.toIntOrNull()
                    ?: json.optJSONArray("included")
                        ?.optJSONObject(0)
                        ?.optNullableString("id")
                        ?.toIntOrNull()
            }
            val kitsuId = result.getOrNull()
            if (kitsuId != null) {
                MetadataLog.info(
                    SOURCE,
                    "Identificativo Kitsu risolto",
                    lookupDetails + mapOf("id_kitsu_risolto" to kitsuId),
                )
                return kitsuId
            }
            result.exceptionOrNull()?.let { error ->
                MetadataLog.failure(
                    source = SOURCE,
                    action = "Risoluzione identificativo Kitsu non riuscita",
                    error = error,
                    details = lookupDetails,
                )
            }
        }
        MetadataLog.warning(SOURCE, "Identificativo Kitsu non trovato", baseDetails)
        return null
    }

    private fun requestOperation(path: String): String = when {
        path.contains("/episodes", ignoreCase = true) -> "Episodi anime Kitsu"
        path.contains("/characters", ignoreCase = true) -> "Personaggi anime Kitsu"
        path.contains("/media-relationships", ignoreCase = true) -> "Raccomandazioni anime Kitsu"
        path.contains("/mappings", ignoreCase = true) || path.startsWith("mappings", ignoreCase = true) -> {
            "Mappature identificativi Kitsu"
        }
        path.startsWith("anime", ignoreCase = true) -> "Metadati anime Kitsu"
        else -> "Richiesta API Kitsu"
    }

    private suspend fun fetchAnimeAttributes(
        kitsuId: Int,
        operation: String,
        details: Map<String, Any?>,
    ): JSONObject? {
        animeAttributesCache[kitsuId]?.let { cached ->
            MetadataLog.info(
                SOURCE,
                "Metadati anime recuperati dalla cache",
                details + mapOf("id_kitsu" to kitsuId, "cache" to true),
            )
            return cached
        }
        val attributes = request(
            "anime/$kitsuId",
            operation = operation,
            details = details,
        )
            ?.optJSONObject("data")
            ?.optJSONObject("attributes")
            ?: return null
        return animeAttributesCache.putIfAbsent(kitsuId, attributes) ?: attributes
    }

    private fun requestEndpoint(path: String): String = when {
        path.contains("/episodes", ignoreCase = true) -> "anime/episodi"
        path.contains("/characters", ignoreCase = true) -> "anime/personaggi"
        path.contains("/media-relationships", ignoreCase = true) -> "anime/raccomandazioni"
        path.contains("/mappings", ignoreCase = true) || path.startsWith("mappings", ignoreCase = true) -> {
            "mappature"
        }
        path.startsWith("anime", ignoreCase = true) -> "anime"
        else -> "endpoint_non_classificato"
    }

    private companion object {
        const val SOURCE = "Kitsu"
        const val API_URL = "https://kitsu.io/api/edge"
        const val ACCEPT = "application/vnd.api+json"
    }

    private val animeAttributesCache = ConcurrentHashMap<Int, JSONObject>()
}
