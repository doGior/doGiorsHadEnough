package it.dogior.hadEnough.remote

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.txt
import it.dogior.hadEnough.util.Reflect
import it.dogior.hadEnough.util.StreamCenterDevice

class StreamCenterRemoteEpisodeAction : StreamCenterRemoteTvAction() {
    override val name: UiText = txt("📺 Riproduci sulla TV (StreamCenter)")

    override fun chosenLink(result: LinkLoadingResult, index: Int?): String? = null
}

class StreamCenterRemoteSourceAction : StreamCenterRemoteTvAction() {
    override val name: UiText = txt("📺 Scegli fonte per la TV (StreamCenter)")
    override val oneSource: Boolean = true

    override fun chosenLink(result: LinkLoadingResult, index: Int?): String? =
        result.links.getOrNull(index ?: 0)?.name
}

abstract class StreamCenterRemoteTvAction : VideoClickAction() {
    protected abstract fun chosenLink(result: LinkLoadingResult, index: Int?): String?

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean =
        context != null && StreamCenterRemote.isEnabled() &&
            !StreamCenterDevice.isTvLike(context) && StreamCenterRemote.hasTrustedPeers(context)

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val page = currentPage(video) ?: return StreamCenterRemoteNavigator.toast("Scheda non trovata, riaprila e riprova")
        val position = DataStoreHelper.getViewPos(video.id)
        StreamCenterRemote.sendToTv(RemoteHandoff(
            url = page.url,
            apiName = page.apiName,
            name = page.name,
            episodeId = video.id,
            season = video.season,
            episode = video.episode,
            positionMs = position?.position ?: video.position,
            durationMs = position?.duration ?: video.duration,
            linkName = chosenLink(result, index),
        ))
    }

    private fun currentPage(video: ResultEpisode): RemoteCard? =
        StreamCenterRemoteCards.find(video.apiName, video.headerName ?: "")
            ?: openedPage(video)
            ?: cachedHeader(video)

    private fun openedPage(video: ResultEpisode): RemoteCard? {
        val activity = CommonActivity.activity as? FragmentActivity ?: return null
        return allFragments(activity.supportFragmentManager.fragments)
            .flatMap { fragment -> fieldsOf<ResultViewModel2>(fragment) }
            .flatMap { viewModel -> fieldsOf<LoadResponse>(viewModel) }
            .firstOrNull { it.apiName == video.apiName }
            ?.let { RemoteCard(it.url, it.apiName, it.name) }
    }

    private fun cachedHeader(video: ResultEpisode): RemoteCard? {
        val header = getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, video.parentId.toString())
            ?: getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE_BACKUP, video.parentId.toString())
            ?: return null
        return RemoteCard(header.url, header.apiName, header.name)
    }

    private inline fun <reified T> fieldsOf(target: Any): List<T> =
        Reflect.instanceFields(target.javaClass).mapNotNull { runCatching { it.get(target) }.getOrNull() as? T }

    private fun allFragments(fragments: List<Fragment>): List<Fragment> = fragments.flatMap { fragment ->
        listOf(fragment) + allFragments(runCatching { fragment.childFragmentManager.fragments }.getOrDefault(emptyList()))
    }
}
