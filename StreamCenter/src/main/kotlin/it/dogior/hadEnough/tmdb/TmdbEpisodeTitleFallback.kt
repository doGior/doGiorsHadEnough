package it.dogior.hadEnough.tmdb

import it.dogior.hadEnough.util.cleanMetadataEpisodeTitle
import org.json.JSONObject

internal object TmdbEpisodeTitleFallback {
    const val ENGLISH = "en-US"
    const val JAPANESE = "ja-JP"

    fun apiLanguages(isAnime: Boolean): List<String> = listOfNotNull(ENGLISH, JAPANESE.takeIf { isAnime })

    fun hasMissingTitles(json: JSONObject): Boolean =
        episodeObjects(json).any { cleanMetadataEpisodeTitle(it.optString("name")) == null }

    fun fill(json: JSONObject, fallback: JSONObject): JSONObject {
        val names = episodeObjects(fallback).mapNotNull { episode ->
            val id = episode.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val name = cleanMetadataEpisodeTitle(episode.optString("name")) ?: return@mapNotNull null
            id to name
        }.toMap()
        if (names.isEmpty()) return json
        val filled = JSONObject(json.toString())
        episodeObjects(filled).forEach { episode ->
            if (cleanMetadataEpisodeTitle(episode.optString("name")) != null) return@forEach
            names[episode.optLong("id")]?.let { episode.put("name", it) }
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
