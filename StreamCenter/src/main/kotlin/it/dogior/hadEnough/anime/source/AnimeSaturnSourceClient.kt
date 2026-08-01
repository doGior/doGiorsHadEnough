package it.dogior.hadEnough.anime.source

import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.model.AnilistMetadata
import it.dogior.hadEnough.model.AnimeSaturnPageData
import it.dogior.hadEnough.model.AnimeSaturnPlaybackData
import it.dogior.hadEnough.model.AnimeSaturnSearchItem
import it.dogior.hadEnough.model.AnimeSaturnTitleSources
import it.dogior.hadEnough.model.AnimeSyncIds
import it.dogior.hadEnough.model.StreamCenterMetadata
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.normalizeAnimeEpisodeNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URLEncoder

internal class AnimeSaturnSourceClient(
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
    ): List<AnimeSaturnTitleSources> {
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
        val searchResults = titleCandidates
            .mapChunkedParallel(SEARCH_PARALLELISM) { search(it) }
            .flatten()
        val searchCandidates = searchResults
            .groupBy { it.url }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.score } }
            .sortedWith(
                compareByDescending<AnimeSaturnSearchItem> { it.score }
                    .thenBy { it.title.length }
                    .thenBy { it.url },
            )
            .take(detailCandidateLimit())
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Candidati dettaglio selezionati",
            mapOf(
                "risultati_ricerca" to searchResults.size,
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
                        sourceTitleDedupKey(page.searchItem.title) in exactTitleKeys
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

            val subSources = matches.filter { !it.searchItem.isDub }.mergeEpisodeSources()
            val dubSources = matches.filter { it.searchItem.isDub }.mergeEpisodeSources()

            AnimeSaturnTitleSources(
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

    private suspend fun search(query: String): List<AnimeSaturnSearchItem> {
        AnimeSourceLog.info(SOURCE_NAME, "Tentativo di ricerca provider avviato")
        try {
            ensureDomain()
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Aggiornamento dominio non riuscito", error = error)
            throw error
        }
        val providerUrl = baseUrl()
        val url = "$providerUrl/filter?key=${URLEncoder.encode(query, "UTF-8")}"
        val doc = try {
            Jsoup.parse(app.get(url, headers = headers).text, url)
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Ricerca provider non riuscita", error = error)
            throw error
        }

        val results = doc.select(
            "a.ac[href^='/anime/'], a.ac[href*='/anime/'], " +
                "a[href^='/anime/'], a[href*='animesaturn.net/anime/']"
        ).mapNotNull { item ->
            val href = item.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (href.contains("/ep-", ignoreCase = true)) return@mapNotNull null
            val parsedTitle = cleanText(item.selectFirst(".ac__title, h3, h4, .title")?.text())
                ?: cleanText(item.selectFirst("img[alt]")?.attr("alt"))
                ?: cleanText(item.attr("title"))
                ?: cleanText(item.text())
            val title = parsedTitle
                ?.takeUnless { sourceTitleDedupKey(it) in setOf("dettagli", "detail", "details") }
                ?: titleFromHref(href)
                ?: return@mapNotNull null
            val itemUrl = absoluteProviderUrl(providerUrl, href).trimEnd('/')
            val score = sourceTitleScore(title, query)
            if (score <= 0) return@mapNotNull null
            val isDub = item.select(".ac__dub-badge").isNotEmpty() ||
                Regex("""(?i)(?:^|[-\s(])ita(?:$|[-\s)])""").containsMatchIn(title) ||
                Regex("""(?i)(?:^|[-/])ita(?:$|[-/])""").containsMatchIn(itemUrl)

            AnimeSaturnSearchItem(
                url = itemUrl,
                title = title,
                isDub = isDub,
                score = score,
            )
        }
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Tentativo di ricerca provider completato",
            mapOf("risultati_validi" to results.size),
        )
        return results
    }

    private suspend fun fetchPage(item: AnimeSaturnSearchItem): AnimeSaturnPageData? {
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
        val isDub = item.isDub ||
            Regex("""(?i)(?:^|[-/])ita(?:$|[-/])""").containsMatchIn(item.url) ||
            Regex("""(?i)(?:^|[-\s(])ita(?:$|[-\s)])""").containsMatchIn(item.title)
        val label = if (isDub) "[DUB]" else "[SUB]"
        val episodeSources = doc
            .select("a.ep-tile[href*=/ep-], a[href*='/episode/'][href*='/ep-'], a[href*='/anime/'][href*='/ep-']")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val number = Regex("""/ep-([0-9]+(?:\.[0-9]+)?)""")
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: anchor.attr("title").let {
                        Regex("""(?i)episodio\s+([0-9]+(?:\.[0-9]+)?)""")
                            .find(it)
                            ?.groupValues
                            ?.getOrNull(1)
                    }
                    ?: return@mapNotNull null
                val normalizedNumber = normalizeAnimeEpisodeNumber(number) ?: return@mapNotNull null
                normalizedNumber to AnimeSaturnPlaybackData(
                    label = label,
                    watchUrl = watchUrl(href),
                )
            }
            .distinctBy { it.first }
            .toMap()

        val pageData = AnimeSaturnPageData(
            searchItem = item.copy(isDub = isDub),
            anilistId = doc.selectFirst("a[href*='anilist.co/anime/'][href]")
                ?.attr("href")
                ?.let(::extractAnilistId)
                ?: extractAnilistId(html),
            malId = doc.selectFirst("a[href*='myanimelist.net/anime/'][href]")
                ?.attr("href")
                ?.let(::extractMalId)
                ?: extractMalId(html),
            kitsuId = doc.selectFirst("a[href*='kitsu.io/anime/'][href], a[href*='kitsu.app/anime/'][href]")
                ?.attr("href")
                ?.let(::extractKitsuId)
                ?: extractKitsuId(html),
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

    private fun titleFromHref(href: String): String? {
        val slug = href.substringAfter("/anime/", missingDelimiterValue = "")
            .substringBefore('?')
            .trim('/')
            .takeIf(String::isNotBlank)
            ?: return null
        return slug
            .replace(Regex("""-[A-Za-z0-9]{5}$"""), "")
            .replace('-', ' ')
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun watchUrl(href: String): String {
        val normalizedHref = href.trim().replace(Regex("""^/episode/"""), "/anime/")
        return absoluteProviderUrl(baseUrl(), normalizedHref)
    }

    private fun AnimeSaturnPageData.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId) ||
            (syncIds.kitsuId != null && kitsuId == syncIds.kitsuId)
    }

    private fun List<AnimeSaturnPageData>.mergeEpisodeSources(): Map<String, AnimeSaturnPlaybackData> {
        val merged = linkedMapOf<String, AnimeSaturnPlaybackData>()
        forEach { pageData ->
            pageData.episodeSources.forEach { (number, playback) ->
                if (!merged.containsKey(number)) merged[number] = playback
            }
        }
        return merged
    }

    private fun extractAnilistId(text: String): Int? {
        return Regex("""(?:anilist\.co/anime/|/anilist/)(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractMalId(text: String): Int? {
        return Regex("""(?:myanimelist\.net/anime/|/mal/)(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractKitsuId(text: String): Int? {
        return Regex("""(?:kitsu\.(?:io|app)/anime/|/kitsu/)(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private companion object {
        const val SOURCE_NAME = "AnimeSaturn"
        const val SEARCH_PARALLELISM = 4
    }
}
