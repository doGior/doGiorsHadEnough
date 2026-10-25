package it.dogior.hadEnough.remote

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

internal data class RemoteResumeItem(val handoff: RemoteHandoff, val poster: String?)

internal object StreamCenterRemoteHistory {
    fun recent(limit: Int): List<RemoteResumeItem> = runCatching {
        DataStoreHelper.getAllResumeStateIds().orEmpty()
            .mapNotNull { id -> DataStoreHelper.getLastWatched(id)?.let { id to it } }
            .sortedByDescending { (_, resume) -> resume.updateTime }
            .mapNotNull { (id, resume) ->
                val header = getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, id.toString())
                    ?: getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE_BACKUP, id.toString())
                    ?: return@mapNotNull null
                val position = resume.episodeId?.let(DataStoreHelper::getViewPos)
                RemoteResumeItem(
                    handoff = RemoteHandoff(
                        url = header.url,
                        apiName = header.apiName,
                        name = header.name,
                        episodeId = resume.episodeId,
                        season = resume.season,
                        episode = resume.episode,
                        positionMs = position?.position ?: 0L,
                        durationMs = position?.duration ?: 0L,
                        linkName = null,
                    ),
                    poster = header.poster,
                )
            }
            .take(limit)
    }.getOrDefault(emptyList())
}
