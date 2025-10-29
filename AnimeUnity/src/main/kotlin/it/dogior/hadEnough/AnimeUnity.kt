package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
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
import java.util.Locale

typealias Str = BooleanOrString.AsString
//typealias Bool = BooleanOrString.AsBoolean

const val TAG = "AnimeUnity"

class AnimeUnity : MainAPI() {
    override var mainUrl = Companion.mainUrl
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        @Suppress("ConstPropertyName")
        const val mainUrl = "https://www.animeunity.so"
        var name = "AnimeUnity"
        var headers = mapOf(
            "Host" to mainUrl.toHttpUrl().host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        ).toMutableMap()
//        var cookies = emptyMap<String, String>()
    }

    private val sectionNamesList = mainPageOf(
        "$mainUrl/archivio/" to "In Corso",
        "$mainUrl/archivio/" to "Popolari",
        "$mainUrl/archivio/" to "I migliori",
        "$mainUrl/archivio/" to "In Arrivo",
//        "$mainUrl/archivio/" to "I migliori doppiati",
//        "$mainUrl/archivio/" to "In Corso doppiati",
//        "$mainUrl/archivio/" to "Popolari doppiati",
//        "$mainUrl/archivio/" to "I migliori doppiati",
    )
    override val mainPage = sectionNamesList


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
//        // Log.d("$TAG:setup", "Headers: $headers")

    }

    private fun resetHeadersAndCookies() {
        if (headers.isNotEmpty()) {
            headers.clear()
        }
        headers["Host"] = Companion.mainUrl.toHttpUrl().host
        headers["User-Agent"] =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
//        cookies = emptyMap()
    }

    private suspend fun searchResponseBuilder(objectList: List<Anime>): List<SearchResponse> {
        return objectList.amap { anime ->
            val title = (anime.titleIt ?: anime.titleEng ?: anime.title!!)

            val poster = getImage(anime.imageUrl, anime.anilistId)

            newAnimeSearchResponse(
                name = title.replace(" (ITA)", ""),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                addDubStatus(anime.dub == 1 || title.contains("(ITA)"))
                addPoster(poster)
            }

        }
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        // First try the direct image URL if available
        if (!imageUrl.isNullOrEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://img.animeunity.so/anime/$fileName"
            } catch (_: Exception) {
                // Fallback to Anilist if direct image fails
            }
        }

        // Fallback to Anilist

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

        val body = mapOf(
            "query" to query,
            "variables" to """{"id":$anilistId}"""
        )
        val response = app.post("https://graphql.anilist.co", data = body)
        val anilistObj = parseJson<AnilistResponse>(response.text)

        return anilistObj.data.media.coverImage?.let { coverImage ->
            coverImage.large ?: coverImage.medium!!
        } ?: throw IllegalStateException("No valid image found")

    }

    //Get the Homepage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
//        val localTag = "$TAG:MainPage"

        val url = request.data + "get-animes"
        if (!headers.contains("Cookie")) {
            resetHeadersAndCookies()
            setupHeadersAndCookies()
        }

        val requestData = getDataPerHomeSection(request.name)

        val offset = (page - 1) * 30
        requestData.offset = offset

        // Log.d(
//            localTag,
//            "Sezione: ${request.name} \tPage: $page \t Offset: $offset \t Request offset: ${requestData.offset}"
//        )
        val requestBody = requestData.toRequestBody()


        val response =
            app.post(url, headers = headers, requestBody = requestBody)

        val body = response.text
//        Log.d("$TAG:body", body)

//        // Log.d(localTag, "Cookies: ${response.cookies}")
        val responseObject = parseJson<ApiResponse>(body)
        val titles = responseObject.titles
