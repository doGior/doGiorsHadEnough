package it.dogior.hadEnough.remote

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.exoplayer.ExoPlayer
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.UIHelper
import it.dogior.hadEnough.playback.PlayerSession
import it.dogior.hadEnough.playback.PlayerSessionFactory
import it.dogior.hadEnough.util.Reflect
import it.dogior.hadEnough.util.StreamCenterDevice
import it.dogior.hadEnough.util.StreamCenterLogger
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

internal sealed interface RemoteCommand {
    data object Play : RemoteCommand
    data object Pause : RemoteCommand
    data object Toggle : RemoteCommand
    data object SeekForward : RemoteCommand
    data object SeekBack : RemoteCommand
    data class SeekTo(val positionMs: Long) : RemoteCommand
    data class Subtitle(val id: String?) : RemoteCommand
    data object VolumeUp : RemoteCommand
    data object VolumeDown : RemoteCommand

    companion object {
        fun parse(action: String, positionMs: Long?, subtitleId: String?): RemoteCommand? = when (action) {
            "play" -> Play
            "pause" -> Pause
            "toggle" -> Toggle
            "seekForward" -> SeekForward
            "seekBack" -> SeekBack
            "seekTo" -> positionMs?.takeIf { it >= 0 }?.let(::SeekTo)
            "subtitle" -> Subtitle(subtitleId)
            "volumeUp" -> VolumeUp
            "volumeDown" -> VolumeDown
            else -> null
        }

        fun actionOf(command: RemoteCommand): String = when (command) {
            Play -> "play"
            Pause -> "pause"
            Toggle -> "toggle"
            SeekForward -> "seekForward"
            SeekBack -> "seekBack"
            is SeekTo -> "seekTo"
            is Subtitle -> "subtitle"
            VolumeUp -> "volumeUp"
            VolumeDown -> "volumeDown"
        }
    }
}

