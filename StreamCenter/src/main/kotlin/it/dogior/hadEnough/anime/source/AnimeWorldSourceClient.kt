package it.dogior.hadEnough.anime.source

import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.model.AnilistMetadata
import it.dogior.hadEnough.model.AnimeSyncIds
import it.dogior.hadEnough.model.AnimeWorldEpisodeInfo
import it.dogior.hadEnough.model.AnimeWorldPageData
import it.dogior.hadEnough.model.AnimeWorldPlaybackData
import it.dogior.hadEnough.model.AnimeWorldSearchItem
import it.dogior.hadEnough.model.AnimeWorldTitleSources
import it.dogior.hadEnough.model.StreamCenterMetadata
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.normalizeAnimeEpisodeNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

internal class AnimeWorldSourceClient(
    private val baseUrl: () -> String,
    private val headers: Map<String, String>,
    private val queryLimit: () -> Int,
    private val detailCandidateLimit: () -> Int,
    private val ensureDomain: suspend () -> Unit,
) {
    suspend fun fetchSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeWorldTitleSources> {
        if (syncIds.isEmpty()) {
            AnimeSourceLog.warning(SOURCE_NAME, "Ricerca sorgenti ignorata: identificativi assenti")
            return emptyList()
        }

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(queryLimit())
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Ricerca sorgenti avviata",
            mapOf(
                "titoli_candidati" to titleCandidates.size,
                "identificativi_sincronizzazione" to syncIds.size,
            ),
        )
        val searchCandidates = titleCandidates
            .mapChunkedParallel(SEARCH_PARALLELISM) { search(it) }
            .flatten()
            .distinctBy { it.url }
            .map { item ->
                item to titleCandidates.maxOf { candidate ->
                    maxOf(
                        sourceTitleScore(item.title, candidate),
                        item.otherTitle?.let { sourceTitleScore(it, candidate) } ?: 0,
                    )
                }
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (item, _) -> item }
            .take(detailCandidateLimit())
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Candidati dettaglio selezionati",
            mapOf(
                "ricerche_eseguite" to titleCandidates.size,
                "candidati_dettaglio" to searchCandidates.size,
            ),
        )
        val pageData = coroutineScope {
            searchCandidates
                .map { item -> async(Dispatchers.IO) { fetchPage(item) } }
                .awaitAll()
                .filterNotNull()
        }
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Dettagli candidati completati",
            mapOf("dettagli_con_episodi" to pageData.size),
        )
        val exactTitleKeys = exactAnimeTitleKeys(metadata, anilistMetadata)

        val resolvedSources = syncIds.mapNotNull { sync ->
            val idMatches = pageData.filter { it.matches(sync) }
            val matches = idMatches.ifEmpty {
                if (syncIds.size > 1) {
                    AnimeSourceLog.warning(
                        SOURCE_NAME,
                        "Fallback titolo non applicato: piu identificativi disponibili",
                    )
                    return@mapNotNull null
                }
                pageData.filter { page ->
                    page.anilistId == null && page.malId == null && page.kitsuId == null &&
                        (
                            sourceTitleDedupKey(page.searchItem.title) in exactTitleKeys ||
                                page.searchItem.otherTitle
                                    ?.let { sourceTitleDedupKey(it) in exactTitleKeys } == true
                            )
                }
            }
            when {
                idMatches.isNotEmpty() -> AnimeSourceLog.info(
                    SOURCE_NAME,
                    "Corrispondenza tramite identificativi",
                    mapOf("corrispondenze" to idMatches.size),
                )

                matches.isNotEmpty() -> AnimeSourceLog.warning(
                    SOURCE_NAME,
                    "Fallback titolo applicato: identificativi mancanti nella sorgente",
                    mapOf("corrispondenze" to matches.size),
                )
            }
            if (matches.isEmpty()) {
                AnimeSourceLog.warning(SOURCE_NAME, "Nessuna sorgente corrispondente")
                return@mapNotNull null
            }

            val subSources = matches
                .filter { !it.searchItem.isDub }
                .mergeEpisodeSources()
            val dubSources = matches
                .filter { it.searchItem.isDub }
                .mergeEpisodeSources()

            AnimeWorldTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
                ?.also {
                    AnimeSourceLog.info(
                        SOURCE_NAME,
                        "Sorgenti episodio disponibili",
                        mapOf(
                            "episodi_sub" to subSources.size,
                            "episodi_doppiati" to dubSources.size,
                        ),
                    )
                }
        }
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Ricerca sorgenti conclusa",
            mapOf("contenuti_risolti" to resolvedSources.size),
        )
        return resolvedSources
    }

    private suspend fun search(query: String): List<AnimeWorldSearchItem> {
        AnimeSourceLog.info(SOURCE_NAME, "Tentativo di ricerca provider avviato")
        try {
            ensureDomain()
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Aggiornamento dominio non riuscito", error = error)
            throw error
        }
        val providerUrl = baseUrl()
        val url = "$providerUrl/filter?sort=0&keyword=${URLEncoder.encode(query, "UTF-8")}"
        val doc = try {
            Jsoup.parse(app.get(url, headers = headers).text, url)
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Ricerca provider non riuscita", error = error)
            throw error
        }

        val results = doc.select("div.film-list > .item").mapNotNull { item ->
            val anchor = item.selectFirst("a.name[href]") ?: return@mapNotNull null
            val title = cleanText(anchor.text()) ?: return@mapNotNull null
            val otherTitle = cleanText(anchor.attr("data-jtitle"))
            val itemUrl = absoluteProviderUrl(providerUrl, anchor.attr("href")).trimEnd('/')
            val isDub = item.select(".status .dub").isNotEmpty() ||
                title.contains("(ITA)", ignoreCase = true) ||
                otherTitle.orEmpty().contains("(ITA)", ignoreCase = true)

            AnimeWorldSearchItem(
                url = itemUrl,
                title = title,
                otherTitle = otherTitle,
                isDub = isDub,
            )
        }
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Tentativo di ricerca provider completato",
            mapOf("risultati_validi" to results.size),
        )
        return results
    }

    private suspend fun fetchPage(item: AnimeWorldSearchItem): AnimeWorldPageData? {
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Dettaglio candidato richiesto",
            mapOf("candidato_doppiato" to item.isDub),
        )
        val html = try {
            app.get(item.url, headers = headers).text
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Dettaglio candidato non riuscito", error = error)
            throw error
        }
        val doc = Jsoup.parse(html, item.url)
        val isDub = item.isDub || html.contains("window.animeDub = true", ignoreCase = true)
        val label = if (isDub) "[DUB]" else "[SUB]"
        val episodeSources = parseEpisodes(doc).mapNotNull { episode ->
            val number = normalizeAnimeEpisodeNumber(episode.number) ?: return@mapNotNull null
            number to AnimeWorldPlaybackData(
                label = label,
                pageUrl = item.url,
                episodeToken = episode.token,
            )
        }.toMap()

        val pageData = AnimeWorldPageData(
            searchItem = item.copy(isDub = isDub),
            anilistId = doc.selectFirst("#anilist-button[href]")
                ?.attr("href")
                ?.substringAfterLast('/')
                ?.toIntOrNull(),
            malId = doc.selectFirst("#mal-button[href]")
                ?.attr("href")
                ?.substringAfterLast('/')
                ?.toIntOrNull(),
            kitsuId = doc.selectFirst("a[href*='kitsu.io/anime/'][href], a[href*='kitsu.app/anime/'][href]")
                ?.attr("href")
                ?.let(::extractKitsuId),
            episodeSources = episodeSources,
        )
        if (pageData.episodeSources.isEmpty()) {
            AnimeSourceLog.warning(SOURCE_NAME, "Dettaglio candidato senza episodi")
            return null
        }
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Dettaglio candidato completato",
            mapOf(
                "episodi_rilevati" to pageData.episodeSources.size,
                "anilist_disponibile" to (pageData.anilistId != null),
                "mal_disponibile" to (pageData.malId != null),
                "kitsu_disponibile" to (pageData.kitsuId != null),
            ),
        )
        return pageData
    }

    private fun parseEpisodes(doc: Document): List<AnimeWorldEpisodeInfo> {
        val preferredAnchors = doc.select(".widget.servers .server[data-name=9] a[data-id][data-episode-num]")
        val anchors = preferredAnchors.ifEmpty {
            doc.select(".widget.servers a[data-id][data-episode-num]")
        }
        return anchors.mapNotNull { anchor ->
            val token = anchor.attr("data-id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val number = anchor.attr("data-episode-num").takeIf(String::isNotBlank)
                ?: anchor.attr("data-num").takeIf(String::isNotBlank)
                ?: anchor.text().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            AnimeWorldEpisodeInfo(number = number, token = token)
        }.distinctBy { normalizeAnimeEpisodeNumber(it.number) ?: it.number }
    }

    private fun AnimeWorldPageData.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId) ||
            (syncIds.kitsuId != null && kitsuId == syncIds.kitsuId)
    }

    private fun List<AnimeWorldPageData>.mergeEpisodeSources(): Map<String, AnimeWorldPlaybackData> {
        val merged = linkedMapOf<String, AnimeWorldPlaybackData>()
        forEach { pageData ->
            pageData.episodeSources.forEach { (number, playback) ->
                if (!merged.containsKey(number)) merged[number] = playback
            }
        }
        return merged
    }

    private fun extractKitsuId(text: String): Int? {
        return Regex("""(?:kitsu\.(?:io|app)/anime/|/kitsu/)(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private companion object {
        const val SOURCE_NAME = "AnimeWorld"
        const val SEARCH_PARALLELISM = 4
    }
}
