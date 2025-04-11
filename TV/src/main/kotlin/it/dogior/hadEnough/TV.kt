package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class TV(
    private val enabledPlaylists: List<String>,
    override var lang: String,
    private val sharedPref: SharedPreferences?
) : MainAPI() {
    override var mainUrl =
        "https://raw.githubusercontent.com/Free-TV/IPTV/refs/heads/master/playlists"
    override var name = "TV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private var playlists = mutableMapOf<String, Playlist?>()
    private val urlList = enabledPlaylists.map { "$mainUrl/$it" }


    private suspend fun getTVChannels(url: String): List<TVChannel> {
        if (playlists[url] == null) {
            playlists[url] = IptvPlaylistParser().parseM3U(app.get(url).text)
        }
        return playlists[url]!!.items
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (urlList.isEmpty()){
            return newHomePageResponse( HomePageList(
                "Enable channels in the plugin settings",
                emptyList(),
                isHorizontalImages = true
            ), false)
        }
        val sections = urlList.map {
            val data = getTVChannels(it)
            val sectionTitle = it.substringAfter("playlist_", "").replace(".m3u8", "").trim().capitalize()
            val show = data.map { showData ->
                sharedPref?.edit()?.apply {
                    putString(showData.url, showData.toJson())
                    apply()
                }
                showData.toSearchResponse(apiName = this@TV.name)
            }
            HomePageList(
                sectionTitle,
                show,
                isHorizontalImages = true
            )
        }
        return newHomePageResponse( sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponses = urlList.map { url ->
            val data = getTVChannels(url)
            data.filter {
                it.attributes["tvg-id"]?.contains(query) ?: false ||
                        it.title?.lowercase()?.contains(query.lowercase()) ?: false
            }.map { it.toSearchResponse(apiName = this@TV.name) }
        }.flatten()
        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val tvChannel = sharedPref?.getString(url, null)?.let { parseJson<TVChannel>(it) }
            ?: throw ErrorLoadingException("Error loading channel from cache")

        val streamUrl = tvChannel.url.toString()
        val channelName = tvChannel.title ?: tvChannel.attributes["tvg-id"].toString()
        val posterUrl = tvChannel.attributes["tvg-logo"].toString()

        return LiveStreamLoadResponse(
            channelName,
            streamUrl,
            this.name,
            url,
            posterUrl
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        callback.invoke(
            newExtractorLink(
                "Free-TV",
                "Free-TV",
                data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}

data class Playlist(
    val items: List<TVChannel> = emptyList(),
)

data class TVChannel(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
) {
    fun toSearchResponse(apiName: String): SearchResponse {
        val streamUrl = url.toString()
        val channelName = title ?: attributes["tvg-id"].toString()
        val posterUrl = attributes["tvg-logo"].toString()
        return LiveSearchResponse(
            channelName,
            streamUrl,
            apiName,
            TvType.Live,
            posterUrl,
        )
    }
}