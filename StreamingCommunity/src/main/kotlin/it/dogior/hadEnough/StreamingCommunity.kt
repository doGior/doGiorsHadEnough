package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class StreamingCommunity(
    override var lang: String = "it",
    customBaseUrl: String? = null,
) : MainAPI() {
    private val siteRootUrl = resolveBaseUrl(customBaseUrl)
    private val siteHost = siteRootUrl.toHttpUrl().host
    private val cdnHost = resolveCdnHost(siteHost)
    private var inertiaVersion = ""
    private var decodedXsrfToken = ""
    private val headers = mapOf(
        "Cookie" to "",
        "X-Inertia" to true.toString(),
        "X-Inertia-Version" to inertiaVersion,
        "X-Requested-With" to "XMLHttpRequest",
    ).toMutableMap()

    override var mainUrl = siteRootUrl + lang
    override var name = Companion.name
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override val hasMainPage = true

    companion object {
        const val DEFAULT_BASE_URL = "https://streamingcommunityz.ooo/"
        var name = "StreamingCommunity"
        const val TAG = "SCommunity"

        fun normalizeBaseUrl(rawUrl: String?): String? {
            val trimmedValue = rawUrl?.trim().orEmpty()
            if (trimmedValue.isBlank()) return null

            val candidate = if ("://" in trimmedValue) trimmedValue else "https://$trimmedValue"

            return runCatching {
                val normalizedUrl = candidate.toHttpUrl()
                val rewrittenHost = normalizeKnownHost(normalizedUrl.host)

                normalizedUrl.newBuilder()
                    .host(rewrittenHost)
                    .encodedPath("/")
                    .query(null)
                    .fragment(null)
                    .build()
                    .toString()
            }.getOrNull()
        }

        fun resolveBaseUrl(rawUrl: String?): String {
            return normalizeBaseUrl(rawUrl) ?: DEFAULT_BASE_URL
        }

        private fun resolveCdnHost(siteHost: String): String {
            val fallbackHost = DEFAULT_BASE_URL.toHttpUrl().host
            return if (isIpAddress(siteHost) || siteHost.equals("localhost", ignoreCase = true)) {
                "cdn.$fallbackHost"
            } else {
                "cdn.$siteHost"
            }
        }

        private fun normalizeKnownHost(host: String): String {
            return when (host.lowercase()) {
                "streamingunity.biz",
                "www.streamingunity.biz" -> DEFAULT_BASE_URL.toHttpUrl().host
                else -> host
            }
        }

        private fun isIpAddress(host: String): Boolean {
            val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
            return ipv4Regex.matches(host) || host.contains(":")
        }
    }

    private val sliderFetchRequestBody = SliderFetchRequestBody(
        sliders = listOf(
            SliderFetchRequestSlider(name = "top10", genre = null),
            SliderFetchRequestSlider(name = "trending", genre = null),
            SliderFetchRequestSlider(name = "latest", genre = null),
            SliderFetchRequestSlider(name = "upcoming", genre = null),
            SliderFetchRequestSlider(name = "genre", genre = "Animation"),
            SliderFetchRequestSlider(name = "genre", genre = "Adventure"),
            SliderFetchRequestSlider(name = "genre", genre = "Action"),
            SliderFetchRequestSlider(name = "genre", genre = "Comedy"),
            SliderFetchRequestSlider(name = "genre", genre = "Crime"),
            SliderFetchRequestSlider(name = "genre", genre = "Documentary"),
            SliderFetchRequestSlider(name = "genre", genre = "Drama"),
            SliderFetchRequestSlider(name = "genre", genre = "Family"),
            SliderFetchRequestSlider(name = "genre", genre = "Science Fiction"),
            SliderFetchRequestSlider(name = "genre", genre = "Fantasy"),
            SliderFetchRequestSlider(name = "genre", genre = "Horror"),
            SliderFetchRequestSlider(name = "genre", genre = "Reality"),
            SliderFetchRequestSlider(name = "genre", genre = "Romance"),
            SliderFetchRequestSlider(name = "genre", genre = "Thriller")
        )
    )

    private suspend fun fetchSliderSectionsInBatches(): List<HomePageList> {
        val maxSlidersPerRequest = 6
        val allSections = mutableListOf<HomePageList>()

        sliderFetchRequestBody.sliders
            .chunked(maxSlidersPerRequest)
            .forEachIndexed { index, sliderBatch ->
                val response = app.post(
                    "${siteRootUrl}api/sliders/fetch?lang=$lang",
                    requestBody = SliderFetchRequestBody(sliderBatch).toRequestBody(),
                    headers = getSliderFetchHeaders()
                )

                val payload = response.body.string()
//                Log.d(TAG, "Slider fetch batch=${index + 1} status=${response.code} size=${sliderBatch.size}")
//                Log.d(TAG, "Slider fetch batch=${index + 1} preview=${payload.take(500)}")

                allSections += parseSliderFetchSections(payload)
            }

        return allSections
    }

    private fun isHtmlPayload(payload: String): Boolean {
        val trimmed = payload.trimStart()
        return trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)
    }

    private fun extractInertiaPageJson(html: String): String? {
        val dataPageRaw = org.jsoup.Jsoup.parse(html).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    private fun parseInertiaPayload(payload: String, logContext: String): InertiaResponse? {
        if (payload.isBlank()) {
            Log.e(TAG, "$logContext: empty payload")
            return null
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: expected JSON but received HTML payload")
            return null
        }
        return runCatching { parseJson<InertiaResponse>(payload) }
            .onFailure { Log.e(TAG, "$logContext: invalid JSON payload - ${it.message}") }
            .getOrNull()
    }

    private fun parseBrowseTitles(payload: String, logContext: String): List<Title> {
        val jsonPayload = if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: received HTML payload, attempting embedded data-page fallback")
            extractInertiaPageJson(payload) ?: return emptyList()
        } else {
            payload
        }

        val result = parseInertiaPayload(jsonPayload, logContext) ?: return emptyList()
        return result.props.titles ?: emptyList()
    }

    private fun parseSliderFetchSections(payload: String): List<HomePageList> {
        if (payload.isBlank()) return emptyList()
        val trimmedPayload = payload.trimStart()
        if (trimmedPayload.startsWith("{") || trimmedPayload.contains("\"message\"")) {
            Log.e(
                TAG,
                "Sliders fetch: received error object instead of slider array: ${payload.take(300)}"
            )
            return emptyList()
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "Sliders fetch: expected JSON array but received HTML payload")
            return emptyList()
        }

        val sliders = runCatching { parseJson<List<Slider>>(payload) }
            .onFailure { Log.e(TAG, "Sliders fetch: invalid JSON payload - ${it.message}") }
            .getOrNull()
            ?: return emptyList()

        return sliders.mapNotNull { slider ->
            val items = searchResponseBuilder(slider.titles)
            if (items.isEmpty()) return@mapNotNull null
            HomePageList(
                name = slider.label.ifBlank { slider.name },
                list = items,
                isHorizontalImages = false
            )
        }
    }

    private suspend fun setupHeaders() {
        val response = app.get("$mainUrl/archive")
        val cookieJar = linkedMapOf<String, String>()
        response.cookies.forEach { cookieJar[it.key] = it.value }

        val csrfResponse = app.get(
            "${siteRootUrl}sanctum/csrf-cookie",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest"
            )
        )
        csrfResponse.cookies.forEach { cookieJar[it.key] = it.value }

        headers["Cookie"] = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        decodedXsrfToken = cookieJar["XSRF-TOKEN"]
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: ""

        val page = response.document
        val inertiaPageObject = page.select("#app").attr("data-page")
        inertiaVersion = inertiaPageObject
            .substringAfter("\"version\":\"")
            .substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    private fun getSliderFetchHeaders(): Map<String, String> {
        return mapOf(
            "Cookie" to (headers["Cookie"] ?: ""),
            "X-Requested-With" to "XMLHttpRequest",
            "X-XSRF-TOKEN" to decodedXsrfToken,
            "Referer" to "$mainUrl/",
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "Origin" to siteRootUrl.removeSuffix("/")
        )
    }

    private fun searchResponseBuilder(listJson: List<Title>): List<SearchResponse> {
        val list: List<SearchResponse> =
            listJson.filter { it.type == "movie" || it.type == "tv" }.map { title ->
                val url = "$mainUrl/titles/${title.id}-${title.slug}"

                if (title.type == "tv") {
                    newTvSeriesSearchResponse(title.name, url) {
                        posterUrl = "https://$cdnHost/images/" + title.getPoster()
                    }
                } else {
                    newMovieSearchResponse(title.name, url) {
                        posterUrl = "https://$cdnHost/images/" + title.getPoster()
                    }
                }
            }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }

        val lazySections = fetchSliderSectionsInBatches()
        if (lazySections.isEmpty()) {
            Log.d(TAG, "Lazy slider fetch returned no sections")
        }

        return newHomePageResponse(lazySections, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search"
        val response = app.get(url, params = mapOf("q" to query)).body.string()
        val titles = parseBrowseTitles(response, "Search")
        return searchResponseBuilder(titles)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val params = mutableMapOf("q" to query)
        if (page > 1) params["page"] = page.toString()
        val response = app.get("$mainUrl/search", params = params).body.string()
        val titles = parseBrowseTitles(response, "Search page=$page")
        val items = searchResponseBuilder(titles)
        val hasNext = items.isNotEmpty() && items.size >= 60
        return newSearchResponseList(items, hasNext = hasNext)
    }

    private suspend fun getPoster(title: TitleProp): String? {
        if (title.tmdbId != null) {
            val tmdbUrl = "https://www.themoviedb.org/${title.type}/${title.tmdbId}"
            val resp = app.get(tmdbUrl).document
            val img = resp.select("img.poster.w-full").attr("srcset").split(", ").last()
            return img
        } else {
            return title.getBackgroundImageId().let { "https://$cdnHost/images/$it" }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val actualUrl = getActualUrl(url)
        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }
        val response = app.get(actualUrl, headers = headers)
        val responseBody = response.body.string()

        val props = parseJson<InertiaResponse>(responseBody).props
        val title = props.title!!
        val genres = title.genres.map { it.name.capitalize() }
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }
        val poster = getPoster(title)

        if (title.type == "tv") {
            val episodes: List<Episode> = getEpisodes(props)

            val tvShow = newTvSeriesLoadResponse(
                title.name,
                actualUrl,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://$cdnHost/images/$it" }

                this.tags = genres
                this.episodes = episodes
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }

            }
            return tvShow
        } else {
            val data = LoadData(
                "$mainUrl/iframe/${title.id}&canPlayFHD=1",
                "movie",
                title.tmdbId
            )
            val movie = newMovieLoadResponse(
                title.name,
                actualUrl,
                TvType.Movie,
                dataUrl = data.toJson()
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://$cdnHost/images/$it" }

                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)

                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }

                title.runtime?.let { this.duration = it }
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }
            }
            return movie
        }
    }

    private fun getActualUrl(url: String) =
        if (!url.contains(mainUrl)) {
            val replacingValue =
                if (url.contains("/it/") || url.contains("/en/")) mainUrl.toHttpUrl().host else mainUrl.toHttpUrl().host + "/$lang"
            val actualUrl = url.replace(url.toHttpUrl().host, replacingValue)

//            Log.d("$TAG:UrlFix", "Old: $url\nNew: $actualUrl")
            actualUrl
        } else {
            url
        }

    private suspend fun getEpisodes(props: Props): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val title = props.title

        title?.seasons?.forEach { season ->
            val responseEpisodes = emptyList<it.dogior.hadEnough.Episode>().toMutableList()
            if (season.id == props.loadedSeason!!.id) {
                responseEpisodes.addAll(props.loadedSeason.episodes!!)
            } else {
                if (inertiaVersion == "") {
                    setupHeaders()
                }
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                val obj =
                    parseJson<InertiaResponse>(app.get(url, headers = headers).body.string())
                responseEpisodes.addAll(obj.props.loadedSeason?.episodes!!)
            }
            responseEpisodes.forEach { ep ->

                val loadData = LoadData(
                    "$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = season.number,
                    episodeNumber = ep.number)
                episodeList.add(
                    newEpisode(loadData.toJson()) {
                        this.name = ep.name
                        this.posterUrl = props.cdnUrl + "/images/" + ep.getCover()
                        this.description = ep.plot
                        this.episode = ep.number
                        this.season = season.number
                        this.runTime = ep.duration
                    }
                )
            }
        }

        return episodeList
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
//        Log.d(TAG, "Load Data : $data")
        if (data.isEmpty()) return false
        val loadData = parseJson<LoadData>(data)

        val response = app.get(loadData.url).document
        val iframeSrc = response.select("iframe").attr("src")

        VixCloudExtractor().getUrl(
            url = iframeSrc,
            referer = siteRootUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        val vixsrcUrl = if(loadData.type == "movie"){
            "https://vixsrc.to/movie/${loadData.tmdbId}"
        } else{
            "https://vixsrc.to/tv/${loadData.tmdbId}/${loadData.seasonNumber}/${loadData.episodeNumber}"
        }

        VixSrcExtractor().getUrl(
            url = vixsrcUrl,
            referer = "https://vixsrc.to/",
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }
}
