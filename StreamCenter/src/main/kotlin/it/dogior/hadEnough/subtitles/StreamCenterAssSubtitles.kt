package it.dogior.hadEnough.subtitles

import android.content.Context
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import it.dogior.hadEnough.playback.PlayerSession
import it.dogior.hadEnough.playback.PlayerSessionFactory
import it.dogior.hadEnough.playback.StreamCenterPlayerHooks

object StreamCenterAssSubtitles {
    private var isEnabled: () -> Boolean = { false }
    private var appContext: Context? = null
    private var pluginFile: String? = null

    fun install(context: Context, pluginFile: String?, enabled: () -> Boolean) {
        isEnabled = enabled
        this.pluginFile = pluginFile
        val application = context.applicationContext
        appContext = application
        if (enabled()) LibassLibrary.preload(application, pluginFile)
        StreamCenterPlayerHooks.register(context, "libass", sessionFactory)
    }

    private fun libraryReady(): Boolean {
        val context = appContext ?: return false
        return LibassLibrary.isReady(context, pluginFile)
    }

    private val sessionFactory = object : PlayerSessionFactory {
        override fun create(fragment: AbstractPlayerFragment<*>, exoPlayer: ExoPlayer): PlayerSession? {
            if (!isEnabled()) return null
            val context = fragment.context ?: return null
            if (!libraryReady()) return null
            return AssPlaybackSession(context.applicationContext, exoPlayer, FragmentAssHost(fragment)).also { it.start() }
        }

        override fun onFailure(error: Throwable) = AssLog.error("Sottotitoli libass disattivati per questo player", error)
    }

    private class FragmentAssHost(private val fragment: AbstractPlayerFragment<*>) : AssPlayerHost {
        override fun rootView(): View? = fragment.view

        override fun subtitleOffsetMs(): Long = runCatching { fragment.player.getSubtitleOffset() }.getOrDefault(0L)

        override fun subtitleRequest(id: String): AssSubtitleRequest? {
            val subtitle = fragment.player.getCurrentPreferredSubtitle() ?: return null
            if (subtitle.getId() != id) return null
            return AssSubtitleRequest(subtitle.getFixedUrl(), subtitle.headers)
        }
    }
}
