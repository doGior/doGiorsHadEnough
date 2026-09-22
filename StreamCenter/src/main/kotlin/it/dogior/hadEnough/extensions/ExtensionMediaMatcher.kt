package it.dogior.hadEnough.extensions

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import java.text.Normalizer
import java.util.Locale

internal object ExtensionMediaMatcher {
    private val languageSuffix = Regex("(?i)\\s*(?:[\\[(](?:dub|sub|ita|eng|sub ita|sub eng|italiano|english)[\\])]|(?:[-–]\\s*)?(?:sub ita|sub eng|dub))\\s*$")
    private val animeIds = setOf("anilist", "mal", "kitsu")

    fun normalizedTitle(title: String): String = Normalizer.normalize(
        title.replace(languageSuffix, ""), Normalizer.Form.NFKD,
    ).lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun titleMatches(context: ExtensionPlaybackContext, titles: List<String>): Boolean {
        val expected = context.titles.map(::normalizedTitle).filter(String::isNotBlank).toSet()
        return titles.any { normalizedTitle(it) in expected }
    }

    fun matches(context: ExtensionPlaybackContext, response: LoadResponse, resolvedById: String? = null): Boolean {
        val movie = response is MovieLoadResponse || response.type == TvType.AnimeMovie
        if (movie != context.isMovie) return false
        if (response !is MovieLoadResponse && response !is TvSeriesLoadResponse && response !is AnimeLoadResponse) return false
        val ids = response.extensionIds()
        val scopedAnime = context.isAnime && response is AnimeLoadResponse
        val comparable = context.ids.keys.intersect(ids.keys)
            .filter { !scopedAnime || it in animeIds }
        if (comparable.any { context.ids[it] != ids[it] }) return false
        if (comparable.isNotEmpty() || (resolvedById != null && (!scopedAnime || resolvedById in animeIds))) return true
        if (context.year != null && response.year != null && context.year != response.year) return false
        val anime = response as? AnimeLoadResponse
        return titleMatches(context, listOfNotNull(response.name, anime?.engName, anime?.japName) + anime?.synonyms.orEmpty())
    }

    fun playbackData(context: ExtensionPlaybackContext, response: LoadResponse): List<String> {
        return when (response) {
            is MovieLoadResponse -> if (context.isMovie) listOf(response.dataUrl) else emptyList()
            is TvSeriesLoadResponse -> {
                if (context.isAnime && (context.mappedSeason == null || context.mappedEpisode == null)) emptyList()
                else selectEpisode(
                    response.episodes,
                    context.mappedSeason ?: context.season,
                    context.mappedEpisode ?: context.episode,
                )?.let { listOf(it.data) }.orEmpty()
            }
            is AnimeLoadResponse -> {
                val variants = context.dubStatus?.let { listOf(response.episodes[it].orEmpty()) }
                    ?: response.episodes.values.toList()
                variants.mapNotNull { episodes ->
                    if (context.isMovie) {
                        episodes.distinctBy { it.data }.singleOrNull()
                    } else if (context.isAnime && episodes.mapNotNull { it.season }.all { it == 1 }) {
                        selectEpisode(episodes, 1, context.episode)
                    } else {
                        selectEpisode(episodes, context.mappedSeason ?: context.season, context.mappedEpisode ?: context.episode)
                    }
                }.map { it.data }
            }
            else -> emptyList()
        }.filter(String::isNotBlank).distinct()
    }

    internal fun selectEpisode(episodes: List<Episode>, season: Int?, number: Int?): Episode? {
        if (number == null || number < 0) return null
        if (season != null) {
            val exact = episodes.filter { it.season == season && it.episode == number }
            if (exact.isNotEmpty()) return exact.distinctBy { it.data }.singleOrNull()
        }
        if ((season == null || season == 1) && episodes.all { it.season == null }) {
            return episodes.filter { it.episode == number }.distinctBy { it.data }.singleOrNull()
        }
        return null
    }
}
