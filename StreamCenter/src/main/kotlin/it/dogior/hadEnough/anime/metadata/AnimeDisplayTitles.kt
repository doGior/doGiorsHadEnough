package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.StreamCenterPlugin

internal data class AnimeDisplayTitles(
    val fallback: String,
    val italian: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
    val animeUnity: String? = null,
) {
    fun preferred(preference: String): String {
        val candidates = when (preference) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY -> listOf(animeUnity, fallback)
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> listOf(romaji, fallback)
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> listOf(english, fallback)
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> listOf(native, fallback)
            else -> listOf(italian, english, romaji, fallback)
        }
        val selected = candidates.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) } ?: fallback
        return forDisplay(selected)
    }

    fun forDisplay(title: String): String = if (containsJapaneseTitleScript(title)) {
        animeUnity?.trim()?.takeIf(String::isNotBlank) ?: title
    } else title
}

internal fun containsJapaneseTitleScript(value: String): Boolean {
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        if (Character.UnicodeScript.of(codePoint) in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
            )) return true
        offset += Character.charCount(codePoint)
    }
    return false
}
