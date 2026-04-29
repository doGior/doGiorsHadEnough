package it.dogior.hadEnough

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class Hub : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = "Hub"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    private val tmdbLanguage = "it-IT"
    private val animeKeywordPath = "/keyword/210024-anime/"
    private val animeMarker = "hub_media=anime"
    private val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movie" to "Film popolari",
        "$mainUrl/movie/now-playing" to "Film al cinema",
        "$mainUrl/movie/upcoming" to "Film in arrivo",
        "$mainUrl/movie/top-rated" to "Film piu votati",
        "$mainUrl/tv" to "Serie TV popolari",
        "$mainUrl/tv/airing-today" to "Serie TV oggi in onda",
        "$mainUrl/tv/on-the-air" to "Serie TV in onda",
        "$mainUrl/tv/top-rated" to "Serie TV piu votate",
        "$mainUrl${animeKeywordPath}tv" to "Anime",
        "$mainUrl${animeKeywordPath}movie" to "Film anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getTmdbDocument(request.data, page = page, cacheProfile = TmdbCacheProfile.Home)
        val items = parseMediaCards(doc, isAnimeSection(request.data))
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false,
            ),
            hasNext = hasNextPage(doc, page),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchSearchResults(query, 1).first
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = fetchSearchResults(query, page)
        return newSearchResponseList(items, hasNext)
    }

    private suspend fun fetchSearchResults(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val movieDoc = getTmdbDocument(
            "$mainUrl/search/movie?query=$encodedQuery",
            page = page,
            cacheProfile = TmdbCacheProfile.Search,
        )
        val tvDoc = getTmdbDocument(
            "$mainUrl/search/tv?query=$encodedQuery",
            page = page,
            cacheProfile = TmdbCacheProfile.Search,
        )
        val items = (parseMediaCards(movieDoc) + parseMediaCards(tvDoc)).distinctBy { it.url }
        val hasNext = hasNextPage(movieDoc, page) || hasNextPage(tvDoc, page)
        return items to hasNext
    }

    override suspend fun load(url: String): LoadResponse {
        val actualUrl = normalizeTmdbUrl(url)
        val doc = getTmdbDocument(actualUrl, cacheProfile = TmdbCacheProfile.Detail)
        val isTvSeries = actualUrl.contains("/tv/")
        val isAnime = actualUrl.contains(animeMarker)
        val metadata = buildMetadata(doc, actualUrl, isAnime)
        val typeTags = if (isAnime) listOf("Anime") else emptyList()
        val tags = (typeTags + metadata.tags).distinctBy { it.lowercase(Locale.ROOT) }

        return if (isTvSeries) {
            val episodes = fetchEpisodes(doc, actualUrl)

            newTvSeriesLoadResponse(
                metadata.title,
                actualUrl,
                if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes,
            ) {
                this.posterUrl = metadata.poster
                this.backgroundPosterUrl = metadata.background
                this.plot = metadata.plot
                this.tags = tags
                this.year = metadata.year
                this.actors = metadata.people
                this.recommendations = metadata.recommendations
                this.contentRating = metadata.contentRating
                this.showStatus = metadata.showStatus
                this.comingSoon = metadata.comingSoon
                metadata.tmdbId?.let { addTMDbId(it) }
                metadata.trailerUrl?.let { addTrailer(it) }
                addScore(metadata.score)
            }
        } else {
            newMovieLoadResponse(
                metadata.title,
                actualUrl,
                if (isAnime) TvType.AnimeMovie else TvType.Movie,
                dataUrl = "",
            ) {
                this.posterUrl = metadata.poster
                this.backgroundPosterUrl = metadata.background
                this.plot = metadata.plot
                this.tags = tags
                this.year = metadata.year
                this.duration = metadata.duration
                this.actors = metadata.people
                this.recommendations = metadata.recommendations
                this.contentRating = metadata.contentRating
                this.comingSoon = metadata.comingSoon
                metadata.tmdbId?.let { addTMDbId(it) }
                metadata.trailerUrl?.let { addTrailer(it) }
                addScore(metadata.score)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return false
    }

    private data class HubMetadata(
        val title: String,
        val plot: String?,
        val poster: String?,
        val background: String?,
        val tags: List<String>,
        val year: Int?,
        val tmdbId: String?,
        val score: String?,
        val people: List<ActorData>,
        val recommendations: List<SearchResponse>,
        val contentRating: String?,
        val showStatus: ShowStatus?,
        val comingSoon: Boolean,
        val duration: Int?,
        val trailerUrl: String?,
    )

    private enum class TmdbCacheProfile(val ttlMs: Long) {
        Home(12L * 60L * 60L * 1000L),
        Search(12L * 60L * 60L * 1000L),
        Detail(7L * 24L * 60L * 60L * 1000L),
        Seasons(24L * 60L * 60L * 1000L),
        Recommendations(7L * 24L * 60L * 60L * 1000L);

        val cacheMinutes: Int
            get() = (ttlMs / 60_000L).toInt().coerceAtLeast(1)
    }

    private data class TextCacheEntry(
        val text: String,
        val expiresAtMs: Long,
    )

    private class ExpiringTextCache(private val maxEntries: Int) {
        private var diskDirectory: File? = null
        private val entries = object : LinkedHashMap<String, TextCacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextCacheEntry>?): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        fun setDiskDirectory(directory: File) {
            diskDirectory = directory
            runCatching { directory.mkdirs() }
        }

        @Synchronized
        fun get(key: String, allowExpired: Boolean = false): String? {
            val entry = entries[key]
            if (entry != null) {
                if (allowExpired || entry.expiresAtMs > System.currentTimeMillis()) {
                    return entry.text
                }

                entries.remove(key)
            }

            return readDisk(key, allowExpired)
        }

        @Synchronized
        fun put(key: String, text: String, ttlMs: Long) {
            val expiresAtMs = System.currentTimeMillis() + ttlMs
            entries[key] = TextCacheEntry(
                text = text,
                expiresAtMs = expiresAtMs,
            )
            writeDisk(key, text, expiresAtMs)
        }

        private fun readDisk(key: String, allowExpired: Boolean): String? {
            val file = cacheFile(key) ?: return null
            val raw = runCatching { file.readText() }.getOrNull() ?: return null
            val separator = raw.indexOf('\n')
            if (separator <= 0) {
                runCatching { file.delete() }
                return null
            }

            val expiresAtMs = raw.substring(0, separator).toLongOrNull()
            if (expiresAtMs == null) {
                runCatching { file.delete() }
                return null
            }

            val text = raw.substring(separator + 1)
            if (!allowExpired && expiresAtMs <= System.currentTimeMillis()) {
                runCatching { file.delete() }
                return null
            }

            entries[key] = TextCacheEntry(text, expiresAtMs)
            return text
        }

        private fun writeDisk(key: String, text: String, expiresAtMs: Long) {
            val directory = diskDirectory ?: return
            runCatching {
                directory.mkdirs()
                cacheFile(key)?.writeText("$expiresAtMs\n$text")
                trimDisk(directory)
            }
        }

        private fun cacheFile(key: String): File? {
            val directory = diskDirectory ?: return null
            return File(directory, "${sha256(key)}.html")
        }

        private fun trimDisk(directory: File) {
            val files = directory.listFiles { file -> file.isFile && file.extension == "html" }.orEmpty()
            val maxDiskEntries = maxEntries * 2
            if (files.size <= maxDiskEntries) return

            files.sortedBy { it.lastModified() }
                .take(files.size - maxDiskEntries)
                .forEach { runCatching { it.delete() } }
        }

        private fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private suspend fun buildMetadata(doc: Document, actualUrl: String, isAnime: Boolean): HubMetadata {
        val title = getLocalizedTitle(doc).ifBlank { "Sconosciuto" }
        val originalTitle = extractAnyFact(doc, "Titolo originale", "Original Title", "Original Name")
        val status = extractAnyFact(doc, "Stato", "Status")
        val originalLanguage = extractAnyFact(doc, "Lingua Originale", "Original Language")
        val type = extractAnyFact(doc, "Tipo", "Type")
        val budget = extractAnyFact(doc, "Budget")
        val revenue = extractAnyFact(doc, "Incasso", "Revenue")
        val genres = doc.select("span.genres a").mapNotNull { cleanText(it.text()) }
        val factTags = buildFactTags(title, originalTitle, status, originalLanguage, type, budget, revenue)
        val keywords = extractKeywords(doc)
        val images = doc.select("meta[property=og:image]").mapNotNull { cleanText(it.attr("content")) }
        val score = doc.selectFirst(".user_score_chart[data-percent]")
            ?.attr("data-percent")
            ?.toIntOrNull()
            ?.let { String.format(Locale.US, "%.1f", it / 10.0) }

        return HubMetadata(
            title = title,
            plot = cleanText(doc.selectFirst("meta[property=og:description]")?.attr("content")),
            poster = images.firstOrNull(),
            background = images.getOrNull(1),
            tags = (genres + factTags + keywords).distinctBy { it.lowercase(Locale.ROOT) },
            year = parseYear(doc),
            tmdbId = extractTmdbId(actualUrl),
            score = score,
            people = (parseActors(doc) + parseCrew(doc)).distinct(),
            recommendations = fetchRecommendations(actualUrl, isAnime),
            contentRating = extractContentRating(doc),
            showStatus = mapShowStatus(status),
            comingSoon = isComingSoon(status),
            duration = parseRuntime(doc.selectFirst("span.runtime")?.text()),
            trailerUrl = extractTrailerUrl(doc),
        )
    }

    private suspend fun getTmdbDocument(
        url: String,
        page: Int? = null,
        cacheProfile: TmdbCacheProfile = TmdbCacheProfile.Detail,
    ): Document {
        val requestUrl = stripHubParams(normalizeTmdbUrl(url, page))
        tmdbTextCache.get(requestUrl)?.let { return Jsoup.parse(it, requestUrl) }

        return try {
            val html = app.get(
                requestUrl,
                headers = headers,
                cacheTime = cacheProfile.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
            if (html.isNotBlank()) {
                tmdbTextCache.put(requestUrl, html, cacheProfile.ttlMs)
            }
            Jsoup.parse(html, requestUrl)
        } catch (exception: Exception) {
            tmdbTextCache.get(requestUrl, allowExpired = true)?.let { Jsoup.parse(it, requestUrl) }
                ?: throw exception
        }
    }

    private fun normalizeTmdbUrl(url: String, page: Int? = null): String {
        val absoluteUrl = if (url.startsWith("http")) {
            url
        } else {
            mainUrl + if (url.startsWith("/")) url else "/$url"
        }

        val params = buildMap {
            put("language", tmdbLanguage)
            page?.let { put("page", it.toString()) }
        }

        val existingQuery = absoluteUrl.substringAfter("?", "")
        val paramsToAppend = params
            .filterKeys { "$it=" !in existingQuery }
            .map { "${it.key}=${it.value}" }

        if (paramsToAppend.isEmpty()) return absoluteUrl

        val separator = if ("?" in absoluteUrl) "&" else "?"
        return absoluteUrl + separator + paramsToAppend.joinToString("&")
    }

    private fun stripHubParams(url: String): String {
        val baseUrl = url.substringBefore("?")
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return url

        val params = query.split("&").filterNot { parameter ->
            parameter == animeMarker || parameter.startsWith("hub_media=")
        }
        return if (params.isEmpty()) baseUrl else "$baseUrl?${params.joinToString("&")}"
    }

    private fun parseMediaCards(doc: Document, isAnime: Boolean = false): List<SearchResponse> {
        val seen = mutableSetOf<String>()

        return doc.select("a[href]").mapNotNull { anchor ->
            val mediaType = getMediaType(anchor) ?: return@mapNotNull null
            val href = anchor.attr("href").substringBefore("?")
            if (!seen.add(href)) return@mapNotNull null

            val card = findMediaCard(anchor) ?: anchor
            val title = getCardTitle(card, anchor) ?: return@mapNotNull null
            val poster = card.selectFirst("img.poster")?.extractImageUrl()
                ?: anchor.selectFirst("img.poster")?.extractImageUrl()
                ?: card.selectFirst("img.backdrop")?.extractImageUrl()
                ?: anchor.selectFirst("img.backdrop")?.extractImageUrl()
            val itemUrl = normalizeTmdbUrl(href).let { if (isAnime) markAnimeUrl(it) else it }

            if (isAnime) {
                return@mapNotNull newAnimeSearchResponse(
                    title,
                    itemUrl,
                    if (mediaType == "tv") TvType.Anime else TvType.AnimeMovie,
                ) {
                    this.posterUrl = poster
                }
            }

            if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    private fun getMediaType(anchor: Element): String? {
        val dataMediaType = anchor.attr("data-media-type")
            .takeIf { it == "movie" || it == "tv" }
        if (dataMediaType != null) return dataMediaType

        val href = anchor.attr("href")
        return when {
            Regex("""/movie/\d+""").containsMatchIn(href) -> "movie"
            Regex("""/tv/\d+""").containsMatchIn(href) -> "tv"
            else -> null
        }
    }

    private fun findMediaCard(anchor: Element): Element? {
        return anchor.parents().firstOrNull { parent ->
            val className = parent.className()
            className.contains("poster-card") ||
                className.contains("media-card") ||
                className.contains("mini_card")
        }
    }

    private fun getCardTitle(card: Element, anchor: Element): String? {
        val title = card.selectFirst("h2 span")?.text()?.trim()
            ?: anchor.selectFirst("h2 span")?.text()?.trim()
            ?: card.selectFirst("a.title bdi")?.text()?.trim()
            ?: anchor.selectFirst("bdi")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: card.selectFirst("img.poster")?.attr("alt")?.trim()
            ?: anchor.selectFirst("img.poster")?.attr("alt")?.trim()
            ?: card.selectFirst("img.backdrop")?.attr("alt")?.trim()
            ?: anchor.selectFirst("img.backdrop")?.attr("alt")?.trim()

        return title?.takeIf { it.isNotBlank() }
    }

    private fun isAnimeSection(url: String): Boolean {
        return url.contains(animeKeywordPath)
    }

    private fun markAnimeUrl(url: String): String {
        if (url.contains(animeMarker)) return url
        val separator = if ("?" in url) "&" else "?"
        return "$url$separator$animeMarker"
    }

    private fun Element.extractImageUrl(): String? {
        val srcset = attr("srcset")
            .split(",")
            .lastOrNull()
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.startsWith("http") }

        return srcset ?: attr("src").takeIf { it.startsWith("http") }
    }

    private fun getLocalizedTitle(doc: Document): String {
        return doc.selectFirst("section.header.poster h2 a")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""
    }

    private fun parseYear(doc: Document): Int? {
        val titleYear = doc.selectFirst("section.header.poster h2 span.release_date")
            ?.text()
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        if (titleYear != null) return titleYear

        return doc.selectFirst("span.release")
            ?.text()
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
    }

    private fun parseRuntime(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val hours = Regex("""(\d+)\s*h""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*m""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val runtime = hours * 60 + minutes
        return runtime.takeIf { it > 0 }
    }

    private fun extractAnyFact(doc: Document, vararg labels: String): String? {
        return labels.asSequence().mapNotNull { extractFact(doc, it) }.firstOrNull()
    }

    private fun extractFact(doc: Document, label: String): String? {
        val expectedLabel = normalizeFactLabel(label)
        for (fact in doc.select("section.facts.left_column p")) {
            val strong = fact.selectFirst("strong") ?: continue
            val key = strong.text().trim()
            if (normalizeFactLabel(key) != expectedLabel) continue

            val ownText = fact.ownText().trim()
            val allText = fact.text().trim()
            val fallbackText = if (allText.startsWith(key)) allText.substring(key.length).trim() else allText
            return cleanText(ownText.ifBlank { fallbackText })
        }
        return null
    }

    private fun normalizeFactLabel(value: String): String {
        return value.trim().trimEnd(':').lowercase(Locale.ROOT)
    }

    private fun cleanText(text: String?): String? {
        return text
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "-" }
    }

    private fun buildFactTags(
        title: String,
        originalTitle: String?,
        status: String?,
        originalLanguage: String?,
        type: String?,
        budget: String?,
        revenue: String?,
    ): List<String> {
        val originalTitleTag = originalTitle
            ?.takeIf { !it.equals(title, ignoreCase = true) }
            ?.let { "Titolo originale: $it" }

        return listOfNotNull(
            originalTitleTag,
            status?.let { "Stato: $it" },
            originalLanguage?.let { "Lingua originale: $it" },
            type?.let { "Tipo: $it" },
            budget?.let { "Budget: $it" },
            revenue?.let { "Incasso: $it" },
        )
    }

    private fun extractKeywords(doc: Document): List<String> {
        return doc.select("section.keywords li a.rounded")
            .mapNotNull { cleanText(it.text()) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun extractContentRating(doc: Document): String? {
        return cleanText(doc.selectFirst("span.certification")?.text())
    }

    private fun extractTrailerUrl(doc: Document): String? {
        val trailer = doc.select("a.play_trailer[data-id]")
            .firstOrNull { it.attr("data-site").equals("YouTube", ignoreCase = true) }
            ?: return null
        val youtubeId = cleanText(trailer.attr("data-id")) ?: return null
        return "https://www.youtube.com/watch?v=$youtubeId"
    }

    private fun mapShowStatus(status: String?): ShowStatus? {
        val normalized = status?.lowercase(Locale.ROOT) ?: return null
        return when {
            normalized.contains("terminat") ||
                normalized.contains("conclus") ||
                normalized.contains("cancellat") ||
                normalized.contains("ended") ||
                normalized.contains("canceled") -> ShowStatus.Completed
            normalized.contains("in corso") ||
                normalized.contains("in onda") ||
                normalized.contains("produzione") ||
                normalized.contains("returning") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun isComingSoon(status: String?): Boolean {
        val normalized = status?.lowercase(Locale.ROOT) ?: return false
        return normalized.contains("prossimamente") ||
            normalized.contains("pianificat") ||
            normalized.contains("annunciat") ||
            normalized.contains("post produzione") ||
            normalized.contains("post-produzione") ||
            normalized.contains("in produzione") ||
            normalized.contains("rumored")
    }

    private suspend fun fetchEpisodes(doc: Document, actualUrl: String): List<Episode> {
        if (!actualUrl.contains("/tv/")) return emptyList()

        return fetchSeasonUrls(doc, actualUrl).flatMap { seasonUrl ->
            val fallbackSeason = extractSeasonNumber(seasonUrl)
            runCatching { getTmdbDocument(seasonUrl, cacheProfile = TmdbCacheProfile.Seasons) }
                .getOrNull()
                ?.let { parseSeasonEpisodes(it, fallbackSeason) }
                .orEmpty()
        }
    }

    private suspend fun fetchSeasonUrls(doc: Document, actualUrl: String): List<String> {
        val path = actualUrl.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (!path.startsWith("tv/")) return emptyList()

        val seasonIndex = runCatching {
            getTmdbDocument("$mainUrl/$path/seasons", cacheProfile = TmdbCacheProfile.Seasons)
        }.getOrNull()
        val seasonUrls = seasonIndex?.let(::extractSeasonLinks).orEmpty()
        return seasonUrls.ifEmpty { extractSeasonLinks(doc) }
    }

    private fun extractSeasonLinks(doc: Document): List<String> {
        return doc.select("a[href*=/season/]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                    .substringBefore("#")
                    .takeIf { it.contains("/season/") && !it.contains("/episode/") }
                    ?: return@mapNotNull null
                normalizeTmdbUrl(href)
            }
            .filter { extractSeasonNumber(it) != null }
            .distinctBy { extractSeasonNumber(it) }
            .sortedWith(compareBy({ extractSeasonNumber(it) ?: Int.MAX_VALUE }, { it }))
    }

    private fun extractSeasonNumber(url: String): Int? {
        return Regex("""/season/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseSeasonEpisodes(seasonDoc: Document, fallbackSeason: Int?): List<Episode> {
        return seasonDoc.select("div.episode_list div.card").mapNotNull { card ->
            val anchor = card.selectFirst("a[data-episode-number][data-season-number]")
                ?: card.selectFirst("a[href*=/episode/]")
            val rawHref = anchor?.attr("href")?.takeIf { it.isNotBlank() && it.contains("/episode/") }
                ?: card.attr("data-url").takeIf { it.isNotBlank() && it.contains("/episode/") }
            val dataUrl = rawHref?.let { normalizeTmdbUrl(it) }.orEmpty()
            val episodeNumber = anchor?.attr("data-episode-number")?.toIntOrNull()
                ?: rawHref?.let { Regex("""/episode/(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            val seasonNumber = anchor?.attr("data-season-number")?.toIntOrNull()
                ?: rawHref?.let { extractSeasonNumber(it) }
                ?: fallbackSeason
            val title = cleanEpisodeTitle(
                card.selectFirst("div.episode_title h3 a")?.text()
                    ?: anchor?.text()
            )

            if (dataUrl.isBlank() && episodeNumber == null && title == null) return@mapNotNull null

            val airDate = parseItalianDateToIso(card.selectFirst("div.date span.date")?.text())
            val runtime = parseRuntime(card.selectFirst("span.runtime")?.text())
            newEpisode(dataUrl) {
                this.name = title
                this.season = seasonNumber
                this.episode = episodeNumber
                this.posterUrl = card.selectFirst("img.backdrop")?.extractImageUrl()
                    ?: card.selectFirst("img")?.extractImageUrl()
                this.description = cleanEpisodeDescription(card.selectFirst("div.overview p")?.text())
                this.runTime = runtime
                airDate?.let { this.addDate(it) }
            }
        }
    }

    private fun cleanEpisodeTitle(text: String?): String? {
        return cleanText(text)
            ?.replace(Regex("""^\d+\.\s*"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun cleanEpisodeDescription(text: String?): String? {
        return cleanText(
            text
                ?.replace("Leggi di pi\u00f9", "")
                ?.replace("Leggi di piu", "")
        )
    }

    private fun parseItalianDateToIso(text: String?): String? {
        val cleaned = cleanText(text) ?: return null
        Regex("""\d{4}-\d{2}-\d{2}""").find(cleaned)?.value?.let { return it }

        val match = Regex("""(\d{1,2})\s+([A-Za-z]+),?\s+(\d{4})""").find(cleaned) ?: return null
        val day = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val monthName = match.groupValues.getOrNull(2)?.lowercase(Locale.ROOT) ?: return null
        val year = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val month = mapOf(
            "gennaio" to 1,
            "febbraio" to 2,
            "marzo" to 3,
            "aprile" to 4,
            "maggio" to 5,
            "giugno" to 6,
            "luglio" to 7,
            "agosto" to 8,
            "settembre" to 9,
            "ottobre" to 10,
            "novembre" to 11,
            "dicembre" to 12,
        )[monthName] ?: return null

        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun parseActors(doc: Document): List<ActorData> {
        return doc.select("#cast_scroller li.card").mapNotNull { card ->
            val name = card.selectFirst("p a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val character = card.selectFirst("p.character")?.text()?.trim()
            val image = card.selectFirst("img.profile")?.extractImageUrl()
            ActorData(Actor(name, image), roleString = character)
        }
    }

    private fun parseCrew(doc: Document): List<ActorData> {
        return doc.select("ol.people.no_image li.profile").mapNotNull { person ->
            val name = cleanText(person.selectFirst("p a")?.text()) ?: return@mapNotNull null
            val role = cleanText(person.selectFirst("p.character")?.text())
            ActorData(Actor(name, null), roleString = role)
        }
    }

    private fun extractTmdbId(url: String): String? {
        val path = url.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (!path.startsWith("movie/") && !path.startsWith("tv/")) return null
        return path.substringAfter('/').substringBefore('-').toIntOrNull()?.toString()
    }

    private suspend fun fetchRecommendations(url: String, isAnime: Boolean): List<SearchResponse> {
        val path = url.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (!path.startsWith("movie/") && !path.startsWith("tv/")) return emptyList()

        val recommendationsUrl = "$mainUrl/$path/remote/recommendations"
        return runCatching {
            getTmdbDocument(
                "$recommendationsUrl?version=1&translate=false",
                cacheProfile = TmdbCacheProfile.Recommendations,
            )
        }.getOrNull()?.let { parseMediaCards(it, isAnime) }.orEmpty()
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean {
        return doc.select("span.next a").isNotEmpty() ||
            doc.select("a[href]").any { it.attr("href").contains("page=${page + 1}") }
    }

    companion object {
        private val tmdbTextCache = ExpiringTextCache(maxEntries = 256)

        fun setCacheDirectory(directory: File) {
            tmdbTextCache.setDiskDirectory(directory)
        }
    }
}
