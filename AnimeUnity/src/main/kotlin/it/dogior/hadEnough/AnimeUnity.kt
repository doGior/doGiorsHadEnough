package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Str = BooleanOrString.AsString

@Suppress("unused")
class AnimeUnity(
    private val sharedPref: SharedPreferences?,
) : MainAPI() {
    override var mainUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        get() = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        set(value) {
            field = value
        }
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        @Suppress("ConstPropertyName")
        const val mainUrl = "https://www.animeunity.so"
        const val ARCHIVE_BATCH_SIZE = 30
        const val advancedSearchSectionName = "Ricerca avanzata"
        const val latestEpisodesSectionName = "Ultimi Episodi"
        const val calendarSectionName = "Calendario"
        const val randomSectionName = "Random"
        const val ongoingSectionName = "In Corso"
        const val popularSectionName = "Popolari"
        const val bestSectionName = "I migliori"
        const val upcomingSectionName = "In Arrivo"
        
        var name = "AnimeUnity"
        var headers = mapOf(
            "Host" to mainUrl.toHttpUrl().host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        ).toMutableMap()
    }

    private data class ArchivePageResult(
        val titles: List<Anime>,
        val hasNextPage: Boolean,
    )

    override val mainPage: List<MainPageData>
        get() = buildSectionNamesList()

    private fun buildSectionNamesList(): List<MainPageData> {
        val order = AnimeUnityPlugin.getConfiguredSectionOrder(sharedPref)
        val sections = buildList {
            if (AnimeUnityPlugin.isAdvancedSearchEnabled(sharedPref)) {
                add("advanced")
            }
            addAll(order.split(","))
        }
        
        return mainPageOf(
            *sections.mapNotNull { section ->
                when (section) {
                    "advanced" -> "$mainUrl/archivio/" to advancedSearchSectionName
                    "latest" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES)) "$mainUrl/" to latestEpisodesSectionName else null
                    "calendar" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_CALENDAR)) "$mainUrl/calendario" to calendarSectionName else null
                    "ongoing" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_ONGOING)) "$mainUrl/archivio/" to ongoingSectionName else null
                    "popular" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_POPULAR)) "$mainUrl/archivio/" to popularSectionName else null
                    "best" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_BEST)) "$mainUrl/archivio/" to bestSectionName else null
                    "upcoming" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_UPCOMING)) "$mainUrl/archivio/" to upcomingSectionName else null
                    "random" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_RANDOM)) "$mainUrl/archivio/" to randomSectionName else null
                    else -> null
                }
            }.toTypedArray()
        )
    }

    private fun isSectionEnabled(prefKey: String): Boolean {
        return sharedPref?.getBoolean(prefKey, true) ?: true
    }

    private fun getSectionCount(sectionName: String): Int {
        val (key, defaultCount) = when (sectionName) {
            advancedSearchSectionName -> AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT to
                AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT
            latestEpisodesSectionName -> AnimeUnityPlugin.PREF_LATEST_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            calendarSectionName -> AnimeUnityPlugin.PREF_CALENDAR_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            ongoingSectionName -> AnimeUnityPlugin.PREF_ONGOING_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            popularSectionName -> AnimeUnityPlugin.PREF_POPULAR_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            bestSectionName -> AnimeUnityPlugin.PREF_BEST_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            upcomingSectionName -> AnimeUnityPlugin.PREF_UPCOMING_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            randomSectionName -> AnimeUnityPlugin.PREF_RANDOM_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            else -> return AnimeUnityPlugin.DEFAULT_SECTION_COUNT
        }
        return (sharedPref?.getInt(key, defaultCount)
            ?: defaultCount).coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
    }

    private fun shouldShowScore(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_SCORE, true) ?: true
    }

    private fun shouldShowDubSub(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, true) ?: true
    }

    private fun shouldShowEpisodeNumber(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, true) ?: true
    }

    private fun withoutDubSuffix(title: String): String {
        return title.replace(" (ITA)", "")
    }

    private fun getShowStatus(status: String?): ShowStatus? {
        return when (status?.trim()?.lowercase(Locale.ROOT)) {
            "terminato" -> ShowStatus.Completed
            "in corso" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun getStatusTag(status: String?): String? {
        val normalizedStatus = status?.trim().orEmpty()
        if (normalizedStatus.isBlank()) return null
        return if (getShowStatus(normalizedStatus) == null) "Stato: $normalizedStatus" else null
    }

    private fun buildDisplayTitle(title: String, episodeNumber: Int?): String {
        val baseTitle = withoutDubSuffix(title)

        return if (!shouldShowDubSub() && shouldShowEpisodeNumber() && episodeNumber != null) {
            "$baseTitle - Ep. $episodeNumber"
        } else {
            baseTitle
        }
    }

    private fun applyCardDisplayState(
        response: AnimeSearchResponse,
        dubbed: Boolean,
        poster: String?,
        score: String?,
        episodeNumber: Int? = null,
    ) {
        if (shouldShowDubSub()) {
            if (shouldShowEpisodeNumber()) {
                response.addDubStatus(dubbed, episodeNumber)
            } else {
                response.addDubStatus(dubbed)
            }
        }

        response.addPoster(poster)

        if (shouldShowScore()) {
            score?.let {
                response.score = Score.from(it, 10)
            }
        }
    }

    private suspend fun ensureHeadersAndCookies(forceReset: Boolean = false) {
        val currentHost = mainUrl.toHttpUrl().host
        val shouldRefreshHeaders = forceReset ||
            headers["Host"] != currentHost ||
            headers["Referer"] != mainUrl ||
            !headers.containsKey("Cookie")

        if (shouldRefreshHeaders) {
            resetHeadersAndCookies()
            setupHeadersAndCookies()
        }
    }

    private suspend fun setupHeadersAndCookies() {
        val response = app.get("$mainUrl/archivio", headers = headers)

        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies =
            "XSRF-TOKEN=${response.cookies["XSRF-TOKEN"]}; animeunity_session=${response.cookies["animeunity_session"]}"
        val h = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json;charset=utf-8",
            "X-CSRF-Token" to csrfToken,
            "Referer" to mainUrl,
            "Cookie" to cookies
        )
        headers.putAll(h)
    }

    private fun resetHeadersAndCookies() {
        if (headers.isNotEmpty()) {
            headers.clear()
        }
        headers["Host"] = mainUrl.toHttpUrl().host
        headers["User-Agent"] =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
    }

    private suspend fun searchResponseBuilder(objectList: List<Anime>, episodeNumber: Int? = null): List<SearchResponse> {
        return objectList.amap { anime ->
            val title = (anime.titleIt ?: anime.titleEng ?: anime.title!!)
            val poster = getImage(anime.imageUrl, anime.anilistId)

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, episodeNumber),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    dubbed = anime.dub == 1 || title.contains("(ITA)"),
                    poster = poster,
                    score = anime.score,
                    episodeNumber = episodeNumber
                )
            }
        }
    }

    private suspend fun fetchArchiveBatch(url: String, requestData: RequestData): ApiResponse {
        val response = app.post(url, headers = headers, requestBody = requestData.toRequestBody())
        return parseJson<ApiResponse>(response.text)
    }

    private suspend fun fetchArchiveSectionPage(
        url: String,
        requestData: RequestData,
        page: Int,
        sectionCount: Int,
    ): ArchivePageResult {
        val pageStartOffset = (page - 1) * sectionCount
        val collectedTitles = mutableListOf<Anime>()
        var nextOffset = pageStartOffset
        var total = 0

        while (collectedTitles.size < sectionCount) {
            val responseObject = fetchArchiveBatch(url, requestData.copy(offset = nextOffset))
            total = responseObject.total

            val batchTitles = responseObject.titles.orEmpty()
            if (batchTitles.isEmpty()) break

            collectedTitles += batchTitles.take(sectionCount - collectedTitles.size)
            nextOffset += batchTitles.size

            if (nextOffset >= total || batchTitles.size < ARCHIVE_BATCH_SIZE) {
                break
            }
        }

        return ArchivePageResult(
            titles = collectedTitles,
            hasNextPage = total > pageStartOffset + collectedTitles.size,
        )
    }

    private suspend fun fetchRandomTitles(url: String, sectionCount: Int): Pair<List<Anime>, Int> {
        val requestData = RequestData(dubbed = 0)
        val initialResponse = fetchArchiveBatch(url, requestData.copy(offset = 0))
        val total = initialResponse.total
        val collectedTitles = linkedMapOf<Int, Anime>()
        val requestedOffsets = mutableSetOf<Int>()
        val maxAttempts = ((sectionCount + ARCHIVE_BATCH_SIZE - 1) / ARCHIVE_BATCH_SIZE) * 3

        fun collectBatch(batch: List<Anime>) {
            batch.forEach { anime ->
                collectedTitles.putIfAbsent(anime.id, anime)
            }
        }

        if (total <= ARCHIVE_BATCH_SIZE) {
            collectBatch(initialResponse.titles.orEmpty())
            return collectedTitles.values.shuffled().take(sectionCount) to total
        }

        repeat(maxAttempts) {
            if (collectedTitles.size >= sectionCount) {
                return@repeat
            }

            val maxOffset = (total - ARCHIVE_BATCH_SIZE).coerceAtLeast(0)
            val randomOffset = if (maxOffset == 0) 0 else (0..maxOffset).random()
            if (!requestedOffsets.add(randomOffset)) {
                return@repeat
            }

            collectBatch(fetchArchiveBatch(url, requestData.copy(offset = randomOffset)).titles.orEmpty())
        }

        if (collectedTitles.isEmpty()) {
            collectBatch(initialResponse.titles.orEmpty())
        }

        return collectedTitles.values.shuffled().take(sectionCount) to total
    }

    private suspend fun latestEpisodesResponseBuilder(objectList: List<LatestEpisodeItem>): List<SearchResponse> {
        return objectList.amap { item ->
            val anime = item.anime
            val title = (anime.titleIt ?: anime.titleEng ?: anime.title!!)
            val poster = getImage(anime.imageUrl, anime.anilistId)

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, item.number.toIntOrNull()),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    dubbed = anime.dub == 1 || title.contains("(ITA)"),
                    poster = poster,
                    score = anime.score,
                    episodeNumber = item.number.toIntOrNull()
                )
            }
        }
    }

    private fun getImageCdnHost(): String {
        val host = mainUrl.toHttpUrl().host
        return when {
            host == "animeunity.so" -> "img.animeunity.so"
            host.startsWith("www.") -> host.replaceFirst("www.", "img.")
            host.startsWith("img.") -> host
            else -> "img.$host"
        }
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        if (!imageUrl.isNullOrEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageCdnHost()}/anime/$fileName"
            } catch (_: Exception) {}
        }
        return anilistId?.let { getAnilistPoster(it) }
    }

    private suspend fun getAnilistPoster(anilistId: Int): String {
        val query = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                coverImage {
                    large
                    medium
                }
            }
        }
    """.trimIndent()

        val body = mapOf("query" to query, "variables" to """{"id":$anilistId}""")
        val response = app.post("https://graphql.anilist.co", data = body)
        val anilistObj = parseJson<AnilistResponse>(response.text)

        return anilistObj.data.media.coverImage?.let { coverImage ->
            coverImage.large ?: coverImage.medium!!
        } ?: throw IllegalStateException("No valid image found")
    }

    private suspend fun getTrailerUrl(anime: Anime): String? {
        getAniListTrailer(anime)?.let { return it }
        return getJikanTrailer(anime)
    }

    private suspend fun getAniListTrailer(anime: Anime): String? {
        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title

        val (query, variables) = when {
            anime.anilistId != null -> {
                """
                query (${'$'}id: Int) {
                    Media(id: ${'$'}id, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"id":${anime.anilistId}}"""
            }

            anime.malId != null -> {
                """
                query (${'$'}idMal: Int) {
                    Media(idMal: ${'$'}idMal, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"idMal":${anime.malId}}"""
            }

            !searchTitle.isNullOrBlank() -> {
                """
                query (${'$'}search: String) {
                    Media(search: ${'$'}search, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"search":${org.json.JSONObject.quote(searchTitle)}}"""
            }

            else -> return null
        }

        val body = mapOf("query" to query, "variables" to variables)
        val response = runCatching { app.post("https://graphql.anilist.co", data = body).text }.getOrNull()
            ?: return null
        val media = runCatching { parseJson<AnilistResponse>(response).data.media }.getOrNull()
            ?: return null

        return normalizeAniListTrailerUrl(media.trailer)
    }

    private fun normalizeAniListTrailerUrl(trailer: AnilistTrailer?): String? {
        if (trailer?.site?.equals("youtube", ignoreCase = true) == true && !trailer.id.isNullOrBlank()) {
            return "https://www.youtube.com/watch?v=${trailer.id}"
        }

        return null
    }

    private fun normalizeTrailerUrl(trailer: JikanTrailer?): String? {
        val directUrl = trailer?.url?.takeIf(String::isNotBlank)
        if (directUrl != null) return directUrl

        val youtubeId = trailer?.youtubeId?.takeIf(String::isNotBlank)
            ?: trailer?.embedUrl
                ?.substringAfter("/embed/", "")
                ?.substringBefore("?")
                ?.substringBefore("/")
                ?.takeIf(String::isNotBlank)

        if (youtubeId != null) {
            return "https://www.youtube.com/watch?v=$youtubeId"
        }

        return trailer?.embedUrl?.takeIf(String::isNotBlank)
    }

    private fun normalizeJikanTitle(title: String): String {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
    }

    private suspend fun getJikanTrailer(anime: Anime): String? {
        anime.malId?.let { malId ->
            val url = "https://api.jikan.moe/v4/anime/$malId/full"
            val response = runCatching { app.get(url).text }.getOrNull() ?: return null
            val trailer = runCatching { parseJson<JikanFullResponse>(response).data.trailer }.getOrNull()
            return normalizeTrailerUrl(trailer)
        }

        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title ?: return null
        val searchUrl = "https://api.jikan.moe/v4/anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchTitle)
            .addQueryParameter("limit", "5")
            .build()
            .toString()

        val response = runCatching { app.get(searchUrl).text }.getOrNull() ?: return null
        val candidates = runCatching { parseJson<JikanSearchResponse>(response).data }.getOrNull().orEmpty()
        if (candidates.isEmpty()) return null

        val searchTitles = listOfNotNull(anime.titleIt, anime.titleEng, anime.title)
            .map { normalizeJikanTitle(it) }
            .filter { it.isNotBlank() }

        return candidates.firstNotNullOfOrNull { candidate ->
            val trailerUrl = normalizeTrailerUrl(candidate.trailer) ?: return@firstNotNullOfOrNull null
            val candidateTitles = buildList {
                add(candidate.title)
                candidate.titleEnglish?.let(::add)
                candidate.titleJapanese?.let(::add)
                addAll(candidate.titleSynonyms.orEmpty())
            }.map(::normalizeJikanTitle)

            trailerUrl.takeIf {
                searchTitles.isEmpty() || searchTitles.any(candidateTitles::contains)
            }
        } ?: candidates.firstNotNullOfOrNull { normalizeTrailerUrl(it.trailer) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == latestEpisodesSectionName) {
            return getLatestEpisodesMainPage(page)
        }
        if (request.name.startsWith(calendarSectionName)) {
            return getCalendarMainPage(page, request)
        }
        if (request.name == randomSectionName) {
            return getRandomMainPage(page)
        }

        val url = request.data + "get-animes"
        ensureHeadersAndCookies()

        val requestData = getDataPerHomeSection(request.name)
        val sectionCount = getSectionCount(request.name)
        val archivePage = fetchArchiveSectionPage(url, requestData, page, sectionCount)
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = if (archivePage.titles.isNotEmpty()) {
                    searchResponseBuilder(archivePage.titles)
                } else {
                    emptyList()
                },
                isHorizontalImages = false
            ),
            archivePage.hasNextPage
        )
    }

    private suspend fun getLatestEpisodesMainPage(page: Int): HomePageResponse {
        val sectionCount = getSectionCount(latestEpisodesSectionName)
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = latestEpisodesSectionName, list = emptyList(), isHorizontalImages = false),
                false
            )
        }

        val latestEpisodesJson = app.get("$mainUrl/?page=1").document
            .selectFirst("#ultimi-episodi layout-items")
            ?.attr("items-json")
            .orEmpty()

        val latestEpisodes = latestEpisodesJson
            .takeIf(String::isNotBlank)
            ?.let { json ->
                runCatching { parseJson<LatestEpisodesPage>(json).episodes }.getOrDefault(emptyList())
            }
            ?.take(sectionCount)
            ?: emptyList()

        return newHomePageResponse(
            HomePageList(
                name = latestEpisodesSectionName,
                list = latestEpisodesResponseBuilder(latestEpisodes),
                isHorizontalImages = false
            ),
            false
        )
    }

    private suspend fun getCalendarMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentDay = getCurrentItalianDayName()
        val calendarTitle = "$calendarSectionName ($currentDay)"
        val sectionCount = getSectionCount(calendarSectionName)

        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = calendarTitle, list = emptyList(), isHorizontalImages = false),
                false
            )
        }

        val calendarAnime = app.get(request.data).document
            .select("calendario-item")
            .mapNotNull { item ->
                val animeJson = item.attr("a")
                if (animeJson.isBlank()) return@mapNotNull null

                val anime = runCatching { parseJson<Anime>(animeJson) }.getOrNull() ?: return@mapNotNull null
                val episodeNumber = extractCalendarEpisodeNumber(item, anime)

                if (normalizeDayName(anime.day) == normalizeDayName(currentDay)) {
                    anime to episodeNumber
                } else {
                    null
                }
            }
            .distinctBy { it.first.id }
            .take(sectionCount)

        return newHomePageResponse(
            HomePageList(
                name = calendarTitle,
                list = calendarAnime.amap { (anime, ep) ->
                    searchResponseBuilder(listOf(anime), ep).first()
                },
                isHorizontalImages = false
            ),
            false
        )
    }

    private fun extractCalendarEpisodeNumber(item: Element, anime: Anime): Int? {
        item.attr("episodes_count")
            .trim()
            .toIntOrNull()
            ?.let { releasedEpisodes ->
                return releasedEpisodes + 1
            }

        return anime.episodes
            ?.mapNotNull { it.number.toIntOrNull() }
            ?.maxOrNull()
            ?.plus(1)
    }

    private suspend fun getRandomMainPage(page: Int): HomePageResponse {
        val url = "$mainUrl/archivio/get-animes"
        ensureHeadersAndCookies()

        val sectionCount = getSectionCount(randomSectionName)
        val (titles, total) = fetchRandomTitles(url, sectionCount)

        return newHomePageResponse(
            HomePageList(
                name = randomSectionName,
                list = searchResponseBuilder(titles),
                isHorizontalImages = false
            ),
            page < 5 && total > sectionCount
        )
    }

    private fun getCurrentItalianDayName(): String {
        val formatter = SimpleDateFormat("EEEE", Locale.ITALIAN)
        return formatter.format(Date()).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString()
        }
    }

    private fun normalizeDayName(dayName: String?): String {
        return Normalizer.normalize(dayName.orEmpty(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun getDataPerHomeSection(section: String) = when (section) {
        advancedSearchSectionName -> AnimeUnityPlugin.getAdvancedSearchRequestData(sharedPref)
        popularSectionName -> RequestData(orderBy = Str("Popolarità"), dubbed = 0)
        upcomingSectionName -> RequestData(status = Str("In Uscita"), dubbed = 0)
        bestSectionName -> RequestData(orderBy = Str("Valutazione"), dubbed = 0)
        ongoingSectionName -> RequestData(orderBy = Str("Popolarità"), status = Str("In Corso"), dubbed = 0)
        else -> RequestData()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/archivio/get-animes"
        ensureHeadersAndCookies(forceReset = true)

        val requestBody = RequestData(title = query, dubbed = 0).toRequestBody()
        val response = app.post(url, headers = headers, requestBody = requestBody)

        val responseObject = parseJson<ApiResponse>(response.text)
        val titles = responseObject.titles ?: emptyList()

        return searchResponseBuilder(titles)
    }

    override suspend fun load(url: String): LoadResponse {
        ensureHeadersAndCookies(forceReset = true)
        val animePage = app.get(url).document

        val relatedAnimeJsonArray = animePage.select("layout-items").attr("items-json")
        val relatedAnime = parseJson<List<Anime>>(relatedAnimeJsonArray)

        val videoPlayer = animePage.select("video-player")
        val anime = parseJson<Anime>(videoPlayer.attr("anime"))

        val eps = parseJson<List<Episode>>(videoPlayer.attr("episodes"))
        val totalEps = videoPlayer.attr("episodes_count").toInt()
        val isEpNumberMultipleOfRange = totalEps % 120 == 0
        val range = if (isEpNumberMultipleOfRange) totalEps / 120 else (totalEps / 120) + 1
        
        val episodes = eps.map {
            newEpisode("$url/${it.id}") { this.episode = it.number.toIntOrNull() }
        }.toMutableList()

        if (totalEps > 120) {
            for (i in 2..range) {
                val endRange = if (i == range) totalEps else i * 120
                val infoUrl = "$mainUrl/info_api/${anime.id}/1?start_range=${1 + (i - 1) * 120}&end_range=${endRange}"
                val info = app.get(infoUrl).text
                val animeInfo = parseJson<AnimeInfo>(info)
                episodes.addAll(animeInfo.episodes.map {
                    newEpisode("$url/${it.id}") { this.episode = it.number.toIntOrNull() }
                })
            }
        }
        val title = anime.titleIt ?: anime.titleEng ?: anime.title!!
        val relatedAnimes = relatedAnime.amap {
            val relatedTitle = (it.titleIt ?: it.titleEng ?: it.title!!)
            val poster = getImage(it.imageUrl, it.anilistId)
            newAnimeSearchResponse(
                name = withoutDubSuffix(relatedTitle),
                url = "$mainUrl/anime/${it.id}-${it.slug}",
                type = if (it.type == "TV") TvType.Anime
                else if (it.type == "Movie" || it.episodesCount == 1) TvType.AnimeMovie
                else TvType.OVA
            ) {
                if (shouldShowDubSub()) {
                    addDubStatus(it.dub == 1 || relatedTitle.contains("(ITA)"))
                }
                addPoster(poster)
            }
        }
        val trailerUrl = getTrailerUrl(anime)

        return newAnimeLoadResponse(
            name = title.replace(" (ITA)", ""),
            url = url,
            type = if (anime.type == "TV") TvType.Anime
            else if (anime.type == "Movie" || anime.episodesCount == 1) TvType.AnimeMovie
            else TvType.OVA,
        ) {
            this.posterUrl = getImage(anime.imageUrl, anime.anilistId)
            anime.cover?.let { this.backgroundPosterUrl = getBanner(it) }
            this.year = anime.date.toInt()
            addScore(anime.score)
            addDuration(anime.episodesLength.toString() + " minuti")
            val dub = if (anime.dub == 1) DubStatus.Dubbed else DubStatus.Subbed
            addEpisodes(dub, episodes)
            addAniListId(anime.anilistId)
            addMalId(anime.malId)
            if (trailerUrl != null) {
                addTrailer(trailerUrl)
            }
            this.showStatus = getShowStatus(anime.status)
            this.plot = anime.plot
            val doppiato = if (anime.dub == 1 || title.contains("(ITA)")) "\uD83C\uDDEE\uD83C\uDDF9  Italiano" else "\uD83C\uDDEF\uD83C\uDDF5  Giapponese"
            this.tags = listOfNotNull(doppiato, getStatusTag(anime.status)) + anime.genres.map { genre ->
                genre.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            this.comingSoon = anime.status == "In uscita prossimamente"
            this.recommendations = relatedAnimes
        }
    }

    private fun getBanner(imageUrl: String): String {
        if (imageUrl.isNotEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageCdnHost()}/anime/$fileName"
            } catch (_: Exception) {}
        }
        return imageUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        val sourceUrl = document.select("video-player").attr("embed_url")
        VixCloudExtractor().getUrl(
            url = sourceUrl,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return true
    }
}
