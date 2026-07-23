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
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(queryLimit())
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
                            sourceTitleDedupKey(page.searchItem.title) in exactTitleKeys
                    }
                }
            if (matches.isEmpty()) return@mapNotNull null

            val subSources = matches.filter { !it.searchItem.isDub }.mergeEpisodeSources()
            val dubSources = matches.filter { it.searchItem.isDub }.mergeEpisodeSources()

            AnimeSaturnTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun search(query: String): List<AnimeSaturnSearchItem> {
        ensureDomain()
        val providerUrl = baseUrl()
        val url = "$providerUrl/filter?key=${URLEncoder.encode(query, "UTF-8")}"
        val doc = Jsoup.parse(app.get(url, headers = headers).text, url)

        return doc.select(
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
    }

    private suspend fun fetchPage(item: AnimeSaturnSearchItem): AnimeSaturnPageData? {
        val html = app.get(item.url, headers = headers).text
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

        return AnimeSaturnPageData(
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
        ).takeIf { it.episodeSources.isNotEmpty() }
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
        const val SEARCH_PARALLELISM = 4
    }
}
