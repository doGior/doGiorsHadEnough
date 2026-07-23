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

internal data class StreamCenterTrackingIds(
    val anilist: Int? = null,
    val mal: Int? = null,
    val kitsu: Int? = null,
    val simkl: Int? = null,
    val imdb: String? = null,
    val tmdb: String? = null,
)

internal fun LoadResponse.addStreamCenterTrackingIds(ids: StreamCenterTrackingIds) {
    ids.anilist?.let { addAniListId(it) }
    ids.mal?.let { addMalId(it) }
    ids.kitsu?.let { addKitsuId(it) }
    ids.simkl?.let { addSimklId(it) }
    ids.imdb?.let { addImdbId(it) }
    ids.tmdb?.let { addTMDbId(it) }
}

internal fun LoadResponse.addStreamCenterTrackingId(name: SyncIdName, value: String) {
    val id = value.substringBefore('/').trim().toIntOrNull()
    when (name) {
        SyncIdName.Anilist -> id?.let { addAniListId(it) }
        SyncIdName.MyAnimeList -> id?.let { addMalId(it) }
        SyncIdName.Kitsu -> id?.let { addKitsuId(it) }
        SyncIdName.Simkl -> id?.let { addSimklId(it) }
        SyncIdName.Imdb -> value.trim().takeIf(String::isNotBlank)?.let { addImdbId(it) }
        else -> Unit
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
