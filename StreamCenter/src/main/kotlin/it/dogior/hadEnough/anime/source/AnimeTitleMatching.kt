package it.dogior.hadEnough.anime.source

import it.dogior.hadEnough.model.AnilistMetadata
import it.dogior.hadEnough.model.StreamCenterMetadata
import java.util.Locale

internal fun buildAnimeSourceTitleCandidates(
    metadata: StreamCenterMetadata,
    anilistMetadata: AnilistMetadata?,
): List<String> {
    val baseTitles = (
        listOfNotNull(anilistMetadata?.title) +
            anilistMetadata?.titleCandidates.orEmpty() +
            listOfNotNull(metadata.title, metadata.originalTitle)
        )
        .map(String::trim)
        .filter(String::isNotBlank)

    return (baseTitles + baseTitles.flatMap(::expandTitleCandidate))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(::sourceTitleDedupKey)
}

internal fun exactAnimeTitleKeys(
    metadata: StreamCenterMetadata,
    anilistMetadata: AnilistMetadata?,
): Set<String> {
    return (
        listOfNotNull(anilistMetadata?.title) +
            anilistMetadata?.titleCandidates.orEmpty() +
            listOfNotNull(metadata.title, metadata.originalTitle)
        )
        .map(::sourceTitleDedupKey)
        .filter(String::isNotBlank)
        .toSet()
}

internal fun sourceTitleScore(title: String, query: String): Int {
    val normalizedTitle = sourceTitleDedupKey(title)
    val normalizedQuery = sourceTitleDedupKey(query)
    if (normalizedTitle.isBlank() || normalizedQuery.isBlank()) return 0
    if (normalizedTitle == normalizedQuery) return 120
    if (normalizedTitle.contains(normalizedQuery) || normalizedQuery.contains(normalizedTitle)) return 100

    val titleTokens = sourceTitleTokens(normalizedTitle)
    val queryTokens = sourceTitleTokens(normalizedQuery)
    if (titleTokens.isEmpty() || queryTokens.isEmpty()) return 0
    val overlap = titleTokens.intersect(queryTokens).size
    if (overlap == 0) return 0
    val overlapRatio = overlap.toDouble() / minOf(titleTokens.size, queryTokens.size).toDouble()
    val baseScore = when {
        overlapRatio >= 0.75 -> 80
        overlapRatio >= 0.50 -> 55
        overlap >= 2 -> 35
        else -> 0
    }
    val moviePenalty = if (
        title.contains("movie", ignoreCase = true) &&
        !query.contains("movie", ignoreCase = true)
    ) 15 else 0
    return (baseScore - moviePenalty).coerceAtLeast(0)
}

internal fun sourceTitleDedupKey(title: String): String {
    return normalizeSourceTitle(title).ifBlank { title.lowercase(Locale.ROOT) }
}

internal fun absoluteProviderUrl(baseUrl: String, href: String): String {
    val cleanedHref = href.trim()
    return when {
        cleanedHref.startsWith("http://") || cleanedHref.startsWith("https://") -> cleanedHref
        cleanedHref.startsWith("//") -> "https:$cleanedHref"
        else -> "${baseUrl.trimEnd('/')}/${cleanedHref.trimStart('/')}"
    }
}

private fun expandTitleCandidate(title: String): List<String> {
    val withoutSeason = title
        .replace(
            Regex("""\s*[-–:]?\s*(?:Stagione|Season|Parte|Part|Cour)\s+\d+\b""", RegexOption.IGNORE_CASE),
            "",
        )
        .replace(Regex("""\s+\d+(?:st|nd|rd|th)\s+Season\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+Final\s+Season\b""", RegexOption.IGNORE_CASE), "")
    val numericSeason = title.replace(
        Regex("""\s+(?:Stagione|Season)\s+(\d+)\b""", RegexOption.IGNORE_CASE),
        " $1",
    )
    val simplifiedPunctuation = title
        .replace(Regex("""[:;!?.,'"“”‘’]+"""), " ")
        .replace(Regex("""\s+"""), " ")
    val words = title.split(Regex("""\s+""")).filter(String::isNotBlank)

    return listOf(
        title.replace(Regex("""\(\d{4}\)"""), ""),
        numericSeason,
        withoutSeason,
        title.substringBefore(':'),
        title.substringBefore(" - "),
        simplifiedPunctuation,
    ) + if (words.size > 3) listOf(words.take(3).joinToString(" ")) else emptyList()
}

private fun sourceTitleTokens(title: String): Set<String> {
    return sourceTitleDedupKey(title)
        .split(' ')
        .map(String::trim)
        .filter { it.length >= 2 }
        .toSet()
}

private fun normalizeSourceTitle(title: String): String {
    return title
        .lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(Regex("""\([^)]*\)"""), " ")
        .replace(Regex("""\b(movie|the movie|ita|sub ita|subita|tv|ona|ova|special|season|stagione)\b"""), " ")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
}
