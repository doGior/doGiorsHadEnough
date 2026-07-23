package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.model.KitsuEpisodeMetadata
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import org.json.JSONObject

internal class KitsuMetadataClient(
    private val httpClient: AnimeMetadataHttpClient,
) {
    suspend fun request(path: String): JSONObject? {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$API_URL/${path.trimStart('/')}"
        }
        val text = httpClient.getText(url, ACCEPT) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    suspend fun fetchEpisodes(
        kitsuId: Int?,
        targetEpisodeCount: Int? = null,
    ): Map<Int, KitsuEpisodeMetadata> {
        val resolvedKitsuId = kitsuId ?: return emptyMap()
        val result = linkedMapOf<Int, KitsuEpisodeMetadata>()
        var offset = 0
        var page = 0
        val maxPages = if (targetEpisodeCount == null) DEFAULT_MAX_PAGES else EXTENDED_MAX_PAGES
        while (page < maxPages) {
            val json = request(
                "anime/$resolvedKitsuId/episodes?page%5Blimit%5D=20&page%5Boffset%5D=$offset",
            ) ?: break
            val data = json.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (index in 0 until data.length()) {
                val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
                val number = attributes.optNullableInt("number")
                    ?: attributes.optNullableInt("relativeNumber")
                    ?: continue
                if (result.containsKey(number)) continue
                val title = attributes.optNullableString("canonicalTitle")
                    ?: attributes.optJSONObject("titles")?.let {
                        it.optNullableString("en")
                            ?: it.optNullableString("en_us")
                            ?: it.optNullableString("en_jp")
                    }
                val synopsis = cleanText(attributes.optNullableString("synopsis"))
                val airdate = attributes.optNullableString("airdate")
                val length = attributes.optNullableInt("length")
                val thumbnail = attributes.optJSONObject("thumbnail")?.optNullableString("original")
                result[number] = KitsuEpisodeMetadata(
                    name = title,
                    description = synopsis,
                    runTime = length,
                    posterUrl = thumbnail,
                    date = airdate,
                )
            }
            if (targetEpisodeCount != null && (result.keys.maxOrNull() ?: 0) >= targetEpisodeCount) break
            if (json.optJSONObject("links")?.optNullableString("next") == null) break
            offset += PAGE_SIZE
            page++
        }
        return result
    }

    suspend fun resolveAnimeId(malId: Int?, anilistId: Int?): Int? {
        val lookups = listOfNotNull(
            malId?.let { "myanimelist/anime" to it },
            anilistId?.let { "anilist/anime" to it },
        )
        for ((site, externalId) in lookups) {
            val encodedSite = site.replace("/", "%2F")
            val path = "mappings?filter%5BexternalSite%5D=$encodedSite" +
                "&filter%5BexternalId%5D=$externalId&include=item"
            val kitsuId = runCatching {
                val json = request(path) ?: return@runCatching null
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
            }.getOrNull()
            if (kitsuId != null) return kitsuId
        }
        return null
    }

    private companion object {
        const val API_URL = "https://kitsu.io/api/edge"
        const val ACCEPT = "application/vnd.api+json"
        const val PAGE_SIZE = 20
        const val DEFAULT_MAX_PAGES = 15
        const val EXTENDED_MAX_PAGES = 80
    }
}
