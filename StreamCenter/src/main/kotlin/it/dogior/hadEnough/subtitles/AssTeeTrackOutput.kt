package it.dogior.hadEnough.subtitles

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

internal class AssTeeTrackOutput(
    private val delegate: TrackOutput,
    private val onSample: (timeUs: Long, sample: ByteArray) -> Unit,
) : TrackOutput {
    private var pending = ByteArray(INITIAL_CAPACITY)
    private var pendingSize = 0

    override fun format(format: Format) = delegate.format(format)

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val buffer = ByteArray(length)
        val read = input.read(buffer, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) append(buffer, 0, read)
        delegate.sampleData(ParsableByteArray(buffer, read), read, sampleDataPart)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) append(data.data, data.position, length)
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        val end = pendingSize - offset
        val start = end - size
        if (size > 0 && start >= 0) {
            runCatching { onSample(timeUs, pending.copyOfRange(start, end)) }
        }
        if (offset in 1..pendingSize) System.arraycopy(pending, pendingSize - offset, pending, 0, offset)
        pendingSize = offset.coerceIn(0, pendingSize)
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun append(source: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (pendingSize + length > pending.size) {
            pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + length))
        }
        System.arraycopy(source, offset, pending, pendingSize, length)
        pendingSize += length
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
    }
}
