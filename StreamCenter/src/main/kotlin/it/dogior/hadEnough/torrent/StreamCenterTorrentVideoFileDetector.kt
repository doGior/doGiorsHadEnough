package it.dogior.hadEnough.torrent

import java.util.Locale

internal object StreamCenterTorrentVideoFileDetector {
    private val videoExtensions = setOf(
        "mkv", "mp4", "avi", "webm", "mov", "qt", "m4v",
        "mpeg", "mpg", "mpe", "m2v", "ts", "m2t", "tp", "m2ts", "mts",
        "wmv", "asf", "flv", "f4v", "ogm", "ogv", "vob",
    )
    private val ignoredVideoRegex = Regex(
        """(?i)(?:^|[^a-z0-9])(?:sample|trailer|preview|proof|featurette|interview|ncop|nced)(?:[^a-z0-9]|$)""",
    )

    fun isUsableVideoFile(path: String): Boolean {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').trim()
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in videoExtensions && !ignoredVideoRegex.containsMatchIn(fileName)
    }
}
