package it.dogior.hadEnough.torrent

import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil

internal object StreamCenterTorrentQueryBuilder {
    private const val MAX_TITLES = 2

    fun build(context: StreamCenterTorrentPlaybackContext): List<String> {
        return prioritizedTitles(context)
            .map(::cleanDisplayText)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_TITLES)
            .flatMap { title -> queriesFor(title, context).asSequence() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
    }

    fun buildForFilters(
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        baseLimit: Int = Int.MAX_VALUE,
    ): List<String> {
        val queries = build(context).take(baseLimit.coerceAtLeast(1))
        if (filters.language == StreamCenterTorrentLanguageFilter.ANY) return queries
        return queries
            .asSequence()
            .flatMap { query -> sequenceOf("$query ita", query) }
            .distinctBy { query -> query.lowercase(Locale.ROOT) }
            .toList()
    }

    fun buildBatchForFilters(
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<String> {
        val queries = batchQueries(context)
        if (filters.language == StreamCenterTorrentLanguageFilter.ANY) return queries
        return queries
            .flatMap { query -> listOf("$query ita", query) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun buildForNyaa(
        definition: StreamCenterTorrentSourceDefinition,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<String> {
        val regularQueries = buildForFilters(context, filters) +
            buildBatchForFilters(context, filters)
        if (definition.key != StreamCenterTorrentSources.SUKEBEI_NYAA_KEY) {
            return regularQueries
        }

        val japaneseQueries = context.japaneseTitle
            ?.let(::cleanDisplayText)
            ?.takeIf(::containsJapaneseScript)
            ?.let { title -> queriesFor(title, context) + batchQueriesFor(title, context) }
            .orEmpty()
        if (japaneseQueries.isEmpty()) return regularQueries

        val prioritizedJapaneseQueries = if (
            filters.language == StreamCenterTorrentLanguageFilter.ANY
        ) {
            japaneseQueries
        } else {
            japaneseQueries.flatMap { query -> listOf(query, "$query ita") }
        }
        return (prioritizedJapaneseQueries + regularQueries)
            .distinctBy { query -> query.lowercase(Locale.ROOT) }
    }

    fun buildItalianVariants(
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        limit: Int,
    ): List<String> {
        val preferItalian = filters.language != StreamCenterTorrentLanguageFilter.ANY
        val normalizedLimit = limit.coerceAtLeast(1)
        val queries = build(context)
        if (preferItalian) {
            return queries
                .asSequence()
                .take(normalizedLimit)
                .flatMap { query -> sequenceOf("$query ita", query) }
                .distinctBy { query -> query.lowercase(Locale.ROOT) }
                .toList()
        }
        return queries
            .asSequence()
            .flatMap { query -> sequenceOf(query, "$query ita") }
            .distinctBy { query -> query.lowercase(Locale.ROOT) }
            .take(normalizedLimit)
            .toList()
    }

    fun buildBatchItalianVariants(
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
        limit: Int,
    ): List<String> {
        val queries = batchQueries(context).take(limit.coerceAtLeast(1))
        if (filters.language == StreamCenterTorrentLanguageFilter.ANY) return queries
        return queries
            .flatMap { query -> listOf("$query ita", query) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun queriesFor(
        title: String,
        context: StreamCenterTorrentPlaybackContext,
    ): List<String> {
        val episode = context.episode
        if (episode != null) {
            val season = context.season ?: 1
            val seasonEpisode =
                "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
            return if (context.isAnime) {
                buildList {
                    context.episodeNumbersForSearch().forEach { number ->
                        add("$title ${number.toString().padStart(2, '0')}")
                    }
                    add("$title $seasonEpisode")
                }
            } else {
                listOf("$title $seasonEpisode")
            }
        }

        val year = context.year
        return listOf(
            if (year != null && !containsYear(title, year)) "$title $year" else title,
        )
    }

    private fun batchQueriesFor(
        title: String,
        context: StreamCenterTorrentPlaybackContext,
    ): List<String> {
        if (context.episode == null || context.isMovie) return emptyList()
        val season = context.season ?: 1
        return if (context.isAnime) {
            listOf("$title batch", "$title complete", title)
        } else {
            listOf(
                "$title S${season.toString().padStart(2, '0')}",
                "$title season $season",
                title,
            )
        }
    }

    private fun containsYear(value: String, year: Int): Boolean =
        Regex("""(?:^|\D)$year(?:\D|$)""").containsMatchIn(value)

    private fun batchQueries(context: StreamCenterTorrentPlaybackContext): List<String> {
        val queriesByTitle = prioritizedTitles(context)
            .map(::cleanDisplayText)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_TITLES)
            .map { title -> batchQueriesFor(title, context) }
            .toList()
        val maximumQueries = queriesByTitle.maxOfOrNull { queries -> queries.size }
            ?: return emptyList()
        return buildList {
            for (index in 0 until maximumQueries) {
                queriesByTitle.forEach { queries ->
                    queries.getOrNull(index)?.let(::add)
                }
            }
        }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun prioritizedTitles(
        context: StreamCenterTorrentPlaybackContext,
    ): Sequence<String> = sequence {
        context.titles.firstOrNull()?.let { yield(it) }
        context.englishTitle?.let { yield(it) }
        context.titles.drop(1).forEach { yield(it) }
    }
}

internal object StreamCenterTorrentMatchPolicy {
    private val yearRegex = Regex("""(?:19|20)\d{2}""")
    private val episodeMarkerRegex = Regex(
        """(?i)(?:s\d{1,3}[._ -]*)?e(?:p(?:isode)?)?[._ -]*0*(\d{1,4})(?:v\d+)?""",
    )
    private val episodeRangeRegex = Regex(
        """(?i)(?:(e(?:p(?:isodes?)?)?)[._ -]*)?0*(\d{1,4})\s*[-~+&]\s*(?:e(?:p)?[._ -]*)?0*(\d{1,4})(?=$|[^0-9])(?!\s*p\b)""",
    )
    private val bracketEpisodeListRegex = Regex(
        """[\[(]\s*(?:(?:e|ep)?0*\d{1,3}[\s,;+&]*){2,}[\])]""",
        RegexOption.IGNORE_CASE,
    )
    private val packRegex = Regex(
        """
        \b(?:batch|complete|completo)\b
        |
        (?:全集|全話|全\s*\d{1,3}\s*話)
        |
        \b(?:collection|collezione|trilogy|trilogia|quadrilogy|filmography)\b
        |
        \ball\s+(?:episodes?|seasons?|movies?|films?)\b
        |
        \b(?:episodes?|eps?)\s*0*\d{1,3}\s*[-~+&]\s*0*\d{1,3}\b
        |
        (?:^|[\s._\-\[(])0*\d{1,3}\s*[-~+&]\s*0*\d{1,3}(?:[\s._\-\])]|$)
        |
        [\[(]\s*0*\d{1,3}\s*[-~+&]\s*0*\d{1,3}\s*[\])]
        |
        \b(?:s\d{1,2})?e\d{1,3}\s*[-~+&]\s*(?:s\d{1,2})?e?\d{1,3}\b
        |
        (?:第\s*)?\d{1,3}\s*話?\s*[-~〜～－—]\s*(?:第\s*)?\d{1,3}\s*話
        """.trimIndent(),
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    )

    fun matches(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        if (!matchesAnyTitle(candidateTitle, context)) return false
        if (!matchesYear(candidateTitle, context.year)) return false

        val episode = context.episode
        if (episode == null) {
            return context.isMovie || !context.isAnime
        }
        if (isBatchCandidate(candidateTitle, context)) {
            return explicitEpisodeMatch(candidateTitle, context) != false
        }

        return if (context.isAnime) {
            context.episodeNumbersForSearch().any { number ->
                matchesAnimeEpisode(candidateTitle, number)
            } || matchesGeneralEpisode(candidateTitle, context.season ?: 1, episode)
        } else {
            matchesGeneralEpisode(candidateTitle, context.season ?: 1, episode)
        }
    }

    fun isBatchCandidate(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        if (context.episode == null) return false
        val hasPackMarker = hasUnexpectedPackMarker(
            candidateTitle,
            context.titles + listOfNotNull(context.japaneseTitle),
        )
        val episodeMarkers = episodeMarkerRegex.findAll(normalizeCompatibility(candidateTitle))
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .distinct()
            .take(2)
            .count()
        return hasPackMarker || episodeMarkers >= 2 ||
            bracketEpisodeListRegex.containsMatchIn(normalizeCompatibility(candidateTitle))
    }

    private fun explicitEpisodeMatch(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean? {
        val targetNumbers = context.episodeNumbersForSearch().toSet()
        if (targetNumbers.isEmpty()) return null
        val normalized = normalizeCompatibility(candidateTitle)
        val explicitNumbers = episodeMarkerRegex.findAll(normalized)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .toSet()
        val ranges = episodeRangeRegex.findAll(normalized).mapNotNull { match ->
            val marked = match.groupValues.getOrNull(1).orEmpty().isNotBlank()
            val start = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
            if (!marked && maxOf(start, end) > MAX_UNMARKED_EPISODE_NUMBER) {
                return@mapNotNull null
            }
            minOf(start, end)..maxOf(start, end)
        }.toList()
        val bracketNumbers = bracketEpisodeListRegex.findAll(normalized)
            .flatMap { match -> Regex("""\d{1,3}""").findAll(match.value) }
            .mapNotNull { match -> match.value.toIntOrNull() }
            .toSet()
        if (explicitNumbers.isEmpty() && ranges.isEmpty() && bracketNumbers.isEmpty()) return null
        return targetNumbers.any { target ->
            target in explicitNumbers || target in bracketNumbers || ranges.any { target in it }
        }
    }

    private fun hasUnexpectedPackMarker(
        candidateTitle: String,
        expectedTitles: List<String>,
    ): Boolean {
        val candidateMarkers = packRegex.findAll(normalizeCompatibility(candidateTitle))
            .map { match -> normalizeUnicodeForMatch(match.value) }
            .filter(String::isNotBlank)
            .toSet()
        if (candidateMarkers.isEmpty()) return false
        val expectedMarkers = expectedTitles
            .asSequence()
            .flatMap { title -> packRegex.findAll(normalizeCompatibility(title)) }
            .map { match -> normalizeUnicodeForMatch(match.value) }
            .filter(String::isNotBlank)
            .toSet()
        return candidateMarkers.any { marker -> marker !in expectedMarkers }
    }

    private fun matchesAnyTitle(
        releaseTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        return matchesLatinTitle(releaseTitle, context.titles) ||
            matchesJapaneseTitle(releaseTitle, context.japaneseTitle)
    }

    private fun matchesLatinTitle(releaseTitle: String, titles: List<String>): Boolean {
        val release = normalizeForMatch(releaseTitle)
        if (release.isBlank()) return false

        return titles.asSequence()
            .map(::normalizeForMatch)
            .filter(String::isNotBlank)
            .any { expected ->
                if (containsPhrase(release, expected)) return@any true

                val expectedTokens = expected.split(' ')
                    .filter { token -> token.length >= 2 || token.all(Char::isDigit) }
                    .distinct()
                if (expectedTokens.isEmpty()) return@any false

                val releaseTokens = release.split(' ').toHashSet()
                val matched = expectedTokens.count(releaseTokens::contains)
                val required = ceil(expectedTokens.size * 0.75).toInt().coerceAtLeast(1)
                matched >= required
            }
    }

    private fun matchesJapaneseTitle(
        releaseTitle: String,
        japaneseTitle: String?,
    ): Boolean {
        val expected = japaneseTitle
            ?.takeIf(::containsJapaneseScript)
            ?.let(::normalizeUnicodeForMatch)
            ?.takeIf(String::isNotBlank)
            ?: return false
        val release = normalizeUnicodeForMatch(releaseTitle)
        return release.contains(expected)
    }

    private fun containsPhrase(text: String, phrase: String): Boolean =
        " $text ".contains(" $phrase ")

    private fun matchesYear(releaseTitle: String, expectedYear: Int?): Boolean {
        if (expectedYear == null) return true
        val years = yearRegex.findAll(releaseTitle)
            .mapNotNull { match -> match.value.toIntOrNull() }
            .toSet()
        return years.isEmpty() || expectedYear in years
    }

    private fun matchesGeneralEpisode(title: String, season: Int, episode: Int): Boolean {
        val compatibleTitle = normalizeCompatibility(title)
        val seasonValue = season.toString()
        val episodeValue = episode.toString()
        val markers = listOf(
            Regex(
                """(?i)(?:^|[^a-z0-9])s0*$seasonValue[._ -]*e(?:p)?0*$episodeValue(?:[^0-9]|$)""",
            ),
            Regex(
                """(?i)(?:^|[^0-9])0*$seasonValue[._ -]*x[._ -]*0*$episodeValue(?:[^0-9]|$)""",
            ),
        )
        return markers.any { marker -> marker.containsMatchIn(compatibleTitle) }
    }

    private fun matchesAnimeEpisode(title: String, episode: Int): Boolean {
        val compatibleTitle = normalizeCompatibility(title)
        val value = episode.toString()
        val markers = listOf(
            Regex("""(?i)(?:^|[^a-z0-9])e(?:p(?:isode)?)?[._ -]*0*$value(?:v\d+)?(?:[^0-9]|$)"""),
            Regex("""(?i)(?:^|[\s_\-\[(])0*$value(?:v\d+)?(?:[\s_\-\])]|$)"""),
            Regex("""(?:^|[^0-9])(?:第\s*)?0*$value\s*話(?:v\d+)?(?:[^0-9]|$)"""),
        )
        return markers.any { marker -> marker.containsMatchIn(compatibleTitle) }
    }

    private const val MAX_UNMARKED_EPISODE_NUMBER = 600
}

internal object StreamCenterTorrentMagnet {
    private val infoHashRegex = Regex(
        """(?i)(?:urn:btih:)?([a-f0-9]{40}|[a-z2-7]{32})(?:[^a-z0-9]|$)""",
    )
    private val exactBtihRegex = Regex(
        """(?i)^urn:btih:([a-f0-9]{40}|[a-z2-7]{32})$""",
    )
    private val fallbackTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
    )

    fun infoHash(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            return magnetParameters(trimmed)
                .asSequence()
                .filter { (key, _) -> key.equals("xt", ignoreCase = true) }
                .mapNotNull { (_, parameterValue) ->
                    exactBtihRegex.matchEntire(parameterValue.trim())
                        ?.groupValues
                        ?.getOrNull(1)
                }
                .firstOrNull()
                ?.let(::normalizeInfoHash)
        }
        return infoHashRegex.find(decode(trimmed))
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeInfoHash)
    }

    fun build(candidate: StreamCenterTorrentCandidate): String? {
        val suppliedMagnet = candidate.magnetUrl
        val hash = infoHash(suppliedMagnet) ?: infoHash(candidate.infoHash) ?: return null
        val suppliedParameters = magnetParameters(suppliedMagnet)
        val fileIndex = candidate.fileIndex ?: fileIndex(suppliedMagnet)
        val webSources = suppliedParameters
            .asSequence()
            .filter { (key, _) ->
                key.equals("ws", ignoreCase = true) ||
                    key.equals("xs", ignoreCase = true) ||
                    key.equals("as", ignoreCase = true)
            }
            .map { (key, value) -> key.lowercase(Locale.ROOT) to value }
            .filter { (_, value) -> value.isNotBlank() }
            .distinct()
            .take(10)
            .toList()
        val trackers = suppliedParameters
            .asSequence()
            .filter { (key, _) -> key.equals("tr", ignoreCase = true) }
            .map { (_, value) -> value }
            .filter(String::isNotBlank)
            .distinct()
            .take(20)
            .toList()
            .ifEmpty { fallbackTrackers }

        return buildList {
            add("xt=urn:btih:$hash")
            add("dn=${encode(candidate.selectedFileName ?: candidate.title)}")
            fileIndex?.let { index -> add("index=$index") }
            webSources.forEach { (key, value) -> add("$key=${encode(value)}") }
            trackers.forEach { tracker -> add("tr=${encode(tracker)}") }
        }.joinToString(separator = "&", prefix = "magnet:?")
    }

    private fun normalizeInfoHash(value: String): String {
        return if (value.length == 40) {
            value.lowercase(Locale.ROOT)
        } else {
            value.uppercase(Locale.ROOT)
        }
    }

    fun fileIndex(value: String?): Int? {
        return magnetParameters(value)
            .asSequence()
            .filter { (key, _) ->
                key.equals("index", ignoreCase = true) ||
                    key.equals("so", ignoreCase = true) ||
                    key.equals("fileIdx", ignoreCase = true)
            }
            .mapNotNull { (_, parameterValue) -> parameterValue.trim().toIntOrNull() }
            .firstOrNull { index -> index >= 0 }
    }

    private fun magnetParameters(value: String?): List<Pair<String, String>> {
        if (value.isNullOrBlank()) return emptyList()
        return value.substringAfter('?', "")
            .split('&')
            .mapNotNull { parameter ->
                val rawKey = parameter.substringBefore('=')
                if (rawKey.isBlank()) return@mapNotNull null
                decode(rawKey) to decode(parameter.substringAfter('=', ""))
            }
    }
}

internal fun qualityFromTorrentTitle(title: String): Int {
    return StreamCenterTorrentMetadata.resolution(title)
        ?: Qualities.Unknown.value
}

internal fun encodeTorrentPathValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun cleanTorrentBaseUrl(value: String): String =
    value.trim().substringBefore('#').substringBefore('?').trimEnd('/')

internal fun torrentUrlQueryParameter(value: String, name: String): String? {
    return value.substringAfter('?', "")
        .substringBefore('#')
        .split('&')
        .firstOrNull { parameter ->
            decode(parameter.substringBefore('=')).equals(name, ignoreCase = true)
        }
        ?.substringAfter('=', "")
        ?.let(::decode)
        ?.trim()
        ?.takeIf(String::isNotBlank)
}

internal fun torrentSourceBaseUrls(
    definition: StreamCenterTorrentSourceDefinition,
    sourceUrl: String,
    fallbackDefaults: List<String>,
): List<String> {
    val configured = cleanTorrentBaseUrl(sourceUrl)
    if (configured.isBlank()) return emptyList()
    val usesDefault = configured.equals(
        cleanTorrentBaseUrl(definition.displayUrl),
        ignoreCase = true,
    )
    return buildList {
        add(configured)
        if (usesDefault) addAll(fallbackDefaults.map(::cleanTorrentBaseUrl))
    }
        .filter(String::isNotBlank)
        .distinctBy { url -> url.lowercase(Locale.ROOT) }
}

internal fun cleanDisplayText(value: String): String =
    value.replace(Regex("""\s+"""), " ").trim()

internal fun parsePositiveInt(value: Any?): Int? {
    val parsed = when (value) {
        is Number -> value.toInt()
        null -> null
        else -> value.toString()
            .replace(",", "")
            .trim()
            .toIntOrNull()
    }
    return parsed?.takeIf { it >= 0 }
}

internal fun formatTorrentBytes(value: Long): String {
    if (value <= 0L) return value.toString()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unit = 0
    while (amount >= 1024.0 && unit < units.lastIndex) {
        amount /= 1024.0
        unit++
    }
    val decimals = if (amount >= 10.0 || unit == 0) 0 else 1
    return String.format(Locale.ROOT, "%.${decimals}f %s", amount, units[unit])
}

private fun normalizeForMatch(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
    return cleanDisplayText(normalized)
}

internal fun containsJapaneseScript(value: String?): Boolean =
    value?.let(::normalizeCompatibility)?.any { character ->
        character in '\u3040'..'\u30ff' ||
            character in '\u31f0'..'\u31ff' ||
            character in '\u3400'..'\u4dbf' ||
            character in '\u4e00'..'\u9fff' ||
            character in '\uf900'..'\ufaff'
    } == true

private fun normalizeCompatibility(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)

private fun normalizeUnicodeForMatch(value: String): String =
    normalizeCompatibility(value)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
        .getOrDefault(value)
