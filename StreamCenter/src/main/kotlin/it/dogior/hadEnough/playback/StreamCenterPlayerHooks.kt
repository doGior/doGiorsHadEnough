package it.dogior.hadEnough.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.media3.exoplayer.ExoPlayer
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import it.dogior.hadEnough.settings.StreamCenterBaseSettingsFragment
import it.dogior.hadEnough.util.Reflect
import it.dogior.hadEnough.util.StreamCenterLogger
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal interface PlayerSession {
    fun onFrame()
    fun release()
}

internal interface PlayerSessionFactory {
    fun create(fragment: AbstractPlayerFragment<*>, exoPlayer: ExoPlayer): PlayerSession?
    fun onFailure(error: Throwable)
}

internal object StreamCenterPlayerHooks {
    private var installed = false
    private val factories = ConcurrentHashMap<String, PlayerSessionFactory>()
    private val attachedActivities = WeakHashMap<Activity, Boolean>()
    private val monitors = WeakHashMap<Fragment, PlayerMonitor>()

    fun register(context: Context, key: String, factory: PlayerSessionFactory) {
        factories[key] = factory
        if (installed) return
        installed = true
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
        (context as? Activity)?.let(::attach)
        runCatching { CommonActivity.activity }.getOrNull()?.let(::attach)
    }

    private fun attach(activity: Activity) {
        val fragmentActivity = activity as? FragmentActivity ?: return
        if (attachedActivities.put(activity, true) == true) return
        fragmentActivity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        fragmentActivity.supportFragmentManager.fragments.forEach(::watchTree)
    }

    private fun watchTree(fragment: Fragment) {
        if (fragment.view != null) startMonitor(fragment)
        runCatching { fragment.childFragmentManager.fragments }.getOrNull()?.forEach(::watchTree)
    }

    private fun startMonitor(fragment: Fragment) {
        if (fragment !is AbstractPlayerFragment<*> || monitors.containsKey(fragment)) return
        monitors[fragment] = PlayerMonitor(fragment).also { it.start() }
        fragment.view?.post { runCatching { StreamCenterBaseSettingsFragment.closeAllForPlayback() } }
    }

    private fun stopMonitor(fragment: Fragment) {
        monitors.remove(fragment)?.stop()
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            runCatching { startMonitor(f) }.onFailure {
                StreamCenterLogger.warning("Aggancio al player non riuscito", mapOf("errore" to it.toString()))
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = stopMonitor(f)
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = attach(activity)
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            attachedActivities.remove(activity)
        }
    }

    private class PlayerMonitor(private val fragment: AbstractPlayerFragment<*>) : Choreographer.FrameCallback {
        private val choreographer = Choreographer.getInstance()
        private val sessions = LinkedHashMap<String, PlayerSession>()
        private val failed = HashSet<String>()
        private var exoPlayer: ExoPlayer? = null
        private var running = false

        fun start() {
            if (running) return
            running = true
            choreographer.postFrameCallback(this)
        }

        fun stop() {
            running = false
            choreographer.removeFrameCallback(this)
            releaseSessions()
            exoPlayer = null
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            runCatching { refreshPlayer() }.onFailure {
                StreamCenterLogger.warning("Aggancio al player interrotto", mapOf("errore" to it.toString()))
                stop()
                return
            }
            sessions.entries.toList().forEach { (key, session) ->
                runCatching { session.onFrame() }.onFailure { disable(key, it) }
            }
            choreographer.postFrameCallback(this)
        }

        private fun refreshPlayer() {
            val current = Reflect.get(fragment.player, "exoPlayer") as? ExoPlayer
            if (current === exoPlayer) return
            releaseSessions()
            exoPlayer = current ?: return
            factories.forEach { (key, factory) ->
                if (key in failed) return@forEach
                runCatching { factory.create(fragment, current) }
                    .onSuccess { session -> session?.let { sessions[key] = it } }
                    .onFailure { disable(key, it) }
            }
        }

        private fun disable(key: String, error: Throwable) {
            failed += key
            sessions.remove(key)?.let { session -> runCatching { session.release() } }
            factories[key]?.onFailure(error)
        }

        private fun releaseSessions() {
            sessions.values.forEach { session -> runCatching { session.release() } }
            sessions.clear()
        }
    }
}
