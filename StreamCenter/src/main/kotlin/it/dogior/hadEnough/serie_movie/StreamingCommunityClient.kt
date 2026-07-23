package it.dogior.hadEnough.serie_movie

import android.content.SharedPreferences
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.model.StreamCenterMetadata
import it.dogior.hadEnough.model.StreamingCommunityEpisode
import it.dogior.hadEnough.model.StreamingCommunityPlaybackData
import it.dogior.hadEnough.model.StreamingCommunitySeason
import it.dogior.hadEnough.model.StreamingCommunityTitle
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal class StreamingCommunityClient(
    private val sharedPref: SharedPreferences?,
    private val rootUrl: () -> String,
    private val mainUrl: () -> String,
    private val defaultHeaders: Map<String, String>,
    private val ensureDomain: suspend () -> Unit,
) {
    private var inertiaVersion = ""
    private var xsrfToken = ""
    private var lastForcedRefreshMs = 0L
    private val sessionHeaders = mutableMapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to "",
        "X-Requested-With" to "XMLHttpRequest",
    )

    suspend fun fetchPageProps(pageUrl: String): JSONObject? {
        val text = app.get(pageUrl, headers = defaultHeaders).body.string()
        val json = extractPageJson(text)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        return json.optJSONObject("props") ?: json
    }

    suspend fun fetchSearchPage(query: String, page: Int): JSONObject? {
        ensureDomain()
        val text = app.get(
            "${mainUrl()}/search",
            params = mapOf("q" to query, "page" to page.toString()),
            headers = defaultHeaders,
        ).body.string()
        val json = extractPageJson(text)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        return json.optJSONObject("props") ?: json
    }

    fun cdnUrl(props: JSONObject): String {
        return props.optNullableString("cdn_url")?.trimEnd('/')
            ?: "https://cdn.${hostOf(rootUrl())}"
    }

    suspend fun findTitle(
        metadata: StreamCenterMetadata,
        isTvSeries: Boolean,
    ): StreamingCommunityTitle? {
        val expectedType = if (isTvSeries) "tv" else "movie"
        val expectedTmdbId = metadata.tmdbId?.toIntOrNull()
        val titleCandidates = listOfNotNull(metadata.title, metadata.originalTitle)
            .map { it.replace(Regex("""\(\d{4}\)"""), "").trim() }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
        val searchCandidates = titleCandidates
            .flatMap { query -> search(query) }
            .filter { it.type == expectedType }
            .distinctBy { it.id }

        var fallback: StreamingCommunityTitle? = null
        for (candidate in searchCandidates.take(DETAIL_CANDIDATE_LIMIT)) {
            val detail = fetchTitleDetail(candidate) ?: continue
            if (expectedTmdbId != null && detail.tmdbId == expectedTmdbId) return detail
            if (fallback == null && detail.matchesFallback(metadata, expectedType)) fallback = detail
        }
        return if (expectedTmdbId == null) fallback else null
    }

    suspend fun search(query: String): List<StreamingCommunityTitle> {
        ensureDomain()
        val text = app.get(
            "${mainUrl()}/search",
            params = mapOf("q" to query),
        ).body.string()
        return parseSearchResults(text)
    }

    suspend fun episodePayloads(
        title: StreamingCommunityTitle,
    ): Map<Pair<Int, Int>, StreamingCommunityPlaybackData> {
        if (title.type != "tv") return emptyMap()
        val episodes = linkedMapOf<Pair<Int, Int>, StreamingCommunityPlaybackData>()
        for (season in title.seasons) {
            val seasonWithEpisodes = if (season.episodes.isNotEmpty()) {
                season
            } else {
                fetchSeason(title, season.number) ?: season
            }
            seasonWithEpisodes.episodes.forEach { episode ->
                episodes[seasonWithEpisodes.number to episode.number] = StreamingCommunityPlaybackData(
                    iframeUrl = "${mainUrl()}/iframe/${title.id}?episode_id=${episode.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = seasonWithEpisodes.number,
                    episodeNumber = episode.number,
                )
            }
        }
        return episodes
    }

    fun moviePlayback(title: StreamingCommunityTitle): StreamingCommunityPlaybackData? {
        if (title.type != "movie") return null
        return StreamingCommunityPlaybackData(
            iframeUrl = "${mainUrl()}/iframe/${title.id}&canPlayFHD=1",
            type = title.type,
            tmdbId = title.tmdbId,
        )
    }

    fun imageUrl(filename: String?): String? {
        val file = filename?.takeIf(String::isNotBlank) ?: return null
        return "https://cdn.${hostOf(rootUrl())}/images/$file"
    }

    fun showStatus(status: String?): ShowStatus? = when (status?.trim()?.lowercase(Locale.ROOT)) {
        "ended", "canceled", "cancelled" -> ShowStatus.Completed
        "returning series", "in production", "planned" -> ShowStatus.Ongoing
        else -> null
    }

    fun resetSession() {
        sharedPref?.edit()?.remove(PREF_SESSION)?.apply()
        lastForcedRefreshMs = 0L
        applySession(cookie = "", xsrfToken = "", inertiaVersion = "")
    }

    private fun parseSearchResults(text: String): List<StreamingCommunityTitle> {
        return runCatching {
            val json = JSONObject(extractPageJson(text) ?: text)
            val titles = json.optJSONArray("data")
                ?: json.optJSONObject("props")?.optJSONArray("titles")
                ?: JSONArray()
            buildList {
                for (index in 0 until titles.length()) {
                    titles.optJSONObject(index)?.toTitle()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchTitleDetail(title: StreamingCommunityTitle): StreamingCommunityTitle? {
        fetchTitleDetailAttempt(title)?.let { return it }
        if (!shouldForceRefresh()) return title
        if (runCatching { ensureHeaders(forceRefresh = true) }.isFailure) return title
        return fetchTitleDetailAttempt(title) ?: title
    }

    private suspend fun fetchTitleDetailAttempt(title: StreamingCommunityTitle): StreamingCommunityTitle? {
        runCatching { ensureHeaders() }.getOrElse { return null }
        val text = runCatching {
            app.get(
                "${mainUrl()}/titles/${title.id}-${title.slug}",
                headers = sessionHeaders,
            ).body.string()
        }.getOrNull() ?: return null

        return runCatching {
            val json = JSONObject(extractPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            val resolvedTitle = props.optJSONObject("title")?.toTitle() ?: return null
            val loadedSeason = props.optJSONObject("loadedSeason")?.toSeason()
            if (loadedSeason == null) {
                resolvedTitle
            } else {
                resolvedTitle.copy(seasons = resolvedTitle.seasons.mergeSeason(loadedSeason))
            }
        }.getOrNull()
    }

    private suspend fun fetchSeason(
        title: StreamingCommunityTitle,
        seasonNumber: Int,
    ): StreamingCommunitySeason? {
        fetchSeasonAttempt(title, seasonNumber)?.let { return it }
        if (!shouldForceRefresh()) return null
        if (runCatching { ensureHeaders(forceRefresh = true) }.isFailure) return null
        return fetchSeasonAttempt(title, seasonNumber)
    }

    private suspend fun fetchSeasonAttempt(
        title: StreamingCommunityTitle,
        seasonNumber: Int,
    ): StreamingCommunitySeason? {
        runCatching { ensureHeaders() }.getOrElse { return null }
        val text = runCatching {
            app.get(
                "${mainUrl()}/titles/${title.id}-${title.slug}/season-$seasonNumber",
                headers = sessionHeaders,
            ).body.string()
        }.getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(extractPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            props.optJSONObject("loadedSeason")?.toSeason()
        }.getOrNull()
    }

    @Synchronized
    private fun shouldForceRefresh(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastForcedRefreshMs < FORCE_REFRESH_INTERVAL_MS) return false
        lastForcedRefreshMs = now
        return true
    }

    private suspend fun ensureHeaders(forceRefresh: Boolean = false) {
        ensureDomain()
        if (hasSessionHeaders() && !forceRefresh) return
        if (!forceRefresh && restoreSession()) return

        val response = app.get("${mainUrl()}/archive")
        val cookieJar = linkedMapOf<String, String>()
        response.cookies.forEach { cookieJar[it.key] = it.value }
        val csrfResponse = app.get(
            "${rootUrl()}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "${mainUrl()}/",
                "X-Requested-With" to "XMLHttpRequest",
            )
        )
        csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

        applySession(
            cookie = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" },
            xsrfToken = cookieJar["XSRF-TOKEN"]
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                .orEmpty(),
            inertiaVersion = response.document
                .select("#app")
                .attr("data-page")
                .substringAfter("\"version\":\"")
                .substringBefore("\""),
        )
        persistSession()
    }

    private fun hasSessionHeaders(): Boolean {
        return sessionHeaders["Cookie"].orEmpty().isNotBlank() && inertiaVersion.isNotBlank()
    }

    private fun applySession(cookie: String, xsrfToken: String, inertiaVersion: String) {
        sessionHeaders["Cookie"] = cookie
        this.xsrfToken = xsrfToken
        this.inertiaVersion = inertiaVersion
        sessionHeaders["X-Inertia-Version"] = inertiaVersion
        if (xsrfToken.isNotBlank()) {
            sessionHeaders["X-XSRF-TOKEN"] = xsrfToken
        } else {
            sessionHeaders.remove("X-XSRF-TOKEN")
        }
    }

    private fun restoreSession(): Boolean {
        val json = readSessionPayload() ?: return false
        val cookie = json.optString("cookie")
        val storedVersion = json.optString("inertiaVersion")
        if (cookie.isBlank() || storedVersion.isBlank()) return false
        applySession(cookie, json.optString("xsrfToken"), storedVersion)
        return true
    }

    private fun persistSession() {
        if (!hasSessionHeaders()) return
        val payload = JSONObject()
            .put("cookie", sessionHeaders["Cookie"].orEmpty())
            .put("xsrfToken", xsrfToken)
            .put("inertiaVersion", inertiaVersion)
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

    private fun extractPageJson(payload: String): String? {
        val trimmedPayload = payload.trimStart()
        if (!trimmedPayload.startsWith("<")) return null
        val dataPageRaw = Jsoup.parse(payload).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    private fun JSONObject.toTitle(): StreamingCommunityTitle? {
        val id = optNullableInt("id") ?: return null
        val slug = optNullableString("slug") ?: return null
        val name = optNullableString("name") ?: return null
        val type = optNullableString("type") ?: return null
        val seasons = optJSONArray("seasons")?.let { seasonsJson ->
            buildList {
                for (index in 0 until seasonsJson.length()) {
                    seasonsJson.optJSONObject(index)?.toSeason()?.let(::add)
                }
            }
        }.orEmpty()
        val images = optJSONArray("images")
        return StreamingCommunityTitle(
            id = id,
            slug = slug,
            name = name,
            type = type,
            tmdbId = optNullableInt("tmdb_id"),
            imdbId = optNullableString("imdb_id"),
            year = optNullableString("release_date")?.substringBefore('-')?.toIntOrNull(),
            seasons = seasons,
            plot = cleanText(optNullableString("plot")),
            score = optNullableString("score"),
            runtime = optNullableInt("runtime"),
            genres = optJSONArray("genres")?.let { genresJson ->
                buildList {
                    for (index in 0 until genresJson.length()) {
                        genresJson.optJSONObject(index)?.optNullableString("name")?.let(::add)
                    }
                }
            }.orEmpty(),
            status = optNullableString("status"),
            age = optNullableInt("age"),
            posterFilename = images?.imageFilename("poster"),
            backgroundFilename = images?.imageFilename("background") ?: images?.imageFilename("cover"),
            logoFilename = images?.imageFilename("logo"),
        )
    }

    private fun JSONObject.toSeason(): StreamingCommunitySeason? {
        val id = optNullableInt("id") ?: return null
        val number = optNullableInt("number") ?: return null
        val episodes = optJSONArray("episodes")?.let { episodesJson ->
            buildList {
                for (index in 0 until episodesJson.length()) {
                    episodesJson.optJSONObject(index)?.toEpisode()?.let(::add)
                }
            }
        }.orEmpty()
        return StreamingCommunitySeason(id = id, number = number, episodes = episodes)
    }

    private fun JSONObject.toEpisode(): StreamingCommunityEpisode? {
        return StreamingCommunityEpisode(
            id = optNullableInt("id") ?: return null,
            number = optNullableInt("number") ?: return null,
        )
    }

    private fun JSONArray.imageFilename(imageType: String): String? {
        for (index in 0 until length()) {
            val image = optJSONObject(index) ?: continue
            if (image.optNullableString("type") == imageType) return image.optNullableString("filename")
        }
        return null
    }

    private fun List<StreamingCommunitySeason>.mergeSeason(
        loadedSeason: StreamingCommunitySeason,
    ): List<StreamingCommunitySeason> {
        if (none { it.number == loadedSeason.number || it.id == loadedSeason.id }) return this + loadedSeason
        return map { season ->
            if (season.number == loadedSeason.number || season.id == loadedSeason.id) loadedSeason else season
        }
    }

    private fun StreamingCommunityTitle.matchesFallback(
        metadata: StreamCenterMetadata,
        expectedType: String,
    ): Boolean {
        if (type != expectedType) return false
        val titleMatches = normalizeTitle(name) == normalizeTitle(metadata.title) ||
            normalizeTitle(name) == normalizeTitle(metadata.originalTitle)
        val yearMatches = metadata.year == null || year == null || metadata.year == year
        return titleMatches && yearMatches
    }

    private fun normalizeTitle(title: String?): String {
        return title.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore('/').substringBefore(':')
    }

    private companion object {
        const val PREF_SESSION = "streamcenter_sc_session"
        const val SESSION_TTL_MS = 24L * 60L * 60L * 1000L
        const val FORCE_REFRESH_INTERVAL_MS = 60_000L
        const val DETAIL_CANDIDATE_LIMIT = 8
    }
}
