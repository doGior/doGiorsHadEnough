package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.addDate
import it.dogior.hadEnough.model.AniZipEpisodeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class AnimeEpisodeMetadataMerger(
    private val kitsuClient: KitsuMetadataClient,
    private val jikanClient: JikanMetadataClient,
) {
    suspend fun merge(
        malId: Int?,
        kitsuId: Int?,
        anilistEpisodes: List<Episode>,
        aniZipCatalog: AniZipEpisodeCatalog,
        targetEpisodeCount: Int? = null,
        episodeFactory: (Episode.() -> Unit) -> Episode,
    ): List<Episode> = coroutineScope {
        val kitsuDeferred = async(Dispatchers.IO) {
            val resolvedKitsuId = kitsuId ?: aniZipCatalog.kitsuId
            runCatching {
                kitsuClient.fetchEpisodes(resolvedKitsuId, targetEpisodeCount)
            }.getOrDefault(emptyMap())
        }
        val malDeferred = async(Dispatchers.IO) {
            malId?.let {
                runCatching {
                    jikanClient.fetchEpisodeExtras(it, targetEpisodeCount)
                }.getOrDefault(emptyMap())
            }.orEmpty()
        }
        val anilistByNumber = anilistEpisodes.mapNotNull { episode ->
            episode.episode?.let { it to episode }
        }.toMap()
        val aniZip = aniZipCatalog.episodes
        val kitsu = kitsuDeferred.await()
        val mal = malDeferred.await()

        val numbers = (anilistByNumber.keys + aniZip.keys + kitsu.keys + mal.keys).toSortedSet()
        if (numbers.isEmpty()) return@coroutineScope anilistEpisodes

        numbers.map { number ->
            val anilistEpisode = anilistByNumber[number]
            val aniZipEpisode = aniZip[number]
            val kitsuEpisode = kitsu[number]
            val malEpisode = mal[number]
            val markerSuffix = when {
                malEpisode?.filler == true -> " (Filler)"
                malEpisode?.recap == true -> " (Riassunto)"
                else -> ""
            }
            val baseName = aniZipEpisode?.title
                ?: kitsuEpisode?.name
                ?: anilistEpisode?.name
                ?: malEpisode?.title

            episodeFactory {
                this.episode = number
                this.season = 1
                this.name = when {
                    baseName != null -> baseName + markerSuffix
                    markerSuffix.isNotEmpty() -> "Episodio $number$markerSuffix"
                    else -> null
                }
                this.posterUrl = kitsuEpisode?.posterUrl
                    ?: aniZipEpisode?.posterUrl
                    ?: anilistEpisode?.posterUrl
                this.description = aniZipEpisode?.summary
                    ?: kitsuEpisode?.description
                    ?: aniZipEpisode?.overview
                malEpisode?.score?.let { this.score = Score.from(it.toString(), 5) }
                    ?: aniZipEpisode?.rating?.let { this.score = Score.from(it.toString(), 10) }
                this.runTime = kitsuEpisode?.runTime ?: aniZipEpisode?.runTime
                when {
                    aniZipEpisode?.airDate != null -> this.addDate(aniZipEpisode.airDate)
                    kitsuEpisode?.date != null -> this.addDate(kitsuEpisode.date)
                    malEpisode?.airedDate != null -> this.addDate(malEpisode.airedDate)
                    else -> aniZipEpisode?.fallbackAirDate?.let { this.addDate(it) }
                }
            }
        }
    }
}
