package it.dogior.hadEnough.torrent

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToLong

private const val DEFAULT_TORRENT_RESULT_LIMIT = 25
private const val MIN_TORRENT_RESULT_LIMIT = 1
private const val MAX_TORRENT_RESULT_LIMIT = 100

internal enum class StreamCenterTorrentLanguageFilter(
    val preferenceValue: String,
    val title: String,
    val description: String,
) {
    PREFER_ITALIAN(
        preferenceValue = "prefer_italian",
        title = "Preferisci italiano",
        description = "Prova a portare in alto audio o sottotitoli italiani; se non sono disponibili, mostra gli altri risultati.",
    ),
    ONLY_ITALIAN(
        preferenceValue = "only_italian",
        title = "Solo italiano",
        description = "Mostra solo release con italiano chiaramente indicato nell'audio o nei sottotitoli.",
    ),
    ANY(
        preferenceValue = "any",
        title = "Qualsiasi lingua",
        description = "Non applica preferenze o limiti di lingua.",
    ),
    ;

    companion object {
        fun fromPreference(value: String?): StreamCenterTorrentLanguageFilter =
            entries.firstOrNull { filter -> filter.preferenceValue == value } ?: PREFER_ITALIAN
    }
}

internal enum class StreamCenterTorrentLanguageEvidence(
    val priority: Int,
    val confirmsItalian: Boolean,
) {
    ITALIAN_AUDIO_EXPLICIT(priority = 220, confirmsItalian = true),
    ITALIAN_SUBTITLES_EXPLICIT(priority = 180, confirmsItalian = true),
    ITALIAN_EXPLICIT_UNSPECIFIED(priority = 220, confirmsItalian = true),
    MULTI_SUBTITLES_GENERIC(priority = 140, confirmsItalian = false),
    MULTI_AUDIO_GENERIC(priority = 120, confirmsItalian = false),
    DUAL_AUDIO_GENERIC(priority = 80, confirmsItalian = false),
    ITALIAN_RELEASE_HINT(priority = 40, confirmsItalian = false),
    NONE(priority = 0, confirmsItalian = false),
}

internal data class StreamCenterTorrentFilterSettings(
    val containLocation: StreamCenterExtContainLocation = StreamCenterExtContainLocation.TITLE,
    val language: StreamCenterTorrentLanguageFilter = StreamCenterTorrentLanguageFilter.PREFER_ITALIAN,
    val resultLimit: Int = DEFAULT_TORRENT_RESULT_LIMIT,
    val minimumSizeBytes: Long? = null,
    val maximumSizeBytes: Long? = null,
    val minimumSeeders: Int? = 1,
    val maximumSeeders: Int? = null,
    val minimumResolution: Int = 1080,
    val excludeCinemaCopies: Boolean = true,
    val excludedTerms: String = "",
    val blockedVideoCodecs: Set<StreamCenterTorrentVideoCodec> = emptySet(),
) {
    fun hasValidBounds(): Boolean =
        (minimumSizeBytes == null || maximumSizeBytes == null || minimumSizeBytes <= maximumSizeBytes) &&
            (minimumSeeders == null || maximumSeeders == null || minimumSeeders <= maximumSeeders)
}

internal object StreamCenterTorrentPreferences {
    const val SEARCH_LOCATION_KEY = "torrentExtSearchLocation"
    const val LANGUAGE_KEY = "torrentExtLanguage"
    const val RESULT_LIMIT_KEY = "torrentExtResultLimit"
    const val MINIMUM_SIZE_BYTES_KEY = "torrentExtMinimumSizeBytes"
    const val MAXIMUM_SIZE_BYTES_KEY = "torrentExtMaximumSizeBytes"
    const val MINIMUM_SEEDERS_KEY = "torrentExtMinimumSeeders"
    const val MAXIMUM_SEEDERS_KEY = "torrentExtMaximumSeeders"
    const val MINIMUM_RESOLUTION_KEY = "torrentExtMinimumResolution"
    const val EXCLUDE_CINEMA_COPIES_KEY = "torrentExtExcludeCinemaCopies"
    const val EXCLUDED_TERMS_KEY = "torrentExtExcludedTerms"
    const val BLOCKED_VIDEO_CODECS_KEY = "torrentExtBlockedVideoCodecs"
    const val PRIMARY_ENABLED_KEY = "torrentExtPrimaryEnabled"
    const val SECONDARY_ENABLED_KEY = "torrentExtSecondaryEnabled"
    const val PROXY_ENABLED_KEY = "torrentExtProxyEnabled"
    const val DOMAIN_ORDER_KEY = "torrentExtDomainOrder"

