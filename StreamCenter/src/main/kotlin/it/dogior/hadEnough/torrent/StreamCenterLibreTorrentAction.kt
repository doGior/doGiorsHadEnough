package it.dogior.hadEnough.torrent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt

class StreamCenterLibreTorrentAction : VideoClickAction() {
    override val name: UiText = txt("LibreTorrent (StreamCenter)")
    override val oneSource: Boolean = true
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.MAGNET, ExtractorLinkType.TORRENT)

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val url = result.links.getOrNull(index ?: 0)?.url ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(LIBRETORRENT_PACKAGE)
        }
        launch(intent, null)
    }

    private companion object {
        const val LIBRETORRENT_PACKAGE = "org.proninyaroslav.libretorrent"
    }
}
