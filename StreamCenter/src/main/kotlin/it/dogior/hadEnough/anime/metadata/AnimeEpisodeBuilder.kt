package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.StreamCenterStremioPlaybackContext
import it.dogior.hadEnough.torrent.StreamCenterTorrentPlaybackContext
import it.dogior.hadEnough.torrent.forEpisode
import it.dogior.hadEnough.util.parseWholeAnimeEpisodeNumber

internal fun futureTmdbEpisodes(
    mappedEpisodes: Map<Int, TmdbAnimeEpisodeMetadata>,
    seasonEpisodes: List<TmdbAnimeEpisodeMetadata>,
    lastSourceNumber: Int,
): List<Pair<Int, TmdbAnimeEpisodeMetadata>> {
    val offset = mappedEpisodes.mapNotNull { (sourceNumber, meta) ->
        meta.tmdbEpisode?.let { it - sourceNumber }
    }.distinct().singleOrNull() ?: return emptyList()
    return seasonEpisodes.mapNotNull { meta ->
        meta.tmdbEpisode?.minus(offset)
            ?.takeIf { it > lastSourceNumber }
            ?.let { sourceNumber -> sourceNumber to meta }
    }.distinctBy { it.first }.sortedBy { it.first }
}

@Suppress("DEPRECATION_ERROR", "DEPRECATION")
internal fun MainAPI.buildAnimeEpisodes(
    animeUnitySources: List<AnimeUnityTitleSources>,
    animeWorldSources: List<AnimeWorldTitleSources>,
    animeSaturnSources: List<AnimeSaturnTitleSources>,
    tmdb: TmdbAnimeMetadata?,
    fallbackPoster: String?,
    stremioContext: StreamCenterStremioPlaybackContext,
    torrentContext: StreamCenterTorrentPlaybackContext?,
    displaySeason: Int? = null,
): List<Episode> {
    val tmdbEpisodes = tmdb?.episodes.orEmpty()
    val seasonEpisodes = tmdb?.seasonEpisodes.orEmpty()
    val sourceNumbers = (
        animeUnitySources.flatMap { it.episodeNumbers() } +
            animeWorldSources.flatMap { it.episodeNumbers() } +
            animeSaturnSources.flatMap { it.episodeNumbers() }
        )
        .distinct()
        .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))

    fun tmdbOnlyEpisode(displayNumber: Int, meta: TmdbAnimeEpisodeMetadata): Episode {
        val season = meta.tmdbSeason ?: tmdb?.season ?: displaySeason ?: 1
        val episodeNumber = meta.tmdbEpisode ?: displayNumber
        return newEpisode(
            StreamCenterPlaybackData(
                stremio = stremioContext.copy(season = season, episode = episodeNumber),
                torrent = torrentContext?.forEpisode(season, episodeNumber),
            ).toJson(),
        ) {
            this.name = meta.title ?: "Episodio $displayNumber"
            this.season = displaySeason ?: season
            this.episode = displayNumber
            this.posterUrl = meta.posterUrl ?: fallbackPoster
            this.description = meta.description
            this.runTime = meta.runTime
            meta.ratingPercent?.let {
                this.rating = it
                this.score = Score.from(it, 100)
            }
            meta.airDate?.let { addDate(it) }
        }
    }

    if (sourceNumbers.isNotEmpty()) {
        val played = sourceNumbers.mapNotNull { number ->
            val playback = animeUnitySources.firstNotNullOfOrNull { it.playbackForEpisode(number) }
            val animeWorldPlaybacks = animeWorldSources.flatMap { it.playbacksForEpisode(number) }
                .distinctBy { "${it.label}:${it.episodeToken}:${it.pageUrl}" }
            val animeSaturnPlaybacks = animeSaturnSources.flatMap { it.playbacksForEpisode(number) }
                .distinctBy { "${it.label}:${it.watchUrl}" }
            if (playback == null && animeWorldPlaybacks.isEmpty() && animeSaturnPlaybacks.isEmpty()) {
                return@mapNotNull null
            }
            val whole = parseWholeAnimeEpisodeNumber(number)
            val meta = whole?.let { tmdbEpisodes[it] }
            val isSpecial = whole == null || whole <= 0
            val season = meta?.tmdbSeason ?: tmdb?.season ?: displaySeason ?: 1
            val episodeNumber = meta?.tmdbEpisode ?: whole?.takeIf { it > 0 }
            newEpisode(
                StreamCenterPlaybackData(
                    animeUnity = playback,
                    animeWorld = animeWorldPlaybacks,
                    animeSaturn = animeSaturnPlaybacks,
                    stremio = stremioContext.copy(season = season, episode = episodeNumber),
                    torrent = torrentContext?.forEpisode(season, episodeNumber),
                ).toJson(),
            ) {
                this.name = meta?.title
                    ?: if (isSpecial) "Speciale $number" else "Episodio $number"
                this.season = displaySeason ?: season
                this.episode = whole?.takeIf { it > 0 }
                this.posterUrl = meta?.posterUrl ?: fallbackPoster
                this.description = meta?.description
                this.runTime = meta?.runTime
                meta?.ratingPercent?.let {
                    this.rating = it
                    this.score = Score.from(it, 100)
                }
                meta?.airDate?.let { addDate(it) }
            }
        }
        val lastSourceNum = sourceNumbers
            .mapNotNull(::parseWholeAnimeEpisodeNumber)
            .filter { it > 0 }
            .maxOrNull() ?: 0
        val upcoming = futureTmdbEpisodes(tmdbEpisodes, seasonEpisodes, lastSourceNum)
            .map { (sourceNumber, meta) -> tmdbOnlyEpisode(sourceNumber, meta) }
        return played + upcoming
    }

    if (tmdbEpisodes.isNotEmpty()) {
        return tmdbEpisodes.entries.sortedBy { it.key }.map { (number, meta) -> tmdbOnlyEpisode(number, meta) }
    }
    return seasonEpisodes
        .filter { it.tmdbEpisode != null }
        .sortedBy { it.tmdbEpisode }
        .map { meta -> tmdbOnlyEpisode(meta.tmdbEpisode!!, meta) }
}