    private val defaultDomainOrder = StreamCenterExtDomain.entries.map { it.preferenceValue }

    fun read(preferences: SharedPreferences?): StreamCenterTorrentFilterSettings {
        return StreamCenterTorrentFilterSettings(
            containLocation = StreamCenterExtContainLocation.fromPreference(
                preferences.safeString(SEARCH_LOCATION_KEY),
            ),
            language = StreamCenterTorrentLanguageFilter.fromPreference(
                preferences.safeString(LANGUAGE_KEY),
            ),
            resultLimit = preferences.safeInt(RESULT_LIMIT_KEY)
                ?.takeIf { value -> value in MIN_TORRENT_RESULT_LIMIT..MAX_TORRENT_RESULT_LIMIT }
                ?: DEFAULT_TORRENT_RESULT_LIMIT,
            minimumSizeBytes = preferences.safeLong(MINIMUM_SIZE_BYTES_KEY)
                ?.takeIf { value -> value > 0L },
            maximumSizeBytes = preferences.safeLong(MAXIMUM_SIZE_BYTES_KEY)
                ?.takeIf { value -> value > 0L },
            minimumSeeders = preferences.safeInt(MINIMUM_SEEDERS_KEY)
                ?.let { value -> value.takeIf { it > 0 } }
                ?: if (preferences.containsKey(MINIMUM_SEEDERS_KEY)) null else DEFAULT_MINIMUM_SEEDERS,
            maximumSeeders = preferences.safeInt(MAXIMUM_SEEDERS_KEY)
                ?.takeIf { value -> value >= 0 },
            minimumResolution = preferences.safeInt(MINIMUM_RESOLUTION_KEY)
                ?.takeIf { value -> value in minimumResolutionOptions }
                ?: DEFAULT_MINIMUM_RESOLUTION,
            excludeCinemaCopies = preferences.safeBoolean(EXCLUDE_CINEMA_COPIES_KEY)
                ?: DEFAULT_EXCLUDE_CINEMA_COPIES,
            excludedTerms = preferences.safeString(EXCLUDED_TERMS_KEY)
                ?.trim()
                .orEmpty(),
            blockedVideoCodecs = blockedVideoCodecs(preferences),
        ).takeIf(StreamCenterTorrentFilterSettings::hasValidBounds)
            ?: StreamCenterTorrentFilterSettings(
                containLocation = StreamCenterExtContainLocation.fromPreference(
                    preferences.safeString(SEARCH_LOCATION_KEY),
                ),
                language = StreamCenterTorrentLanguageFilter.fromPreference(
                    preferences.safeString(LANGUAGE_KEY),
                ),
                resultLimit = preferences.safeInt(RESULT_LIMIT_KEY)
                    ?.takeIf { value -> value in MIN_TORRENT_RESULT_LIMIT..MAX_TORRENT_RESULT_LIMIT }
                    ?: DEFAULT_TORRENT_RESULT_LIMIT,
                minimumResolution = preferences.safeInt(MINIMUM_RESOLUTION_KEY)
                    ?.takeIf { value -> value in minimumResolutionOptions }
                    ?: DEFAULT_MINIMUM_RESOLUTION,
                excludeCinemaCopies = preferences.safeBoolean(EXCLUDE_CINEMA_COPIES_KEY)
                    ?: DEFAULT_EXCLUDE_CINEMA_COPIES,
                excludedTerms = preferences.safeString(EXCLUDED_TERMS_KEY)?.trim().orEmpty(),
                blockedVideoCodecs = blockedVideoCodecs(preferences),
            )
    }

    fun enabledDomains(preferences: SharedPreferences?): List<StreamCenterExtDomain> {
        val enabled = StreamCenterExtDomain.entries.filter { domain ->
            when (domain) {
                StreamCenterExtDomain.PRIMARY ->
                    preferences.safeBoolean(PRIMARY_ENABLED_KEY) ?: domain.defaultEnabled
                StreamCenterExtDomain.SECONDARY ->
                    preferences.safeBoolean(SECONDARY_ENABLED_KEY) ?: domain.defaultEnabled
                StreamCenterExtDomain.PROXY ->
                    preferences.safeBoolean(PROXY_ENABLED_KEY) ?: domain.defaultEnabled
            }
        }.toSet()
        return domainOrder(preferences).filter { domain -> domain in enabled }
    }

    fun isDomainEnabled(
        preferences: SharedPreferences?,
        domain: StreamCenterExtDomain,
    ): Boolean = domain in enabledDomains(preferences)

