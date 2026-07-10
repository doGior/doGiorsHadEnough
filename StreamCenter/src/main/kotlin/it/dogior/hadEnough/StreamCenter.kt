package it.dogior.hadEnough

import android.content.SharedPreferences
import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
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
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale

class StreamCenter(
    private val sharedPref: SharedPreferences? = null,
    private val searchSection: String = SEARCH_SECTION_MAIN,
) : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = when (searchSection) {
        SEARCH_SECTION_MOVIES -> "StreamCenter Film"
        SEARCH_SECTION_SERIES -> "StreamCenter Serie TV"
        SEARCH_SECTION_ANIME -> "StreamCenter Anime"
        else -> "StreamCenter"
    }
    override var lang = "it"
    override val hasMainPage = searchSection == SEARCH_SECTION_MAIN
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = when (searchSection) {
        SEARCH_SECTION_MOVIES -> setOf(TvType.Movie)
        SEARCH_SECTION_SERIES -> setOf(TvType.TvSeries)
        SEARCH_SECTION_ANIME -> setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        else -> setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    }

    override val supportedSyncNames = if (searchSection == SEARCH_SECTION_MAIN) {
        setOf(SyncIdName.Anilist, SyncIdName.MyAnimeList)
    } else {
        emptySet()
    }

    private val tmdbLanguage = "it-IT"
    private val animeMarker = "streamcenter_media=anime"
    private val animeAnilistParam = "streamcenter_anilist"
    private val animeMalParam = "streamcenter_mal"
    private val animeVariantParam = "streamcenter_variant"
    private val animeTmdbSeasonParam = "streamcenter_tmdb_season"
    private val animeDisplaySeasonParam = "streamcenter_anime_season"
    private val anilistOnlyPath = "/anilist/"
    private val malOnlyPath = "/mal/"
    private val scHomePath = "/sc/"
    private val scHomeTypeParam = "streamcenter_sc_type"
    private val animeUnityUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMEUNITY }
    private val animeWorldUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMEWORLD }
    private val animeSaturnUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMESATURN }
    private val hentaiWorldUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_HENTAIWORLD }
    private val animeListMappingUrl = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"
    private val anilistApiUrl = "https://graphql.anilist.co"
    private val kitsuApiUrl = "https://kitsu.io/api/edge"
    private val jikanApiUrl = "https://api.jikan.moe/v4"
    private val streamingCommunityRootUrl: String
        get() = StreamCenterPlugin.getSourceBaseUrl(sharedPref, StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
            .ifBlank { StreamCenterPlugin.DEFAULT_URL_STREAMINGCOMMUNITY } + "/"
    private val streamingCommunityMainUrl: String
        get() = "${streamingCommunityRootUrl}it"
    private val headers = mapOf(
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )
    private val performanceMode: Boolean
        get() = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref)
    private val sourceGroupTimeoutMs: Long
        get() = if (performanceMode) SOURCE_GROUP_TIMEOUT_PERFORMANCE_MS else SOURCE_GROUP_TIMEOUT_MS
    private val animeSearchQueryLimit: Int
        get() = if (performanceMode) ANIME_SEARCH_QUERY_LIMIT_PERFORMANCE else ANIME_SEARCH_QUERY_LIMIT
    private val auArchiveQueryLimit: Int
        get() = if (performanceMode) AU_ARCHIVE_QUERY_LIMIT_PERFORMANCE else AU_ARCHIVE_QUERY_LIMIT
    private val awDetailCandidateLimit: Int
        get() = if (performanceMode) AW_DETAIL_CANDIDATE_LIMIT_PERFORMANCE else AW_DETAIL_CANDIDATE_LIMIT
    private val animeSaturnDetailCandidateLimit: Int
        get() = if (performanceMode) {
            ANIMESATURN_DETAIL_CANDIDATE_LIMIT_PERFORMANCE
        } else {
            ANIMESATURN_DETAIL_CANDIDATE_LIMIT
        }
    private val animeUnityHeaders = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    private fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore("/").substringBefore(":")
    }
    private var streamingCommunityInertiaVersion = ""
    private var streamingCommunityXsrfToken = ""
    private val streamingCommunityHeaders = mutableMapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to streamingCommunityInertiaVersion,
        "X-Requested-With" to "XMLHttpRequest",
    )

    override val mainPage
        get() = mainPageOf(
            *StreamCenterPlugin.getConfiguredHomeSections(sharedPref)
                .map { it.definition.data to it.title }
                .toTypedArray(),
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val showHomeScores = !performanceMode && StreamCenterPlugin.shouldShowHomeScore(sharedPref)
        val showAnimeDubStatus = !performanceMode && StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref)
        val showAnimeEpisodeNumber = !performanceMode && StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref)
        val limit = StreamCenterPlugin.getHomeSectionCount(sharedPref, data)

        val items = runCatching {
            when {
                data == "au:calendar" -> fetchAnimeUnityCalendarHome(
                    limit,
                    showHomeScores,
                    showAnimeDubStatus,
                    showAnimeEpisodeNumber,
                )
                data == "au:latest" -> fetchAnimeUnityLatestHome(
                    limit,
                    showHomeScores,
                    showAnimeDubStatus,
                    showAnimeEpisodeNumber,
                )
                data == "au:popular" -> fetchAnimeUnityPopularHome(
                    page,
                    limit,
                    showHomeScores,
                    showAnimeDubStatus,
                    showAnimeEpisodeNumber,
                    resolveVariants = !performanceMode,
                )
                data.startsWith("sc:archive:") -> fetchStreamingCommunityArchiveHome(data, limit, showHomeScores)
                data.startsWith("sc:") -> fetchStreamingCommunityHome(data, limit, showHomeScores)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

        val hasNext = data == "au:popular" && page < 5 && items.size >= limit
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false,
            ),
            hasNext = hasNext,
        )
    }


    private data class AnimeUnityHomeItem(
        val id: Int,
        val slug: String,
        val title: String,
        val type: String?,
        val dub: Boolean,
        val score: String?,
        val imageUrl: String?,
        val anilistId: Int?,
        val malId: Int?,
        val availableEpisodes: Int?,
        val episodeNumber: Int?,
    )

    private fun JSONObject.toAnimeUnityHomeItem(episodeNumber: Int? = null): AnimeUnityHomeItem? {
        val id = optNullableInt("id") ?: return null
        val slug = optNullableString("slug") ?: return null
        val title = optNullableString("title_it")
            ?: optNullableString("title_eng")
            ?: optNullableString("title")
            ?: return null
        return AnimeUnityHomeItem(
            id = id,
            slug = slug,
            title = title.replace(" (ITA)", "").trim(),
            type = optNullableString("type"),
            dub = optNullableInt("dub") == 1 || title.contains("(ITA)"),
            score = optNullableString("score"),
            imageUrl = optNullableString("imageurl"),
            anilistId = optNullableInt("anilist_id"),
            malId = optNullableInt("mal_id"),
            availableEpisodes = optNullableInt("episodes_count") ?: optNullableInt("real_episodes_count"),
            episodeNumber = episodeNumber,
        )
    }

    private fun animeHomeRoutingUrl(malId: Int?, anilistId: Int?): String? {
        return when {
            anilistId != null -> markAnilistUrl(anilistId, malId)
            malId != null -> markMalOnlyUrl(malId)
            else -> null
        }
    }

    private fun markAnilistUrl(anilistId: Int, malId: Int? = null): String {
        val params = buildList {
            add(animeMarker)
            add("$animeAnilistParam=$anilistId")
            malId?.let { add("$animeMalParam=$it") }
        }
        return "$mainUrl$anilistOnlyPath$anilistId?${params.joinToString("&")}"
    }

    private fun animeUnityImageHost(): String {
        val host = hostOf(animeUnityUrl)
        return when {
            host == "animeunity.so" -> "img.animeunity.so"
            host.startsWith("www.") -> host.replaceFirst("www.", "img.")
            host.startsWith("img.") -> host
            else -> "img.$host"
        }
    }

    private fun animeUnityPoster(imageUrl: String?): String? {
        val raw = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("http")) return raw
        val fileName = raw.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
        return "https://${animeUnityImageHost()}/anime/$fileName"
    }

    private fun buildGroupedAnimeUnityHomeResponses(
        items: List<AnimeUnityHomeItem>,
        showDubStatus: Boolean,
        showEpisodeNumber: Boolean,
        showScore: Boolean,
        limit: Int,
    ): List<SearchResponse> {
        if (!StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)) {
            val seen = mutableSetOf<String>()
            return items.mapNotNull { item ->
                val baseUrl = animeHomeRoutingUrl(item.malId, item.anilistId) ?: return@mapNotNull null
                val url = "$baseUrl&$animeVariantParam=${if (item.dub) "dub" else "sub"}"
                if (!seen.add(url)) return@mapNotNull null
                val type = when (item.type) {
                    "Movie", "Special" -> TvType.AnimeMovie
                    else -> TvType.Anime
                }
                newAnimeSearchResponse(item.title, url, type) {
                    this.posterUrl = animeUnityPoster(item.imageUrl)
                    if (showDubStatus) {
                        addDubStatus(
                            if (item.dub) DubStatus.Dubbed else DubStatus.Subbed,
                            item.episodeNumber.takeIf { showEpisodeNumber },
                        )
                    }
                    if (showScore) {
                        item.score?.let { this.score = Score.from(it, 10) }
                    }
                }
            }.take(limit)
        }
        val groups = linkedMapOf<String, MutableList<AnimeUnityHomeItem>>()
        for (item in items) {
            val url = animeHomeRoutingUrl(item.malId, item.anilistId) ?: continue
            groups.getOrPut(url) { mutableListOf() } += item
        }
        return groups.entries.take(limit).map { (url, variants) ->
            val primary = variants.firstOrNull { !it.dub } ?: variants.first()
            val type = when (primary.type) {
                "Movie", "Special" -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            newAnimeSearchResponse(primary.title, url, type) {
                this.posterUrl = variants.firstNotNullOfOrNull { animeUnityPoster(it.imageUrl) }
                if (showDubStatus) {
                    addDubStatus(
                        dubExist = variants.any { it.dub },
                        subExist = variants.any { !it.dub },
                        dubEpisodes = variants.filter { it.dub }.mapNotNull { it.episodeNumber }.maxOrNull()
                            .takeIf { showEpisodeNumber },
                        subEpisodes = variants.filter { !it.dub }.mapNotNull { it.episodeNumber }.maxOrNull()
                            .takeIf { showEpisodeNumber },
                    )
                }
                if (showScore) {
                    variants.firstNotNullOfOrNull { it.score }?.let {
                        this.score = Score.from(it, 10)
                    }
                }
            }
        }
    }

    private suspend fun fetchAnimeUnityHtml(path: String): Document {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
        val url = "$animeUnityUrl$path"
        val html = getCachedText("GET:$url", TmdbCacheProfile.AnimeUnityHome) {
            app.get(url, headers = headers).text
        }
        return Jsoup.parse(html, url)
    }

    private suspend fun fetchAnimeUnityCalendarHome(
        limit: Int,
        showScore: Boolean,
        showDubStatus: Boolean,
        showEpisodeNumber: Boolean,
    ): List<SearchResponse> {
        val today = normalizeDayName(currentItalianCalendarDayName())
        val doc = fetchAnimeUnityHtml("/calendario")
        val items = doc.select("calendario-item").mapNotNull { element ->
            val json = element.attr("a").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@mapNotNull null
            if (normalizeDayName(obj.optNullableString("day")) != today) return@mapNotNull null
            val releasedEpisodes = element.attr("episodes_count").trim().toIntOrNull()
            obj.toAnimeUnityHomeItem(episodeNumber = releasedEpisodes?.plus(1))
        }
        return buildGroupedAnimeUnityHomeResponses(
            items = items,
            showDubStatus = showDubStatus,
            showEpisodeNumber = showEpisodeNumber,
            showScore = showScore,
            limit = limit,
        )
    }

    private suspend fun fetchAnimeUnityLatestHome(
        limit: Int,
        showScore: Boolean,
        showDubStatus: Boolean,
        showEpisodeNumber: Boolean,
    ): List<SearchResponse> {
        val doc = fetchAnimeUnityHtml("/")
        val json = doc.selectFirst("#ultimi-episodi layout-items")?.attr("items-json").orEmpty()
        if (json.isBlank()) return emptyList()
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val items = buildList {
            for (index in 0 until data.length()) {
                val entry = data.optJSONObject(index) ?: continue
                val episodeNumber = parseWholeEpisodeNumber(entry.optNullableString("number"))
                val animeObj = entry.optJSONObject("anime") ?: continue
                animeObj.toAnimeUnityHomeItem(episodeNumber = episodeNumber)?.let(::add)
            }
        }
        return buildGroupedAnimeUnityHomeResponses(
            items = items,
            showDubStatus = showDubStatus,
            showEpisodeNumber = showEpisodeNumber,
            showScore = showScore,
            limit = limit,
        )
    }

    private suspend fun fetchAnimeUnityPopularHome(
        page: Int,
        limit: Int,
        showScore: Boolean,
        showDubStatus: Boolean,
        showEpisodeNumber: Boolean,
        resolveVariants: Boolean = true,
    ): List<SearchResponse> {
        val items = mutableListOf<AnimeUnityHomeItem>()
        var currentPage = page
        while (currentPage <= 5) {
            val doc = fetchAnimeUnityHtml("/top-anime?order=most_viewed&page=$currentPage")
            val json = doc.selectFirst("top-anime")?.attr("animes").orEmpty()
            if (json.isBlank()) break
            val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: break
            if (data.length() == 0) break

            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: continue
                val item = obj.toAnimeUnityHomeItem() ?: continue
                items += if (resolveVariants) {
                    fetchAnimeUnityPopularHomeVariants(item)
                } else {
                    listOf(item.copy(episodeNumber = item.availableEpisodes))
                }
                val groupedCount = groupedAnimeUnityHomeCount(items)
                if (groupedCount >= limit) {
                    return buildGroupedAnimeUnityHomeResponses(
                        items = items,
                        showDubStatus = showDubStatus,
                        showEpisodeNumber = showEpisodeNumber,
                        showScore = showScore,
                        limit = limit,
                    )
                }
            }
            currentPage++
        }
        return buildGroupedAnimeUnityHomeResponses(
            items = items,
            showDubStatus = showDubStatus,
            showEpisodeNumber = showEpisodeNumber,
            showScore = showScore,
            limit = limit,
        )
    }

    private fun groupedAnimeUnityHomeCount(items: List<AnimeUnityHomeItem>): Int {
        return items.mapNotNull { animeHomeRoutingUrl(it.malId, it.anilistId) }
            .distinct()
            .size
    }

    private suspend fun fetchAnimeUnityPopularHomeVariants(item: AnimeUnityHomeItem): List<AnimeUnityHomeItem> {
        val variants = runCatching {
            val syncIds = AnimeSyncIds(
                tmdbId = 0,
                tmdbSeason = null,
                displaySeason = null,
                anilistId = item.anilistId,
                malId = item.malId,
                kitsuId = null,
                type = item.type,
            )
            findAnimeUnityVariants(
                syncIds,
                listOf(item.title),
                exactTitleKeys = setOf(sourceTitleDedupKey(item.title)),
                allowTitleFallback = true,
            )
                .map { anime ->
                    AnimeUnityHomeItem(
                        id = anime.id,
                        slug = anime.slug,
                        title = cleanAnimeUnityTitle(anime.displayTitle()),
                        type = anime.type,
                        dub = anime.isDub,
                        score = anime.score ?: item.score,
                        imageUrl = anime.imageUrl ?: item.imageUrl,
                        anilistId = anime.anilistId,
                        malId = anime.malId,
                        availableEpisodes = anime.episodesCount ?: anime.realEpisodesCount ?: item.availableEpisodes,
                        episodeNumber = anime.episodesCount ?: anime.realEpisodesCount ?: item.availableEpisodes,
                    )
                }
        }.getOrDefault(emptyList())
        return variants.ifEmpty {
            listOf(item.copy(episodeNumber = item.availableEpisodes))
        }
    }

    private fun currentItalianCalendarDayName(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Lunedì"
            Calendar.TUESDAY -> "Martedì"
            Calendar.WEDNESDAY -> "Mercoledì"
            Calendar.THURSDAY -> "Giovedì"
            Calendar.FRIDAY -> "Venerdì"
            Calendar.SATURDAY -> "Sabato"
            else -> "Domenica"
        }
    }

    private fun normalizeDayName(day: String?): String {
        return java.text.Normalizer.normalize(day.orEmpty(), java.text.Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private suspend fun fetchStreamingCommunityHome(
        data: String,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        val parts = data.split(":")
        val type = parts.getOrNull(1) ?: "tv"
        val sliderName = parts.getOrNull(2) ?: "trending"
        val pageUrl = if (type == "movie") "$streamingCommunityMainUrl/movies" else streamingCommunityMainUrl

        val props = fetchStreamingCommunityPageProps("SC:HOME:$pageUrl", pageUrl) ?: return emptyList()
        val sliders = props.optJSONArray("sliders") ?: return emptyList()
        val titles = (0 until sliders.length())
            .asSequence()
            .mapNotNull { sliders.optJSONObject(it) }
            .firstOrNull { it.optNullableString("name") == sliderName }
            ?.optJSONArray("titles")
            ?: return emptyList()

        return titles.toStreamingCommunityHomeResponses(type, streamingCommunityCdnUrl(props), limit, showScore)
    }

    private suspend fun fetchStreamingCommunityArchiveHome(
        data: String,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        val parts = data.split(":")
        val type = parts.getOrNull(2) ?: "movie"
        val genreId = parts.getOrNull(3)?.toIntOrNull() ?: return emptyList()
        val pageUrl = "$streamingCommunityMainUrl/archive?type=$type&genre%5B%5D=$genreId&sort=views"

        val props = fetchStreamingCommunityPageProps("SC:ARCHIVE:$pageUrl", pageUrl) ?: return emptyList()
        val titles = props.optJSONArray("titles") ?: return emptyList()
        return titles.toStreamingCommunityHomeResponses(type, streamingCommunityCdnUrl(props), limit, showScore)
    }

    private suspend fun fetchStreamingCommunityPageProps(cacheKey: String, pageUrl: String): JSONObject? {
        val text = getCachedText(cacheKey, TmdbCacheProfile.StreamingCommunityHome) {
            app.get(pageUrl, headers = headers).body.string()
        }
        val json = extractStreamingCommunityPageJson(text)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        return json.optJSONObject("props") ?: json
    }

    private fun streamingCommunityCdnUrl(props: JSONObject): String {
        return props.optNullableString("cdn_url")?.trimEnd('/')
            ?: "https://cdn.${hostOf(streamingCommunityRootUrl)}"
    }

    private fun JSONArray.toStreamingCommunityHomeResponses(
        type: String,
        cdnUrl: String,
        limit: Int,
        showScore: Boolean,
    ): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        return buildList {
            for (index in 0 until length()) {
                val title = optJSONObject(index) ?: continue
                if (title.optNullableString("type") != type) continue
                val response = title.toStreamingCommunityHomeResponse(type, cdnUrl, showScore) ?: continue
                if (seen.add(response.url)) add(response)
                if (size >= limit) break
            }
        }
    }

    private fun JSONObject.toStreamingCommunityHomeResponse(
        type: String,
        cdnUrl: String,
        showScore: Boolean,
    ): SearchResponse? {
        val id = optNullableInt("id") ?: return null
        val slug = optNullableString("slug") ?: return null
        val name = optNullableString("name") ?: return null
        val poster = optJSONArray("images")?.streamingCommunityPosterFilename()
            ?.let { "$cdnUrl/images/$it" }
        val scoreValue = optNullableString("score")
        val url = "$mainUrl$scHomePath$id-$slug?$scHomeTypeParam=$type"
        return if (type == "tv") {
            newTvSeriesSearchResponse(name, url, TvType.TvSeries) {
                this.posterUrl = poster
                if (showScore) scoreValue?.let { this.score = Score.from(it, 10) }
            }
        } else {
            newMovieSearchResponse(name, url, TvType.Movie) {
                this.posterUrl = poster
                if (showScore) scoreValue?.let { this.score = Score.from(it, 10) }
            }
        }
    }

    private fun JSONArray.streamingCommunityPosterFilename(): String? {
        var fallback: String? = null
        for (index in 0 until length()) {
            val image = optJSONObject(index) ?: continue
            val filename = image.optNullableString("filename") ?: continue
            if (image.optNullableString("type") == "poster") return filename
            if (fallback == null) fallback = filename
        }
        return fallback
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchSearchResults(query, 1).first
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = fetchSearchResults(query, page)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return fetchSearchResults(query, 1).first
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        val numericId = id.substringBefore("/").trim().toIntOrNull() ?: return null
        return when (name) {
            SyncIdName.Anilist -> markAnilistUrl(numericId)
            SyncIdName.MyAnimeList -> markMalOnlyUrl(numericId)
            else -> null
        }
    }

    private suspend fun fetchSearchResults(query: String, page: Int): Pair<List<SearchResponse>, Boolean> = coroutineScope {
        val empty = emptyList<SearchResponse>() to false
        if (query.isBlank() || page < 1) return@coroutineScope empty
        val splitByType = StreamCenterPlugin.shouldSplitSearchResultsByType(sharedPref)

        when (searchSection) {
            SEARCH_SECTION_MOVIES, SEARCH_SECTION_SERIES -> {
                if (!splitByType) return@coroutineScope empty
                val (scItems, scHasNext) = runCatching { searchStreamingCommunity(query, page) }
                    .getOrDefault(empty)
                val wantedType = if (searchSection == SEARCH_SECTION_MOVIES) {
                    TvType.Movie
                } else {
                    TvType.TvSeries
                }
                scItems.filter { it.type == wantedType }.distinctBy { it.url } to scHasNext
            }
            SEARCH_SECTION_ANIME -> {
                if (!splitByType) return@coroutineScope empty
                runCatching { searchAnimeUnity(query, page) }.getOrDefault(empty)
            }
            else -> {
                if (splitByType) return@coroutineScope empty
                val streamingCommunityDeferred = async(Dispatchers.IO) {
                    runCatching { searchStreamingCommunity(query, page) }
                        .getOrDefault(empty)
                }
                val animeUnityDeferred = async(Dispatchers.IO) {
                    runCatching { searchAnimeUnity(query, page) }
                        .getOrDefault(empty)
                }

                val (scItems, scHasNext) = streamingCommunityDeferred.await()
                val (animeItems, animeHasNext) = animeUnityDeferred.await()
                val items = interleaveSearchResults(scItems, animeItems).distinctBy { it.url }
                items to (scHasNext || animeHasNext)
            }
        }
    }

    private fun interleaveSearchResults(vararg lists: List<SearchResponse>): List<SearchResponse> {
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        return buildList {
            for (index in 0 until maxSize) {
                for (list in lists) {
                    list.getOrNull(index)?.let(::add)
                }
            }
        }
    }

    private suspend fun searchStreamingCommunity(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val empty = emptyList<SearchResponse>() to false
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        val text = getCachedText(
            "SC:SEARCHUI:$page:${query.trim().lowercase(Locale.ROOT)}",
            TmdbCacheProfile.StreamingCommunitySearch,
        ) {
            app.get(
                "$streamingCommunityMainUrl/search",
                params = mapOf("q" to query, "page" to page.toString()),
                headers = headers,
            ).body.string()
        }
        val json = extractStreamingCommunityPageJson(text)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return empty
        val props = json.optJSONObject("props") ?: return empty
        val cdnUrl = props.optNullableString("cdn_url")?.trimEnd('/')
            ?: "https://cdn.${hostOf(streamingCommunityRootUrl)}"
        val titles = props.optJSONArray("titles") ?: return empty

        val seen = mutableSetOf<String>()
        val items = buildList {
            for (index in 0 until titles.length()) {
                val title = titles.optJSONObject(index) ?: continue
                val type = title.optNullableString("type") ?: "tv"
                val response = title.toStreamingCommunityHomeResponse(type, cdnUrl, showScore = true)
                    ?: continue
                if (seen.add(response.url)) add(response)
            }
        }
        return items to (titles.length() >= SC_SEARCH_PAGE_SIZE)
    }

    private suspend fun searchAnimeUnity(
        query: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val records = fetchAnimeUnityArchive(query, offset = (page - 1) * AU_ARCHIVE_BATCH_SIZE)
        if (!StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref)) {
            val seen = mutableSetOf<String>()
            val items = records.mapNotNull { anime ->
                val baseUrl = animeHomeRoutingUrl(anime.malId, anime.anilistId) ?: return@mapNotNull null
                val url = "$baseUrl&$animeVariantParam=${if (anime.isDub) "dub" else "sub"}"
                if (!seen.add(url)) return@mapNotNull null
                val type = if (anime.type.equals("Movie", ignoreCase = true)) {
                    TvType.AnimeMovie
                } else {
                    TvType.Anime
                }
                newAnimeSearchResponse(cleanAnimeUnityTitle(anime.displayTitle()), url, type) {
                    this.posterUrl = animeUnityPoster(anime.imageUrl)
                    this.year = anime.year
                    anime.score?.let { this.score = Score.from(it, 10) }
                    addDubStatus(dubExist = anime.isDub, subExist = !anime.isDub)
                }
            }
            return items to (records.size >= AU_ARCHIVE_BATCH_SIZE)
        }
        val groups = linkedMapOf<String, MutableList<AnimeUnityAnime>>()
        records.forEach { anime ->
            val url = animeHomeRoutingUrl(anime.malId, anime.anilistId) ?: return@forEach
            groups.getOrPut(url) { mutableListOf() } += anime
        }
        val items = groups.map { (url, variants) ->
            val primary = variants.firstOrNull { !it.isDub } ?: variants.first()
            val type = if (primary.type.equals("Movie", ignoreCase = true)) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
            newAnimeSearchResponse(cleanAnimeUnityTitle(primary.displayTitle()), url, type) {
                this.posterUrl = variants.firstNotNullOfOrNull { animeUnityPoster(it.imageUrl) }
                this.year = variants.firstNotNullOfOrNull { it.year }
                variants.firstNotNullOfOrNull { it.score }?.let { this.score = Score.from(it, 10) }
                addDubStatus(
                    dubExist = variants.any { it.isDub },
                    subExist = variants.any { !it.isDub },
                )
            }
        }
        return items to (records.size >= AU_ARCHIVE_BATCH_SIZE)
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains(scHomePath)) {
            return loadStreamingCommunityHomeTitle(url)
        }
        if (isAnilistOnlyUrl(url)) {
            val anilistId = extractAnilistIdFromText(url)
            val malId = parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return loadAnilistMedia(anilistId, malId)
        }
        if (isMalOnlyUrl(url)) {
            val malId = extractMalIdFromText(url)
                ?: parseQueryParams(url)[animeMalParam]?.toIntOrNull()
            return loadAnilistMedia(null, malId)
        }

        val actualUrl = normalizeTmdbUrl(url)
        if (actualUrl.contains(animeMarker)) {
            val selection = parseAnimeSelection(actualUrl)
            var anilistId = selection?.anilistId
            var malId = selection?.malId
            if (anilistId == null && malId == null) {
                val primary = pickPrimaryAnimeSyncIds(
                    selectAnimeSyncIds(
                        fetchAnimeSyncIds(extractTmdbId(actualUrl), actualUrl.contains("/tv/")),
                        selection,
                    )
                )
                anilistId = primary?.anilistId
                malId = primary?.malId
            }
            if (anilistId != null || malId != null) {
                return loadAnilistMedia(anilistId, malId)
            }
        }

        return loadTmdbMedia(actualUrl)
    }

    private suspend fun loadTmdbMedia(
        actualUrl: String,
        scHint: StreamingCommunityTitle? = null,
    ): LoadResponse {
        val doc = getTmdbDocument(actualUrl, cacheProfile = TmdbCacheProfile.Detail)
        val isTvSeries = actualUrl.contains("/tv/")
        val metadata = buildMetadata(doc, actualUrl, isAnime = false)
        val streamingCommunityTitle = scHint
            ?: if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)) {
                fetchStreamingCommunityTitle(metadata, isTvSeries)
            } else {
                null
            }
        val sc = streamingCommunityTitle

        val title = metadata.title.takeIf { it.isNotBlank() && it != "Sconosciuto" }
            ?: sc?.name ?: metadata.title
        val poster = metadata.poster ?: streamingCommunityImageUrl(sc?.posterFilename)
        val background = metadata.background ?: streamingCommunityImageUrl(sc?.backgroundFilename)
        val plot = metadata.plot ?: sc?.plot
        val tags = metadata.tags.distinctBy { it.lowercase(Locale.ROOT) }
            .ifEmpty { sc?.genres.orEmpty() }
        val year = metadata.year ?: sc?.year
        val score = metadata.score ?: sc?.score
        val contentRating = metadata.contentRating ?: sc?.age?.let { "$it+" }

        return if (isTvSeries) {
            val streamingCommunityEpisodes = streamingCommunityTitle
                ?.let { fetchStreamingCommunityEpisodePayloads(it) }
                .orEmpty()
            val episodes = fetchEpisodes(
                doc = doc,
                actualUrl = actualUrl,
                streamingCommunityEpisodes = streamingCommunityEpisodes,
                fallbackPoster = poster.takeIf { !performanceMode },
                minimalMetadata = performanceMode,
            )
            newTvSeriesLoadResponse(
                title,
                actualUrl,
                TvType.TvSeries,
                episodes,
            ) {
                if (!performanceMode) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.logoUrl = streamingCommunityImageUrl(sc?.logoFilename)
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.actors = metadata.people
                    this.recommendations = metadata.recommendations
                    this.contentRating = contentRating
                    this.showStatus = metadata.showStatus ?: mapStreamingCommunityStatus(sc?.status)
                    this.comingSoon = metadata.comingSoon
                }
                metadata.tmdbId?.let { addTMDbId(it) }
                sc?.imdbId?.let { addImdbId(it) }
                if (!performanceMode) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(score)
                }
            }
        } else {
            val moviePlaybackData = StreamCenterPlaybackData(
                tmdbUrl = actualUrl,
                streamingCommunity = streamingCommunityTitle?.toStreamingCommunityMoviePlayback(),
            )
            newMovieLoadResponse(
                title,
                actualUrl,
                TvType.Movie,
                dataUrl = moviePlaybackData.toJson(),
            ) {
                if (!performanceMode) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.logoUrl = streamingCommunityImageUrl(sc?.logoFilename)
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.duration = metadata.duration ?: sc?.runtime
                    this.actors = metadata.people
                    this.recommendations = metadata.recommendations
                    this.contentRating = contentRating
                    this.comingSoon = metadata.comingSoon
                }
                metadata.tmdbId?.let { addTMDbId(it) }
                sc?.imdbId?.let { addImdbId(it) }
                if (!performanceMode) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(score)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playbackData = runCatching { parseJson<StreamCenterPlaybackData>(data) }.getOrNull()
        val tasksBySource = linkedMapOf<String, MutableList<suspend () -> Boolean>>()
        fun addTask(sourceKey: String, task: suspend () -> Boolean) {
            tasksBySource.getOrPut(sourceKey) { mutableListOf() } += task
        }

        playbackData?.animeUnity
            ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) }
            ?.let { animeUnityPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) {
                    loadAnimeUnityLinks(
                        playbackData = animeUnityPlayback,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) {
            playbackData?.animeWorld.orEmpty().forEach { animeWorldPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD) {
                    loadAnimeWorldLink(
                        playbackData = animeWorldPlayback,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }
        }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) {
            playbackData?.animeSaturn.orEmpty().forEach { animeSaturnPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN) {
                    loadAnimeSaturnLink(
                        playbackData = animeSaturnPlayback,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }
        }

        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)) {
            playbackData?.hentaiWorld.orEmpty().forEach { hentaiWorldPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD) {
                    loadHentaiWorldLink(
                        playbackData = hentaiWorldPlayback,
                        callback = callback,
                    )
                }
            }
        }

        playbackData?.streamingCommunity
            ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) }
            ?.let { streamingCommunityPlayback ->
                addTask(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) {
                    loadStreamingCommunityLinks(
                        playbackData = streamingCommunityPlayback,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            }

        val priorityOrder = StreamCenterPlugin.getSourcePriorityOrder(sharedPref)
        val orderedKeys = tasksBySource.keys.sortedBy { key ->
            priorityOrder.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        var loadedAny = false
        for (sourceKey in orderedKeys) {
            val sourceLoaded = withTimeoutOrNull(sourceGroupTimeoutMs) {
                runParallelSourceTasks(tasksBySource[sourceKey].orEmpty())
            } ?: false
            loadedAny = loadedAny || sourceLoaded
            if (performanceMode && loadedAny) break
        }
        return loadedAny
    }

    private suspend fun runParallelSourceTasks(tasks: List<suspend () -> Boolean>): Boolean {
        if (tasks.isEmpty()) return false
        return supervisorScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    runCatching { task() }.getOrDefault(false)
                }
            }.awaitAll().any { it }
        }
    }

    private suspend fun <T, R : Any> List<T>.mapChunkedParallel(
        chunkSize: Int,
        transform: suspend (T) -> R?,
    ): List<R> {
        val results = mutableListOf<R>()
        for (chunk in chunked(chunkSize)) {
            results += coroutineScope {
                chunk.map { item ->
                    async(Dispatchers.IO) {
                        runCatching { transform(item) }.getOrNull()
                    }
                }.awaitAll()
            }.filterNotNull()
        }
        return results
    }

    private fun isSourceEnabled(prefKey: String): Boolean {
        return StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, prefKey)
    }

    private suspend fun ensureUpdatedSourceDomain(prefKey: String) {
        if (!StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref)) return
        synchronized(checkedSourceDomains) {
            if (!checkedSourceDomains.add(prefKey)) return
        }
        val result = runCatching {
            val baseUrl = StreamCenterPlugin.getSourceBaseUrl(sharedPref, prefKey)
            if (baseUrl.isBlank()) return@runCatching false
            val response = app.get(baseUrl, headers = headers, timeout = 15L)
            val finalUrl = response.url
            val newHost = hostOf(finalUrl)
            val moved = response.code in 200..299 &&
                finalUrl.startsWith("http") &&
                newHost.isNotBlank() &&
                !newHost.equals(hostOf(baseUrl), ignoreCase = true)
            if (moved) {
                val scheme = finalUrl.substringBefore("://")
                StreamCenterPlugin.setSourceBaseUrl(sharedPref, prefKey, "$scheme://$newHost")
            }
            moved
        }
        when {
            result.isFailure -> synchronized(checkedSourceDomains) { checkedSourceDomains.remove(prefKey) }
            result.getOrDefault(false) -> resetSourceSession(prefKey)
        }
    }

    private fun resetSourceSession(prefKey: String) {
        when (prefKey) {
            StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY -> {
                sharedPref?.edit()?.remove(PREF_SC_SESSION)?.apply()
                applyStreamingCommunitySession(cookie = "", xsrfToken = "", inertiaVersion = "")
            }
            StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY -> {
                sharedPref?.edit()?.remove(PREF_AU_SESSION)?.apply()
                applyAnimeUnitySession(cookie = "", csrfToken = "")
            }
        }
    }

    private data class StreamCenterMetadata(
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

    private data class AnilistMetadata(
        val title: String?,
        val score: String?,
        val titleCandidates: List<String> = emptyList(),
    )

    private enum class TmdbCacheProfile(val ttlMs: Long) {
        Detail(7L * 24L * 60L * 60L * 1000L),
        Seasons(24L * 60L * 60L * 1000L),
        Recommendations(7L * 24L * 60L * 60L * 1000L),
        AnimeMappings(7L * 24L * 60L * 60L * 1000L),
        AnimeUnityArchive(24L * 60L * 60L * 1000L),
        AnimeUnityDetail(24L * 60L * 60L * 1000L),
        AnimeUnityPlayer(60L * 60L * 1000L),
        AnimeWorldSearch(24L * 60L * 60L * 1000L),
        AnimeWorldDetail(24L * 60L * 60L * 1000L),
        AnimeWorldPlayer(60L * 60L * 1000L),
        AnimeSaturnSearch(24L * 60L * 60L * 1000L),
        AnimeSaturnDetail(24L * 60L * 60L * 1000L),
        AnimeSaturnPlayer(10L * 60L * 1000L),
        HentaiWorldSearch(24L * 60L * 60L * 1000L),
        HentaiWorldDetail(24L * 60L * 60L * 1000L),
        HentaiWorldPlayer(60L * 60L * 1000L),
        TmdbScore(7L * 24L * 60L * 60L * 1000L),
        StreamingCommunitySession(24L * 60L * 60L * 1000L),
        StreamingCommunitySearch(6L * 60L * 60L * 1000L),
        StreamingCommunityDetail(24L * 60L * 60L * 1000L),
        StreamingCommunityPlayer(60L * 60L * 1000L),
        AnimeUnitySession(12L * 60L * 60L * 1000L),
        AnimeUnityHome(60L * 60L * 1000L),
        StreamingCommunityHome(60L * 60L * 1000L),
        AnilistDetail(7L * 24L * 60L * 60L * 1000L)
    }

    private data class TextCacheEntry(
        val text: String,
        val expiresAtMs: Long,
    )

    private data class AnimeEpisodeMetadataCacheEntry(
        val episodes: List<Episode>,
        val maxEpisodeNumber: Int,
        val expiresAtMs: Long,
    )

    private class ExpiringTextCache(private val maxEntries: Int) {
        private val entries = object : LinkedHashMap<String, TextCacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextCacheEntry>?): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        fun get(key: String, allowExpired: Boolean = false): String? {
            val entry = entries[key] ?: return null
            if (allowExpired || entry.expiresAtMs > System.currentTimeMillis()) {
                return entry.text
            }
            entries.remove(key)
            return null
        }

        @Synchronized
        fun put(key: String, text: String, ttlMs: Long) {
            entries[key] = TextCacheEntry(
                text = text,
                expiresAtMs = System.currentTimeMillis() + ttlMs,
            )
        }

        @Synchronized
        fun clear() {
            entries.clear()
        }
    }

    private data class AnimeSyncIds(
        val tmdbId: Int,
        val tmdbSeason: Int?,
        val displaySeason: Int?,
        val anilistId: Int?,
        val malId: Int?,
        val kitsuId: Int?,
        val type: String?,
    )

    private data class StreamCenterAnimeSelection(
        val tmdbSeason: Int?,
        val displaySeason: Int?,
        val anilistId: Int?,
        val malId: Int?,
    )

    private data class StreamCenterPlaybackData(
        val tmdbUrl: String? = null,
        val animeUnity: AnimeUnityPlaybackData? = null,
        val animeWorld: List<AnimeWorldPlaybackData> = emptyList(),
        val animeSaturn: List<AnimeSaturnPlaybackData> = emptyList(),
        val hentaiWorld: List<HentaiWorldPlaybackData> = emptyList(),
        val streamingCommunity: StreamingCommunityPlaybackData? = null,
    )

    private data class ResolvedLoadSources(
        val anilistMetadata: AnilistMetadata? = null,
        val animeUnitySources: List<AnimeUnityTitleSources> = emptyList(),
        val animeWorldSources: List<AnimeWorldTitleSources> = emptyList(),
        val animeSaturnSources: List<AnimeSaturnTitleSources> = emptyList(),
        val hentaiWorldSources: List<HentaiWorldTitleSources> = emptyList(),
    )

    private data class AnimeUnityPlaybackData(
        val preferredUrl: String,
        val subUrl: String? = null,
        val dubUrl: String? = null,
    )

    private data class AnimeWorldPlaybackData(
        val label: String,
        val pageUrl: String,
        val episodeToken: String,
    )

    private data class AnimeSaturnPlaybackData(
        val label: String,
        val watchUrl: String,
    )

    private data class HentaiWorldPlaybackData(
        val watchUrl: String,
    )

    private data class StreamingCommunityTitle(
        val id: Int,
        val slug: String,
        val name: String,
        val type: String,
        val tmdbId: Int?,
        val imdbId: String? = null,
        val year: Int?,
        val seasons: List<StreamingCommunitySeason>,
        val plot: String? = null,
        val score: String? = null,
        val runtime: Int? = null,
        val genres: List<String> = emptyList(),
        val status: String? = null,
        val age: Int? = null,
        val posterFilename: String? = null,
        val backgroundFilename: String? = null,
        val logoFilename: String? = null,
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
        val plot: String? = null,
        val imageUrl: String? = null,
        val score: String? = null,
        val type: String? = null,
        val year: Int? = null,
    )

    private data class AnimeUnityEpisodeInfo(
        val id: Int,
        val number: String,
    )

    private data class AnimeUnityPageData(
        val anime: AnimeUnityAnime,
        val episodes: List<AnimeUnityEpisodeInfo>,
    )

    private data class AnimeWorldSearchItem(
        val url: String,
        val title: String,
        val otherTitle: String?,
        val isDub: Boolean,
    )

    private data class AnimeWorldPageData(
        val searchItem: AnimeWorldSearchItem,
        val anilistId: Int?,
        val malId: Int?,
        val kitsuId: Int?,
        val episodeSources: Map<String, AnimeWorldPlaybackData>,
    )

    private data class AnimeWorldEpisodeInfo(
        val number: String,
        val token: String,
    )

    private data class AnimeWorldTitleSources(
        val syncIds: AnimeSyncIds,
        val subSources: Map<String, AnimeWorldPlaybackData>,
        val dubSources: Map<String, AnimeWorldPlaybackData>,
    ) {
        fun playbacksForEpisode(number: String?): List<AnimeWorldPlaybackData> {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return emptyList()
            val sources = mutableListOf<AnimeWorldPlaybackData>()
            subSources[normalizedNumber]?.let(sources::add)
            dubSources[normalizedNumber]?.let(sources::add)
            return sources.distinctBy { "${it.label}:${it.episodeToken}:${it.pageUrl}" }
        }

        fun firstPlaybacks(): List<AnimeWorldPlaybackData> {
            val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
            return playbacksForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> = sortedEpisodeNumbers(subSources, dubSources)
    }

    private data class AnimeSaturnSearchItem(
        val url: String,
        val title: String,
        val isDub: Boolean,
        val score: Int,
    )

    private data class AnimeSaturnPageData(
        val searchItem: AnimeSaturnSearchItem,
        val anilistId: Int?,
        val malId: Int?,
        val kitsuId: Int?,
        val episodeSources: Map<String, AnimeSaturnPlaybackData>,
    )

    private data class AnimeSaturnTitleSources(
        val syncIds: AnimeSyncIds,
        val subSources: Map<String, AnimeSaturnPlaybackData>,
        val dubSources: Map<String, AnimeSaturnPlaybackData>,
    ) {
        fun playbacksForEpisode(number: String?): List<AnimeSaturnPlaybackData> {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return emptyList()
            val sources = mutableListOf<AnimeSaturnPlaybackData>()
            subSources[normalizedNumber]?.let(sources::add)
            dubSources[normalizedNumber]?.let(sources::add)
            return sources.distinctBy { "${it.label}:${it.watchUrl}" }
        }

        fun firstPlaybacks(): List<AnimeSaturnPlaybackData> {
            val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
            return playbacksForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> = sortedEpisodeNumbers(subSources, dubSources)
    }

    private data class HentaiWorldSearchItem(
        val url: String,
        val title: String,
    )

    private data class HentaiWorldTitleSources(
        val sources: Map<String, HentaiWorldPlaybackData>,
    ) {
        fun playbacksForEpisode(number: String?): List<HentaiWorldPlaybackData> {
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return emptyList()
            return listOfNotNull(sources[normalizedNumber])
        }

        fun firstPlaybacks(): List<HentaiWorldPlaybackData> {
            val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
            return playbacksForEpisode(firstNumber)
        }

        fun episodeNumbers(): List<String> = sortedEpisodeNumbers(sources)
    }

    private data class AnimeUnityTitleSources(
        val syncIds: AnimeSyncIds,
        val subSources: Map<String, String>,
        val dubSources: Map<String, String>,
        val plot: String? = null,
        val posterUrl: String? = null,
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

        fun episodeNumbers(): List<String> = sortedEpisodeNumbers(subSources, dubSources)
    }

    private suspend fun buildMetadata(doc: Document, actualUrl: String, isAnime: Boolean): StreamCenterMetadata {
        if (performanceMode) {
            return StreamCenterMetadata(
                title = getLocalizedTitle(doc).ifBlank { "Sconosciuto" },
                originalTitle = null,
                plot = null,
                poster = null,
                background = null,
                tags = emptyList(),
                year = null,
                tmdbId = extractTmdbId(actualUrl),
                score = null,
                people = emptyList(),
                recommendations = emptyList(),
                contentRating = null,
                showStatus = null,
                comingSoon = false,
                duration = null,
                trailerUrl = null,
            )
        }
        val loadExtras = !performanceMode
        val title = getLocalizedTitle(doc).ifBlank { "Sconosciuto" }
        val originalTitle = extractAnyFact(doc, "Titolo originale", "Original Title", "Original Name")
        val status = extractAnyFact(doc, "Stato", "Status")
        val originalLanguage = extractAnyFact(doc, "Lingua Originale", "Original Language")
        val type = extractAnyFact(doc, "Tipo", "Type")
        val budget = extractAnyFact(doc, "Budget")
        val revenue = extractAnyFact(doc, "Incasso", "Revenue")
        val genres = doc.select("span.genres a").mapNotNull { cleanText(it.text()) }
        val factTags = buildFactTags(title, originalTitle, status, originalLanguage, type, budget, revenue)
        val keywords = if (loadExtras) extractKeywords(doc) else emptyList()
        val images = doc.select("meta[property=og:image]").mapNotNull { cleanText(it.attr("content")) }
        val score = if (loadExtras) extractTmdbScore(doc) else null

        return StreamCenterMetadata(
            title = title,
            originalTitle = originalTitle,
            plot = cleanText(doc.selectFirst("meta[property=og:description]")?.attr("content")),
            poster = images.firstOrNull(),
            background = images.getOrNull(1),
            tags = (genres + factTags + keywords).distinctBy { it.lowercase(Locale.ROOT) },
            year = parseYear(doc),
            tmdbId = extractTmdbId(actualUrl),
            score = score,
            people = if (loadExtras) (parseActors(doc) + parseCrew(doc)).distinct() else emptyList(),
            recommendations = if (loadExtras) fetchRecommendations(actualUrl, isAnime) else emptyList(),
            contentRating = extractContentRating(doc),
            showStatus = mapShowStatus(status),
            comingSoon = isComingSoon(status),
            duration = parseRuntime(doc.selectFirst("span.runtime")?.text()),
            trailerUrl = if (loadExtras) extractTrailerUrl(doc) else null,
        )
    }

    private suspend fun getTmdbDocument(
        url: String,
        page: Int? = null,
        cacheProfile: TmdbCacheProfile = TmdbCacheProfile.Detail,
    ): Document {
        val requestUrl = stripStreamCenterParams(normalizeTmdbUrl(url, page))
        val html = getCachedText("GET:$requestUrl", cacheProfile) {
            app.get(
                requestUrl,
                headers = headers,
            ).text
        }
        return Jsoup.parse(html, requestUrl)
    }

    private suspend fun getCachedText(
        cacheKey: String,
        cacheProfile: TmdbCacheProfile,
        cacheBlank: Boolean = false,
        fetch: suspend () -> String,
    ): String {
        tmdbTextCache.get(cacheKey)?.let { return it }

        return try {
            fetch().also { text ->
                if (text.isNotBlank() || cacheBlank) {
                    tmdbTextCache.put(cacheKey, text, cacheProfile.ttlMs)
                }
            }
        } catch (exception: Exception) {
            tmdbTextCache.get(cacheKey, allowExpired = true) ?: throw exception
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

    private fun stripStreamCenterParams(url: String): String {
        val baseUrl = url.substringBefore("?")
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return url

        val params = query.split("&").filterNot { parameter -> parameter.startsWith("streamcenter_") }
        return if (params.isEmpty()) baseUrl else "$baseUrl?${params.joinToString("&")}"
    }

    private suspend fun parseMediaCards(
        doc: Document,
        isAnime: Boolean = false,
        detectAnime: Boolean = false,
        fetchMissingScores: Boolean = false,
        showScores: Boolean = true,
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
            val cardScore = extractCardScore(card, anchor)
            val itemUrl = normalizeTmdbUrl(href)
            val shouldCheckAnime = isAnime || detectAnime
            val animeSyncIds = if (shouldCheckAnime) {
                fetchAnimeSyncIds(extractTmdbId(itemUrl), isTvSeries = mediaType == "tv")
            } else {
                emptyList()
            }
            val shouldBuildAnimeCard = isAnime || animeSyncIds.isNotEmpty()

            if (shouldBuildAnimeCard) {
                results += buildAnimeSearchResponses(
                    title = title,
                    itemUrl = itemUrl,
                    poster = poster,
                    mediaType = mediaType,
                    syncIds = animeSyncIds,
                    showScore = showScores,
                )
                continue
            }

            val tmdbScore = if (showScores) {
                cardScore ?: if (fetchMissingScores) fetchTmdbScore(itemUrl) else null
            } else {
                null
            }
            results += if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    tmdbScore?.let { this.score = Score.from(it, 10) }
                }
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = poster
                    tmdbScore?.let { this.score = Score.from(it, 10) }
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
        score: String? = null,
        showScore: Boolean = true,
    ): List<SearchResponse> {
        if (mediaType != "tv" || syncIds.isEmpty()) {
            return listOf(
                newAnimeSearchResponse(
                    title,
                    markAnimeUrl(itemUrl, syncIds.firstOrNull()),
                    if (mediaType == "tv") TvType.Anime else TvType.AnimeMovie,
                ) {
                    this.posterUrl = poster
                    if (showScore) {
                        score?.let { this.score = Score.from(it, 10) }
                    }
                }
            )
        }

        return syncIds.map { syncIdsForSeason ->
            newAnimeSearchResponse(
                title,
                markAnimeUrl(itemUrl, syncIdsForSeason),
                TvType.Anime,
            ) {
                this.posterUrl = poster
                if (showScore) {
                    score?.let { this.score = Score.from(it, 10) }
                }
            }
        }
    }

    private fun extractCardScore(card: Element, anchor: Element): String? {
        return listOf(card, anchor).firstNotNullOfOrNull { element ->
            extractTmdbScore(element)
        }
    }

    private suspend fun fetchTmdbScore(url: String): String? {
        return runCatching {
            val normalizedUrl = stripStreamCenterParams(normalizeTmdbUrl(url))
            tmdbScoreMemoryCache[normalizedUrl]?.let { return@runCatching it.takeIf(String::isNotBlank) }

            val score = getCachedText(
                cacheKey = "TMDB:SCORE:$normalizedUrl",
                cacheProfile = TmdbCacheProfile.TmdbScore,
                cacheBlank = true,
            ) {
                extractTmdbScore(getTmdbDocument(normalizedUrl, cacheProfile = TmdbCacheProfile.Detail)).orEmpty()
            }
            tmdbScoreMemoryCache[normalizedUrl] = score
            score.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun Int.toDecimalScore(): String {
        return String.format(Locale.US, "%.1f", this / 10.0)
    }

    private fun extractTmdbScore(element: Element): String? {
        extractTmdbScorePercent(element)?.let { return it }
        return extractTmdbJsonLdScore(element)
    }

    private fun extractTmdbScorePercent(element: Element): String? {
        return element.selectFirst(".user_score_chart[data-percent]")
            ?.attr("data-percent")
            ?.toIntOrNull()
            ?.toDecimalScore()
    }

    private fun extractTmdbJsonLdScore(element: Element): String? {
        return element.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { script ->
                runCatching {
                    JSONObject(script.data().trim())
                        .optJSONObject("aggregateRating")
                        ?.opt("ratingValue")
                        ?.toString()
                        ?.toDecimalScore()
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun String.toDecimalScore(): String? {
        val value = replace(',', '.').toDoubleOrNull() ?: return null
        val normalized = if (value > 10.0) value / 10.0 else value
        return String.format(Locale.US, "%.1f", normalized)
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

    private fun isAnilistOnlyUrl(url: String): Boolean {
        return url.contains(anilistOnlyPath) || Regex("""anilist\.co/anime/\d+""").containsMatchIn(url)
    }

    private fun extractAnilistIdFromText(text: String): Int? {
        return Regex("""(?:anilist\.co/anime/|/anilist/)(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun markMalOnlyUrl(malId: Int): String {
        val params = listOf(animeMarker, "$animeMalParam=$malId")
        return "$mainUrl$malOnlyPath$malId?${params.joinToString("&")}"
    }

    private fun isMalOnlyUrl(url: String): Boolean {
        return url.contains(malOnlyPath) || Regex("""myanimelist\.net/anime/\d+""").containsMatchIn(url)
    }

    private fun extractMalIdFromText(text: String): Int? {
        return Regex("""(?:myanimelist\.net/anime/|/mal/)(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractKitsuIdFromText(text: String): Int? {
        return Regex("""(?:kitsu\.(?:io|app)/anime/|/kitsu/)(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private suspend fun fetchAnimeSyncIdsForMal(malId: Int, isTvSeries: Boolean): List<AnimeSyncIds> {
        val memoryCacheKey = "MALONLY:$malId:$isTvSeries"
        animeSyncIdsMemoryCache[memoryCacheKey]?.let { return it }
        return fetchAnimeSyncIdsByExternalId()["MAL:$malId:$isTvSeries"]
            .orEmpty()
            .distinctBy { listOf(it.tmdbId, it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1) }
            .also { animeSyncIdsMemoryCache[memoryCacheKey] = it }
    }

    private data class AnilistLoadMetadata(
        val anilistId: Int,
        val malId: Int?,
        val title: String,
        val titleCandidates: List<String>,
        val originalTitle: String?,
        val format: String?,
        val poster: String?,
        val background: String?,
        val description: String?,
        val score: String?,
        val year: Int?,
        val duration: Int?,
        val episodes: Int?,
        val status: String?,
        val genres: List<String>,
        val isAdult: Boolean,
        val trailerUrl: String?,
        val characters: List<ActorData>,
        val recommendations: List<SearchResponse>,
        val episodeMetadata: List<Episode>,
        val studios: List<String>,
        val source: String?,
        val season: String?,
        val nextAiringEpisode: Int?,
        val nextAiringAtSeconds: Long?,
    ) {
        fun toStreamCenterMetadata(): StreamCenterMetadata = StreamCenterMetadata(
            title = title,
            originalTitle = originalTitle,
            plot = description,
            poster = poster,
            background = background,
            tags = genres,
            year = year,
            tmdbId = null,
            score = score,
            people = emptyList(),
            recommendations = recommendations,
            contentRating = if (isAdult) "18+" else null,
            showStatus = null,
            comingSoon = false,
            duration = duration,
            trailerUrl = trailerUrl,
        )
    }

    private suspend fun loadAnilistMedia(anilistId: Int?, malId: Int?): LoadResponse {
        val metadata = fetchAnilistMetadata(anilistId, malId)
            ?: error("Metadati AniList non trovati")
        val resolvedAnilistId = metadata.anilistId
        val resolvedMalId = metadata.malId
        val isMovie = metadata.format.equals("MOVIE", ignoreCase = true)
        val syncIds = if (performanceMode) {
            emptyList()
        } else {
            val byMal = resolvedMalId?.let { fetchAnimeSyncIdsForMal(it, isTvSeries = !isMovie) }.orEmpty()
            byMal.ifEmpty { fetchAnimeSyncIdsForAnilist(resolvedAnilistId, isTvSeries = !isMovie) }
        }
        val fallbackSyncIds = listOf(
            AnimeSyncIds(
                tmdbId = 0,
                tmdbSeason = null,
                displaySeason = null,
                anilistId = resolvedAnilistId,
                malId = resolvedMalId,
                kitsuId = if (performanceMode) null else resolveKitsuAnimeId(resolvedMalId, resolvedAnilistId),
                type = if (isMovie) "Movie" else "TV",
            )
        )
        val resolvedSyncIds = syncIds.ifEmpty { fallbackSyncIds }
        val sourceSyncIds = if (performanceMode) resolvedSyncIds else enrichAnimeSyncIdsWithKitsu(resolvedSyncIds)
        val streamCenterMetadata = metadata.toStreamCenterMetadata()
        val matchMetadata = AnilistMetadata(
            title = metadata.title,
            score = metadata.score,
            titleCandidates = metadata.titleCandidates,
        )

        val resolvedSources = coroutineScope {
            val useAnimeUnity = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
            val useAnimeWorld = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
            val useAnimeSaturn = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
            val useHentaiWorld = isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)
            val animeUnityDeferred = if (useAnimeUnity) {
                async(Dispatchers.IO) {
                    fetchAnimeUnitySources(streamCenterMetadata, matchMetadata, sourceSyncIds)
                }
            } else {
                null
            }
            val animeWorldDeferred = if (useAnimeWorld) {
                async(Dispatchers.IO) {
                    fetchAnimeWorldSources(streamCenterMetadata, matchMetadata, sourceSyncIds)
                }
            } else {
                null
            }
            val animeSaturnDeferred = if (useAnimeSaturn) {
                async(Dispatchers.IO) {
                    fetchAnimeSaturnSources(streamCenterMetadata, matchMetadata, sourceSyncIds)
                }
            } else {
                null
            }
            val hentaiWorldDeferred = if (useHentaiWorld) {
                async(Dispatchers.IO) { fetchHentaiWorldSources(streamCenterMetadata, matchMetadata) }
            } else {
                null
            }

            ResolvedLoadSources(
                anilistMetadata = matchMetadata,
                animeUnitySources = animeUnityDeferred?.await().orEmpty(),
                animeWorldSources = animeWorldDeferred?.await().orEmpty(),
                animeSaturnSources = animeSaturnDeferred?.await().orEmpty(),
                hentaiWorldSources = hentaiWorldDeferred?.await().orEmpty(),
            )
        }
        val animeUnitySources = resolvedSources.animeUnitySources
        val animeWorldSources = resolvedSources.animeWorldSources
        val animeSaturnSources = resolvedSources.animeSaturnSources
        val hentaiWorldSources = resolvedSources.hentaiWorldSources
        val sourceUrl = markAnilistUrl(resolvedAnilistId, resolvedMalId)
        val resolvedPlot = animeUnitySources.firstNotNullOfOrNull { it.plot?.takeIf(String::isNotBlank) }
            ?: metadata.description
        val episodeFallbackPoster = animeUnitySources.firstNotNullOfOrNull { it.posterUrl }
            ?: metadata.poster
        val tags = (
            if (performanceMode) {
                listOf("Anime") + metadata.genres
            } else {
                listOfNotNull("Anime", anilistFormatLabel(metadata.format)) +
                    metadata.genres +
                    metadata.studios.map { "Studio: $it" } +
                    listOfNotNull(
                        anilistSeasonLabel(metadata.season, metadata.year),
                        anilistSourceLabel(metadata.source),
                    )
            }
            ).distinctBy { it.lowercase(Locale.ROOT) }
        val contentRating = if (metadata.isAdult) "18+" else null

        return if (isMovie) {
            val playbackData = StreamCenterPlaybackData(
                animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
                animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
                animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
                hentaiWorld = hentaiWorldSources.flatMap { it.firstPlaybacks() },
            )
            newMovieLoadResponse(
                metadata.title,
                sourceUrl,
                TvType.AnimeMovie,
                dataUrl = playbackData.toJson(),
            ) {
                if (!performanceMode) {
                    this.posterUrl = metadata.poster
                    this.backgroundPosterUrl = metadata.background
                    this.plot = resolvedPlot
                    this.tags = tags
                    this.year = metadata.year
                    this.duration = metadata.duration
                    this.contentRating = contentRating
                    this.actors = metadata.characters
                    this.recommendations = metadata.recommendations
                }
                addAniListId(resolvedAnilistId)
                resolvedMalId?.let { addMalId(it) }
                if (!performanceMode) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(metadata.score)
                }
            }
        } else {
            val episodeMetadata = if (performanceMode) {
                metadata.episodeMetadata
            } else {
                buildAnimeEpisodeMetadata(
                    malId = resolvedMalId,
                    anilistId = resolvedAnilistId,
                    anilistEpisodes = metadata.episodeMetadata,
                    syncIds = sourceSyncIds,
                    targetEpisodeCount = listOfNotNull(
                        metadata.episodes,
                        maxAnimeSourceEpisodeNumber(
                            animeUnitySources = animeUnitySources,
                            animeWorldSources = animeWorldSources,
                            animeSaturnSources = animeSaturnSources,
                            hentaiWorldSources = hentaiWorldSources,
                        ),
                    ).maxOrNull(),
                )
            }
            val episodes = buildAnimeSourceEpisodes(
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                hentaiWorldSources = hentaiWorldSources,
                seasonNumber = null,
                tmdbEpisodes = episodeMetadata,
                fallbackPoster = episodeFallbackPoster.takeIf { !performanceMode },
            ).ifEmpty {
                buildAnimeFallbackEpisodes(
                    metadata.episodes,
                    episodeMetadata,
                    episodeFallbackPoster.takeIf { !performanceMode },
                )
            }
            val animeType = when (metadata.format?.uppercase(Locale.ROOT)) {
                "OVA", "ONA", "SPECIAL" -> TvType.OVA
                else -> TvType.Anime
            }
            newAnimeLoadResponse(
                metadata.title,
                sourceUrl,
                animeType,
            ) {
                if (!performanceMode) {
                    this.posterUrl = metadata.poster
                    this.backgroundPosterUrl = metadata.background
                    this.plot = resolvedPlot
                    this.tags = tags
                    this.year = metadata.year
                    this.duration = metadata.duration
                    this.contentRating = contentRating
                    this.actors = metadata.characters
                    this.recommendations = metadata.recommendations
                    this.showStatus = mapAnilistShowStatus(metadata.status)
                    this.comingSoon = metadata.status.equals("NOT_YET_RELEASED", ignoreCase = true)
                }
                if (!performanceMode && metadata.nextAiringEpisode != null && metadata.nextAiringAtSeconds != null) {
                    this.nextAiring = NextAiring(
                        episode = metadata.nextAiringEpisode,
                        unixTime = metadata.nextAiringAtSeconds,
                    )
                }
                addEpisodes(DubStatus.Subbed, episodes)
                if (
                    animeUnitySources.isEmpty() &&
                    animeWorldSources.isEmpty() &&
                    animeSaturnSources.isEmpty() &&
                    hentaiWorldSources.isEmpty()
                ) {
                    addSeasonNames(buildAnimeSeasonData(episodes))
                }
                addAniListId(resolvedAnilistId)
                resolvedMalId?.let { addMalId(it) }
                if (!performanceMode) {
                    metadata.trailerUrl?.let { addTrailer(it) }
                    addScore(metadata.score)
                }
            }
        }
    }

    private suspend fun fetchAnimeSyncIdsForAnilist(anilistId: Int, isTvSeries: Boolean): List<AnimeSyncIds> {
        val memoryCacheKey = "ALONLY:$anilistId:$isTvSeries"
        animeSyncIdsMemoryCache[memoryCacheKey]?.let { return it }
        return fetchAnimeSyncIdsByExternalId()["AL:$anilistId:$isTvSeries"]
            .orEmpty()
            .distinctBy { listOf(it.tmdbId, it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1) }
            .also { animeSyncIdsMemoryCache[memoryCacheKey] = it }
    }

    private fun buildAnimeFallbackEpisodes(
        totalEpisodes: Int?,
        episodeMetadata: List<Episode>,
        fallbackPoster: String?,
    ): List<Episode> {
        if (episodeMetadata.isNotEmpty()) {
            return episodeMetadata.map { info ->
                newEpisode(StreamCenterPlaybackData().toJson()) {
                    this.name = info.name ?: "Episodio ${info.episode}"
                    this.season = 1
                    this.episode = info.episode
                    this.posterUrl = info.posterUrl ?: fallbackPoster
                    this.description = info.description
                    this.score = info.score
                    this.runTime = info.runTime
                    info.date?.let { this.date = it }
                }
            }
        }
        val total = totalEpisodes ?: return emptyList()
        if (total <= 0) return emptyList()
        return (1..total).map { number ->
            newEpisode(StreamCenterPlaybackData().toJson()) {
                this.name = "Episodio $number"
                this.season = 1
                this.episode = number
                this.posterUrl = fallbackPoster
            }
        }
    }

    private suspend fun buildAnimeEpisodeMetadata(
        malId: Int?,
        anilistId: Int?,
        anilistEpisodes: List<Episode>,
        syncIds: List<AnimeSyncIds>,
        targetEpisodeCount: Int? = null,
    ): List<Episode> = coroutineScope {
        val cacheKey = buildAnimeEpisodeMetadataCacheKey(malId, anilistId, syncIds)
        val requiredEpisodeCount = targetEpisodeCount ?: 0
        synchronized(animeEpisodeMetadataMemoryCache) {
            animeEpisodeMetadataMemoryCache[cacheKey]
                ?.takeIf {
                    it.expiresAtMs > System.currentTimeMillis() &&
                        it.maxEpisodeNumber >= requiredEpisodeCount
                }
                ?.let { return@coroutineScope it.episodes }
        }
        val kitsuDeferred = async(Dispatchers.IO) {
            runCatching { fetchKitsuEpisodes(malId, anilistId, targetEpisodeCount) }.getOrDefault(emptyMap())
        }
        val malDeferred = async(Dispatchers.IO) {
            malId?.let { runCatching { fetchMalEpisodeExtras(it, targetEpisodeCount) }.getOrDefault(emptyMap()) }.orEmpty()
        }
        val tmdbDeferred = async(Dispatchers.IO) {
            runCatching { fetchFlatTmdbAnimeEpisodes(syncIds, targetEpisodeCount) }.getOrDefault(emptyMap())
        }
        val anilistByNumber = anilistEpisodes.mapNotNull { ep -> ep.episode?.let { it to ep } }.toMap()
        val kitsu = kitsuDeferred.await()
        val mal = malDeferred.await()
        val tmdb = tmdbDeferred.await()

        val numbers = (anilistByNumber.keys + kitsu.keys + mal.keys + tmdb.keys)
            .toSortedSet()
        if (numbers.isEmpty()) return@coroutineScope anilistEpisodes

        val mergedEpisodes = numbers.map { number ->
            val al = anilistByNumber[number]
            val k = kitsu[number]
            val m = mal[number]
            val t = tmdb[number]
            val markerSuffix = when {
                m?.filler == true -> " (Filler)"
                m?.recap == true -> " (Riassunto)"
                else -> ""
            }
            val baseName = k?.name ?: t?.name ?: al?.name ?: m?.title
            newEpisode("") {
                this.episode = number
                this.season = 1
                this.name = when {
                    baseName != null -> baseName + markerSuffix
                    markerSuffix.isNotEmpty() -> "Episodio $number$markerSuffix"
                    else -> null
                }
                this.posterUrl = al?.posterUrl ?: k?.posterUrl ?: t?.posterUrl
                this.description = k?.description ?: t?.description
                m?.score?.let { this.score = Score.from(it.toString(), 5) } ?: t?.score?.let {
                    this.score = it
                }
                this.runTime = k?.runTime ?: t?.runTime
                if (k?.date != null) {
                    this.date = k.date
                } else if (t?.date != null) {
                    this.date = t.date
                } else {
                    m?.airedDate?.let { this.addDate(it) }
                }
            }
        }
        synchronized(animeEpisodeMetadataMemoryCache) {
            animeEpisodeMetadataMemoryCache[cacheKey] = AnimeEpisodeMetadataCacheEntry(
                episodes = mergedEpisodes,
                maxEpisodeNumber = mergedEpisodes.maxOfOrNull { it.episode ?: 0 } ?: 0,
                expiresAtMs = System.currentTimeMillis() + ANIME_EPISODE_METADATA_CACHE_TTL_MS,
            )
        }
        mergedEpisodes
    }

    private fun buildAnimeEpisodeMetadataCacheKey(
        malId: Int?,
        anilistId: Int?,
        syncIds: List<AnimeSyncIds>,
    ): String {
        val tmdbIds = syncIds.mapNotNull { it.tmdbId.takeIf { id -> id > 0 } }
            .distinct()
            .sorted()
            .joinToString(",")
        return "AL:${anilistId ?: 0}:MAL:${malId ?: 0}:TMDB:$tmdbIds"
    }

    private suspend fun fetchFlatTmdbAnimeEpisodes(
        syncIds: List<AnimeSyncIds>,
        targetEpisodeCount: Int?,
    ): Map<Int, Episode> {
        if ((targetEpisodeCount ?: 0) < TMDB_LONG_ANIME_EPISODE_THRESHOLD) return emptyMap()
        val tmdbId = syncIds.firstOrNull { it.tmdbId > 0 && it.isTvSeriesMapping() }?.tmdbId
            ?: return emptyMap()
        val actualUrl = "$mainUrl/tv/$tmdbId"
        val doc = runCatching {
            getTmdbDocument(actualUrl, cacheProfile = TmdbCacheProfile.Seasons)
        }.getOrNull() ?: return emptyMap()
        val episodes = fetchEpisodes(doc, actualUrl)
            .filter { (it.season ?: 0) > 0 && it.episode != null }
            .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))

        return episodes.mapIndexed { index, episode ->
            index + 1 to newEpisode("") {
                this.name = episode.name
                this.season = 1
                this.episode = index + 1
                this.posterUrl = episode.posterUrl
                this.description = episode.description
                this.score = episode.score
                this.runTime = episode.runTime
                episode.date?.let { this.date = it }
            }
        }.toMap()
    }

    private suspend fun fetchKitsuEpisodes(
        malId: Int?,
        anilistId: Int?,
        targetEpisodeCount: Int? = null,
    ): Map<Int, Episode> {
        val kitsuId = resolveKitsuAnimeId(malId, anilistId) ?: return emptyMap()
        val result = linkedMapOf<Int, Episode>()
        var offset = 0
        var page = 0
        val maxPages = if (targetEpisodeCount == null) KITSU_DEFAULT_MAX_PAGES else KITSU_EXTENDED_MAX_PAGES
        while (page < maxPages) {
            val url = "$kitsuApiUrl/anime/$kitsuId/episodes?page%5Blimit%5D=20&page%5Boffset%5D=$offset"
            val text = getCachedText("KITSU:EPS:$kitsuId:$offset", TmdbCacheProfile.AnilistDetail) {
                app.get(url, headers = mapOf("Accept" to "application/vnd.api+json")).text
            }
            val json = runCatching { JSONObject(text) }.getOrNull() ?: break
            val data = json.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (index in 0 until data.length()) {
                val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
                val number = attributes.optNullableInt("number")
                    ?: attributes.optNullableInt("relativeNumber")
                    ?: continue
                if (result.containsKey(number)) continue
                val title = attributes.optNullableString("canonicalTitle")
                    ?: attributes.optJSONObject("titles")?.let {
                        it.optNullableString("en") ?: it.optNullableString("en_us")
                            ?: it.optNullableString("en_jp")
                    }
                val synopsis = cleanText(attributes.optNullableString("synopsis"))
                val airdate = attributes.optNullableString("airdate")
                val length = attributes.optNullableInt("length")
                val thumbnail = attributes.optJSONObject("thumbnail")?.optNullableString("original")
                result[number] = newEpisode("") {
                    this.episode = number
                    this.name = title
                    this.description = synopsis
                    this.runTime = length
                    this.posterUrl = thumbnail
                    airdate?.let { this.addDate(it) }
                }
            }
            if (targetEpisodeCount != null && (result.keys.maxOrNull() ?: 0) >= targetEpisodeCount) break
            val hasNext = json.optJSONObject("links")?.optNullableString("next") != null
            if (!hasNext) break
            offset += 20
            page++
        }
        return result
    }

    private suspend fun resolveKitsuAnimeId(malId: Int?, anilistId: Int?): Int? {
        val lookups = listOfNotNull(
            malId?.let { "myanimelist/anime" to it },
            anilistId?.let { "anilist/anime" to it },
        )
        for ((site, externalId) in lookups) {
            val encodedSite = site.replace("/", "%2F")
            val url = "$kitsuApiUrl/mappings?filter%5BexternalSite%5D=$encodedSite" +
                "&filter%5BexternalId%5D=$externalId&include=item"
            val text = runCatching {
                getCachedText("KITSU:MAP:$site:$externalId", TmdbCacheProfile.AnilistDetail) {
                    app.get(url, headers = mapOf("Accept" to "application/vnd.api+json")).text
                }
            }.getOrNull() ?: continue
            val kitsuId = runCatching {
                val json = JSONObject(text)
                json.optJSONArray("data")?.optJSONObject(0)
                    ?.optJSONObject("relationships")
                    ?.optJSONObject("item")
                    ?.optJSONObject("data")
                    ?.optNullableString("id")
                    ?.toIntOrNull()
                    ?: json.optJSONArray("included")?.optJSONObject(0)?.optNullableString("id")?.toIntOrNull()
            }.getOrNull()
            if (kitsuId != null) return kitsuId
        }
        return null
    }

    private suspend fun enrichAnimeSyncIdsWithKitsu(syncIds: List<AnimeSyncIds>): List<AnimeSyncIds> {
        return syncIds.map { sync ->
            if (sync.kitsuId != null) {
                sync
            } else {
                sync.copy(kitsuId = resolveKitsuAnimeId(sync.malId, sync.anilistId))
            }
        }
    }

    private data class MalEpisodeExtra(
        val title: String?,
        val score: Double?,
        val airedDate: String?,
        val filler: Boolean,
        val recap: Boolean,
    )

    private suspend fun fetchMalEpisodeExtras(
        malId: Int,
        targetEpisodeCount: Int? = null,
    ): Map<Int, MalEpisodeExtra> {
        val result = linkedMapOf<Int, MalEpisodeExtra>()
        var page = 1
        val maxPages = if (targetEpisodeCount == null) MAL_EPISODES_DEFAULT_MAX_PAGES else MAL_EPISODES_EXTENDED_MAX_PAGES
        while (page <= maxPages) {
            val url = "$jikanApiUrl/anime/$malId/episodes?page=$page"
            val text = runCatching {
                getCachedText("MAL:EPISODES:$malId:$page", TmdbCacheProfile.AnilistDetail) {
                    throttleJikan()
                    app.get(url, headers = mapOf("Accept" to "application/json")).text
                }
            }.getOrNull() ?: break
            val json = runCatching { JSONObject(text) }.getOrNull() ?: break
            val data = json.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (index in 0 until data.length()) {
                val entry = data.optJSONObject(index) ?: continue
                val number = entry.optNullableInt("mal_id") ?: continue
                if (result.containsKey(number)) continue
                result[number] = MalEpisodeExtra(
                    title = entry.optNullableString("title"),
                    score = entry.optDouble("score", 0.0).takeIf { it > 0.0 },
                    airedDate = entry.optNullableString("aired")?.substringBefore("T"),
                    filler = entry.optBoolean("filler", false),
                    recap = entry.optBoolean("recap", false),
                )
            }
            if (targetEpisodeCount != null && (result.keys.maxOrNull() ?: 0) >= targetEpisodeCount) break
            val hasNext = json.optJSONObject("pagination")?.optBoolean("has_next_page", false) ?: false
            if (!hasNext) break
            page++
        }
        return result
    }

    private suspend fun throttleJikan() {
        jikanMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = JIKAN_MIN_INTERVAL_MS - (now - jikanLastRequestAtMs)
            if (wait > 0) delay(wait)
            jikanLastRequestAtMs = System.currentTimeMillis()
        }
    }

    private fun mapAnilistShowStatus(status: String?): ShowStatus? {
        return when (status?.uppercase(Locale.ROOT)) {
            "FINISHED" -> ShowStatus.Completed
            "RELEASING", "HIATUS" -> ShowStatus.Ongoing
            else -> null
        }
    }


    private suspend fun fetchAnilistMetadata(anilistId: Int?, malId: Int?): AnilistLoadMetadata? {
        if (anilistId == null && malId == null) return null
        val cacheKey = "ANILIST:MEDIA:${if (performanceMode) "performance" else "full"}:" +
            (anilistId?.let { "al-$it" } ?: "mal-$malId")
        val variables = JSONObject().apply {
            if (anilistId != null) put("id", anilistId) else malId?.let { put("idMal", it) }
        }
        val query = if (performanceMode) ANILIST_MEDIA_PERFORMANCE_QUERY else ANILIST_MEDIA_QUERY
        val data = anilistGraphQL(query, variables, cacheKey) ?: return null
        val media = data.optJSONObject("Media") ?: return null
        return parseAnilistMedia(media)
    }

    private suspend fun anilistGraphQL(
        query: String,
        variables: JSONObject,
        cacheKey: String,
    ): JSONObject? {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val text = runCatching {
            getCachedText(cacheKey, TmdbCacheProfile.AnilistDetail) {
                var lastError: Throwable? = null
                repeat(3) { attempt ->
                    if (attempt > 0) delay(1000L * attempt)
                    throttleAnilist()
                    val response = runCatching {
                        app.post(
                            anilistApiUrl,
                            headers = mapOf(
                                "Accept" to "application/json",
                                "Content-Type" to "application/json",
                            ),
                            requestBody = body.toRequestBody("application/json".toMediaType()),
                        ).text
                    }.getOrElse { lastError = it; return@repeat }
                    if (isAnilistSuccess(response)) return@getCachedText response
                    lastError = RuntimeException("AniList throttled or empty response")
                }
                throw (lastError ?: RuntimeException("AniList request failed"))
            }
        }.getOrNull() ?: return null
        return runCatching { JSONObject(text).optJSONObject("data") }.getOrNull()
    }

    private fun isAnilistSuccess(text: String): Boolean {
        return runCatching {
            JSONObject(text).optJSONObject("data")?.optJSONObject("Media") != null
        }.getOrDefault(false)
    }

    private suspend fun throttleAnilist() {
        anilistMutex.withLock {
            val minInterval = StreamCenterPlugin.getAnilistMinIntervalMs(sharedPref)
            val now = System.currentTimeMillis()
            val wait = minInterval - (now - anilistLastRequestAtMs)
            if (wait > 0) delay(wait)
            anilistLastRequestAtMs = System.currentTimeMillis()
        }
    }

    private fun parseAnilistMedia(media: JSONObject): AnilistLoadMetadata {
        val titleObj = media.optJSONObject("title")
        val romaji = titleObj?.optNullableString("romaji")
        val english = titleObj?.optNullableString("english")
        val native = titleObj?.optNullableString("native")
        val title = romaji ?: english ?: native ?: "Sconosciuto"
        val synonyms = media.optJSONArray("synonyms")?.toStringList().orEmpty()
        val candidates = (listOfNotNull(romaji, english, native) + synonyms)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val coverImage = media.optJSONObject("coverImage")
        val poster = coverImage?.let {
            it.optNullableString("extraLarge")
                ?: it.optNullableString("large")
                ?: it.optNullableString("medium")
        }
        val score = media.optNullableInt("averageScore")?.let(::formatAnilistScore)
        val year = media.optNullableInt("seasonYear")
            ?: media.optJSONObject("startDate")?.optNullableInt("year")

        return AnilistLoadMetadata(
            anilistId = media.optNullableInt("id") ?: 0,
            malId = media.optNullableInt("idMal"),
            title = title,
            titleCandidates = candidates,
            originalTitle = candidates.firstOrNull { !it.equals(title, ignoreCase = true) },
            format = media.optNullableString("format"),
            poster = poster,
            background = media.optNullableString("bannerImage") ?: poster,
            description = cleanAnilistDescription(media.optNullableString("description")),
            score = score,
            year = year,
            duration = media.optNullableInt("duration"),
            episodes = media.optNullableInt("episodes"),
            status = media.optNullableString("status"),
            genres = media.optJSONArray("genres")?.toStringList().orEmpty(),
            isAdult = media.optBoolean("isAdult", false),
            trailerUrl = parseAnilistTrailer(media.optJSONObject("trailer")),
            characters = parseAnilistCharacters(media.optJSONObject("characters")),
            recommendations = parseAnilistRecommendations(media.optJSONObject("recommendations")),
            episodeMetadata = parseAnilistEpisodes(media.optJSONArray("streamingEpisodes")),
            studios = media.optJSONObject("studios")?.optJSONArray("nodes")?.let { nodes ->
                buildList {
                    for (index in 0 until nodes.length()) {
                        nodes.optJSONObject(index)?.optNullableString("name")?.let(::add)
                    }
                }
            }.orEmpty(),
            source = media.optNullableString("source"),
            season = media.optNullableString("season"),
            nextAiringEpisode = media.optJSONObject("nextAiringEpisode")?.optNullableInt("episode"),
            nextAiringAtSeconds = media.optJSONObject("nextAiringEpisode")
                ?.optLong("airingAt", 0L)
                ?.takeIf { it > 0L },
        )
    }

    private fun anilistSeasonLabel(season: String?, year: Int?): String? {
        val name = when (season?.uppercase(Locale.ROOT)) {
            "WINTER" -> "Inverno"
            "SPRING" -> "Primavera"
            "SUMMER" -> "Estate"
            "FALL" -> "Autunno"
            else -> return null
        }
        return "Stagione: $name${year?.let { " $it" }.orEmpty()}"
    }

    private fun anilistSourceLabel(source: String?): String? {
        val name = when (source?.uppercase(Locale.ROOT)) {
            "MANGA" -> "Manga"
            "LIGHT_NOVEL" -> "Light novel"
            "NOVEL" -> "Romanzo"
            "ORIGINAL" -> "Originale"
            "VIDEO_GAME" -> "Videogioco"
            "VISUAL_NOVEL" -> "Visual novel"
            "WEB_NOVEL" -> "Web novel"
            "DOUJINSHI" -> "Doujinshi"
            "MULTIMEDIA_PROJECT" -> "Progetto multimediale"
            null -> return null
            else -> source.lowercase(Locale.ROOT).replace('_', ' ')
                .replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        return "Fonte: $name"
    }

    private fun anilistFormatLabel(format: String?): String? {
        return when (format?.uppercase(Locale.ROOT)) {
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "SPECIAL" -> "Speciale"
            "TV_SHORT" -> "Corto TV"
            "MUSIC" -> "Video musicale"
            else -> null
        }
    }

    private fun formatAnilistScore(averageScore: Int): String {
        return String.format(Locale.US, "%.1f", averageScore / 10.0)
    }

    private fun parseAnilistEpisodes(streamingEpisodes: JSONArray?): List<Episode> {
        val episodes = streamingEpisodes ?: return emptyList()
        val seenNumbers = mutableSetOf<Int>()
        return buildList {
            for (index in 0 until episodes.length()) {
                val entry = episodes.optJSONObject(index) ?: continue
                val rawTitle = entry.optNullableString("title")
                val number = rawTitle
                    ?.let { Regex("""(?i)episod[eio]\s*(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    ?: (index + 1)
                if (!seenNumbers.add(number)) continue
                val cleanTitle = rawTitle
                    ?.replace(Regex("""(?i)^\s*episod[eio]\s*\d+\s*([-:]\s*)?"""), "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                add(
                    newEpisode("") {
                        this.name = cleanTitle
                        this.season = 1
                        this.episode = number
                        this.posterUrl = entry.optNullableString("thumbnail")
                    }
                )
            }
        }
    }

    private fun cleanAnilistDescription(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val withBreaks = description.replace(Regex("(?i)<br\\s*/?>"), "\n")
        return cleanText(Jsoup.parse(withBreaks).text())
    }

    private fun JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                (opt(index) as? String)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun parseAnilistTrailer(trailer: JSONObject?): String? {
        val id = trailer?.optNullableString("id") ?: return null
        return when (trailer.optNullableString("site")?.lowercase(Locale.ROOT)) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else -> null
        }
    }

    private fun parseAnilistCharacters(charactersObj: JSONObject?): List<ActorData> {
        val edges = charactersObj?.optJSONArray("edges") ?: return emptyList()
        return buildList {
            for (index in 0 until edges.length()) {
                val edge = edges.optJSONObject(index) ?: continue
                val node = edge.optJSONObject("node") ?: continue
                val name = node.optJSONObject("name")?.optNullableString("full") ?: continue
                val image = node.optJSONObject("image")?.let {
                    it.optNullableString("large") ?: it.optNullableString("medium")
                }
                val voiceActorObj = edge.optJSONArray("voiceActors")?.optJSONObject(0)
                val voiceActor = voiceActorObj
                    ?.optJSONObject("name")
                    ?.optNullableString("full")
                    ?.let { vaName ->
                        val vaImage = voiceActorObj.optJSONObject("image")?.let {
                            it.optNullableString("large") ?: it.optNullableString("medium")
                        }
                        Actor(vaName, vaImage)
                    }
                val role = when (edge.optNullableString("role")?.uppercase(Locale.ROOT)) {
                    "MAIN" -> ActorRole.Main
                    "SUPPORTING" -> ActorRole.Supporting
                    "BACKGROUND" -> ActorRole.Background
                    else -> null
                }
                add(ActorData(Actor(name, image), role = role, voiceActor = voiceActor))
            }
        }.distinctBy { it.actor.name }
    }

    private fun parseAnilistRecommendations(recommendationsObj: JSONObject?): List<SearchResponse> {
        val nodes = recommendationsObj?.optJSONArray("nodes") ?: return emptyList()
        val seen = mutableSetOf<Int>()
        return buildList {
            for (index in 0 until nodes.length()) {
                val media = nodes.optJSONObject(index)?.optJSONObject("mediaRecommendation") ?: continue
                val recAnilistId = media.optNullableInt("id") ?: continue
                if (!seen.add(recAnilistId)) continue
                val titleObj = media.optJSONObject("title")
                val title = titleObj?.optNullableString("romaji")
                    ?: titleObj?.optNullableString("english")
                    ?: continue
                val poster = media.optJSONObject("coverImage")?.let {
                    it.optNullableString("large") ?: it.optNullableString("medium")
                }
                val type = if (media.optNullableString("format").equals("MOVIE", ignoreCase = true)) {
                    TvType.AnimeMovie
                } else {
                    TvType.Anime
                }
                add(
                    newAnimeSearchResponse(
                        title,
                        markAnilistUrl(recAnilistId, media.optNullableInt("idMal")),
                        type,
                    ) {
                        this.posterUrl = poster
                    }
                )
                if (size >= ANILIST_RECOMMENDATIONS_LIMIT) break
            }
        }
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

    private suspend fun loadStreamingCommunityHomeTitle(url: String): LoadResponse {
        val type = parseQueryParams(url)[scHomeTypeParam] ?: "tv"
        val idSlug = url.substringAfter(scHomePath).substringBefore("?")
        val id = idSlug.substringBefore("-").toIntOrNull()
            ?: error("StreamingCommunity: id non valido")
        val slug = idSlug.substringAfter("-", "")
        val baseTitle = StreamingCommunityTitle(
            id = id,
            slug = slug,
            name = slug,
            type = type,
            tmdbId = null,
            year = null,
            seasons = emptyList(),
        )
        val detail = runCatching { fetchStreamingCommunityTitleDetail(baseTitle) }.getOrNull() ?: baseTitle
        detail.tmdbId?.let { tmdbId ->
            val tmdbPath = if (type == "tv") "tv" else "movie"
            runCatching { loadTmdbMedia("$mainUrl/$tmdbPath/$tmdbId", scHint = detail) }
                .getOrNull()
                ?.let { return it }
        }
        return loadStreamingCommunityOnly(detail)
    }

    private suspend fun loadStreamingCommunityOnly(title: StreamingCommunityTitle): LoadResponse {
        val sourceUrl = "$mainUrl$scHomePath${title.id}-${title.slug}?$scHomeTypeParam=${title.type}"
        val poster = streamingCommunityImageUrl(title.posterFilename)
        return if (title.type == "tv") {
            val episodes = fetchStreamingCommunityEpisodePayloads(title).entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .map { (seasonEpisode, playback) ->
                    newEpisode(StreamCenterPlaybackData(streamingCommunity = playback).toJson()) {
                        this.season = seasonEpisode.first
                        this.episode = seasonEpisode.second
                        if (!performanceMode) this.posterUrl = poster
                    }
                }
            newTvSeriesLoadResponse(title.name, sourceUrl, TvType.TvSeries, episodes) {
                if (!performanceMode) {
                    this.posterUrl = poster
                    this.year = title.year
                    this.backgroundPosterUrl = streamingCommunityImageUrl(title.backgroundFilename)
                    this.logoUrl = streamingCommunityImageUrl(title.logoFilename)
                    this.plot = title.plot
                    this.tags = title.genres
                    this.showStatus = mapStreamingCommunityStatus(title.status)
                    this.contentRating = title.age?.let { "$it+" }
                }
                title.tmdbId?.let { addTMDbId(it.toString()) }
                title.imdbId?.let { addImdbId(it) }
                if (!performanceMode) addScore(title.score)
            }
        } else {
            val playback = title.toStreamingCommunityMoviePlayback()
            newMovieLoadResponse(
                title.name,
                sourceUrl,
                TvType.Movie,
                dataUrl = StreamCenterPlaybackData(streamingCommunity = playback).toJson(),
            ) {
                if (!performanceMode) {
                    this.posterUrl = poster
                    this.year = title.year
                    this.backgroundPosterUrl = streamingCommunityImageUrl(title.backgroundFilename)
                    this.logoUrl = streamingCommunityImageUrl(title.logoFilename)
                    this.plot = title.plot
                    this.tags = title.genres
                    this.duration = title.runtime
                    this.contentRating = title.age?.let { "$it+" }
                }
                title.tmdbId?.let { addTMDbId(it.toString()) }
                title.imdbId?.let { addImdbId(it) }
                if (!performanceMode) addScore(title.score)
            }
        }
    }

    private suspend fun fetchStreamingCommunityTitle(
        metadata: StreamCenterMetadata,
        isTvSeries: Boolean,
    ): StreamingCommunityTitle? {
        val expectedType = if (isTvSeries) "tv" else "movie"
        val expectedTmdbId = metadata.tmdbId?.toIntOrNull()
        val titleCandidates = listOfNotNull(metadata.title, metadata.originalTitle)
            .map { it.replace(Regex("""\(\d{4}\)"""), "").trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val cacheKey = listOf(
            expectedType,
            expectedTmdbId?.toString().orEmpty(),
            titleCandidates.joinToString("|") { it.lowercase(Locale.ROOT) },
        ).joinToString(":")
        if (streamingCommunityTitleMemoryCache.containsKey(cacheKey)) {
            return streamingCommunityTitleMemoryCache[cacheKey]
        }

        val searchCandidates = titleCandidates
            .flatMap { query -> fetchStreamingCommunitySearchResults(query) }
            .filter { it.type == expectedType }
            .distinctBy { it.id }

        var fallback: StreamingCommunityTitle? = null
        for (candidate in searchCandidates.take(8)) {
            val detail = fetchStreamingCommunityTitleDetail(candidate) ?: continue
            if (expectedTmdbId != null && detail.tmdbId == expectedTmdbId) {
                streamingCommunityTitleMemoryCache[cacheKey] = detail
                return detail
            }
            if (fallback == null && detail.matchesStreamingCommunityFallback(metadata, expectedType)) {
                fallback = detail
            }
        }

        return (if (expectedTmdbId == null) fallback else null)
            .also { streamingCommunityTitleMemoryCache[cacheKey] = it }
    }

    private suspend fun fetchStreamingCommunitySearchResults(query: String): List<StreamingCommunityTitle> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        streamingCommunitySearchMemoryCache[normalizedQuery]?.let { return it }
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)

        val text = getCachedText(
            "SC:GET:$streamingCommunityMainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}",
            TmdbCacheProfile.StreamingCommunitySearch,
        ) {
            app.get(
                "$streamingCommunityMainUrl/search",
                params = mapOf("q" to query),
            ).body.string()
        }

        return parseStreamingCommunitySearchResults(text)
            .also { streamingCommunitySearchMemoryCache[normalizedQuery] = it }
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

    private var streamingCommunityLastForcedRefreshMs = 0L

    @Synchronized
    private fun shouldForceStreamingCommunityRefresh(): Boolean {
        val now = System.currentTimeMillis()
        if (now - streamingCommunityLastForcedRefreshMs < 60_000L) return false
        streamingCommunityLastForcedRefreshMs = now
        return true
    }

    private suspend fun fetchStreamingCommunityTitleDetail(
        title: StreamingCommunityTitle,
    ): StreamingCommunityTitle? {
        fetchStreamingCommunityTitleDetailAttempt(title)?.let { return it }
        if (!shouldForceStreamingCommunityRefresh()) return title
        if (runCatching { ensureStreamingCommunityHeaders(forceRefresh = true) }.isFailure) return title
        return fetchStreamingCommunityTitleDetailAttempt(title) ?: title
    }

    private suspend fun fetchStreamingCommunityTitleDetailAttempt(
        title: StreamingCommunityTitle,
    ): StreamingCommunityTitle? {
        runCatching { ensureStreamingCommunityHeaders() }.getOrElse { return null }
        val url = "$streamingCommunityMainUrl/titles/${title.id}-${title.slug}"
        val memoryKey = "$url:$streamingCommunityInertiaVersion"
        streamingCommunityDetailMemoryCache[memoryKey]?.let { return it }
        val text = runCatching {
            getCachedText(
                "SC:GET:$url:$streamingCommunityInertiaVersion",
                TmdbCacheProfile.StreamingCommunityDetail,
            ) {
                app.get(
                    url,
                    headers = streamingCommunityHeaders,
                ).body.string()
            }
        }.getOrNull() ?: return null

        return parseStreamingCommunityTitleDetail(text)
            ?.also { streamingCommunityDetailMemoryCache[memoryKey] = it }
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
        fetchStreamingCommunitySeasonAttempt(title, seasonNumber)?.let { return it }
        if (!shouldForceStreamingCommunityRefresh()) return null
        if (runCatching { ensureStreamingCommunityHeaders(forceRefresh = true) }.isFailure) return null
        return fetchStreamingCommunitySeasonAttempt(title, seasonNumber)
    }

    private suspend fun fetchStreamingCommunitySeasonAttempt(
        title: StreamingCommunityTitle,
        seasonNumber: Int,
    ): StreamingCommunitySeason? {
        runCatching { ensureStreamingCommunityHeaders() }.getOrElse { return null }
        val url = "$streamingCommunityMainUrl/titles/${title.id}-${title.slug}/season-$seasonNumber"
        val memoryKey = "$url:$streamingCommunityInertiaVersion"
        streamingCommunitySeasonMemoryCache[memoryKey]?.let { return it }
        val text = runCatching {
            getCachedText(
                "SC:GET:$url:$streamingCommunityInertiaVersion",
                TmdbCacheProfile.StreamingCommunityDetail,
            ) {
                app.get(
                    url,
                    headers = streamingCommunityHeaders,
                ).body.string()
            }
        }.getOrNull() ?: return null

        return runCatching {
            val json = JSONObject(extractStreamingCommunityPageJson(text) ?: text)
            val props = json.optJSONObject("props") ?: json
            props.optJSONObject("loadedSeason")?.toStreamingCommunitySeason()
        }.getOrNull()?.also { streamingCommunitySeasonMemoryCache[memoryKey] = it }
    }

    private suspend fun fetchStreamingCommunityEpisodePayloads(
        title: StreamingCommunityTitle,
    ): Map<Pair<Int, Int>, StreamingCommunityPlaybackData> {
        if (title.type != "tv") return emptyMap()
        val cacheKey = "${title.id}:${title.slug}"
        streamingCommunityEpisodePayloadsMemoryCache[cacheKey]?.let { return it }

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

        return episodes.also { streamingCommunityEpisodePayloadsMemoryCache[cacheKey] = it }
    }

    private fun StreamingCommunityTitle.toStreamingCommunityMoviePlayback(): StreamingCommunityPlaybackData? {
        if (type != "movie") return null
        return StreamingCommunityPlaybackData(
            iframeUrl = "$streamingCommunityMainUrl/iframe/$id&canPlayFHD=1",
            type = type,
            tmdbId = tmdbId,
        )
    }

    private fun hasStreamingCommunitySessionHeaders(): Boolean {
        return streamingCommunityHeaders["Cookie"].orEmpty().isNotBlank() &&
            streamingCommunityInertiaVersion.isNotBlank()
    }

    private fun applyStreamingCommunitySession(cookie: String, xsrfToken: String, inertiaVersion: String) {
        streamingCommunityHeaders["Cookie"] = cookie
        streamingCommunityXsrfToken = xsrfToken
        streamingCommunityInertiaVersion = inertiaVersion
        streamingCommunityHeaders["X-Inertia-Version"] = streamingCommunityInertiaVersion
        if (streamingCommunityXsrfToken.isNotBlank()) {
            streamingCommunityHeaders["X-XSRF-TOKEN"] = streamingCommunityXsrfToken
        } else {
            streamingCommunityHeaders.remove("X-XSRF-TOKEN")
        }
    }

    private fun restoreStreamingCommunitySession(): Boolean {
        val json = readSessionPayload(sharedPref, PREF_SC_SESSION) ?: return false
        return runCatching {
            val cookie = json.optString("cookie")
            val inertiaVersion = json.optString("inertiaVersion")
            if (cookie.isBlank() || inertiaVersion.isBlank()) return false

            applyStreamingCommunitySession(
                cookie = cookie,
                xsrfToken = json.optString("xsrfToken"),
                inertiaVersion = inertiaVersion,
            )
            true
        }.getOrDefault(false)
    }

    private fun persistStreamingCommunitySession() {
        if (!hasStreamingCommunitySessionHeaders()) return
        val payload = JSONObject()
            .put("cookie", streamingCommunityHeaders["Cookie"].orEmpty())
            .put("xsrfToken", streamingCommunityXsrfToken)
            .put("inertiaVersion", streamingCommunityInertiaVersion)
        writeSessionPayload(
            sharedPref,
            PREF_SC_SESSION,
            payload,
            TmdbCacheProfile.StreamingCommunitySession.ttlMs,
        )
    }

    private suspend fun ensureStreamingCommunityHeaders(forceRefresh: Boolean = false) {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
        if (hasStreamingCommunitySessionHeaders() && !forceRefresh) return
        if (!forceRefresh && restoreStreamingCommunitySession()) return

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

        applyStreamingCommunitySession(
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
        persistStreamingCommunitySession()
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
            posterFilename = images?.streamingCommunityImageFilename("poster"),
            backgroundFilename = images?.streamingCommunityImageFilename("background")
                ?: images?.streamingCommunityImageFilename("cover"),
            logoFilename = images?.streamingCommunityImageFilename("logo"),
        )
    }

    private fun JSONArray.streamingCommunityImageFilename(imageType: String): String? {
        for (index in 0 until length()) {
            val image = optJSONObject(index) ?: continue
            if (image.optNullableString("type") == imageType) {
                return image.optNullableString("filename")
            }
        }
        return null
    }

    private fun streamingCommunityImageUrl(filename: String?): String? {
        val file = filename?.takeIf { it.isNotBlank() } ?: return null
        return "https://cdn.${hostOf(streamingCommunityRootUrl)}/images/$file"
    }

    private fun mapStreamingCommunityStatus(status: String?): ShowStatus? {
        return when (status?.trim()?.lowercase(Locale.ROOT)) {
            "ended", "canceled", "cancelled" -> ShowStatus.Completed
            "returning series", "in production", "planned" -> ShowStatus.Ongoing
            else -> null
        }
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
        metadata: StreamCenterMetadata,
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
            ).body.string()
        }

        return JSONArray(responseText).also { animeMappingsMemoryCache = it }
    }

    private suspend fun fetchAllAnimeSyncIds(): List<AnimeSyncIds> {
        animeSyncIdsListMemoryCache?.let { return it }
        return runCatching {
            val items = fetchAnimeMappings()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val numericTmdbId = item.optNullableInt("themoviedb_id") ?: continue

                    val type = item.optString("type").takeIf(String::isNotBlank)
                    add(
                        AnimeSyncIds(
                            tmdbId = numericTmdbId,
                            tmdbSeason = item.optJSONObject("season")?.optNullableInt("tmdb"),
                            displaySeason = null,
                            anilistId = item.optNullableInt("anilist_id"),
                            malId = item.optNullableInt("mal_id"),
                            kitsuId = item.optNullableInt("kitsu_id"),
                            type = type,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .also { animeSyncIdsListMemoryCache = it }
    }

    private suspend fun fetchAnimeSyncIdsByTmdb(): Map<String, List<AnimeSyncIds>> {
        animeSyncIdsByTmdbMemoryCache?.let { return it }
        return fetchAllAnimeSyncIds()
            .groupBy { "${it.tmdbId}:${it.isTvSeriesMapping()}" }
            .mapValues { (key, values) ->
                val isTvSeries = key.substringAfter(':').toBoolean()
                values
                    .distinctBy { listOf(it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1, it.kitsuId ?: -1) }
                    .mapIndexed { index, syncIds ->
                        syncIds.copy(displaySeason = if (isTvSeries) index + 1 else syncIds.tmdbSeason)
                    }
            }
            .also { animeSyncIdsByTmdbMemoryCache = it }
    }

    private suspend fun fetchAnimeSyncIdsByExternalId(): Map<String, List<AnimeSyncIds>> {
        animeSyncIdsByExternalIdMemoryCache?.let { return it }
        val grouped = mutableMapOf<String, MutableList<AnimeSyncIds>>()

        fun add(key: String, syncIds: AnimeSyncIds) {
            grouped.getOrPut(key) { mutableListOf() } += syncIds
        }

        fetchAnimeSyncIdsByTmdb().values.flatten().forEach { syncIds ->
            val isTvSeries = syncIds.isTvSeriesMapping()
            syncIds.anilistId?.let { add("AL:$it:$isTvSeries", syncIds) }
            syncIds.malId?.let { add("MAL:$it:$isTvSeries", syncIds) }
            syncIds.kitsuId?.let { add("KITSU:$it:$isTvSeries", syncIds) }
        }

        return grouped.mapValues { (_, values) ->
            values.distinctBy {
                listOf(it.tmdbId, it.tmdbSeason ?: -1, it.anilistId ?: -1, it.malId ?: -1, it.kitsuId ?: -1)
            }
        }.also { animeSyncIdsByExternalIdMemoryCache = it }
    }

    private suspend fun fetchAnimeSyncIds(tmdbId: String?, isTvSeries: Boolean): List<AnimeSyncIds> {
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return emptyList()
        val memoryCacheKey = "$numericTmdbId:$isTvSeries"
        animeSyncIdsMemoryCache[memoryCacheKey]?.let { return it }

        return fetchAnimeSyncIdsByTmdb()["$numericTmdbId:$isTvSeries"]
            .orEmpty()
            .also { animeSyncIdsMemoryCache[memoryCacheKey] = it }
    }

    private fun AnimeSyncIds.isTvSeriesMapping(): Boolean {
        return !type.equals("Movie", ignoreCase = true)
    }

    private fun pickPrimaryAnimeSyncIds(syncIds: List<AnimeSyncIds>): AnimeSyncIds? {
        return syncIds.firstOrNull { it.tmdbSeason == null } ?: syncIds.firstOrNull()
    }

    private fun selectAnimeSyncIds(
        syncIds: List<AnimeSyncIds>,
        selection: StreamCenterAnimeSelection?,
    ): List<AnimeSyncIds> {
        selection ?: return syncIds
        return syncIds.filter { syncIdsForSeason ->
            (selection.anilistId != null && syncIdsForSeason.anilistId == selection.anilistId) ||
                (selection.malId != null && syncIdsForSeason.malId == selection.malId) ||
                (selection.displaySeason != null && syncIdsForSeason.displaySeason == selection.displaySeason)
        }.ifEmpty { syncIds }
    }

    private fun parseAnimeSelection(url: String): StreamCenterAnimeSelection? {
        val params = parseQueryParams(url)
        val selection = StreamCenterAnimeSelection(
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
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeUnityTitleSources> {
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(auArchiveQueryLimit)
        val exactTitleKeys = exactAnimeTitleKeys(metadata, anilistMetadata)
        val allowTitleFallback = syncIds.size == 1

        return syncIds.mapNotNull { sync ->
            val variants = findAnimeUnityVariants(sync, titleCandidates, exactTitleKeys, allowTitleFallback)
            if (variants.isEmpty()) return@mapNotNull null

            val subAnime = variants.firstOrNull { !it.isDub }
            val dubAnime = variants.firstOrNull { it.isDub }
            val (subPage, dubPage) = coroutineScope {
                val subDeferred = subAnime?.let { async(Dispatchers.IO) { fetchAnimeUnityPageData(it) } }
                val dubDeferred = dubAnime?.let { async(Dispatchers.IO) { fetchAnimeUnityPageData(it) } }
                subDeferred?.await() to dubDeferred?.await()
            }
            val subSources = buildAnimeUnityEpisodeSources(subPage)
            val dubSources = buildAnimeUnityEpisodeSources(dubPage)
            val pageAnime = subPage?.anime ?: dubPage?.anime
            val plot = pageAnime?.plot?.takeIf { it.isNotBlank() }
            val poster = animeUnityPoster(pageAnime?.imageUrl)

            AnimeUnityTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
                plot = plot,
                posterUrl = poster,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun findAnimeUnityVariants(
        syncIds: AnimeSyncIds,
        titleCandidates: List<String>,
        exactTitleKeys: Set<String>,
        allowTitleFallback: Boolean,
    ): List<AnimeUnityAnime> {
        val candidates = linkedMapOf<Int, AnimeUnityAnime>()
        for (chunk in titleCandidates.chunked(ANIME_SEARCH_PARALLELISM)) {
            coroutineScope {
                chunk.map { title ->
                    async(Dispatchers.IO) {
                        runCatching { fetchAnimeUnityArchive(title) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }.flatten().forEach { anime ->
                if (!candidates.containsKey(anime.id)) {
                    candidates[anime.id] = anime
                }
            }
            if (candidates.values.any { it.matches(syncIds) }) break
        }

        val idMatches = candidates.values.filter { anime -> anime.matches(syncIds) }
        val exactMatches = idMatches.ifEmpty {
            if (!allowTitleFallback) return emptyList()
            candidates.values.filter { anime ->
                anime.anilistId == null && anime.malId == null &&
                    anime.titleKeys().any { it in exactTitleKeys }
            }
        }
        if (exactMatches.isEmpty()) return emptyList()

        val matchedContentKeys = exactMatches.map { it.contentKey() }.toSet()
        return candidates.values
            .filter { it.contentKey() in matchedContentKeys || it.matches(syncIds) }
            .distinctBy(AnimeUnityAnime::id)
            .sortedWith(compareBy<AnimeUnityAnime> { if (it.isDub) 1 else 0 }.thenBy { it.id })
    }

    private suspend fun fetchAnimeUnityArchive(title: String, offset: Int = 0): List<AnimeUnityAnime> {
        ensureAnimeUnityHeaders()
        val body = buildAnimeUnityArchiveBody(title, offset)
        val requestBody = body.toRequestBody("application/json;charset=utf-8".toMediaType())
        val cacheKey = "POST:$animeUnityUrl/archivio/get-animes:$body"
        val text = getCachedText(cacheKey, TmdbCacheProfile.AnimeUnityArchive) {
            app.post(
                "$animeUnityUrl/archivio/get-animes",
                headers = animeUnityHeaders,
                requestBody = requestBody,
            ).text
        }

        return parseAnimeUnityArchive(text)
    }

    private fun buildAnimeUnityArchiveBody(title: String, offset: Int = 0): String {
        return JSONObject().apply {
            put("title", title)
            put("type", false)
            put("year", false)
            put("order", false)
            put("status", false)
            put("genres", false)
            put("season", false)
            put("dubbed", 0)
            put("offset", offset)
        }.toString()
    }

    private fun hasAnimeUnitySessionHeaders(): Boolean {
        return animeUnityHeaders["Cookie"].orEmpty().isNotBlank() &&
            animeUnityHeaders["X-CSRF-Token"].orEmpty().isNotBlank()
    }

    private fun applyAnimeUnitySession(cookie: String, csrfToken: String) {
        animeUnityHeaders.putAll(
            mapOf(
                "Host" to hostOf(animeUnityUrl),
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json;charset=utf-8",
                "X-CSRF-Token" to csrfToken,
                "Referer" to animeUnityUrl,
                "Cookie" to cookie,
            )
        )
    }

    private fun restoreAnimeUnitySession(): Boolean {
        val json = readSessionPayload(sharedPref, PREF_AU_SESSION) ?: return false
        return runCatching {
            val cookie = json.optString("cookie")
            val csrfToken = json.optString("csrfToken")
            if (cookie.isBlank() || csrfToken.isBlank()) return false

            applyAnimeUnitySession(cookie, csrfToken)
            true
        }.getOrDefault(false)
    }

    private fun persistAnimeUnitySession() {
        if (!hasAnimeUnitySessionHeaders()) return
        val payload = JSONObject()
            .put("cookie", animeUnityHeaders["Cookie"].orEmpty())
            .put("csrfToken", animeUnityHeaders["X-CSRF-Token"].orEmpty())
        writeSessionPayload(
            sharedPref,
            PREF_AU_SESSION,
            payload,
            TmdbCacheProfile.AnimeUnitySession.ttlMs,
        )
    }

    private suspend fun ensureAnimeUnityHeaders(forceRefresh: Boolean = false) {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
        if (hasAnimeUnitySessionHeaders() && !forceRefresh) return
        if (!forceRefresh && restoreAnimeUnitySession()) return

        animeUnityHeaders["Host"] = hostOf(animeUnityUrl)
        val response = app.get("$animeUnityUrl/archivio", headers = animeUnityHeaders)
        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies = listOfNotNull(
            response.cookies["XSRF-TOKEN"]?.let { "XSRF-TOKEN=$it" },
            response.cookies["animeunity_session"]?.let { "animeunity_session=$it" },
        ).joinToString("; ")

        applyAnimeUnitySession(cookies, csrfToken)
        persistAnimeUnitySession()
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

    private suspend fun fetchAnimeWorldSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeWorldTitleSources> {
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(animeSearchQueryLimit)
        val searchCandidates = titleCandidates
            .mapChunkedParallel(ANIME_SEARCH_PARALLELISM) { fetchAnimeWorldSearchResults(it) }
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
            .take(awDetailCandidateLimit)
        val pageData = coroutineScope {
            searchCandidates
                .map { item -> async(Dispatchers.IO) { fetchAnimeWorldPageData(item) } }
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
                .mergeAnimeWorldEpisodeSources()
            val dubSources = matches
                .filter { it.searchItem.isDub }
                .mergeAnimeWorldEpisodeSources()

            AnimeWorldTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun fetchAnimeWorldSearchResults(query: String): List<AnimeWorldSearchItem> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
        val url = "$animeWorldUrl/filter?sort=0&keyword=${URLEncoder.encode(query, "UTF-8")}"
        val html = getCachedText("AW:GET:$url", TmdbCacheProfile.AnimeWorldSearch) {
            app.get(
                url,
                headers = headers,
            ).text
        }
        val doc = Jsoup.parse(html, url)

        return doc.select("div.film-list > .item").mapNotNull { item ->
            val anchor = item.selectFirst("a.name[href]") ?: return@mapNotNull null
            val title = cleanText(anchor.text()) ?: return@mapNotNull null
            val otherTitle = cleanText(anchor.attr("data-jtitle"))
            val itemUrl = toAbsoluteProviderUrl(animeWorldUrl, anchor.attr("href")).trimEnd('/')
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

    private suspend fun fetchAnimeWorldPageData(item: AnimeWorldSearchItem): AnimeWorldPageData? {
        val html = getCachedText("AW:GET:${item.url}", TmdbCacheProfile.AnimeWorldDetail) {
            app.get(
                item.url,
                headers = headers,
            ).text
        }
        val doc = Jsoup.parse(html, item.url)
        val isDub = item.isDub || html.contains("window.animeDub = true", ignoreCase = true)
        val label = if (isDub) "[DUB]" else "[SUB]"
        val episodes = parseAnimeWorldEpisodes(doc)
        val episodeSources = episodes.mapNotNull { episode ->
            val number = normalizeAnimeUnityEpisodeNumber(episode.number) ?: return@mapNotNull null
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
                ?.let { extractKitsuIdFromText(it) },
            episodeSources = episodeSources,
        ).takeIf { it.episodeSources.isNotEmpty() }
    }

    private fun parseAnimeWorldEpisodes(doc: Document): List<AnimeWorldEpisodeInfo> {
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
        }.distinctBy { normalizeAnimeUnityEpisodeNumber(it.number) ?: it.number }
    }

    private suspend fun fetchAnimeSaturnSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
        syncIds: List<AnimeSyncIds>,
    ): List<AnimeSaturnTitleSources> {
        if (syncIds.isEmpty()) return emptyList()

        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(animeSearchQueryLimit)
        val searchResults = titleCandidates
            .mapChunkedParallel(ANIME_SEARCH_PARALLELISM) { fetchAnimeSaturnSearchResults(it) }
            .flatten()
        val searchCandidates = searchResults
            .groupBy { it.url }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.score } }
            .sortedWith(
                compareByDescending<AnimeSaturnSearchItem> { it.score }
                    .thenBy { it.title.length }
                    .thenBy { it.url },
            )
            .take(animeSaturnDetailCandidateLimit)
        val pageData = coroutineScope {
            searchCandidates
                .map { item -> async(Dispatchers.IO) { fetchAnimeSaturnPageData(item) } }
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

            val subSources = matches
                .filter { !it.searchItem.isDub }
                .mergeAnimeSaturnEpisodeSources()
            val dubSources = matches
                .filter { it.searchItem.isDub }
                .mergeAnimeSaturnEpisodeSources()

            AnimeSaturnTitleSources(
                syncIds = sync,
                subSources = subSources,
                dubSources = dubSources,
            ).takeIf { it.subSources.isNotEmpty() || it.dubSources.isNotEmpty() }
        }
    }

    private suspend fun fetchAnimeSaturnSearchResults(query: String): List<AnimeSaturnSearchItem> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)
        val url = "$animeSaturnUrl/filter?key=${URLEncoder.encode(query, "UTF-8")}"
        val html = getCachedText("AS:GET:$url", TmdbCacheProfile.AnimeSaturnSearch) {
            app.get(
                url,
                headers = headers,
            ).text
        }
        val doc = Jsoup.parse(html, url)

        return doc.select("a.ac[href^='/anime/'], a.ac[href*='/anime/'], a[href^='/anime/'], a[href*='animesaturn.net/anime/']").mapNotNull { item ->
            val href = item.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (href.contains("/ep-", ignoreCase = true)) return@mapNotNull null
            val parsedTitle = cleanText(item.selectFirst(".ac__title, h3, h4, .title")?.text())
                ?: cleanText(item.selectFirst("img[alt]")?.attr("alt"))
                ?: cleanText(item.attr("title"))
                ?: cleanText(item.text())
            val title = parsedTitle
                ?.takeUnless { sourceTitleDedupKey(it) in setOf("dettagli", "detail", "details") }
                ?: animeSaturnTitleFromHref(href)
                ?: return@mapNotNull null
            val itemUrl = toAbsoluteProviderUrl(animeSaturnUrl, href).trimEnd('/')
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

    private fun animeSaturnTitleFromHref(href: String): String? {
        val slug = href.substringAfter("/anime/", missingDelimiterValue = "")
            .substringBefore('?')
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?: return null
        return slug
            .replace(Regex("""-[A-Za-z0-9]{5}$"""), "")
            .replace('-', ' ')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun sourceTitleScore(title: String, query: String): Int {
        val normalizedTitle = sourceTitleDedupKey(title)
        val normalizedQuery = sourceTitleDedupKey(query)
        if (normalizedTitle.isBlank() || normalizedQuery.isBlank()) return 0
        if (normalizedTitle == normalizedQuery) return 120
        if (normalizedTitle.contains(normalizedQuery) || normalizedQuery.contains(normalizedTitle)) return 100

        val titleTokens = sourceTitleTokens(normalizedTitle)
        val queryTokens = sourceTitleTokens(normalizedQuery)
        if (titleTokens.isEmpty() || queryTokens.isEmpty()) return 0
        val overlap = titleTokens.intersect(queryTokens).size
        if (overlap == 0) return 0
        val overlapRatio = overlap.toDouble() / minOf(titleTokens.size, queryTokens.size).toDouble()
        val baseScore = when {
            overlapRatio >= 0.75 -> 80
            overlapRatio >= 0.50 -> 55
            overlap >= 2 -> 35
            else -> 0
        }
        val moviePenalty = if (
            title.contains("movie", ignoreCase = true) &&
            !query.contains("movie", ignoreCase = true)
        ) {
            15
        } else {
            0
        }
        return (baseScore - moviePenalty).coerceAtLeast(0)
    }

    private suspend fun fetchAnimeSaturnPageData(item: AnimeSaturnSearchItem): AnimeSaturnPageData? {
        val html = getCachedText("AS:GET:${item.url}", TmdbCacheProfile.AnimeSaturnDetail) {
            app.get(
                item.url,
                headers = headers,
            ).text
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
                        Regex("""(?i)episodio\s+([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.getOrNull(1)
                    }
                    ?: return@mapNotNull null
                val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return@mapNotNull null
                normalizedNumber to AnimeSaturnPlaybackData(
                    label = label,
                    watchUrl = animeSaturnWatchUrl(href),
                )
            }
            .distinctBy { it.first }
            .toMap()

        return AnimeSaturnPageData(
            searchItem = item.copy(isDub = isDub),
            anilistId = doc.selectFirst("a[href*='anilist.co/anime/'][href]")
                ?.attr("href")
                ?.let { extractAnilistIdFromText(it) }
                ?: extractAnilistIdFromText(html),
            malId = doc.selectFirst("a[href*='myanimelist.net/anime/'][href]")
                ?.attr("href")
                ?.let { extractMalIdFromText(it) }
                ?: extractMalIdFromText(html),
            kitsuId = doc.selectFirst("a[href*='kitsu.io/anime/'][href], a[href*='kitsu.app/anime/'][href]")
                ?.attr("href")
                ?.let { extractKitsuIdFromText(it) }
                ?: extractKitsuIdFromText(html),
            episodeSources = episodeSources,
        ).takeIf { it.episodeSources.isNotEmpty() }
    }

    private fun animeSaturnWatchUrl(href: String): String {
        val normalizedHref = href.trim()
            .replace(Regex("""^/episode/"""), "/anime/")
        return toAbsoluteProviderUrl(animeSaturnUrl, normalizedHref)
    }

    private suspend fun fetchHentaiWorldSources(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
    ): List<HentaiWorldTitleSources> {
        val titleCandidates = buildAnimeSourceTitleCandidates(metadata, anilistMetadata)
            .take(animeSearchQueryLimit)
        val normalizedCandidates = titleCandidates.map(::sourceTitleDedupKey).toSet()

        return titleCandidates
            .mapChunkedParallel(ANIME_SEARCH_PARALLELISM) { fetchHentaiWorldSearchResults(it) }
            .flatten()
            .distinctBy { it.url }
            .filter { sourceTitleDedupKey(it.title) in normalizedCandidates }
            .take(4)
            .mapNotNull { fetchHentaiWorldTitleSources(it) }
    }

    private suspend fun fetchHentaiWorldSearchResults(query: String): List<HentaiWorldSearchItem> {
        ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD)
        val url = "$hentaiWorldUrl/archive?search=${URLEncoder.encode(query, "UTF-8")}"
        val html = getCachedText("HW:GET:$url", TmdbCacheProfile.HentaiWorldSearch) {
            app.get(
                url,
                headers = headers,
            ).text
        }
        val doc = Jsoup.parse(html, url)

        return doc.select("article").mapNotNull { item ->
            val anchor = item.selectFirst("a[href^=/hentai/]") ?: return@mapNotNull null
            val title = cleanText(item.selectFirst("h3")?.text())
                ?: cleanText(anchor.text())
                ?: cleanText(item.selectFirst("img[alt]")?.attr("alt")?.removePrefix("Copertina di "))
                ?: return@mapNotNull null

            HentaiWorldSearchItem(
                url = toAbsoluteProviderUrl(hentaiWorldUrl, anchor.attr("href")).trimEnd('/'),
                title = title,
            )
        }
    }

    private suspend fun fetchHentaiWorldTitleSources(item: HentaiWorldSearchItem): HentaiWorldTitleSources? {
        val html = getCachedText("HW:GET:${item.url}", TmdbCacheProfile.HentaiWorldDetail) {
            app.get(
                item.url,
                headers = headers,
            ).text
        }
        val doc = Jsoup.parse(html, item.url)
        val sources = doc.select("article.episode-item").mapNotNull { episode ->
            val number = episode.attr("data-episode").takeIf(String::isNotBlank)
                ?: episode.selectFirst("a[href*=/watch/]")?.attr("href")
                    ?.let { Regex("""episode-(\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }
                ?: return@mapNotNull null
            val watchUrl = episode.selectFirst("a[href*=/watch/]")
                ?.attr("href")
                ?.takeIf(String::isNotBlank)
                ?.let { toAbsoluteProviderUrl(hentaiWorldUrl, it) }
                ?: return@mapNotNull null
            val normalizedNumber = normalizeAnimeUnityEpisodeNumber(number) ?: return@mapNotNull null
            normalizedNumber to HentaiWorldPlaybackData(watchUrl = watchUrl)
        }.toMap()

        return HentaiWorldTitleSources(sources).takeIf { it.sources.isNotEmpty() }
    }

    private suspend fun fetchEpisodes(
        doc: Document,
        actualUrl: String,
        animeUnitySources: List<AnimeUnityTitleSources> = emptyList(),
        animeWorldSources: List<AnimeWorldTitleSources> = emptyList(),
        animeSaturnSources: List<AnimeSaturnTitleSources> = emptyList(),
        hentaiWorldSources: List<HentaiWorldTitleSources> = emptyList(),
        targetSeason: Int? = null,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData> = emptyMap(),
        fallbackPoster: String? = null,
        minimalMetadata: Boolean = false,
    ): List<Episode> = coroutineScope {
        if (!actualUrl.contains("/tv/")) {
            emptyList()
        } else {
            val seasonUrls = targetSeason
                ?.let { listOf(buildTmdbSeasonUrl(actualUrl, it)) }
                ?: fetchSeasonUrls(doc, actualUrl)

            seasonUrls.map { seasonUrl ->
                async(Dispatchers.IO) {
                    val fallbackSeason = extractSeasonNumber(seasonUrl)
                    runCatching { getTmdbDocument(seasonUrl, cacheProfile = TmdbCacheProfile.Seasons) }
                        .getOrNull()
                        ?.let {
                            parseSeasonEpisodes(
                                seasonDoc = it,
                                fallbackSeason = fallbackSeason,
                                animeUnitySources = animeUnitySources,
                                animeWorldSources = animeWorldSources,
                                animeSaturnSources = animeSaturnSources,
                                hentaiWorldSources = hentaiWorldSources,
                                streamingCommunityEpisodes = streamingCommunityEpisodes,
                                fallbackPoster = fallbackPoster,
                                minimalMetadata = minimalMetadata,
                            )
                        }
                        .orEmpty()
                }
            }.awaitAll().flatten()
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
        val baseUrl = stripStreamCenterParams(normalizeTmdbUrl(actualUrl)).substringBefore("?").trimEnd('/')
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
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
        hentaiWorldSources: List<HentaiWorldTitleSources>,
        streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
        fallbackPoster: String? = null,
        minimalMetadata: Boolean = false,
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
            val title = if (minimalMetadata) {
                null
            } else {
                cleanEpisodeTitle(
                    card.selectFirst("div.episode_title h3 a")?.text()
                        ?: anchor?.text()
                )
            }

            if (dataUrl.isBlank() && episodeNumber == null && title == null) return@mapNotNull null

            val airDate = if (minimalMetadata) null else parseItalianDateToIso(card.selectFirst("div.date span.date")?.text())
            val runtime = if (minimalMetadata) null else parseRuntime(card.selectFirst("span.runtime")?.text())
            val streamingCommunityPlayback = seasonNumber?.let { season ->
                episodeNumber?.let { episode -> streamingCommunityEpisodes[season to episode] }
            }
            val sourcePayload = buildStreamCenterEpisodePayload(
                tmdbUrl = dataUrl,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                animeUnitySources = animeUnitySources,
                animeWorldSources = animeWorldSources,
                animeSaturnSources = animeSaturnSources,
                hentaiWorldSources = hentaiWorldSources,
                streamingCommunity = streamingCommunityPlayback,
            )
            newEpisode(sourcePayload) {
                this.name = if (minimalMetadata) {
                    episodeNumber?.let { "Episodio $it" }
                } else {
                    title
                }
                this.season = seasonNumber
                this.episode = episodeNumber
                if (!minimalMetadata) {
                    this.posterUrl = card.selectFirst("img.backdrop")?.extractImageUrl()
                        ?: card.selectFirst("img")?.extractImageUrl()
                        ?: fallbackPoster
                    this.description = cleanEpisodeDescription(card.selectFirst("div.overview p")?.text())
                    this.runTime = runtime
                    airDate?.let { this.addDate(it) }
                }
            }
        }
    }

    private fun buildStreamCenterEpisodePayload(
        tmdbUrl: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
        hentaiWorldSources: List<HentaiWorldTitleSources>,
        streamingCommunity: StreamingCommunityPlaybackData? = null,
    ): String {
        if (
            animeUnitySources.isEmpty() &&
            animeWorldSources.isEmpty() &&
            animeSaturnSources.isEmpty() &&
            hentaiWorldSources.isEmpty() &&
            streamingCommunity == null
        ) {
            return tmdbUrl
        }

        val seasonSources = when {
            seasonNumber != null -> animeUnitySources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeUnitySources.takeIf { it.size == 1 }?.firstOrNull()

        val animeWorldSeasonSources = when {
            seasonNumber != null -> animeWorldSources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeWorldSources.takeIf { it.size == 1 }?.firstOrNull()
        val animeSaturnSeasonSources = when {
            seasonNumber != null -> animeSaturnSources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeSaturnSources.takeIf { it.size == 1 }?.firstOrNull()

        val animeUnityPlayback = seasonSources?.playbackForEpisode(episodeNumber?.toString())
        val animeWorldPlaybacks = animeWorldSeasonSources
            ?.playbacksForEpisode(episodeNumber?.toString())
            .orEmpty()
        val animeSaturnPlaybacks = animeSaturnSeasonSources
            ?.playbacksForEpisode(episodeNumber?.toString())
            .orEmpty()
        val hentaiWorldPlaybacks = hentaiWorldSources
            .flatMap { it.playbacksForEpisode(episodeNumber?.toString()) }
            .distinctBy { it.watchUrl }
        return StreamCenterPlaybackData(
            tmdbUrl = tmdbUrl.takeIf(String::isNotBlank),
            animeUnity = animeUnityPlayback,
            animeWorld = animeWorldPlaybacks,
            animeSaturn = animeSaturnPlaybacks,
            hentaiWorld = hentaiWorldPlaybacks,
            streamingCommunity = streamingCommunity,
        ).toJson()
    }

    private fun buildAnimeSourceEpisodes(
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
        hentaiWorldSources: List<HentaiWorldTitleSources>,
        seasonNumber: Int?,
        tmdbEpisodes: List<Episode>,
        fallbackPoster: String? = null,
    ): List<Episode> {
        val animeUnityTitleSources = when {
            seasonNumber != null -> animeUnitySources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeUnitySources.takeIf { it.size == 1 }?.firstOrNull()
        val animeWorldTitleSources = when {
            seasonNumber != null -> animeWorldSources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeWorldSources.takeIf { it.size == 1 }?.firstOrNull()
        val animeSaturnTitleSources = when {
            seasonNumber != null -> animeSaturnSources.firstOrNull { it.syncIds.tmdbSeason == seasonNumber }
            else -> null
        } ?: animeSaturnSources.takeIf { it.size == 1 }?.firstOrNull()
        val hentaiWorldTitleSources = hentaiWorldSources
        val episodeNumbers = (
            animeUnityTitleSources?.episodeNumbers().orEmpty() +
                animeWorldTitleSources?.episodeNumbers().orEmpty() +
                animeSaturnTitleSources?.episodeNumbers().orEmpty() +
                hentaiWorldTitleSources.flatMap { it.episodeNumbers() }
            )
            .distinct()
            .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        if (episodeNumbers.isEmpty()) return emptyList()

        val tmdbEpisodesByNumber = tmdbEpisodes.mapNotNull { episode ->
            episode.episode?.let { it to episode }
        }.toMap()

        return episodeNumbers.mapNotNull { number ->
            val playback = animeUnityTitleSources?.playbackForEpisode(number)
            val animeWorldPlaybacks = animeWorldTitleSources?.playbacksForEpisode(number).orEmpty()
            val animeSaturnPlaybacks = animeSaturnTitleSources?.playbacksForEpisode(number).orEmpty()
            val hentaiWorldPlaybacks = hentaiWorldTitleSources
                .flatMap { it.playbacksForEpisode(number) }
                .distinctBy { it.watchUrl }
            if (
                playback == null &&
                animeWorldPlaybacks.isEmpty() &&
                animeSaturnPlaybacks.isEmpty() &&
                hentaiWorldPlaybacks.isEmpty()
            ) {
                return@mapNotNull null
            }
            val episodeNumber = parseWholeEpisodeNumber(number)
            val tmdbEpisode = episodeNumber?.let { tmdbEpisodesByNumber[it] }
            val isSpecialEpisode = episodeNumber == null || episodeNumber <= 0
            newEpisode(
                StreamCenterPlaybackData(
                    animeUnity = playback,
                    animeWorld = animeWorldPlaybacks,
                    animeSaturn = animeSaturnPlaybacks,
                    hentaiWorld = hentaiWorldPlaybacks,
                ).toJson()
            ) {
                this.name = tmdbEpisode?.name
                    ?: if (isSpecialEpisode) "Speciale $number" else "Episodio $number"
                this.season = seasonNumber ?: tmdbEpisode?.season ?: 1
                this.episode = episodeNumber?.takeIf { it > 0 } ?: tmdbEpisode?.episode
                this.posterUrl = tmdbEpisode?.posterUrl ?: fallbackPoster
                this.description = tmdbEpisode?.description
                this.score = tmdbEpisode?.score
                this.runTime = tmdbEpisode?.runTime
                tmdbEpisode?.date?.let { this.date = it }
            }
        }
    }

    private fun maxAnimeSourceEpisodeNumber(
        animeUnitySources: List<AnimeUnityTitleSources>,
        animeWorldSources: List<AnimeWorldTitleSources>,
        animeSaturnSources: List<AnimeSaturnTitleSources>,
        hentaiWorldSources: List<HentaiWorldTitleSources>,
    ): Int? {
        return (
            animeUnitySources.flatMap { it.episodeNumbers() } +
                animeWorldSources.flatMap { it.episodeNumbers() } +
                animeSaturnSources.flatMap { it.episodeNumbers() } +
                hentaiWorldSources.flatMap { it.episodeNumbers() }
            )
            .mapNotNull(::parseWholeEpisodeNumber)
            .maxOrNull()
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

    private fun buildAnimeSourceTitleCandidates(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
    ): List<String> {
        val baseTitles = (
            listOfNotNull(anilistMetadata?.title) +
                anilistMetadata?.titleCandidates.orEmpty() +
                listOfNotNull(metadata.title, metadata.originalTitle)
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return (baseTitles + baseTitles.flatMap(::expandTitleCandidate))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { sourceTitleDedupKey(it) }
    }

    private fun expandTitleCandidate(title: String): List<String> {
        val withoutSeason = title
            .replace(
                Regex("""\s*[-–:]?\s*(?:Stagione|Season|Parte|Part|Cour)\s+\d+\b""", RegexOption.IGNORE_CASE),
                "",
            )
            .replace(Regex("""\s+\d+(?:st|nd|rd|th)\s+Season\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Final\s+Season\b""", RegexOption.IGNORE_CASE), "")
        val numericSeason = title.replace(Regex("""\s+(?:Stagione|Season)\s+(\d+)\b""", RegexOption.IGNORE_CASE), " $1")
        val simplifiedPunctuation = title
            .replace(Regex("""[:;!?.,'"“”‘’]+"""), " ")
            .replace(Regex("""\s+"""), " ")
        val words = title.split(Regex("""\s+""")).filter { it.isNotBlank() }

        return listOf(
            title.replace(Regex("""\(\d{4}\)"""), ""),
            numericSeason,
            withoutSeason,
            title.substringBefore(':'),
            title.substringBefore(" - "),
            simplifiedPunctuation,
        ) + if (words.size > 3) listOf(words.take(3).joinToString(" ")) else emptyList()
    }

    private fun sourceTitleDedupKey(title: String): String {
        return normalizeSourceTitle(title).ifBlank { title.lowercase(Locale.ROOT) }
    }

    private fun exactAnimeTitleKeys(
        metadata: StreamCenterMetadata,
        anilistMetadata: AnilistMetadata?,
    ): Set<String> {
        return (
            listOfNotNull(anilistMetadata?.title) +
                anilistMetadata?.titleCandidates.orEmpty() +
                listOfNotNull(metadata.title, metadata.originalTitle)
            )
            .map(::sourceTitleDedupKey)
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun sourceTitleTokens(title: String): Set<String> {
        return sourceTitleDedupKey(title)
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun normalizeSourceTitle(title: String): String {
        return title
            .lowercase(Locale.ROOT)
            .replace("&", "and")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\b(movie|the movie|ita|sub ita|subita|tv|ona|ova|special|season|stagione)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun toAbsoluteProviderUrl(baseUrl: String, href: String): String {
        val cleanedHref = href.trim()
        return when {
            cleanedHref.startsWith("http://") || cleanedHref.startsWith("https://") -> cleanedHref
            cleanedHref.startsWith("//") -> "https:$cleanedHref"
            else -> "${baseUrl.trimEnd('/')}/${cleanedHref.trimStart('/')}"
        }
    }

    private fun AnimeWorldPageData.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId) ||
            (syncIds.kitsuId != null && kitsuId == syncIds.kitsuId)
    }

    private fun List<AnimeWorldPageData>.mergeAnimeWorldEpisodeSources(): Map<String, AnimeWorldPlaybackData> {
        val merged = linkedMapOf<String, AnimeWorldPlaybackData>()
        forEach { pageData ->
            pageData.episodeSources.forEach { (number, playback) ->
                if (!merged.containsKey(number)) {
                    merged[number] = playback
                }
            }
        }
        return merged
    }

    private fun AnimeSaturnPageData.matches(syncIds: AnimeSyncIds): Boolean {
        return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
            (syncIds.malId != null && malId == syncIds.malId) ||
            (syncIds.kitsuId != null && kitsuId == syncIds.kitsuId)
    }

    private fun List<AnimeSaturnPageData>.mergeAnimeSaturnEpisodeSources(): Map<String, AnimeSaturnPlaybackData> {
        val merged = linkedMapOf<String, AnimeSaturnPlaybackData>()
        forEach { pageData ->
            pageData.episodeSources.forEach { (number, playback) ->
                if (!merged.containsKey(number)) {
                    merged[number] = playback
                }
            }
        }
        return merged
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

    private fun AnimeUnityAnime.titleKeys(): Set<String> {
        return listOfNotNull(title, titleEng, titleIt, slug.replace('-', ' '))
            .map { sourceTitleDedupKey(cleanAnimeUnityTitle(it)) }
            .filter { it.isNotBlank() }
            .toSet()
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
            plot = cleanText(optNullableString("plot")),
            imageUrl = optNullableString("imageurl"),
            score = optNullableString("score"),
            type = optNullableString("type"),
            year = optNullableString("date")?.trim()?.take(4)?.toIntOrNull(),
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

    private fun animeSourceDisplayName(provider: String, label: String): String {
        val audio = when {
            label.contains("DUB", ignoreCase = true) -> "\uD83C\uDDEE\uD83C\uDDF9 (DUB)"
            label.contains("SUB", ignoreCase = true) -> "\uD83C\uDDEF\uD83C\uDDF5 (SUB)"
            else -> "Fonte principale"
        }
        return "$provider - $audio"
    }

    private suspend fun loadAnimeUnityLinks(
        playbackData: AnimeUnityPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tasks = buildAnimeUnityPlayerSources(playbackData).map { source ->
            suspend {
                val embedUrl = getAnimeUnityEmbedUrl(source.url)
                if (embedUrl.isNullOrBlank()) {
                    false
                } else {
                    StreamCenterVixCloudExtractor(
                        sourceName = animeSourceDisplayName("VixCloud", source.label),
                        displayName = animeSourceDisplayName("AnimeUnity", source.label),
                    ).getUrl(
                        url = embedUrl,
                        referer = animeUnityUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                    true
                }
            }
        }
        return runParallelSourceTasks(tasks)
    }

    private suspend fun getAnimeUnityEmbedUrl(playerUrl: String): String? {
        val html = getCachedText("GET:$playerUrl", TmdbCacheProfile.AnimeUnityPlayer) {
            app.get(
                playerUrl,
            ).text
        }

        return Jsoup.parse(html, playerUrl)
            .selectFirst("video-player")
            ?.attr("embed_url")
            ?.takeIf(String::isNotBlank)
    }

    private suspend fun loadAnimeWorldLink(
        playbackData: AnimeWorldPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val infoUrl = "$animeWorldUrl/api/episode/info?id=${URLEncoder.encode(playbackData.episodeToken, "UTF-8")}"
        val text = runCatching {
            getCachedText("AW:GET:$infoUrl", TmdbCacheProfile.AnimeWorldPlayer) {
                app.get(
                    infoUrl,
                    headers = headers + mapOf("Referer" to playbackData.pageUrl),
                ).text
            }
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val grabber = json.optNullableString("grabber") ?: return false
        val target = json.optNullableString("target").orEmpty()

        if (target.contains("listeamed.net", ignoreCase = true) ||
            grabber.contains("listeamed.net", ignoreCase = true)
        ) {
            return runCatching {
                loadExtractor(grabber, animeWorldUrl, subtitleCallback, callback)
            }.getOrDefault(false)
        }

        emitDirectVideoLink(
            source = "AnimeWorld",
            name = animeSourceDisplayName("AnimeWorld", playbackData.label),
            url = grabber,
            referer = animeWorldUrl,
            callback = callback,
        )
        return true
    }

    private suspend fun loadAnimeSaturnLink(
        playbackData: AnimeSaturnPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            getCachedText("AS:GET:${playbackData.watchUrl}", TmdbCacheProfile.AnimeSaturnPlayer) {
                app.get(
                    playbackData.watchUrl,
                    headers = headers,
                ).text
            }
        }.getOrNull() ?: return false

        val doc = Jsoup.parse(html, playbackData.watchUrl)
        val embedUrl = doc.selectFirst("iframe#watch-iframe[src], iframe[src*='play.saturncdn.net'][src]")
            ?.attr("src")
            ?.takeIf(String::isNotBlank)
            ?.let { toAbsoluteProviderUrl(animeSaturnUrl, it) }
            ?: Regex(""""initialVideoUrl"\s*:\s*"([^"]+)"""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
            ?: return false

        val videoUrl = getAnimeSaturnVideoUrl(embedUrl) ?: return false
        if (videoUrl.contains("youtube.com/embed/", ignoreCase = true)) {
            return runCatching {
                loadExtractor(videoUrl, animeSaturnUrl, subtitleCallback, callback)
            }.getOrDefault(false)
        }

        emitDirectVideoLink(
            source = "AnimeSaturn",
            name = animeSourceDisplayName("AnimeSaturn", playbackData.label),
            url = videoUrl,
            referer = embedUrl,
            callback = callback,
        )
        return true
    }

    private suspend fun getAnimeSaturnVideoUrl(embedUrl: String): String? {
        val match = Regex("""/embed/(\d+)\?token=([^&]+)&expires=(\d+)""").find(embedUrl) ?: return null
        val id = match.groupValues.getOrNull(1) ?: return null
        val token = match.groupValues.getOrNull(2) ?: return null
        val expires = match.groupValues.getOrNull(3) ?: return null
        val baseUrl = embedUrl.substringBefore("/embed/")
        val playlistUrl = "$baseUrl/embed/$id/playlist?token=$token&expires=$expires"
        val text = runCatching {
            getCachedText("AS:PLAYLIST:$playlistUrl", TmdbCacheProfile.AnimeSaturnPlayer) {
                app.get(
                    playlistUrl,
                    headers = headers + mapOf("Referer" to embedUrl),
                ).text
            }
        }.getOrNull() ?: return null
        val encoded = runCatching { JSONObject(text).optNullableString("d") }.getOrNull() ?: return null
        return decodeAnimeSaturnPayload(encoded, token)?.let { decoded ->
            if (decoded.startsWith("youtube/")) {
                "https://www.youtube.com/embed/${decoded.removePrefix("youtube/")}"
            } else {
                decoded
            }
        }?.takeIf(String::isNotBlank)
    }

    private fun decodeAnimeSaturnPayload(encoded: String, key: String): String? {
        if (key.isBlank()) return null
        val decodedBytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val output = ByteArray(decodedBytes.size)
        decodedBytes.forEachIndexed { index, byte ->
            output[index] = (byte.toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
        }
        return String(output, Charsets.UTF_8)
    }

    private suspend fun loadHentaiWorldLink(
        playbackData: HentaiWorldPlaybackData,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            getCachedText("HW:GET:${playbackData.watchUrl}", TmdbCacheProfile.HentaiWorldPlayer) {
                app.get(
                    playbackData.watchUrl,
                    headers = headers,
                ).text
            }
        }.getOrNull() ?: return false

        val videoUrl = Regex("""const\s+videoUrl\s*=\s*['"]([^'"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""file\s*:\s*['"]([^'"]+)""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
            ?: return false

        emitDirectVideoLink(
            source = "HentaiWorld",
            name = "HentaiWorld",
            url = videoUrl,
            referer = playbackData.watchUrl,
            callback = callback,
        )
        return true
    }

    private suspend fun emitDirectVideoLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(
            newExtractorLink(
                source = source,
                name = name,
                url = url,
                type = INFER_TYPE,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun loadStreamingCommunityLinks(
        playbackData: StreamingCommunityPlaybackData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tasks = mutableListOf<suspend () -> Boolean>()
        val iframeSrc = runCatching {
            val html = getCachedText("SC:GET:${playbackData.iframeUrl}", TmdbCacheProfile.StreamingCommunityPlayer) {
                app.get(
                    playbackData.iframeUrl,
                ).body.string()
            }
            Jsoup.parse(html, playbackData.iframeUrl)
                .selectFirst("iframe")
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

        if (!iframeSrc.isNullOrBlank()) {
            tasks += {
                StreamCenterVixCloudExtractor(
                    sourceName = "VixCloud",
                    displayName = "StreamingCommunity - VixCloud",
                ).getUrl(
                    url = iframeSrc,
                    referer = streamingCommunityRootUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                true
            }
        }

        val vixSrcUrl = buildStreamingCommunityVixSrcUrl(playbackData)
        if (!vixSrcUrl.isNullOrBlank()) {
            tasks += {
                StreamCenterVixSrcExtractor().getUrl(
                    url = vixSrcUrl,
                    referer = "https://vixsrc.to/",
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                true
            }
        }

        return runParallelSourceTasks(tasks)
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

    companion object {
        const val SEARCH_SECTION_MAIN = "main"
        const val SEARCH_SECTION_MOVIES = "movies"
        const val SEARCH_SECTION_SERIES = "series"
        const val SEARCH_SECTION_ANIME = "anime"

        private const val PREF_SC_SESSION = "streamcenter_sc_session"
        private const val PREF_AU_SESSION = "streamcenter_au_session"

        private val tmdbTextCache = ExpiringTextCache(maxEntries = 768)
        private val animeSyncIdsMemoryCache = mutableMapOf<String, List<AnimeSyncIds>>()
        private val animeEpisodeMetadataMemoryCache =
            mutableMapOf<String, AnimeEpisodeMetadataCacheEntry>()
        private var animeMappingsMemoryCache: JSONArray? = null
        private var animeSyncIdsListMemoryCache: List<AnimeSyncIds>? = null
        private var animeSyncIdsByTmdbMemoryCache: Map<String, List<AnimeSyncIds>>? = null
        private var animeSyncIdsByExternalIdMemoryCache: Map<String, List<AnimeSyncIds>>? = null
        private val tmdbScoreMemoryCache = mutableMapOf<String, String>()
        private val streamingCommunityTitleMemoryCache = mutableMapOf<String, StreamingCommunityTitle?>()
        private val streamingCommunitySearchMemoryCache = mutableMapOf<String, List<StreamingCommunityTitle>>()
        private val streamingCommunityDetailMemoryCache = mutableMapOf<String, StreamingCommunityTitle>()
        private val streamingCommunitySeasonMemoryCache = mutableMapOf<String, StreamingCommunitySeason>()
        private val streamingCommunityEpisodePayloadsMemoryCache =
            mutableMapOf<String, Map<Pair<Int, Int>, StreamingCommunityPlaybackData>>()

        private val checkedSourceDomains = mutableSetOf<String>()

        private const val ANILIST_RECOMMENDATIONS_LIMIT = 40
        private const val KITSU_DEFAULT_MAX_PAGES = 15
        private const val KITSU_EXTENDED_MAX_PAGES = 80
        private const val MAL_EPISODES_DEFAULT_MAX_PAGES = 5
        private const val MAL_EPISODES_EXTENDED_MAX_PAGES = 30
        private const val TMDB_LONG_ANIME_EPISODE_THRESHOLD = 300
        private const val ANIME_EPISODE_METADATA_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val SC_SEARCH_PAGE_SIZE = 60
        private const val AU_ARCHIVE_BATCH_SIZE = 30
        private const val AU_ARCHIVE_QUERY_LIMIT = 8
        private const val AU_ARCHIVE_QUERY_LIMIT_PERFORMANCE = 4
        private const val AW_DETAIL_CANDIDATE_LIMIT = 16
        private const val AW_DETAIL_CANDIDATE_LIMIT_PERFORMANCE = 8
        private const val ANIMESATURN_DETAIL_CANDIDATE_LIMIT = 36
        private const val ANIMESATURN_DETAIL_CANDIDATE_LIMIT_PERFORMANCE = 18
        private const val ANIME_SEARCH_QUERY_LIMIT = 12
        private const val ANIME_SEARCH_QUERY_LIMIT_PERFORMANCE = 6
        private const val ANIME_SEARCH_PARALLELISM = 4
        private const val SOURCE_GROUP_TIMEOUT_MS = 8_000L
        private const val SOURCE_GROUP_TIMEOUT_PERFORMANCE_MS = 5_000L

        private val anilistMutex = Mutex()
        private var anilistLastRequestAtMs = 0L

        private const val JIKAN_MIN_INTERVAL_MS = 400L
        private val jikanMutex = Mutex()
        private var jikanLastRequestAtMs = 0L

        private val ANILIST_MEDIA_QUERY = """
            query (${'$'}id: Int, ${'$'}idMal: Int) {
              Media(id: ${'$'}id, idMal: ${'$'}idMal, type: ANIME) {
                id
                idMal
                format
                episodes
                duration
                status
                isAdult
                genres
                averageScore
                season
                seasonYear
                source(version: 3)
                startDate { year }
                bannerImage
                description(asHtml: false)
                title { romaji english native }
                synonyms
                coverImage { extraLarge large medium }
                trailer { id site }
                studios(isMain: true) { nodes { name } }
                nextAiringEpisode { airingAt episode }
                streamingEpisodes { title thumbnail }
                characters(sort: [ROLE, RELEVANCE], perPage: 25) {
                  edges {
                    role
                    node { name { full } image { large medium } }
                    voiceActors(language: JAPANESE, sort: [RELEVANCE]) {
                      name { full }
                      image { large medium }
                    }
                  }
                }
                recommendations(sort: [RATING_DESC], perPage: 40) {
                  nodes {
                    mediaRecommendation {
                      id
                      idMal
                      format
                      title { romaji english }
                      coverImage { large medium }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        private val ANILIST_MEDIA_PERFORMANCE_QUERY = """
            query (${'$'}id: Int, ${'$'}idMal: Int) {
              Media(id: ${'$'}id, idMal: ${'$'}idMal, type: ANIME) {
                id
                idMal
                format
                title { romaji english native }
                synonyms
              }
            }
        """.trimIndent()

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

        private fun sortedEpisodeNumbers(vararg sources: Map<String, *>): List<String> {
            return sources.flatMap { it.keys }
                .distinct()
                .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
        }

        fun clearCaches() {
            tmdbTextCache.clear()
            animeSyncIdsMemoryCache.clear()
            synchronized(animeEpisodeMetadataMemoryCache) {
                animeEpisodeMetadataMemoryCache.clear()
            }
            animeMappingsMemoryCache = null
            animeSyncIdsListMemoryCache = null
            animeSyncIdsByTmdbMemoryCache = null
            animeSyncIdsByExternalIdMemoryCache = null
            tmdbScoreMemoryCache.clear()
            streamingCommunityTitleMemoryCache.clear()
            streamingCommunitySearchMemoryCache.clear()
            streamingCommunityDetailMemoryCache.clear()
            streamingCommunitySeasonMemoryCache.clear()
            streamingCommunityEpisodePayloadsMemoryCache.clear()
            synchronized(checkedSourceDomains) { checkedSourceDomains.clear() }
        }

        suspend fun checkApisAvailability(sharedPref: SharedPreferences?): List<Pair<String, Boolean>> = coroutineScope {
            fun sourceUrl(key: String) = StreamCenterPlugin.getSourceBaseUrl(sharedPref, key)
            val scUrl = sourceUrl(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
            val auUrl = sourceUrl(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)
            val awUrl = sourceUrl(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)
            val asUrl = sourceUrl(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)

            val checks: List<Pair<String, suspend () -> Boolean>> = listOf(
                "AniList" to { isAnilistApiAvailable() },
                "MyAnimeList (Jikan)" to { jsonApiReachable("https://api.jikan.moe/v4/anime/1", "application/json") },
                "Kitsu" to { jsonApiReachable("https://kitsu.io/api/edge/anime/1", "application/vnd.api+json") },
                "TMDB" to { urlReachable("https://www.themoviedb.org") },
                "StreamingCommunity" to { urlReachable("$scUrl/it") },
                "AnimeUnity" to { urlReachable(auUrl) },
                "AnimeWorld" to { urlReachable(awUrl) },
                "AnimeSaturn" to { urlReachable(asUrl) },
                "Mappe anime (Fribb)" to {
                    urlReachable("https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json")
                },
            )

            checks.map { (name, check) ->
                async(Dispatchers.IO) { name to runCatching { check() }.getOrDefault(false) }
            }.awaitAll()
        }

        private suspend fun isAnilistApiAvailable(): Boolean {
            return runCatching {
                val body = JSONObject()
                    .put("query", "query { Media(id: 1, type: ANIME) { id } }")
                    .toString()
                val response = app.post(
                    "https://graphql.anilist.co",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                    ),
                    requestBody = body.toRequestBody("application/json".toMediaType()),
                ).text
                JSONObject(response).optJSONObject("data")?.opt("Media") != null
            }.getOrDefault(false)
        }

        private val availabilityCheckHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        )

        private suspend fun jsonApiReachable(url: String, accept: String): Boolean {
            return runCatching {
                val text = app.get(
                    url,
                    headers = availabilityCheckHeaders + ("Accept" to accept),
                    timeout = 15L,
                ).text
                JSONObject(text).has("data")
            }.getOrDefault(false)
        }

        private suspend fun urlReachable(url: String): Boolean {
            if (url.isBlank()) return false
            return runCatching {
                app.get(url, headers = availabilityCheckHeaders, timeout = 15L).code in 200..399
            }.getOrDefault(false)
        }

        private fun readSessionPayload(prefs: SharedPreferences?, key: String): JSONObject? {
            val raw = prefs?.getString(key, null) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                val expiresAt = json.optLong("expiresAt", 0L)
                if (expiresAt in 1..System.currentTimeMillis()) {
                    prefs.edit().remove(key).apply()
                    return null
                }
                json
            }.getOrNull()
        }

        private fun writeSessionPayload(
            prefs: SharedPreferences?,
            key: String,
            payload: JSONObject,
            ttlMs: Long,
        ) {
            prefs ?: return
            payload.put("expiresAt", System.currentTimeMillis() + ttlMs)
            prefs.edit().putString(key, payload.toString()).apply()
        }
    }
}
