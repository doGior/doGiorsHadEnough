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
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(queryLimit())
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
        val pageData = coroutineScope {
            searchCandidates
                .map { item -> async(Dispatchers.IO) { fetchPage(item) } }
                .awaitAll()
                .filterNotNull()
        }
        val exactTitleKeys = exactAnimeTitleKeys(metadata, anilistMetadata)

        return syncIds.mapNotNull { sync ->
            val matches = pageData.filter { it.matches(sync) }
                .ifEmpty {
                    if (syncIds.size > 1) return@mapNotNull null
                    pageData.filter { page ->
                        page.anilistId == null && page.malId == null && page.kitsuId == null &&
                            (
                                sourceTitleDedupKey(page.searchItem.title) in exactTitleKeys ||
                                    page.searchItem.otherTitle
                                        ?.let { sourceTitleDedupKey(it) in exactTitleKeys } == true
                                )
                    }
                }
            if (matches.isEmpty()) return@mapNotNull null

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
        }
    }

    private suspend fun search(query: String): List<AnimeWorldSearchItem> {
        ensureDomain()
        val providerUrl = baseUrl()
        val url = "$providerUrl/filter?sort=0&keyword=${URLEncoder.encode(query, "UTF-8")}"
        val html = app.get(url, headers = headers).text
        val doc = Jsoup.parse(html, url)

        return doc.select("div.film-list > .item").mapNotNull { item ->
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
    }

    private suspend fun fetchPage(item: AnimeWorldSearchItem): AnimeWorldPageData? {
        val html = app.get(item.url, headers = headers).text
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

        return AnimeWorldPageData(
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
        ).takeIf { it.episodeSources.isNotEmpty() }
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
        const val SEARCH_PARALLELISM = 4
    }
}
