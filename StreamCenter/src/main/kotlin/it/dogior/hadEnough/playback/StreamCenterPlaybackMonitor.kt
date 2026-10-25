package it.dogior.hadEnough.playback

import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.util.ListenerProxy
import it.dogior.hadEnough.util.Reflect
import it.dogior.hadEnough.util.StreamCenterLogger
import java.util.WeakHashMap

internal object PlaybackInsightSessionFactory : PlayerSessionFactory {
    private val states = WeakHashMap<AbstractPlayerFragment<*>, PlaybackSwitchState>()

    override fun create(fragment: AbstractPlayerFragment<*>, exoPlayer: ExoPlayer): PlayerSession? {
        if (!StreamCenterPlaybackInsights.enabled()) return null
        val state = states.getOrPut(fragment) { PlaybackSwitchState() }
        return PlaybackInsightSession(fragment, exoPlayer, state).also { it.start() }
    }

    override fun onFailure(error: Throwable) {
        StreamCenterLogger.logTabError(
            StreamCenterPlaybackInsights.TAB,
            "Misurazione disattivata per questo player",
            error,
        )
    }
}

internal class PlaybackSwitchState {
    var lastFailedUrl: String? = null
    var lastFailureAt = 0L

    fun markFailure(url: String) {
        lastFailedUrl = url
        lastFailureAt = SystemClock.uptimeMillis()
    }
}

private class PlaybackAttempt(val url: String, val origin: PlaybackOrigin, val automatic: Boolean) {
    var waitedMs = 0L
    var startupMs: Long? = null
    var stalls = 0
    var stallMs = 0L
    var stallStartedAt = 0L
    var seeking = false
    var height = 0
    var finished = false
}

