package it.dogior.hadEnough.anime.source

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.StreamCenterAnimeArchiveFilters
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.model.AnilistMetadata
import it.dogior.hadEnough.model.AnimeSyncIds
import it.dogior.hadEnough.model.AnimeUnityAnime
import it.dogior.hadEnough.model.AnimeUnityEpisodeInfo
import it.dogior.hadEnough.model.AnimeUnityPageData
import it.dogior.hadEnough.model.AnimeUnityTitleSources
import it.dogior.hadEnough.model.StreamCenterMetadata
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.normalizeAnimeEpisodeNumber
import it.dogior.hadEnough.util.objects
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class AnimeUnitySourceClient(
    private val sharedPref: SharedPreferences?,
    private val baseUrl: () -> String,
    private val archiveQueryLimit: () -> Int,
    private val posterResolver: (String?) -> String?,
    private val ensureDomain: suspend () -> Unit,
) {
    suspend fun fetchSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeUnityTitleSources> {
        if (syncIds.isEmpty()) {
            AnimeSourceLog.warning(SOURCE_NAME, "Ricerca sorgenti ignorata: identificativi assenti")
            return emptyList()
        }

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(archiveQueryLimit())
        val exactTitleKeys = exactAnimeTitleKeys(metadata, anilistMetadata)
        val allowTitleFallback = syncIds.size == 1
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Ricerca sorgenti avviata",
            mapOf(
                "titoli_candidati" to titleCandidates.size,
                "identificativi_sincronizzazione" to syncIds.size,
                "fallback_titolo_consentito" to allowTitleFallback,
            ),
        )

        val resolvedSources = syncIds.mapNotNull { sync ->
            val variants = findVariants(sync, titleCandidates, exactTitleKeys, allowTitleFallback)
            if (variants.isEmpty()) {
                AnimeSourceLog.warning(SOURCE_NAME, "Nessuna variante corrispondente")
                return@mapNotNull null
            }

            val pages = coroutineScope {
                recordsCoveringAudio(variants).map { anime ->
                    async(Dispatchers.IO) { fetchPage(anime) }
                }.awaitAll().filterNotNull()
            }
            val subSources = mergeEpisodeSources(pages, dub = false)
            val dubSources = mergeEpisodeSources(pages, dub = true)
            val pageAnime = pages.firstOrNull()?.anime

            AnimeUnityTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
                title = pageAnime?.displayTitle()?.let(::cleanAnimeUnityTitle),
                plot = pageAnime?.plot?.takeIf(String::isNotBlank),
                posterUrl = posterResolver(pageAnime?.imageUrl),
                related = pages.flatMap(AnimeUnityPageData::related).distinctBy(AnimeUnityAnime::id),
                recommendations = pages.flatMap(AnimeUnityPageData::recommendations).distinctBy(AnimeUnityAnime::id),
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
                ?.also {
                    AnimeSourceLog.info(
                        SOURCE_NAME,
                        "Sorgenti episodio disponibili",
                        mapOf(
                            "varianti_trovate" to variants.size,
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

    suspend fun fetchArchive(
        filters: StreamCenterAnimeArchiveFilters = StreamCenterAnimeArchiveFilters(),
        page: Int = 1,
        title: String = "",
    ): List<AnimeUnityAnime> = fetchArchivePage(filters, page, title).records

    suspend fun fetchRandomArchive(targetRecordCount: Int): List<AnimeUnityAnime> {
        val requestedPages = ((targetRecordCount.coerceAtLeast(1) + ARCHIVE_PAGE_SIZE - 1) / ARCHIVE_PAGE_SIZE)
            .coerceAtLeast(MIN_RANDOM_PAGE_SAMPLES)
        val (lastPage, firstPage) = resolveArchiveExtent()
        if (lastPage <= 0) return emptyList()
        val pageNumbers = (1..lastPage).shuffled(random).take(requestedPages)
        val pages = pageNumbers.mapChunkedParallel(RANDOM_PAGE_CONCURRENCY) { pageNumber ->
            if (pageNumber == 1 && firstPage != null) firstPage else fetchArchive(page = pageNumber)
        }
        val records = pages.flatten()
            .distinctBy(AnimeUnityAnime::id)
            .toMutableList()
        Collections.shuffle(records, random)
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Campionamento casuale archivio completato",
            mapOf(
                "pagine_catalogo" to lastPage,
                "pagine_casuali" to pageNumbers.size,
                "pagine_riuscite" to pages.size,
                "candidati_casuali" to records.size,
            ),
        )
        return records
    }

    suspend fun fetchArchivePage(
        filters: StreamCenterAnimeArchiveFilters = StreamCenterAnimeArchiveFilters(),
        page: Int = 1,
        title: String = "",
    ): AnimeUnityArchivePage {
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Richiesta archivio avviata",
            mapOf(
                "pagina" to page,
                "filtro_doppiato" to filters.dubbed,
                "ricerca_titolo_presente" to title.isNotBlank(),
            ),
        )
        val text = try {
            getJson("/api/archive?${AnimeUnityArchiveCatalog.query(filters, title, page)}")
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Richiesta archivio non riuscita", error = error)
            throw error
        }
        val root = JSONObject(text)
        val data = root.optJSONArray("data") ?: error("Archivio AnimeUnity privo di dati.")
        return AnimeUnityArchivePage(
            records = data.toAnimeUnityAnimeList(),
            lastPage = root.optNullableInt("last_page") ?: page,
        ).also { archivePage ->
            AnimeSourceLog.info(
                SOURCE_NAME,
                "Richiesta archivio completata",
                mapOf(
                    "risultati_archivio" to archivePage.records.size,
                    "pagine_catalogo" to archivePage.lastPage,
                ),
            )
        }
    }

    private suspend fun resolveArchiveExtent(): Pair<Int, List<AnimeUnityAnime>?> {
        val cacheKey = baseUrl().trimEnd('/').lowercase(Locale.ROOT)
        archiveSizeCache[cacheKey]
            ?.takeIf { cached -> cached.expiresAt > System.currentTimeMillis() }
            ?.let { cached -> return cached.lastPage to null }
        val firstPage = fetchArchivePage()
        archiveSizeCache[cacheKey] = AnimeUnityArchiveSizeCache(
            lastPage = firstPage.lastPage,
            expiresAt = System.currentTimeMillis() + ARCHIVE_SIZE_CACHE_MS,
        )
        return firstPage.lastPage to firstPage.records
    }

    suspend fun findVariants(
        syncIds: AnimeSyncIds,
        titleCandidates: List<String>,
        exactTitleKeys: Set<String>,
        allowTitleFallback: Boolean,
    ): List<AnimeUnityAnime> {
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Ricerca varianti avviata",
            mapOf(
                "titoli_candidati" to titleCandidates.size,
                "fallback_titolo_consentito" to allowTitleFallback,
            ),
        )
        val candidates = linkedMapOf<Int, AnimeUnityAnime>()
        for ((batchIndex, chunk) in titleCandidates.chunked(SEARCH_PARALLELISM).withIndex()) {
            AnimeSourceLog.info(
                SOURCE_NAME,
                "Tentativo ricerca archivio",
                mapOf(
                    "lotto" to (batchIndex + 1),
                    "titoli_nel_lotto" to chunk.size,
                ),
            )
            searchTitles(chunk, "Tentativo ricerca archivio non riuscito").forEach { anime ->
                candidates.putIfAbsent(anime.id, anime)
            }
            AnimeSourceLog.info(
                SOURCE_NAME,
                "Tentativo ricerca archivio completato",
                mapOf(
                    "lotto" to (batchIndex + 1),
                    "candidati_univoci" to candidates.size,
                ),
            )
            if (candidates.values.any { it.matches(syncIds) }) break
        }

        if (
            candidates.values.none { it.matches(syncIds) } &&
            (syncIds.anilistId != null || syncIds.malId != null) &&
            !StreamCenterPlugin.isPerformanceModeEnabled(sharedPref)
        ) {
            searchBroadenedVariants(titleCandidates, syncIds, candidates)
        }

        val idMatches = candidates.values.filter { it.matches(syncIds) }
        val exactMatches = idMatches.ifEmpty {
            if (!allowTitleFallback) {
                AnimeSourceLog.warning(
                    SOURCE_NAME,
                    "Fallback titolo non applicato: piu identificativi disponibili",
                )
                return emptyList()
            }
            candidates.values.filter { anime ->
                anime.anilistId == null && anime.malId == null &&
                    anime.titleKeys().any { it in exactTitleKeys }
            }
        }
        if (exactMatches.isEmpty()) {
            AnimeSourceLog.warning(SOURCE_NAME, "Nessuna variante verificata")
            return emptyList()
        }
        if (idMatches.isEmpty()) {
            AnimeSourceLog.warning(
                SOURCE_NAME,
                "Fallback titolo applicato: identificativi mancanti nella sorgente",
                mapOf("corrispondenze" to exactMatches.size),
            )
        } else {
            AnimeSourceLog.info(
                SOURCE_NAME,
                "Corrispondenza tramite identificativi",
                mapOf("corrispondenze" to idMatches.size),
            )
        }

        val matchedContentKeys = exactMatches.map(AnimeUnityAnime::contentKey).toSet()
        return candidates.values
            .filter { it.contentKey() in matchedContentKeys || it.matches(syncIds) }
            .distinctBy(AnimeUnityAnime::id)
            .sortedBy(AnimeUnityAnime::id)
    }

    private suspend fun searchTitles(titles: List<String>, failureMessage: String): List<AnimeUnityAnime> =
        coroutineScope {
            titles.map { title ->
                async(Dispatchers.IO) {
                    runCatchingCancellable { fetchArchive(title = title) }
                        .onFailure { AnimeSourceLog.warning(SOURCE_NAME, failureMessage, error = it) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }

    private suspend fun searchBroadenedVariants(
        titleCandidates: List<String>,
        syncIds: AnimeSyncIds,
        candidates: MutableMap<Int, AnimeUnityAnime>,
    ) {
        val broadenedQueries = broadenedArchiveQueries(titleCandidates)
        if (broadenedQueries.isEmpty()) return
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Ricerca allargata avviata",
            mapOf("query_allargate" to broadenedQueries.size),
        )
        for (chunk in broadenedQueries.chunked(SEARCH_PARALLELISM)) {
            searchTitles(chunk, "Ricerca allargata non riuscita").forEach { anime ->
                candidates.putIfAbsent(anime.id, anime)
            }
            if (candidates.values.any { it.matches(syncIds) }) break
        }
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Ricerca allargata completata",
            mapOf("candidati_univoci" to candidates.size),
        )
    }

    private fun broadenedArchiveQueries(titleCandidates: List<String>): List<String> {
        val existing = titleCandidates.map { it.trim().lowercase() }.toSet()
        return titleCandidates
            .asSequence()
            .mapNotNull(::broadenedQuery)
            .filterNot { it.trim().lowercase() in existing }
            .distinctBy { it.lowercase() }
            .take(MAX_BROADENED_QUERIES)
            .toList()
    }

    private fun broadenedQuery(title: String): String? {
        val words = title.trim().split(WHITESPACE_REGEX).filter(String::isNotBlank)
        if (words.size <= BROADENED_QUERY_WORD_COUNT) return null
        return words.take(BROADENED_QUERY_WORD_COUNT).joinToString(" ").takeIf { it.length >= 3 }
    }

    private fun recordsCoveringAudio(variants: List<AnimeUnityAnime>): List<AnimeUnityAnime> {
        val selected = mutableListOf<AnimeUnityAnime>()
        variants.firstOrNull { it.hasSub }?.let(selected::add)
        if (selected.none { it.hasDub }) variants.firstOrNull { it.hasDub }?.let(selected::add)
        return selected.ifEmpty { variants.take(1) }
    }

    private suspend fun fetchPage(anime: AnimeUnityAnime): AnimeUnityPageData? {
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Dettaglio variante richiesto",
            mapOf("identificativo_variante" to anime.id),
        )
        val url = "${baseUrl()}/anime/${anime.id}-${anime.slug}"
        val props = try {
            ensureDomain()
            animeUnityPageProps(app.get(url, headers = requestHeaders, timeout = REQUEST_TIMEOUT_S).text, url)
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Dettaglio variante non riuscito", error = error)
            throw error
        } ?: run {
            AnimeSourceLog.warning(SOURCE_NAME, "Dettaglio variante senza dati")
            return null
        }
        val pageAnime = props.optJSONObject("anime")?.toAnimeUnityAnime() ?: anime
        val loaded = props.optJSONObject("loadedEpisodes")
        val initialEpisodes = parseEpisodes(loaded?.optJSONArray("episodes"))
        return AnimeUnityPageData(
            anime = pageAnime,
            episodes = fetchAllEpisodes(pageAnime, loaded, initialEpisodes),
            related = props.optJSONArray("relatedAnimes").toAnimeUnityAnimeList(),
            recommendations = props.optJSONArray("recommendedAnimes").toAnimeUnityAnimeList(),
        ).also { pageData ->
            AnimeSourceLog.info(
                SOURCE_NAME,
                "Dettaglio variante completato",
                mapOf(
                    "episodi_rilevati" to pageData.episodes.size,
                    "episodi_dichiarati" to (loaded?.optNullableInt("total") ?: initialEpisodes.size),
                    "anime_correlati" to pageData.related.size,
                    "raccomandazioni" to pageData.recommendations.size,
                ),
            )
        }
    }

    private suspend fun fetchAllEpisodes(
        anime: AnimeUnityAnime,
        loaded: JSONObject?,
        initialEpisodes: List<AnimeUnityEpisodeInfo>,
    ): List<AnimeUnityEpisodeInfo> {
        val total = loaded?.optNullableInt("total") ?: return initialEpisodes
        val tabSize = loaded.optNullableInt("tab_size") ?: return initialEpisodes
        val loadedStart = loaded.optNullableInt("start") ?: 1
        val missingStarts = (1..total step tabSize).filter { it != loadedStart }
        if (missingStarts.isEmpty()) return initialEpisodes
        val tabs = missingStarts.mapChunkedParallel(EPISODE_TAB_CONCURRENCY) { start ->
            fetchEpisodeTab(anime.id, start) ?: fetchEpisodeTab(anime.id, start)
        }
        check(tabs.size == missingStarts.size) { "AnimeUnity: elenco episodi incompleto" }
        return (initialEpisodes + tabs.flatten()).distinctBy(AnimeUnityEpisodeInfo::number)
    }

    private suspend fun fetchEpisodeTab(animeId: Int, start: Int): List<AnimeUnityEpisodeInfo>? =
        runCatchingCancellable {
            val episodes = JSONObject(getJson("/api/anime/$animeId/episodes?start=$start")).optJSONArray("episodes")
            parseEpisodes(episodes ?: error("AnimeUnity: blocco episodi privo di dati"))
        }.onFailure { error ->
            AnimeSourceLog.warning(
                SOURCE_NAME,
                "Recupero pagina episodi non riuscito",
                mapOf("inizio" to start),
                error,
            )
        }.getOrNull()

    private fun parseEpisodes(episodes: JSONArray?): List<AnimeUnityEpisodeInfo> {
        return episodes.objects().mapNotNull { episode ->
            val number = episode.optNullableString("number") ?: return@mapNotNull null
            val videoIds = buildMap {
                episode.optJSONArray("videos").objects()
                    .filterNot { video -> video.optBoolean("is_hidden", false) }
                    .forEach { video ->
                        val language = video.optNullableString("language")?.lowercase(Locale.ROOT) ?: return@forEach
                        val id = video.optNullableInt("id") ?: return@forEach
                        putIfAbsent(language, id)
                    }
            }
            AnimeUnityEpisodeInfo(number = number, videoIds = videoIds).takeIf { videoIds.isNotEmpty() }
        }
    }

    private fun mergeEpisodeSources(pages: List<AnimeUnityPageData>, dub: Boolean): Map<String, String> {
        val sources = linkedMapOf<String, String>()
        pages.forEach { pageData ->
            val pageBaseUrl = "${baseUrl()}/anime/${pageData.anime.id}-${pageData.anime.slug}"
            pageData.episodes.forEach { episode ->
                val number = normalizeAnimeEpisodeNumber(episode.number) ?: return@forEach
                val videoId = episode.videoId(dub) ?: return@forEach
                sources.putIfAbsent(number, "$pageBaseUrl/$videoId")
            }
        }
        return sources
    }

    private fun AnimeUnityEpisodeInfo.videoId(dub: Boolean): Int? {
        if (dub) return videoIds[ANIME_UNITY_DUB_LANGUAGE]
        return videoIds[SUB_PRIMARY_LANGUAGE]
            ?: videoIds.entries.firstOrNull { it.key != ANIME_UNITY_DUB_LANGUAGE }?.value
    }

    private suspend fun getJson(path: String): String {
        ensureDomain()
        val response = app.get("${baseUrl()}$path", headers = jsonHeaders, timeout = REQUEST_TIMEOUT_S)
        check(response.code in 200..299) { "AnimeUnity HTTP ${response.code}" }
        return response.text
    }

    private companion object {
        const val SOURCE_NAME = "AnimeUnity"
        const val SUB_PRIMARY_LANGUAGE = "jpn"
        const val REQUEST_TIMEOUT_S = 8L
        const val ARCHIVE_SIZE_CACHE_MS = 6L * 60L * 60L * 1000L
        const val ARCHIVE_PAGE_SIZE = 30
        const val MIN_RANDOM_PAGE_SAMPLES = 3
        const val RANDOM_PAGE_CONCURRENCY = 3
        const val EPISODE_TAB_CONCURRENCY = 4
        const val SEARCH_PARALLELISM = 4
        const val BROADENED_QUERY_WORD_COUNT = 4
        const val MAX_BROADENED_QUERIES = 6
        val WHITESPACE_REGEX = Regex("""\s+""")
        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        )
        val jsonHeaders = requestHeaders + mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val archiveSizeCache = ConcurrentHashMap<String, AnimeUnityArchiveSizeCache>()
        val random = SecureRandom()
    }
}

internal data class AnimeUnityArchivePage(
    val records: List<AnimeUnityAnime>,
    val lastPage: Int,
)

private data class AnimeUnityArchiveSizeCache(
    val lastPage: Int,
    val expiresAt: Long,
)
