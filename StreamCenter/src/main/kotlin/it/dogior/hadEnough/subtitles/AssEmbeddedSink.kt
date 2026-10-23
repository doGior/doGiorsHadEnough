package it.dogior.hadEnough.subtitles

internal interface AssEmbeddedSink {
    val isReleased: Boolean
    fun onEmbeddedTrack(trackNumber: Int, header: ByteArray?)
    fun onEmbeddedSample(trackNumber: Int, timeUs: Long, sample: ByteArray)
    fun onAttachments(body: ByteArray)
}
