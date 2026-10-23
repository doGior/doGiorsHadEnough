package it.dogior.hadEnough

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.iptv.StreamCenterIptv
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun StreamCenter.iptvSearchResponse(channel: StreamCenterIptv.Channel): SearchResponse {
    val encodedId = URLEncoder.encode(channel.id, StandardCharsets.UTF_8.name())
    return newLiveSearchResponse(channel.name, "${StreamCenterIptv.ROUTE_PREFIX}$encodedId") {
        posterUrl = channel.logo
        StreamCenterIptv.languageForRegion(channel.regionKey)?.let { lang = it }
    }
}

internal suspend fun StreamCenter.fetchIptvChannelsByIds(ordered: List<String>, limit: Int): List<SearchResponse> {
    if (ordered.isEmpty()) return emptyList()
    val regionKeys = ordered.map { it.substringBefore(':') }.distinct()
    val channelsById = regionKeys.flatMap { regionKey ->
        runCatching { StreamCenterIptv.fetchChannels(regionKey) }.getOrDefault(emptyList())
    }.associateBy { it.id }
    return ordered.mapNotNull { channelsById[it] }.take(limit).map(this@fetchIptvChannelsByIds::iptvSearchResponse)
}

internal suspend fun StreamCenter.searchIptv(query: String): List<SearchResponse> {
    val terms = query.trim().lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
    if (terms.isEmpty()) return emptyList()
    val favoriteRegions = StreamCenterPlugin.getAllIptvSelectedChannelIds(sharedPref)
        .map { it.substringBefore(':') }
    val regionKeys = (listOf(StreamCenterPlugin.getIptvRegion(sharedPref)) + favoriteRegions)
        .distinct()
        .filter { key -> StreamCenterIptv.allRegions().any { it.key == key } }
    return coroutineScope {
        regionKeys.map { regionKey ->
            async(Dispatchers.IO) {
                runCatching { StreamCenterIptv.fetchChannels(regionKey) }.getOrDefault(emptyList())
            }
        }.awaitAll()
            .flatten()
            .filter { channel ->
                val text = "${channel.name} ${channel.group}".lowercase(Locale.ROOT)
                terms.all(text::contains)
            }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map(this@searchIptv::iptvSearchResponse)
    }
}

internal suspend fun StreamCenter.loadIptvChannel(url: String): LoadResponse {
    val id = URLDecoder.decode(url.substringAfter(StreamCenterIptv.ROUTE_PREFIX), StandardCharsets.UTF_8.name())
    val regionKey = id.substringBefore(':')
    val channel = StreamCenterIptv.fetchChannels(regionKey).firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Canale TV non più disponibile nella playlist")
    val sectionKey = StreamCenterPlugin.getIptvCustomSectionKeys(sharedPref)
        .firstOrNull { id in StreamCenterPlugin.getIptvSectionChannelIds(sharedPref, it) }
    val suggestions = sectionKey
        ?.let { StreamCenterPlugin.getIptvSectionChannelOrder(sharedPref, it) }
        .orEmpty()
        .filterNot { it == id }
        .let { fetchIptvChannelsByIds(it, it.size) }
    return newLiveStreamLoadResponse(channel.name, url, channel.playbackData()) {
        posterUrl = channel.logo
        plot = "Diretta TV · ${channel.regionName} · ${channel.group}"
        tags = listOf("TV", channel.regionName, channel.group)
        recommendations = suggestions
    }.withCardProvenance(
        defaultSource = "Playlist IPTV (${channel.regionName})",
        fieldSources = mapOf(
            "tipo_contenuto" to listOf("StreamCenter (routing IPTV)"),
            "trama" to listOf("Playlist IPTV", "StreamCenter (testo composto)"),
            "tag" to listOf("Playlist IPTV", "StreamCenter (classificazione)"),
            "raccomandazioni" to listOf(
                "Configurazione sezione IPTV StreamCenter",
                "Playlist IPTV",
            ),
        ),
    )
}

internal data class ResolvedIptvStream(
    val url: String,
    val type: ExtractorLinkType,
)

internal suspend fun StreamCenter.resolveIptvStream(
    originalUrl: String,
    requestHeaders: Map<String, String>,
): ResolvedIptvStream {
    fun typeFrom(url: String, contentType: String = "", bodyStart: String = ""): ExtractorLinkType? {
        val normalizedUrl = url.substringBefore('?').lowercase(Locale.ROOT)
        val normalizedType = contentType.lowercase(Locale.ROOT)
        return when {
            normalizedUrl.endsWith(".m3u8") ||
                "mpegurl" in normalizedType ||
                bodyStart.trimStart().startsWith("#EXTM3U") -> ExtractorLinkType.M3U8
            normalizedUrl.endsWith(".mpd") ||
                "dash+xml" in normalizedType ||
                bodyStart.trimStart().startsWith("<MPD", ignoreCase = true) -> ExtractorLinkType.DASH
            normalizedUrl.endsWith(".mp4") || normalizedUrl.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    typeFrom(originalUrl)?.let { return ResolvedIptvStream(originalUrl, it) }
    val response = runCatching {
        app.get(
            originalUrl,
            headers = requestHeaders,
            allowRedirects = true,
            timeout = 15L,
        )
    }.getOrNull()
    if (response != null) {
        val finalUrl = response.url
        val contentType = response.headers["Content-Type"].orEmpty()
        val detected = typeFrom(finalUrl, contentType, response.text.take(64))
        if (detected != null) return ResolvedIptvStream(finalUrl, detected)
    }
    return ResolvedIptvStream(response?.url ?: originalUrl, ExtractorLinkType.M3U8)
}
