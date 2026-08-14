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
import it.dogior.hadEnough.util.StreamCenterLogger
import kotlinx.coroutines.CompletableDeferred
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
    private val pagePropsLock = Any()
    private val pagePropsInFlight = HashMap<String, CompletableDeferred<JSONObject?>>()
    private val sessionHeaders = mutableMapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to "",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun log(action: String, details: Map<String, Any?> = emptyMap()) {
        StreamCenterLogger.logMetadata(
            tabName = logTabName(details),
            source = SOURCE_NAME,
            action = action,
            metadata = details,
        )
    }

    private fun warning(
        action: String,
        details: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) {
        StreamCenterLogger.logMetadata(
            tabName = logTabName(details),
            source = SOURCE_NAME,
            action = action,
            metadata = details,
            level = StreamCenterLogger.Level.WARNING,
            throwable = error,
        )
    }

    private fun logTabName(details: Map<String, Any?>): String {
        return listOf("titolo_scheda", "titolo", "titolo_richiesto", "nome", "title")
            .firstNotNullOfOrNull { key -> details[key]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?: SOURCE_NAME
    }

    suspend fun fetchPageProps(pageUrl: String): JSONObject? {
        var owned: CompletableDeferred<JSONObject?>? = null
        val pending: CompletableDeferred<JSONObject?>? = synchronized(pagePropsLock) {
            val existing = pagePropsInFlight[pageUrl]
            if (existing != null) {
                existing
            } else {
                CompletableDeferred<JSONObject?>().also {
                    pagePropsInFlight[pageUrl] = it
                    owned = it
                }
                null
            }
        }
        val ownedDeferred = owned ?: return pending!!.await()

        var props: JSONObject? = null
        try {
            props = requestPageProps(pageUrl)
        } finally {
            synchronized(pagePropsLock) { pagePropsInFlight.remove(pageUrl) }
            ownedDeferred.complete(props)
        }
        return props
    }

    private suspend fun requestPageProps(pageUrl: String): JSONObject? {
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
        log(
            "Ricerca titolo avviata",
            mapOf(
                "tipo_richiesto" to expectedType,
                "titoli_candidati" to titleCandidates.size,
                "tmdb_disponibile" to (expectedTmdbId != null),
            ),
        )
        val searchCandidates = titleCandidates
            .flatMap { query -> search(query) }
            .filter { it.type == expectedType }
            .distinctBy { it.id }
        log(
            "Risultati ricerca titolo raccolti",
            mapOf("candidati_tipo_compatibile" to searchCandidates.size),
        )

        var fallback: StreamingCommunityTitle? = null
        for ((index, candidate) in searchCandidates.take(DETAIL_CANDIDATE_LIMIT).withIndex()) {
            log(
                "Dettaglio candidato verificato",
                mapOf(
                    "indice_candidato" to (index + 1),
                    "identificativo_candidato" to candidate.id,
                ),
            )
            val detail = fetchTitleDetail(candidate) ?: continue
            if (expectedTmdbId != null && detail.tmdbId == expectedTmdbId) {
                log("Titolo corrispondente tramite TMDB")
                return detail
            }
            if (fallback == null && detail.matchesFallback(metadata, expectedType)) {
                fallback = detail
                warning("Fallback titolo disponibile: corrispondenza nome e anno")
            }
        }
        return if (expectedTmdbId == null) {
            fallback.also {
                if (it == null) warning("Nessun titolo corrispondente")
            }
        } else {
            warning("Nessun titolo corrispondente tramite TMDB")
            null
        }
    }

    suspend fun search(query: String): List<StreamingCommunityTitle> {
        log("Tentativo ricerca provider avviato")
        ensureDomain()
        val text = try {
            app.get(
                "${mainUrl()}/search",
                params = mapOf("q" to query),
            ).body.string()
        } catch (error: Throwable) {
            warning("Tentativo ricerca provider non riuscito", error = error)
            throw error
        }
        return parseSearchResults(text).also { results ->
            log("Tentativo ricerca provider completato", mapOf("risultati_estratti" to results.size))
        }
    }

    suspend fun episodePayloads(
        title: StreamingCommunityTitle,
    ): Map<Pair<Int, Int>, StreamingCommunityPlaybackData> {
        if (title.type != "tv") {
            warning("Recupero episodi ignorato: tipo non televisivo")
            return emptyMap()
        }
        log(
            "Recupero episodi avviato",
            mapOf(
                "identificativo_titolo" to title.id,
                "stagioni_disponibili" to title.seasons.size,
            ),
        )
        val episodes = linkedMapOf<Pair<Int, Int>, StreamingCommunityPlaybackData>()
        for (season in title.seasons) {
            val seasonWithEpisodes = if (season.episodes.isNotEmpty()) {
                season
            } else {
                log(
                    "Dettaglio stagione richiesto",
                    mapOf("numero_stagione" to season.number),
                )
                fetchSeason(title, season.number) ?: season
            }
            seasonWithEpisodes.episodes.forEach { episode ->
                episodes[seasonWithEpisodes.number to episode.number] = StreamingCommunityPlaybackData(
                    iframeUrl = "${mainUrl()}/iframe/${title.id}?episode_id=${episode.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    imdbId = title.imdbId,
                    seasonNumber = seasonWithEpisodes.number,
                    episodeNumber = episode.number,
                )
            }
        }
        return episodes.also { resolvedEpisodes ->
            log(
                "Recupero episodi completato",
                mapOf("episodi_disponibili" to resolvedEpisodes.size),
            )
        }
    }

    fun moviePlayback(title: StreamingCommunityTitle): StreamingCommunityPlaybackData? {
        if (title.type != "movie") return null
        return StreamingCommunityPlaybackData(
            iframeUrl = "${mainUrl()}/iframe/${title.id}&canPlayFHD=1",
            type = title.type,
            tmdbId = title.tmdbId,
            imdbId = title.imdbId,
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
        log("Sessione provider reimpostata")
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
        }.onFailure {
            warning("Parsing risultati ricerca non riuscito", error = it)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchTitleDetail(title: StreamingCommunityTitle): StreamingCommunityTitle? {
        log("Dettaglio titolo avviato", mapOf("identificativo_titolo" to title.id))
        fetchTitleDetailAttempt(title)?.let {
            log("Dettaglio titolo completato al primo tentativo")
            return it
        }
        if (!shouldForceRefresh()) {
            warning("Retry dettaglio titolo non eseguito: aggiornamento recente")
            return title
        }
        log("Retry dettaglio titolo avviato: aggiornamento sessione")
        if (runCatching { ensureHeaders(forceRefresh = true) }.onFailure {
                warning("Aggiornamento sessione per retry non riuscito", error = it)
            }.isFailure
        ) {
            return title
        }
        return (fetchTitleDetailAttempt(title) ?: title).also {
            log("Retry dettaglio titolo completato")
        }
    }

    suspend fun fetchRelatedTitles(
        title: StreamingCommunityTitle,
        limit: Int,
    ): List<StreamingCommunityTitle> {
        log(
            "Recupero correlati avviato",
            mapOf("identificativo_titolo" to title.id),
        )
        fetchRelatedTitlesAttempt(title, limit)?.let { relatedTitles ->
            log("Recupero correlati completato", mapOf("correlati_estratti" to relatedTitles.size))
            return relatedTitles
        }
        if (!shouldForceRefresh()) {
            warning("Retry correlati non eseguito: aggiornamento recente")
            return emptyList()
        }
        log("Retry correlati avviato: aggiornamento sessione")
        if (runCatching { ensureHeaders(forceRefresh = true) }.onFailure {
                warning("Aggiornamento sessione per retry correlati non riuscito", error = it)
            }.isFailure
        ) {
            return emptyList()
        }
        return fetchRelatedTitlesAttempt(title, limit).orEmpty().also { relatedTitles ->
            log("Retry correlati completato", mapOf("correlati_estratti" to relatedTitles.size))
        }
    }

    private suspend fun fetchTitleDetailAttempt(title: StreamingCommunityTitle): StreamingCommunityTitle? {
        val props = fetchTitlePropsAttempt(title) ?: return null
        return props.optJSONObject("title")?.toTitle()?.let { resolvedTitle ->
            val loadedSeason = props.optJSONObject("loadedSeason")?.toSeason()
            if (loadedSeason == null) {
                resolvedTitle
            } else {
                resolvedTitle.copy(seasons = resolvedTitle.seasons.mergeSeason(loadedSeason))
            }
        }
    }

    private suspend fun fetchRelatedTitlesAttempt(
        title: StreamingCommunityTitle,
        limit: Int,
    ): List<StreamingCommunityTitle>? {
        val props = fetchTitlePropsAttempt(title) ?: return null
        val relatedTitles = props.optJSONArray("sliders")
            ?.firstObject { it.optNullableString("name") == "related" }
            ?.optJSONArray("titles")
            ?: return emptyList()
        return buildList {
            for (index in 0 until relatedTitles.length()) {
                relatedTitles.optJSONObject(index)?.toTitle()?.let(::add)
                if (size >= limit) break
            }
        }.filterNot { it.id == title.id }
    }

    private suspend fun fetchTitlePropsAttempt(title: StreamingCommunityTitle): JSONObject? {
        runCatching { ensureHeaders() }.getOrElse {
            warning("Sessione per dettaglio titolo non disponibile", error = it)
            return null
        }
        val text = runCatching {
            app.get(
                "${mainUrl()}/titles/${title.id}-${title.slug}",
                headers = sessionHeaders,
            ).body.string()
        }.onFailure {
            warning("Richiesta dettaglio titolo non riuscita", error = it)
        }.getOrNull() ?: return null

        return runCatching {
            val json = JSONObject(extractPageJson(text) ?: text)
            json.optJSONObject("props") ?: json
        }.onFailure {
            warning("Parsing dettaglio titolo non riuscito", error = it)
        }.getOrNull()
    }

    private suspend fun fetchSeason(
        title: StreamingCommunityTitle,
        seasonNumber: Int,
    ): StreamingCommunitySeason? {
        fetchSeasonAttempt(title, seasonNumber)?.let {
            log("Dettaglio stagione completato al primo tentativo", mapOf("numero_stagione" to seasonNumber))
            return it
        }
        if (!shouldForceRefresh()) {
            warning(
                "Retry dettaglio stagione non eseguito: aggiornamento recente",
                mapOf("numero_stagione" to seasonNumber),
            )
            return null
        }
        log("Retry dettaglio stagione avviato", mapOf("numero_stagione" to seasonNumber))
        if (runCatching { ensureHeaders(forceRefresh = true) }.onFailure {
                warning(
                    "Aggiornamento sessione per retry stagione non riuscito",
                    mapOf("numero_stagione" to seasonNumber),
                    it,
                )
            }.isFailure
        ) {
            return null
        }
        return fetchSeasonAttempt(title, seasonNumber).also {
            log(
                "Retry dettaglio stagione completato",
                mapOf(
                    "numero_stagione" to seasonNumber,
                    "dettaglio_disponibile" to (it != null),
                ),
            )
        }
    }

    private suspend fun fetchSeasonAttempt(
        title: StreamingCommunityTitle,
        seasonNumber: Int,
    ): StreamingCommunitySeason? {
        runCatching { ensureHeaders() }.getOrElse {
            warning(
                "Sessione per dettaglio stagione non disponibile",
                mapOf("numero_stagione" to seasonNumber),
                it,
            )
            return null
        }
        val text = runCatching {
            app.get(
                "${mainUrl()}/titles/${title.id}-${title.slug}/season-$seasonNumber",
                headers = sessionHeaders,
            ).body.string()
        }.onFailure {
            warning(
                "Richiesta dettaglio stagione non riuscita",
                mapOf("numero_stagione" to seasonNumber),
                it,
            )
        }.getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(extractPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            props.optJSONObject("loadedSeason")?.toSeason()
        }.onFailure {
            warning(
                "Parsing dettaglio stagione non riuscito",
                mapOf("numero_stagione" to seasonNumber),
                it,
            )
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

    private fun JSONArray.firstObject(predicate: (JSONObject) -> Boolean): JSONObject? {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            if (predicate(item)) return item
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
        const val SOURCE_NAME = "StreamingCommunity"
        const val PREF_SESSION = "streamcenter_sc_session"
        const val SESSION_TTL_MS = 24L * 60L * 60L * 1000L
        const val FORCE_REFRESH_INTERVAL_MS = 60_000L
        const val DETAIL_CANDIDATE_LIMIT = 8
    }
}
