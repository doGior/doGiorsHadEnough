package it.dogior.hadEnough.subtitles

internal object AssEmbeddedCache {
    private const val MAX_MEDIA = 3

    class Chunk(val startMs: Long, val durationMs: Long, val data: ByteArray)

    private class Track(val header: ByteArray?) {
        val chunks = LinkedHashMap<String, Chunk>()
    }

    private class Media {
        val fonts = LinkedHashMap<String, ByteArray>()
        val tracks = LinkedHashMap<Int, Track>()
    }

    class Snapshot(val fonts: List<Pair<String, ByteArray>>, val tracks: List<Triple<Int, ByteArray?, List<Chunk>>>)

    private val media = object : LinkedHashMap<String, Media>(MAX_MEDIA, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Media>?): Boolean = size > MAX_MEDIA
    }

    @Synchronized
    fun addFonts(key: String, fonts: List<MatroskaAttachments.Font>) {
        val entry = media.getOrPut(key, ::Media)
        fonts.forEach { entry.fonts[it.name] = it.data }
    }

    @Synchronized
    fun addTrack(key: String, trackNumber: Int, header: ByteArray?) {
        val entry = media.getOrPut(key, ::Media)
        if (trackNumber !in entry.tracks) entry.tracks[trackNumber] = Track(header)
    }

    @Synchronized
    fun addChunk(key: String, trackNumber: Int, chunk: Chunk) {
        val track = media[key]?.tracks?.get(trackNumber) ?: return
        track.chunks[readOrder(chunk.data) ?: "${chunk.startMs}:${chunk.data.contentHashCode()}"] = chunk
    }

    @Synchronized
    fun snapshot(key: String): Snapshot? {
        val entry = media[key] ?: return null
        return Snapshot(
            fonts = entry.fonts.map { it.key to it.value },
            tracks = entry.tracks.map { (number, track) -> Triple(number, track.header, track.chunks.values.toList()) },
        )
    }

    private fun readOrder(data: ByteArray): String? {
        val comma = data.indexOf(','.code.toByte())
        if (comma <= 0) return null
        return String(data, 0, comma, Charsets.US_ASCII).trim().takeIf { value -> value.all(Char::isDigit) }
    }
}