    fun setDomainEnabled(
        preferences: SharedPreferences?,
        domain: StreamCenterExtDomain,
        enabled: Boolean,
    ) {
        val key = when (domain) {
            StreamCenterExtDomain.PRIMARY -> PRIMARY_ENABLED_KEY
            StreamCenterExtDomain.SECONDARY -> SECONDARY_ENABLED_KEY
            StreamCenterExtDomain.PROXY -> PROXY_ENABLED_KEY
        }
        val default = domain.defaultEnabled
        preferences?.edit()?.apply {
            if (enabled == default) remove(key) else putBoolean(key, enabled)
        }?.apply()
    }

    fun domainOrder(preferences: SharedPreferences?): List<StreamCenterExtDomain> {
        val stored = preferences.safeString(DOMAIN_ORDER_KEY)
            ?.split(',')
            ?.mapNotNull(StreamCenterExtDomain::fromPreference)
            ?.distinct()
            .orEmpty()
        return (stored + StreamCenterExtDomain.entries).distinct()
    }

    fun setDomainOrder(preferences: SharedPreferences?, order: List<StreamCenterExtDomain>) {
        val normalized = (order + StreamCenterExtDomain.entries)
            .distinct()
            .map(StreamCenterExtDomain::preferenceValue)
        preferences?.edit()?.apply {
            if (normalized == defaultDomainOrder) remove(DOMAIN_ORDER_KEY)
            else putString(DOMAIN_ORDER_KEY, normalized.joinToString(","))
        }?.apply()
    }

    fun setContainLocation(preferences: SharedPreferences?, value: StreamCenterExtContainLocation) {
        preferences.storeDefaultAware(SEARCH_LOCATION_KEY, value.preferenceValue, StreamCenterExtContainLocation.TITLE.preferenceValue)
    }

    fun setLanguage(preferences: SharedPreferences?, value: StreamCenterTorrentLanguageFilter) {
        preferences.storeDefaultAware(
            LANGUAGE_KEY,
            value.preferenceValue,
            StreamCenterTorrentLanguageFilter.PREFER_ITALIAN.preferenceValue,
        )
    }

    fun setResultLimit(preferences: SharedPreferences?, value: Int) {
        val normalized = value.coerceIn(MIN_TORRENT_RESULT_LIMIT, MAX_TORRENT_RESULT_LIMIT)
        preferences?.edit()?.apply {
            if (normalized == DEFAULT_TORRENT_RESULT_LIMIT) remove(RESULT_LIMIT_KEY)
            else putInt(RESULT_LIMIT_KEY, normalized)
        }?.apply()
    }

    fun setSizeBounds(
        preferences: SharedPreferences?,
        minimumBytes: Long?,
        maximumBytes: Long?,
    ): Boolean {
        val minimum = minimumBytes?.takeIf { value -> value > 0L }
        val maximum = maximumBytes?.takeIf { value -> value > 0L }
        if (minimum != null && maximum != null && minimum > maximum) return false
        preferences?.edit()?.apply {
            putOptionalLong(MINIMUM_SIZE_BYTES_KEY, minimum)
            putOptionalLong(MAXIMUM_SIZE_BYTES_KEY, maximum)
        }?.apply()
        return true
    }

    fun setSeederBounds(
        preferences: SharedPreferences?,
        minimumSeeders: Int?,
        maximumSeeders: Int?,
    ): Boolean {
        val minimum = minimumSeeders?.takeIf { value -> value >= 0 }
        val maximum = maximumSeeders?.takeIf { value -> value >= 0 }
        if (minimum != null && maximum != null && minimum > maximum) return false
        preferences?.edit()?.apply {
            putMinimumSeeders(minimum)
            putOptionalInt(MAXIMUM_SEEDERS_KEY, maximum, null)
        }?.apply()
        return true
    }

    fun setMinimumResolution(preferences: SharedPreferences?, value: Int) {
        val normalized = value.takeIf { it in minimumResolutionOptions } ?: DEFAULT_MINIMUM_RESOLUTION
        preferences?.edit()?.apply {
            if (normalized == DEFAULT_MINIMUM_RESOLUTION) remove(MINIMUM_RESOLUTION_KEY)
            else putInt(MINIMUM_RESOLUTION_KEY, normalized)
        }?.apply()
    }

    fun setExcludeCinemaCopies(preferences: SharedPreferences?, value: Boolean) {
        preferences?.edit()?.apply {
            if (value == DEFAULT_EXCLUDE_CINEMA_COPIES) remove(EXCLUDE_CINEMA_COPIES_KEY)
            else putBoolean(EXCLUDE_CINEMA_COPIES_KEY, value)
        }?.apply()
    }

