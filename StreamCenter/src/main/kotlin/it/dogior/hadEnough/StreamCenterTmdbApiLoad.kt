package it.dogior.hadEnough

import it.dogior.hadEnough.extensions.InstalledExtensionSources
import it.dogior.hadEnough.model.StreamCenterMetadata
import it.dogior.hadEnough.tmdb.StreamCenterTmdbApiClient
import it.dogior.hadEnough.tmdb.TmdbApiDetails
import it.dogior.hadEnough.tmdb.TmdbApiEpisode
import it.dogior.hadEnough.tmdb.TmdbApiMapping
import it.dogior.hadEnough.tmdb.TmdbApiSeason
import org.json.JSONObject

internal data class TmdbApiMedia(
    val tmdbId: Int,
    val isTv: Boolean,
    val details: TmdbApiDetails,
    val metadata: StreamCenterMetadata,
    val imdbId: String?,
    val logo: String?,
)

internal data class TmdbApiGroupSeason(val digitalSeason: Int, val episodes: List<TmdbApiEpisode>)

internal fun tmdbLogoLanguages(isAnime: Boolean): List<String> = listOfNotNull("it", "en", "ja".takeIf { isAnime }, "xx")

internal fun StreamCenter.needsTmdbSourceIdentifiers(): Boolean =
    StreamCenterPlugin.isTorrentEnabled(sharedPref) ||
        activeStremioResolverAddons().isNotEmpty() ||
        catalogDefinition?.stremioAddon != null ||
        InstalledExtensionSources.enabledKeys(sharedPref).isNotEmpty() ||
        isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_VIDXGO) ||
        (catalogDefinition == null && isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY))

internal suspend fun StreamCenter.fetchTmdbApiMedia(
    isTv: Boolean,
    tmdbId: Int?,
    minimal: Boolean,
    isAnime: Boolean = false,
): TmdbApiMedia? {
    val id = tmdbId?.takeIf { it > 0 } ?: return null
    if (!StreamCenterTmdbApiClient.isActive()) return null
    val kind = if (isTv) "tv" else "movie"
    val appended = listOf(
        "credits", "external_ids", "videos", "images", if (isTv) "content_ratings" else "release_dates",
    )
    val json = StreamCenterTmdbApiClient.get(
        "$kind/$id",
        mapOf(
            "append_to_response" to appended.joinToString(","),
            "include_image_language" to (tmdbLogoLanguages(isAnime) + "null").joinToString(","),
            "include_video_language" to "it,en,null",
        ),
    ) ?: return null
    val details = TmdbApiMapping.details(json, isTv)
    if (details.title.isBlank()) return null
    val logo = if (performanceMode) null else TmdbApiMapping.logo(json.optJSONObject("images"), tmdbLogoLanguages(isAnime))
    val metadata = if (minimal) {
        StreamCenterMetadata(
            title = details.title, originalTitle = null, plot = null, poster = null, background = null,
            tags = emptyList(), year = null, tmdbId = id.toString(), score = null, people = emptyList(),
            contentRating = null, showStatus = null, comingSoon = false, duration = null, trailerUrl = null,
        )
    } else {
        StreamCenterMetadata(
            title = details.title,
            originalTitle = details.originalTitle,
            plot = details.plot,
            poster = details.poster,
            background = details.background,
            tags = details.tags,
            year = details.year,
            tmdbId = id.toString(),
            score = details.score,
            people = details.people,
            contentRating = details.contentRating,
            showStatus = mapShowStatus(details.status),
            comingSoon = isComingSoon(details.status),
            duration = details.duration,
            trailerUrl = details.trailerUrl,
            logo = logo,
        )
    }
    return TmdbApiMedia(id, isTv, details, metadata, TmdbApiMapping.imdbId(json), logo)
}

internal suspend fun StreamCenter.fetchTmdbApiSeasons(tmdbId: Int): List<TmdbApiSeason>? {
    val json = StreamCenterTmdbApiClient.get("tv/$tmdbId") ?: return null
    return TmdbApiMapping.details(json, isTv = true).seasons
}

internal suspend fun StreamCenter.fetchTmdbApiSeason(tmdbId: Int, season: Int, isAnime: Boolean = false): JSONObject? =
    StreamCenterTmdbApiClient.getWithEpisodeTitles("tv/$tmdbId/season/$season", isAnime)

internal suspend fun StreamCenter.fetchTmdbApiEpisodeGroup(
    tmdbId: Int,
    expectedSeasonCount: Int,
): List<TmdbApiGroupSeason>? {
    val groups = StreamCenterTmdbApiClient.get("tv/$tmdbId/episode_groups")?.optJSONArray("results") ?: return null
    val candidates = (0 until groups.length()).mapNotNull(groups::optJSONObject)
    val chosen = candidates.firstOrNull { it.optInt("group_count") == expectedSeasonCount }
        ?: candidates.firstOrNull { it.optInt("type") == DIGITAL_EPISODE_GROUP_TYPE }
        ?: return emptyList()
    val groupId = chosen.optString("id").takeIf(String::isNotBlank) ?: return emptyList()
    val detail = StreamCenterTmdbApiClient.getWithEpisodeTitles("tv/episode_group/$groupId", isAnime = false)
        ?.optJSONArray("groups") ?: return null
    return (0 until detail.length()).mapNotNull(detail::optJSONObject)
        .sortedBy { it.optInt("order") }
        .mapIndexed { index, group ->
            TmdbApiGroupSeason(
                digitalSeason = Regex("""(\d+)""").find(group.optString("name"))?.value?.toIntOrNull() ?: (index + 1),
                episodes = TmdbApiMapping.episodes(group),
            )
        }
}

private const val DIGITAL_EPISODE_GROUP_TYPE = 4
