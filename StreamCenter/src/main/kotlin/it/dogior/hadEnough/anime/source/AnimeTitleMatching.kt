package it.dogior.hadEnough.anime.source

import it.dogior.hadEnough.model.AnilistMetadata
import it.dogior.hadEnough.model.StreamCenterMetadata
import java.text.Normalizer
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
    if (normalizedTitle == normalizedQuery) return 160
    if (normalizedTitle.startsWith("$normalizedQuery ")) return 140
    if (containsWholeTitleQuery(normalizedTitle, normalizedQuery)) return 120
    if (containsWholeTitleQuery(normalizedQuery, normalizedTitle)) return 110

    val titleTokens = sourceTitleTokens(normalizedTitle)
    val queryTokens = sourceTitleTokens(normalizedQuery)
    if (titleTokens.isEmpty() || queryTokens.isEmpty()) return 0
    val exactMatches = queryTokens.count { it in titleTokens }
    val typoMatches = queryTokens.count { queryToken ->
        queryToken !in titleTokens && titleTokens.any { titleToken ->
            isTypoTolerantTokenMatch(queryToken, titleToken)
        }
    }
    val matchedTokens = exactMatches + typoMatches
    val requiredMatches = when (queryTokens.size) {
        1 -> 1
        2 -> 2
        else -> (queryTokens.size * 2 + 2) / 3
    }
    if (matchedTokens < requiredMatches) return 0

    val baseScore = when {
        matchedTokens == queryTokens.size && titleTokens.size == queryTokens.size -> 105
        matchedTokens == queryTokens.size -> 95
        else -> 75
    }
    val exactBonus = exactMatches * 4
    val typoPenalty = typoMatches * 3
    val moviePenalty = if (
        title.contains("movie", ignoreCase = true) &&
        !query.contains("movie", ignoreCase = true)
    ) 15 else 0
    return (baseScore + exactBonus - typoPenalty - moviePenalty).coerceAtLeast(0)
}

private fun isTypoTolerantTokenMatch(queryToken: String, titleToken: String): Boolean {
    if (queryToken.length < 4 || titleToken.length < 4) return false
    val maxDistance = if (minOf(queryToken.length, titleToken.length) <= 5) 1 else 2
    return editDistance(queryToken, titleToken, maxDistance) <= maxDistance
}

private fun containsWholeTitleQuery(title: String, query: String): Boolean {
    return " $title ".contains(" $query ")
}

private fun editDistance(first: String, second: String, maxDistance: Int): Int {
    if (kotlin.math.abs(first.length - second.length) > maxDistance) return maxDistance + 1
    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    for (firstIndex in first.indices) {
        current[0] = firstIndex + 1
        var rowMinimum = current[0]
        for (secondIndex in second.indices) {
            current[secondIndex + 1] = minOf(
                previous[secondIndex + 1] + 1,
                current[secondIndex] + 1,
                previous[secondIndex] + if (first[firstIndex] == second[secondIndex]) 0 else 1,
            )
            rowMinimum = minOf(rowMinimum, current[secondIndex + 1])
        }
        if (rowMinimum > maxDistance) return maxDistance + 1
        val temporary = previous
        previous = current
        current = temporary
    }
    return previous[second.length]
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
    return Normalizer.normalize(title, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(Regex("""\([^)]*\)"""), " ")
        .replace(Regex("""\b(movie|the movie|ita|sub ita|subita|tv|ona|ova|special|season|stagione)\b"""), " ")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
}
