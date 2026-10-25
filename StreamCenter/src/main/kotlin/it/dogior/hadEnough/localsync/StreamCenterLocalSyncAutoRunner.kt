package it.dogior.hadEnough.localsync

import android.content.Context
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.util.StreamCenterForeground
import java.util.concurrent.CopyOnWriteArraySet

internal object StreamCenterLocalSyncAutoRunner {
    private var appContext: Context? = null
    private var coordinator: StreamCenterLocalSyncAutoCoordinator? = null
    private val listeners = CopyOnWriteArraySet<StreamCenterLocalSyncListener>()
    private var lifecycleRegistered = false

    private val fanOut = object : StreamCenterLocalSyncListener {
        override fun onStateChanged(state: StreamCenterLocalSyncState, message: String) =
            listeners.forEach { it.onStateChanged(state, message) }

        override fun onEvent(event: StreamCenterLocalSyncEvent) =
            listeners.forEach { it.onEvent(event) }

        override fun onOfferFound(offer: StreamCenterLocalSyncOffer) =
            listeners.forEach { it.onOfferFound(offer) }

        override fun onPairingCodeReady(code: String) =
            listeners.forEach { it.onPairingCodeReady(code) }

        override fun onCompleted(result: StreamCenterLocalSyncResult) =
            listeners.forEach { it.onCompleted(result) }

        override fun onError(message: String, error: Throwable?) =
            listeners.forEach { it.onError(message, error) }
    }

    @Synchronized
    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (coordinator == null) coordinator = StreamCenterLocalSyncAutoCoordinator(app, fanOut)
        StreamCenterForeground.attach(app)
        if (!lifecycleRegistered) {
            StreamCenterForeground.addListener { refresh() }
            lifecycleRegistered = true
        }
        refresh()
    }

    fun addListener(listener: StreamCenterLocalSyncListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StreamCenterLocalSyncListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun refresh() {
        val activeCoordinator = coordinator ?: return
        val context = appContext ?: return
        if (!StreamCenterPlugin.completeResetInProgress && StreamCenterForeground.isForeground && StreamCenterLocalSyncAutoConfig.isOperational(context)) {
            activeCoordinator.start()
        } else {
            activeCoordinator.stop()
        }
    }

    fun forcePeerSync(peerId: String): Boolean {
        if (StreamCenterPlugin.completeResetInProgress) return false
        return coordinator?.forcePeerSync(peerId) == true
    }

    suspend fun stopForCompleteReset() {
        val running = synchronized(this) { coordinator?.stopForCompleteReset() }
        running?.join()
    }
}
