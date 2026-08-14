package it.dogior.hadEnough.torrent

internal enum class StreamCenterTorrentVideoCodec(
    val preferenceValue: String,
    val displayName: String,
    private val patterns: List<Regex>,
) {
    AVC(
        preferenceValue = "avc",
        displayName = "H.264 / AVC",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:x264|h\.?264|avc1?|mpeg-?4\s*avc)(?![a-z0-9])""")),
    ),
    HEVC(
        preferenceValue = "hevc",
        displayName = "H.265 / HEVC",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:x265|h\.?265|hevc)(?![a-z0-9])""")),
    ),
    AV1(
        preferenceValue = "av1",
        displayName = "AV1",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])av1(?![a-z0-9])""")),
    ),
    VP9(
        preferenceValue = "vp9",
        displayName = "VP9",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])vp9(?![a-z0-9])""")),
    ),
    VP8(
        preferenceValue = "vp8",
        displayName = "VP8",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:vp8|vp08|v_vp8)(?![a-z0-9])""")),
    ),
    THEORA(
        preferenceValue = "theora",
        displayName = "Theora",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:theora|thra)(?![a-z0-9])""")),
    ),
    XVID(
        preferenceValue = "xvid",
        displayName = "MPEG-4 ASP",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:xvid|divx|dx50|div3)(?![a-z0-9])""")),
    ),
    MPEG2(
        preferenceValue = "mpeg2",
        displayName = "MPEG-2",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:mpeg-?2|h\.?262)(?![a-z0-9])""")),
    ),
    MPEG1(
        preferenceValue = "mpeg1",
        displayName = "MPEG-1 Video",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:mpeg-?1(?:\s*(?:video|visual))?|mpeg1video|mpg1)(?![a-z0-9])""")),
    ),
    VC1(
        preferenceValue = "vc1",
        displayName = "VC-1",
        patterns = listOf(Regex("""(?i)(?<![a-z0-9])(?:vc-?1|wvc1)(?![a-z0-9])""")),
    ),
    ;

    fun matches(text: String): Boolean = patterns.any { pattern -> pattern.containsMatchIn(text) }

    companion object {
        fun fromPreference(value: String?): StreamCenterTorrentVideoCodec? =
            entries.firstOrNull { codec -> codec.preferenceValue == value }

        fun detectAll(text: String): Set<StreamCenterTorrentVideoCodec> =
            entries.filterTo(linkedSetOf()) { codec -> codec.matches(text) }
    }
}

internal data class StreamCenterTorrentVideoCodecDetection(
    val codecs: Set<StreamCenterTorrentVideoCodec> = emptySet(),
)

internal object StreamCenterTorrentVideoCodecDetector {
    fun detect(candidate: StreamCenterTorrentCandidate): StreamCenterTorrentVideoCodecDetection =
        StreamCenterTorrentVideoCodecDetection(StreamCenterTorrentVideoCodec.detectAll(codecText(candidate)))

    fun accepts(
        candidate: StreamCenterTorrentCandidate,
        blockedCodecs: Set<StreamCenterTorrentVideoCodec>,
    ): Boolean {
        if (blockedCodecs.isEmpty()) return true
        val detected = detect(candidate).codecs
        if (detected.isEmpty()) return true
        return detected.any { codec -> codec !in blockedCodecs }
    }

    private fun codecText(candidate: StreamCenterTorrentCandidate): String = buildString {
        append(candidate.title)
        candidate.selectedFileName?.let { name ->
            append(' ')
            append(name)
        }
        candidate.availableFiles?.forEach { file ->
            append(' ')
            append(file.path)
        }
    }
}
