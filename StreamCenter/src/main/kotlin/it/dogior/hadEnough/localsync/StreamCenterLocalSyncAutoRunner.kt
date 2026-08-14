package it.dogior.hadEnough.localsync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet

internal object StreamCenterLocalSyncAutoRunner {
    private var appContext: Context? = null
    private var coordinator: StreamCenterLocalSyncAutoCoordinator? = null
    private val listeners = CopyOnWriteArraySet<StreamCenterLocalSyncListener>()
    private var startedActivities = 0
    private var foreground = false
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
        if (!lifecycleRegistered) {
            (app as? Application)?.registerActivityLifecycleCallbacks(callbacks)
            lifecycleRegistered = true
        }
        foreground = true
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
        if (foreground && StreamCenterLocalSyncAutoConfig.isOperational(context)) {
            activeCoordinator.start()
        } else {
            activeCoordinator.stop()
        }
    }

    fun forcePeerSync(peerId: String): Boolean {
        return coordinator?.forcePeerSync(peerId) == true
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities += 1
            if (startedActivities == 1) {
                foreground = true
                refresh()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) {
                foreground = false
                refresh()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
