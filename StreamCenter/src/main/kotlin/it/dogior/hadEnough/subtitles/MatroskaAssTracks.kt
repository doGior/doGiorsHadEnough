package it.dogior.hadEnough.subtitles

import it.dogior.hadEnough.util.Reflect
import android.util.SparseArray
import androidx.media3.extractor.TrackOutput

internal object MatroskaAssTracks {
    private val ASS_CODECS = setOf("S_TEXT/ASS", "S_TEXT/SSA")

    fun isMatroska(extractor: Any): Boolean = extractor.javaClass.simpleName.contains("Matroska")

    fun patch(extractor: Any, sink: AssEmbeddedSink): Boolean {
        val tracks = Reflect.get(extractor, "tracks") as? SparseArray<*> ?: return false
        if (tracks.size() == 0) return false
        var complete = true
        for (index in 0 until tracks.size()) {
            val track = tracks.valueAt(index) ?: continue
            val output = Reflect.get(track, "output") as? TrackOutput
            if (output == null) {
                complete = false
                continue
            }
            if (output is AssTeeTrackOutput || Reflect.get(track, "codecId") !in ASS_CODECS) continue
            val number = Reflect.get(track, "number") as? Int ?: continue
            val tee = AssTeeTrackOutput(output) { timeUs, sample -> sink.onEmbeddedSample(number, timeUs, sample) }
            if (Reflect.set(track, "output", tee)) {
                sink.onEmbeddedTrack(number, Reflect.get(track, "codecPrivate") as? ByteArray)
            }
        }
        return complete
    }
}
