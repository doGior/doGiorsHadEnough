package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.cache.CachedEpisode
import it.dogior.hadEnough.cache.CachedMediaEntry
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import it.dogior.hadEnough.model.StreamCenterPlaybackData
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.withTimeoutOrNull

internal class AnimeSeasonEpisodeMetadata(
    private val api: MainAPI,
    private val aniZip: AniZipMetadataClient,
    private val tmdb: TmdbAnimeEpisodeMetadataClient,
    private val cacheEnabled: () -> Boolean,
) {
    suspend fun load(release: AnimeSeriesEntry): List<Episode> {
        val key = StreamCenterMediaCache.animeEpisodesKey(release.id)
        if (cacheEnabled()) {
            StreamCenterMediaCache.readByKey(key)?.let { cached ->
                return cached.episodes.map { episode ->
                    api.newEpisode(episode.data) {
                        name = episode.name
                        season = episode.season
                        this.episode = episode.episode
                        posterUrl = episode.posterUrl
                        description = episode.description
                        runTime = episode.runTime
                        date = episode.dateMillis
                        score = episode.score?.let { Score.from(it, 10_000) }
                    }
                }
            }
        }
        val catalog = aniZip.fetch(release.id, release.malId)
        val numbers = (catalog.episodes.keys + (1..release.availableEpisodes)).filter {
            it > 0 && (release.episodeCount == null || it <= release.episodeCount)
        }.toSortedSet()
        val mapped = runCatchingCancellable {
            withTimeoutOrNull(12_000) { tmdb.resolveShow(release.id, numbers, catalog) }
        }.getOrNull()
        numbers += mapped?.episodes.orEmpty().keys.filter {
            it > 0 && (release.episodeCount == null || it <= release.episodeCount)
        }
        val count = release.episodeCount ?: numbers.maxOrNull() ?: 0
        val episodes = numbers.map { number ->
            val metadata = mapped?.episodes?.get(number)
            val fallback = catalog.episodes[number]
            api.newEpisode(StreamCenterPlaybackData(animeSeason = AnimeSeasonPlayback(
                release.id, release.malId, number, count,
            )).toJson()) {
                name = metadata?.title ?: fallback?.title ?: "Episodio $number"
                episode = number
                season = metadata?.tmdbSeason ?: mapped?.season
                posterUrl = metadata?.posterUrl ?: fallback?.posterUrl ?: release.poster
                description = metadata?.description ?: fallback?.overview ?: fallback?.summary
                runTime = metadata?.runTime ?: fallback?.runTime
                score = metadata?.ratingPercent?.let { Score.from(it, 100) }
                    ?: fallback?.rating?.let { Score.from(it, 10) }
                (metadata?.airDate ?: fallback?.airDate ?: fallback?.fallbackAirDate)?.let { addDate(it) }
            }
        }
        if (cacheEnabled() && (mapped?.episodes?.isNotEmpty() == true || catalog.episodes.isNotEmpty())) {
            val now = System.currentTimeMillis()
            val nextAir = episodes.mapNotNull { it.date }.filter { it > now }.minOrNull()
            val ongoing = release.status != "FINISHED"
            val expiry = StreamCenterMediaCache.computeMediaExpiry(now, if (ongoing) "Ongoing" else "Completed", nextAir)
            StreamCenterMediaCache.writeMedia(CachedMediaEntry(
                schemaVersion = StreamCenterMediaCache.SCHEMA_VERSION,
                key = key,
                url = "https://anilist.co/anime/${release.id}",
                type = StreamCenterMediaCache.TYPE_ANIME,
                title = release.title,
                posterUrl = release.poster,
                episodes = episodes.map { episode -> CachedEpisode(
                    data = episode.data, name = episode.name, season = episode.season, episode = episode.episode,
                    posterUrl = episode.posterUrl, description = episode.description, runTime = episode.runTime,
                    dateMillis = episode.date, score = episode.score?.toInt(10_000),
                ) },
                cachedAtMillis = now,
                expiresAtMillis = if (episodes.any { it.description == null || it.score == null }) {
                    minOf(expiry, now + 30 * 60_000)
                } else expiry,
            ))
        }
        return episodes
    }
}
