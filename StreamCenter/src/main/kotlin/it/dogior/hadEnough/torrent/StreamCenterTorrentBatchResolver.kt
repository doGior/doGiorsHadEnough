package it.dogior.hadEnough.torrent

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class StreamCenterTorrentBatchResolution(
    val candidate: StreamCenterTorrentCandidate? = null,
    val failure: String? = null,
    val fileCount: Int = 0,
    val videoFileCount: Int = 0,
    val matchingFileCount: Int = 0,
)

internal object StreamCenterTorrentBatchResolver {
    suspend fun resolve(
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
        val files = candidate.availableFiles ?: run {
            val request = candidate.fileMetadataRequest
                ?: return StreamCenterTorrentBatchResolution(
                    failure = "metadati_file_non_disponibili",
                )
            val fetchResult = try {
                withTimeoutOrNull(BATCH_METADATA_TIMEOUT_MS) {
                    FileFetchResult(fetchFiles(request))
                } ?: return StreamCenterTorrentBatchResolution(failure = "timeout_metadati_file")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return StreamCenterTorrentBatchResolution(failure = "errore_metadati_file")
            }
            fetchResult.files
                ?: return StreamCenterTorrentBatchResolution(failure = "metadati_file_non_validi")
        }

        val selection = StreamCenterTorrentFileSelector.select(
            files = files,
            context = context,
        )
        val selectedFile = selection.file
            ?: return StreamCenterTorrentBatchResolution(
                failure = selection.failure?.logValue() ?: "file_episodio_non_trovato",
                fileCount = selection.fileCount,
                videoFileCount = selection.videoFileCount,
                matchingFileCount = selection.matchingFileCount,
            )
        return StreamCenterTorrentBatchResolution(
            candidate = candidate.copy(
                size = selectedFile.sizeBytes?.let(::formatTorrentBytes) ?: candidate.size,
                sizeBytes = selectedFile.sizeBytes ?: candidate.sizeBytes,
                fileIndex = selectedFile.index,
                selectedFileName = selectedFile.path,
                fileMetadataRequest = null,
                availableFiles = null,
            ),
            fileCount = selection.fileCount,
            videoFileCount = selection.videoFileCount,
            matchingFileCount = selection.matchingFileCount,
        )
    }

    private suspend fun fetchFiles(
        request: StreamCenterTorrentFileMetadataRequest,
    ): List<StreamCenterTorrentFile>? {
        val response = app.get(
            url = request.url,
            headers = mapOf(
                "Accept" to when (request.format) {
                    StreamCenterTorrentFileMetadataFormat.TORRENT ->
                        "application/x-bittorrent, application/octet-stream;q=0.9"
                    else -> "application/json, text/plain;q=0.8"
                },
                "User-Agent" to USER_AGENT,
            ),
            cacheTime = 300,
            timeout = 5L,
        )
        if (response.code !in 200..299) return null
        val bytes = response.body.byteStream().use { input ->
            input.readLimited(MAX_METADATA_BYTES)
        } ?: return null
        return when (request.format) {
            StreamCenterTorrentFileMetadataFormat.TORRENT ->
                StreamCenterTorrentFileParser.parseTorrent(bytes, request.expectedInfoHash)?.files
            StreamCenterTorrentFileMetadataFormat.APIBAY -> parseApiBayFiles(bytes)
        }
    }

    private fun parseApiBayFiles(bytes: ByteArray): List<StreamCenterTorrentFile>? {
        val entries = runCatching { JSONArray(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return null
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val name = entry.optJSONArray("name")
                    ?.optString(0)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: entry.optString("name").trim().takeIf(String::isNotBlank)
                    ?: continue
                val size = entry.optJSONArray("size")
                    ?.optLong(0, 0L)
                    ?.takeIf { it > 0L }
                    ?: entry.optLong("size", 0L).takeIf { it > 0L }
                add(StreamCenterTorrentFile(index, name, size))
            }
        }.takeIf(List<StreamCenterTorrentFile>::isNotEmpty)
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun StreamCenterTorrentFileSelectionFailure.logValue(): String = when (this) {
        StreamCenterTorrentFileSelectionFailure.EPISODE_MISSING -> "episodio_non_disponibile"
        StreamCenterTorrentFileSelectionFailure.FILES_MISSING -> "elenco_file_vuoto"
        StreamCenterTorrentFileSelectionFailure.VIDEO_FILES_MISSING -> "file_video_non_trovati"
        StreamCenterTorrentFileSelectionFailure.EPISODE_FILE_MISSING -> "file_episodio_non_trovato"
        StreamCenterTorrentFileSelectionFailure.EPISODE_FILE_AMBIGUOUS -> "file_episodio_ambiguo"
    }

    private data class FileFetchResult(
        val files: List<StreamCenterTorrentFile>?,
    )

    private const val BATCH_METADATA_TIMEOUT_MS = 6_000L
    private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "Chrome/124.0.0.0 Safari/537.36"
}
