package it.dogior.hadEnough.subtitles

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure

internal class AssMatroskaExtractor(
    private val delegate: Extractor,
    private val sink: AssEmbeddedSink,
) : Extractor {
    private var tracksPatched = false
    private var lastInput: ExtractorInput? = null
    private var capturingInput: ExtractorInput? = null

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) = delegate.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = delegate.read(capture(input), seekPosition)
        if (!tracksPatched && !sink.isReleased) {
            tracksPatched = runCatching { MatroskaAssTracks.patch(delegate, sink) }.getOrDefault(true)
        }
        return result
    }

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private fun capture(input: ExtractorInput): ExtractorInput {
        if (input !== lastInput) {
            lastInput = input
            capturingInput = AttachmentCaptureInput(input) { body ->
                if (!sink.isReleased) runCatching { sink.onAttachments(body) }
            }
        }
        return capturingInput ?: input
    }
}
