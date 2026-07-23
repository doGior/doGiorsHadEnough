package it.dogior.hadEnough.model

import it.dogior.hadEnough.util.normalizeAnimeEpisodeNumber
import it.dogior.hadEnough.util.sortedEpisodeNumbers

internal data class AnimeUnityPlaybackData(
    val preferredUrl: String,
    val subUrl: String? = null,
    val dubUrl: String? = null,
)

internal data class AnimeWorldPlaybackData(
    val label: String,
    val pageUrl: String,
    val episodeToken: String,
)

internal data class AnimeSaturnPlaybackData(
    val label: String,
    val watchUrl: String,
)

internal data class AnimeUnityPlayerSource(
    val label: String,
    val url: String,
)

internal data class AnimeUnityAnime(
    val id: Int,
    val slug: String,
    val title: String?,
    val titleEng: String?,
    val titleIt: String?,
    val dub: Int,
    val anilistId: Int?,
    val malId: Int?,
    val episodesCount: Int?,
    val realEpisodesCount: Int?,
    val plot: String? = null,
    val imageUrl: String? = null,
    val score: String? = null,
    val type: String? = null,
    val year: Int? = null,
)

internal data class AnimeUnityEpisodeInfo(
    val id: Int,
    val number: String,
)

internal data class AnimeUnityPageData(
    val anime: AnimeUnityAnime,
    val episodes: List<AnimeUnityEpisodeInfo>,
)

internal data class AnimeWorldSearchItem(
    val url: String,
    val title: String,
    val otherTitle: String?,
    val isDub: Boolean,
)

internal data class AnimeWorldPageData(
    val searchItem: AnimeWorldSearchItem,
    val anilistId: Int?,
    val malId: Int?,
    val kitsuId: Int?,
    val episodeSources: Map<String, AnimeWorldPlaybackData>,
)

internal data class AnimeWorldEpisodeInfo(
    val number: String,
    val token: String,
)

internal data class AnimeWorldTitleSources(
    val syncIds: AnimeSyncIds,
    val subSources: Map<String, AnimeWorldPlaybackData>,
    val dubSources: Map<String, AnimeWorldPlaybackData>,
) {
    fun playbacksForEpisode(number: String?): List<AnimeWorldPlaybackData> {
        val normalizedNumber = normalizeAnimeEpisodeNumber(number) ?: return emptyList()
        val sources = mutableListOf<AnimeWorldPlaybackData>()
        subSources[normalizedNumber]?.let(sources::add)
        dubSources[normalizedNumber]?.let(sources::add)
        return sources.distinctBy { "${it.label}:${it.episodeToken}:${it.pageUrl}" }
    }

    fun firstPlaybacks(): List<AnimeWorldPlaybackData> {
        val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
        return playbacksForEpisode(firstNumber)
    }

    fun episodeNumbers(): List<String> = sortedEpisodeNumbers(subSources, dubSources)
}

internal data class AnimeSaturnSearchItem(
    val url: String,
    val title: String,
    val isDub: Boolean,
    val score: Int,
)

internal data class AnimeSaturnPageData(
    val searchItem: AnimeSaturnSearchItem,
    val anilistId: Int?,
    val malId: Int?,
    val kitsuId: Int?,
    val episodeSources: Map<String, AnimeSaturnPlaybackData>,
)

internal data class AnimeSaturnTitleSources(
    val syncIds: AnimeSyncIds,
    val subSources: Map<String, AnimeSaturnPlaybackData>,
    val dubSources: Map<String, AnimeSaturnPlaybackData>,
) {
    fun playbacksForEpisode(number: String?): List<AnimeSaturnPlaybackData> {
        val normalizedNumber = normalizeAnimeEpisodeNumber(number) ?: return emptyList()
        val sources = mutableListOf<AnimeSaturnPlaybackData>()
        subSources[normalizedNumber]?.let(sources::add)
        dubSources[normalizedNumber]?.let(sources::add)
        return sources.distinctBy { "${it.label}:${it.watchUrl}" }
    }

    fun firstPlaybacks(): List<AnimeSaturnPlaybackData> {
        val firstNumber = episodeNumbers().firstOrNull() ?: return emptyList()
        return playbacksForEpisode(firstNumber)
    }

    fun episodeNumbers(): List<String> = sortedEpisodeNumbers(subSources, dubSources)
}

internal data class AnimeUnityTitleSources(
    val syncIds: AnimeSyncIds,
    val subSources: Map<String, String>,
    val dubSources: Map<String, String>,
    val title: String? = null,
    val plot: String? = null,
    val posterUrl: String? = null,
) {
    fun playbackForEpisode(number: String?): AnimeUnityPlaybackData? {
        val normalizedNumber = normalizeAnimeEpisodeNumber(number) ?: return null
        val subUrl = subSources[normalizedNumber]
        val dubUrl = dubSources[normalizedNumber]
        val preferredUrl = subUrl ?: dubUrl ?: return null
        return AnimeUnityPlaybackData(
            preferredUrl = preferredUrl,
            subUrl = subUrl,
            dubUrl = dubUrl,
        )
    }

    fun firstPlayback(): AnimeUnityPlaybackData? {
        val firstNumber = episodeNumbers().firstOrNull()
        return playbackForEpisode(firstNumber)
    }

    fun episodeNumbers(): List<String> = sortedEpisodeNumbers(subSources, dubSources)
}
