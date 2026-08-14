package it.dogior.hadEnough.torrent

internal data class StreamCenterTorrentBatchResolution(
    val candidate: StreamCenterTorrentCandidate? = null,
    val failure: String? = null,
)

internal object StreamCenterTorrentBatchResolver {
    fun resolve(
        candidate: StreamCenterTorrentCandidate,
        context: StreamCenterTorrentPlaybackContext,
    ): StreamCenterTorrentBatchResolution {
        val suppliedIndex = candidate.fileIndex
            ?: StreamCenterTorrentMagnet.fileIndex(candidate.magnetUrl)
        if (suppliedIndex != null) {
            return StreamCenterTorrentBatchResolution(
                candidate = candidate.copy(fileIndex = suppliedIndex),
            )
        }
        val files = candidate.availableFiles
            ?: return StreamCenterTorrentBatchResolution(
                failure = "metadati_file_non_disponibili",
            )

        val selection = StreamCenterTorrentFileSelector.select(
            files = files,
            context = context,
        )
        val selectedFile = selection.file
            ?: return StreamCenterTorrentBatchResolution(
                failure = selection.failure?.logValue() ?: "file_episodio_non_trovato",
            )
        return StreamCenterTorrentBatchResolution(
            candidate = candidate.copy(
                fileIndex = selectedFile.index,
                selectedFileName = selectedFile.path,
                sizeBytes = selectedFile.sizeBytes ?: candidate.sizeBytes,
                availableFiles = null,
            ),
        )
    }

    private fun StreamCenterTorrentFileSelectionFailure.logValue(): String = when (this) {
        StreamCenterTorrentFileSelectionFailure.FILES_MISSING -> "elenco_file_vuoto"
        StreamCenterTorrentFileSelectionFailure.VIDEO_FILES_MISSING -> "file_video_non_trovati"
        StreamCenterTorrentFileSelectionFailure.EPISODE_FILE_MISSING -> "file_episodio_non_trovato"
        StreamCenterTorrentFileSelectionFailure.EPISODE_FILE_AMBIGUOUS -> "file_episodio_ambiguo"
    }
}
