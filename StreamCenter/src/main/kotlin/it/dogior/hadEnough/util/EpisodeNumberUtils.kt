package it.dogior.hadEnough.util

internal fun normalizeAnimeEpisodeNumber(number: String?): String? {
    val normalized = number
        ?.trim()
        ?.replace(',', '.')
        ?.takeIf(String::isNotBlank)
        ?: return null
    val numericValue = normalized.toDoubleOrNull() ?: return normalized
    val intValue = numericValue.toInt()
    return if (numericValue == intValue.toDouble()) intValue.toString() else numericValue.toString()
}

internal fun parseWholeAnimeEpisodeNumber(number: String?): Int? {
    val normalized = normalizeAnimeEpisodeNumber(number) ?: return null
    val numericValue = normalized.toDoubleOrNull() ?: return normalized.toIntOrNull()
    val intValue = numericValue.toInt()
    return intValue.takeIf { numericValue == it.toDouble() }
}

internal fun sortedEpisodeNumbers(vararg sources: Map<String, *>): List<String> {
    return sources.flatMap { it.keys }
        .distinct()
        .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }, { it }))
}
