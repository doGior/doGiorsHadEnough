package it.dogior.hadEnough.torrent

import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil

internal object StreamCenterTorrentMatchPolicy {
    private val yearRegex = Regex("""(?:19|20)\d{2}""")
    private val episodeMarkerRegex = Regex(
        """(?i)(?<![a-z0-9])(?:s\d{1,3}[._ -]*)?e(?:p(?:isode)?)?[._ -]*0*(\d{1,4})(?:v\d+)?""",
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
        if (context.isAnime && hasConflictingAnimePartMarker(candidateTitle, context)) return false
        val batchCandidate = isBatchCandidate(candidateTitle, context)
        if (
            context.isAnime &&
            hasConflictingAnimeSeriesMarker(candidateTitle, context) &&
            !hasExplicitCanonicalEpisodeMatch(candidateTitle, context) &&
            !batchCandidate
        ) {
            return false
        }
        if (!context.isAnime && hasConflictingSeason(candidateTitle, context)) return false
        if (context.isMovie && !matchesYear(candidateTitle, context.year)) return false

        val episode = context.episode
        if (episode == null) {
            return context.isMovie || !context.isAnime
        }
        if (batchCandidate) {
            return explicitEpisodeMatch(candidateTitle, context) != false
        }

        return if (context.isAnime) {
            animeEpisodeEvidenceScore(candidateTitle, context) > 0
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
            context.latinTitles() + listOfNotNull(context.japaneseTitle),
        )
        val episodeMarkers = episodeMarkerRegex.findAll(normalizeCompatibility(candidateTitle))
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .distinct()
            .take(2)
            .count()
        return hasPackMarker ||
            episodeMarkers >= 2 ||
            bracketEpisodeListRegex.containsMatchIn(normalizeCompatibility(candidateTitle)) ||
            isSeasonPack(candidateTitle, context) ||
            isAnimePartPack(candidateTitle, context)
    }

