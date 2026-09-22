package it.dogior.hadEnough.model

import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ShowStatus

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
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val absoluteEpisodeNumber: Int?,
)

internal data class AniZipEpisodeCatalog(
    val titles: Map<String, String> = emptyMap(),
    val description: String? = null,
    val episodes: Map<Int, AniZipEpisodeMetadata> = emptyMap(),
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
)

internal data class TmdbAnimeEpisodeMetadata(
    val title: String?,
    val description: String?,
    val posterUrl: String? = null,
    val airDate: String? = null,
    val runTime: Int? = null,
    val ratingPercent: Int? = null,
    val tmdbSeason: Int? = null,
    val tmdbEpisode: Int? = null,
)

internal data class TmdbAnimeShowRef(
    val tmdbId: Int,
    val season: Int?,
    val seasonAirDate: String? = null,
    val episodes: Map<Int, TmdbAnimeEpisodeMetadata> = emptyMap(),
    val seasonEpisodes: List<TmdbAnimeEpisodeMetadata> = emptyList(),
)

internal data class TmdbAnimeMetadata(
    val tmdbId: Int,
    val season: Int?,
    val title: String?,
    val englishTitle: String?,
    val originalTitle: String?,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val plot: String?,
    val genres: List<String>,
    val streamingPlatforms: String?,
    val budget: String?,
    val revenue: String?,
    val airingSeasonLabel: String?,
    val year: Int?,
    val duration: Int?,
    val score: String?,
    val contentRating: String?,
    val showStatus: ShowStatus?,
    val comingSoon: Boolean,
    val trailerUrl: String?,
    val alternativeTitles: List<String>,
    val episodes: Map<Int, TmdbAnimeEpisodeMetadata>,
    val seasonEpisodes: List<TmdbAnimeEpisodeMetadata>,
    val seasonName: String? = null,
)
