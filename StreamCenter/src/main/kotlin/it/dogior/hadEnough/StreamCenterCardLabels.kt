package it.dogior.hadEnough

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.DataStoreHelper.BookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.SubscribedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.FavoritesData

internal data class CardLabelVisibility(val score: Boolean, val dubStatus: Boolean, val episodes: Boolean)

internal fun StreamCenter.cardLabelVisibility(): CardLabelVisibility {
    val dubStatus = !performanceMode && StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref)
    return CardLabelVisibility(
        score = showCardScores,
        dubStatus = dubStatus,
        episodes = dubStatus && StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref),
    )
}

internal fun StreamCenter.applyCardLabelPreferences(items: List<SearchResponse>): List<SearchResponse> {
    if (items.isEmpty()) return items
    val (showScore, showDubStatus, showEpisodes) = cardLabelVisibility()
    if (showScore && showDubStatus && showEpisodes) return items
    return items.map { card -> card.withVisibleLabels(showScore, showDubStatus, showEpisodes) }
}

private fun SearchResponse.withVisibleLabels(
    showScore: Boolean,
    showDubStatus: Boolean,
    showEpisodes: Boolean,
): SearchResponse {
    val score = score.takeIf { showScore }
    return when (this) {
        is AnimeSearchResponse -> {
            val dubStatus = dubStatus.takeIf { showDubStatus }
            val episodes = episodes.takeIf { showEpisodes } ?: mutableMapOf()
            if (score == this.score && dubStatus == this.dubStatus && episodes == this.episodes) this
            else copy(score = score, dubStatus = dubStatus, episodes = episodes)
        }
        is TvSeriesSearchResponse -> if (score == this.score) this else copy(score = score)
        is MovieSearchResponse -> if (score == this.score) this else copy(score = score)
        is BookmarkedData -> if (score == this.score) this else copy(score = score)
        is SubscribedData -> if (score == this.score) this else copy(score = score)
        is FavoritesData -> if (score == this.score) this else copy(score = score)
        else -> this
    }
}