    fun relevanceScore(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Int {
        val titleScore = when {
            matchesJapaneseTitle(candidateTitle, context.japaneseTitle) -> 210
            hasExactLatinTitle(candidateTitle, context.latinTitles()) -> 195
            matchesLatinTitle(candidateTitle, context.latinTitles()) -> 135
            matchesAnimeBaseTitle(candidateTitle, context) -> 100
            else -> 0
        }
        val yearScore = context.year?.let { expectedYear ->
            if (yearRegex.findAll(candidateTitle).any { match -> match.value.toIntOrNull() == expectedYear }) 30 else 0
        } ?: 0
        val episodeScore = context.episode?.let { episode ->
            when {
                isBatchCandidate(candidateTitle, context) -> 45
                context.isAnime && animeEpisodeEvidenceScore(candidateTitle, context) > 0 -> 120
                matchesGeneralEpisode(candidateTitle, context.season ?: 1, episode) -> 120
                else -> 0
            }
        } ?: 35
        return titleScore + yearScore + episodeScore
    }

    fun hasConflictingSeason(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean = seasonMarkerCompatibility(candidateTitle, context) == false

    fun seasonMarkerCompatibility(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean? {
        if (context.isAnime || context.episode == null) return null
        val expectedSeason = context.season?.takeIf { value -> value > 0 } ?: 1
        val candidateSeasons = tvSeasonMarkers(candidateTitle)
        if (candidateSeasons.isEmpty) return null
        return expectedSeason in candidateSeasons
    }

    private fun explicitEpisodeMatch(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean? {
        val targetNumbers = if (context.isAnime) {
            eligibleAnimeCoordinates(candidateTitle, context)
                .mapTo(linkedSetOf(), StreamCenterTorrentEpisodeCoordinate::episode)
        } else {
            context.episodeNumbersForSearch().toSet()
        }
        if (targetNumbers.isEmpty()) return null
        val normalized = normalizeCompatibility(candidateTitle)
        val withoutSeasonRanges = tvSeasonRangeRegex.replace(normalized, " ")
        val explicitNumbers = episodeMarkerRegex.findAll(withoutSeasonRanges)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .toSet()
        val ranges = episodeRangeRegex.findAll(withoutSeasonRanges).mapNotNull { match ->
            val marked = match.groupValues.getOrNull(1).orEmpty().isNotBlank()
            val start = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
            if (!marked && maxOf(start, end) > MAX_UNMARKED_EPISODE_NUMBER) {
                return@mapNotNull null
            }
            minOf(start, end)..maxOf(start, end)
        }.toList()
        val bracketNumbers = bracketEpisodeListRegex.findAll(withoutSeasonRanges)
            .flatMap { match -> Regex("""\d{1,3}""").findAll(match.value) }
            .mapNotNull { match -> match.value.toIntOrNull() }
            .toSet()
        if (explicitNumbers.isEmpty() && ranges.isEmpty() && bracketNumbers.isEmpty()) return null
        return targetNumbers.any { target ->
            target in explicitNumbers || target in bracketNumbers || ranges.any { target in it }
        }
    }

    internal fun animeEpisodeEvidenceScore(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
        markerSource: String = candidateTitle,
    ): Int {
        if (!context.isAnime || context.episode == null) return 0
        val normalizedCandidate = normalizeCompatibility(candidateTitle)
        val normalizedMarkers = normalizeCompatibility(markerSource)
        if (hasConflictingAnimePartMarker(normalizedMarkers, context)) return 0
        val hasCanonicalRenumbering = context.hasCanonicalEpisodeRenumbering()

        return context.episodeCoordinatesForSearch().maxOfOrNull { coordinate ->
            animeCoordinateScore(
                candidateTitle = normalizedCandidate,
                markerSource = normalizedMarkers,
                coordinate = coordinate,
                context = context,
                hasCanonicalRenumbering = hasCanonicalRenumbering,
            )
        } ?: 0
    }

    private fun hasExplicitCanonicalEpisodeMatch(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        if (!context.isAnime || context.episode == null) return false
        val normalized = normalizeCompatibility(candidateTitle)
        return context.episodeCoordinatesForSearch()
            .asSequence()
            .filter { coordinate -> coordinate.isCanonical }
            .any { coordinate ->
                animeCoordinateScore(
                    candidateTitle = normalized,
                    markerSource = normalized,
                    coordinate = coordinate,
                    context = context,
                    hasCanonicalRenumbering = context.hasCanonicalEpisodeRenumbering(),
                ) > 0
            }
    }

    private fun eligibleAnimeCoordinates(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): List<StreamCenterTorrentEpisodeCoordinate> {
        val normalized = normalizeCompatibility(candidateTitle)
        val hasCanonicalRenumbering = context.hasCanonicalEpisodeRenumbering()
        val hasSeasonMarker = !tvSeasonMarkers(normalized).isEmpty
        val localPartCompatible = hasCompatibleLocalPartMarker(normalized, context)
        return context.episodeCoordinatesForSearch().filter { coordinate ->
            when (coordinate.kind) {
                StreamCenterTorrentEpisodeCoordinateKind.LOCAL ->
                    !hasCanonicalRenumbering || localPartCompatible
                StreamCenterTorrentEpisodeCoordinateKind.ABSOLUTE -> !hasSeasonMarker
                StreamCenterTorrentEpisodeCoordinateKind.SEASON,
                StreamCenterTorrentEpisodeCoordinateKind.LEGACY,
                -> true
            }
        }
    }

    private fun animeCoordinateScore(
        candidateTitle: String,
        markerSource: String,
        coordinate: StreamCenterTorrentEpisodeCoordinate,
        context: StreamCenterTorrentPlaybackContext,
        hasCanonicalRenumbering: Boolean,
    ): Int {
        val episode = coordinate.episode.takeIf { it > 0 } ?: return 0
        if (hasConflictingAnimePartMarker(markerSource, context)) return 0
        val candidateSeasons = tvSeasonMarkers(markerSource)
        if (
            coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.ABSOLUTE &&
            !candidateSeasons.isEmpty
        ) {
            return 0
        }
        if (
            coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.LOCAL &&
            hasCanonicalRenumbering &&
            !hasCompatibleLocalPartMarker(markerSource, context)
        ) {
            return 0
        }

        val exactSeasonEpisode = coordinate.season?.let { season ->
            matchesStructuredAnimeEpisode(candidateTitle, season, episode)
        } == true
        val anySeasonEpisode = matchesStructuredAnimeEpisode(candidateTitle, null, episode)
        val markedEpisode = matchesStandaloneAnimeEpisode(candidateTitle, episode)
        val bareEpisode = matchesBareAnimeEpisode(candidateTitle, episode)
        if (!exactSeasonEpisode && !anySeasonEpisode && !markedEpisode && !bareEpisode) return 0

        return when (coordinate.kind) {
            StreamCenterTorrentEpisodeCoordinateKind.SEASON -> when {
                exactSeasonEpisode -> 180
                anySeasonEpisode -> 166
                markedEpisode -> 158
                else -> 144
            }
            StreamCenterTorrentEpisodeCoordinateKind.ABSOLUTE -> when {
                markedEpisode -> 156
                bareEpisode -> 142
                else -> 0
            }
            StreamCenterTorrentEpisodeCoordinateKind.LEGACY -> when {
                anySeasonEpisode -> 134
                markedEpisode -> 130
                else -> 118
            }
            StreamCenterTorrentEpisodeCoordinateKind.LOCAL -> when {
                exactSeasonEpisode -> if (hasCanonicalRenumbering) 124 else 150
                anySeasonEpisode -> 0
                markedEpisode -> if (hasCanonicalRenumbering) 116 else 140
                else -> if (hasCanonicalRenumbering) 104 else 126
            }
        }
    }

    private fun matchesStructuredAnimeEpisode(
        value: String,
        expectedSeason: Int?,
        expectedEpisode: Int,
    ): Boolean {
        val season = expectedSeason?.toString() ?: "\\d{1,3}"
        val episode = expectedEpisode.toString()
        return listOf(
            Regex(
                """(?i)(?:^|[^a-z0-9])(?:s|season|stagione|serie|series)[._ -]*0*$season[._ -]*(?:e(?:p(?:isode)?)?|episodio)[._ -]*0*$episode(?:v\d+)?(?:[^0-9]|$)""",
            ),
            Regex(
                """(?i)(?:^|[^0-9])0*$season[._ -]*x[._ -]*0*$episode(?:[^0-9]|$)""",
            ),
        ).any { regex -> regex.containsMatchIn(value) }
    }

    private fun matchesStandaloneAnimeEpisode(
        value: String,
        expectedEpisode: Int,
    ): Boolean {
        val episode = expectedEpisode.toString()
        return listOf(
            Regex(
                """(?i)(?:^|[^a-z0-9])(?:e(?:p(?:isode)?)?|episodio)[._ -]*0*$episode(?:v\d+)?(?:[^0-9]|$)""",
            ),
            Regex(
                """(?:^|[^0-9])(?:\u7b2c\s*)?0*$episode\s*\u8a71(?:v\d+)?(?:[^0-9]|$)""",
            ),
        ).any { regex -> regex.containsMatchIn(value) }
    }

    private fun matchesBareAnimeEpisode(
        value: String,
        expectedEpisode: Int,
    ): Boolean {
        val episode = expectedEpisode.toString()
        val episodeOnlyValue = decimalNoiseRegex.replace(
            tvSeasonMarkerRegex.replace(
                animePartNumberRegex.replace(value, " "),
                " ",
            ),
            " ",
        )
        return Regex(
            """(?i)(?:^|[\s._\-\[(])0*$episode(?:v\d+)?(?=$|[\s._\-\])])""",
        ).containsMatchIn(episodeOnlyValue)
    }

    private fun StreamCenterTorrentPlaybackContext.hasCanonicalEpisodeRenumbering(): Boolean {
        val local = episodeCoordinatesForSearch()
            .firstOrNull { coordinate ->
                coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.LOCAL
            } ?: return false
        return episodeCoordinatesForSearch().any { coordinate ->
            coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.SEASON &&
                (coordinate.episode != local.episode || coordinate.season != local.season)
        }
    }

    private val StreamCenterTorrentEpisodeCoordinate.isCanonical: Boolean
        get() = kind == StreamCenterTorrentEpisodeCoordinateKind.SEASON ||
            kind == StreamCenterTorrentEpisodeCoordinateKind.ABSOLUTE

    private fun hasCompatibleLocalPartMarker(
        value: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        val expectedParts = expectedAnimeSeriesMarkers(context).parts
        if (expectedParts.isEmpty()) return false
        val candidateParts = nearestAnimePartMarkers(value)
        return candidateParts.isNotEmpty() &&
            candidateParts.intersect(expectedParts).isNotEmpty()
    }

    private fun nearestAnimePartMarkers(value: String): Set<Int> = value
        .split('/', '\\')
        .asReversed()
        .firstNotNullOfOrNull { component ->
            animeSeriesMarkers(component).parts.takeIf(Set<Int>::isNotEmpty)
        }
        .orEmpty()

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
        return matchesLatinTitle(releaseTitle, context.latinTitles()) ||
            matchesJapaneseTitle(releaseTitle, context.japaneseTitle) ||
            matchesAnimeBaseTitle(releaseTitle, context)
    }

    private fun StreamCenterTorrentPlaybackContext.latinTitles(): List<String> =
        titles + listOfNotNull(englishTitle)

    private fun matchesAnimeBaseTitle(
        releaseTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        if (!context.isAnime) return false
        val baseTitles = context.latinTitles()
            .map(::titleBase)
            .filter(::hasUsefulTitleTokens)
            .distinctBy { title -> normalizeForMatch(title) }
        return baseTitles.isNotEmpty() && matchesLatinTitle(releaseTitle, baseTitles)
    }

    private fun titleBase(value: String): String = cleanDisplayText(
        value
            .substringBefore(':')
            .substringBefore('(')
            .replace(titlePartSuffixRegex, " "),
    )

    private fun hasUsefulTitleTokens(value: String): Boolean = normalizeForMatch(value)
        .split(' ')
        .count { token -> token.length >= 2 } >= 2

    private fun hasConflictingAnimeSeriesMarker(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean = expectedAnimeSeriesMarkers(context)
        .takeUnless(AnimeSeriesMarkers::isEmpty)
        ?.conflictsWith(animeSeriesMarkers(candidateTitle))
        ?: false

    private fun hasConflictingAnimePartMarker(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        val expectedParts = expectedAnimeSeriesMarkers(context).parts
        val candidateParts = nearestAnimePartMarkers(candidateTitle)
        return expectedParts.isNotEmpty() &&
            candidateParts.isNotEmpty() &&
            expectedParts.intersect(candidateParts).isEmpty()
    }

    private fun expectedAnimeSeriesMarkers(
        context: StreamCenterTorrentPlaybackContext,
    ): AnimeSeriesMarkers {
        val titleMarkers = context.latinTitles().fold(AnimeSeriesMarkers()) { markers, title ->
            markers + animeSeriesMarkers(title)
        }
        return if (titleMarkers.seasons.isNotEmpty()) {
            titleMarkers
        } else {
            titleMarkers.copy(
                seasons = context.season
                    ?.takeIf { season -> season > 0 }
                    ?.let(::setOf)
                    .orEmpty(),
            )
        }
    }

    private fun animeSeriesMarkers(value: String): AnimeSeriesMarkers = AnimeSeriesMarkers(
        seasons = seasonTitleMarkerRegex.findAll(normalizeCompatibility(value))
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { number -> number > 0 }
            .toSet(),
        parts = animePartNumberRegex.findAll(normalizeCompatibility(value))
            .mapNotNull { match ->
                match.groupValues
                    .drop(1)
                    .firstOrNull(String::isNotBlank)
                    ?.toIntOrNull()
            }
            .filter { number -> number > 0 }
            .toSet(),
    )

    private fun matchesLatinTitle(releaseTitle: String, titles: List<String>): Boolean {
        val release = normalizeForMatch(releaseTitle)
        if (release.isBlank()) return false

        return titles.asSequence()
            .map(::normalizeForMatch)
            .filter(String::isNotBlank)
            .any { expected ->
                if (containsPhrase(release, expected)) return@any true

                val expectedTokens = expected.split(' ')
                    .filter { token ->
                        token !in TITLE_MATCH_STOP_WORDS &&
                            (token.length >= 2 || token.all(Char::isDigit))
                    }
                    .distinct()
                if (expectedTokens.isEmpty()) return@any false

                val releaseTokens = release.split(' ')
                    .filterNot(TITLE_MATCH_STOP_WORDS::contains)
                    .toHashSet()
                val matched = expectedTokens.count(releaseTokens::contains)
                val required = ceil(expectedTokens.size * 0.75).toInt().coerceAtLeast(1)
                matched >= required
            }
    }

    private fun hasExactLatinTitle(releaseTitle: String, titles: List<String>): Boolean {
        val release = normalizeForMatch(releaseTitle)
        if (release.isBlank()) return false
        return titles.asSequence()
            .map(::normalizeForMatch)
            .filter(String::isNotBlank)
            .any { expected -> containsPhrase(release, expected) }
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
        val markers = buildList {
            add(Regex(
                """(?i)(?:^|[^a-z0-9])s0*$seasonValue[._ -]*e(?:p)?0*$episodeValue(?:[^0-9]|$)""",
            ))
            add(Regex(
                """(?i)(?:^|[^0-9])0*$seasonValue[._ -]*x[._ -]*0*$episodeValue(?:[^0-9]|$)""",
            ))
            if (season == 1 || season in tvSeasonMarkers(compatibleTitle)) {
                add(Regex(
                    """(?i)(?:^|[^a-z0-9])e(?:p(?:isode)?)?[._ -]*0*$episodeValue(?:v\d+)?(?:[^0-9]|$)""",
                ))
            }
            if (season in 1..9 && episode in 1..99) {
                val compactEpisodeValue = (season * 100 + episode).toString()
                add(Regex(
                    """(?i)(?:^|[^0-9])0*$compactEpisodeValue(?:[^0-9]|$)""",
                ))
            }
        }
        return markers.any { marker -> marker.containsMatchIn(compatibleTitle) }
    }

    private fun isSeasonPack(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        val seasons = context.seasonNumbersForMatch()
        if (seasons.isEmpty()) return false
        val episodes = context.episodeNumbersForSearch()
        if (seasons.any { season ->
                episodes.any { episode -> matchesGeneralEpisode(candidateTitle, season, episode) }
            }
        ) {
            return false
        }
        val normalized = normalizeCompatibility(candidateTitle)
        val markers = tvSeasonMarkers(normalized)
        return seasons.any { season -> season in markers }
    }

    private fun tvSeasonMarkers(value: String): TvSeasonMarkers {
        val normalized = normalizeCompatibility(value)
        val ranges = tvSeasonRangeRegex.findAll(normalized)
            .mapNotNull { match ->
                val bounds = match.groupValues
                    .drop(1)
                    .filter(String::isNotBlank)
                    .mapNotNull(String::toIntOrNull)
                if (bounds.size != 2 || bounds.any { season -> season <= 0 }) {
                    return@mapNotNull null
                }
                minOf(bounds[0], bounds[1])..maxOf(bounds[0], bounds[1])
            }
            .toList()
        val seasons = tvSeasonMarkerRegex.findAll(normalized)
            .mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull(String::isNotBlank)?.toIntOrNull()
            }
            .filter { season -> season > 0 }
            .toSet()
        return TvSeasonMarkers(seasons = seasons, ranges = ranges)
    }

    private fun StreamCenterTorrentPlaybackContext.seasonNumbersForMatch(): Set<Int> {
        val titleSeasons = if (isAnime) {
            latinTitles()
                .asSequence()
                .flatMap { title -> seasonTitleMarkerRegex.findAll(normalizeCompatibility(title)) }
                .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
                .filter { number -> number > 0 }
                .toSet()
        } else {
            emptySet()
        }
        return titleSeasons.ifEmpty {
            season?.takeIf { value -> value > 0 }?.let { value -> setOf(value) }.orEmpty()
        }
    }

    private fun isAnimePartPack(
        candidateTitle: String,
        context: StreamCenterTorrentPlaybackContext,
    ): Boolean {
        if (!context.isAnime) return false
        if (context.episodeNumbersForSearch().any { episode ->
                matchesAnimeEpisode(candidateTitle, episode)
            }
        ) {
            return false
        }
        return animePartMarkerRegex.containsMatchIn(normalizeCompatibility(candidateTitle))
    }

    private fun matchesAnimeEpisode(title: String, episode: Int): Boolean {
        val compatibleTitle = normalizeCompatibility(title)
        val value = episode.toString()
        val markers = listOf(
            Regex("""(?i)(?:^|[^a-z0-9])e(?:p(?:isode)?)?[._ -]*0*$value(?:v\d+)?(?:[^0-9]|$)"""),
            Regex("""(?:^|[^0-9])(?:第\s*)?0*$value\s*話(?:v\d+)?(?:[^0-9]|$)"""),
        )
        return markers.any { marker -> marker.containsMatchIn(compatibleTitle) } ||
            matchesBareAnimeEpisode(compatibleTitle, episode)
    }

    private const val MAX_UNMARKED_EPISODE_NUMBER = 600
    private val TITLE_MATCH_STOP_WORDS = setOf(
        "a", "an", "and", "at", "de", "dei", "del", "della", "delle", "degli", "di",
        "e", "for", "gli", "i", "il", "in", "la", "le", "lo", "of", "on", "or", "the",
        "to", "un", "una", "uno",
    )
    private val animePartMarkerRegex = Regex(
        "(?i)\\b(?:part|parte|cour)\\s*\\d+\\b|(?:\\u7b2c\\s*)?\\d+\\s*(?:\\u30af\\u30fc\\u30eb|\\u671f)",
    )
    private val animePartNumberRegex = Regex(
        "(?i)\\b(?:part|parte|cour)\\s*(\\d+)\\b|(?:\\u7b2c\\s*)?(\\d+)\\s*(?:\\u30af\\u30fc\\u30eb|\\u671f)",
    )
    private val seasonTitleMarkerRegex = Regex(
        """(?i)(?:^|[^a-z0-9])(?:s|season|stagione|serie|series)\s*0*(\d{1,3})(?=$|[^a-z0-9]|[._ -]*e(?:p(?:isode)?)?[._ -]*\d)""",
    )
    private val tvSeasonMarkerRegex = Regex(
        """(?i)(?:^|[^a-z0-9])(?:s\s*0*(\d{1,3})(?=[._ -]*e|[^a-z0-9]|$)|(?:season|stagione|serie|series)[._ -]*0*(\d{1,3})(?:[^0-9]|$))""",
    )
    private val decimalNoiseRegex = Regex("""\d+\.\d+""")
    private val tvSeasonRangeRegex = Regex(
        """(?i)(?:^|[^a-z0-9])(?:s\s*0*(\d{1,3})\s*[-~]\s*(?:s\s*)?0*(\d{1,3})|(?:seasons?|stagion(?:e|i)|series?)[._ -]*0*(\d{1,3})\s*[-~]\s*(?:(?:seasons?|stagion(?:e|i)|series?)[._ -]*)?0*(\d{1,3}))(?=$|[^a-z0-9])""",
    )
    private val titlePartSuffixRegex = Regex(
        """(?i)\s+(?:part|parte|cour|season|stagione|serie|series)\s*(?:\d+|[ivxlcdm]+)\b.*""",
    )

    private data class AnimeSeriesMarkers(
        val seasons: Set<Int> = emptySet(),
        val parts: Set<Int> = emptySet(),
    ) {
        val isEmpty: Boolean
            get() = seasons.isEmpty() && parts.isEmpty()

        operator fun plus(other: AnimeSeriesMarkers): AnimeSeriesMarkers = AnimeSeriesMarkers(
            seasons = seasons + other.seasons,
            parts = parts + other.parts,
        )

        fun conflictsWith(other: AnimeSeriesMarkers): Boolean =
            (seasons.isNotEmpty() && other.seasons.isNotEmpty() &&
                seasons.intersect(other.seasons).isEmpty()) ||
                (parts.isNotEmpty() && other.parts.isNotEmpty() &&
                    parts.intersect(other.parts).isEmpty())

    }

    private data class TvSeasonMarkers(
        val seasons: Set<Int>,
        val ranges: List<IntRange>,
    ) {
        val isEmpty: Boolean
            get() = seasons.isEmpty() && ranges.isEmpty()

        operator fun contains(season: Int): Boolean =
            season in seasons || ranges.any { range -> season in range }
    }
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
        val suppliedTrackers = suppliedParameters.asSequence()
            .filter { (key, _) -> key.equals("tr", ignoreCase = true) }
            .map { (_, value) -> value }
            .filter(::isTrustedTrackerUrl)
        val trackers = suppliedTrackers
            .distinct()
            .take(MAX_TRACKERS)
            .toList()
            .ifEmpty { fallbackTrackers }

        return buildList {
            add("xt=urn:btih:$hash")
            add("dn=${encode((candidate.selectedFileName ?: candidate.title).take(MAX_DISPLAY_NAME_LENGTH))}")
            fileIndex?.let { index -> add("index=$index") }
            trackers.forEach { tracker -> add("tr=${encode(tracker)}") }
        }.joinToString(separator = "&", prefix = "magnet:?")
    }

    private fun isTrustedTrackerUrl(value: String): Boolean {
        val cleaned = value.trim()
        if (cleaned.isBlank() || cleaned.length > MAX_EXTERNAL_URL_LENGTH) return false
        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme !in TRACKER_SCHEMES || uri.userInfo != null || uri.fragment != null) return false
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT) ?: return false
        val port = uri.port
        return host in trustedTrackerHosts && (port == -1 || port in 1..65_535)
    }

