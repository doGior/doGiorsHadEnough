package it.dogior.hadEnough.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet

internal object StreamCenterForeground {
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private var registered = false
    private var startedActivities = 0

    @Volatile
    var isForeground = false
        private set

    @Synchronized
    fun attach(context: Context) {
        if (!registered) {
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(callbacks)
            registered = true
        }
        if (!isForeground) update(true)
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun update(foreground: Boolean) {
        isForeground = foreground
        listeners.forEach { listener -> runCatching { listener(foreground) } }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities += 1
            if (startedActivities == 1 && !isForeground) update(true)
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0 && isForeground) update(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
