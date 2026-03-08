package it.dogior.hadEnough

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo

class YouTubeChannelProvider(language: String) : YouTubeProvider(language, null) {
    override var name = "YouTube Channels"
    override val hasMainPage = false
    override val SEARCH_CONTENT_FILTER = "channels"

    override suspend fun load(url: String): LoadResponse {
        val channelInfo = ChannelInfo.getInfo(url)
        val avatars = try {
            channelInfo.avatars.last().url
        } catch (_: Exception){
            null
        }
        val banners = try {
            channelInfo.banners.last().url
        } catch (_: Exception){
            null
        }
        val tags = mutableListOf("Subscribers: ${formatThousands(channelInfo.subscriberCount)}")
        return newTvSeriesLoadResponse(channelInfo.name, url, TvType.Others, getChannelVideos(channelInfo)){
            this.posterUrl = avatars
            this.backgroundPosterUrl = banners
            this.plot = channelInfo.description
            this.tags = tags
        }
    }

    private fun getChannelVideos(channel: ChannelInfo): List<Episode> {
        val tabsLinkHandlers = channel.tabs
        val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(service, it) }
        val videoTab = tabs.first { it.name == "videos" }
        val videos = videoTab.relatedItems.mapNotNull {
            newEpisode(it.url){
                this.name = it.name
                this.posterUrl = it.thumbnails.last().url
            }
        }
        return videos.reversed()
    }

}