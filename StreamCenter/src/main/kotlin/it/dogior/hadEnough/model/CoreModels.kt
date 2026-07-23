package it.dogior.hadEnough.model

import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ShowStatus
import it.dogior.hadEnough.stremio.StreamCenterStremioPlaybackContext

internal data class StreamCenterMetadata(
    val title: String,
    val originalTitle: String?,
    val plot: String?,
    val poster: String?,
    val background: String?,
    val tags: List<String>,
    val year: Int?,
    val tmdbId: String?,
    val score: String?,
    val people: List<ActorData>,
    val contentRating: String?,
    val showStatus: ShowStatus?,
    val comingSoon: Boolean,
    val duration: Int?,
    val trailerUrl: String?,
    val logo: String? = null,
)

internal data class AnilistMetadata(
    val title: String?,
    val titleCandidates: List<String> = emptyList(),
)

internal data class AnimeSyncIds(
    val anilistId: Int?,
    val malId: Int?,
    val kitsuId: Int?,
)

internal data class StreamCenterAnimeSelection(
    val anilistId: Int?,
    val malId: Int?,
)

internal data class StreamCenterPlaybackData(
    val tmdbUrl: String? = null,
    val animeUnity: AnimeUnityPlaybackData? = null,
    val animeWorld: List<AnimeWorldPlaybackData> = emptyList(),
    val animeSaturn: List<AnimeSaturnPlaybackData> = emptyList(),
    val streamingCommunity: StreamingCommunityPlaybackData? = null,
    val stremio: StreamCenterStremioPlaybackContext? = null,
)

internal data class ResolvedLoadSources(
    val animeUnitySources: List<AnimeUnityTitleSources> = emptyList(),
    val animeWorldSources: List<AnimeWorldTitleSources> = emptyList(),
    val animeSaturnSources: List<AnimeSaturnTitleSources> = emptyList(),
    val aniZipCatalog: AniZipEpisodeCatalog = AniZipEpisodeCatalog(),
)
