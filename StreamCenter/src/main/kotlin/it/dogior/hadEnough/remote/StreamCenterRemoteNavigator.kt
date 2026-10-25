package it.dogior.hadEnough.remote

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.playback.StreamCenterSourceOrder
import it.dogior.hadEnough.settings.StreamCenterBaseSettingsFragment
import it.dogior.hadEnough.util.StreamCenterLogger

internal data class RemoteCard(val url: String, val apiName: String, val name: String)

internal object StreamCenterRemoteNavigator {
    private const val PARKED_HANDOFF_TTL_MS = 30 * 60_000L
    private const val PINNED_SOURCE_MS = 2 * 60_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingCard: RemoteCard? = null

    @Volatile
    private var parked: Pair<RemoteHandoff, Long>? = null

    fun install() {
        StreamCenterRemotePlayer.addCloseListener {
            pendingCard?.let { card ->
                pendingCard = null
                mainHandler.post { load(card, START_ACTION_RESUME_LATEST, 0) }
            }
        }
    }

    fun openCard(card: RemoteCard) {
        if (StreamCenterPlugin.completeResetInProgress) return
        mainHandler.post {
            if (StreamCenterRemotePlayer.isActive) {
                pendingCard = card
                toast("Scheda ricevuta: ${card.name}. Si apre quando chiudi il player")
            } else {
                load(card, START_ACTION_RESUME_LATEST, 0)
            }
        }
    }

    fun play(handoff: RemoteHandoff) {
        if (StreamCenterPlugin.completeResetInProgress) return
        mainHandler.post {
            val episodeId = handoff.episodeId
            if (episodeId != null && handoff.positionMs > 0) {
                runCatching { DataStoreHelper.setViewPos(episodeId, handoff.positionMs, handoff.durationMs) }
            }
            handoff.linkName?.let { StreamCenterSourceOrder.pinOnce(it, PINNED_SOURCE_MS) }
            val card = RemoteCard(handoff.url, handoff.apiName, handoff.name)
            if (episodeId != null) load(card, START_ACTION_LOAD_EP, episodeId)
            else load(card, START_ACTION_RESUME_LATEST, 0)
        }
    }

    fun park(handoff: RemoteHandoff) {
        parked = handoff to System.currentTimeMillis() + PARKED_HANDOFF_TTL_MS
    }

    fun parkedHandoff(): RemoteHandoff? {
        val (handoff, until) = parked ?: return null
        if (System.currentTimeMillis() > until) {
            parked = null
            return null
        }
        return handoff
    }

    fun takeParked(): RemoteHandoff? = parkedHandoff().also { parked = null }

    fun clear() {
        pendingCard = null
        parked = null
    }

    fun toast(message: String) {
        mainHandler.post { runCatching { CommonActivity.showToast(message, Toast.LENGTH_SHORT) } }
    }

    private fun load(card: RemoteCard, action: Int, value: Int) {
        val activity = CommonActivity.activity as? FragmentActivity ?: return
        runCatching { StreamCenterBaseSettingsFragment.closeAllForPlayback() }
        runCatching { activity.loadResult(card.url, card.apiName, card.name, action, value) }.onFailure {
            StreamCenterLogger.warning("Telecomando: apertura scheda non riuscita", mapOf("errore" to it.toString()))
        }
    }
}