    fun setExcludedTerms(preferences: SharedPreferences?, value: String) {
        val normalized = value
            .split(',', ';', '\n')
            .map(::cleanDisplayText)
            .filter(String::isNotBlank)
            .distinctBy { term -> term.lowercase(Locale.ROOT) }
            .joinToString(", ")
            .take(MAX_EXCLUDED_TERMS_LENGTH)
            .trimEnd(',', ' ')
        preferences?.edit()?.apply {
            if (normalized.isBlank()) remove(EXCLUDED_TERMS_KEY)
            else putString(EXCLUDED_TERMS_KEY, normalized)
        }?.apply()
    }

    fun setVideoCodecAllowed(
        preferences: SharedPreferences?,
        codec: StreamCenterTorrentVideoCodec,
        allowed: Boolean,
    ) {
        val blocked = blockedVideoCodecs(preferences).toMutableSet()
        if (allowed) blocked -= codec else blocked += codec
        storeBlockedVideoCodecs(preferences, blocked)
    }

    fun reset(preferences: SharedPreferences?) {
        val editor = preferences?.edit() ?: return
        reset(editor)
        editor.apply()
    }

    fun reset(editor: SharedPreferences.Editor) {
        preferenceKeys.forEach(editor::remove)
    }

    fun isObsoletePreference(key: String): Boolean {
        return key.startsWith("sourceTorrent") ||
            key.startsWith("urlTorrent") ||
            key.startsWith("torrentFilter") ||
            key.startsWith("torrentExtReleaseSources")
    }

    fun isDefaultPreference(key: String, value: Any?): Boolean = when (key) {
        SEARCH_LOCATION_KEY -> value == StreamCenterExtContainLocation.TITLE.preferenceValue
        LANGUAGE_KEY -> value == StreamCenterTorrentLanguageFilter.PREFER_ITALIAN.preferenceValue
        RESULT_LIMIT_KEY -> value == DEFAULT_TORRENT_RESULT_LIMIT
        MINIMUM_SEEDERS_KEY -> value == DEFAULT_MINIMUM_SEEDERS
        MINIMUM_RESOLUTION_KEY -> value == DEFAULT_MINIMUM_RESOLUTION
        EXCLUDE_CINEMA_COPIES_KEY -> value == DEFAULT_EXCLUDE_CINEMA_COPIES
        EXCLUDED_TERMS_KEY -> value is String && value.isBlank()
        BLOCKED_VIDEO_CODECS_KEY -> value is String && parseVideoCodecs(value).isEmpty()
        PRIMARY_ENABLED_KEY -> value == true
        SECONDARY_ENABLED_KEY -> value == true
        PROXY_ENABLED_KEY -> value == false
        DOMAIN_ORDER_KEY -> value is String && parseDomainOrder(value) == defaultDomainOrder
        else -> false
    }

    fun summary(preferences: SharedPreferences?): String {
        val settings = read(preferences)
        val domains = enabledDomains(preferences).joinToString(" → ") { domain ->
            when (domain) {
                StreamCenterExtDomain.PRIMARY -> "principale"
                StreamCenterExtDomain.SECONDARY -> "fallback"
                StreamCenterExtDomain.PROXY -> "proxy"
            }
        }
        return buildList {
            add("EXT · $domains")
            add(settings.containLocation.title)
            add(settings.language.title)
            add("${settings.resultLimit} torrent")
            add(
                settings.minimumResolution
                    .takeIf { resolution -> resolution > 0 }
                    ?.let { resolution -> "${resolution}p+" }
                    ?: "qualità libera",
            )
            settings.minimumSeeders?.let { seeders -> add("$seeders+ seed") }
            if (settings.excludeCinemaCopies) add("copie cinema escluse")
            if (settings.excludedTerms.isNotBlank()) add("termini esclusi")
            settings.blockedVideoCodecs.size
                .takeIf { count -> count > 0 }
                ?.let { count -> add("$count codec esclusi") }
        }.joinToString(" · ")
    }

    val minimumResolutionOptions: List<Int> = listOf(0, 480, 720, 1080, 2160)
    fun isDefaultResolutionFloor(value: Int): Boolean = value == DEFAULT_MINIMUM_RESOLUTION

    fun isDefaultSeederFloor(value: Int): Boolean = value == DEFAULT_MINIMUM_SEEDERS

    private fun blockedVideoCodecs(
        preferences: SharedPreferences?,
    ): Set<StreamCenterTorrentVideoCodec> = parseVideoCodecs(preferences.safeString(BLOCKED_VIDEO_CODECS_KEY))