private class PlaybackInsightSession(
    private val fragment: AbstractPlayerFragment<*>,
    private val exoPlayer: ExoPlayer,
    private val state: PlaybackSwitchState,
) : PlayerSession {
    private var attempt: PlaybackAttempt? = null
    private var lastFrameAt = SystemClock.uptimeMillis()
    private val listener: Player.Listener = ListenerProxy.create(
        Player.Listener::class.java,
        mapOf(
            "onRenderedFirstFrame" to { _ -> onFirstFrame() },
            "onPlaybackStateChanged" to { args -> onPlaybackState(args.firstOrNull() as? Int) },
            "onPositionDiscontinuity" to { args ->
                if (args.lastOrNull() as? Int == Player.DISCONTINUITY_REASON_SEEK) attempt?.seeking = true
            },
            "onVideoSizeChanged" to { args -> (args.firstOrNull() as? VideoSize)?.let(::onVideoSize) },
            "onPlayerError" to { args -> onError(args.firstOrNull() as? PlaybackException) },
        ),
        onError = { PlaybackInsightSessionFactory.onFailure(it) },
    )

    fun start() {
        exoPlayer.addListener(listener)
    }

    override fun onFrame() {
        val now = SystemClock.uptimeMillis()
        val delta = (now - lastFrameAt).coerceIn(0L, MAX_FRAME_GAP_MS)
        lastFrameAt = now
        val link = selectedLink()
        if (link?.url != attempt?.url) {
            finish()
            attempt = link?.let(::begin)
        }
        val current = attempt ?: return
        if (current.finished || current.startupMs != null) return
        if (exoPlayer.playWhenReady) current.waitedMs += delta
        if (current.automatic && current.waitedMs >= timeoutFor(current.origin)) onTimeout(current)
    }

    override fun release() {
        exoPlayer.removeListener(listener)
        finish()
        attempt = null
    }

    private fun begin(link: ExtractorLink): PlaybackAttempt? {
        val origin = StreamCenterPlaybackInsights.originOf(link.url) ?: return null
        return PlaybackAttempt(link.url, origin, isAutomatic(link))
    }

    private fun onFirstFrame() {
        val current = attempt ?: return
        if (current.startupMs == null) current.startupMs = current.waitedMs
    }

    private fun onPlaybackState(playbackState: Int?) {
        val current = attempt ?: return
        if (current.finished || current.startupMs == null) return
        when (playbackState) {
            Player.STATE_BUFFERING -> if (current.seeking) {
                current.seeking = false
            } else if (current.stallStartedAt == 0L) {
                current.stalls++
                current.stallStartedAt = SystemClock.uptimeMillis()
            }
            Player.STATE_READY -> closeStall(current)
        }
    }

    private fun onVideoSize(size: VideoSize) {
        val current = attempt ?: return
        if (size.height > current.height) current.height = size.height
    }

    private fun onError(error: PlaybackException?) {
        val current = attempt ?: return
        if (current.finished) return
        val dead = current.startupMs == null && error?.errorCode in DEAD_LINK_ERRORS
        finish(if (dead) PlaybackOutcome.DEAD else PlaybackOutcome.FAILED, error?.errorCodeName)
        state.markFailure(current.url)
    }

    private fun onTimeout(current: PlaybackAttempt) {
        finish(PlaybackOutcome.TIMEOUT)
        state.markFailure(current.url)
        if (!fragment.hasNextMirror()) return
        CommonActivity.showToast("${current.origin.source.name} non parte, provo la fonte successiva", Toast.LENGTH_SHORT)
        fragment.nextMirror()
    }

    private fun closeStall(current: PlaybackAttempt) {
        if (current.stallStartedAt == 0L) return
        current.stallMs += SystemClock.uptimeMillis() - current.stallStartedAt
        current.stallStartedAt = 0L
    }

    private fun finish(outcome: PlaybackOutcome? = null, reason: String? = null) {
        val current = attempt ?: return
        if (current.finished) return
        current.finished = true
        closeStall(current)
        val result = outcome ?: if (current.startupMs != null) PlaybackOutcome.STARTED else return
        val sample = PlaybackSample(
            atMillis = System.currentTimeMillis(),
            outcome = result.name,
            startupMs = current.startupMs,
            stalls = current.stalls,
            stallMs = current.stallMs,
            height = current.height.takeIf { it > 0 },
            declaredHeight = current.origin.declaredHeight,
        )
        StreamCenterPlaybackInsights.record(current.origin, sample)
        StreamCenterLogger.logTab(
            StreamCenterPlaybackInsights.TAB,
            "Esito riproduzione registrato",
            mapOf(
                "fonte" to current.origin.source.name,
                "host" to current.origin.host,
                "esito" to result.name,
                "motivo" to reason,
                "avvio_ms" to current.startupMs,
                "attesa_ms" to current.waitedMs,
                "blocchi" to current.stalls,
                "altezza_video" to sample.height,
                "scelta_automatica" to current.automatic,
            ),
            if (result == PlaybackOutcome.STARTED) StreamCenterLogger.Level.INFO else StreamCenterLogger.Level.WARNING,
        )
    }

    private fun selectedLink(): ExtractorLink? =
        (Reflect.get(fragment, "currentSelectedLink") as? Pair<*, *>)?.first as? ExtractorLink

    private fun isAutomatic(link: ExtractorLink): Boolean {
        val viewModel = Reflect.get(fragment, "viewModel") as? PlayerGeneratorViewModel ?: return false
        val profile = Reflect.get(fragment, "currentQualityProfile") as? Int ?: return false
        val sorted = runCatching { viewModel.state.sortLinks(profile).map { it.first?.url } }.getOrNull() ?: return false
        val index = sorted.indexOf(link.url)
        if (index == 0) return true
        val failedIndex = state.lastFailedUrl?.let(sorted::indexOf) ?: return false
        val recent = SystemClock.uptimeMillis() - state.lastFailureAt <= AUTO_SWITCH_WINDOW_MS
        return recent && failedIndex >= 0 && index == failedIndex + 1
    }

    private fun timeoutFor(origin: PlaybackOrigin): Long =
        if (origin.source.group == PlaybackSourceGroup.TORRENT) TORRENT_START_TIMEOUT_MS else START_TIMEOUT_MS

    private companion object {
        const val START_TIMEOUT_MS = 15_000L
        const val TORRENT_START_TIMEOUT_MS = 45_000L
        const val AUTO_SWITCH_WINDOW_MS = 10_000L
        const val MAX_FRAME_GAP_MS = 1_000L
        val DEAD_LINK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
    }
}
