package it.dogior.hadEnough.torrent

import java.text.Normalizer

internal enum class StreamCenterTorrentFileSelectionFailure {
    FILES_MISSING,
    VIDEO_FILES_MISSING,
    EPISODE_FILE_MISSING,
    EPISODE_FILE_AMBIGUOUS,
}

internal data class StreamCenterTorrentFileSelection(
    val file: StreamCenterTorrentFile? = null,
    val failure: StreamCenterTorrentFileSelectionFailure? = null,
)

internal object StreamCenterTorrentFileSelector {
    fun select(
        files: List<StreamCenterTorrentFile>,
        context: StreamCenterTorrentPlaybackContext,
    ): StreamCenterTorrentFileSelection {
        if (context.episode == null) return StreamCenterTorrentFileSelection()
        if (files.isEmpty()) {
            return StreamCenterTorrentFileSelection(
                failure = StreamCenterTorrentFileSelectionFailure.FILES_MISSING,
            )
        }
        val videoFiles = files.filter { file ->
            StreamCenterTorrentVideoFileDetector.isUsableVideoFile(file.path)
        }
        if (videoFiles.isEmpty()) {
            return StreamCenterTorrentFileSelection(
                failure = StreamCenterTorrentFileSelectionFailure.VIDEO_FILES_MISSING,
            )
        }

        val scored = videoFiles.mapNotNull { file ->
            episodeScore(file, context)
                .takeIf { score -> score > 0 }
                ?.let { score -> file to score }
        }
        val highestScore = scored.maxOfOrNull(Pair<StreamCenterTorrentFile, Int>::second)
        val matchingFiles = highestScore
            ?.let { score -> scored.filter { it.second == score }.map(Pair<StreamCenterTorrentFile, Int>::first) }
            .orEmpty()
        val selected = when {
            matchingFiles.size == 1 -> matchingFiles.single()
            matchingFiles.size > 1 && (highestScore ?: 0) >= EXPLICIT_EPISODE_SCORE ->
                matchingFiles.maxWithOrNull(
                    compareBy<StreamCenterTorrentFile> { file ->
                        StreamCenterTorrentMetadata.resolution(file.fileName()) ?: 0
                    }.thenBy { file -> file.sizeBytes ?: 0L }
                        .thenByDescending(StreamCenterTorrentFile::index),
                )
            else -> null
        }
        val failure = when {
            selected != null -> null
            matchingFiles.size > 1 -> StreamCenterTorrentFileSelectionFailure.EPISODE_FILE_AMBIGUOUS
            else -> StreamCenterTorrentFileSelectionFailure.EPISODE_FILE_MISSING
        }
        return StreamCenterTorrentFileSelection(
            file = selected,
            failure = failure,
        )
    }

    private fun episodeScore(
        file: StreamCenterTorrentFile,
        context: StreamCenterTorrentPlaybackContext,
    ): Int {
        val fileName = file.fileName()
        if (context.isAnime) {
            return StreamCenterTorrentMatchPolicy.animeEpisodeEvidenceScore(
                candidateTitle = fileName,
                context = context,
                markerSource = file.path,
            )
        }
        if (hasConflictingSeasonInPath(file.path, context)) return 0
        val normalized = Normalizer.normalize(fileName, Normalizer.Form.NFKC)
        val season = context.season?.takeIf { it > 0 }
        var score = 0
        context.episodeNumbersForSearch().forEachIndexed { position, number ->
            val priorityBonus = if (position == 0) 2 else 0
            val value = number.toString()
            if (
                season != null && Regex(
                    """(?i)(?:^|[^a-z0-9])s0*$season[._ -]*e(?:p)?0*$value(?:v\d+)?(?:[^0-9]|$)""",
                ).containsMatchIn(normalized)
            ) {
                score = maxOf(score, 122 + priorityBonus)
            }
            if (
                Regex(
                    """(?i)(?:^|[^a-z0-9])s\d{1,3}[._ -]*e(?:p)?0*$value(?:v\d+)?(?:[^0-9]|$)""",
                ).containsMatchIn(normalized)
            ) {
                score = maxOf(score, 116 + priorityBonus)
            }
            if (
                season != null && Regex(
                    """(?i)(?:^|[^0-9])0*$season[._ -]*x[._ -]*0*$value(?:[^0-9]|$)""",
                ).containsMatchIn(normalized)
            ) {
                score = maxOf(score, 112 + priorityBonus)
            }
            if (
                Regex(
                    """(?i)(?:^|[^a-z0-9])e(?:p(?:isode)?)?[._ -]*0*$value(?:v\d+)?(?:[^0-9]|$)""",
                ).containsMatchIn(normalized)
            ) {
                score = maxOf(score, 108 + priorityBonus)
            }
            if (
                Regex(
                    """(?i)(?:^|[^a-z0-9])episode[._ -]*0*$value(?:v\d+)?(?:[^0-9]|$)""",
                ).containsMatchIn(normalized)
            ) {
                score = maxOf(score, 104 + priorityBonus)
            }
            if (
                Regex(
                    """(?i)(?:^|[\s._\-\[(])0*$value(?:v\d+)?(?=$|[\s._\-\])])""",
                ).containsMatchIn(normalized)
            ) {
                score = maxOf(score, 90 + priorityBonus)
            }
        }
        return score
    }

    private fun hasConflictingSeasonInPath(
        path: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        path.split('/', '\\').asReversed().forEach { component ->
            StreamCenterTorrentMatchPolicy.seasonMarkerCompatibility(component, context)
                ?.let { compatible -> return !compatible }
        }
        return false
    }

    private fun StreamCenterTorrentFile.fileName(): String =
        path.substringAfterLast('/').substringAfterLast('\\').trim()

    private const val EXPLICIT_EPISODE_SCORE = 104
}