    private fun parseVideoCodecs(value: String?): Set<StreamCenterTorrentVideoCodec> = value
        ?.split(',')
        ?.mapNotNull(StreamCenterTorrentVideoCodec::fromPreference)
        ?.toCollection(linkedSetOf())
        .orEmpty()

    private fun storeBlockedVideoCodecs(
        preferences: SharedPreferences?,
        blocked: Set<StreamCenterTorrentVideoCodec>,
    ) {
        val normalized = StreamCenterTorrentVideoCodec.entries.filter { codec -> codec in blocked }
        preferences?.edit()?.apply {
            if (normalized.isEmpty()) remove(BLOCKED_VIDEO_CODECS_KEY)
            else putString(BLOCKED_VIDEO_CODECS_KEY, normalized.joinToString(",") { it.preferenceValue })
        }?.apply()
    }

    private fun parseDomainOrder(value: String): List<String> = value
        .split(',')
        .mapNotNull(StreamCenterExtDomain::fromPreference)
        .distinct()
        .let { domains ->
            (listOf(StreamCenterExtDomain.PRIMARY) + domains + StreamCenterExtDomain.entries)
                .distinct()
                .map(StreamCenterExtDomain::preferenceValue)
        }

    private fun SharedPreferences?.storeDefaultAware(key: String, value: String, default: String) {
        this?.edit()?.apply {
            if (value == default) remove(key) else putString(key, value)
        }?.apply()
    }

    private fun SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun SharedPreferences.Editor.putMinimumSeeders(value: Int?) {
        when {
            value == null -> putInt(MINIMUM_SEEDERS_KEY, 0)
            value == DEFAULT_MINIMUM_SEEDERS -> remove(MINIMUM_SEEDERS_KEY)
            else -> putInt(MINIMUM_SEEDERS_KEY, value)
        }
    }

    private fun SharedPreferences.Editor.putOptionalInt(key: String, value: Int?, default: Int?) {
        when {
            value == null || value == default -> remove(key)
            else -> putInt(key, value)
        }
    }

    private fun SharedPreferences?.safeString(key: String): String? =
        runCatching { this?.getString(key, null) }.getOrNull()

    private fun SharedPreferences?.safeBoolean(key: String): Boolean? =
        runCatching {
            if (this?.contains(key) == true) this.getBoolean(key, false) else null
        }.getOrNull()

    private fun SharedPreferences?.safeInt(key: String): Int? =
        runCatching {
            if (this?.contains(key) == true) this.getInt(key, 0) else null
        }.getOrNull()

    private fun SharedPreferences?.safeLong(key: String): Long? =
        runCatching {
            if (this?.contains(key) == true) this.getLong(key, 0L) else null
        }.getOrNull()

    private fun SharedPreferences?.containsKey(key: String): Boolean =
        runCatching { this?.contains(key) == true }.getOrDefault(false)

    private val preferenceKeys = setOf(
        SEARCH_LOCATION_KEY,
        LANGUAGE_KEY,
        RESULT_LIMIT_KEY,
        MINIMUM_SIZE_BYTES_KEY,
        MAXIMUM_SIZE_BYTES_KEY,
        MINIMUM_SEEDERS_KEY,
        MAXIMUM_SEEDERS_KEY,
        MINIMUM_RESOLUTION_KEY,
        EXCLUDE_CINEMA_COPIES_KEY,
        EXCLUDED_TERMS_KEY,
        BLOCKED_VIDEO_CODECS_KEY,
        PRIMARY_ENABLED_KEY,
        SECONDARY_ENABLED_KEY,
        PROXY_ENABLED_KEY,
        DOMAIN_ORDER_KEY,
    )
    private const val DEFAULT_MINIMUM_RESOLUTION = 1080
    private const val DEFAULT_MINIMUM_SEEDERS = 1
    private const val DEFAULT_EXCLUDE_CINEMA_COPIES = true
    private const val MAX_EXCLUDED_TERMS_LENGTH = 500
}

