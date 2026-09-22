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
    val animeUnity = animeUnitySources.firstOrNull()
    val animeWorld = animeWorldSources.firstOrNull()
    val animeSaturn = animeSaturnSources.firstOrNull()
    val tmdbEpisodes = tmdb?.episodes.orEmpty()
    val seasonEpisodes = tmdb?.seasonEpisodes.orEmpty()
    val sourceNumbers = (
        animeUnity?.episodeNumbers().orEmpty() +
            animeWorld?.episodeNumbers().orEmpty() +
            animeSaturn?.episodeNumbers().orEmpty()
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
            this.season = season
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
            val playback = animeUnity?.playbackForEpisode(number)
            val animeWorldPlaybacks = animeWorld?.playbacksForEpisode(number).orEmpty()
            val animeSaturnPlaybacks = animeSaturn?.playbacksForEpisode(number).orEmpty()
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
                this.season = season
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
        val maxMappedTmdbEp = tmdbEpisodes.values.mapNotNull { it.tmdbEpisode }.maxOrNull()
        val lastSourceNum = sourceNumbers
            .mapNotNull(::parseWholeAnimeEpisodeNumber)
            .filter { it > 0 }
            .maxOrNull() ?: 0
        val upcoming = if (maxMappedTmdbEp == null) {
            emptyList()
        } else {
            seasonEpisodes
                .filter { it.tmdbEpisode != null && it.tmdbEpisode > maxMappedTmdbEp }
                .sortedBy { it.tmdbEpisode }
                .mapIndexed { index, meta -> tmdbOnlyEpisode(lastSourceNum + index + 1, meta) }
        }
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

