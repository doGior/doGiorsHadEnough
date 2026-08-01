package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.model.AniZipEpisodeCatalog
import it.dogior.hadEnough.model.TmdbAnimeEpisodeMetadata
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.mapChunkedParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class TmdbAnimeEpisodeMetadataClient(
    headers: Map<String, String>,
    cacheDirectory: () -> File?,
) {
    private val headers = headers
    private val mappingClient = AniBridgeEpisodeMappingClient(headers, cacheDirectory)
    private val seasonCache = ConcurrentHashMap<String, List<TmdbEpisode>>()

    suspend fun fetch(
        anilistId: Int,
        aniZipCatalog: AniZipEpisodeCatalog,
    ): Map<Int, TmdbAnimeEpisodeMetadata> = withContext(Dispatchers.IO) {
        val episodeNumbers = aniZipCatalog.episodes.keys.filter { it > 0 }.toSet()
        val requestDetails = buildMap<String, Any?> {
            put("id_anilist", anilistId)
            aniZipCatalog.tmdbId?.let { put("id_tmdb_anizip", it) }
            put("episodi_richiesti", episodeNumbers.size)
        }
        if (episodeNumbers.isEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Recupero metadati episodi ignorato",
                requestDetails + mapOf("motivo" to "nessun_episodio_anizip"),
            )
            return@withContext emptyMap()
        }

        MetadataLog.info(SOURCE, "Recupero metadati episodi avviato", requestDetails)
        val mappedReferences = mappingClient.fetch(anilistId, episodeNumbers)
        MetadataLog.info(
            SOURCE,
            "Mappature AniBridge ricevute",
            requestDetails + mapOf("mappature_ricevute" to mappedReferences.size),
        )
        val mappedMetadata = resolveMappedEpisodes(mappedReferences)
        if (mappedMetadata.isNotEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Metadati episodi risolti tramite AniBridge",
                requestDetails + mapOf("episodi_risolti" to mappedMetadata.size),
            )
            return@withContext mappedMetadata
        }

        val fallbackMetadata = aniZipCatalog.tmdbId?.let { tmdbId ->
            MetadataLog.info(
                SOURCE,
                "Avvio fallback TMDB basato sulle date",
                requestDetails + mapOf("id_tmdb" to tmdbId),
            )
            resolveEpisodesByAirDate(tmdbId, aniZipCatalog, episodeNumbers)
        }.orEmpty()
        MetadataLog.info(
            SOURCE,
            "Recupero metadati episodi completato",
            requestDetails + mapOf(
                "strategia_finale" to if (aniZipCatalog.tmdbId == null) "nessuna_mappatura_disponibile" else "date_di_uscita",
                "episodi_risolti" to fallbackMetadata.size,
            ),
        )
        fallbackMetadata
    }

    private suspend fun resolveMappedEpisodes(
        references: Map<Int, TmdbAnimeEpisodeReference>,
    ): Map<Int, TmdbAnimeEpisodeMetadata> {
        if (references.isEmpty()) {
            MetadataLog.info(SOURCE, "Risoluzione AniBridge non necessaria", mapOf("motivo" to "nessuna_mappatura"))
            return emptyMap()
        }
        MetadataLog.info(
            SOURCE,
            "Risoluzione episodi tramite AniBridge avviata",
            mapOf(
                "mappature_ricevute" to references.size,
                "stagioni_da_recuperare" to references.values.map { it.tmdbId to it.season }.distinct().size,
            ),
        )
        val seasonEpisodes = references.values
            .map { it.tmdbId to it.season }
            .distinct()
            .mapChunkedParallel(SEASON_REQUEST_CONCURRENCY) { (tmdbId, season) ->
                val episodes = fetchSeason(tmdbId, season)
                (tmdbId to season) to episodes
            }
            .toMap()

        val result = references.mapNotNull { (sourceEpisode, reference) ->
            val tmdbEpisode = seasonEpisodes[reference.tmdbId to reference.season]
                ?.firstOrNull { it.episode == reference.episode }
                ?: return@mapNotNull null
            tmdbEpisode.toMetadata()
                .takeIf { it.title != null || it.description != null }
                ?.let { sourceEpisode to it }
        }.toMap()
        MetadataLog.info(
            SOURCE,
            "Risoluzione episodi tramite AniBridge completata",
            mapOf(
                "mappature_ricevute" to references.size,
                "episodi_risolti" to result.size,
                "stagioni_con_episodi" to seasonEpisodes.values.count { it.isNotEmpty() },
            ),
        )
        return result
    }

    private suspend fun resolveEpisodesByAirDate(
        tmdbId: Int,
        aniZipCatalog: AniZipEpisodeCatalog,
        episodeNumbers: Set<Int>,
    ): Map<Int, TmdbAnimeEpisodeMetadata> {
        val sourceByDate = aniZipCatalog.episodes
            .mapNotNull { (number, episode) ->
                val date = episode.airDate ?: episode.fallbackAirDate
                date?.let { number to it }
            }
            .filter { (number, _) -> number in episodeNumbers }
            .groupBy({ (_, date) -> date }, { (number, _) -> number })
        if (sourceByDate.isEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Fallback TMDB per date ignorato",
                mapOf("id_tmdb" to tmdbId, "motivo" to "nessuna_data_anizip_utilizzabile"),
            )
            return emptyMap()
        }
        MetadataLog.info(
            SOURCE,
            "Risoluzione episodi TMDB per date avviata",
            mapOf(
                "id_tmdb" to tmdbId,
                "episodi_richiesti" to episodeNumbers.size,
                "date_anizip_distinte" to sourceByDate.size,
            ),
        )

        val tmdbByDate = fetchSeriesEpisodes(tmdbId)
            .mapNotNull { episode -> episode.airDate?.let { it to episode } }
            .groupBy({ (date, _) -> date }, { (_, episode) -> episode })
        if (tmdbByDate.isEmpty()) {
            MetadataLog.warning(
                SOURCE,
                "Fallback TMDB per date senza episodi utilizzabili",
                mapOf("id_tmdb" to tmdbId, "motivo" to "nessun_episodio_tmdb_con_data"),
            )
            return emptyMap()
        }

        val result = buildMap {
            sourceByDate.forEach { (date, sourceEpisodes) ->
                val tmdbEpisodes = tmdbByDate[date].orEmpty()
                if (sourceEpisodes.size != tmdbEpisodes.size) return@forEach
                sourceEpisodes.sorted()
                    .zip(tmdbEpisodes.sortedWith(compareBy(TmdbEpisode::season, TmdbEpisode::episode)))
                    .forEach { (sourceEpisode, tmdbEpisode) ->
                        tmdbEpisode.toMetadata()
                            .takeIf { it.title != null || it.description != null }
                            ?.let { put(sourceEpisode, it) }
                }
            }
        }
        MetadataLog.info(
            SOURCE,
            "Risoluzione episodi TMDB per date completata",
            mapOf(
                "id_tmdb" to tmdbId,
                "date_anizip_distinte" to sourceByDate.size,
                "date_tmdb_distinte" to tmdbByDate.size,
                "episodi_risolti" to result.size,
            ),
        )
        return result
    }

    private suspend fun fetchSeriesEpisodes(tmdbId: Int): List<TmdbEpisode> {
        if (tmdbId <= 0) {
            MetadataLog.warning(
                SOURCE,
                "Elenco stagioni TMDB ignorato",
                mapOf("id_tmdb" to tmdbId, "motivo" to "id_tmdb_non_valido"),
            )
            return emptyList()
        }
        val details = mapOf("id_tmdb" to tmdbId)
        val document = document(
            url = "$TMDB_BASE_URL/tv/$tmdbId/seasons",
            operation = "Elenco stagioni TMDB",
            details = details,
        ) ?: run {
            MetadataLog.warning(SOURCE, "Elenco stagioni TMDB non disponibile", details)
            return emptyList()
        }
        val seasons = document.select("a[href*=/season/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                    .takeUnless { it.contains("/episode/") }
                    ?: return@mapNotNull null
                SEASON_IN_URL.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .take(MAX_FALLBACK_SEASONS)
        MetadataLog.info(
            SOURCE,
            "Stagioni TMDB individuate",
            details + mapOf("stagioni_individuate" to seasons.size, "limite_stagioni" to MAX_FALLBACK_SEASONS),
        )
        val episodes = seasons.mapChunkedParallel(SEASON_REQUEST_CONCURRENCY) { season ->
            fetchSeason(tmdbId, season).takeIf { it.isNotEmpty() }
        }.flatten()
        MetadataLog.info(
            SOURCE,
            "Episodi stagioni TMDB elaborati",
            details + mapOf("stagioni_consultate" to seasons.size, "episodi_trovati" to episodes.size),
        )
        return episodes
    }

    private suspend fun fetchSeason(tmdbId: Int, season: Int): List<TmdbEpisode> {
        if (tmdbId <= 0 || season < 0) {
            MetadataLog.warning(
                SOURCE,
                "Stagione TMDB ignorata",
                mapOf(
                    "id_tmdb" to tmdbId,
                    "stagione_tmdb" to season,
                    "motivo" to "identificativo_non_valido",
                ),
            )
            return emptyList()
        }
        val cacheKey = "$tmdbId:$season"
        seasonCache[cacheKey]?.let { cached ->
            MetadataLog.info(
                SOURCE,
                "Stagione TMDB ottenuta dalla cache",
                mapOf("id_tmdb" to tmdbId, "stagione_tmdb" to season, "episodi_in_cache" to cached.size),
            )
            return cached
        }
        val details = mapOf("id_tmdb" to tmdbId, "stagione_tmdb" to season)
        val episodes = document(
            url = "$TMDB_BASE_URL/tv/$tmdbId/season/$season",
            operation = "Episodi stagione TMDB",
            details = details,
        )
            ?.let { parseSeasonEpisodes(it, season) }
            .orEmpty()
        if (episodes.isNotEmpty()) seasonCache.putIfAbsent(cacheKey, episodes)
        val resolved = seasonCache[cacheKey] ?: episodes
        MetadataLog.info(
            SOURCE,
            "Stagione TMDB elaborata",
            details + mapOf("episodi_trovati" to resolved.size, "cache_aggiornata" to episodes.isNotEmpty()),
        )
        return resolved
    }

    private suspend fun document(
        url: String,
        operation: String,
        details: Map<String, Any?>,
    ): Document? {
        val requestUrl = "$url?language=it-IT"
        val requestDetails = details + mapOf(
            "operazione" to operation,
            "tentativo" to 1,
            "tentativi_massimi" to 1,
            "timeout_secondi" to TMDB_TIMEOUT_SECONDS,
        )
        MetadataLog.info(SOURCE, "Richiesta TMDB avviata", requestDetails)
        val responseResult = runCatching {
            app.get(
                requestUrl,
                headers = headers,
                cacheTime = 0,
                timeout = TMDB_TIMEOUT_SECONDS,
            )
        }
        val response = responseResult.getOrNull() ?: run {
            MetadataLog.failure(
                source = SOURCE,
                action = "Richiesta TMDB non riuscita",
                error = responseResult.exceptionOrNull(),
                details = requestDetails + mapOf("motivo" to "errore_di_rete"),
            )
            return null
        }
        if (response.code !in 200..299 || response.text.isBlank()) {
            MetadataLog.warning(
                SOURCE,
                "Risposta TMDB non utilizzabile",
                requestDetails + mapOf(
                    "stato_http" to response.code,
                    "motivo" to if (response.code in 200..299) "risposta_vuota" else "stato_http_non_valido",
                ),
            )
            return null
        }
        val document = Jsoup.parse(response.text, requestUrl)
        MetadataLog.info(
            SOURCE,
            "Risposta TMDB elaborata",
            requestDetails + mapOf("stato_http" to response.code),
        )
        return document
    }

    private fun parseSeasonEpisodes(document: Document, fallbackSeason: Int): List<TmdbEpisode> {
        return document.select("div.episode_list div.card").mapNotNull { card ->
            val anchor = card.selectFirst("a[data-episode-number][data-season-number]")
            val href = anchor?.attr("href")
                ?: card.selectFirst("a[href*=/episode/]")?.attr("href")
            val episode = anchor?.attr("data-episode-number")?.toIntOrNull()
                ?: href?.let { EPISODE_IN_URL.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: return@mapNotNull null
            val season = anchor?.attr("data-season-number")?.toIntOrNull()
                ?: href?.let { SEASON_IN_URL.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: fallbackSeason
            TmdbEpisode(
                season = season,
                episode = episode,
                title = cleanTitle(
                    card.selectFirst("div.episode_title h3 a")?.text()
                        ?: anchor?.text(),
                ),
                description = cleanDescription(card.selectFirst("div.overview p")?.text()),
                airDate = normalizeDate(card.selectFirst("div.date span.date")?.text()),
            )
        }
    }

    private fun cleanTitle(value: String?): String? {
        return cleanText(value)
            ?.replace(Regex("""^\d+\.\s*"""), "")
            ?.takeIf(String::isNotBlank)
    }

    private fun cleanDescription(value: String?): String? {
        return cleanText(
            value
                ?.replace("Leggi di pi\u00f9", "")
                ?.replace("Leggi di piu", ""),
        )
    }

    private fun normalizeDate(value: String?): String? {
        val text = cleanText(value) ?: return null
        ISO_DATE.find(text)?.value?.let { return it }
        val match = NAMED_DATE.find(text) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = MONTHS[match.groupValues[2].lowercase(Locale.ROOT)] ?: return null
        val year = match.groupValues[3].toIntOrNull() ?: return null
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun TmdbEpisode.toMetadata(): TmdbAnimeEpisodeMetadata = TmdbAnimeEpisodeMetadata(
        title = title,
        description = description,
    )

    private data class TmdbEpisode(
        val season: Int,
        val episode: Int,
        val title: String?,
        val description: String?,
        val airDate: String?,
    )

    private companion object {
        const val SOURCE = "TMDB"
        const val TMDB_BASE_URL = "https://www.themoviedb.org"
        const val TMDB_TIMEOUT_SECONDS = 15L
        const val SEASON_REQUEST_CONCURRENCY = 3
        const val MAX_FALLBACK_SEASONS = 32
        val SEASON_IN_URL = Regex("""/season/(\d+)""")
        val EPISODE_IN_URL = Regex("""/episode/(\d+)""")
        val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        val NAMED_DATE = Regex("""(\d{1,2})\s+(\p{L}+),?\s+(\d{4})""")
        val MONTHS = mapOf(
            "gennaio" to 1,
            "febbraio" to 2,
            "marzo" to 3,
            "aprile" to 4,
            "maggio" to 5,
            "giugno" to 6,
            "luglio" to 7,
            "agosto" to 8,
            "settembre" to 9,
            "ottobre" to 10,
            "novembre" to 11,
            "dicembre" to 12,
            "january" to 1,
            "february" to 2,
            "march" to 3,
            "april" to 4,
            "may" to 5,
            "june" to 6,
            "july" to 7,
            "august" to 8,
            "september" to 9,
            "october" to 10,
            "november" to 11,
            "december" to 12,
        )
    }
}
