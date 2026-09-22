package it.dogior.hadEnough.catalog

internal fun normalizeTmdbStatusTag(tag: String): String {
    if (!tag.startsWith("Stato:", ignoreCase = true)) return tag
    val status = tag.substringAfter(':').trim()
    return if (status.contains("ripropost", ignoreCase = true) || status.equals("Returning Series", ignoreCase = true)) {
        "Stato: In corso"
    } else tag
}
