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
            ?: return AniZipEpisodeCatalog()
        val text = httpClient.getText(
            url = "$API_URL?$lookup",
            accept = "application/json",
        ) ?: return AniZipEpisodeCatalog()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return AniZipEpisodeCatalog()
        val titles = readLocalizedValues(root.optJSONObject("titles"))
        val description = listOf("description", "overview", "synopsis", "summary")
            .firstNotNullOfOrNull { fieldName -> italianText(root, fieldName) }
            ?.let(::cleanDescription)
        val mappedKitsuId = root.optJSONObject("mappings")?.optNullableInt("kitsu_id")
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
                )
            }
        }

        return AniZipEpisodeCatalog(
            titles = titles,
            description = description,
            episodes = episodes,
            kitsuId = mappedKitsuId,
        )
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
        const val API_URL = "https://api.ani.zip/mappings"
    }
}
