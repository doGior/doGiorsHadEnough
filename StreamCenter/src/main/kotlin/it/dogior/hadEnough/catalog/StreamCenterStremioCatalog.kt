package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import it.dogior.hadEnough.stremio.StreamCenterStremioAddon
import it.dogior.hadEnough.stremio.StreamCenterStremioAddonClient
import it.dogior.hadEnough.stremio.StreamCenterStremioCatalogDescriptor
import it.dogior.hadEnough.stremio.StreamCenterStremioCatalogItem
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class StreamCenterStremioCatalog(
    private val addon: StreamCenterStremioAddon,
    private val catalogKey: String,
) : StreamCenterCatalog {
    private val cards = ConcurrentHashMap<String, StreamCenterStremioCatalogItem>()

    override suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        val descriptor = section.stremioCatalog ?: return StreamCenterCatalogPage(emptyList(), false)
        val expectedType = stremioTvType(descriptor.type)
            ?: return StreamCenterCatalogPage(emptyList(), false)
        val result = StreamCenterStremioAddonClient.loadCatalog(addon, descriptor, page)
        return StreamCenterCatalogPage(
            items = result.items.mapNotNull { item ->
                item.takeIf { stremioTvType(it.type) == expectedType }
                    ?.let { validItem -> searchResponse(api, validItem, showScore) }
            },
            hasNext = result.hasNext,
        )
    }

    override suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage {
        if (query.isBlank()) return StreamCenterCatalogPage(emptyList(), false)
        val descriptors = addon.catalogs.filter { descriptor ->
            stremioTvType(descriptor.type) != null && descriptor.supportsExtra("search")
        }
        if (descriptors.isEmpty()) return StreamCenterCatalogPage(emptyList(), false)
        val items = linkedMapOf<String, SearchResponse>()
        var hasNext = false
        descriptors.forEach { descriptor ->
            val expectedType = stremioTvType(descriptor.type) ?: return@forEach
            val result = StreamCenterStremioAddonClient.loadCatalog(
                addon = addon,
                catalog = descriptor,
                page = page,
                query = query,
            )
            hasNext = hasNext || result.hasNext
            result.items.forEach { item ->
                if (stremioTvType(item.type) != expectedType) return@forEach
                searchResponse(api, item, showScore)?.let { response ->
                    items.putIfAbsent(response.url, response)
                }
            }
        }
        return StreamCenterCatalogPage(items.values.toList(), hasNext)
    }

    suspend fun media(url: String): StreamCenterStremioCatalogItem? {
        val route = parseRoute(url) ?: return null
        val cached = cards[url]
        val metadata = StreamCenterStremioAddonClient.loadCatalogMeta(
            addon = addon,
            type = route.type,
            id = route.id,
        )
        return metadata?.withFallback(cached) ?: cached
    }

    private fun searchResponse(
        api: MainAPI,
        item: StreamCenterStremioCatalogItem,
        showScore: Boolean,
    ): SearchResponse? {
        val type = stremioTvType(item.type) ?: return null
        val url = mediaUrl(item.type, item.id)
        cards[url] = item
        val score = item.score?.takeIf { showScore }?.let { Score.from(it, 10) }
        return when (type) {
            TvType.Movie -> api.newMovieSearchResponse(item.name, url, type) {
                posterUrl = item.posterUrl
                year = item.year
                this.score = score
            }
            TvType.TvSeries -> api.newTvSeriesSearchResponse(item.name, url, type) {
                posterUrl = item.posterUrl
                year = item.year
                this.score = score
            }
            TvType.Live -> api.newLiveSearchResponse(item.name, url) {
                posterUrl = item.posterUrl
            }
            else -> api.newAnimeSearchResponse(item.name, url, type) {
                posterUrl = item.posterUrl
                year = item.year
                this.score = score
            }
        }
    }

    private fun mediaUrl(type: String, id: String): String = buildString {
        append(ROUTE_PREFIX)
        append('/')
        append(encode(catalogKey))
        append('/')
        append(encode(type))
        append('/')
        append(encode(id))
    }

    private fun parseRoute(url: String): StremioCatalogRoute? {
        val route = url.substringAfter("$ROUTE_PREFIX/", "")
        if (route.isBlank()) return null
        val parts = route.substringBefore('?').split('/')
        if (parts.size != 3) return null
        val decodedKey = decode(parts[0]) ?: return null
        if (decodedKey != catalogKey) return null
        val type = decode(parts[1])?.takeIf(String::isNotBlank) ?: return null
        val id = decode(parts[2])?.takeIf(String::isNotBlank) ?: return null
        return StremioCatalogRoute(type, id)
    }

    private fun StreamCenterStremioCatalogItem.withFallback(
        fallback: StreamCenterStremioCatalogItem?,
    ): StreamCenterStremioCatalogItem {
        fallback ?: return this
        return copy(
            posterUrl = posterUrl ?: fallback.posterUrl,
            backgroundUrl = backgroundUrl ?: fallback.backgroundUrl,
            description = description ?: fallback.description,
            year = year ?: fallback.year,
            score = score ?: fallback.score,
            genres = genres.ifEmpty { fallback.genres },
            imdbId = imdbId ?: fallback.imdbId,
            tmdbId = tmdbId ?: fallback.tmdbId,
            videos = videos.ifEmpty { fallback.videos },
        )
    }

    private fun StreamCenterStremioCatalogDescriptor.supportsExtra(name: String): Boolean =
        extra.any { value -> value.equals(name, ignoreCase = true) }

    private fun stremioTvType(type: String): TvType? = when (type.lowercase(Locale.ROOT)) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "tv", "channel" -> TvType.Live
        else -> null
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()

    private data class StremioCatalogRoute(
        val type: String,
        val id: String,
    )

    private companion object {
        const val ROUTE_PREFIX = "https://streamcenter.stremio/catalog"
    }
}
