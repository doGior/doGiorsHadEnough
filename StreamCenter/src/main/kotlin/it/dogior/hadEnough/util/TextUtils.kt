package it.dogior.hadEnough.util

internal fun cleanText(text: String?): String? {
    return text
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "-" }
}

internal fun cleanTmdbEpisodeDescription(text: String?): String? {
    return cleanText(
        text
            ?.replace("Leggi di più", "")
            ?.replace("Leggi di piu", ""),
    )?.takeUnless { TMDB_OVERVIEW_PLACEHOLDER_REGEX.containsMatchIn(it) }
}

private val TMDB_OVERVIEW_PLACEHOLDER_REGEX = Regex(
    """Non abbiamo (una descrizione|una panoramica)|We don['’]t have an overview""",
    RegexOption.IGNORE_CASE,
)
