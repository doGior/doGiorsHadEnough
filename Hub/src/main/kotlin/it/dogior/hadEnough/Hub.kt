package it.dogior.hadEnough

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
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
    private val animeAnilistParam = "hub_anilist"
    private val animeMalParam = "hub_mal"
    private val animeTmdbSeasonParam = "hub_tmdb_season"
    private val animeDisplaySeasonParam = "hub_anime_season"
    private val anilistGraphqlUrl = "https://graphql.anilist.co"
    private val anilistSectionPrefix = "anilist:"
    private val animeUnityUrl = "https://www.animeunity.so"
    private val animeListMappingUrl = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"
    private val streamingCommunityRootUrl = "https://streamingunity.dog/"
    private val streamingCommunityMainUrl = "${streamingCommunityRootUrl}it"
    private val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )
    private val animeUnityHeaders = mutableMapOf(
        "Host" to "www.animeunity.so",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )
    private var streamingCommunityInertiaVersion = ""
    private var streamingCommunityXsrfToken = ""
    private val streamingCommunityHeaders = mutableMapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to streamingCommunityInertiaVersion,
        "X-Requested-With" to "XMLHttpRequest",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/tv/on-the-air?without_keywords=210024" to "Serie TV - Ultime",
        "$mainUrl/tv/top-rated?without_keywords=210024" to "Serie TV - Più Votati",
        "$mainUrl/movie/now-playing?without_keywords=210024" to "Film - Ultimi",
        "$mainUrl/movie/top-rated?without_keywords=210024" to "Film - Più Votati",
        "anilist:anime_latest" to "Anime - Ultimi",
        "anilist:anime_top" to "Anime - Più Votati",
        "anilist:anime_season_top" to "Anime - Più Votati della stagione",
        "anilist:anime_movie_latest" to "Film Anime - Ultimi",
        "anilist:anime_movie_top" to "Film Anime - Più Votati",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        parseAnilistSection(request.data)?.let { section ->
            val anilistPage = fetchAnilistHomePage(section, page)
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = anilistPage.items,
                    isHorizontalImages = false,
                ),
                hasNext = anilistPage.hasNext,
            )
        }

        val isAnimeSection = isAnimeSection(request.data)
        val doc = getTmdbDocument(request.data, page = page, cacheProfile = TmdbCacheProfile.Home)
        val items = parseMediaCards(
            doc = doc,
            isAnime = isAnimeSection,
            excludeAnime = !isAnimeSection,
        )
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
        val items = (parseMediaCards(movieDoc) + parseMediaCards(tvDoc, detectAnime = true)).distinctBy { it.url }
        val hasNext = hasNextPage(movieDoc, page) || hasNextPage(tvDoc, page)
        return items to hasNext
    }

    override suspend fun load(url: String): LoadResponse {
        val actualUrl = normalizeTmdbUrl(url)
        val doc = getTmdbDocument(actualUrl, cacheProfile = TmdbCacheProfile.Detail)
        val isTvSeries = actualUrl.contains("/tv/")
        val isAnime = actualUrl.contains(animeMarker)
        val animeSelection = if (isAnime) parseAnimeSelection(actualUrl) else null
        val metadata = buildMetadata(doc, actualUrl, isAnime)
        val animeSyncIds = if (isAnime) {
            selectAnimeSyncIds(fetchAnimeSyncIds(metadata.tmdbId, isTvSeries), animeSelection)
        } else {
            emptyList()
        }
        val animeUnitySources = if (isAnime) fetchAnimeUnitySources(metadata, animeSyncIds) else emptyList()
        val streamingCommunityTitle = if (!isAnime) {
            fetchStreamingCommunityTitle(metadata, isTvSeries)
        } else {
            null
        }
        val typeTags = if (isAnime) listOf("Anime") else emptyList()
        val tags = (typeTags + metadata.tags).distinctBy { it.lowercase(Locale.ROOT) }
        val displayTitle = if (isAnime) {
            buildAnimeSeasonTitle(metadata.title, animeSelection?.displaySeason)
        } else {
            metadata.title
        }

        return if (isTvSeries) {
            val selectedSeason = animeSelection?.displaySeason ?: animeSelection?.tmdbSeason
            val streamingCommunityEpisodes = streamingCommunityTitle
                ?.let { fetchStreamingCommunityEpisodePayloads(it) }
                .orEmpty()
            val tmdbEpisodes = fetchEpisodes(
                doc = doc,
                actualUrl = actualUrl,
                animeUnitySources = animeUnitySources,
                targetSeason = selectedSeason,
                streamingCommunityEpisodes = streamingCommunityEpisodes,
            )
            val animeUnityEpisodes = if (isAnime && animeSelection != null) {
                buildAnimeUnityEpisodes(
                    animeUnitySources = animeUnitySources,
                    seasonNumber = animeSelection.displaySeason ?: selectedSeason,
                    tmdbEpisodes = tmdbEpisodes,
                )
            } else {
                emptyList()
            }
            val episodes = animeUnityEpisodes.ifEmpty { tmdbEpisodes }
            val primarySyncIds = pickPrimaryAnimeSyncIds(animeSyncIds)

            if (isAnime) {
                newAnimeLoadResponse(
                    displayTitle,
                    actualUrl,
                    TvType.Anime,
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
                    addEpisodes(DubStatus.Subbed, episodes)
                    addSeasonNames(buildAnimeSeasonData(episodes))
                    metadata.tmdbId?.let { addTMDbId(it) }
                    primarySyncIds?.anilistId?.let { addAniListId(it) }
                    primarySyncIds?.malId?.let { addMalId(it) }
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(metadata.score)
                }
            } else {
                newTvSeriesLoadResponse(
                    displayTitle,
                    actualUrl,
                    TvType.TvSeries,
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
            }
        } else {
            val moviePlaybackData = HubPlaybackData(
                tmdbUrl = actualUrl,
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                streamingCommunity = streamingCommunityTitle?.toStreamingCommunityMoviePlayback(),
            )
            val primarySyncIds = animeSyncIds.firstOrNull()

            newMovieLoadResponse(
                displayTitle,
                actualUrl,
                if (isAnime) TvType.AnimeMovie else TvType.Movie,
                dataUrl = moviePlaybackData.toJson(),
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
                primarySyncIds?.anilistId?.let { addAniListId(it) }
                primarySyncIds?.malId?.let { addMalId(it) }
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
        val playbackData = runCatching { parseJson<HubPlaybackData>(data) }.getOrNull()
        var found = false

        playbackData?.animeUnity?.let { animeUnityPlayback ->
            val playerSources = buildAnimeUnityPlayerSources(animeUnityPlayback)
            playerSources.forEach { source ->
                val embedUrl = getAnimeUnityEmbedUrl(source.url)
                if (!embedUrl.isNullOrBlank()) {
                    found = true
                    HubVixCloudExtractor(
                        sourceName = "VixCloud ${source.label}",
                        displayName = "AnimeUnity ${source.label}",
                    ).getUrl(
                        url = embedUrl,
                        referer = animeUnityUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }
        }

        playbackData?.streamingCommunity?.let { streamingCommunityPlayback ->
            found = loadStreamingCommunityLinks(
                playbackData = streamingCommunityPlayback,
                subtitleCallback = subtitleCallback,
                callback = callback,
            ) || found
        }

        return found
    }

    private data class HubMetadata(
        val title: String,
        val originalTitle: String?,
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

    private enum class AnilistHomeSection(
        val key: String,
        val isTvSeries: Boolean,
        val sort: String,
        val formats: List<String>,
        val currentSeason: Boolean = false,
        val releasedOnly: Boolean = false,
    ) {
        AnimeLatest(
            key = "anime_latest",
            isTvSeries = true,
            sort = "START_DATE_DESC",
            formats = listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL"),
            releasedOnly = true,
        ),
        AnimeTop(
            key = "anime_top",
            isTvSeries = true,
            sort = "SCORE_DESC",
            formats = listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL"),
        ),
        AnimeSeasonTop(
            key = "anime_season_top",
            isTvSeries = true,
            sort = "SCORE_DESC",
            formats = listOf("TV", "TV_SHORT", "OVA", "ONA", "SPECIAL"),
            currentSeason = true,
        ),
        AnimeMovieLatest(
            key = "anime_movie_latest",
            isTvSeries = false,
            sort = "START_DATE_DESC",
            formats = listOf("MOVIE"),
            releasedOnly = true,
        ),
        AnimeMovieTop(
            key = "anime_movie_top",
            isTvSeries = false,
            sort = "SCORE_DESC",
            formats = listOf("MOVIE"),
        );

        companion object {
            fun fromKey(key: String): AnilistHomeSection? {
                return values().firstOrNull { it.key == key }
            }
        }
    }

    private data class AnilistHomePageResult(
        val items: List<SearchResponse>,
        val hasNext: Boolean,
    )

    private data class AnilistMedia(
        val anilistId: Int,
        val malId: Int?,
        val title: String,
        val poster: String?,
    )

    private data class AnilistSeason(
        val name: String,
        val year: Int,
    )

    private data class TmdbCardData(
        val title: String?,
        val poster: String?,
    )

    private enum class TmdbCacheProfile(val ttlMs: Long) {
        Home(12L * 60L * 60L * 1000L),
        Search(12L * 60L * 60L * 1000L),
        Detail(7L * 24L * 60L * 60L * 1000L),
        Seasons(24L * 60L * 60L * 1000L),
        Recommendations(7L * 24L * 60L * 60L * 1000L),
        AnilistHome(12L * 60L * 60L * 1000L),
        AnimeMappings(7L * 24L * 60L * 60L * 1000L),
        AnimeUnityArchive(24L * 60L * 60L * 1000L),
        AnimeUnityDetail(24L * 60L * 60L * 1000L),
        AnimeUnityPlayer(60L * 60L * 1000L),
        StreamingCommunitySearch(6L * 60L * 60L * 1000L),
        StreamingCommunityDetail(24L * 60L * 60L * 1000L),
        StreamingCommunityPlayer(60L * 60L * 1000L);

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

    private data class AnimeSyncIds(
        val tmdbId: Int,
        val tmdbSeason: Int?,
        val displaySeason: Int?,
        val anilistId: Int?,
        val malId: Int?,
        val type: String?,
    )

    private data class HubAnimeSelection(
        val tmdbSeason: Int?,
        val displaySeason: Int?,
        val anilistId: Int?,
        val malId: Int?,
    )

    private data class HubPlaybackData(
        val tmdbUrl: String? = null,
        val animeUnity: AnimeUnityPlaybackData? = null,
        val streamingCommunity: StreamingCommunityPlaybackData? = null,
    )

    private data class AnimeUnityPlaybackData(
        val preferredUrl: String,
        val subUrl: String? = null,
        val dubUrl: String? = null,
    )

    private data class StreamingCommunityTitle(
        val id: Int,
        val slug: String,
        val name: String,
        val type: String,
        val tmdbId: Int?,
        val year: Int?,
        val seasons: List<StreamingCommunitySeason>,
    )

    private data class StreamingCommunitySeason(
        val id: Int,
        val number: Int,
        val episodes: List<StreamingCommunityEpisode>,
    )

    private data class StreamingCommunityEpisode(
        val id: Int,
        val number: Int,
    )

    private data class StreamingCommunityPlaybackData(
        val iframeUrl: String,
        val type: String,
        val tmdbId: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    )

    private data class AnimeUnityPlayerSource(
        val label: String,
        val url: String,
    )

    private data class AnimeUnityAnime(
        val id: Int,
        val slug: String,
        val title: String?,
        val titleEng: String?,
        val titleIt: String?,
        val dub: Int,
        val anilistId: Int?,
        val malId: Int?,
        val episodesCount: Int?,
        val realEpisodesCount: Int?,
    )

    private data class AnimeUnityEpisodeInfo(
        val id: Int,
        val number: String,
    )

    private data class AnimeUnityPageData(
        val anime: AnimeUnityAnime,
        val episodes: List<AnimeUnityEpisodeInfo>,
    )

    private data class AnimeUnityTitleSources(
        val syncIds: AnimeSyncIds,
        val subSources: Map<String, String>,
        val dubSources: Map<String, String>,
    ) {
        fun playbackForEpisode(number: String?): AnimeUnityPlaybackData? {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return null
            val subUrl = subSources[normalizedNumber]
            val dubUrl = dubSources[normalizedNumber]
            val preferredUrl = subUrl ?: dubUrl ?: return null
            return AnimeUnityPlaybackData(
                preferredUrl = preferredUrl,
                subUrl = subUrl,
                dubUrl = dubUrl,
            )
        }

        fun firstPlayback(): AnimeUnityPlaybackData? {
            val firstNumber = episodeNumbers().firstOrNull()
            return playbackForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> {
            return (subSources.keys + dubSources.keys)
                .distinct()
                .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
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
            originalTitle = originalTitle,
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
        val html = getCachedText("GET:$requestUrl", cacheProfile) {
            app.get(
                requestUrl,
                headers = headers,
                cacheTime = cacheProfile.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        return Jsoup.parse(html, requestUrl)
    }

    private suspend fun getCachedText(
        cacheKey: String,
        cacheProfile: TmdbCacheProfile,
        fetch: suspend () -> String,
    ): String {
        tmdbTextCache.get(cacheKey)?.let { return it }

        return try {
            fetch().also { text ->
                if (text.isNotBlank()) {
                    tmdbTextCache.put(cacheKey, text, cacheProfile.ttlMs)
                }
            }
        } catch (exception: Exception) {
            tmdbTextCache.get(cacheKey, allowExpired = true) ?: throw exception
        }
    }

    private fun parseAnilistSection(data: String): AnilistHomeSection? {
        if (!data.startsWith(anilistSectionPrefix)) return null
        return AnilistHomeSection.fromKey(data.removePrefix(anilistSectionPrefix))
    }

    private suspend fun fetchAnilistHomePage(
        section: AnilistHomeSection,
        page: Int,
    ): AnilistHomePageResult {
        val requestBodyText = buildAnilistHomeRequestBody(section, page)
        val requestBody = requestBodyText.toRequestBody("application/json;charset=utf-8".toMediaType())
        val responseText = getCachedText("AL:POST:$requestBodyText", TmdbCacheProfile.AnilistHome) {
            app.post(
                anilistGraphqlUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json;charset=utf-8",
                ),
                requestBody = requestBody,
                cacheTime = TmdbCacheProfile.AnilistHome.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }
        val (media, hasNext) = parseAnilistMediaPage(responseText)

        return AnilistHomePageResult(
            items = buildAnilistAnimeSearchResponses(media, section),
            hasNext = hasNext,
        )
    }

    private fun buildAnilistHomeRequestBody(section: AnilistHomeSection, page: Int): String {
        val variables = JSONObject().apply {
            put("page", page.coerceAtLeast(1))
            put("perPage", 25)
            put("sort", JSONArray().put(section.sort))
            put("formatIn", JSONArray(section.formats))
            if (section.currentSeason) {
                val season = getCurrentAnilistSeason()
                put("season", season.name)
                put("seasonYear", season.year)
            }
            if (section.releasedOnly) {
                put("startDateLesser", getTomorrowAnilistDateInt())
            }
        }
        val query = """
            query (
                ${'$'}page: Int,
                ${'$'}perPage: Int,
                ${'$'}sort: [MediaSort],
                ${'$'}formatIn: [MediaFormat],
                ${'$'}season: MediaSeason,
                ${'$'}seasonYear: Int,
                ${'$'}startDateLesser: FuzzyDateInt
            ) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo {
                        hasNextPage
                    }
                    media(
                        type: ANIME,
                        isAdult: false,
                        sort: ${'$'}sort,
                        format_in: ${'$'}formatIn,
                        season: ${'$'}season,
                        seasonYear: ${'$'}seasonYear,
                        startDate_lesser: ${'$'}startDateLesser
                    ) {
                        id
                        idMal
                        title {
                            userPreferred
                            english
                            romaji
                            native
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                    }
                }
            }
        """.trimIndent()

        return JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
    }

    private fun parseAnilistMediaPage(text: String): Pair<List<AnilistMedia>, Boolean> {
        return runCatching {
            val page = JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?: return@runCatching emptyList<AnilistMedia>() to false
            val hasNext = page.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
            val mediaItems = page.optJSONArray("media") ?: JSONArray()
            val media = buildList {
                for (index in 0 until mediaItems.length()) {
                    mediaItems.optJSONObject(index)?.toAnilistMedia()?.let(::add)
                }
            }

            media to hasNext
        }.getOrDefault(emptyList<AnilistMedia>() to false)
    }

    private fun JSONObject.toAnilistMedia(): AnilistMedia? {
        val anilistId = optNullableInt("id") ?: return null
        val titleJson = optJSONObject("title")
        val title = titleJson?.let { title ->
            listOf(
                title.optNullableString("english"),
                title.optNullableString("userPreferred"),
                title.optNullableString("romaji"),
                title.optNullableString("native"),
            ).firstOrNull { !it.isNullOrBlank() }
        } ?: return null
        val cover = optJSONObject("coverImage")?.let { coverImage ->
            coverImage.optNullableString("extraLarge")
                ?: coverImage.optNullableString("large")
        }

        return AnilistMedia(
            anilistId = anilistId,
            malId = optNullableInt("idMal"),
            title = title,
            poster = cover,
        )
    }

    private suspend fun buildAnilistAnimeSearchResponses(
        media: List<AnilistMedia>,
        section: AnilistHomeSection,
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val mediaType = if (section.isTvSeries) "tv" else "movie"

        for (anilistMedia in media) {
            val syncIds = fetchAnimeSyncIdsForAnilist(
                anilistId = anilistMedia.anilistId,
                malId = anilistMedia.malId,
                isTvSeries = section.isTvSeries,
            )
            for (syncIdsForTitle in syncIds) {
                val itemUrl = "$mainUrl/$mediaType/${syncIdsForTitle.tmdbId}"
                val tmdbCard = fetchTmdbCardData(itemUrl)
                val title = tmdbCard?.title
                    ?: anilistMedia.title
                val poster = tmdbCard?.poster
                    ?: anilistMedia.poster

                buildAnimeSearchResponses(
                    title = title,
                    itemUrl = itemUrl,
                    poster = poster,
                    mediaType = mediaType,
                    syncIds = listOf(syncIdsForTitle),
                ).forEach { response ->
                    if (seen.add(response.url)) {
                        results += response
                    }
                }
            }
        }

        return results
    }

    private suspend fun fetchTmdbCardData(itemUrl: String): TmdbCardData? {
        val doc = runCatching {
            getTmdbDocument(itemUrl, cacheProfile = TmdbCacheProfile.Home)
        }.getOrNull() ?: return null
        val title = getLocalizedTitle(doc).takeIf { it.isNotBlank() }
        val poster = doc.select("meta[property=og:image]")
            .mapNotNull { cleanText(it.attr("content")) }
            .firstOrNull()

        return TmdbCardData(title = title, poster = poster)
    }

    private fun getCurrentAnilistSeason(): AnilistSeason {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val season = when (now.get(Calendar.MONTH) + 1) {
            in 1..3 -> "WINTER"
            in 4..6 -> "SPRING"
            in 7..9 -> "SUMMER"
            else -> "FALL"
        }

        return AnilistSeason(name = season, year = year)
    }

    private fun getTomorrowAnilistDateInt(): Int {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
        }.toAnilistDateInt()
    }

    private fun Calendar.toAnilistDateInt(): Int {
        val year = get(Calendar.YEAR)
        val month = get(Calendar.MONTH) + 1
        val day = get(Calendar.DAY_OF_MONTH)
        return year * 10000 + month * 100 + day
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

        val params = query.split("&").filterNot { parameter -> parameter.startsWith("hub_") }
        return if (params.isEmpty()) baseUrl else "$baseUrl?${params.joinToString("&")}"
    }

    private suspend fun parseMediaCards(
        doc: Document,
        isAnime: Boolean = false,
        detectAnime: Boolean = false,
        excludeAnime: Boolean = false,
    ): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        for (anchor in doc.select("a[href]")) {
            val mediaType = getMediaType(anchor) ?: continue
            val href = anchor.attr("href").substringBefore("?")
            if (!seen.add(href)) continue

            val card = findMediaCard(anchor) ?: anchor
            val title = getCardTitle(card, anchor) ?: continue
            val poster = card.selectFirst("img.poster")?.extractImageUrl()
                ?: anchor.selectFirst("img.poster")?.extractImageUrl()
                ?: card.selectFirst("img.backdrop")?.extractImageUrl()
                ?: anchor.selectFirst("img.backdrop")?.extractImageUrl()
            val itemUrl = normalizeTmdbUrl(href)
            val shouldCheckAnime = isAnime || detectAnime || excludeAnime
            val animeSyncIds = if (shouldCheckAnime) {
                fetchAnimeSyncIds(extractTmdbId(itemUrl), isTvSeries = mediaType == "tv")
            } else {
                emptyList()
            }
            if (excludeAnime && animeSyncIds.isNotEmpty()) continue

            val shouldBuildAnimeCard = isAnime || animeSyncIds.isNotEmpty()

            if (shouldBuildAnimeCard) {
                results += buildAnimeSearchResponses(
                    title = title,
                    itemUrl = itemUrl,
                    poster = poster,
                    mediaType = mediaType,
                    syncIds = animeSyncIds,
                )
                continue
            }

            results += if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        return results
    }

    private fun buildAnimeSearchResponses(
        title: String,
        itemUrl: String,
        poster: String?,
        mediaType: String,
        syncIds: List<AnimeSyncIds>,
    ): List<SearchResponse> {
        if (mediaType != "tv" || syncIds.isEmpty()) {
            return listOf(
                newAnimeSearchResponse(
                    title,
                    markAnimeUrl(itemUrl, syncIds.firstOrNull()),
                    if (mediaType == "tv") TvType.Anime else TvType.AnimeMovie,
                ) {
                    this.posterUrl = poster
                }
            )
        }

        return syncIds.map { syncIdsForSeason ->
            newAnimeSearchResponse(
                buildAnimeSeasonTitle(title, syncIdsForSeason.displaySeason),
                markAnimeUrl(itemUrl, syncIdsForSeason),
                TvType.Anime,
            ) {
                this.posterUrl = poster
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
        return url.contains(animeKeywordPath) || parseAnilistSection(url) != null
    }

    private fun markAnimeUrl(url: String, syncIds: AnimeSyncIds? = null): String {
        val params = buildList {
            if (!url.contains(animeMarker)) add(animeMarker)
            syncIds?.anilistId?.let { add("$animeAnilistParam=$it") }
            syncIds?.malId?.let { add("$animeMalParam=$it") }
            syncIds?.tmdbSeason?.let { add("$animeTmdbSeasonParam=$it") }
            syncIds?.displaySeason?.let { add("$animeDisplaySeasonParam=$it") }
        }
        if (params.isEmpty()) return url

        val separator = if ("?" in url) "&" else "?"
        return "$url$separator${params.joinToString("&")}"
    }

    private fun buildAnimeSeasonTitle(title: String, displaySeason: Int?): String {
        return if (displaySeason == null || displaySeason <= 1) title else "$title Stagione $displaySeason"
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

    private suspend fun fetchStreamingCommunityTitle(
        metadata: HubMetadata,
        isTvSeries: Boolean,
    ): StreamingCommunityTitle? {
        val expectedType = if (isTvSeries) "tv" else "movie"
        val expectedTmdbId = metadata.tmdbId?.toIntOrNull()
        val titleCandidates = listOfNotNull(metadata.title, metadata.originalTitle)
            .map { it.replace(Regex("""\(\d{4}\)"""), "").trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val searchCandidates = titleCandidates
            .flatMap { query -> fetchStreamingCommunitySearchResults(query) }
            .filter { it.type == expectedType }
            .distinctBy { it.id }

        var fallback: StreamingCommunityTitle? = null
        for (candidate in searchCandidates.take(8)) {
            val detail = fetchStreamingCommunityTitleDetail(candidate) ?: continue
            if (expectedTmdbId != null && detail.tmdbId == expectedTmdbId) return detail
            if (fallback == null && detail.matchesStreamingCommunityFallback(metadata, expectedType)) {
                fallback = detail
            }
        }

        return if (expectedTmdbId == null) fallback else null
    }

    private suspend fun fetchStreamingCommunitySearchResults(query: String): List<StreamingCommunityTitle> {
        val text = getCachedText(
            "SC:GET:$streamingCommunityMainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}",
            TmdbCacheProfile.StreamingCommunitySearch,
        ) {
            app.get(
                "$streamingCommunityMainUrl/search",
                params = mapOf("q" to query),
                cacheTime = TmdbCacheProfile.StreamingCommunitySearch.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return parseStreamingCommunitySearchResults(text)
    }

    private fun parseStreamingCommunitySearchResults(text: String): List<StreamingCommunityTitle> {
        return runCatching {
            val json = JSONObject(extractStreamingCommunityPageJson(text) ?: text)
            val titles = json.optJSONArray("data")
                ?: json.optJSONObject("props")?.optJSONArray("titles")
                ?: JSONArray()
            buildList {
                for (index in 0 until titles.length()) {
                    titles.optJSONObject(index)?.toStreamingCommunityTitle()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchStreamingCommunityTitleDetail(
        title: StreamingCommunityTitle,
    ): StreamingCommunityTitle? {
        ensureStreamingCommunityHeaders()
        val url = "$streamingCommunityMainUrl/titles/${title.id}-${title.slug}"
        val text = getCachedText(
            "SC:GET:$url:$streamingCommunityInertiaVersion",
            TmdbCacheProfile.StreamingCommunityDetail,
        ) {
            app.get(
                url,
                headers = streamingCommunityHeaders,
                cacheTime = TmdbCacheProfile.StreamingCommunityDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return parseStreamingCommunityTitleDetail(text) ?: title
    }

    private fun parseStreamingCommunityTitleDetail(text: String): StreamingCommunityTitle? {
        return runCatching {
            val json = JSONObject(extractStreamingCommunityPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            val title = props.optJSONObject("title")?.toStreamingCommunityTitle() ?: return null
            val loadedSeason = props.optJSONObject("loadedSeason")?.toStreamingCommunitySeason()
            if (loadedSeason == null) {
                title
            } else {
                title.copy(seasons = title.seasons.mergeStreamingCommunitySeason(loadedSeason))
            }
        }.getOrNull()
    }

    private suspend fun fetchStreamingCommunitySeason(
        title: StreamingCommunityTitle,
        seasonNumber: Int,
    ): StreamingCommunitySeason? {
        ensureStreamingCommunityHeaders()
        val url = "$streamingCommunityMainUrl/titles/${title.id}-${title.slug}/season-$seasonNumber"
        val text = getCachedText(
            "SC:GET:$url:$streamingCommunityInertiaVersion",
            TmdbCacheProfile.StreamingCommunityDetail,
        ) {
            app.get(
                url,
                headers = streamingCommunityHeaders,
                cacheTime = TmdbCacheProfile.StreamingCommunityDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return runCatching {
            val json = JSONObject(extractStreamingCommunityPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            props.optJSONObject("loadedSeason")?.toStreamingCommunitySeason()
        }.getOrNull()
    }

    private suspend fun fetchStreamingCommunityEpisodePayloads(
        title: StreamingCommunityTitle,
    ): Map<Pair<Int, Int>, StreamingCommunityPlaybackData> {
        if (title.type != "tv") return emptyMap()

        val episodes = linkedMapOf<Pair<Int, Int>, StreamingCommunityPlaybackData>()
        for (season in title.seasons) {
            val seasonWithEpisodes = if (season.episodes.isNotEmpty()) {
                season
            } else {
                fetchStreamingCommunitySeason(title, season.number) ?: season
            }

            seasonWithEpisodes.episodes.forEach { episode ->
                episodes[seasonWithEpisodes.number to episode.number] = StreamingCommunityPlaybackData(
                    iframeUrl = "$streamingCommunityMainUrl/iframe/${title.id}?episode_id=${episode.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = seasonWithEpisodes.number,
                    episodeNumber = episode.number,
                )
            }
        }

        return episodes
    }

    private fun StreamingCommunityTitle.toStreamingCommunityMoviePlayback(): StreamingCommunityPlaybackData? {
        if (type != "movie") return null
        return StreamingCommunityPlaybackData(
            iframeUrl = "$streamingCommunityMainUrl/iframe/$id&canPlayFHD=1",
            type = type,
            tmdbId = tmdbId,
        )
    }

    private suspend fun ensureStreamingCommunityHeaders(forceRefresh: Boolean = false) {
        val hasSessionHeaders = streamingCommunityHeaders["Cookie"].orEmpty().isNotBlank() &&
            streamingCommunityInertiaVersion.isNotBlank()
        if (hasSessionHeaders && !forceRefresh) return

        val response = app.get("$streamingCommunityMainUrl/archive")
        val cookieJar = linkedMapOf<String, String>()
        response.cookies.forEach { cookieJar[it.key] = it.value }

        val csrfResponse = app.get(
            "${streamingCommunityRootUrl}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "$streamingCommunityMainUrl/",
                "X-Requested-With" to "XMLHttpRequest",
            )
        )
        csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

        streamingCommunityHeaders["Cookie"] = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        streamingCommunityXsrfToken = cookieJar["XSRF-TOKEN"]
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            .orEmpty()
        streamingCommunityInertiaVersion = response.document
            .select("#app")
            .attr("data-page")
            .substringAfter("\"version\":\"")
            .substringBefore("\"")
        streamingCommunityHeaders["X-Inertia-Version"] = streamingCommunityInertiaVersion
    }

    private fun extractStreamingCommunityPageJson(payload: String): String? {
        val trimmedPayload = payload.trimStart()
        if (!trimmedPayload.startsWith("<")) return null
        val dataPageRaw = Jsoup.parse(payload).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    private fun JSONObject.toStreamingCommunityTitle(): StreamingCommunityTitle? {
        val id = optNullableInt("id") ?: return null
        val slug = optNullableString("slug") ?: return null
        val name = optNullableString("name") ?: return null
        val type = optNullableString("type") ?: return null
        val seasons = optJSONArray("seasons")?.let { seasonsJson ->
            buildList {
                for (index in 0 until seasonsJson.length()) {
                    seasonsJson.optJSONObject(index)?.toStreamingCommunitySeason()?.let(::add)
                }
            }
        }.orEmpty()

        return StreamingCommunityTitle(
            id = id,
            slug = slug,
            name = name,
            type = type,
            tmdbId = optNullableInt("tmdb_id"),
            year = optNullableString("release_date")?.substringBefore('-')?.toIntOrNull(),
            seasons = seasons,
        )
    }

    private fun JSONObject.toStreamingCommunitySeason(): StreamingCommunitySeason? {
        val id = optNullableInt("id") ?: return null
        val number = optNullableInt("number") ?: return null
        val episodes = optJSONArray("episodes")?.let { episodesJson ->
            buildList {
                for (index in 0 until episodesJson.length()) {
                    episodesJson.optJSONObject(index)?.toStreamingCommunityEpisode()?.let(::add)
                }
            }
        }.orEmpty()

        return StreamingCommunitySeason(
            id = id,
            number = number,
            episodes = episodes,
        )
    }

    private fun JSONObject.toStreamingCommunityEpisode(): StreamingCommunityEpisode? {
        return StreamingCommunityEpisode(
            id = optNullableInt("id") ?: return null,
            number = optNullableInt("number") ?: return null,
        )
    }

    private fun List<StreamingCommunitySeason>.mergeStreamingCommunitySeason(
        loadedSeason: StreamingCommunitySeason,
    ): List<StreamingCommunitySeason> {
        if (none { it.number == loadedSeason.number || it.id == loadedSeason.id }) {
            return this + loadedSeason
        }

        return map { season ->
            if (season.number == loadedSeason.number || season.id == loadedSeason.id) loadedSeason else season
        }
    }

    private fun StreamingCommunityTitle.matchesStreamingCommunityFallback(
        metadata: HubMetadata,
        expectedType: String,
    ): Boolean {
        if (type != expectedType) return false
        val titleMatches = normalizeLookupTitle(name) == normalizeLookupTitle(metadata.title) ||
            normalizeLookupTitle(name) == normalizeLookupTitle(metadata.originalTitle)
        val yearMatches = metadata.year == null || year == null || metadata.year == year
        return titleMatches && yearMatches
    }

    private fun normalizeLookupTitle(title: String?): String {
        return title.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private suspend fun fetchAnimeMappings(): JSONArray {
        animeMappingsMemoryCache?.let { return it }
        val responseText = getCachedText("GET:$animeListMappingUrl", TmdbCacheProfile.AnimeMappings) {
            app.get(
                animeListMappingUrl,
                cacheTime = TmdbCacheProfile.AnimeMappings.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).body.string()
        }

        return JSONArray(responseText).also { animeMappingsMemoryCache = it }
    }

    private suspend fun fetchAnimeSyncIds(tmdbId: String?, isTvSeries: Boolean): List<AnimeSyncIds> {
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return emptyList()
        val memoryCacheKey = "$numericTmdbId:$isTvSeries"
        animeSyncIdsMemoryCache[memoryCacheKey]?.let { return it }

        return runCatching {
            val items = fetchAnimeMappings()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.optNullableInt("themoviedb_id") != numericTmdbId) continue

                    val type = item.optString("type").takeIf(String::isNotBlank)
                    val isMovieMapping = type.equals("Movie", ignoreCase = true)
                    if (isTvSeries == isMovieMapping) continue

                    add(
                        AnimeSyncIds(
                            tmdbId = numericTmdbId,
                            tmdbSeason = item.optJSONObject("season")?.optNullableInt("tmdb"),
                            displaySeason = null,
                            anilistId = item.optNullableInt("anilist_id"),
                            malId = item.optNullableInt("mal_id"),
                            type = type,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .distinctBy { listOf(it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1) }
            .mapIndexed { index, syncIds ->
                syncIds.copy(displaySeason = if (isTvSeries) index + 1 else syncIds.tmdbSeason)
            }
            .also { animeSyncIdsMemoryCache[memoryCacheKey] = it }
    }

    private suspend fun fetchAnimeSyncIdsForAnilist(
        anilistId: Int,
        malId: Int?,
        isTvSeries: Boolean,
    ): List<AnimeSyncIds> {
        val memoryCacheKey = "AL:$anilistId:${malId ?: 0}:$isTvSeries"
        animeSyncIdsMemoryCache[memoryCacheKey]?.let { return it }

        return runCatching {
            val items = fetchAnimeMappings()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val itemAnilistId = item.optNullableInt("anilist_id")
                    val itemMalId = item.optNullableInt("mal_id")
                    val matchesIds = itemAnilistId == anilistId || (malId != null && itemMalId == malId)
                    if (!matchesIds) continue

                    val numericTmdbId = item.optNullableInt("themoviedb_id") ?: continue
                    val type = item.optString("type").takeIf(String::isNotBlank)
                    val isMovieMapping = type.equals("Movie", ignoreCase = true)
                    if (isTvSeries == isMovieMapping) continue

                    add(
                        AnimeSyncIds(
                            tmdbId = numericTmdbId,
                            tmdbSeason = item.optJSONObject("season")?.optNullableInt("tmdb"),
                            displaySeason = null,
                            anilistId = itemAnilistId,
                            malId = itemMalId,
                            type = type,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .distinctBy { listOf(it.tmdbId, it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1) }
            .map { syncIds ->
                if (!isTvSeries) {
                    syncIds
                } else {
                    fetchAnimeSyncIds(syncIds.tmdbId.toString(), isTvSeries = true)
                        .firstOrNull { it.matchesSameAnimeSync(syncIds) }
                        ?: syncIds
                }
            }
            .also { animeSyncIdsMemoryCache[memoryCacheKey] = it }
    }

    private fun AnimeSyncIds.matchesSameAnimeSync(other: AnimeSyncIds): Boolean {
        return tmdbId == other.tmdbId &&
            (
                (anilistId != null && anilistId == other.anilistId) ||
                    (malId != null && malId == other.malId) ||
                    (tmdbSeason != null && tmdbSeason == other.tmdbSeason)
                )
    }

    private fun pickPrimaryAnimeSyncIds(syncIds: List<AnimeSyncIds>): AnimeSyncIds? {
        return syncIds.firstOrNull { it.tmdbSeason == null } ?: syncIds.firstOrNull()
    }

    private fun selectAnimeSyncIds(
        syncIds: List<AnimeSyncIds>,
        selection: HubAnimeSelection?,
    ): List<AnimeSyncIds> {
        selection ?: return syncIds
        return syncIds.filter { syncIdsForSeason ->
            (selection.anilistId != null && syncIdsForSeason.anilistId == selection.anilistId) ||
                (selection.malId != null && syncIdsForSeason.malId == selection.malId) ||
                (selection.displaySeason != null && syncIdsForSeason.displaySeason == selection.displaySeason)
        }.ifEmpty { syncIds }
    }

    private fun parseAnimeSelection(url: String): HubAnimeSelection? {
        val params = parseQueryParams(url)
        val selection = HubAnimeSelection(
            tmdbSeason = params[animeTmdbSeasonParam]?.toIntOrNull(),
            displaySeason = params[animeDisplaySeasonParam]?.toIntOrNull(),
            anilistId = params[animeAnilistParam]?.toIntOrNull(),
            malId = params[animeMalParam]?.toIntOrNull(),
        )

        return selection.takeIf {
            it.tmdbSeason != null || it.displaySeason != null || it.anilistId != null || it.malId != null
        }
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val query = url.substringAfter("?", "").takeIf(String::isNotBlank) ?: return emptyMap()
        return query.split("&").mapNotNull { parameter ->
            val key = parameter.substringBefore("=").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val value = parameter.substringAfter("=", "")
            key to value
        }.toMap()
    }

    private fun buildAnimeSeasonData(episodes: List<Episode>): List<SeasonData> {
        return episodes.mapNotNull { it.season }
            .distinct()
            .sorted()
            .map { season ->
                SeasonData(
                    season = season,
                    name = if (season == 0) "Speciali" else "Stagione $season",
                    displaySeason = season.takeIf { it > 0 },
                )
            }
    }

    private suspend fun fetchAnimeUnitySources(
        metadata: HubMetadata,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeUnityTitleSources> {
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = listOfNotNull(metadata.title, metadata.originalTitle)
            .map { it.replace(Regex("""\(\d{4}\)"""), "").trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        return syncIds.mapNotNull { sync ->
            val variants = findAnimeUnityVariants(sync, titleCandidates)
            if (variants.isEmpty()) return@mapNotNull null

            val subAnime = variants.firstOrNull { !it.isDub }
            val dubAnime = variants.firstOrNull { it.isDub }
            val subPage = subAnime?.let { fetchAnimeUnityPageData(it) }
            val dubPage = dubAnime?.let { fetchAnimeUnityPageData(it) }
            val subSources = buildAnimeUnityEpisodeSources(subPage)
            val dubSources = buildAnimeUnityEpisodeSources(dubPage)

            AnimeUnityTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun findAnimeUnityVariants(
        syncIds: AnimeSyncIds,
        titleCandidates: List<String>,
    ): List<AnimeUnityAnime> {
        val candidates = linkedMapOf<Int, AnimeUnityAnime>()
        titleCandidates.forEach { title ->
            fetchAnimeUnityArchive(title).forEach { anime ->
                if (!candidates.containsKey(anime.id)) {
                    candidates[anime.id] = anime
                }
            }
        }

        val exactMatches = candidates.values.filter { anime -> anime.matches(syncIds) }
        if (exactMatches.isEmpty()) return emptyList()

        val matchedContentKeys = exactMatches.map { it.contentKey() }.toSet()
        return candidates.values
            .filter { it.contentKey() in matchedContentKeys || it.matches(syncIds) }
            .distinctBy(AnimeUnityAnime::id)
            .sortedWith(compareBy<AnimeUnityAnime> { if (it.isDub) 1 else 0 }.thenBy { it.id })
    }

    private suspend fun fetchAnimeUnityArchive(title: String): List<AnimeUnityAnime> {
        ensureAnimeUnityHeaders()
        val body = buildAnimeUnityArchiveBody(title)
        val requestBody = body.toRequestBody("application/json;charset=utf-8".toMediaType())
        val cacheKey = "POST:$animeUnityUrl/archivio/get-animes:$body"
        val text = getCachedText(cacheKey, TmdbCacheProfile.AnimeUnityArchive) {
            app.post(
                "$animeUnityUrl/archivio/get-animes",
                headers = animeUnityHeaders,
                requestBody = requestBody,
                cacheTime = TmdbCacheProfile.AnimeUnityArchive.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }

        return parseAnimeUnityArchive(text)
    }

    private fun buildAnimeUnityArchiveBody(title: String): String {
        return JSONObject().apply {
            put("title", title)
            put("type", false)
            put("year", false)
            put("order", false)
            put("status", false)
            put("genres", false)
            put("season", false)
            put("dubbed", 0)
            put("offset", 0)
        }.toString()
    }

    private suspend fun ensureAnimeUnityHeaders(forceRefresh: Boolean = false) {
        val hasSessionHeaders = animeUnityHeaders["Cookie"].orEmpty().isNotBlank() &&
            animeUnityHeaders["X-CSRF-Token"].orEmpty().isNotBlank()
        if (hasSessionHeaders && !forceRefresh) return

        val response = app.get("$animeUnityUrl/archivio", headers = animeUnityHeaders)
        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies = listOfNotNull(
            response.cookies["XSRF-TOKEN"]?.let { "XSRF-TOKEN=$it" },
            response.cookies["animeunity_session"]?.let { "animeunity_session=$it" },
        ).joinToString("; ")

        animeUnityHeaders.putAll(
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json;charset=utf-8",
                "X-CSRF-Token" to csrfToken,
                "Referer" to animeUnityUrl,
                "Cookie" to cookies,
            )
        )
    }

    private fun parseAnimeUnityArchive(text: String): List<AnimeUnityAnime> {
        return runCatching {
            val records = JSONObject(text).optJSONArray("records") ?: JSONArray()
            buildList {
                for (index in 0 until records.length()) {
                    records.optJSONObject(index)?.toAnimeUnityAnime()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchAnimeUnityPageData(anime: AnimeUnityAnime): AnimeUnityPageData? {
        val url = "$animeUnityUrl/anime/${anime.id}-${anime.slug}"
        val html = getCachedText("GET:$url", TmdbCacheProfile.AnimeUnityDetail) {
            app.get(
                url,
                cacheTime = TmdbCacheProfile.AnimeUnityDetail.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }
        val doc = Jsoup.parse(html, url)
        val videoPlayer = doc.selectFirst("video-player") ?: return null
        val pageAnime = videoPlayer.attr("anime")
            .takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it).toAnimeUnityAnime() }.getOrNull() }
            ?: anime
        val initialEpisodes = parseAnimeUnityEpisodes(videoPlayer.attr("episodes"))
        val totalEpisodes = videoPlayer.attr("episodes_count").toIntOrNull() ?: initialEpisodes.size
        val allEpisodes = fetchAllAnimeUnityEpisodes(pageAnime, initialEpisodes, totalEpisodes)

        return AnimeUnityPageData(
            anime = pageAnime,
            episodes = allEpisodes,
        )
    }

    private suspend fun fetchAllAnimeUnityEpisodes(
        anime: AnimeUnityAnime,
        initialEpisodes: List<AnimeUnityEpisodeInfo>,
        totalEpisodes: Int,
    ): List<AnimeUnityEpisodeInfo> {
        if (totalEpisodes <= 120) return initialEpisodes

        val episodes = initialEpisodes.toMutableList()
        val pageCount = if (totalEpisodes % 120 == 0) totalEpisodes / 120 else (totalEpisodes / 120) + 1
        for (page in 2..pageCount) {
            val startRange = 1 + (page - 1) * 120
            val endRange = if (page == pageCount) totalEpisodes else page * 120
            val infoUrl = "$animeUnityUrl/info_api/${anime.id}/1?start_range=$startRange&end_range=$endRange"
            val text = getCachedText("GET:$infoUrl", TmdbCacheProfile.AnimeUnityDetail) {
                app.get(
                    infoUrl,
                    cacheTime = TmdbCacheProfile.AnimeUnityDetail.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
                ).text
            }
            episodes += parseAnimeUnityEpisodes(JSONObject(text).optJSONArray("episodes")?.toString().orEmpty())
        }

        return episodes.distinctBy { it.id }
    }

    private fun parseAnimeUnityEpisodes(text: String): List<AnimeUnityEpisodeInfo> {
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

    private fun buildAnimeUnityEpisodeSources(pageData: AnimeUnityPageData?): Map<String, String> {
        pageData ?: return emptyMap()
        val baseUrl = "$animeUnityUrl/anime/${pageData.anime.id}-${pageData.anime.slug}"
        return pageData.episodes.mapNotNull { episode ->
            val number = normalizeAnimeUnityEpisodeNumber(episode.number) ?: return@mapNotNull null
            number to "$baseUrl/${episode.id}"
        }.toMap()
    }

    private suspend fun fetchEpisodes(
        doc: Document,
        actualUrl: String,
        animeUnitySources: List<AnimeUnityTitleSources> = emptyList(),
        targetSeason: Int? = null,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData> = emptyMap(),
    ): List<Episode> {
        if (!actualUrl.contains("/tv/")) return emptyList()

        val seasonUrls = targetSeason
            ?.let { listOf(buildTmdbSeasonUrl(actualUrl, it)) }
            ?: fetchSeasonUrls(doc, actualUrl)

        return seasonUrls.flatMap { seasonUrl ->
            val fallbackSeason = extractSeasonNumber(seasonUrl)
            runCatching { getTmdbDocument(seasonUrl, cacheProfile = TmdbCacheProfile.Seasons) }
                .getOrNull()
                ?.let { parseSeasonEpisodes(it, fallbackSeason, animeUnitySources, streamingCommunityEpisodes) }
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

    private fun buildTmdbSeasonUrl(actualUrl: String, seasonNumber: Int): String {
        val baseUrl = stripHubParams(normalizeTmdbUrl(actualUrl)).substringBefore("?").trimEnd('/')
        return normalizeTmdbUrl("$baseUrl/season/$seasonNumber")
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

    private fun parseSeasonEpisodes(
        seasonDoc: Document,
        fallbackSeason: Int?,
        animeUnitySources: List<AnimeUnityTitleSources>,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
    ): List<Episode> {
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
            val streamingCommunityPlayback = seasonNumber?.let { season ->
                episodeNumber?.let { episode -> streamingCommunityEpisodes[season to episode] }
            }
            val sourcePayload = buildHubEpisodePayload(
                tmdbUrl = dataUrl,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                animeUnitySources = animeUnitySources,
                streamingCommunity = streamingCommunityPlayback,
            )
            newEpisode(sourcePayload) {
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

    private fun buildHubEpisodePayload(
        tmdbUrl: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        animeUnitySources: List<AnimeUnityTitleSources>,
        streamingCommunity: StreamingCommunityPlaybackData? = null,
    ): String {
        if (animeUnitySources.isEmpty() && streamingCommunity == null) return tmdbUrl

        val seasonSources = when {
            seasonNumber != null -> animeUnitySources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeUnitySources.takeIf { it.size == 1 }?.firstOrNull()

        val animeUnityPlayback = seasonSources?.playbackForEpisode(episodeNumber?.toString())
        return HubPlaybackData(
            tmdbUrl = tmdbUrl.takeIf(String::isNotBlank),
            animeUnity = animeUnityPlayback,
            streamingCommunity = streamingCommunity,
        ).toJson()
    }

    private fun buildAnimeUnityEpisodes(
        animeUnitySources: List<AnimeUnityTitleSources>,
        seasonNumber: Int?,
        tmdbEpisodes: List<Episode>,
    ): List<Episode> {
        val titleSources = animeUnitySources.firstOrNull() ?: return emptyList()
        val tmdbEpisodesByNumber = tmdbEpisodes.mapNotNull { episode ->
            episode.episode?.let { it to episode }
        }.toMap()

        return titleSources.episodeNumbers().mapNotNull { number ->
            val playback = titleSources.playbackForEpisode(number) ?: return@mapNotNull null
            val episodeNumber = parseWholeEpisodeNumber(number)
            val tmdbEpisode = episodeNumber?.let { tmdbEpisodesByNumber[it] }
            newEpisode(HubPlaybackData(animeUnity = playback).toJson()) {
                this.name = tmdbEpisode?.name ?: "Episodio $number"
                this.season = seasonNumber ?: tmdbEpisode?.season
                this.episode = episodeNumber ?: tmdbEpisode?.episode
                this.posterUrl = tmdbEpisode?.posterUrl
                this.description = tmdbEpisode?.description
                this.runTime = tmdbEpisode?.runTime
                tmdbEpisode?.date?.let { this.date = it }
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

    private val AnimeUnityAnime.isDub: Boolean
        get() = dub == 1 || title.orEmpty().contains("(ITA)") ||
            titleEng.orEmpty().contains("(ITA)") ||
            titleIt.orEmpty().contains("(ITA)")

    private fun AnimeUnityAnime.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId)
    }

    private fun AnimeUnityAnime.contentKey(): String {
        return when {
            anilistId != null -> "anilist:$anilistId"
            malId != null -> "mal:$malId"
            else -> "title:${cleanAnimeUnityTitle(displayTitle()).lowercase(Locale.ROOT)}"
        }
    }

    private fun AnimeUnityAnime.displayTitle(): String {
        return titleIt ?: titleEng ?: title ?: slug
    }

    private fun cleanAnimeUnityTitle(title: String): String {
        return title.replace(" (ITA)", "").trim()
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
        )
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return when (val value = opt(name)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.takeIf { it > 0 }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun buildAnimeUnityPlayerSources(playbackData: AnimeUnityPlaybackData): List<AnimeUnityPlayerSource> {
        val sources = mutableListOf<AnimeUnityPlayerSource>()
        val seen = linkedSetOf<String>()

        fun add(url: String?, label: String) {
            val normalizedUrl = url?.takeIf(String::isNotBlank) ?: return
            if (seen.add(normalizedUrl)) {
                sources += AnimeUnityPlayerSource(label = label, url = normalizedUrl)
            }
        }

        when (playbackData.preferredUrl) {
            playbackData.dubUrl -> {
                add(playbackData.dubUrl, "[DUB]")
                add(playbackData.subUrl, "[SUB]")
            }
            playbackData.subUrl -> {
                add(playbackData.subUrl, "[SUB]")
                add(playbackData.dubUrl, "[DUB]")
            }
            else -> {
                add(playbackData.preferredUrl, "[SOURCE]")
                add(playbackData.subUrl, "[SUB]")
                add(playbackData.dubUrl, "[DUB]")
            }
        }

        return sources
    }

    private suspend fun getAnimeUnityEmbedUrl(playerUrl: String): String? {
        val html = getCachedText("GET:$playerUrl", TmdbCacheProfile.AnimeUnityPlayer) {
            app.get(
                playerUrl,
                cacheTime = TmdbCacheProfile.AnimeUnityPlayer.cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
            ).text
        }

        return Jsoup.parse(html, playerUrl)
            .selectFirst("video-player")
            ?.attr("embed_url")
            ?.takeIf(String::isNotBlank)
    }

    private suspend fun loadStreamingCommunityLinks(
        playbackData: StreamingCommunityPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val iframeSrc = runCatching {
            val html = getCachedText("SC:GET:${playbackData.iframeUrl}", TmdbCacheProfile.StreamingCommunityPlayer) {
                app.get(
                    playbackData.iframeUrl,
                    cacheTime = TmdbCacheProfile.StreamingCommunityPlayer.cacheMinutes,
                    cacheUnit = TimeUnit.MINUTES,
                ).body.string()
            }
            Jsoup.parse(html, playbackData.iframeUrl)
                .selectFirst("iframe")
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

        if (!iframeSrc.isNullOrBlank()) {
            runCatching {
                HubVixCloudExtractor(
                    sourceName = "VixCloud",
                    displayName = "StreamingCommunity - VixCloud",
                ).getUrl(
                    url = iframeSrc,
                    referer = streamingCommunityRootUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            }.onSuccess { found = true }
        }

        val vixSrcUrl = buildStreamingCommunityVixSrcUrl(playbackData)
        if (!vixSrcUrl.isNullOrBlank()) {
            runCatching {
                HubVixSrcExtractor().getUrl(
                    url = vixSrcUrl,
                    referer = "https://vixsrc.to/",
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            }.onSuccess { found = true }
        }

        return found
    }

    private fun buildStreamingCommunityVixSrcUrl(playbackData: StreamingCommunityPlaybackData): String? {
        val tmdbId = playbackData.tmdbId ?: return null
        return if (playbackData.type == "movie") {
            "https://vixsrc.to/movie/$tmdbId"
        } else {
            val seasonNumber = playbackData.seasonNumber ?: return null
            val episodeNumber = playbackData.episodeNumber ?: return null
            "https://vixsrc.to/tv/$tmdbId/$seasonNumber/$episodeNumber"
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
        val recommendationsDoc = runCatching {
            getTmdbDocument(
                "$recommendationsUrl?version=1&translate=false",
                cacheProfile = TmdbCacheProfile.Recommendations,
            )
        }.getOrNull() ?: return emptyList()

        return parseMediaCards(recommendationsDoc, isAnime)
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean {
        return doc.select("span.next a").isNotEmpty() ||
            doc.select("a[href]").any { it.attr("href").contains("page=${page + 1}") }
    }

    companion object {
        private val tmdbTextCache = ExpiringTextCache(maxEntries = 256)
        private val animeSyncIdsMemoryCache = mutableMapOf<String, List<AnimeSyncIds>>()
        private var animeMappingsMemoryCache: JSONArray? = null

        private fun normalizeAnimeUnityEpisodeNumber(number: String?): String? {
            val normalized = number
                ?.trim()
                ?.replace(',', '.')
                ?.takeIf(String::isNotBlank)
                ?: return null
            val numericValue = normalized.toDoubleOrNull() ?: return normalized
            val intValue = numericValue.toInt()

            return if (numericValue == intValue.toDouble()) intValue.toString() else numericValue.toString()
        }

        private fun parseWholeEpisodeNumber(number: String?): Int? {
            val normalized = normalizeAnimeUnityEpisodeNumber(number) ?: return null
            val numericValue = normalized.toDoubleOrNull() ?: return normalized.toIntOrNull()
            val intValue = numericValue.toInt()
            return intValue.takeIf { numericValue == it.toDouble() }
        }

        fun setCacheDirectory(directory: File) {
            tmdbTextCache.setDiskDirectory(directory)
        }
    }
}
