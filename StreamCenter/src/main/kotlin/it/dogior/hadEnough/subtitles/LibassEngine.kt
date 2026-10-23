package it.dogior.hadEnough.subtitles

internal class LibassEngine {
    private var ass = LibassNative.libraryInit()
    private var render = 0L
    private var renderFontsVersion = -1
    private var fontsVersion = 0
    private val fontNames = HashSet<String>()
    private val tracks = ArrayList<Long>()
    private var frameWidth = 0
    private var frameHeight = 0
    private var storageWidth = 0
    private var storageHeight = 0

    fun createTrack(header: ByteArray?): Long {
        if (ass == 0L) return 0L
        val track = LibassNative.trackNew(ass)
        if (track == 0L) return 0L
        tracks += track
        if (header != null && header.isNotEmpty()) LibassNative.trackReadHeader(track, header)
        return track
    }

    fun readChunk(track: Long, startMs: Long, durationMs: Long, data: ByteArray, offset: Int, length: Int) {
        if (ass == 0L || track !in tracks) return
        LibassNative.trackReadChunk(track, data, offset, length, startMs, durationMs)
    }

    fun addFont(name: String, data: ByteArray) {
        if (ass == 0L || !fontNames.add(name)) return
        LibassNative.libraryAddFont(ass, name, data)
        fontsVersion++
    }

    fun renderFrame(
        track: Long,
        timeMs: Long,
        width: Int,
        height: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): AssFrame? {
        if (ass == 0L || track !in tracks) return null
        if (render == 0L || renderFontsVersion != fontsVersion) {
            if (render != 0L) LibassNative.rendererDone(render)
            render = LibassNative.rendererInit(ass)
            renderFontsVersion = fontsVersion
            frameWidth = 0
            storageWidth = 0
            if (render == 0L) return null
        }
        if (width != frameWidth || height != frameHeight) {
            LibassNative.rendererSetFrameSize(render, width, height)
            frameWidth = width
            frameHeight = height
        }
        if (videoWidth > 0 && videoHeight > 0 && (videoWidth != storageWidth || videoHeight != storageHeight)) {
            LibassNative.rendererSetStorageSize(render, videoWidth, videoHeight)
            storageWidth = videoWidth
            storageHeight = videoHeight
        }
        return LibassNative.rendererRender(render, track, timeMs)
    }

    fun release() {
        if (ass == 0L) return
        if (render != 0L) LibassNative.rendererDone(render)
        render = 0L
        tracks.forEach(LibassNative::trackFree)
        tracks.clear()
        LibassNative.libraryDone(ass)
        ass = 0L
    }
}
