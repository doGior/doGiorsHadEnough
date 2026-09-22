package it.dogior.hadEnough.util

import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

private val EPISODE_TITLE_PREFIX = Regex("""^\d+\.\s*""")
private val RUNTIME_HOURS = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE)
private val RUNTIME_MINUTES = Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE)
private val ISO_DATE = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
private val NAMED_DATE = Regex("""(\d{1,2})\s+(\p{L}+),?\s+(\d{4})""")
private val MONTH_FIRST_DATE = Regex("""(\p{L}+)\s+(\d{1,2}),?\s+(\d{4})""")
private val MONTHS = listOf(
    "gennaio" to "january", "febbraio" to "february", "marzo" to "march",
    "aprile" to "april", "maggio" to "may", "giugno" to "june",
    "luglio" to "july", "agosto" to "august", "settembre" to "september",
    "ottobre" to "october", "novembre" to "november", "dicembre" to "december",
).flatMapIndexed { index, (italian, english) -> listOf(italian to index + 1, english to index + 1) }.toMap()

internal fun cleanMetadataEpisodeTitle(text: String?): String? = cleanText(text)
    ?.replace(EPISODE_TITLE_PREFIX, "")
    ?.takeIf(String::isNotBlank)

internal fun parseMetadataRuntime(text: String?): Int? {
    val value = cleanText(text) ?: return null
    val hours = RUNTIME_HOURS.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val minutes = RUNTIME_MINUTES.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    if (hours > Int.MAX_VALUE / 60L || minutes > Int.MAX_VALUE) return null
    return (hours * 60 + minutes).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

internal fun parseMetadataDate(text: String?): String? {
    val value = cleanText(text) ?: return null
    val iso = ISO_DATE.find(value)
    if (iso != null) {
        return validIsoDate(iso.groupValues[1].toInt(), iso.groupValues[2].toInt(), iso.groupValues[3].toInt())
    }
    val dayFirst = NAMED_DATE.find(value)
    val match = dayFirst ?: MONTH_FIRST_DATE.find(value) ?: return null
    val monthName = match.groupValues[if (dayFirst != null) 2 else 1].lowercase(Locale.ROOT)
    val day = match.groupValues[if (dayFirst != null) 1 else 2].toInt()
    val month = MONTHS[monthName] ?: return null
    return validIsoDate(match.groupValues[3].toInt(), month, day)
}

private fun validIsoDate(year: Int, month: Int, day: Int): String? {
    if (year <= 0 || month !in 1..12 || day !in 1..31) return null
    val valid = runCatching {
        GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            isLenient = false
            clear()
            set(year, month - 1, day)
        }.timeInMillis
    }.isSuccess
    return if (valid) String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day) else null
}
