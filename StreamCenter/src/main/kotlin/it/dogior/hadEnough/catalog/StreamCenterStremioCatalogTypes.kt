package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.TvType
import java.util.Locale

internal fun stremioMediaTvType(type: String): TvType? = when (type.lowercase(Locale.ROOT)) {
    "movie" -> TvType.Movie
    "series" -> TvType.TvSeries
    "anime" -> TvType.Anime
    "tv", "channel" -> TvType.Live
    else -> null
}

internal fun stremioSectionTvType(type: String): TvType? =
    type.takeIf(String::isNotBlank)?.let { stremioMediaTvType(it) ?: TvType.Others }

internal fun stremioCatalogAcceptsItem(catalogType: String, itemType: String): Boolean {
    val actualType = stremioMediaTvType(itemType) ?: return false
    val expectedType = stremioMediaTvType(catalogType)
    return catalogType.isNotBlank() && (expectedType == null || actualType == expectedType)
}

internal fun stremioCatalogSupportedTypes(types: List<String>): Set<TvType> =
    types.flatMapTo(linkedSetOf()) { type ->
        stremioMediaTvType(type)?.let(::listOf)
            ?: if (type.isNotBlank()) listOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)
            else emptyList()
    }
