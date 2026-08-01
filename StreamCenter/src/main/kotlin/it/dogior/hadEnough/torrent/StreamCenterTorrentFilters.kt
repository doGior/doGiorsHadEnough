package it.dogior.hadEnough.torrent

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.roundToLong

internal enum class StreamCenterTorrentLanguageFilter(
    val preferenceValue: String,
) {
    ANY("any"),
    PRIORITIZE_ITALIAN("prioritize_italian"),
    ITALIAN_OR_MULTI("italian_or_multi"),
    EXPLICIT_ITALIAN("explicit_italian"),
    ;

    companion object {
        fun fromPreference(
            value: String?,
            fallback: StreamCenterTorrentLanguageFilter = ANY,
        ): StreamCenterTorrentLanguageFilter {
            return values().firstOrNull { filter -> filter.preferenceValue == value } ?: fallback
        }
    }
}

internal data class StreamCenterTorrentFilterSettings(
    val language: StreamCenterTorrentLanguageFilter =
        StreamCenterTorrentLanguageFilter.PRIORITIZE_ITALIAN,
    val excludeCinemaCopies: Boolean = false,
    val minimumResolution: Int = 720,
    val minimumSeeders: Int = 1,
    val maximumSizeBytes: Long = 0L,
) {
    fun languageSearchPasses(): List<StreamCenterTorrentFilterSettings> {
        return if (language == StreamCenterTorrentLanguageFilter.PRIORITIZE_ITALIAN) {
            listOf(
                copy(language = StreamCenterTorrentLanguageFilter.ITALIAN_OR_MULTI),
                copy(language = StreamCenterTorrentLanguageFilter.ANY),
            )
        } else {
            listOf(this)
        }
    }
}

internal object StreamCenterTorrentFilterPreferences {
    const val LANGUAGE_KEY = "torrentFilterLanguage"
    const val EXCLUDE_CINEMA_COPIES_KEY = "torrentFilterExcludeCinemaCopies"
    const val MINIMUM_RESOLUTION_KEY = "torrentFilterMinimumResolution"
    const val MINIMUM_SEEDERS_KEY = "torrentFilterMinimumSeeders"
    const val MAXIMUM_SIZE_BYTES_KEY = "torrentFilterMaximumSizeBytes"

    val minimumResolutionOptions = listOf(0, 480, 720, 1080, 2160)
    val minimumSeederOptions = listOf(0, 1, 5, 10, 20, 50)
    val maximumSizeOptions = listOf(
        0L,
        1L * GIBIBYTE,
        2L * GIBIBYTE,
        5L * GIBIBYTE,
        10L * GIBIBYTE,
        20L * GIBIBYTE,
        50L * GIBIBYTE,
    )

    fun read(preferences: SharedPreferences?): StreamCenterTorrentFilterSettings {
        return StreamCenterTorrentFilterSettings(
            language = StreamCenterTorrentLanguageFilter.fromPreference(
                value = preferences.safeString(LANGUAGE_KEY),
                fallback = DEFAULT_LANGUAGE,
            ),
            excludeCinemaCopies = preferences.safeBoolean(
                EXCLUDE_CINEMA_COPIES_KEY,
                false,
            ),
            minimumResolution = preferences.safeInt(
                MINIMUM_RESOLUTION_KEY,
                DEFAULT_MINIMUM_RESOLUTION,
            ).takeIf(minimumResolutionOptions::contains) ?: DEFAULT_MINIMUM_RESOLUTION,
            minimumSeeders = preferences.safeInt(
                MINIMUM_SEEDERS_KEY,
                DEFAULT_MINIMUM_SEEDERS,
            ).takeIf(minimumSeederOptions::contains) ?: DEFAULT_MINIMUM_SEEDERS,
            maximumSizeBytes = preferences.safeLong(
                MAXIMUM_SIZE_BYTES_KEY,
                0L,
            ).takeIf(maximumSizeOptions::contains) ?: 0L,
        )
    }

    fun setLanguage(
        preferences: SharedPreferences?,
        value: StreamCenterTorrentLanguageFilter,
    ) {
        preferences?.edit()?.apply {
            if (value == DEFAULT_LANGUAGE) remove(LANGUAGE_KEY)
            else putString(LANGUAGE_KEY, value.preferenceValue)
        }?.apply()
    }

