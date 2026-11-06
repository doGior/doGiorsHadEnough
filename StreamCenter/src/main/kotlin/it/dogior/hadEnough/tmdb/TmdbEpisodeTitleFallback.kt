package it.dogior.hadEnough.tmdb

import it.dogior.hadEnough.util.cleanMetadataEpisodeTitle
import it.dogior.hadEnough.util.cleanTmdbEpisodeDescription
import org.json.JSONObject

internal object TmdbEpisodeTitleFallback {
    const val ENGLISH = "en-US"
    const val JAPANESE = "ja-JP"

    fun apiLanguages(isAnime: Boolean): List<String> = listOfNotNull(ENGLISH, JAPANESE.takeIf { isAnime })

    fun fillsOverview(language: String): Boolean = language == ENGLISH

    fun hasMissingText(json: JSONObject, withOverview: Boolean): Boolean =
        episodeObjects(json).any { episode ->
            cleanMetadataEpisodeTitle(episode.optString("name")) == null ||
                (withOverview && cleanTmdbEpisodeDescription(episode.optString("overview")) == null)
        }

    fun fill(json: JSONObject, fallback: JSONObject, withOverview: Boolean): JSONObject {
        val texts = episodeObjects(fallback).mapNotNull { episode ->
            val id = episode.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            id to (cleanMetadataEpisodeTitle(episode.optString("name")) to
                cleanTmdbEpisodeDescription(episode.optString("overview")).takeIf { withOverview })
        }.toMap()
        if (texts.isEmpty()) return json
        val filled = JSONObject(json.toString())
        episodeObjects(filled).forEach { episode ->
            val (name, overview) = texts[episode.optLong("id")] ?: return@forEach
            if (cleanMetadataEpisodeTitle(episode.optString("name")) == null && name != null) episode.put("name", name)
            if (cleanTmdbEpisodeDescription(episode.optString("overview")) == null && overview != null) {
                episode.put("overview", overview)
            }
        }
        return filled
    }

    private fun episodeObjects(json: JSONObject): List<JSONObject> {
        val groups = json.optJSONArray("groups")
        val containers = if (groups != null) {
            (0 until groups.length()).mapNotNull(groups::optJSONObject)
        } else {
            listOf(json)
        }
        return containers.flatMap { container ->
            val episodes = container.optJSONArray("episodes") ?: return@flatMap emptyList()
            (0 until episodes.length()).mapNotNull(episodes::optJSONObject)
        }
    }
}
