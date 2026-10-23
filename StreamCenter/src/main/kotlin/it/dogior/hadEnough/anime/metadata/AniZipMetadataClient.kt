package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.model.AniZipEpisodeCatalog
import it.dogior.hadEnough.model.AniZipEpisodeMetadata
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.parseMetadataDate
import org.json.JSONObject

internal class AniZipMetadataClient(
    private val httpClient: AnimeMetadataHttpClient,
) {
    suspend fun fetch(anilistId: Int?, malId: Int?): AniZipEpisodeCatalog {
        val lookup = anilistId?.let { "anilist_id=$it" }
            ?: malId?.let { "mal_id=$it" }
            ?: return AniZipEpisodeCatalog()
        val details = mapOf("id_anilist" to anilistId, "id_myanimelist" to malId)
        val text = httpClient.getText(
            url = "$API_URL?$lookup",
            accept = "application/json",
            source = SOURCE,
            operation = "Mappature AniZip",
            details = details,
        ) ?: return AniZipEpisodeCatalog()
        val root = runCatching { JSONObject(text) }.getOrElse { error ->
            MetadataLog.failure(SOURCE, "Mappature AniZip non valide", error, details)
            return AniZipEpisodeCatalog()
        }
        return parseMappings(root).also { catalog ->
            MetadataLog.info(
                SOURCE,
                "Mappature AniZip elaborate",
                details + mapOf("episodi_mappati" to catalog.episodes.size, "id_tmdb" to catalog.tmdbId),
            )
        }
    }

    internal fun parseMappings(root: JSONObject): AniZipEpisodeCatalog {
        val mappings = root.optJSONObject("mappings")
        val episodes = linkedMapOf<Int, AniZipEpisodeMetadata>()
        root.optJSONObject("episodes")?.let { entries ->
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val number = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
                val entry = entries.optJSONObject(key) ?: continue
                episodes[number] = AniZipEpisodeMetadata(
                    airDate = parseMetadataDate(entry.optNullableString("airdate")),
                    fallbackAirDate = parseMetadataDate(
                        entry.optNullableString("airDate") ?: entry.optNullableString("airDateUtc"),
                    ),
                    seasonNumber = entry.optNullableInt("seasonNumber"),
                    episodeNumber = entry.optNullableInt("episodeNumber"),
                    absoluteEpisodeNumber = entry.optNullableInt("absoluteEpisodeNumber"),
                )
            }
        }
        return AniZipEpisodeCatalog(
            episodes = episodes,
            anilistId = mappings?.optNullableInt("anilist_id"),
            malId = mappings?.optNullableInt("mal_id"),
            kitsuId = mappings?.optNullableInt("kitsu_id"),
            tmdbId = mappings?.optNullableString("themoviedb_id")?.toIntOrNull()?.takeIf { it > 0 },
            imdbId = mappings?.optNullableString("imdb_id")?.trim()?.lowercase()
                ?.takeIf { it.startsWith("tt") && it.length >= 7 && it.drop(2).all(Char::isDigit) },
        )
    }

    private companion object {
        const val SOURCE = "AniZip"
        const val API_URL = "https://api.ani.zip/mappings"
    }
}