    fun setExcludeCinemaCopies(
        preferences: SharedPreferences?,
        value: Boolean,
    ) {
        preferences?.edit()?.apply {
            if (value) putBoolean(EXCLUDE_CINEMA_COPIES_KEY, true)
            else remove(EXCLUDE_CINEMA_COPIES_KEY)
        }?.apply()
    }

    fun setMinimumResolution(
        preferences: SharedPreferences?,
        value: Int,
    ) {
        val normalized = value.takeIf(minimumResolutionOptions::contains)
            ?: DEFAULT_MINIMUM_RESOLUTION
        preferences?.edit()?.apply {
            if (normalized == DEFAULT_MINIMUM_RESOLUTION) remove(MINIMUM_RESOLUTION_KEY)
            else putInt(MINIMUM_RESOLUTION_KEY, normalized)
        }?.apply()
    }

    fun setMinimumSeeders(
        preferences: SharedPreferences?,
        value: Int,
    ) {
        val normalized = value.takeIf(minimumSeederOptions::contains)
            ?: DEFAULT_MINIMUM_SEEDERS
        preferences?.edit()?.apply {
            if (normalized == DEFAULT_MINIMUM_SEEDERS) remove(MINIMUM_SEEDERS_KEY)
            else putInt(MINIMUM_SEEDERS_KEY, normalized)
        }?.apply()
    }

    fun setMaximumSizeBytes(
        preferences: SharedPreferences?,
        value: Long,
    ) {
        val normalized = value.takeIf(maximumSizeOptions::contains) ?: 0L

        preferences?.edit()?.apply {
            if (normalized == 0L) remove(MAXIMUM_SIZE_BYTES_KEY)
            else putLong(MAXIMUM_SIZE_BYTES_KEY, normalized)
        }?.apply()
    }

    fun reset(preferences: SharedPreferences?) {
        val editor = preferences?.edit() ?: return
        reset(editor)
        editor.apply()
    }

    fun reset(editor: SharedPreferences.Editor) {
        editor.remove(LANGUAGE_KEY)
        editor.remove(EXCLUDE_CINEMA_COPIES_KEY)
        editor.remove(MINIMUM_RESOLUTION_KEY)
        editor.remove(MINIMUM_SEEDERS_KEY)
        editor.remove(MAXIMUM_SIZE_BYTES_KEY)
    }

    fun isDefaultPreference(key: String, value: Any?): Boolean = when (key) {
        LANGUAGE_KEY -> value == DEFAULT_LANGUAGE.preferenceValue
        EXCLUDE_CINEMA_COPIES_KEY -> value == false
        MINIMUM_RESOLUTION_KEY -> value == DEFAULT_MINIMUM_RESOLUTION
        MINIMUM_SEEDERS_KEY -> value == DEFAULT_MINIMUM_SEEDERS
        MAXIMUM_SIZE_BYTES_KEY -> value == 0L
        else -> false
    }

    private fun SharedPreferences?.safeString(key: String): String? =
        runCatching { this?.getString(key, null) }.getOrNull()

    private fun SharedPreferences?.safeBoolean(key: String, default: Boolean): Boolean =
        runCatching { this?.getBoolean(key, default) }.getOrNull() ?: default

    private fun SharedPreferences?.safeInt(key: String, default: Int): Int =
        runCatching { this?.getInt(key, default) }.getOrNull() ?: default

    private fun SharedPreferences?.safeLong(
        key: String,
        default: Long,
    ): Long =
        runCatching { this?.getLong(key, default) }.getOrNull() ?: default

    private const val GIBIBYTE = 1_073_741_824L
    private val DEFAULT_LANGUAGE = StreamCenterTorrentLanguageFilter.PRIORITIZE_ITALIAN
    private const val DEFAULT_MINIMUM_RESOLUTION = 720
    private const val DEFAULT_MINIMUM_SEEDERS = 1
}

