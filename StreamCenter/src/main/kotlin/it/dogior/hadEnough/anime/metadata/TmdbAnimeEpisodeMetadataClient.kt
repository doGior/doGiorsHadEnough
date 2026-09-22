package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.cache.ExpiringCache
import it.dogior.hadEnough.model.AniZipEpisodeCatalog
import it.dogior.hadEnough.model.TmdbAnimeEpisodeMetadata
import it.dogior.hadEnough.model.TmdbAnimeShowRef
import it.dogior.hadEnough.util.cleanMetadataEpisodeTitle
import it.dogior.hadEnough.util.parseMetadataDate
import it.dogior.hadEnough.util.parseMetadataRuntime
import it.dogior.hadEnough.util.runCatchingCancellable
import it.dogior.hadEnough.util.cleanTmdbEpisodeDescription
import it.dogior.hadEnough.util.mapChunkedParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File

internal class TmdbAnimeEpisodeMetadataClient(
    headers: Map<String, String>,
    cacheDirectory: () -> File?,
) {
    private val headers = headers
    private val mappingClient = AniBridgeEpisodeMappingClient(headers, cacheDirectory)
    private val seasonCache = ExpiringCache<String, List<TmdbEpisode>>(
        maxEntries = 128,
        ttlMillis = 15 * 60 * 1_000L,
    )

    suspend fun resolveShow(
        anilistId: Int,
        sourceEpisodeNumbers: Set<Int>,
        aniZipCatalog: AniZipEpisodeCatalog,
    ): TmdbAnimeShowRef? = withContext(Dispatchers.IO) {
        val episodeNumbers = sourceEpisodeNumbers.filter { it > 0 }.toSet()
            .ifEmpty { aniZipCatalog.episodes.keys.filter { it > 0 }.toSet() }
            .ifEmpty { setOf(1) }
        val requestDetails = buildMap<String, Any?> {
            put("id_anilist", anilistId)
            aniZipCatalog.tmdbId?.let { put("id_tmdb_anizip", it) }
            put("episodi_richiesti", episodeNumbers.size)
        }
        MetadataLog.info(SOURCE, "Risoluzione serie TMDB avviata", requestDetails)

        resolveViaAniBridge(anilistId, episodeNumbers, requestDetails)
            ?.let { return@withContext it }

        val tmdbId = aniZipCatalog.tmdbId?.takeIf { it > 0 } ?: run {
            MetadataLog.info(
                SOURCE,
                "Nessuna serie TMDB risolvibile",
                requestDetails + mapOf("motivo" to "nessun_id_tmdb"),
            )
            return@withContext null
        }
        resolveViaAirDate(tmdbId, aniZipCatalog, episodeNumbers, requestDetails)
    }

    suspend fun resolveShowByTmdbId(
        tmdbId: Int,
        sourceEpisodeNumbers: Set<Int>,
        aniZipCatalog: AniZipEpisodeCatalog,
    ): TmdbAnimeShowRef? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext null
        resolveViaAirDate(
            tmdbId,
            aniZipCatalog,
            sourceEpisodeNumbers.filter { it > 0 }.toSet()
                .ifEmpty { aniZipCatalog.episodes.keys.filter { it > 0 }.toSet() },
            mapOf("id_tmdb" to tmdbId),
        )
    }

    private suspend fun resolveViaAniBridge(
        anilistId: Int,
        episodeNumbers: Set<Int>,
        requestDetails: Map<String, Any?>,
    ): TmdbAnimeShowRef? {
        if (anilistId <= 0 || episodeNumbers.isEmpty()) return null
        val references = mappingClient.fetch(anilistId, episodeNumbers)
        if (references.isEmpty()) {
            MetadataLog.info(SOURCE, "Nessuna mappatura AniBridge", requestDetails)
            return null
        }
        val seasonEpisodes = references.values
            .map { it.tmdbId to it.season }
            .distinct()
            .mapChunkedParallel(SEASON_REQUEST_CONCURRENCY) { (tmdbId, season) ->
                (tmdbId to season) to fetchSeason(tmdbId, season)
            }
            .toMap()
        val bySource = references.mapNotNull { (sourceEpisode, reference) ->
            seasonEpisodes[reference.tmdbId to reference.season]
                ?.firstOrNull { it.episode == reference.episode }
                ?.let { sourceEpisode to it.toMetadata() }
        }.toMap()
        val dominant = references.values
            .groupingBy { it.tmdbId to it.season }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: return null
        val (tmdbId, season) = dominant
        val dominantSeasonEpisodes = seasonEpisodes[dominant].orEmpty()
        val seasonAirDate = dominantSeasonEpisodes.mapNotNull(TmdbEpisode::airDate).minOrNull()
        MetadataLog.info(
            SOURCE,
            "Serie TMDB risolta tramite AniBridge",
            requestDetails + mapOf(
                "id_tmdb" to tmdbId,
                "stagione_tmdb" to season,
                "episodi_risolti" to bySource.size,
                "episodi_stagione_tmdb" to dominantSeasonEpisodes.size,
            ),
        )
        return TmdbAnimeShowRef(
            tmdbId = tmdbId,
            season = season,
            seasonAirDate = seasonAirDate,
            episodes = bySource,
            seasonEpisodes = dominantSeasonEpisodes.map { it.toMetadata() },
        )
    }

    private suspend fun resolveViaAirDate(
        tmdbId: Int,
        aniZipCatalog: AniZipEpisodeCatalog,
        episodeNumbers: Set<Int>,
        requestDetails: Map<String, Any?>,
    ): TmdbAnimeShowRef {
        val byDate = resolveEpisodesByAirDate(tmdbId, aniZipCatalog, episodeNumbers)
        if (byDate.isEmpty()) {
            MetadataLog.info(
                SOURCE,
                "Serie TMDB risolta senza mappatura episodi",
                requestDetails + mapOf("id_tmdb" to tmdbId),
            )
            return TmdbAnimeShowRef(tmdbId, season = null)
        }
        val dominantSeason = byDate.values
            .groupingBy(TmdbEpisode::season)
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: 1
        val dominantSeasonEpisodes = fetchSeason(tmdbId, dominantSeason)
        val seasonAirDate = dominantSeasonEpisodes
            .mapNotNull(TmdbEpisode::airDate)
            .minOrNull()
            ?: byDate.values.filter { it.season == dominantSeason }.mapNotNull(TmdbEpisode::airDate).minOrNull()
        MetadataLog.info(
            SOURCE,
            "Serie TMDB risolta tramite date di uscita",
            requestDetails + mapOf(
                "id_tmdb" to tmdbId,
                "stagione_tmdb" to dominantSeason,
                "episodi_risolti" to byDate.size,
                "episodi_stagione_tmdb" to dominantSeasonEpisodes.size,
            ),
        )
        return TmdbAnimeShowRef(
            tmdbId = tmdbId,
            season = dominantSeason,
            seasonAirDate = seasonAirDate,
            episodes = byDate.mapValues { it.value.toMetadata() },
            seasonEpisodes = dominantSeasonEpisodes.map { it.toMetadata() },
        )
    }

    private suspend fun resolveEpisodesByAirDate(
        tmdbId: Int,
        aniZipCatalog: AniZipEpisodeCatalog,
        episodeNumbers: Set<Int>,
    ): Map<Int, TmdbEpisode> {
        val sourceByDate = aniZipCatalog.episodes
            .mapNotNull { (number, episode) ->
                val date = episode.airDate ?: episode.fallbackAirDate
                date?.let { number to it }
            }
            .filter { (number, _) -> number in episodeNumbers }
            .groupBy({ (_, date) -> date }, { (number, _) -> number })
        if (sourceByDate.isEmpty()) return emptyMap()

        val tmdbByDate = fetchSeriesEpisodes(tmdbId)
            .mapNotNull { episode -> episode.airDate?.let { it to episode } }
            .groupBy({ (date, _) -> date }, { (_, episode) -> episode })
        if (tmdbByDate.isEmpty()) return emptyMap()

        return buildMap {
            sourceByDate.forEach { (date, sourceEpisodes) ->
                val tmdbEpisodes = tmdbByDate[date].orEmpty()
                if (sourceEpisodes.size != tmdbEpisodes.size) return@forEach
                sourceEpisodes.sorted()
                    .zip(tmdbEpisodes.sortedWith(compareBy(TmdbEpisode::season, TmdbEpisode::episode)))
                    .forEach { (sourceEpisode, tmdbEpisode) -> put(sourceEpisode, tmdbEpisode) }
            }
        }
    }

    private suspend fun fetchSeriesEpisodes(tmdbId: Int): List<TmdbEpisode> {
        if (tmdbId <= 0) return emptyList()
        val details = mapOf("id_tmdb" to tmdbId)
        val document = document(
            url = "$TMDB_BASE_URL/tv/$tmdbId/seasons",
            operation = "Elenco stagioni TMDB",
            details = details,
        ) ?: return emptyList()
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
        return seasons.mapChunkedParallel(SEASON_REQUEST_CONCURRENCY) { season ->
            fetchSeason(tmdbId, season).takeIf { it.isNotEmpty() }
        }.flatten()
    }

    private suspend fun fetchSeason(tmdbId: Int, season: Int): List<TmdbEpisode> {
        if (tmdbId <= 0 || season < 0) return emptyList()
        val cacheKey = "$tmdbId:$season"
        seasonCache[cacheKey]?.let { return it }
        val details = mapOf("id_tmdb" to tmdbId, "stagione_tmdb" to season)
        val episodes = document(
            url = "$TMDB_BASE_URL/tv/$tmdbId/season/$season",
            operation = "Episodi stagione TMDB",
            details = details,
        )
            ?.let { parseSeasonEpisodes(it, season) }
            .orEmpty()
        if (episodes.isNotEmpty()) seasonCache.put(cacheKey, episodes)
        return episodes
    }

    private suspend fun document(
        url: String,
        operation: String,
        details: Map<String, Any?>,
    ): Document? {
        val requestUrl = "$url?language=it-IT"
        val requestDetails = details + mapOf(
            "operazione" to operation,
            "timeout_secondi" to TMDB_TIMEOUT_SECONDS,
        )
        val responseResult = runCatchingCancellable {
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
        return Jsoup.parse(response.text, requestUrl)
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
                title = cleanMetadataEpisodeTitle(
                    card.selectFirst("div.episode_title h3 a")?.text()
                        ?: anchor?.text(),
                ),
                description = cleanTmdbEpisodeDescription(card.selectFirst("div.overview p")?.text()),
                airDate = parseMetadataDate(card.selectFirst("div.date span.date")?.text()),
                still = extractStill(card),
                runTime = parseMetadataRuntime(card.selectFirst("div.date span.runtime")?.text()),
                ratingPercent = parseRatingPercent(card),
            )
        }
    }

    private fun parseRatingPercent(card: Element): Int? {
        val rating = card.selectFirst("div.rating") ?: return null
        return Regex("""\d+""").find(rating.text())
            ?.value
            ?.toIntOrNull()
            ?.takeIf { it in 1..100 }
    }

    private fun extractStill(card: Element): String? {
        val image = card.selectFirst("div.image img") ?: return null
        val fromSrcset = image.attr("srcset")
            .split(",")
            .mapNotNull { it.trim().substringBefore(" ").takeIf(String::isNotBlank) }
            .lastOrNull()
        return (fromSrcset ?: image.attr("src")).takeIf { it.startsWith("http") }
    }

    private fun TmdbEpisode.toMetadata(): TmdbAnimeEpisodeMetadata = TmdbAnimeEpisodeMetadata(
        title = title,
        description = description,
        posterUrl = still,
        airDate = airDate,
        runTime = runTime,
        ratingPercent = ratingPercent,
        tmdbSeason = season,
        tmdbEpisode = episode,
    )

    private data class TmdbEpisode(
        val season: Int,
        val episode: Int,
        val title: String?,
        val description: String?,
        val airDate: String?,
        val still: String?,
        val runTime: Int?,
        val ratingPercent: Int?,
    )

    private companion object {
        const val SOURCE = "TMDB"
        const val TMDB_BASE_URL = "https://www.themoviedb.org"
        const val TMDB_TIMEOUT_SECONDS = 15L
        const val SEASON_REQUEST_CONCURRENCY = 3
        const val MAX_FALLBACK_SEASONS = 32
        val SEASON_IN_URL = Regex("""/season/(\d+)""")
        val EPISODE_IN_URL = Regex("""/episode/(\d+)""")
    }
}
