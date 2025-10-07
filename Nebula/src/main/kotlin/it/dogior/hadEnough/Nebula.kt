package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject


class Nebula : MainAPI() {
    override var mainUrl = "https://nebula.tv"
    override var name = "Nebula"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true
    var apiUrl = "https://content.api.nebula.app"

    override val mainPage = mainPageOf(
        "$apiUrl/video_episodes/?category=news" to "News",
        "$apiUrl/video_episodes/?category=animation" to "Animation",
        "$apiUrl/video_episodes/?category=culture" to "Culture",
        "$apiUrl/video_episodes/?category=engineering" to "Engineering",
        "$apiUrl/video_episodes/?category=history" to "History",
        "$apiUrl/video_episodes/?category=science" to "Science",
        "$apiUrl/video_channels/?ordering=-published_at" to "New Channels",
        "$apiUrl/video_channels/?ordering=-episode_published" to "Channels with recent uploads",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isVideoRequest = request.data.contains("video_episodes")
        val resp = app.get(request.data + "&offset=${(page - 1) * 20}")
        val obj = if (isVideoRequest) {
            parseJson<Response.VideoResponses>(resp.body.string())
        } else {
            parseJson<Response.ChannelResponses>(resp.body.string())
        }
        val searchResponses = obj.results.mapNotNull {
            when (it) {
                is ItemInfo.Video -> {
                    if ("free_sample_eligible" !in it.attributes) return@mapNotNull null
                    newMovieSearchResponse(it.title, it.url, TvType.Movie) {
                        this.posterUrl = it.images.thumbnail?.src
                    }
                }

                is ItemInfo.Channel -> {
                    newTvSeriesSearchResponse(it.title, it.url, TvType.TvSeries) {
                        this.posterUrl = it.images.avatar?.src
                    }
                }
            }
        }
        val hasNext = obj.next != null
        return newHomePageResponse(
            HomePageList(
                request.name,
                searchResponses,
                isVideoRequest
            ), hasNext
        )
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrls = listOf(
            "$apiUrl/video_channels/search/?q=$query",
            "$apiUrl/video_episodes/search/?q=$query"
        )
        val eps = searchUrls.map { searchUrl ->
            val resp = app.get(searchUrl)
            val obj = if (searchUrl.contains("episodes")) {
                parseJson<Response.VideoResponses>(resp.body.string())
            } else {
                parseJson<Response.ChannelResponses>(resp.body.string())
            }
            obj.results.mapNotNull {
                when (it) {
                    is ItemInfo.Video -> {
                        if ("free_sample_eligible" !in it.attributes) return@mapNotNull null
                        newMovieSearchResponse(it.title, it.url, TvType.Movie) {
                            this.posterUrl = it.images.thumbnail?.src
                        }
                    }

                    is ItemInfo.Channel -> {
                        newTvSeriesSearchResponse(it.title, it.url, TvType.TvSeries) {
                            this.posterUrl = it.images.avatar?.src
                        }
                    }
                }
            }
        }.flatten()
        return eps
    }

    override suspend fun load(url: String): LoadResponse {
        val isVideo = url.contains("nebula.tv/videos/")
        val apiUrl = if (isVideo) this.apiUrl + "/content" + url.substringAfter("https://nebula.tv")
        else this.apiUrl + "/video_channels" + url.substringAfter("https://nebula.tv")
        val resp = app.get(apiUrl)
        val obj = if (isVideo) parseJson<ItemInfo.Video>(resp.body.string())
        else parseJson<ItemInfo.Channel>(resp.body.string())
        return obj.toLoadResponse()
    }

    private suspend fun getEpisodes(channelId: String): List<Episode> {
        val channelUrl =
            "https://content.api.nebula.app/video_channels/${channelId}/video_episodes/?ordering=-published_at&page_size=100"

        val resp = app.get(channelUrl)
        val obj = parseJson<Response.VideoResponses>(resp.body.string())
        val videos = obj.results.mapNotNull {
            if ("free_sample_eligible" !in it.attributes) return@mapNotNull null
            newEpisode("https://content.api.nebula.app/video_episodes/${it.id}/manifest.m3u8?app_version=25.10.1&platform=web&compatibility=true&all_manifest=false") {
                this.posterUrl = it.images.thumbnail?.src
                this.name = it.title
                this.description = it.shortDescription
                this.runTime = it.duration / 60
                this.addDate(it.publishedAt)
            }
        }
        return videos
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = app.post("https://users.api.nebula.app/api/v1/authorization/")
        val token = JSONObject(resp.body.string()).getString("token")
        val url = "$data&token=$token"
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = url,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }

    suspend fun ItemInfo.toLoadResponse(): LoadResponse {
        when (this) {
            is ItemInfo.Video -> {
                val obj = this
                val dataUrl =
                    "https://content.api.nebula.app/video_episodes/${obj.id}/manifest.m3u8?app_version=25.10.1&platform=web&compatibility=true&all_manifest=false"

                return newMovieLoadResponse(obj.title, url, TvType.Movie, dataUrl) {
                    this.posterUrl = obj.images.thumbnail?.src
                    this.plot = obj.shortDescription
                    this.duration = obj.duration / 60
                    this.year = obj.publishedAt.substringBefore('-').toIntOrNull()
                    this.tags = listOf(obj.channel) + obj.categories.map { it.capitalize() }
                    this.comingSoon = "free_sample_eligible" !in obj.attributes
                }
            }

            is ItemInfo.Channel -> {
                val obj = this
                return newTvSeriesLoadResponse(
                    obj.title,
                    url,
                    TvType.TvSeries,
                    getEpisodes(obj.id)
                ) {
                    this.posterUrl = obj.images.avatar?.src
                    this.backgroundPosterUrl = obj.images.channelBanner?.src
                    this.plot = obj.description
                }
            }
        }

    }
}

sealed interface ItemInfo {
    data class Video(
        @JsonProperty("title") val title: String,
        @JsonProperty("app_path") val appPath: String,
        @JsonProperty("attributes") val attributes: List<String>,
        @JsonProperty("category_slugs") val categories: List<String>,
        @JsonProperty("channel_title") val channel: String,
        @JsonProperty("description") val description: String,
        @JsonProperty("duration") val duration: Int,
        @JsonProperty("id") val id: String,
        @JsonProperty("images") val images: Images,
        @JsonProperty("published_at") val publishedAt: String,
        @JsonProperty("share_url") val url: String,
        @JsonProperty("short_description") val shortDescription: String,
    ) : ItemInfo

    data class Channel(
        @JsonProperty("title") val title: String,
        @JsonProperty("app_path") val appPath: String,
        @JsonProperty("categories") val categories: List<Category>,
        @JsonProperty("description") val description: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("images") val images: Images,
        @JsonProperty("share_url") val url: String,
    ) : ItemInfo
}

data class Images(
    @JsonProperty("thumbnail") val thumbnail: Image?,
    @JsonProperty("banner") val channelBanner: Image?,
    @JsonProperty("avatar") val avatar: Image?
)

data class Image(
    @JsonProperty("src") val src: String
)

data class Category(
    @JsonProperty("title") val name: String
)

sealed class Response(
    open val next: String?,
    open val previous: String?,
    open val results: List<ItemInfo>
) {
    data class VideoResponses(
        override val next: String? = null,
        override val previous: String? = null,
        override val results: List<ItemInfo.Video>
    ) : Response(next, previous, results)

    data class ChannelResponses(
        override val next: String?,
        override val previous: String?,
        override val results: List<ItemInfo.Channel>
    ) : Response(next, previous, results)
}