//        // Log.d(localTag, "Titles: $titles")

        val hasNextPage = requestData.offset
            ?.let { it < 177 } ?: true && titles?.size == 30
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = titles?.let { searchResponseBuilder(it) } ?: emptyList(),
                isHorizontalImages = false
            ), hasNextPage
        )
    }

    private fun getDataPerHomeSection(section: String) = when (section) {
        "Popolari" -> {
            RequestData(orderBy = Str("Popolarità"), dubbed = 0)
        }

        "In Arrivo" -> {
            RequestData(status = Str("In Uscita"), dubbed = 0)
        }

        "I migliori" -> {
            RequestData(orderBy = Str("Valutazione"), dubbed = 0)
        }

        "In Corso" -> {
            RequestData(orderBy = Str("Popolarità"), status = Str("In Corso"), dubbed = 0)
        }

        else -> {
            RequestData()
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
//        val localTag = "$TAG:search"
        val url = "$mainUrl/archivio/get-animes"

        resetHeadersAndCookies()
        setupHeadersAndCookies()

        val requestBody = RequestData(title = query, dubbed = 0).toRequestBody()
        val response =
            app.post(url, headers = headers, requestBody = requestBody)

        val responseObject = parseJson<ApiResponse>(response.text)
        val titles = responseObject.titles
        // Log.d(localTag, "Titles: $titles")

        return searchResponseBuilder(titles ?: emptyList())
    }

    // This function gets called when you enter the page/show
    override suspend fun load(url: String): LoadResponse {
//        val localTag = "$TAG:load"
        resetHeadersAndCookies()
        setupHeadersAndCookies()
        val animePage = app.get(url).document

        val relatedAnimeJsonArray =
            animePage.select("layout-items").attr("items-json")//.replace("\\", "")
        val relatedAnime = parseJson<List<Anime>>(relatedAnimeJsonArray)


        val videoPlayer = animePage.select("video-player")
        val anime = parseJson<Anime>(videoPlayer.attr("anime"))


        val eps = parseJson<List<Episode>>(videoPlayer.attr("episodes"))
        val totalEps = videoPlayer.attr("episodes_count").toInt()
        // 120 is the max number of episodes per request to the info_api endpoint
        val isEpNumberMultipleOfRange = totalEps % 120 == 0
        val range = if (isEpNumberMultipleOfRange) {
            totalEps / 120
        } else {
            (totalEps / 120) + 1
        }
        val episodes = eps.map {
            newEpisode("$url/${it.id}") {
                this.episode = it.number.toIntOrNull()
            }
        }.toMutableList()

        if (totalEps > 120) {
            for (i in 2..range) {
                val endRange = if (i == range) {
                    totalEps
                } else {
                    i * 120
                }

                val infoUrl =
                    "$mainUrl/info_api/${anime.id}/1?start_range=${1 + (i - 1) * 120}&end_range=${endRange}"
                val info = app.get(infoUrl).text
                val animeInfo = parseJson<AnimeInfo>(info)
                episodes.addAll(animeInfo.episodes.map {
                    newEpisode("$url/${it.id}") {
                        this.episode = it.number.toIntOrNull()
                    }
                })
            }
        }
        val title = anime.titleIt ?: anime.titleEng ?: anime.title!!
        val relatedAnimes = relatedAnime.amap {
            val relatedTitle = (it.titleIt ?: it.titleEng ?: it.title!!)
            val poster = getImage(it.imageUrl, it.anilistId)
            newAnimeSearchResponse(
                name = relatedTitle.replace(" (ITA)", ""),
                url = "$mainUrl/anime/${it.id}-${it.slug}",
                type = if (it.type == "TV") TvType.Anime
                else if (it.type == "Movie" || it.episodesCount == 1) TvType.AnimeMovie
                else TvType.OVA
            ) {
                addDubStatus(it.dub == 1 || relatedTitle.contains("(ITA)"))
                addPoster(poster)
            }
        }

        val animeLoadResponse = newAnimeLoadResponse(
            name = title.replace(" (ITA)", ""),
            url = url,
            type = if (anime.type == "TV") TvType.Anime
            else if (anime.type == "Movie" || anime.episodesCount == 1) TvType.AnimeMovie
            else TvType.OVA,
        ) {
            this.posterUrl =
                getImage(anime.imageUrl, anime.anilistId)
            anime.cover?.let {
                this.backgroundPosterUrl = getBanner(it)
            }
            this.year = anime.date.toInt()
            addScore(anime.score)

            addDuration(anime.episodesLength.toString() + " minuti")
            val dub = if (anime.dub == 1) DubStatus.Dubbed else DubStatus.Subbed
            addEpisodes(dub, episodes)

            addAniListId(anime.anilistId)
            addMalId(anime.malId)
            this.plot = anime.plot
            val doppiato =
                if (anime.dub == 1 || title.contains("(ITA)")) "\uD83C\uDDEE\uD83C\uDDF9  Italiano" else "\uD83C\uDDEF\uD83C\uDDF5  Giapponese"
            this.tags = listOf(doppiato) + anime.genres.map { genre ->
                genre.name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(
                        Locale.getDefault()
                    ) else it.toString()
                }
            }
            this.comingSoon = anime.status == "In uscita prossimamente"
            this.recommendations = relatedAnimes
        }

        return animeLoadResponse
    }

    private fun getBanner(imageUrl: String): String {
//        Log.d("$TAG:getPoster", "imageUrl: $imageUrl")
        if (imageUrl.isNotEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                val cdnHost = mainUrl.toHttpUrl().host.replace("www", "img")
                return "https://$cdnHost/anime/$fileName"
            } catch (_: Exception) {
            }
        }
        return imageUrl
    }


    // This function is how you load the links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
//        val localTag = "$TAG:loadLinks"
//         Log.d(localTag, "Url : $data")

        val document = app.get(data).document

        val sourceUrl = document.select("video-player").attr("embed_url")
//         Log.d(localTag, "Document: $document")
//         Log.d(localTag, "Iframe: $sourceUrl")
        VixCloudExtractor().getUrl(
            url = sourceUrl,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return true
    }
}
