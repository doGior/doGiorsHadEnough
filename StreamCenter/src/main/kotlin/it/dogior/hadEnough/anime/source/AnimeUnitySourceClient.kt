package it.dogior.hadEnough.anime.source

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.StreamCenterAnimeArchiveFilters
import it.dogior.hadEnough.model.AnilistMetadata
import it.dogior.hadEnough.model.AnimeSyncIds
import it.dogior.hadEnough.model.AnimeUnityAnime
import it.dogior.hadEnough.model.AnimeUnityEpisodeInfo
import it.dogior.hadEnough.model.AnimeUnityPageData
import it.dogior.hadEnough.model.AnimeUnityTitleSources
import it.dogior.hadEnough.model.StreamCenterMetadata
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.normalizeAnimeEpisodeNumber
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class AnimeUnitySourceClient(
    private val sharedPref: SharedPreferences?,
    private val baseUrl: () -> String,
    private val archiveQueryLimit: () -> Int,
    private val posterResolver: (String?) -> String?,
    private val ensureDomain: suspend () -> Unit,
) {
    private val requestHeaders = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

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

            val subAnime = variants.firstOrNull { !it.isDub }
            val dubAnime = variants.firstOrNull { it.isDub }
            val (subPage, dubPage) = coroutineScope {
                val subDeferred = subAnime?.let { async(Dispatchers.IO) { fetchPage(it) } }
                val dubDeferred = dubAnime?.let { async(Dispatchers.IO) { fetchPage(it) } }
                subDeferred?.await() to dubDeferred?.await()
            }
            val subSources = buildEpisodeSources(subPage)
            val dubSources = buildEpisodeSources(dubPage)
            val pageAnime = subPage?.anime ?: dubPage?.anime

            AnimeUnityTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
                title = pageAnime?.displayTitle()?.let(::cleanAnimeUnityTitle),
                plot = pageAnime?.plot?.takeIf(String::isNotBlank),
                posterUrl = posterResolver(pageAnime?.imageUrl),
                related = (subPage?.related.orEmpty() + dubPage?.related.orEmpty())
                    .distinctBy(AnimeUnityAnime::id),
                recommendations = (subPage?.recommendations.orEmpty() + dubPage?.recommendations.orEmpty())
                    .distinctBy(AnimeUnityAnime::id),
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
        offset: Int = 0,
        title: String = "",
    ): List<AnimeUnityAnime> = fetchArchivePage(filters, offset, title).records

    suspend fun fetchRandomArchive(targetRecordCount: Int): List<AnimeUnityAnime> {
        val requestedRecords = targetRecordCount.coerceAtLeast(1)
        ensureHeaders()
        val extent = resolveArchiveExtent()
        if (extent.totalRecords <= 0) return emptyList()
        val offsets = randomArchiveOffsets(extent.totalRecords, requestedRecords)
        val requestSemaphore = Semaphore(RANDOM_PAGE_CONCURRENCY)
        val pages = supervisorScope {
            offsets.map { offset ->
                async(Dispatchers.IO) {
                    requestSemaphore.withPermit {
                        try {
                            if (offset == 0 && extent.firstPage != null) {
                                extent.firstPage
                            } else {
                                fetchArchivePage(offset = offset).records
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            AnimeSourceLog.warning(
                                SOURCE_NAME,
                                "Campione casuale archivio non disponibile",
                                mapOf("offset" to offset),
                                error,
                            )
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val records = pages.flatten()
            .distinctBy(AnimeUnityAnime::id)
            .toMutableList()
        Collections.shuffle(records, random)
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Campionamento casuale archivio completato",
            mapOf(
                "elementi_catalogo" to extent.totalRecords,
                "pagine_casuali" to offsets.size,
                "pagine_riuscite" to pages.size,
                "candidati_casuali" to records.size,
            ),
        )
        return records
    }

    private suspend fun fetchArchivePage(
        filters: StreamCenterAnimeArchiveFilters = StreamCenterAnimeArchiveFilters(),
        offset: Int = 0,
        title: String = "",
    ): AnimeUnityArchivePage {
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Richiesta archivio avviata",
            mapOf(
                "offset" to offset,
                "filtro_doppiato" to filters.dubbed,
                "ricerca_titolo_presente" to title.isNotBlank(),
            ),
        )
        ensureHeaders()
        val requestBody = buildArchiveBody(title, offset, filters)
            .toRequestBody("application/json;charset=utf-8".toMediaType())
        val text = try {
            app.post(
                "${baseUrl()}/archivio/get-animes",
                headers = requestHeaders,
                requestBody = requestBody,
            ).text
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Richiesta archivio non riuscita", error = error)
            throw error
        }
        return parseArchivePage(text, offset).also { page ->
            AnimeSourceLog.info(
                SOURCE_NAME,
                "Richiesta archivio completata",
                mapOf(
                    "risultati_archivio" to page.records.size,
                    "elementi_catalogo" to page.totalRecords,
                ),
            )
        }
    }

    private suspend fun resolveArchiveExtent(): AnimeUnityArchiveExtent {
        val cacheKey = baseUrl().trimEnd('/').lowercase()
        archiveSizeCache[cacheKey]
            ?.takeIf { cached -> cached.expiresAt > System.currentTimeMillis() }
            ?.let { cached -> return AnimeUnityArchiveExtent(cached.totalRecords) }

        val firstPage = fetchArchivePage(offset = 0)
        val firstPageSize = firstPage.rawRecordCount
            ?: throw IllegalStateException("Risposta archivio AnimeUnity non valida.")
        val totalRecords = firstPage.totalRecords ?: discoverArchiveSize(firstPageSize)
        archiveSizeCache[cacheKey] = AnimeUnityArchiveSizeCache(
            totalRecords = totalRecords,
            expiresAt = System.currentTimeMillis() + ARCHIVE_SIZE_CACHE_MS,
        )
        return AnimeUnityArchiveExtent(totalRecords, firstPage.records)
    }

    private suspend fun discoverArchiveSize(firstPageSize: Int): Int {
        if (firstPageSize < ARCHIVE_PAGE_SIZE) return firstPageSize
        var lastFullPage = 0
        var firstEmptyPage = 1
        while (firstEmptyPage <= MAX_ARCHIVE_PAGE_INDEX) {
            val offset = firstEmptyPage * ARCHIVE_PAGE_SIZE
            val page = fetchArchivePage(offset = offset)
            page.totalRecords?.let { return it }
            val rawRecordCount = page.rawRecordCount
                ?: throw IllegalStateException("Risposta archivio AnimeUnity non valida.")
            if (rawRecordCount < ARCHIVE_PAGE_SIZE) {
                if (rawRecordCount > 0) return offset + rawRecordCount
                break
            }
            lastFullPage = firstEmptyPage
            firstEmptyPage *= 2
        }
        if (firstEmptyPage > MAX_ARCHIVE_PAGE_INDEX) {
            return (lastFullPage + 1) * ARCHIVE_PAGE_SIZE
        }
        while (lastFullPage + 1 < firstEmptyPage) {
            val pageIndex = lastFullPage + (firstEmptyPage - lastFullPage) / 2
            val offset = pageIndex * ARCHIVE_PAGE_SIZE
            val page = fetchArchivePage(offset = offset)
            page.totalRecords?.let { return it }
            val rawRecordCount = page.rawRecordCount
                ?: throw IllegalStateException("Risposta archivio AnimeUnity non valida.")
            when {
                rawRecordCount == 0 -> firstEmptyPage = pageIndex
                rawRecordCount < ARCHIVE_PAGE_SIZE -> return offset + rawRecordCount
                else -> lastFullPage = pageIndex
            }
        }
        return (lastFullPage + 1) * ARCHIVE_PAGE_SIZE
    }

    private fun randomArchiveOffsets(totalRecords: Int, targetRecordCount: Int): List<Int> {
        val pageCount = ((totalRecords.toLong() + ARCHIVE_PAGE_SIZE - 1L) / ARCHIVE_PAGE_SIZE)
            .toInt()
        val requestedPages = ((targetRecordCount.toLong() + ARCHIVE_PAGE_SIZE - 1L) / ARCHIVE_PAGE_SIZE)
            .coerceAtLeast(MIN_RANDOM_PAGE_SAMPLES.toLong())
            .coerceAtMost(pageCount.toLong())
            .toInt()
        if (requestedPages * 2 >= pageCount) {
            val pageIndexes = (0 until pageCount).toMutableList()
            Collections.shuffle(pageIndexes, random)
            return pageIndexes.take(requestedPages)
                .map { pageIndex -> pageIndex * ARCHIVE_PAGE_SIZE }
        }
        val pageIndexes = linkedSetOf<Int>()
        while (pageIndexes.size < requestedPages) {
            pageIndexes += random.nextInt(pageCount)
        }
        return pageIndexes.map { pageIndex -> pageIndex * ARCHIVE_PAGE_SIZE }
    }

    fun resetSession() {
        sharedPref?.edit()?.remove(PREF_SESSION)?.apply()
        applySession(cookie = "", csrfToken = "")
        AnimeSourceLog.info(SOURCE_NAME, "Sessione sorgente reimpostata")
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
            coroutineScope {
                chunk.map { title ->
                    async(Dispatchers.IO) {
                        runCatching { fetchArchive(title = title) }
                            .onFailure {
                                AnimeSourceLog.warning(
                                    SOURCE_NAME,
                                    "Tentativo ricerca archivio non riuscito",
                                    error = it,
                                )
                            }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll()
            }.flatten().forEach { anime ->
                if (!candidates.containsKey(anime.id)) candidates[anime.id] = anime
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
            .sortedWith(compareBy<AnimeUnityAnime> { if (it.isDub) 1 else 0 }.thenBy { it.id })
    }

    private fun buildArchiveBody(
        title: String,
        offset: Int,
        filters: StreamCenterAnimeArchiveFilters,
    ): String {
        return JSONObject().apply {
            put("title", title)
            put("type", filters.type ?: false)
            put("year", filters.year ?: false)
            put("order", filters.order ?: false)
            put("status", filters.status ?: false)
            val genreIds = filters.selectedGenreIds
            put(
                "genres",
                if (genreIds.isEmpty()) false else JSONArray().apply {
                    genreIds.forEach { id -> put(JSONObject().put("id", id)) }
                },
            )
            put("season", filters.season ?: false)
            put("dubbed", if (filters.dubbed) 1 else 0)
            put("offset", offset)
        }.toString()
    }

    private suspend fun fetchPage(anime: AnimeUnityAnime): AnimeUnityPageData? {
        AnimeSourceLog.info(
            SOURCE_NAME,
            "Dettaglio variante richiesto",
            mapOf("identificativo_variante" to anime.id),
        )
        val url = "${baseUrl()}/anime/${anime.id}-${anime.slug}"
        val html = try {
            app.get(url).text
        } catch (error: Throwable) {
            AnimeSourceLog.warning(SOURCE_NAME, "Dettaglio variante non riuscito", error = error)
            throw error
        }
        val document = Jsoup.parse(html, url)
        val videoPlayer = document.selectFirst("video-player") ?: run {
            AnimeSourceLog.warning(SOURCE_NAME, "Dettaglio variante senza player")
            return null
        }
        val pageAnimeJson = videoPlayer.attr("anime")
            .takeIf(String::isNotBlank)
            ?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
        val pageAnime = pageAnimeJson?.toAnimeUnityAnime() ?: anime
        val initialEpisodes = parseEpisodes(videoPlayer.attr("episodes"))
        val totalEpisodes = videoPlayer.attr("episodes_count").toIntOrNull() ?: initialEpisodes.size
        return AnimeUnityPageData(
            anime = pageAnime,
            episodes = fetchAllEpisodes(pageAnime, initialEpisodes, totalEpisodes),
            related = parseRelated(
                pageAnimeJson?.optJSONArray("related")?.toString()
                    ?: videoPlayer.attr("related"),
            ),
            recommendations = parseRecommendations(document),
        ).also { pageData ->
            AnimeSourceLog.info(
                SOURCE_NAME,
                "Dettaglio variante completato",
                mapOf(
                    "episodi_rilevati" to pageData.episodes.size,
                    "episodi_dichiarati" to totalEpisodes,
                    "anime_correlati" to pageData.related.size,
                    "raccomandazioni" to pageData.recommendations.size,
                ),
            )
        }
    }

    private suspend fun fetchAllEpisodes(
        anime: AnimeUnityAnime,
        initialEpisodes: List<AnimeUnityEpisodeInfo>,
        totalEpisodes: Int,
    ): List<AnimeUnityEpisodeInfo> {
        if (totalEpisodes <= EPISODES_PER_PAGE) return initialEpisodes

        val episodes = initialEpisodes.toMutableList()
        val pageCount = (totalEpisodes + EPISODES_PER_PAGE - 1) / EPISODES_PER_PAGE
        for (page in 2..pageCount) {
            val startRange = 1 + (page - 1) * EPISODES_PER_PAGE
            val endRange = if (page == pageCount) totalEpisodes else page * EPISODES_PER_PAGE
            val url = "${baseUrl()}/info_api/${anime.id}/1?start_range=$startRange&end_range=$endRange"
            val text = try {
                app.get(url).text
            } catch (error: Throwable) {
                AnimeSourceLog.warning(
                    SOURCE_NAME,
                    "Recupero pagina episodi non riuscito",
                    mapOf("pagina" to page),
                    error,
                )
                throw error
            }
            episodes += parseEpisodes(
                JSONObject(text).optJSONArray("episodes")?.toString().orEmpty()
            )
        }
        return episodes.distinctBy { it.id }
    }

    private fun parseEpisodes(text: String): List<AnimeUnityEpisodeInfo> {
        return runCatching {
            val episodes = JSONArray(text)
            buildList {
                for (index in 0 until episodes.length()) {
                    val episode = episodes.optJSONObject(index) ?: continue
                    val id = episode.optNullableInt("id") ?: continue
                    val number = episode.optString("number").takeIf(String::isNotBlank) ?: continue
                    add(AnimeUnityEpisodeInfo(id = id, number = number))
                }
            }
        }.onFailure {
            AnimeSourceLog.warning(SOURCE_NAME, "Parsing episodi non riuscito", error = it)
        }.getOrDefault(emptyList())
    }

    private fun parseRecommendations(document: org.jsoup.nodes.Document): List<AnimeUnityAnime> {
        val json = document.selectFirst("div.recommended layout-items[items-json]")
            ?.attr("items-json")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        return parseAnimeList(json, "raccomandazioni")
    }

    private fun parseRelated(json: String): List<AnimeUnityAnime> {
        return parseAnimeList(json.trim(), "anime correlati")
    }

    private fun parseAnimeList(json: String, section: String): List<AnimeUnityAnime> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val items = JSONArray(json)
            buildList {
                for (index in 0 until items.length()) {
                    items.optJSONObject(index)?.toAnimeUnityAnime()?.let(::add)
                }
            }.distinctBy(AnimeUnityAnime::id)
        }.onFailure { error ->
            AnimeSourceLog.warning(SOURCE_NAME, "Parsing $section non riuscito", error = error)
        }.getOrDefault(emptyList())
    }

    private fun buildEpisodeSources(pageData: AnimeUnityPageData?): Map<String, String> {
        pageData ?: return emptyMap()
        val pageBaseUrl = "${baseUrl()}/anime/${pageData.anime.id}-${pageData.anime.slug}"
        return pageData.episodes.mapNotNull { episode ->
            val number = normalizeAnimeEpisodeNumber(episode.number) ?: return@mapNotNull null
            number to "$pageBaseUrl/${episode.id}"
        }.toMap()
    }

    private fun parseArchivePage(text: String, offset: Int): AnimeUnityArchivePage {
        return runCatching {
            val root = JSONObject(text)
            val recordsJson = root.optJSONArray("records")
                ?: throw IllegalArgumentException("Archivio AnimeUnity privo di record.")
            val records = buildList {
                for (index in 0 until recordsJson.length()) {
                    recordsJson.optJSONObject(index)?.toAnimeUnityAnime()?.let(::add)
                }
            }
            val rawRecordCount = recordsJson.length()
            val reportedTotal = archiveTotalContainers(root)
                .firstNotNullOfOrNull { container ->
                    container.archiveTotal()?.takeIf { total -> total >= offset + rawRecordCount }
                }
            AnimeUnityArchivePage(
                records = records,
                totalRecords = reportedTotal
                    ?: (offset + rawRecordCount).takeIf { rawRecordCount < ARCHIVE_PAGE_SIZE },
                rawRecordCount = rawRecordCount,
            )
        }.onFailure {
            AnimeSourceLog.warning(SOURCE_NAME, "Parsing archivio non riuscito", error = it)
        }.getOrDefault(AnimeUnityArchivePage(emptyList(), null, null))
    }

    private fun archiveTotalContainers(root: JSONObject): List<JSONObject> = listOfNotNull(
        root,
        root.optJSONObject("pagination"),
        root.optJSONObject("meta"),
    )

    private fun JSONObject.archiveTotal(): Int? {
        return ARCHIVE_TOTAL_KEYS.firstNotNullOfOrNull { key ->
            when (val value = opt(key)) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }?.takeIf { it >= 0 }
        }
    }

    private fun JSONObject.toAnimeUnityAnime(): AnimeUnityAnime? {
        val id = optNullableInt("id") ?: return null
        val slug = optString("slug").takeIf(String::isNotBlank) ?: return null
        return AnimeUnityAnime(
            id = id,
            slug = slug,
            title = optNullableString("title"),
            titleEng = optNullableString("title_eng"),
            titleIt = optNullableString("title_it"),
            dub = optNullableInt("dub") ?: 0,
            anilistId = optNullableInt("anilist_id"),
            malId = optNullableInt("mal_id"),
            episodesCount = optNullableInt("episodes_count"),
            realEpisodesCount = optNullableInt("real_episodes_count"),
            plot = cleanText(optNullableString("plot")),
            imageUrl = optNullableString("imageurl"),
            score = optNullableString("score"),
            type = optNullableString("type"),
            year = optNullableString("date")?.trim()?.take(4)?.toIntOrNull(),
        )
    }

    private suspend fun ensureHeaders(forceRefresh: Boolean = false) {
        ensureDomain()
        if (hasSessionHeaders() && !forceRefresh) return
        if (!forceRefresh && restoreSession()) return

        requestHeaders["Host"] = hostOf(baseUrl())
        val response = app.get("${baseUrl()}/archivio", headers = requestHeaders)
        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies = listOfNotNull(
            response.cookies["XSRF-TOKEN"]?.let { "XSRF-TOKEN=$it" },
            response.cookies["animeunity_session"]?.let { "animeunity_session=$it" },
        ).joinToString("; ")

        applySession(cookies, csrfToken)
        persistSession()
    }

    private fun hasSessionHeaders(): Boolean {
        return requestHeaders["Cookie"].orEmpty().isNotBlank() &&
            requestHeaders["X-CSRF-Token"].orEmpty().isNotBlank()
    }

    private fun applySession(cookie: String, csrfToken: String) {
        requestHeaders.putAll(
            mapOf(
                "Host" to hostOf(baseUrl()),
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json;charset=utf-8",
                "X-CSRF-Token" to csrfToken,
                "Referer" to baseUrl(),
                "Cookie" to cookie,
            )
        )
    }

    private fun restoreSession(): Boolean {
        val json = readSessionPayload() ?: return false
        val cookie = json.optString("cookie")
        val csrfToken = json.optString("csrfToken")
        if (cookie.isBlank() || csrfToken.isBlank()) return false
        applySession(cookie, csrfToken)
        return true
    }

    private fun persistSession() {
        if (!hasSessionHeaders()) return
        val payload = JSONObject()
            .put("cookie", requestHeaders["Cookie"].orEmpty())
            .put("csrfToken", requestHeaders["X-CSRF-Token"].orEmpty())
            .put("expiresAt", System.currentTimeMillis() + SESSION_TTL_MS)
        sharedPref?.edit()?.putString(PREF_SESSION, payload.toString())?.apply()
    }

    private fun readSessionPayload(): JSONObject? {
        val raw = sharedPref?.getString(PREF_SESSION, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val expiresAt = json.optLong("expiresAt", 0L)
            if (expiresAt in 1..System.currentTimeMillis()) {
                sharedPref?.edit()?.remove(PREF_SESSION)?.apply()
                return null
            }
            json
        }.getOrNull()
    }

    private fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore('/').substringBefore(':')
    }

    private companion object {
        const val SOURCE_NAME = "AnimeUnity"
        const val PREF_SESSION = "streamcenter_au_session"
        const val SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        const val ARCHIVE_SIZE_CACHE_MS = 6L * 60L * 60L * 1000L
        const val ARCHIVE_PAGE_SIZE = 30
        const val MIN_RANDOM_PAGE_SAMPLES = 6
        const val RANDOM_PAGE_CONCURRENCY = 3
        const val MAX_ARCHIVE_PAGE_INDEX = 16_384
        const val SEARCH_PARALLELISM = 4
        const val EPISODES_PER_PAGE = 120
        val ARCHIVE_TOTAL_KEYS = listOf(
            "tot",
            "total",
            "recordsTotal",
            "records_total",
            "totalRecords",
            "total_records",
        )
        val archiveSizeCache = ConcurrentHashMap<String, AnimeUnityArchiveSizeCache>()
        val random = SecureRandom()
    }
}

private data class AnimeUnityArchivePage(
    val records: List<AnimeUnityAnime>,
    val totalRecords: Int?,
    val rawRecordCount: Int?,
)

private data class AnimeUnityArchiveExtent(
    val totalRecords: Int,
    val firstPage: List<AnimeUnityAnime>? = null,
)

private data class AnimeUnityArchiveSizeCache(
    val totalRecords: Int,
    val expiresAt: Long,
)
