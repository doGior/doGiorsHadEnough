package it.dogior.hadEnough.remote

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.CommonActivity
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncPresence
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncTrust
import it.dogior.hadEnough.playback.StreamCenterPlayerHooks
import it.dogior.hadEnough.util.StreamCenterDevice
import it.dogior.hadEnough.util.StreamCenterForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

internal object StreamCenterRemote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var installed = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == StreamCenterPlugin.PREF_REMOTE_CONTROL) refresh()
    }

    @Synchronized
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (installed) return refresh()
        installed = true
        StreamCenterForeground.attach(app)
        StreamCenterForeground.addListener { refresh() }
        StreamCenterPlugin.activeSharedPref?.registerOnSharedPreferenceChangeListener(preferenceListener)
        StreamCenterRemotePlayer.configure(app, enabled = ::isEnabled, onContinue = ::continueFromPlayer)
        StreamCenterRemoteNavigator.install()
        StreamCenterPlayerHooks.register(context, "remote", StreamCenterRemotePlayer)
        refresh()
    }

    fun isEnabled(): Boolean = StreamCenterPlugin.isRemoteControlEnabled(StreamCenterPlugin.activeSharedPref)

    fun hasTrustedPeers(context: Context): Boolean =
        StreamCenterLocalSyncTrust.trustedPeers(context.applicationContext).isNotEmpty()

    fun refresh() {
        val context = appContext ?: return
        val shouldRun = isEnabled() &&
            !StreamCenterPlugin.completeResetInProgress &&
            StreamCenterForeground.isForeground &&
            hasTrustedPeers(context)
        StreamCenterRemoteReceiver.update(context, shouldRun)
        if (!isEnabled()) StreamCenterRemoteNavigator.clear()
    }

    suspend fun stopForCompleteReset() {
        StreamCenterRemoteReceiver.stop()?.join()
        StreamCenterRemoteNavigator.clear()
        appContext?.let(StreamCenterRemoteQueue::clear)
    }

    private fun continueFromPlayer(handoff: RemoteHandoff) {
        val context = appContext ?: return
        if (!StreamCenterDevice.isTvLike(context)) return sendToTv(handoff)
        send(context, handoff, "il telefono") {
            StreamCenterRemoteNavigator.park(handoff)
            StreamCenterRemotePlayer.pause()
            StreamCenterRemoteNavigator.toast("Telefono non trovato: apri StreamCenter sul telefono e tocca «Riprendi qui» in Telecomando")
        }
    }

    fun sendToTv(handoff: RemoteHandoff) {
        val context = appContext ?: return
        send(context, handoff, "la TV") {
            StreamCenterRemoteNavigator.toast("Nessuna TV con StreamCenter aperto trovata")
        }
    }

    private fun send(context: Context, handoff: RemoteHandoff, target: String, onMissing: () -> Unit) {
        StreamCenterRemoteNavigator.toast("Cerco $target…")
        scope.launch {
            val receivers = runCatching { StreamCenterRemoteClient.scanReceivers(context) }.getOrDefault(emptyList())
            when (receivers.size) {
                0 -> onMissing()
                1 -> sendToReceiver(context, receivers.first(), handoff, onMissing)
                else -> mainHandler.post { chooseReceiver(receivers) { sendToReceiver(context, it, handoff, onMissing) } }
            }
        }
    }

    private fun sendToReceiver(
        context: Context,
        receiver: StreamCenterLocalSyncPresence,
        handoff: RemoteHandoff,
        onMissing: () -> Unit,
    ) {
        scope.launch {
            val message = JSONObject().put("type", "play").put("handoff", handoff.toJson())
            val delivered = runCatching { StreamCenterRemoteClient.deliver(context, receiver, message) }.isSuccess
            if (delivered) {
                StreamCenterRemotePlayer.close()
                StreamCenterRemoteNavigator.toast("Continua su ${receiver.name}")
            } else {
                onMissing()
            }
        }
    }

    fun chooseReceiver(receivers: List<StreamCenterLocalSyncPresence>, onChosen: (StreamCenterLocalSyncPresence) -> Unit) {
        val activity = CommonActivity.activity ?: return
        AlertDialog.Builder(activity)
            .setTitle("Scegli il dispositivo")
            .setItems(receivers.map { it.name }.toTypedArray()) { _, which -> onChosen(receivers[which]) }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
