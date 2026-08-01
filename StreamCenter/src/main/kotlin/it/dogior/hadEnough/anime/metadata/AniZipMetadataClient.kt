package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.model.AniZipEpisodeCatalog
import it.dogior.hadEnough.model.AniZipEpisodeMetadata
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import org.jsoup.Jsoup
import org.json.JSONObject
import java.util.Locale

internal class AniZipMetadataClient(
    private val httpClient: AnimeMetadataHttpClient,
) {
    suspend fun fetch(anilistId: Int?, malId: Int?): AniZipEpisodeCatalog {
        val lookup = anilistId?.let { "anilist_id=$it" }
            ?: malId?.let { "mal_id=$it" }
            ?: run {
                MetadataLog.info(
                    SOURCE,
                    "Catalogo episodi ignorato",
                    mapOf("motivo" to "identificativo_anilist_e_mal_assenti"),
                )
                return AniZipEpisodeCatalog()
            }
        val requestDetails = buildMap<String, Any?> {
            anilistId?.let { put("id_anilist", it) }
            malId?.let { put("id_myanimelist", it) }
            put("chiave_lookup", if (anilistId != null) "AniList" else "MyAnimeList")
        }
        MetadataLog.info(SOURCE, "Recupero catalogo episodi avviato", requestDetails)
        val text = httpClient.getText(
            url = "$API_URL?$lookup",
            accept = "application/json",
            source = SOURCE,
            operation = "Catalogo episodi AniZip",
            details = requestDetails,
        ) ?: run {
            MetadataLog.warning(SOURCE, "Catalogo episodi non disponibile", requestDetails)
            return AniZipEpisodeCatalog()
        }
        val rootResult = runCatching { JSONObject(text) }
        val root = rootResult.getOrNull() ?: run {
            MetadataLog.failure(
                source = SOURCE,
                action = "Risposta catalogo episodi non valida",
                error = rootResult.exceptionOrNull(),
                details = requestDetails + mapOf("motivo" to "json_non_interpretabile"),
            )
            return AniZipEpisodeCatalog()
        }
        val titles = readLocalizedValues(root.optJSONObject("titles"))
        val description = listOf("description", "overview", "synopsis", "summary")
            .firstNotNullOfOrNull { fieldName -> italianText(root, fieldName) }
            ?.let(::cleanDescription)
        val mappings = root.optJSONObject("mappings")
        val mappedAnilistId = mappings?.optNullableInt("anilist_id")
        val mappedMalId = mappings?.optNullableInt("mal_id")
        val mappedKitsuId = mappings?.optNullableInt("kitsu_id")
        val mappedTmdbId = mappings?.optNullableString("themoviedb_id")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val episodes = linkedMapOf<Int, AniZipEpisodeMetadata>()

        root.optJSONObject("episodes")?.let { entries ->
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val number = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
                val entry = entries.optJSONObject(key) ?: continue
                val airDate = normalizeAirDate(entry.optNullableString("airdate"))
                val fallbackAirDate = normalizeAirDate(
                    entry.optNullableString("airDate") ?: entry.optNullableString("airDateUtc")
                )
                episodes[number] = AniZipEpisodeMetadata(
                    title = localizedText(entry.optJSONObject("title")),
                    summary = cleanSummary(localizedText(entry, "summary")),
                    overview = cleanDescription(localizedText(entry, "overview")),
                    posterUrl = entry.optNullableString("image"),
                    runTime = entry.optNullableInt("length") ?: entry.optNullableInt("runtime"),
                    airDate = airDate,
                    fallbackAirDate = fallbackAirDate,
                    rating = entry.optDouble("rating", 0.0)
                        .takeIf { it.isFinite() && it > 0.0 && it <= 10.0 },
                    episodeNumber = entry.optNullableInt("episodeNumber"),
                    absoluteEpisodeNumber = entry.optNullableInt("absoluteEpisodeNumber"),
                )
            }
        }

        val catalog = AniZipEpisodeCatalog(
            titles = titles,
            description = description,
            episodes = episodes,
            anilistId = mappedAnilistId,
            malId = mappedMalId,
            kitsuId = mappedKitsuId,
            tmdbId = mappedTmdbId,
        )
        MetadataLog.info(
            SOURCE,
            "Catalogo episodi elaborato",
            requestDetails + buildMap<String, Any?> {
                put("titoli_localizzati", titles.size)
                put("descrizione_disponibile", description != null)
                put("episodi_disponibili", episodes.size)
                mappedAnilistId?.let { put("id_anilist_mappato", it) }
                mappedMalId?.let { put("id_myanimelist_mappato", it) }
                mappedKitsuId?.let { put("id_kitsu_mappato", it) }
                mappedTmdbId?.let { put("id_tmdb_mappato", it) }
            },
        )
        return catalog
    }

    fun localizedText(
        values: Map<String, String>,
        preferredLanguage: String,
        allowFallback: Boolean = true,
    ): String? {
        fun normalized(language: String): String = language
            .trim()
            .lowercase(Locale.ROOT)
            .replace('_', '-')

        fun find(language: String): String? {
            val normalizedLanguage = normalized(language)
            return values.entries.firstOrNull { (key, value) ->
                value.isNotBlank() && (
                    normalized(key) == normalizedLanguage ||
                        normalized(key).startsWith("$normalizedLanguage-")
                    )
            }?.value?.trim()?.takeIf(String::isNotBlank)
        }

        val preferred = find(preferredLanguage)
        if (!allowFallback || preferred != null) return preferred
        return find("en")
            ?: find("x-jat")
            ?: find("ja")
            ?: values.values.firstOrNull(String::isNotBlank)?.trim()
    }

    private fun localizedText(container: JSONObject, fieldName: String): String? {
        container.optJSONObject(fieldName)?.let { localizedValues ->
            localizedText(localizedValues)?.let { return it }
        }
        return listOf(
            "${fieldName}_it",
            "${fieldName}-it",
            "${fieldName}It",
            fieldName,
            "${fieldName}_en",
            "${fieldName}-en",
            "${fieldName}En",
        ).firstNotNullOfOrNull { name -> container.optNullableString(name) }
    }

    private fun italianText(container: JSONObject, fieldName: String): String? {
        container.optJSONObject(fieldName)?.let { localizedValues ->
            localizedText(localizedValues, "it", allowFallback = false)?.let { return it }
        }
        return listOf(
            "${fieldName}_it",
            "${fieldName}-it",
            "${fieldName}It",
        ).firstNotNullOfOrNull { name -> container.optNullableString(name) }
    }

    private fun localizedText(
        values: JSONObject?,
        preferredLanguage: String = "it",
        allowFallback: Boolean = true,
    ): String? {
        return localizedText(
            readLocalizedValues(values),
            preferredLanguage,
            allowFallback,
        )
    }

    private fun readLocalizedValues(values: JSONObject?): Map<String, String> {
        values ?: return emptyMap()
        val localized = linkedMapOf<String, String>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val language = keys.next()
            values.optNullableString(language)?.let { localized[language] = it }
        }
        return localized
    }

    private fun cleanSummary(summary: String?): String? {
        val withoutSource = summary
            ?.replace(Regex("""(?is)(?:\r?\n|<br\s*/?>)\s*source:\s*.*$"""), "")
            ?.replace(Regex("""(?im)^\s*source:\s*.*$"""), "")
        return cleanDescription(withoutSource)
    }

    private fun cleanDescription(value: String?): String? {
        val cleaned = value
            ?.let { Jsoup.parse(it).text() }
            ?.let(::cleanText)
            ?: return null
        val normalized = cleaned.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        return cleaned.takeUnless {
            normalized in setOf(
                "no overview available",
                "no summary available",
                "tba",
                "n/a",
                "none",
            )
        }
    }

    private fun normalizeAirDate(value: String?): String? {
        val normalized = value
            ?.substringBefore('T')
            ?.substringBefore(' ')
            ?.trim()
            ?.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
            ?: return null
        val parts = normalized.split('-').mapNotNull(String::toIntOrNull)
        if (parts.size != 3) return null
        val (year, month, day) = parts
        return normalized.takeIf { year > 0 && month in 1..12 && day in 1..31 }
    }

    private companion object {
        const val SOURCE = "AniZip"
        const val API_URL = "https://api.ani.zip/mappings"
    }
}
