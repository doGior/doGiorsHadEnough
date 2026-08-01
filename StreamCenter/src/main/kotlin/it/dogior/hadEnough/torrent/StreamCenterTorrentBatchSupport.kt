package it.dogior.hadEnough.torrent

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal enum class StreamCenterTorrentFileSelectionFailure {
    EPISODE_MISSING,
    FILES_MISSING,
    VIDEO_FILES_MISSING,
    EPISODE_FILE_MISSING,
    EPISODE_FILE_AMBIGUOUS,
}

internal data class StreamCenterTorrentFileSelection(
    val file: StreamCenterTorrentFile? = null,
    val failure: StreamCenterTorrentFileSelectionFailure? = null,
    val fileCount: Int = 0,
    val videoFileCount: Int = 0,
    val matchingFileCount: Int = 0,
)

internal object StreamCenterTorrentFileSelector {
    private val videoExtensions = setOf(
        "avi",
        "flv",
        "m2ts",
        "m4v",
        "mkv",
        "mov",
        "mp4",
        "mpeg",
        "mpg",
        "ogv",
        "ts",
        "webm",
        "wmv",
    )
    private val ignoredVideoRegex = Regex(
        """(?i)(?:^|[^a-z0-9])(?:sample|trailer|preview|proof|featurette|interview|ncop|nced)(?:[^a-z0-9]|$)""",
    )

    fun select(
        files: List<StreamCenterTorrentFile>,
        context: StreamCenterTorrentPlaybackContext,
    ): StreamCenterTorrentFileSelection {
        if (context.episode == null) {
            return StreamCenterTorrentFileSelection(
                failure = StreamCenterTorrentFileSelectionFailure.EPISODE_MISSING,
                fileCount = files.size,
            )
        }
        if (files.isEmpty()) {
            return StreamCenterTorrentFileSelection(
                failure = StreamCenterTorrentFileSelectionFailure.FILES_MISSING,
            )
        }
        val videoFiles = files.filter { file ->
            val fileName = file.fileName()
            fileName.substringAfterLast('.', "")
                .lowercase(Locale.ROOT) in videoExtensions &&
                !ignoredVideoRegex.containsMatchIn(fileName)
        }
        if (videoFiles.isEmpty()) {
            return StreamCenterTorrentFileSelection(
                failure = StreamCenterTorrentFileSelectionFailure.VIDEO_FILES_MISSING,
                fileCount = files.size,
            )
        }

        val playableEpisodeFiles = videoFiles.filterNot { file ->
            StreamCenterTorrentMatchPolicy.isBatchCandidate(file.fileName(), context)
        }
        val scored = playableEpisodeFiles.mapNotNull { file ->
            episodeScore(file.fileName(), context)
                .takeIf { score -> score > 0 }
                ?.let { score -> file to score }
        }
        val highestScore = scored.maxOfOrNull(Pair<StreamCenterTorrentFile, Int>::second)
        val matchingFiles = highestScore
            ?.let { score -> scored.filter { it.second == score }.map(Pair<StreamCenterTorrentFile, Int>::first) }
            .orEmpty()
        val selected = when {
            matchingFiles.size == 1 -> matchingFiles.single()
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
            fileCount = files.size,
            videoFileCount = videoFiles.size,
            matchingFileCount = matchingFiles.size,
        )
    }