internal object StreamCenterTorrentFilterEngine {
    fun accepts(
        candidate: StreamCenterTorrentCandidate,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): Boolean {
        if (!filters.hasValidBounds()) return false
        val metadataText = releaseMetadataText(
            listOfNotNull(candidate.title, candidate.selectedFileName).joinToString(" "),
            context.titles + listOfNotNull(context.englishTitle, context.japaneseTitle),
        )
        val languageEvidence = StreamCenterTorrentMetadata.languageEvidence(metadataText)
        if (
            filters.language == StreamCenterTorrentLanguageFilter.ONLY_ITALIAN &&
            !languageEvidence.confirmsItalian
        ) {
            return false
        }
        if (filters.excludeCinemaCopies && StreamCenterTorrentMetadata.isCinemaCopy(metadataText)) {
            return false
        }
        if (
            filters.excludedTerms.split(',', ';', '\n')
                .map(::cleanDisplayText)
                .filter(String::isNotBlank)
                .any { term -> containsExcludedTerm(metadataText, term) }
        ) {
            return false
        }
        if (!StreamCenterTorrentVideoCodecDetector.accepts(candidate, filters.blockedVideoCodecs)) {
            return false
        }
        if (filters.minimumResolution > 0) {
            val resolution = StreamCenterTorrentMetadata.resolution(metadataText)
            if (resolution != null && resolution < filters.minimumResolution) return false
            if (
                resolution == null &&
                !StreamCenterTorrentPreferences.isDefaultResolutionFloor(filters.minimumResolution)
            ) {
                return false
            }
        }
        val seeders = candidate.seeders
        filters.minimumSeeders?.let { minimumSeeders ->
            if (seeders != null && seeders < minimumSeeders) return false
            if (
                seeders == null &&
                !StreamCenterTorrentPreferences.isDefaultSeederFloor(minimumSeeders)
            ) {
                return false
            }
        }
        if (filters.maximumSeeders != null && (seeders == null || seeders > filters.maximumSeeders)) {
            return false
        }
        val sizeBytes = candidate.sizeBytes ?: StreamCenterTorrentMetadata.parseSizeBytes(candidate.size)
        if (filters.minimumSizeBytes != null && (sizeBytes == null || sizeBytes < filters.minimumSizeBytes)) {
            return false
        }
        if (filters.maximumSizeBytes != null && (sizeBytes == null || sizeBytes > filters.maximumSizeBytes)) {
            return false
        }
        return true
    }

    fun rank(
        candidate: StreamCenterTorrentCandidate,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): StreamCenterTorrentCandidateRanking {
        val rawMetadata = listOfNotNull(candidate.title, candidate.selectedFileName).joinToString(" ")
        val languageMetadata = releaseMetadataText(
            rawMetadata,
            context.titles + listOfNotNull(context.englishTitle, context.japaneseTitle),
        )
        val relevanceScore = StreamCenterTorrentMatchPolicy.relevanceScore(candidate.title, context)
        val languagePriority = languagePriority(languageMetadata, filters.language)
        val qualityScore = when (StreamCenterTorrentMetadata.resolution(rawMetadata) ?: 0) {
            in 2160..Int.MAX_VALUE -> 60
            in 1080..2159 -> 45
            in 720..1079 -> 30
            else -> 0
        }
        val codecScore = when {
            Regex("""(?i)\b(?:hevc|h\.265|x265|av1)\b""").containsMatchIn(rawMetadata) -> 16
            Regex("""(?i)\b(?:h\.264|x264|avc)\b""").containsMatchIn(rawMetadata) -> 10
            else -> 0
        }
        val releaseFormScore = if (context.episode != null) {
            when {
                StreamCenterTorrentMatchPolicy.isBatchCandidate(candidate.title, context) -> 35
                else -> 85
            }
        } else {
            35
        }
        val sourceScore = StreamCenterExtReleaseSources.byId(candidate.extReleaseSourceId)
            ?.reliabilityWeight
            ?.times(4)
            ?: 0
        val sizeScore = (candidate.sizeBytes ?: StreamCenterTorrentMetadata.parseSizeBytes(candidate.size))
            ?.let { sizeBytes ->
                when {
                    context.isMovie && sizeBytes < 100L * 1_048_576L -> -25
                    context.episode != null && sizeBytes < 30L * 1_048_576L -> -15
                    else -> 8
                }
            }
            ?: 0
        val completenessScore = listOf(
            candidate.sizeBytes != null || !candidate.size.isNullOrBlank(),
            candidate.seeders != null,
            candidate.leechers != null,
            candidate.extReleaseSourceId != null,
            StreamCenterTorrentMetadata.resolution(rawMetadata) != null,
        ).count { present -> present } * 4
        val seeders = candidate.seeders
        val seedScore = seeders
            ?.let { value -> (ln(value.coerceAtLeast(0) + 1.0) * 10.0).toInt().coerceAtMost(60) }
            ?: 0
        val leechScore = if ((seeders ?: 0) > 0) {
            (ln((candidate.leechers ?: 0).coerceAtLeast(0) + 1.0) * 8.0).toInt().coerceAtMost(35)
        } else {
            0
        }
        return StreamCenterTorrentCandidateRanking(
            languagePriority = languagePriority,
            score = relevanceScore * 3 + qualityScore + codecScore + releaseFormScore +
                sourceScore + sizeScore + completenessScore + seedScore + leechScore,
        )
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
        if (titleMatch != null) normalized = normalized.replaceRange(titleMatch.range, " ")
        return cleanDisplayText(normalized)
    }

