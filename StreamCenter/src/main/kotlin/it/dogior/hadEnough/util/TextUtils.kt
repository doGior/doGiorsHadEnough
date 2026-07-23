package it.dogior.hadEnough.util

internal fun cleanText(text: String?): String? {
    return text
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "-" }
}
