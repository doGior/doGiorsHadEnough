package it.dogior.hadEnough.playback

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.playback.StreamCenterPlayerOverlay.findViewByName
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterVpnGuard

internal object StreamCenterVpnBadge : PlayerSessionFactory {
    private const val VPN_CHECK_INTERVAL_MS = 1_000L
    private const val VPN_ON_COLOR = "#B3166534"
    private const val VPN_OFF_COLOR = "#B3991B1B"

    fun install(context: Context) {
        StreamCenterPlayerHooks.register(context, "vpn_badge", this)
    }

    override fun create(fragment: AbstractPlayerFragment<*>, exoPlayer: ExoPlayer): PlayerSession = VpnBadgeSession(fragment)

    override fun onFailure(error: Throwable) {
        StreamCenterLogger.warning("Indicatore VPN: aggancio al player non riuscito", mapOf("errore" to error.toString()))
    }

    private class VpnBadgeSession(private val fragment: AbstractPlayerFragment<*>) : PlayerSession {
        private var badge: TextView? = null
        private var alert: TextView? = null
        private var topHolder: View? = null
        private var host: FrameLayout? = null
        private var vpnActive: Boolean? = null
        private var protectionEnabled = false
        private var lastCheckAt = 0L

        override fun onFrame() {
            val root = fragment.view ?: return
            val top = topHolder ?: root.findViewByName("player_top_holder")?.also { topHolder = it }
            val now = SystemClock.elapsedRealtime()
            if (vpnActive == null || now - lastCheckAt >= VPN_CHECK_INTERVAL_MS) {
                lastCheckAt = now
                protectionEnabled = StreamCenterPlugin.isVpnRequired(StreamCenterPlugin.activeSharedPref)
                vpnActive = if (protectionEnabled) StreamCenterVpnGuard.isVpnActive(root.context) else false
            }
            val active = vpnActive == true
            val controls = StreamCenterPlayerOverlay.controlsVisible(top)
            val showBadge = protectionEnabled && controls
            val showAlert = protectionEnabled && !active && !controls && fragment.isResumed
            if (showBadge) (badge ?: createBadge(root))?.let { render(it, active) }
            if (showAlert) (alert ?: createAlert())?.let { render(it, active) }
            badge?.setShown(showBadge)
            alert?.setShown(showAlert)
        }

        override fun release() {
            listOfNotNull(badge, alert).forEach { (it.parent as? ViewGroup)?.removeView(it) }
            badge = null
            alert = null
        }

        private fun createBadge(root: View): TextView? {
            val frame = host ?: StreamCenterPlayerOverlay.host(root)?.also { host = it } ?: return null
            return StreamCenterPlayerOverlay.pill(frame.context, "", focusable = false).also {
                StreamCenterPlayerOverlay.addToSideBar(frame, it, first = true)
                badge = it
            }
        }

        private fun createAlert(): TextView? {
            val content = fragment.activity?.findViewById<FrameLayout>(android.R.id.content) ?: return null
            return StreamCenterPlayerOverlay.pill(content.context, "", focusable = false).also {
                StreamCenterPlayerOverlay.addFloating(content, it)
                alert = it
            }
        }

        private fun render(view: TextView, active: Boolean) {
            if (view.tag == active) return
            view.tag = active
            view.text = if (active) "🔒 VPN ON" else "⚠️ VPN OFF"
            view.contentDescription = if (active) "VPN attiva" else "VPN non attiva"
            view.background = StreamCenterPlayerOverlay.pillBackground(
                view.context,
                if (active) VPN_ON_COLOR else VPN_OFF_COLOR,
                null,
            )
        }

        private fun TextView.setShown(shown: Boolean) {
            val wanted = if (shown) View.VISIBLE else View.GONE
            if (visibility != wanted) {
                visibility = wanted
                StreamCenterPlayerOverlay.updateSideBarMargins(this)
            }
        }
    }
}
