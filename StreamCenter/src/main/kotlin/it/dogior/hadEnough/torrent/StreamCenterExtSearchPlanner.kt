package it.dogior.hadEnough.torrent

import java.util.Locale
import java.text.Normalizer

internal data class StreamCenterExtSearchPlan(
    val query: String,
    val reason: String,
    val batchSearch: Boolean,
)

internal object StreamCenterExtSearchPlanner {
    fun plans(
        context: StreamCenterTorrentPlaybackContext,
        limit: Int = MAX_PLANS,
    ): List<StreamCenterExtSearchPlan> {
        val titles = prioritizedTitles(context)
        if (titles.isEmpty()) return emptyList()
        val plans = when {
            context.isMovie -> moviePlans(titles, context)
            context.isAnime -> animePlans(titles, context)
            else -> tvPlans(titles, context)
        }
        return plans
            .asSequence()
            .map { plan -> plan.copy(query = sanitizeQuery(plan.query)) }
            .filter { plan -> plan.query.length >= 2 }
            .distinctBy { plan -> plan.query.lowercase(Locale.ROOT) }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    private fun moviePlans(
        titles: List<String>,
        context: StreamCenterTorrentPlaybackContext,
    ): List<StreamCenterExtSearchPlan> = buildList {
        titles.forEach { title ->
            val withYear = context.year
                ?.takeIf { year -> !Regex("""(?:^|\D)$year(?:\D|$)""").containsMatchIn(title) }
                ?.let { year -> "$title $year" }
                ?: title
            add(StreamCenterExtSearchPlan(withYear, "titolo e anno", batchSearch = false))
        }
        titles.forEach { title ->
            if (context.year != null) {
                add(StreamCenterExtSearchPlan(title, "titolo alternativo", batchSearch = false))
            }
        }
    }

    private fun tvPlans(
        titles: List<String>,
        context: StreamCenterTorrentPlaybackContext,
    ): List<StreamCenterExtSearchPlan> {
        val episode = context.episode ?: return moviePlans(titles, context)
        val season = context.season ?: 1
        val paddedSeason = season.toString().padStart(2, '0')
        val paddedEpisode = episode.toString().padStart(2, '0')
        val releaseTitles = buildList {
            context.englishTitle?.let(::add)
            addAll(titles)
        }
            .map(::cleanDisplayText)
            .filter(String::isNotBlank)
            .distinctBy { title -> title.lowercase(Locale.ROOT) }
        val primaryTitle = releaseTitles.first()
        return buildList {
            releaseTitles.take(MAX_TV_DIRECT_TITLES).forEach { title ->
                add(StreamCenterExtSearchPlan("$title S${paddedSeason}E$paddedEpisode", "stagione ed episodio", false))
            }
            add(StreamCenterExtSearchPlan("$primaryTitle ${season}x$paddedEpisode", "numerazione alternativa", false))
            if (season in 1..9 && episode in 1..99) {
                add(StreamCenterExtSearchPlan(
                    "$primaryTitle ${season * 100 + episode}",
                    "numerazione compatta",
                    false,
                ))
            }
            add(StreamCenterExtSearchPlan("$primaryTitle S$paddedSeason", "stagione completa", true))
            add(StreamCenterExtSearchPlan(primaryTitle, "titolo serie completo", true))
            if (season == 1) {
                add(StreamCenterExtSearchPlan("$primaryTitle Episode $episode", "episodio esteso", false))
            }
            val unpaddedMarker = "S${season}E$episode"
            if (unpaddedMarker != "S${paddedSeason}E$paddedEpisode") {
                releaseTitles.take(MAX_TV_FALLBACK_TITLES).forEach { title ->
                    add(StreamCenterExtSearchPlan("$title $unpaddedMarker", "numerazione non allineata", false))
                }
            }
            releaseTitles.drop(1).take(1).forEach { title ->
                add(StreamCenterExtSearchPlan("$title ${season}x$paddedEpisode", "numerazione alternativa", false))
            }
            if (episode < 10) {
                add(StreamCenterExtSearchPlan("$primaryTitle ${season}x$episode", "numerazione alternativa breve", false))
            }
            if (season == 1) {
                add(StreamCenterExtSearchPlan("$primaryTitle E$paddedEpisode", "solo numero episodio", false))
            }
            add(StreamCenterExtSearchPlan("$primaryTitle season $season", "pacchetto stagione", true))
            releaseTitles.drop(1).take(1).forEach { title ->
                add(StreamCenterExtSearchPlan("$title S$paddedSeason", "stagione completa alternativa", true))
                add(StreamCenterExtSearchPlan(title, "titolo serie alternativo", true))
            }
        }
    }

    private fun animePlans(
        titles: List<String>,
        context: StreamCenterTorrentPlaybackContext,
    ): List<StreamCenterExtSearchPlan> {
        val episodeCoordinates = context.episodeCoordinatesForSearch()
        val localCoordinate = episodeCoordinates
            .firstOrNull { coordinate ->
                coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.LOCAL
            }
            ?: return moviePlans(titles, context)
        val seasonCoordinate = episodeCoordinates
            .firstOrNull { coordinate ->
                coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.SEASON
            }
        val absoluteCoordinate = episodeCoordinates
            .firstOrNull { coordinate ->
                coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.ABSOLUTE
            }
        val legacyCoordinates = episodeCoordinates
            .filter { coordinate ->
                coordinate.kind == StreamCenterTorrentEpisodeCoordinateKind.LEGACY
            }
            .take(MAX_ALTERNATIVE_EPISODES)
        val localEpisode = localCoordinate.episode
        val latinTitles = titles.filterNot(::containsJapaneseScript)
        val japaneseTitles = buildList {
            context.japaneseTitle?.let(::add)
            titles.filter(::containsJapaneseScript).forEach(::add)
        }
            .map(::cleanDisplayText)
            .filter(String::isNotBlank)
            .distinctBy { title -> title.lowercase(Locale.ROOT) }
        val primaryLatinTitle = latinTitles.firstOrNull() ?: titles.first()
        val primaryVariants = animeTitleVariants(primaryLatinTitle)
        val primarySearchTitle = primaryVariants.firstOrNull() ?: primaryLatinTitle
        val primaryBaseTitle = primaryVariants.lastOrNull() ?: primarySearchTitle
        val alternateLatinTitle = latinTitles
            .drop(1)
            .firstOrNull()
            ?.let(::animeTitleVariants)
            ?.firstOrNull()
        val canonicalAliasTitle = buildList {
            context.englishTitle?.let(::add)
            addAll(context.titles.drop(1))
        }
            .asSequence()
            .map(::cleanDisplayText)
            .filter(String::isNotBlank)
            .filterNot(::containsJapaneseScript)
            .map(::animeTitleVariants)
            .mapNotNull { variants -> variants.getOrNull(1) ?: variants.lastOrNull() }
            .firstOrNull { title -> !title.equals(primaryBaseTitle, ignoreCase = true) }
        val japaneseSearchTitle = japaneseTitles.firstOrNull()
        val japaneseBaseTitle = japaneseSearchTitle
            ?.let(::animeTitleVariants)
            ?.getOrNull(1)
        val season = context.season?.takeIf { value -> value > 0 } ?: 1
        val paddedSeason = season.toString().padStart(2, '0')
        val paddedLocalEpisode = localEpisode.toString().padStart(2, '0')
        return buildList {
            add(StreamCenterExtSearchPlan(
                "$primarySearchTitle $localEpisode",
                "episodio anime",
                batchSearch = false,
            ))
            seasonCoordinate?.let { coordinate ->
                coordinate.season?.takeIf { value -> value > 0 }?.let { canonicalSeason ->
                    val canonicalEpisode = coordinate.episode
                    add(StreamCenterExtSearchPlan(
                        "$primaryBaseTitle S${canonicalSeason.toString().padStart(2, '0')}E${canonicalEpisode.toString().padStart(2, '0')}",
                        "numerazione stagione canonica anime",
                        batchSearch = false,
                    ))
                    canonicalAliasTitle
                        ?.takeUnless { title -> title.equals(primaryBaseTitle, ignoreCase = true) }
                        ?.let { title ->
                            add(StreamCenterExtSearchPlan(
                                "$title S${canonicalSeason.toString().padStart(2, '0')}E${canonicalEpisode.toString().padStart(2, '0')}",
                                "numerazione stagione canonica con titolo alternativo",
                                batchSearch = false,
                            ))
                        }
                    add(StreamCenterExtSearchPlan(
                        "$primaryBaseTitle $canonicalEpisode",
                        "numero episodio stagionale anime",
                        batchSearch = false,
                    ))
                }
            }
            absoluteCoordinate?.let { coordinate ->
                add(StreamCenterExtSearchPlan(
                    "$primaryBaseTitle ${coordinate.episode}",
                    "numerazione anime assoluta",
                    batchSearch = false,
                ))
            }
            if (localEpisode in 1..9) {
                add(StreamCenterExtSearchPlan(
                    "$primarySearchTitle $paddedLocalEpisode",
                    "episodio anime con zero iniziale",
                    batchSearch = false,
                ))
            }
            if (seasonCoordinate == null) {
                add(StreamCenterExtSearchPlan(
                    "$primarySearchTitle S${paddedSeason}E$paddedLocalEpisode",
                    "numerazione stagione anime",
                    batchSearch = false,
                ))
            }
            japaneseSearchTitle?.let { title ->
                add(StreamCenterExtSearchPlan(
                    "$title $localEpisode",
                    "titolo giapponese",
                    batchSearch = false,
                ))
            }
            japaneseBaseTitle
                ?.takeIf { title -> !title.equals(japaneseSearchTitle, ignoreCase = true) }
                ?.let { title ->
                    add(StreamCenterExtSearchPlan(
                        "$title $localEpisode",
                        "titolo giapponese semplificato",
                        batchSearch = false,
                    ))
                }
            primaryVariants.drop(1).take(MAX_PRIMARY_BASE_VARIANTS).forEach { title ->
                add(StreamCenterExtSearchPlan(
                    "$title $localEpisode",
                    "titolo anime semplificato",
                    batchSearch = false,
                ))
            }
            alternateLatinTitle?.let { title ->
                add(StreamCenterExtSearchPlan(
                    "$title $localEpisode",
                    "titolo anime alternativo",
                    batchSearch = false,
                ))
            }
            legacyCoordinates.forEach { coordinate ->
                add(StreamCenterExtSearchPlan(
                    "$primaryBaseTitle ${coordinate.episode}",
                    "numerazione anime alternativa",
                    batchSearch = false,
                ))
            }
            add(StreamCenterExtSearchPlan(
                primaryBaseTitle,
                "titolo anime base",
                batchSearch = false,
            ))
            add(StreamCenterExtSearchPlan("$primaryBaseTitle batch", "batch anime", true))
            add(StreamCenterExtSearchPlan("$primaryBaseTitle complete", "stagione completa", true))
        }
    }

    private fun animeTitleVariants(value: String): List<String> {
        val original = cleanDisplayText(value)
        if (original.isBlank()) return emptyList()
        val normalized = cleanDisplayText(
            original
                .replace(ANIME_YEAR_SUFFIX_REGEX, " ")
                .replace(ANIME_PART_MARKER_REGEX, " "),
        )
        val beforeSubtitle = normalized
            .substringBefore(':')
            .substringBefore('–')
            .substringBefore('—')
            .let(::cleanDisplayText)
        return listOf(original, normalized, beforeSubtitle)
            .filter { title -> title.length >= MINIMUM_TITLE_LENGTH }
            .distinctBy { title -> title.lowercase(Locale.ROOT) }
    }

    private fun prioritizedTitles(context: StreamCenterTorrentPlaybackContext): List<String> {
        val semanticTitles = buildList {
            context.titles.firstOrNull()?.let(::add)
            context.englishTitle?.let(::add)
            context.titles.drop(1).forEach(::add)
            context.japaneseTitle?.let(::add)
        }
            .map(::cleanDisplayText)
            .filter(String::isNotBlank)
            .distinctBy { title -> title.lowercase(Locale.ROOT) }
        val derivedTitles = semanticTitles.flatMap { title ->
            listOf(withoutPunctuation(title), compactPunctuation(title))
        }
        return (semanticTitles + derivedTitles)
            .filter(String::isNotBlank)
            .distinctBy { title -> title.lowercase(Locale.ROOT) }
            .take(if (context.isAnime) MAX_ANIME_TITLES else MAX_TITLES)
    }

    private fun withoutPunctuation(value: String): String =
        cleanDisplayText(
            value
                .replace(APOSTROPHE_REGEX, "")
                .replace(PUNCTUATION_REGEX, " "),
        )

    private fun compactPunctuation(value: String): String {
        val tokens = withoutPunctuation(value).split(' ').filter(String::isNotBlank)
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

    private fun sanitizeQuery(value: String): String = cleanDisplayText(
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .filterNot(Char::isISOControl)
            .take(MAX_QUERY_LENGTH),
    )

    private const val MAX_TITLES = 6
    private const val MAX_ANIME_TITLES = 8
    private const val MAX_TV_DIRECT_TITLES = 4
    private const val MAX_TV_FALLBACK_TITLES = 2
    private const val MAX_ALTERNATIVE_EPISODES = 2
    private const val MAX_PRIMARY_BASE_VARIANTS = 2
    private const val MINIMUM_TITLE_LENGTH = 2
    private const val MAX_QUERY_LENGTH = 180
    private const val MAX_PLANS = 12
    private val APOSTROPHE_REGEX = Regex("""['‘’ʼ`´]+""")
    private val PUNCTUATION_REGEX = Regex("""[^\p{L}\p{N}\s]+""")
    private val ANIME_YEAR_SUFFIX_REGEX = Regex("""\s*\((?:19|20)\d{2}\)\s*""")
    private val ANIME_PART_MARKER_REGEX = Regex(
        """(?i)\s*(?:part|parte|cour|season|stagione|series?)\s*(?:\d+|[ivxlcdm]+)\b|\s*(?:第\s*)?\d+\s*(?:クール|期)""",
    )
}
