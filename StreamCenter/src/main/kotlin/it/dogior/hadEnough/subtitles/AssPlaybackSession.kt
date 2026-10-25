package it.dogior.hadEnough.subtitles

import it.dogior.hadEnough.playback.PlayerSession
import it.dogior.hadEnough.util.ListenerProxy
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

internal class AssPlaybackSession(
    private val context: Context,
    val exoPlayer: ExoPlayer,
    private val host: AssPlayerHost,
) : AssEmbeddedSink, PlayerSession {
    private val engine = LibassEngine()
    private val playerListener: Player.Listener = ListenerProxy.create(
        Player.Listener::class.java,
        mapOf(
            "onTracksChanged" to { _ -> updateSelection() },
            "onTimelineChanged" to { _ -> extendPatching(REFRESH_PATCH_WINDOW_MS) },
            "onPlaybackStateChanged" to { args -> onPlaybackStateChanged(args.firstOrNull() as? Int) },
            "onMediaItemTransition" to { _ -> onMediaItemTransition() },
        ),
        onError = { AssLog.error("Evento del player non gestito", it) },
    )
    private val embeddedTracks = ConcurrentHashMap<String, Long>()
    private val externalTracks = ConcurrentHashMap<String, Long>()
    private val externalRequests = HashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("StreamCenterLibass").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val libassWork = ConcurrentLinkedQueue<() -> Unit>()
    private val drainScheduled = AtomicBoolean(false)
    private val drainLibass = Runnable {
        drainScheduled.set(false)
        drainLibassWork()
    }
    private val hiddenSubtitleViews = ArrayList<WeakReference<View>>()
    private var overlay: AssOverlayView? = null
    private var overlayPlayerView: PlayerView? = null
    private var activeTrack = 0L
    private var renderPending = false
    private var dirty = true

    @Volatile
    private var contentChanged = false
    private var forceShow = true
    private var lastTimeMs = Long.MIN_VALUE
    private var lastWidth = 0
    private var lastHeight = 0
    private var patchUntil = 0L
    private var nextPatchAt = 0L
    private var lateHookReported = false

    @Volatile
    private var mediaKey: String? = null

    @Volatile
    private var restoredFromCache = false
    private var shownImages: Array<AssImage>? = null
    private var shownWidth = 0
    private var shownHeight = 0

    @Volatile
    override var isReleased = false
        private set

    fun start() {
        refreshMediaKey()
        exoPlayer.addListener(playerListener)
        extendPatching(INITIAL_PATCH_WINDOW_MS)
        patchMediaGraph()
        updateSelection()
    }

    override fun onFrame() {
        if (isReleased) return
        val now = SystemClock.uptimeMillis()
        if (now < patchUntil && now >= nextPatchAt) patchMediaGraph()
        if (activeTrack == 0L) return
        if (contentChanged) {
            contentChanged = false
            dirty = true
        }
        val view = attachOverlay() ?: return
        if (view.width <= 0 || view.height <= 0 || renderPending) return
        val timeMs = exoPlayer.currentPosition + host.subtitleOffsetMs()
        if (!dirty && timeMs == lastTimeMs && view.width == lastWidth && view.height == lastHeight) return
        requestRender(view, activeTrack, timeMs)
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer.removeListener(playerListener)
        scope.cancel()
        restoreCloudStreamSubtitles()
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        overlayPlayerView = null
        libassWork.clear()
        renderHandler.post {
            runCatching { engine.release() }
            renderThread.quitSafely()
        }
    }

    private fun onPlaybackStateChanged(playbackState: Int?) {
        if (playbackState == Player.STATE_BUFFERING) extendPatching(REFRESH_PATCH_WINDOW_MS)
    }

    private fun onMediaItemTransition() {
        embeddedTracks.clear()
        refreshMediaKey()
        extendPatching(REFRESH_PATCH_WINDOW_MS)
        updateSelection()
    }

    override fun onEmbeddedTrack(trackNumber: Int, header: ByteArray?) {
        mediaKey?.let { AssEmbeddedCache.addTrack(it, trackNumber, header) }
        val key = trackNumber.toString()
        onLibass {
            if (embeddedTracks.containsKey(key)) return@onLibass
            val track = engine.createTrack(header)
            if (track == 0L) return@onLibass
            embeddedTracks[key] = track
            AssLog.info("Traccia ASS integrata agganciata", mapOf("traccia" to trackNumber))
            mainHandler.post { updateSelection() }
        }
    }

    override fun onEmbeddedSample(trackNumber: Int, timeUs: Long, sample: ByteArray) {
        val dialogue = AssDialogue.parse(sample) ?: return
        val chunk = AssEmbeddedCache.Chunk(timeUs / 1000L, dialogue.durationMs, sample.copyOfRange(dialogue.offset, sample.size))
        mediaKey?.let { AssEmbeddedCache.addChunk(it, trackNumber, chunk) }
        val key = trackNumber.toString()
        onLibass {
            val track = embeddedTracks[key] ?: return@onLibass
            engine.readChunk(track, chunk.startMs, chunk.durationMs, chunk.data, 0, chunk.data.size)
            contentChanged = true
        }
    }

    override fun onAttachments(body: ByteArray) {
        val fonts = MatroskaAttachments.fonts(body)
        if (fonts.isEmpty()) return
        mediaKey?.let { AssEmbeddedCache.addFonts(it, fonts) }
        AssLog.info("Font allegati caricati", mapOf("font" to fonts.size))
        onLibass {
            fonts.forEach { engine.addFont(it.name, it.data) }
            contentChanged = true
        }
    }

    private fun onLibass(work: () -> Unit) {
        if (isReleased) return
        libassWork.add(work)
        if (drainScheduled.compareAndSet(false, true)) renderHandler.post(drainLibass)
    }

    private fun drainLibassWork() {
        while (true) {
            val work = libassWork.poll() ?: return
            runCatching(work).onFailure { AssLog.error("Operazione libass non riuscita", it) }
        }
    }

    private fun refreshMediaKey() {
        val key = runCatching { exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() }.getOrNull()
        mediaKey = key
        val cached = key?.let(AssEmbeddedCache::snapshot) ?: return
        restoredFromCache = cached.tracks.isNotEmpty()
        onLibass {
            cached.fonts.forEach { (name, data) -> engine.addFont(name, data) }
            cached.tracks.forEach { (number, header, chunks) ->
                val trackKey = number.toString()
                if (embeddedTracks.containsKey(trackKey)) return@forEach
                val track = engine.createTrack(header)
                if (track == 0L) return@forEach
                chunks.forEach { engine.readChunk(track, it.startMs, it.durationMs, it.data, 0, it.data.size) }
                embeddedTracks[trackKey] = track
            }
            AssLog.info(
                "Sottotitoli integrati ripristinati dalla cache",
                mapOf("tracce" to cached.tracks.size, "font" to cached.fonts.size, "righe" to cached.tracks.sumOf { it.third.size }),
            )
            contentChanged = true
            mainHandler.post { updateSelection() }
        }
    }

    private fun extendPatching(windowMs: Long) {
        patchUntil = maxOf(patchUntil, SystemClock.uptimeMillis() + windowMs)
    }

    private fun patchMediaGraph() {
        nextPatchAt = SystemClock.uptimeMillis() + PATCH_INTERVAL_MS
        val result = runCatching { Media3AssGraph.patch(exoPlayer, this) }
            .onFailure { AssLog.error("Aggancio al player non riuscito", it) }
            .getOrNull() ?: return
        if (result.lateExtractors > 0 && !lateHookReported) {
            lateHookReported = true
            if (restoredFromCache) {
                AssLog.info("Aggancio MKV in ritardo compensato dalla cache")
            } else {
                AssLog.warning("Aggancio MKV in ritardo: i font allegati potrebbero mancare")
            }
        }
    }

    private fun updateSelection() {
        if (isReleased) return
        val id = selectedTextFormat()?.id?.let(::stripMergePrefix)
        val track = id?.let { embeddedTracks[it] ?: externalTracks[externalKey(it)] } ?: 0L
        if (track == 0L && id != null) requestExternal(id)
        setActiveTrack(track)
    }

    private fun selectedTextFormat(): Format? {
        exoPlayer.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT || !group.isSelected) return@forEach
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) return group.getTrackFormat(index)
            }
        }
        return null
    }

    private fun requestExternal(id: String) {
        val key = externalKey(id)
        if (!looksLikeLocation(key) || !externalRequests.add(key)) return
        val request = runCatching { host.subtitleRequest(id) }.getOrNull() ?: AssSubtitleRequest(key, emptyMap())
        scope.launch {
            val data = runCatching { AssExternalLoader.load(context, request.url, request.headers) }
                .onFailure { AssLog.error("Download sottotitolo ASS non riuscito", it) }
                .getOrNull() ?: return@launch
            onLibass {
                val track = engine.createTrack(data)
                if (track == 0L) return@onLibass
                externalTracks[key] = track
                AssLog.info("Sottotitolo ASS esterno caricato", mapOf("byte" to data.size))
                mainHandler.post { updateSelection() }
            }
        }
    }

    private fun setActiveTrack(track: Long) {
        if (track == activeTrack) return
        activeTrack = track
        dirty = true
        forceShow = true
        shownImages = null
        if (track == 0L) {
            overlay?.clear()
            overlay?.visibility = View.GONE
            restoreCloudStreamSubtitles()
        } else {
            overlay?.visibility = View.VISIBLE
            hideCloudStreamSubtitles()
        }
    }

    private fun requestRender(view: AssOverlayView, track: Long, timeMs: Long) {
        renderPending = true
        dirty = false
        lastTimeMs = timeMs
        lastWidth = view.width
        lastHeight = view.height
        val show = forceShow
        forceShow = false
        val scale = renderScale(view.width, view.height)
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        val video = exoPlayer.videoSize
        renderHandler.post {
            drainLibassWork()
            val frame = if (isReleased) null else runCatching {
                engine.renderFrame(track, timeMs, width, height, video.width, video.height)
            }.onFailure { AssLog.error("Render libass non riuscito", it) }.getOrNull()
            mainHandler.post { onRendered(view, track, frame, width, height, show) }
        }
    }

    private fun onRendered(view: AssOverlayView, track: Long, frame: AssFrame?, width: Int, height: Int, show: Boolean) {
        renderPending = false
        if (isReleased || track != activeTrack) return
        if (frame != null && frame.changed) {
            shownImages = frame.images?.takeIf { it.isNotEmpty() }
            shownWidth = width
            shownHeight = height
        } else if (frame == null) {
            shownImages = null
        } else if (!show) {
            return
        }
        val images = shownImages
        if (images == null) view.clear() else view.show(images, shownWidth, shownHeight)
    }

    private fun attachOverlay(): AssOverlayView? {
        val attached = overlay
        val attachedPlayer = overlayPlayerView
        if (attached != null && attachedPlayer != null && attached.isAttachedToWindow && attachedPlayer.isAttachedToWindow) {
            attached.mirrorTransform(attachedPlayer.videoSurfaceView)
            return attached
        }
        val root = host.rootView() ?: return null
        val playerView = root.findFirstView(PlayerView::class.java) ?: return null
        val frame = playerView.findFirstView(AspectRatioFrameLayout::class.java) ?: return null
        val current = overlay
        if (current != null && current.parent === frame) {
            overlayPlayerView = playerView
            current.mirrorTransform(playerView.videoSurfaceView)
            return current
        }
        current?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val created = AssOverlayView(frame.context)
        frame.addView(created, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = created
        overlayPlayerView = playerView
        dirty = true
        forceShow = true
        hideCloudStreamSubtitles()
        return created
    }

    private fun hideCloudStreamSubtitles() {
        val root = host.rootView() ?: return
        root.findAllViews(SubtitleView::class.java).forEach { view ->
            if (hiddenSubtitleViews.none { it.get() === view }) hiddenSubtitleViews += WeakReference(view)
            view.alpha = 0f
        }
    }

    private fun restoreCloudStreamSubtitles() {
        hiddenSubtitleViews.forEach { it.get()?.alpha = 1f }
        hiddenSubtitleViews.clear()
    }

    private fun renderScale(width: Int, height: Int): Float {
        val pixels = width.toFloat() * height
        return if (pixels <= MAX_RENDER_PIXELS) 1f else sqrt(MAX_RENDER_PIXELS / pixels)
    }

    private fun stripMergePrefix(id: String): String {
        var result = id
        while (true) {
            val match = MERGE_PREFIX.find(result) ?: return result
            result = result.substring(match.range.last + 1)
        }
    }

    private fun externalKey(id: String): String = id.substringBefore('|')

    private fun looksLikeLocation(value: String): Boolean =
        "://" in value || value.startsWith("/") || value.startsWith("content:") || value.startsWith("file:")

    private companion object {
        const val INITIAL_PATCH_WINDOW_MS = 20_000L
        const val REFRESH_PATCH_WINDOW_MS = 5_000L
        const val PATCH_INTERVAL_MS = 200L
        const val MAX_RENDER_PIXELS = 1920f * 1080f
        val MERGE_PREFIX = Regex("""^\d+:""")
    }
}
