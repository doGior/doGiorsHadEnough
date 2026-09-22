package it.dogior.hadEnough.tracking

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import it.dogior.hadEnough.catalog.StreamCenterSimklIds
import it.dogior.hadEnough.catalog.StreamCenterSimklMedia
import it.dogior.hadEnough.util.StreamCenterLogger

internal data class StreamCenterTrackingIds(
    val anilist: Int? = null,
    val mal: Int? = null,
    val kitsu: Int? = null,
    val simkl: Int? = null,
    val imdb: String? = null,
    val tmdb: String? = null,
)

internal enum class TrackingIdService { MAL, ANILIST, KITSU, SIMKL, IMDB, TMDB }

internal val ALL_TRACKING_ID_SERVICES: Set<TrackingIdService> = TrackingIdService.entries.toSet()

internal fun LoadResponse.addStreamCenterTrackingIds(
    ids: StreamCenterTrackingIds,
    showAsTags: Boolean = false,
    visibleServices: Set<TrackingIdService> = ALL_TRACKING_ID_SERVICES,
) {
    ids.anilist?.let { addAniListId(it) }
    ids.mal?.let { addMalId(it) }
    ids.kitsu?.let { addKitsuId(it) }
    ids.simkl?.let { addSimklId(it) }
    ids.imdb?.let { addImdbId(it) }
    ids.tmdb?.let { addTMDbId(it) }
    if (showAsTags) addStreamCenterTrackingIdTags(ids, visibleServices)
    StreamCenterLogger.logTab(
        tabName = name,
        action = "ID servizi di tracciamento associati",
        metadata = ids.toLogMetadata(),
    )
}

internal fun LoadResponse.addStreamCenterTrackingId(
    name: SyncIdName,
    value: String,
    showAsTags: Boolean = false,
) {
    val id = value.substringBefore('/').trim().toIntOrNull()
    val ids = when (name) {
        SyncIdName.Anilist -> id?.let { StreamCenterTrackingIds(anilist = it) }
        SyncIdName.MyAnimeList -> id?.let { StreamCenterTrackingIds(mal = it) }
        SyncIdName.Kitsu -> id?.let { StreamCenterTrackingIds(kitsu = it) }
        SyncIdName.Simkl -> id?.let { StreamCenterTrackingIds(simkl = it) }
        SyncIdName.Imdb -> value.trim().takeIf(String::isNotBlank)?.let { StreamCenterTrackingIds(imdb = it) }
        else -> null
    } ?: return

    addStreamCenterTrackingIds(ids, showAsTags)
}

private fun StreamCenterTrackingIds.toLogMetadata(): Map<String, String> = linkedMapOf(
    "id_anilist" to anilist.orUnavailable(),
    "id_myanimelist" to mal.orUnavailable(),
    "id_kitsu" to kitsu.orUnavailable(),
    "id_simkl" to simkl.orUnavailable(),
    "id_imdb" to imdb.orUnavailable(),
    "id_tmdb" to tmdb.orUnavailable(),
)

private fun Any?.orUnavailable(): String = this?.toString()?.takeIf(String::isNotBlank) ?: "Non disponibile"

private fun LoadResponse.addStreamCenterTrackingIdTags(
    ids: StreamCenterTrackingIds,
    visibleServices: Set<TrackingIdService>,
) {
    fun <T> T?.ifVisible(service: TrackingIdService): T? = this?.takeIf { service in visibleServices }
    val animeTag = listOfNotNull(
        ids.mal.ifVisible(TrackingIdService.MAL)?.let { "MAL $it" },
        ids.anilist.ifVisible(TrackingIdService.ANILIST)?.let { "AniList $it" },
        ids.kitsu.ifVisible(TrackingIdService.KITSU)?.let { "Kitsu $it" },
    ).joinToString("/ ").takeIf(String::isNotBlank)
    val generalTag = listOfNotNull(
        ids.simkl.ifVisible(TrackingIdService.SIMKL)?.let { "Simkl $it" },
        ids.imdb?.takeIf(String::isNotBlank).ifVisible(TrackingIdService.IMDB)?.let { "IMDb $it" },
        ids.tmdb?.takeIf(String::isNotBlank).ifVisible(TrackingIdService.TMDB)?.let { "TMDB $it" },
    ).joinToString("/ ").takeIf(String::isNotBlank)
    val idTags = listOfNotNull(animeTag, generalTag)
    if (idTags.isNotEmpty()) {
        tags = (idTags + tags.orEmpty()).distinct()
    }
}

internal fun StreamCenterSimklMedia.trackingIds() = ids.trackingIds()

internal fun StreamCenterSimklIds.trackingIds() = StreamCenterTrackingIds(
    anilist = anilist,
    mal = mal,
    kitsu = kitsu,
    simkl = simkl,
    imdb = imdb,
    tmdb = tmdb,
)