    private fun languagePriority(
        metadataText: String,
        language: StreamCenterTorrentLanguageFilter,
    ): Int = if (language == StreamCenterTorrentLanguageFilter.PREFER_ITALIAN) {
        StreamCenterTorrentMetadata.languageEvidence(metadataText).priority
    } else {
        0
    }

    private fun normalizeMetadataText(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .let(::cleanDisplayText)

    private fun containsExcludedTerm(metadataText: String, term: String): Boolean {
        val normalizedTerm = normalizeMetadataText(term)
        if (normalizedTerm.isBlank()) return false
        return " $metadataText ".contains(" $normalizedTerm ")
    }

    private data class ExpectedTitleMatch(
        val range: IntRange,
        val length: Int,
    )
}

internal data class StreamCenterTorrentCandidateRanking(
    val languagePriority: Int,
    val score: Int,
)

internal fun StreamCenterTorrentCandidate.isEligibleFor(
    context: StreamCenterTorrentPlaybackContext,
    filters: StreamCenterTorrentFilterSettings,
): Boolean = StreamCenterTorrentMatchPolicy.matches(
    listOfNotNull(title, selectedFileName).joinToString(" "),
    context,
) && StreamCenterTorrentFilterEngine.accepts(this, context, filters)

internal object StreamCenterTorrentMetadata {
    private val italianTokens = setOf("ita", "italian", "italiano", "italiana")
    private val subtitleTokens = setOf("sub", "subs", "subbed", "subtitle", "subtitles")
    private val audioTokens = setOf(
        "audio", "aud", "dub", "dubbing", "doppiaggio", "doppiato", "doppiata",
    )
    private val multiAudioTokens = setOf(
        "multiaudio", "multilang", "multilanguage", "multilingual",
    )
    private val dualAudioTokens = setOf("dualaudio", "duallang", "duallanguage")
    private val audioModeTokens = setOf("audio", "aud", "lang", "language")
    private val multiSubtitleTokens = setOf(
        "multisub", "multisubs", "multisubtitle", "multisubtitles",
    )
    private val languageTokens = italianTokens + setOf(
        "eng", "en", "english", "fra", "fre", "fr", "french",
        "deu", "ger", "de", "german", "spa", "es", "spanish",
        "jpn", "jp", "ja", "japanese", "kor", "ko", "korean",
        "rus", "ru", "russian", "por", "pt", "portuguese",
        "chi", "zho", "zh", "chinese",
    )
    private val italianReleaseHintTokens = setOf("nuita", "mircrew", "novarip", "pir8", "dsmux")
    private val muxTokens = setOf(
        "dlmux", "bdmux", "webmux", "hdtvmux", "hdmux", "dvdmux", "satmux", "sdmux",
    )
    private val muxPrefixes = setOf("dl", "bd", "web", "hdtv", "hd", "dvd", "sat", "sd")
    private val languageSeparatorRegex = Regex("""[^a-z0-9]+""")
    private const val MAX_LANGUAGE_TOKENS_BEFORE_ITALIAN = 3
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
    private val sizeRegex = Regex("""(?i)(\d[\d.,]*)\s*(B|KB|KiB|MB|MiB|GB|GiB|TB|TiB)\b""")
    private val likelyThousandsUnits = setOf("b", "kb", "kib", "mb", "mib")

    fun languageEvidence(metadataText: String): StreamCenterTorrentLanguageEvidence {
        val tokens = metadataText
            .lowercase(Locale.ROOT)
            .replace(languageSeparatorRegex, " ")
            .split(' ')
            .filter(String::isNotBlank)
        return when {
            hasItalianSubtitles(tokens) -> StreamCenterTorrentLanguageEvidence.ITALIAN_SUBTITLES_EXPLICIT
            hasItalianAudio(tokens) -> StreamCenterTorrentLanguageEvidence.ITALIAN_AUDIO_EXPLICIT
            tokens.any { token -> token in italianTokens } ->
                StreamCenterTorrentLanguageEvidence.ITALIAN_EXPLICIT_UNSPECIFIED
            hasMultiSubtitles(tokens) -> StreamCenterTorrentLanguageEvidence.MULTI_SUBTITLES_GENERIC
            hasMultiAudio(tokens) -> StreamCenterTorrentLanguageEvidence.MULTI_AUDIO_GENERIC
            hasDualAudio(tokens) -> StreamCenterTorrentLanguageEvidence.DUAL_AUDIO_GENERIC
            hasItalianReleaseHint(tokens) -> StreamCenterTorrentLanguageEvidence.ITALIAN_RELEASE_HINT
            else -> StreamCenterTorrentLanguageEvidence.NONE
        }
    }