    private fun episodeScore(
        fileName: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Int {
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

    private fun StreamCenterTorrentFile.fileName(): String =
        path.substringAfterLast('/').substringAfterLast('\\').trim()
}

internal data class StreamCenterParsedTorrentFiles(
    val files: List<StreamCenterTorrentFile>,
    val infoHash: String,
)

internal object StreamCenterTorrentFileParser {
    fun parseTorrent(
        bytes: ByteArray,
        expectedInfoHash: String?,
    ): StreamCenterParsedTorrentFiles? {
        val parsed = runCatching { BencodeReader(bytes).parseTorrent() }.getOrNull()
            ?: return null
        val infoHash = MessageDigest.getInstance("SHA-1")
            .digest(bytes.copyOfRange(parsed.infoStart, parsed.infoEnd))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        if (
            expectedInfoHash != null &&
            !infoHash.equals(expectedInfoHash.trim(), ignoreCase = true)
        ) {
            return null
        }
        val info = parsed.root["info"] as? BencodeValue.Dictionary ?: return null
        val files = parseV1Files(info).ifEmpty { parseV2Files(info) }
        return StreamCenterParsedTorrentFiles(files, infoHash)
    }

    private fun parseV1Files(info: BencodeValue.Dictionary): List<StreamCenterTorrentFile> {
        val multiFiles = (info.values["files"] as? BencodeValue.ListValue)?.values
        if (multiFiles != null) {
            return multiFiles.mapIndexedNotNull { index, value ->
                val file = value as? BencodeValue.Dictionary ?: return@mapIndexedNotNull null
                val path = file.path("path.utf-8") ?: file.path("path")
                    ?: return@mapIndexedNotNull null
                StreamCenterTorrentFile(
                    index = index,
                    path = path,
                    sizeBytes = file.long("length"),
                )
            }
        }
        val name = info.text("name.utf-8") ?: info.text("name") ?: return emptyList()
        return listOf(
            StreamCenterTorrentFile(
                index = 0,
                path = name,
                sizeBytes = info.long("length"),
            ),
        )
    }

    private fun parseV2Files(info: BencodeValue.Dictionary): List<StreamCenterTorrentFile> {
        val tree = info.values["file tree"] as? BencodeValue.Dictionary ?: return emptyList()
        val files = mutableListOf<StreamCenterTorrentFile>()

        fun visit(node: BencodeValue.Dictionary, path: List<String>) {
            node.values.forEach { (name, value) ->
                val dictionary = value as? BencodeValue.Dictionary ?: return@forEach
                if (name.isEmpty()) {
                    if (path.isNotEmpty()) {
                        files += StreamCenterTorrentFile(
                            index = files.size,
                            path = path.joinToString("/"),
                            sizeBytes = dictionary.long("length"),
                        )
                    }
                } else {
                    visit(dictionary, path + name)
                }
            }
        }

        visit(tree, emptyList())
        return files
    }

    private fun BencodeValue.Dictionary.text(key: String): String? =
        (values[key] as? BencodeValue.Bytes)
            ?.value
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun BencodeValue.Dictionary.long(key: String): Long? =
        (values[key] as? BencodeValue.Integer)?.value?.takeIf { it >= 0L }

    private fun BencodeValue.Dictionary.path(key: String): String? =
        (values[key] as? BencodeValue.ListValue)
            ?.values
            ?.mapNotNull { value ->
                (value as? BencodeValue.Bytes)
                    ?.value
                    ?.toString(Charsets.UTF_8)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            ?.takeIf(List<String>::isNotEmpty)
            ?.joinToString("/")
}

private sealed interface BencodeValue {
    data class Bytes(val value: ByteArray) : BencodeValue
    data class Integer(val value: Long) : BencodeValue
    data class ListValue(val values: List<BencodeValue>) : BencodeValue
    data class Dictionary(val values: LinkedHashMap<String, BencodeValue>) : BencodeValue
}

private data class ParsedBencodeTorrent(
    val root: LinkedHashMap<String, BencodeValue>,
    val infoStart: Int,
    val infoEnd: Int,
)

private class BencodeReader(
    private val bytes: ByteArray,
) {
    private var position = 0
    private var entries = 0

    fun parseTorrent(): ParsedBencodeTorrent {
        require(readByte() == 'd'.code)
        val root = linkedMapOf<String, BencodeValue>()
        var infoStart = -1
        var infoEnd = -1
        while (peekByte() != 'e'.code) {
            val key = parseBytes().value.toString(Charsets.UTF_8)
            val valueStart = position
            val value = parseValue(1)
            if (key == "info") {
                infoStart = valueStart
                infoEnd = position
            }
            root[key] = value
            registerEntry()
        }
        readByte()
        require(position == bytes.size)
        require(infoStart >= 0 && infoEnd > infoStart)
        return ParsedBencodeTorrent(root, infoStart, infoEnd)
    }

    private fun parseValue(depth: Int): BencodeValue {
        require(depth <= MAX_DEPTH)
        return when (peekByte()) {
            'i'.code -> parseInteger()
            'l'.code -> parseList(depth)
            'd'.code -> parseDictionary(depth)
            in '0'.code..'9'.code -> parseBytes()
            else -> error("Valore bencode non valido")
        }
    }

    private fun parseInteger(): BencodeValue.Integer {
        readByte()
        val start = position
        while (peekByte() != 'e'.code) position++
        val value = bytes.copyOfRange(start, position)
            .toString(Charsets.US_ASCII)
            .toLong()
        readByte()
        return BencodeValue.Integer(value)
    }

    private fun parseList(depth: Int): BencodeValue.ListValue {
        readByte()
        val values = mutableListOf<BencodeValue>()
        while (peekByte() != 'e'.code) {
            values += parseValue(depth + 1)
            registerEntry()
        }
        readByte()
        return BencodeValue.ListValue(values)
    }

    private fun parseDictionary(depth: Int): BencodeValue.Dictionary {
        readByte()
        val values = linkedMapOf<String, BencodeValue>()
        while (peekByte() != 'e'.code) {
            val key = parseBytes().value.toString(Charsets.UTF_8)
            values[key] = parseValue(depth + 1)
            registerEntry()
        }
        readByte()
        return BencodeValue.Dictionary(values)
    }

    private fun parseBytes(): BencodeValue.Bytes {
        val lengthStart = position
        while (peekByte() != ':'.code) {
            require(peekByte() in '0'.code..'9'.code)
            position++
        }
        val length = bytes.copyOfRange(lengthStart, position)
            .toString(Charsets.US_ASCII)
            .toInt()
        require(length in 0..MAX_STRING_BYTES)
        readByte()
        require(position + length <= bytes.size)
        return BencodeValue.Bytes(bytes.copyOfRange(position, position + length)).also {
            position += length
        }
    }

    private fun registerEntry() {
        entries++
        require(entries <= MAX_ENTRIES)
    }

    private fun peekByte(): Int {
        require(position in bytes.indices)
        return bytes[position].toInt() and 0xff
    }

    private fun readByte(): Int = peekByte().also { position++ }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_ENTRIES = 20_000
        const val MAX_STRING_BYTES = 4 * 1024 * 1024
    }
}
