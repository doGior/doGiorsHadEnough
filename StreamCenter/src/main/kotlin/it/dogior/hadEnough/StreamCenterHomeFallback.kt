package it.dogior.hadEnough

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SearchResponse
import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.catalog.StreamCenterCatalogPage
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap

private val aniListHomeFallbacks = mapOf(
    "au:calendar" to "anilist_airing",
    "au:latest" to "anilist_airing",
    "au:popular" to "anilist_popular",
    "au:random" to "anilist_trending",
)

private val tmdbHomeFallbacks = mapOf(
    "sc:tv:trending" to "tv_popular",
    "sc:tv:latest" to "tv_on_the_air",
    "sc:tv:top10" to "tv_top_rated",
    "sc:movie:trending" to "movie_popular",
    "sc:movie:latest" to "movie_new_streaming",
    "sc:movie:top10" to "movie_top_rated",
)

internal fun homeSectionSourceKey(data: String): String? = when {
    data.startsWith("au:") -> StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY
    data.startsWith("sc:") -> StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY
    else -> null
}

internal suspend fun StreamCenter.fallbackHomePage(
    data: String,
    page: Int,
    limit: Int,
    showScore: Boolean,
): StreamCenterCatalogPage {
    val fallback = runCatchingCancellable {
        aniListHomeFallbacks[data]?.let { sectionKey ->
            val section = catalogSection("anilist", sectionKey) ?: return@let StreamCenterCatalogPage(emptyList(), false)
            val firstIndex = (page - 1).coerceAtLeast(0) * limit
            var catalogPageNumber = firstIndex / 20 + 1
            var skip = firstIndex % 20
            val collected = mutableListOf<SearchResponse>()
            var hasNext = false
            for (attempt in 0 until 3) {
                if (collected.size >= limit) break
                val catalogPage = aniListCatalog.section(this, section, catalogPageNumber, showScore)
                val remaining = limit - collected.size
                val available = catalogPage.items.drop(skip)
                collected += available.take(remaining)
                hasNext = available.size > remaining || catalogPage.hasNext
                if (!catalogPage.hasNext || catalogPage.items.isEmpty()) break
                catalogPageNumber++
                skip = 0
            }
            StreamCenterCatalogPage(collected.mapNotNull { card ->
                val anilistId = aniListCatalog.mediaId(card.url) ?: return@mapNotNull null
                (card as? AnimeSearchResponse)?.copy(
                    url = markAnilistUrl(anilistId, parseQueryParams(card.url)[animeMalParam]?.toIntOrNull()),
                )
            }, hasNext)
        } ?: tmdbHomeFallbacks[data]?.let { sectionKey ->
            val section = catalogSection("tmdb", sectionKey) ?: return@let StreamCenterCatalogPage(emptyList(), false)
            tmdbCatalog.section(this, section, page, showScore)
        } ?: StreamCenterCatalogPage(emptyList(), false)
    }.onFailure { error ->
        StreamCenterLogger.logMenuError(
            action = "Ripiego sezione Home non riuscito",
            throwable = error,
            metadata = mapOf("dati_sezione" to data, "pagina" to page),
        )
    }.getOrDefault(StreamCenterCatalogPage(emptyList(), false))
    val items = fallback.items.take(limit)
    if (items.isNotEmpty()) {
        StreamCenterLogger.logMenu(
            action = "Sezione Home riempita da catalogo di ripiego",
            metadata = mapOf(
                "dati_sezione" to data,
                "catalogo" to if (data.startsWith("au:")) "AniList" else "TMDB",
                "elementi" to items.size,
            ),
            level = StreamCenterLogger.Level.WARNING,
        )
    }
    return fallback.copy(items = items)
}

private val announcedFallbacks = ConcurrentHashMap<String, Long>()

internal fun StreamCenter.announceHomeFallback(sourceKey: String) {
    val stamp = lastHomepageRequest
    if (announcedFallbacks.put(sourceKey, stamp) == stamp) return
    val message = if (sourceKey == StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) {
        "AnimeUnity non disponibile: Anime della Home presi da AniList"
    } else {
        "StreamingCommunity non disponibile: Serie TV e Film della Home presi da TMDB"
    }
    Handler(Looper.getMainLooper()).post {
        runCatching { CommonActivity.showToast(message, Toast.LENGTH_LONG) }
    }
}

private fun catalogSection(catalogKey: String, sectionKey: String) =
    StreamCenterCatalogs.catalogs.firstOrNull { it.key == catalogKey }
        ?.sections?.firstOrNull { it.key == sectionKey }