    private fun normalizeInfoHash(value: String): String {
        return if (value.length == 40) {
            value.lowercase(Locale.ROOT)
        } else {
            decodeBase32InfoHash(value) ?: value.uppercase(Locale.ROOT)
        }
    }

    private fun decodeBase32InfoHash(value: String): String? {
        var buffer = 0
        var bufferedBits = 0
        val bytes = ArrayList<Int>(20)
        value.uppercase(Locale.ROOT).forEach { character ->
            val digit = BASE32_ALPHABET.indexOf(character)
            if (digit < 0) return null
            buffer = (buffer shl 5) or digit
            bufferedBits += 5
            if (bufferedBits >= 8) {
                bufferedBits -= 8
                bytes += (buffer shr bufferedBits) and 0xff
                buffer = buffer and ((1 shl bufferedBits) - 1)
            }
        }
        if (bytes.size != 20 || bufferedBits != 0) return null
        return bytes.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private val TRACKER_SCHEMES = setOf("http", "https", "udp")
    private val trustedTrackerHosts = fallbackTrackers.mapNotNullTo(linkedSetOf()) { tracker ->
        runCatching { URI(tracker).host?.lowercase(Locale.ROOT) }.getOrNull()
    }
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val MAX_TRACKERS = 12
    private const val MAX_EXTERNAL_URL_LENGTH = 2_048
    private const val MAX_DISPLAY_NAME_LENGTH = 240

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

internal fun cleanDisplayText(value: String): String =
    value.replace(Regex("""\s+"""), " ").trim()

internal fun parsePositiveInt(value: Any?): Int? {
    val parsed = when (value) {
        is Number -> value.toLong()
        null -> null
        else -> {
            val text = value.toString().trim().lowercase(Locale.ROOT)
            val multiplier = when {
                text.endsWith("k") -> 1_000.0
                text.endsWith("m") -> 1_000_000.0
                else -> 1.0
            }
            val numeric = text
                .removeSuffix("k")
                .removeSuffix("m")
                .replace(",", "")
                .replace(" ", "")
                .toDoubleOrNull()
            numeric
                ?.times(multiplier)
                ?.takeIf(Double::isFinite)
                ?.toLong()
        }
    }
    return parsed?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
}

private fun normalizeForMatch(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(MATCH_APOSTROPHE_REGEX, "")
        .let { normalizedValue -> Normalizer.normalize(normalizedValue, Normalizer.Form.NFD) }
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ")
    return collapseInitialismRuns(cleanDisplayText(normalized))
}

private fun collapseInitialismRuns(value: String): String {
    val tokens = value.split(' ').filter(String::isNotBlank)
    if (tokens.size < 2) return value
    return buildList {
        var index = 0
        while (index < tokens.size) {
            if (tokens[index].length != 1 || !tokens[index][0].isLetterOrDigit()) {
                add(tokens[index++])
                continue
            }
            val start = index
            while (
                index < tokens.size &&
                tokens[index].length == 1 &&
                tokens[index][0].isLetterOrDigit()
            ) {
                index++
            }
            if (index - start >= 2) add(tokens.subList(start, index).joinToString(""))
            else add(tokens[start])
        }
    }.joinToString(" ")
}

private val MATCH_APOSTROPHE_REGEX = Regex("""['\u2018\u2019\u02bc`\u00b4]+""")

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
