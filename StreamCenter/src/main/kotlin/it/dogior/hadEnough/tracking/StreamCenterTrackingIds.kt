package it.dogior.hadEnough.tracking

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.syncproviders.SyncIdName
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

internal fun LoadResponse.addStreamCenterTrackingIds(
    ids: StreamCenterTrackingIds,
    showAsTags: Boolean = false,
) {
    ids.anilist?.let { addAniListId(it) }
    ids.mal?.let { addMalId(it) }
    ids.kitsu?.let { addKitsuId(it) }
    ids.simkl?.let { addSimklId(it) }
    ids.imdb?.let { addImdbId(it) }
    ids.tmdb?.let { addTMDbId(it) }
    if (showAsTags) addStreamCenterTrackingIdTags(ids)
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

private fun LoadResponse.addStreamCenterTrackingIdTags(ids: StreamCenterTrackingIds) {
    val idTags = listOfNotNull(
        ids.mal?.let { "MAL: $it" },
        ids.anilist?.let { "AniList: $it" },
        ids.kitsu?.let { "Kitsu: $it" },
        ids.simkl?.let { "Simkl: $it" },
    )
    if (idTags.isNotEmpty()) {
        tags = (idTags + tags.orEmpty()).distinct()
    }
}

internal fun StreamCenterSimklMedia.trackingIds() = StreamCenterTrackingIds(
    anilist = ids.anilist,
    mal = ids.mal,
    kitsu = ids.kitsu,
    simkl = ids.simkl,
    imdb = ids.imdb,
    tmdb = ids.tmdb,
)
