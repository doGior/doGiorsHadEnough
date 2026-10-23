package it.dogior.hadEnough.subtitles

import androidx.media3.extractor.ExtractorInput

internal class AttachmentCaptureInput(
    private val delegate: ExtractorInput,
    private val onAttachments: (ByteArray) -> Unit,
) : ExtractorInput by delegate {
    private val recent = ByteArray(RECENT_SIZE)
    private var recentSize = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also { if (it > 0) remember(buffer, offset, it) }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
        delegate.readFully(target, offset, length, allowEndOfInput).also { if (it) remember(target, offset, length) }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        delegate.readFully(target, offset, length)
        remember(target, offset, length)
    }

    override fun skip(length: Int): Int {
        recentSize = 0
        return delegate.skip(length)
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        if (!isAttachmentsBody(length)) {
            recentSize = 0
            return delegate.skipFully(length, allowEndOfInput)
        }
        recentSize = 0
        val body = ByteArray(length)
        if (!delegate.readFully(body, 0, length, allowEndOfInput)) return false
        onAttachments(body)
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    private fun remember(source: ByteArray, offset: Int, length: Int) {
        if (length >= RECENT_SIZE) {
            System.arraycopy(source, offset + length - RECENT_SIZE, recent, 0, RECENT_SIZE)
            recentSize = RECENT_SIZE
            return
        }
        val keep = minOf(recentSize, RECENT_SIZE - length)
        System.arraycopy(recent, recentSize - keep, recent, 0, keep)
        System.arraycopy(source, offset, recent, keep, length)
        recentSize = keep + length
    }

    private fun isAttachmentsBody(length: Int): Boolean {
        if (length <= 0 || length > MAX_ATTACHMENTS_BYTES) return false
        for (sizeLength in 1..8) {
            val sizeStart = recentSize - sizeLength
            val idStart = sizeStart - ID_LENGTH
            if (idStart < 0) return false
            if (MatroskaAttachments.vintLength(recent[sizeStart]) != sizeLength) continue
            if (MatroskaAttachments.readVint(recent, idStart, ID_LENGTH, keepMarker = true) != MatroskaAttachments.ID_ATTACHMENTS) continue
            return MatroskaAttachments.readVint(recent, sizeStart, sizeLength, keepMarker = false) == length.toLong()
        }
        return false
    }

    private companion object {
        const val RECENT_SIZE = 12
        const val ID_LENGTH = 4
        const val MAX_ATTACHMENTS_BYTES = 128 * 1024 * 1024
    }
}
