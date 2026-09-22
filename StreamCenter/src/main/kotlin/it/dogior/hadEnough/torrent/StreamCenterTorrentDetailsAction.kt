package it.dogior.hadEnough.torrent

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StreamCenterTorrentDetailsAction : VideoClickAction() {
    override val name: UiText = txt("Dettagli torrent (StreamCenter)")
    override val oneSource: Boolean = true
    override val sourceTypes: Set<ExtractorLinkType> = setOf(ExtractorLinkType.MAGNET)

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean = context != null

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val activityContext = context ?: return
        val link = result.links.getOrNull(index ?: 0) ?: return
        withContext(Dispatchers.Main) { showDetails(activityContext, link.name, link.url) }
    }

    internal fun showDetails(context: Context, name: String, magnet: String): AlertDialog {
        val details = buildString {
            append(name)
            StreamCenterTorrentMagnet.displayName(magnet)?.let { append("\n\nNome: $it") }
            StreamCenterTorrentMagnet.fileIndex(magnet)?.let { append("\nFile nel torrent: ${it.toLong() + 1}") }
            StreamCenterTorrentMagnet.infoHash(magnet)?.let { append("\n\nInfo hash: $it") }
        }
        return AlertDialog.Builder(context)
            .setTitle("Dettagli torrent")
            .setMessage(details)
            .setNeutralButton("Copia magnet") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@setNeutralButton
                clipboard.setPrimaryClip(ClipData.newPlainText("Magnet StreamCenter", magnet))
                Toast.makeText(context, "Magnet copiato", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Apri con…") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(magnet))
                try {
                    context.startActivity(Intent.createChooser(intent, "Apri torrent con"))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Nessun client torrent disponibile", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }
}