internal object StreamCenterTorrentFilterEngine {
    fun accepts(
        candidate: StreamCenterTorrentCandidate,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): Boolean {
        val metadataText = releaseMetadataText(
            listOfNotNull(candidate.title, candidate.selectedFileName).joinToString(" "),
            context.titles,
        )
        val languageAccepted = when (filters.language) {
            StreamCenterTorrentLanguageFilter.ANY -> true
            StreamCenterTorrentLanguageFilter.PRIORITIZE_ITALIAN,
            StreamCenterTorrentLanguageFilter.ITALIAN_OR_MULTI ->
                StreamCenterTorrentMetadata.hasItalianEvidence(metadataText) ||
                    StreamCenterTorrentMetadata.hasMultiLanguageEvidence(metadataText)
            StreamCenterTorrentLanguageFilter.EXPLICIT_ITALIAN ->
                StreamCenterTorrentMetadata.hasExplicitItalianAudio(metadataText)
        }
        if (!languageAccepted) return false
        if (
            filters.excludeCinemaCopies &&
            StreamCenterTorrentMetadata.isCinemaCopy(metadataText)
        ) {
            return false
        }
        if (filters.minimumResolution > 0) {
            val resolution = StreamCenterTorrentMetadata.resolution(metadataText) ?: return false
            if (resolution < filters.minimumResolution) return false
        }
        if (filters.minimumSeeders > 0) {
            val seeders = candidate.seeders ?: return false
            if (seeders < filters.minimumSeeders) return false
        }
        if (filters.maximumSizeBytes > 0L) {
            val unresolvedBatch = candidate.fileIndex == null &&
                StreamCenterTorrentMatchPolicy.isBatchCandidate(candidate.title, context)
            if (!unresolvedBatch) {
                val sizeBytes = candidate.sizeBytes
                    ?: StreamCenterTorrentMetadata.parseSizeBytes(candidate.size)
                    ?: return false
                if (sizeBytes > filters.maximumSizeBytes) return false
            }
        }
        return true
    }

    private fun releaseMetadataText(candidateTitle: String, expectedTitles: List<String>): String {
        var normalized = normalizeMetadataText(candidateTitle)
        val titleMatch = expectedTitles
            .asSequence()
            .map(::normalizeMetadataText)
            .filter(String::isNotBlank)
            .distinct()
            .mapNotNull { expected ->
                val phrase = expected
                    .split(' ')
                    .filter(String::isNotBlank)
                    .joinToString("""\s+""") { token -> Regex.escape(token) }
                Regex("""(?<![a-z0-9])$phrase(?![a-z0-9])""")
                    .find(normalized)
                    ?.let { match -> ExpectedTitleMatch(match.range, expected.length) }
            }
            .minWithOrNull(
                compareBy<ExpectedTitleMatch> { match -> match.range.first }
                    .thenByDescending { match -> match.length },
            )
        if (titleMatch != null) {
            normalized = normalized.replaceRange(titleMatch.range, " ")
        }
        return cleanDisplayText(normalized)
    }

    private fun normalizeMetadataText(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .let(::cleanDisplayText)
    }

    private data class ExpectedTitleMatch(
        val range: IntRange,
        val length: Int,
    )
}

internal fun StreamCenterTorrentCandidate.isEligibleFor(
    context: StreamCenterTorrentPlaybackContext,
    filters: StreamCenterTorrentFilterSettings,
): Boolean {
    return StreamCenterTorrentMatchPolicy.matches(title, context) &&
        StreamCenterTorrentFilterEngine.accepts(this, context, filters)
}

