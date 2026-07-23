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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

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
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(archiveQueryLimit())
        val exactTitleKeys = exactAnimeTitleKeys(metadata, anilistMetadata)
        val allowTitleFallback = syncIds.size == 1

        return syncIds.mapNotNull { sync ->
            val variants = findVariants(sync, titleCandidates, exactTitleKeys, allowTitleFallback)
            if (variants.isEmpty()) return@mapNotNull null

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
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    suspend fun fetchArchive(
        filters: StreamCenterAnimeArchiveFilters = StreamCenterAnimeArchiveFilters(),
        offset: Int = 0,
        title: String = "",
    ): List<AnimeUnityAnime> {
        ensureHeaders()
        val requestBody = buildArchiveBody(title, offset, filters)
            .toRequestBody("application/json;charset=utf-8".toMediaType())
        val text = app.post(
            "${baseUrl()}/archivio/get-animes",
            headers = requestHeaders,
            requestBody = requestBody,
        ).text
        return parseArchive(text)
    }

    fun resetSession() {
        sharedPref?.edit()?.remove(PREF_SESSION)?.apply()
        applySession(cookie = "", csrfToken = "")
    }

    suspend fun findVariants(
        syncIds: AnimeSyncIds,
        titleCandidates: List<String>,
        exactTitleKeys: Set<String>,
        allowTitleFallback: Boolean,
    ): List<AnimeUnityAnime> {
        val candidates = linkedMapOf<Int, AnimeUnityAnime>()
        for (chunk in titleCandidates.chunked(SEARCH_PARALLELISM)) {
            coroutineScope {
                chunk.map { title ->
                    async(Dispatchers.IO) {
                        runCatching { fetchArchive(title = title) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }.flatten().forEach { anime ->
                if (!candidates.containsKey(anime.id)) candidates[anime.id] = anime
            }
            if (candidates.values.any { it.matches(syncIds) }) break
        }

        val idMatches = candidates.values.filter { it.matches(syncIds) }
        val exactMatches = idMatches.ifEmpty {
            if (!allowTitleFallback) return emptyList()
            candidates.values.filter { anime ->
                anime.anilistId == null && anime.malId == null &&
                    anime.titleKeys().any { it in exactTitleKeys }
            }
        }
        if (exactMatches.isEmpty()) return emptyList()

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
            put("genres", filters.genreId?.let { id -> JSONArray().put(JSONObject().put("id", id)) } ?: false)
            put("season", filters.season ?: false)
            put("dubbed", if (filters.dubbed) 1 else 0)
            put("offset", offset)
        }.toString()
    }

    private suspend fun fetchPage(anime: AnimeUnityAnime): AnimeUnityPageData? {
        val url = "${baseUrl()}/anime/${anime.id}-${anime.slug}"
        val html = app.get(url).text
        val videoPlayer = Jsoup.parse(html, url).selectFirst("video-player") ?: return null
        val pageAnime = videoPlayer.attr("anime")
            .takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it).toAnimeUnityAnime() }.getOrNull() }
            ?: anime
        val initialEpisodes = parseEpisodes(videoPlayer.attr("episodes"))
        val totalEpisodes = videoPlayer.attr("episodes_count").toIntOrNull() ?: initialEpisodes.size
        return AnimeUnityPageData(
            anime = pageAnime,
            episodes = fetchAllEpisodes(pageAnime, initialEpisodes, totalEpisodes),
        )
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
            val text = app.get(url).text
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

    private fun parseArchive(text: String): List<AnimeUnityAnime> {
        return runCatching {
            val records = JSONObject(text).optJSONArray("records") ?: JSONArray()
            buildList {
                for (index in 0 until records.length()) {
                    records.optJSONObject(index)?.toAnimeUnityAnime()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
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
        const val PREF_SESSION = "streamcenter_au_session"
        const val SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        const val SEARCH_PARALLELISM = 4
        const val EPISODES_PER_PAGE = 120
    }
}
