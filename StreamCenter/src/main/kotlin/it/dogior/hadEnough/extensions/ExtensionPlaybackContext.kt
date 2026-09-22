package it.dogior.hadEnough.extensions

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.model.StreamCenterPlaybackData
import it.dogior.hadEnough.util.runCatchingCancellable

internal data class ExtensionPlaybackContext(
    val titles: List<String>,
    val year: Int? = null,
    val isMovie: Boolean = false,
    val isAnime: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
    val mappedSeason: Int? = null,
    val mappedEpisode: Int? = null,
    val dubStatus: DubStatus? = null,
    val ids: Map<String, String> = emptyMap(),
)

internal fun LoadResponse.extensionIds(): Map<String, String> = buildMap {
    getAniListId()?.let { put("anilist", it) }
    getMalId()?.let { put("mal", it) }
    getKitsuId()?.let { put("kitsu", it) }
    getImdbId()?.let { put("imdb", it) }
    getTMDbId()?.let { put("tmdb", it) }
}.filterValues { it.isNotBlank() && it != "null" }

internal fun LoadResponse.attachExtensionPlayback() {
    if (this !is MovieLoadResponse && this !is AnimeLoadResponse && this !is TvSeriesLoadResponse) return
    val anime = this as? AnimeLoadResponse
    val isMovie = this is MovieLoadResponse || type == TvType.AnimeMovie
    val base = ExtensionPlaybackContext(
        titles = (listOfNotNull(name, anime?.engName, anime?.japName) + anime?.synonyms.orEmpty()).distinct(),
        year = year,
        isMovie = isMovie,
        isAnime = anime != null || type == TvType.AnimeMovie,
        ids = extensionIds(),
    )
    fun decorate(data: String, episode: Episode? = null, dub: DubStatus? = null): String {
        val playback = runCatchingCancellable { parseJson<StreamCenterPlaybackData>(data) }.getOrNull()
            ?: StreamCenterPlaybackData(tmdbUrl = data.takeIf(String::isNotBlank))
        val torrent = playback.torrent
        val stremio = playback.stremio
        val context = base.copy(
            titles = (base.titles + torrent?.titles.orEmpty() + listOfNotNull(torrent?.englishTitle, torrent?.japaneseTitle))
                .map(String::trim).filter(String::isNotBlank).distinct().take(10),
            season = episode?.season,
            episode = episode?.episode,
            mappedSeason = stremio?.season ?: torrent?.season,
            mappedEpisode = stremio?.episode ?: torrent?.episode,
            dubStatus = dub,
            ids = buildMap {
                stremio?.anilistId?.let { put("anilist", it.toString()) }
                stremio?.malId?.let { put("mal", it.toString()) }
                stremio?.kitsuId?.let { put("kitsu", it.toString()) }
                (stremio?.imdbId ?: torrent?.imdbId)?.let { put("imdb", it) }
                stremio?.tmdbId?.let { put("tmdb", it) }
                putAll(base.ids)
            },
        )
        return playback.copy(extensions = context).toJson()
    }
    when (this) {
        is MovieLoadResponse -> dataUrl = decorate(dataUrl)
        is TvSeriesLoadResponse -> episodes = episodes.map { it.copy(data = decorate(it.data, it)) }
        is AnimeLoadResponse -> episodes = episodes.mapValues { (dub, list) ->
            list.map { it.copy(data = decorate(it.data, it, dub)) }
        }.toMutableMap()
    }
}