internal object StreamCenterTorrentMetadata {
    private val italianSubtitleRegex = Regex(
        """
        \b(?:sub|subs|subbed|subtitle|subtitles)\s+(?:ita|italian|italiano|italiana)\b
        |
        \b(?:ita|italian|italiano|italiana)\s+(?:sub|subs|subbed|subtitle|subtitles)\b
        |
        \b(?:subita|itasub)\b
        """.trimIndent(),
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    )
    private val explicitItalianRegex = Regex(
        """\b(?:ita|italian|italiano|italiana|nuita)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val multiLanguageRegex = Regex(
        """\b(?:multi|multiaudio|multilanguage|dual\s+audio)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val cinemaCopyRegex = Regex(
        """
        \b(?:
            hd\s*cam(?:rip)?|camrip|cam|
            hd\s*ts|tsrip|telesync|
            tc|telecine|
            dvdscr|bd\s*scr|scr|dvd\s+screener|screener|
            workprint|r5
        )\b
        """.trimIndent(),
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    )
    private val explicitResolutionRegex = Regex(
        """(?<!\d)(144|240|360|480|540|576|720|1080|1440|2160|4320)[pi](?![a-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val dimensionResolutionRegex = Regex(
        """(?<!\d)\d{3,4}\s*[x×]\s*(144|240|360|480|540|576|720|1080|1440|2160|4320)(?!\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val sizeRegex = Regex(
        """(?i)(\d[\d.,]*)\s*(B|KB|KiB|MB|MiB|GB|GiB|TB|TiB)\b""",
    )
    private val likelyThousandsUnits = setOf("b", "kb", "kib", "mb", "mib")

    fun hasItalianEvidence(metadataText: String): Boolean {
        return italianSubtitleRegex.containsMatchIn(metadataText) ||
            hasExplicitItalianAudio(metadataText)
    }

    fun hasExplicitItalianAudio(metadataText: String): Boolean {
        val withoutItalianSubtitles = italianSubtitleRegex.replace(metadataText, " ")
        return explicitItalianRegex.containsMatchIn(withoutItalianSubtitles)
    }

    fun hasMultiLanguageEvidence(metadataText: String): Boolean =
        multiLanguageRegex.containsMatchIn(metadataText)

    fun isCinemaCopy(metadataText: String): Boolean =
        cinemaCopyRegex.containsMatchIn(metadataText)

    fun resolution(value: String): Int? {
        val normalized = cleanDisplayText(value.lowercase(Locale.ROOT))
        val resolutions = buildList {
            explicitResolutionRegex.findAll(normalized).forEach { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()?.let(::add)
            }
            dimensionResolutionRegex.findAll(normalized).forEach { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()?.let(::add)
            }
            if (Regex("""\b8k\b""").containsMatchIn(normalized)) add(4320)
            if (Regex("""\b(?:4k|uhd)\b""").containsMatchIn(normalized)) add(2160)
            if (Regex("""\b(?:fhd|full\s+hd)\b""").containsMatchIn(normalized)) add(1080)
            if (Regex("""\bsd\b""").containsMatchIn(normalized)) add(480)
        }
        return resolutions.maxOrNull()
    }

    fun parseSizeBytes(value: String?): Long? {
        val normalized = value
            ?.replace('\u00A0', ' ')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (normalized.all(Char::isDigit)) {
            return normalized.toLongOrNull()?.takeIf { bytes -> bytes > 0L }
        }
        val match = sizeRegex.find(normalized) ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val amount = parseSizeAmount(match.groupValues[1], unit)
            ?.takeIf { number -> number.isFinite() && number > 0.0 }
            ?: return null
        val multiplier = when (unit) {
            "b" -> 1L
            "kb", "kib" -> 1_024L
            "mb", "mib" -> 1_048_576L
            "gb", "gib" -> 1_073_741_824L
            "tb", "tib" -> 1_099_511_627_776L
            else -> return null
        }
        val bytes = amount * multiplier.toDouble()
        return bytes
            .takeIf { number -> number.isFinite() && number <= Long.MAX_VALUE.toDouble() }
            ?.roundToLong()
            ?.takeIf { number -> number > 0L }
    }

    private fun parseSizeAmount(value: String, unit: String): Double? {
        val separators = value.withIndex()
            .filter { (_, character) -> character == ',' || character == '.' }
        if (separators.isEmpty()) return value.toDoubleOrNull()

        val decimalIndex = when {
            value.contains(',') && value.contains('.') -> separators.last().index
            separators.size > 1 -> {
                val separator = separators.first().value
                val groups = value.split(separator)
                if (groups.drop(1).all { group -> group.length == 3 }) null
                else separators.last().index
            }
            else -> {
                val separatorIndex = separators.single().index
                val trailingDigits = value.length - separatorIndex - 1
                val likelyThousands = trailingDigits == 3 &&
                    unit in likelyThousandsUnits
                if (likelyThousands) null else separatorIndex
            }
        }

        val canonical = buildString(value.length) {
            value.forEachIndexed { index, character ->
                when {
                    character.isDigit() -> append(character)
                    index == decimalIndex -> append('.')
                }
            }
        }
        return canonical.toDoubleOrNull()
    }
}
