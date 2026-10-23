package it.dogior.hadEnough.subtitles

internal object LibassNative {
    @JvmStatic external fun libraryInit(): Long
    @JvmStatic external fun libraryAddFont(library: Long, name: String, data: ByteArray)
    @JvmStatic external fun libraryDone(library: Long)
    @JvmStatic external fun trackNew(library: Long): Long
    @JvmStatic external fun trackReadHeader(track: Long, data: ByteArray)
    @JvmStatic external fun trackReadChunk(
        track: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
        startMs: Long,
        durationMs: Long,
    )
    @JvmStatic external fun trackFree(track: Long)
    @JvmStatic external fun rendererInit(library: Long): Long
    @JvmStatic external fun rendererSetFrameSize(renderer: Long, width: Int, height: Int)
    @JvmStatic external fun rendererSetStorageSize(renderer: Long, width: Int, height: Int)
    @JvmStatic external fun rendererRender(renderer: Long, track: Long, timeMs: Long): AssFrame?
    @JvmStatic external fun rendererDone(renderer: Long)
}
