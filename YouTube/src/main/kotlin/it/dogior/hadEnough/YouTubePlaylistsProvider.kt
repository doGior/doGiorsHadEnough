package it.dogior.hadEnough

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class YouTubePlaylistsProvider(language: String) : YouTubeProvider(language, null) {
    override var name = "YouTube Playlists"
    override val hasMainPage = false
    override val SEARCH_CONTENT_FILTER = "playlists"


    private fun getPlaylistVideos(videos: List<StreamInfoItem>): List<Episode> {
        val episodes = videos.map { video ->
            newEpisode(video.url) {
                this.name = video.name
                this.posterUrl = video.thumbnails.last().url
                this.runTime = (video.duration / 60).toInt()
            }
        }
        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val playlistInfo = PlaylistInfo.getInfo(url)
        val banner =
            if (playlistInfo.banners.isNotEmpty()) playlistInfo.banners.last().url else playlistInfo.thumbnails.last().url
        val eps = playlistInfo.relatedItems.toMutableList()
        var hasNext = playlistInfo.hasNextPage()
        var count = 1
        var nextPage = playlistInfo.nextPage
        while (hasNext) {
            val more = PlaylistInfo.getMoreItems(service, url, nextPage)
            eps.addAll(more.items)
            hasNext = more.hasNextPage()
            nextPage = more.nextPage
            count++
            if (count >= 10) break
//            Log.d("YouTubeParser", "Page ${count + 1}: ${more.items.size}")
        }
        return newTvSeriesLoadResponse(
            playlistInfo.name,
            url,
            TvType.Others,
            getPlaylistVideos(eps)
        ) {
            this.posterUrl = playlistInfo.thumbnails.last().url
            this.backgroundPosterUrl = banner
            this.plot = playlistInfo.description.content
            this.actors = listOf(
                ActorData(
                    Actor(playlistInfo.uploaderName, playlistInfo.uploaderAvatars.lastOrNull()?.url)
                )
            )
        }
    }

}