    private fun hasItalianSubtitles(tokens: List<String>): Boolean = tokens.indices.any { index ->
        val token = tokens[index]
        val markerLength = multiSubtitleMarkerLength(tokens, index)
        isConcatenatedItalianSubtitle(token) ||
            (token in subtitleTokens && tokens.getOrNull(index + 1) in italianTokens) ||
            (token in italianTokens && tokens.getOrNull(index + 1) in subtitleTokens) ||
            (markerLength > 0 && tokens.getOrNull(index + markerLength) in italianTokens)
    }

    private fun hasItalianAudio(tokens: List<String>): Boolean = tokens.indices.any { index ->
        val token = tokens[index]
        val markerLength = multiOrDualMarkerLength(tokens, index)
        (markerLength > 0 && tokens.hasItalianLanguageSequence(index + markerLength)) ||
            (token in audioTokens && tokens.getOrNull(index + 1) in italianTokens) ||
            (token in italianTokens && tokens.getOrNull(index + 1) in audioTokens)
    }

    private fun hasMultiSubtitles(tokens: List<String>): Boolean =
        tokens.indices.any { index -> multiSubtitleMarkerLength(tokens, index) > 0 }

    private fun hasMultiAudio(tokens: List<String>): Boolean =
        tokens.indices.any { index -> multiAudioMarkerLength(tokens, index) > 0 }

    private fun hasDualAudio(tokens: List<String>): Boolean =
        tokens.indices.any { index -> dualAudioMarkerLength(tokens, index) > 0 }

    private fun hasItalianReleaseHint(tokens: List<String>): Boolean =
        tokens.any { token -> token in italianReleaseHintTokens || token in muxTokens } ||
            tokens.zipWithNext().any { (prefix, suffix) -> prefix in muxPrefixes && suffix == "mux" }

    private fun isConcatenatedItalianSubtitle(token: String): Boolean = subtitleTokens.any { subtitle ->
        italianTokens.any { italian -> token == subtitle + italian || token == italian + subtitle }
    }

    private fun multiSubtitleMarkerLength(tokens: List<String>, index: Int): Int = when (tokens[index]) {
        in multiSubtitleTokens -> 1
        "multi" -> if (tokens.getOrNull(index + 1) in subtitleTokens) 2 else 0
        else -> 0
    }

    private fun multiAudioMarkerLength(tokens: List<String>, index: Int): Int = when (tokens[index]) {
        in multiAudioTokens -> 1
        "multi" -> if (tokens.getOrNull(index + 1) in audioModeTokens) 2 else 0
        else -> 0
    }

    private fun dualAudioMarkerLength(tokens: List<String>, index: Int): Int = when (tokens[index]) {
        in dualAudioTokens -> 1
        "dual" -> if (tokens.getOrNull(index + 1) in audioModeTokens) 2 else 0
        else -> 0
    }

    private fun multiOrDualMarkerLength(tokens: List<String>, index: Int): Int =
        multiAudioMarkerLength(tokens, index)
            .takeIf { markerLength -> markerLength > 0 }
            ?: dualAudioMarkerLength(tokens, index).takeIf { markerLength -> markerLength > 0 }
            ?: tokens[index].takeIf { token -> token == "multi" || token == "dual" }?.let { 1 }
            ?: 0

    private fun List<String>.hasItalianLanguageSequence(startIndex: Int): Boolean {
        var languageTokensBeforeItalian = 0
        for (index in startIndex until size) {
            val token = this[index]
            if (token in italianTokens) return true
            if (token !in languageTokens || languageTokensBeforeItalian >= MAX_LANGUAGE_TOKENS_BEFORE_ITALIAN) {
                return false
            }
            languageTokensBeforeItalian++
        }
        return false
    }

    fun isCinemaCopy(metadataText: String): Boolean = cinemaCopyRegex.containsMatchIn(metadataText)

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
                if (groups.drop(1).all { group -> group.length == 3 }) null else separators.last().index
            }
            else -> {
                val separatorIndex = separators.single().index
                val trailingDigits = value.length - separatorIndex - 1
                if (trailingDigits == 3 && unit in likelyThousandsUnits) null else separatorIndex
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