internal object StreamCenterRemotePlayer : PlayerSessionFactory {
    private const val SNAPSHOT_INTERVAL_MS = 250L
    private const val VOLUME_STEP = 0.1f
    private val TOP_BAR_BUTTONS = listOf(
        "player_go_back", "player_restart", "player_go_forward", "download_header_toggle", "player_episodes_button",
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closeListeners = CopyOnWriteArraySet<() -> Unit>()
    private var appContext: Context? = null
    private var enabled: () -> Boolean = { false }
    private var onContinue: (RemoteHandoff) -> Unit = {}

    @Volatile
    private var active: RemotePlayerSession? = null

    @Volatile
    var state: RemotePlayerState? = null
        private set

    val isActive: Boolean get() = active != null

    fun configure(context: Context, enabled: () -> Boolean, onContinue: (RemoteHandoff) -> Unit) {
        appContext = context.applicationContext
        this.enabled = enabled
        this.onContinue = onContinue
    }

    fun addCloseListener(listener: () -> Unit) {
        closeListeners.add(listener)
    }

    override fun create(fragment: AbstractPlayerFragment<*>, exoPlayer: ExoPlayer): PlayerSession? {
        if (!enabled()) return null
        return RemotePlayerSession(fragment, exoPlayer).also { active = it }
    }

    override fun onFailure(error: Throwable) {
        StreamCenterLogger.warning("Telecomando: aggancio al player non riuscito", mapOf("errore" to error.toString()))
    }

    fun execute(command: RemoteCommand) {
        mainHandler.post {
            val context = appContext
            val isVolume = command == RemoteCommand.VolumeUp || command == RemoteCommand.VolumeDown
            if (isVolume && context != null && !StreamCenterDevice.isTvLike(context)) {
                adjustSystemVolume(context, raise = command == RemoteCommand.VolumeUp)
            } else {
                active?.execute(command)
            }
        }
    }

    fun handoff(): RemoteHandoff? = active?.handoff()

    fun pause() = execute(RemoteCommand.Pause)

    fun close() {
        mainHandler.post { active?.close() }
    }

    private fun adjustSystemVolume(context: Context, raise: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
    }

    private fun released(session: RemotePlayerSession) {
        if (active !== session) return
        active = null
        state = null
        closeListeners.forEach { runCatching { it() } }
    }

    private class RemotePlayerSession(
        private val fragment: AbstractPlayerFragment<*>,
        private val exoPlayer: ExoPlayer,
    ) : PlayerSession {
        private var lastSnapshotAt = 0L
        private var button: TextView? = null
        private var topHolder: View? = null
        private var buttonHost: FrameLayout? = null

        @Volatile
        private var cachedHandoff: RemoteHandoff? = null

        override fun onFrame() {
            val now = System.currentTimeMillis()
            if (now - lastSnapshotAt < SNAPSHOT_INTERVAL_MS) return
            lastSnapshotAt = now
            state = snapshot()
            cachedHandoff = buildHandoff()
            updateButton()
        }

        override fun release() {
            button?.let { (it.parent as? ViewGroup)?.removeView(it) }
            button = null
            released(this)
        }

        fun handoff(): RemoteHandoff? = cachedHandoff

        private fun adjustPlayerVolume(raise: Boolean) {
            val step = if (raise) VOLUME_STEP else -VOLUME_STEP
            exoPlayer.volume = ((exoPlayer.volume + step) * 10).roundToInt().div(10f).coerceIn(0f, 1f)
            StreamCenterRemoteNavigator.toast("Volume ${(exoPlayer.volume * 100).roundToInt()}%")
        }

        fun close() {
            fragment.player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)
            val activity = fragment.activity as? FragmentActivity ?: return
            runCatching { with(UIHelper) { activity.popCurrentPage("remote") } }.onFailure {
                StreamCenterLogger.warning("Telecomando: chiusura player non riuscita", mapOf("errore" to it.toString()))
            }
        }

        fun execute(command: RemoteCommand) {
            val player = fragment.player
            when (command) {
                RemoteCommand.Play -> player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.UI)
                RemoteCommand.Pause -> player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)
                RemoteCommand.Toggle -> player.handleEvent(CSPlayerEvent.PlayPauseToggle, PlayerEventSource.UI)
                RemoteCommand.SeekForward -> player.handleEvent(CSPlayerEvent.SeekForward, PlayerEventSource.UI)
                RemoteCommand.SeekBack -> player.handleEvent(CSPlayerEvent.SeekBack, PlayerEventSource.UI)
                is RemoteCommand.SeekTo -> player.seekTo(command.positionMs, PlayerEventSource.UI)
                is RemoteCommand.Subtitle -> selectSubtitle(command.id)
                RemoteCommand.VolumeUp -> adjustPlayerVolume(raise = true)
                RemoteCommand.VolumeDown -> adjustPlayerVolume(raise = false)
            }
        }

        private fun snapshot(): RemotePlayerState {
            val player = fragment.player
            val episode = currentEpisode()
            val page = loadResponse()
            val subtitles = availableSubtitles()
            return RemotePlayerState(
                title = page?.name ?: episode?.name ?: "In riproduzione",
                poster = page?.posterUrl ?: episode?.poster,
                season = episode?.season,
                episode = episode?.episode?.takeIf { page !is MovieLoadResponse },
                positionMs = player.getPosition() ?: 0L,
                durationMs = player.getDuration() ?: 0L,
                playing = player.getIsPlaying(),
                subtitles = subtitles.map { RemoteSubtitle(it.getId(), it.name) },
                selectedSubtitle = (Reflect.get(fragment, "currentSelectedSubtitles") as? SubtitleData)?.getId(),
            )
        }

        private fun buildHandoff(): RemoteHandoff? {
            val page = loadResponse() ?: return null
            val episode = currentEpisode()
            val player = fragment.player
            val link = (Reflect.get(fragment, "currentSelectedLink") as? Pair<*, *>)?.first as? ExtractorLink
            return RemoteHandoff(
                url = page.url,
                apiName = page.apiName,
                name = page.name,
                episodeId = episode?.id,
                season = episode?.season,
                episode = episode?.episode,
                positionMs = player.getPosition() ?: 0L,
                durationMs = player.getDuration() ?: 0L,
                linkName = link?.name,
            )
        }

        private fun viewModel(): PlayerGeneratorViewModel? =
            Reflect.get(fragment, "viewModel") as? PlayerGeneratorViewModel

        private fun loadResponse() = (viewModel()?.generator as? RepoLinkGenerator)?.page

        private fun currentEpisode(): ResultEpisode? {
            if (fragment !is GeneratorPlayer) return null
            return runCatching { currentMetaMethod?.invoke(null, fragment) as? ResultEpisode }.getOrNull()
        }

        private fun availableSubtitles(): List<SubtitleData> =
            viewModel()?.currentSubtitles?.value?.value?.sortedBy { it.name }.orEmpty()

        private fun selectSubtitle(id: String?) {
            if (fragment !is GeneratorPlayer) return
            val target = id?.let { wanted -> availableSubtitles().firstOrNull { it.getId() == wanted } ?: return }
            runCatching { setSubtitlesMethod?.invoke(fragment, target, true) }.onFailure {
                StreamCenterLogger.warning("Telecomando: cambio sottotitoli non riuscito", mapOf("errore" to it.toString()))
            }
        }

        private fun updateButton() {
            val root = fragment.view ?: return
            val top = topHolder ?: root.findViewByName("player_top_holder")?.also { topHolder = it }
            val host = buttonHost
                ?: (root.findViewByName("player_holder") as? FrameLayout ?: root as? FrameLayout)?.also { buttonHost = it }
                ?: return
            val visible = enabled() && cachedHandoff != null && top != null &&
                top.visibility == View.VISIBLE && top.alpha > 0.1f
            val current = button ?: if (visible) createButton(host).also {
                button = it
                linkFocus(root, it)
            } else return
            current.visibility = if (visible) View.VISIBLE else View.GONE
        }

        private fun linkFocus(root: View, button: TextView) {
            button.id = View.generateViewId()
            val topBar = TOP_BAR_BUTTONS.mapNotNull { root.findViewByName(it) }
            topBar.forEach { it.nextFocusDownId = button.id }
            (root.findViewByName("player_restart") ?: topBar.firstOrNull())?.let { button.nextFocusUpId = it.id }
            val skipChapter = root.findViewByName("skip_chapter_button")
            val pausePlay = root.findViewByName("player_pause_play")
            if (skipChapter != null) {
                skipChapter.nextFocusUpId = button.id
                button.nextFocusDownId = skipChapter.id
            } else if (pausePlay != null) {
                pausePlay.nextFocusUpId = button.id
                button.nextFocusDownId = pausePlay.id
            }
            button.nextFocusLeftId = button.id
            button.nextFocusRightId = button.id
        }

        private fun createButton(host: FrameLayout): TextView {
            val context = host.context
            val density = context.resources.displayMetrics.density
            val tv = StreamCenterDevice.isTvLike(context)
            return TextView(context).apply {
                text = if (tv) "📱 Continua sul telefono" else "📺 Continua sulla TV"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                isFocusable = true
                val padding = (10 * density).toInt()
                setPadding(padding * 2, padding, padding * 2, padding)
                val normal = GradientDrawable().apply {
                    cornerRadius = 22 * density
                    setColor(Color.parseColor("#B3000000"))
                }
                val focused = GradientDrawable().apply {
                    cornerRadius = 22 * density
                    setColor(Color.parseColor("#E6000000"))
                    setStroke((2 * density).toInt(), Color.WHITE)
                }
                background = normal
                setOnFocusChangeListener { _, hasFocus -> background = if (hasFocus) focused else normal }
                setOnClickListener { cachedHandoff?.let(onContinue) }
                host.addView(this, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ).apply { marginEnd = (24 * density).toInt() })
            }
        }
    }

    private fun View.findViewByName(name: String): View? {
        val id = resources.getIdentifier(name, "id", context.packageName).takeIf { it != 0 } ?: return null
        return findViewById(id)
    }

    private val currentMetaMethod: Method? by lazy {
        runCatching {
            GeneratorPlayer::class.java.getMethod("access\$getCurrentMeta", GeneratorPlayer::class.java)
        }.getOrNull()
    }

    private val setSubtitlesMethod: Method? by lazy {
        runCatching {
            GeneratorPlayer::class.java.getDeclaredMethod(
                "setSubtitles",
                SubtitleData::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }
}
