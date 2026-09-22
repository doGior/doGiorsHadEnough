package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import it.dogior.hadEnough.iptv.StreamCenterIptv
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal class StreamCenterIptvCatalog(private val playlistKey: String) : StreamCenterCatalog {

    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (page > 1) return StreamCenterCatalogPage(emptyList(), false)
        val channels = runCatching { StreamCenterIptv.fetchChannels(playlistKey) }.getOrDefault(emptyList())
        val filtered = StreamCenterIptv.channelsForCategory(channels, section.path)
        return StreamCenterCatalogPage(filtered.map { api.toResponse(it) }, false)
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (page > 1) return StreamCenterCatalogPage(emptyList(), false)
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return StreamCenterCatalogPage(emptyList(), false)
        val channels = runCatching { StreamCenterIptv.fetchChannels(playlistKey) }.getOrDefault(emptyList())
        val matched = channels.filter { it.name.lowercase(Locale.ROOT).contains(normalized) }
        return StreamCenterCatalogPage(matched.map { api.toResponse(it) }, false)
    }

    private fun MainAPI.toResponse(channel: StreamCenterIptv.Channel): SearchResponse {
        val encodedId = URLEncoder.encode(channel.id, StandardCharsets.UTF_8.name())
        return newLiveSearchResponse(channel.name, "${StreamCenterIptv.ROUTE_PREFIX}$encodedId") {
            this.posterUrl = channel.logo
            StreamCenterIptv.languageForRegion(channel.regionKey)?.let { this.lang = it }
        }
    }
}
