package it.dogior.hadEnough.subtitles

internal object MatroskaAttachments {
    const val ID_ATTACHMENTS = 0x1941A469L
    private const val ID_ATTACHED_FILE = 0x61A7L
    private const val ID_FILE_NAME = 0x466EL
    private const val ID_FILE_MIME_TYPE = 0x4660L
    private const val ID_FILE_DATA = 0x465CL
    private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".ttc", ".otc")

    class Font(val name: String, val data: ByteArray)

    fun fonts(data: ByteArray): List<Font> {
        val fonts = ArrayList<Font>()
        forEachElement(data, 0, data.size) { id, start, end ->
            if (id == ID_ATTACHED_FILE) attachedFont(data, start, end)?.let(fonts::add)
        }
        return fonts
    }

    private fun attachedFont(data: ByteArray, start: Int, end: Int): Font? {
        var name: String? = null
        var mime: String? = null
        var fileStart = -1
        var fileEnd = -1
        forEachElement(data, start, end) { id, childStart, childEnd ->
            when (id) {
                ID_FILE_NAME -> name = String(data, childStart, childEnd - childStart, Charsets.UTF_8)
                ID_FILE_MIME_TYPE -> mime = String(data, childStart, childEnd - childStart, Charsets.US_ASCII)
                ID_FILE_DATA -> {
                    fileStart = childStart
                    fileEnd = childEnd
                }
            }
        }
        val fileName = name?.trimEnd('\u0000') ?: return null
        if (fileStart < 0 || !isFont(fileName, mime)) return null
        return Font(fileName, data.copyOfRange(fileStart, fileEnd))
    }

    private fun isFont(name: String, mime: String?): Boolean {
        val type = mime.orEmpty().lowercase()
        return "font" in type || "truetype" in type || "opentype" in type ||
            FONT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    }

    private inline fun forEachElement(data: ByteArray, start: Int, end: Int, block: (Long, Int, Int) -> Unit) {
        var position = start
        while (position < end) {
            val idLength = vintLength(data[position])
            if (idLength == 0 || idLength > 4 || position + idLength > end) return
            val id = readVint(data, position, idLength, keepMarker = true)
            position += idLength
            if (position >= end) return
            val sizeLength = vintLength(data[position])
            if (sizeLength == 0 || position + sizeLength > end) return
            val size = readVint(data, position, sizeLength, keepMarker = false)
            position += sizeLength
            val elementEnd = if (size < 0 || position + size > end) end else (position + size).toInt()
            block(id, position, elementEnd)
            position = elementEnd
        }
    }

    fun vintLength(first: Byte): Int {
        val value = first.toInt() and 0xFF
        for (length in 1..8) {
            if (value and (0x80 shr (length - 1)) != 0) return length
        }
        return 0
    }

    fun readVint(data: ByteArray, offset: Int, length: Int, keepMarker: Boolean): Long {
        var value = (data[offset].toInt() and 0xFF).toLong()
        if (!keepMarker) value = value and (0xFF shr length).toLong()
        var allOnes = value == (0xFF shr length).toLong()
        for (index in 1 until length) {
            val next = data[offset + index].toInt() and 0xFF
            allOnes = allOnes && next == 0xFF
            value = (value shl 8) or next.toLong()
        }
        return if (!keepMarker && allOnes) -1L else value
    }
}
