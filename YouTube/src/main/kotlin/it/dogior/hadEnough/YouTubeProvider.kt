package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class YouTubeProvider(language: String, private val sharedPrefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "YouTube"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    override var lang = language

    val service: YoutubeService = ServiceList.YouTube


    companion object {
        const val MAIN_URL = "https://www.youtube.com"
        var SEARCH_PAGE: Page? = null
        lateinit var SEARCH_HANDLER: SearchQueryHandler
        const val SEARCH_CONTENT_FILTER = "videos"
    }

    fun getTrendingVideoUrls(page: Int): HomePageList? {
        val kiosks = service.kioskList
        val trendingsUrl = kiosks.defaultKioskExtractor.url
        val infoItem = KioskInfo.getInfo(service, trendingsUrl)

        val videos = if (page == 1) {
            infoItem.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = infoItem.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = infoItem.nextPage
            while (count < page && hasNext) {
                val more = KioskInfo.getMoreItems(service, trendingsUrl, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.filter { !it.isShortFormContent }.map {
            newMovieSearchResponse(it.name, it.url, TvType.Others) {
                this.posterUrl = it.thumbnails.last().url
            }
        }
        return HomePageList(
            name = "Trending",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun playlistToSearchResponseList(url: String, page: Int): HomePageList? {
        val playlistInfo = PlaylistInfo.getInfo(url)
        val videos = if (page == 1) {
            playlistInfo.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = playlistInfo.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = playlistInfo.nextPage
            while (count < page && hasNext) {
                val more = PlaylistInfo.getMoreItems(service, url, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map {
            newMovieSearchResponse(it.name, it.url, TvType.Others) {
                this.posterUrl = it.thumbnails.last().url
            }
        }
        return HomePageList(
            name = "${playlistInfo.uploaderName}: ${playlistInfo.name}",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun channelToSearchResponseList(url: String, page: Int): HomePageList? {
        val channelInfo = ChannelInfo.getInfo(url)
        val tabsLinkHandlers = channelInfo.tabs
        val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(service, it) }
        val videoTab = tabs.first { it.name == "videos" }

        val videos = if (page == 1) {
            videoTab.relatedItems.toMutableList()
        } else {
            mutableListOf<InfoItem>()
        }

        if (page > 1) {
            var hasNext = videoTab.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = videoTab.nextPage
            while (count < page && hasNext) {

                val videoTabHandler = tabsLinkHandlers.first { it.url.endsWith("/videos") }
                val more = ChannelTabInfo.getMoreItems(service, videoTabHandler, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map {
            newMovieSearchResponse(it.name, it.url, TvType.Others) {
                this.posterUrl = it.thumbnails.last().url
            }
        }
        return HomePageList(
            name = channelInfo.name,
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isTrendingEnabled = sharedPrefs?.getBoolean("trending", true) ?: true
        val sections = mutableListOf<HomePageList>()
        if (isTrendingEnabled) {
            val videos = getTrendingVideoUrls(page)
            videos?.let { sections.add(it) }
        }
        val playlistsData = sharedPrefs?.getStringSet("playlists", emptySet()) ?: emptySet()
        if (playlistsData.isNotEmpty()) {
            val triples = playlistsData.map { parseJson<Triple<String, String, Long>>(it) }
            val list = triples.amap { data ->
                val playlistUrl = data.first
                val urlPath = playlistUrl.substringAfter("youtu").substringAfter("/")
                val isPlaylist = urlPath.startsWith("playlist?list=")
                val isChannel = urlPath.startsWith("@") || urlPath.startsWith("channel")
                val customSections = if (isPlaylist && !isChannel) {
                    playlistToSearchResponseList(playlistUrl, page)
                } else if (!isPlaylist && isChannel) {
                    channelToSearchResponseList(playlistUrl, page)
                } else {
                    null
                }
                customSections to data.third
            }
            list.sortedBy { it.second }.forEach {
                val homepageSection = it.first
                if (homepageSection != null) {
                    sections.add(homepageSection)
                }
            }

        }
        if (sections.isEmpty()) {
            sections.add(
                HomePageList(
                    "All sections are disabled. Go to the settings to enable them",
                    emptyList()
                )
            )
        }
        return newHomePageResponse(
            sections, true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val handlerFactory = service.searchQHFactory
        val pageResults = mutableListOf<InfoItem>()
        var hasNextPage = true
        if(page<2){
            SEARCH_HANDLER = handlerFactory.fromQuery(
                query,
                listOf(SEARCH_CONTENT_FILTER),
                null
            )
            val searchInfo = SearchInfo.getInfo(service, SearchQueryHandler(SEARCH_HANDLER))
            pageResults.addAll(searchInfo.relatedItems.toMutableList())
            SEARCH_PAGE = searchInfo.nextPage
            hasNextPage = searchInfo.hasNextPage()
        }
        if (page > 1) {
            val more = SearchInfo.getMoreItems(service, SEARCH_HANDLER, SEARCH_PAGE)
            pageResults.addAll(more.items)
            hasNextPage = more.hasNextPage()
            SEARCH_PAGE = more.nextPage
        }

        val finalResults = pageResults.mapNotNull {
            when (it.infoType) {
                InfoType.PLAYLIST, InfoType.CHANNEL -> {
                    newTvSeriesSearchResponse(it.name, it.url, TvType.Others) {
                        this.posterUrl = it.thumbnails.last().url
                    }
                }

                InfoType.STREAM -> {
                    newMovieSearchResponse(it.name, it.url, TvType.Others) {
                        this.posterUrl = it.thumbnails.last().url
                    }
                }

                else -> {
//                    Log.d("YouTubeVideo", "Other type: ${it.name} \t|\t type: ${it.infoType}")
                    null
                }
            }
        }
        return newSearchResponseList(finalResults, hasNextPage)
    }

    override suspend fun load(url: String): LoadResponse {
        val extractor = service.getStreamExtractor(url)
        extractor.fetchPage()
        val videoInfo = StreamInfo.getInfo(extractor)
        Log.d("BANANA", "${videoInfo.name} \t ${videoInfo.url}")
        val views = "Views: ${videoInfo.viewCount}"
        val likes = "Likes: ${videoInfo.likeCount}"
        val length = videoInfo.duration / 60
        return newMovieLoadResponse(videoInfo.name, url, TvType.Others, url) {
            this.posterUrl = videoInfo.thumbnails.last().url
            this.plot = videoInfo.description.content
            this.duration = length.toInt()
            this.tags = listOf(videoInfo.uploaderName, views, likes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        YouTubeExtractor().getUrl(data, "", subtitleCallback, callback)
        return true
    }
}