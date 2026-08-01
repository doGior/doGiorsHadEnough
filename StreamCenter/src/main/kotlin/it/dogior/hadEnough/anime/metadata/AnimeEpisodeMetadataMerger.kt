package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.addDate
import it.dogior.hadEnough.model.AniZipEpisodeCatalog
import it.dogior.hadEnough.model.TmdbAnimeEpisodeMetadata
import it.dogior.hadEnough.util.StreamCenterLogger
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
        tmdbEpisodes: suspend () -> Map<Int, TmdbAnimeEpisodeMetadata> = { emptyMap() },
        targetEpisodeCount: Int? = null,
        tabName: String? = null,
        episodeFactory: (Episode.() -> Unit) -> Episode,
    ): List<Episode> = coroutineScope {
        val resolvedKitsuId = kitsuId ?: aniZipCatalog.kitsuId
        val inputDetails = buildMap<String, Any?> {
            malId?.let { put("id_myanimelist", it) }
            resolvedKitsuId?.let { put("id_kitsu", it) }
            put("episodi_anilist_in_ingresso", anilistEpisodes.size)
            put("episodi_anizip_in_ingresso", aniZipCatalog.episodes.size)
            targetEpisodeCount?.let { put("episodi_obiettivo", it) }
            tabName?.takeIf(String::isNotBlank)?.let { put("titolo_scheda", it) }
        }
        MetadataLog.info(SOURCE, "Aggregazione metadati episodi avviata", inputDetails)
        val kitsuDeferred = async(Dispatchers.IO) {
            val result = runCatching {
                kitsuClient.fetchEpisodes(resolvedKitsuId, targetEpisodeCount)
            }
            result.exceptionOrNull()?.let { error ->
                MetadataLog.failure(
                    source = SOURCE,
                    action = "Recupero episodi Kitsu durante aggregazione non riuscito",
                    error = error,
                    details = inputDetails,
                )
            }
            result.getOrDefault(emptyMap())
        }
        val malDeferred = async(Dispatchers.IO) {
            if (malId == null) {
                MetadataLog.info(
                    SOURCE,
                    "Recupero dettagli Jikan ignorato durante aggregazione",
                    inputDetails + mapOf("motivo" to "id_myanimelist_assente"),
                )
                emptyMap()
            } else {
                val result = runCatching {
                    jikanClient.fetchEpisodeExtras(malId, targetEpisodeCount)
                }
                result.exceptionOrNull()?.let { error ->
                    MetadataLog.failure(
                        source = SOURCE,
                        action = "Recupero dettagli Jikan durante aggregazione non riuscito",
                        error = error,
                        details = inputDetails,
                    )
                }
                result.getOrDefault(emptyMap())
            }
        }
        val tmdbDeferred = async(Dispatchers.IO) { tmdbEpisodes() }
        val anilistByNumber = anilistEpisodes.mapNotNull { episode ->
            episode.episode?.let { it to episode }
        }.toMap()
        val aniZip = aniZipCatalog.episodes
        val kitsu = kitsuDeferred.await()
        val mal = malDeferred.await()
        val tmdb = tmdbDeferred.await()
        MetadataLog.info(
            SOURCE,
            "Fonti metadati episodi raccolte",
            inputDetails + mapOf(
                "episodi_anilist" to anilistByNumber.size,
                "episodi_anizip" to aniZip.size,
                "episodi_kitsu" to kitsu.size,
                "episodi_jikan" to mal.size,
                "episodi_tmdb" to tmdb.size,
            ),
        )

        val numbers = (anilistByNumber.keys + aniZip.keys + kitsu.keys + mal.keys).toSortedSet()
        if (numbers.isEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Aggregazione metadati episodi non necessaria",
                inputDetails + mapOf("motivo" to "nessuna_fonte_con_episodi", "episodi_restituiti" to anilistEpisodes.size),
            )
            return@coroutineScope anilistEpisodes
        }

        val episodeAudit = mutableListOf<Map<String, Any?>>()
        val merged = numbers.map { number ->
            val anilistEpisode = anilistByNumber[number]
            val aniZipEpisode = aniZip[number]
            val kitsuEpisode = kitsu[number]
            val malEpisode = mal[number]
            val tmdbEpisode = tmdb[number]
            val markerSuffix = when {
                malEpisode?.filler == true -> " (Filler)"
                malEpisode?.recap == true -> " (Riassunto)"
                else -> ""
            }
            val baseName = tmdbEpisode?.title
                ?: aniZipEpisode?.title
                ?: kitsuEpisode?.name
                ?: anilistEpisode?.name
                ?: malEpisode?.title
            val baseNameSource = when {
                tmdbEpisode?.title != null -> "TMDB"
                aniZipEpisode?.title != null -> "AniZip"
                kitsuEpisode?.name != null -> "Kitsu"
                anilistEpisode?.name != null -> "AniList"
                malEpisode?.title != null -> "Jikan"
                else -> "StreamCenter (fallback nome)"
            }
            val posterSource = when {
                kitsuEpisode?.posterUrl != null -> "Kitsu"
                aniZipEpisode?.posterUrl != null -> "AniZip"
                anilistEpisode?.posterUrl != null -> "AniList"
                else -> "Nessuna fonte"
            }
            val descriptionSource = when {
                tmdbEpisode?.description != null -> "TMDB"
                aniZipEpisode?.summary != null -> "AniZip (summary)"
                kitsuEpisode?.description != null -> "Kitsu"
                aniZipEpisode?.overview != null -> "AniZip (overview)"
                else -> "Nessuna fonte"
            }
            val scoreSource = when {
                malEpisode?.score != null -> "Jikan"
                aniZipEpisode?.rating != null -> "AniZip"
                else -> "Nessuna fonte"
            }
            val runtimeSource = when {
                kitsuEpisode?.runTime != null -> "Kitsu"
                aniZipEpisode?.runTime != null -> "AniZip"
                else -> "Nessuna fonte"
            }
            val dateSource = when {
                aniZipEpisode?.airDate != null -> "AniZip (airDate)"
                kitsuEpisode?.date != null -> "Kitsu"
                malEpisode?.airedDate != null -> "Jikan"
                aniZipEpisode?.fallbackAirDate != null -> "AniZip (fallbackAirDate)"
                else -> "Nessuna fonte"
            }

            val mergedEpisode = episodeFactory {
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
                this.description = tmdbEpisode?.description
                    ?: aniZipEpisode?.summary
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
            episodeAudit += linkedMapOf(
                "episodio" to sourced(number, "StreamCenter (indice aggregato)"),
                "stagione" to sourced(mergedEpisode.season, "StreamCenter (normalizzazione)"),
                "nome" to StreamCenterLogger.SourcedValue(
                    value = mergedEpisode.name,
                    sources = buildList {
                        add(baseNameSource)
                        if (markerSuffix.isNotEmpty()) add("Jikan (marcatore filler/riassunto)")
                    },
                    note = "Fallback: TMDB → AniZip → Kitsu → AniList → Jikan.",
                ),
                "poster" to sourced(
                    mergedEpisode.posterUrl,
                    posterSource,
                    note = "Fallback: Kitsu → AniZip → AniList.",
                ),
                "descrizione" to sourced(
                    mergedEpisode.description,
                    descriptionSource,
                    note = "Fallback: TMDB → AniZip summary → Kitsu → AniZip overview.",
                ),
                "punteggio" to sourced(
                    mergedEpisode.score?.toString(),
                    scoreSource,
                    note = "Fallback: Jikan → AniZip.",
                ),
                "durata_minuti" to sourced(
                    mergedEpisode.runTime,
                    runtimeSource,
                    note = "Fallback: Kitsu → AniZip.",
                ),
                "data_timestamp" to sourced(
                    mergedEpisode.date,
                    dateSource,
                    note = "Fallback: AniZip airDate → Kitsu → Jikan → AniZip fallbackAirDate.",
                ),
                "filler" to sourced(malEpisode?.filler, "Jikan"),
                "riassunto" to sourced(malEpisode?.recap, "Jikan"),
            )
            mergedEpisode
        }
        MetadataLog.info(
            SOURCE,
            "Aggregazione metadati episodi completata",
            inputDetails + mapOf(
                "episodi_unificati" to merged.size,
                "titoli_da_tmdb" to numbers.count { tmdb[it]?.title != null },
                "titoli_da_anizip" to numbers.count { tmdb[it]?.title == null && aniZip[it]?.title != null },
                "titoli_da_kitsu" to numbers.count {
                    tmdb[it]?.title == null && aniZip[it]?.title == null && kitsu[it]?.name != null
                },
                "titoli_da_anilist" to numbers.count {
                    tmdb[it]?.title == null && aniZip[it]?.title == null &&
                        kitsu[it]?.name == null && anilistByNumber[it]?.name != null
                },
                "titoli_da_jikan" to numbers.count {
                    tmdb[it]?.title == null && aniZip[it]?.title == null &&
                        kitsu[it]?.name == null && anilistByNumber[it]?.name == null && mal[it]?.title != null
                },
                "descrizioni_da_tmdb" to numbers.count { tmdb[it]?.description != null },
                "descrizioni_da_anizip" to numbers.count {
                    tmdb[it]?.description == null && aniZip[it]?.summary != null
                },
                "descrizioni_da_kitsu" to numbers.count {
                    tmdb[it]?.description == null && aniZip[it]?.summary == null && kitsu[it]?.description != null
                },
                "poster_da_kitsu" to numbers.count { kitsu[it]?.posterUrl != null },
                "poster_da_anizip" to numbers.count { kitsu[it]?.posterUrl == null && aniZip[it]?.posterUrl != null },
                "poster_da_anilist" to numbers.count {
                    kitsu[it]?.posterUrl == null && aniZip[it]?.posterUrl == null && anilistByNumber[it]?.posterUrl != null
                },
                "episodi_filler" to numbers.count { mal[it]?.filler == true },
                "episodi_riassunto" to numbers.count { mal[it]?.recap == true },
                "dettaglio_completo_episodi" to episodeAudit,
            ),
        )
        merged
    }

    private fun sourced(
        value: Any?,
        source: String,
        note: String? = null,
    ): StreamCenterLogger.SourcedValue {
        return StreamCenterLogger.SourcedValue(
            value = value,
            sources = listOf(source),
            note = note,
        )
    }

    private companion object {
        const val SOURCE = "Aggregatore metadati"
    }
}
