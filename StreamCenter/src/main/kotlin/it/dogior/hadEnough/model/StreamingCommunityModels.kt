package it.dogior.hadEnough.model

internal data class StreamingCommunityTitle(
    val id: Int,
    val slug: String,
    val name: String,
    val type: String,
    val tmdbId: Int?,
    val imdbId: String? = null,
    val year: Int?,
    val seasons: List<StreamingCommunitySeason>,
    val plot: String? = null,
    val score: String? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val age: Int? = null,
    val posterFilename: String? = null,
    val backgroundFilename: String? = null,
    val logoFilename: String? = null,
)

internal data class StreamingCommunitySeason(
    val id: Int,
    val number: Int,
    val episodes: List<StreamingCommunityEpisode>,
)

internal data class StreamingCommunityEpisode(
    val id: Int,
    val number: Int,
)

internal data class StreamingCommunityPlaybackData(
    val iframeUrl: String,
    val type: String,
    val tmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)
