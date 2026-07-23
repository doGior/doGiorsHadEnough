package it.dogior.hadEnough.model

import com.lagradost.cloudstream3.ActorData

internal data class AnilistLoadMetadata(
    val anilistId: Int,
    val malId: Int?,
    val title: String,
    val titleRomaji: String?,
    val titleEnglish: String?,
    val titleNative: String?,
    val titleCandidates: List<String>,
    val originalTitle: String?,
    val format: String?,
    val poster: String?,
    val background: String?,
    val description: String?,
    val score: String?,
    val year: Int?,
    val duration: Int?,
    val episodes: Int?,
    val status: String?,
    val genres: List<String>,
    val tags: List<String>,
    val isAdult: Boolean,
    val trailerUrl: String?,
    val characters: List<ActorData>,
    val recommendations: List<AnilistRecommendation>,
    val episodeMetadata: List<AnilistEpisodeMetadata>,
    val studios: List<String>,
    val source: String?,
    val season: String?,
    val nextAiringEpisode: Int?,
    val nextAiringAtSeconds: Long?,
) {
    fun toStreamCenterMetadata(): StreamCenterMetadata = StreamCenterMetadata(
        title = title,
        originalTitle = originalTitle,
        plot = description,
        poster = poster,
        background = background,
        tags = genres + tags,
        year = year,
        tmdbId = null,
        score = score,
        people = emptyList(),
        contentRating = if (isAdult) "18+" else null,
        showStatus = null,
        comingSoon = false,
        duration = duration,
        trailerUrl = trailerUrl,
    )
}

internal data class AniZipEpisodeMetadata(
    val title: String?,
    val summary: String?,
    val overview: String?,
    val posterUrl: String?,
    val runTime: Int?,
    val airDate: String?,
    val fallbackAirDate: String?,
    val rating: Double?,
)

internal data class AnilistRecommendation(
    val anilistId: Int,
    val malId: Int?,
    val title: String,
    val format: String?,
    val posterUrl: String?,
)

internal data class AnilistEpisodeMetadata(
    val number: Int,
    val title: String?,
    val posterUrl: String?,
)

internal data class AniZipEpisodeCatalog(
    val titles: Map<String, String> = emptyMap(),
    val description: String? = null,
    val episodes: Map<Int, AniZipEpisodeMetadata> = emptyMap(),
    val kitsuId: Int? = null,
)

internal data class MalEpisodeExtra(
    val title: String?,
    val score: Double?,
    val airedDate: String?,
    val filler: Boolean,
    val recap: Boolean,
)

internal data class KitsuEpisodeMetadata(
    val name: String?,
    val description: String?,
    val posterUrl: String?,
    val runTime: Int?,
    val date: String?,
)
