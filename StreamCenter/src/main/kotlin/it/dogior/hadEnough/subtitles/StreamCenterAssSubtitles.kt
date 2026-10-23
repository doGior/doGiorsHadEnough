package it.dogior.hadEnough.subtitles

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
import java.util.WeakHashMap

object StreamCenterAssSubtitles {
    private var installed = false
    private var isEnabled: () -> Boolean = { false }
    private var appContext: Context? = null
    private var pluginFile: String? = null
    private val attachedActivities = WeakHashMap<Activity, Boolean>()
    private val monitors = WeakHashMap<Fragment, PlayerMonitor>()

    fun install(context: Context, pluginFile: String?, enabled: () -> Boolean) {
        isEnabled = enabled
        this.pluginFile = pluginFile
        val application = context.applicationContext
        appContext = application
        if (enabled()) LibassLibrary.preload(application, pluginFile)
        if (installed) return
        installed = true
        (application as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
        (context as? Activity)?.let(::attach)
        runCatching { CommonActivity.activity }.getOrNull()?.let(::attach)
    }

    private fun libraryReady(): Boolean {
        val context = appContext ?: return false
        return LibassLibrary.isReady(context, pluginFile)
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
    }

    private fun stopMonitor(fragment: Fragment) {
        monitors.remove(fragment)?.stop()
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            runCatching { startMonitor(f) }.onFailure { AssLog.error("Aggancio al player non riuscito", it) }
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

    private class PlayerMonitor(private val fragment: AbstractPlayerFragment<*>) : Choreographer.FrameCallback, AssPlayerHost {
        private val choreographer = Choreographer.getInstance()
        private var running = false
        private var session: AssPlaybackSession? = null

        fun start() {
            if (running) return
            running = true
            choreographer.postFrameCallback(this)
        }

        fun stop() {
            running = false
            choreographer.removeFrameCallback(this)
            session?.release()
            session = null
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            runCatching { tick() }.onFailure {
                AssLog.error("Sottotitoli libass disattivati per questo player", it)
                stop()
                return
            }
            choreographer.postFrameCallback(this)
        }

        private fun tick() {
            val exoPlayer = currentExoPlayer()
            val current = session
            if (exoPlayer !== current?.exoPlayer) {
                current?.release()
                session = exoPlayer?.let(::createSession)
            }
            session?.onFrame()
        }

        private fun createSession(exoPlayer: ExoPlayer): AssPlaybackSession? {
            if (!isEnabled()) return null
            val context = fragment.context ?: return null
            if (!libraryReady()) return null
            return AssPlaybackSession(context.applicationContext, exoPlayer, this).also { it.start() }
        }

        private fun currentExoPlayer(): ExoPlayer? {
            val player = fragment.player
            return Reflect.get(player, "exoPlayer") as? ExoPlayer
        }

        override fun rootView(): View? = fragment.view

        override fun subtitleOffsetMs(): Long = runCatching { fragment.player.getSubtitleOffset() }.getOrDefault(0L)

        override fun subtitleRequest(id: String): AssSubtitleRequest? {
            val subtitle = fragment.player.getCurrentPreferredSubtitle() ?: return null
            if (subtitle.getId() != id) return null
            return AssSubtitleRequest(subtitle.getFixedUrl(), subtitle.headers)
        }
    }
}
