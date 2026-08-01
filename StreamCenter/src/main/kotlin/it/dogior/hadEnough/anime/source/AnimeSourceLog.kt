package it.dogior.hadEnough.anime.source

import it.dogior.hadEnough.util.StreamCenterLogger

internal object AnimeSourceLog {
    fun info(
        source: String,
        action: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        StreamCenterLogger.logMetadata(
            tabName = resolveTabName(details),
            source = source,
            action = action,
            metadata = details,
        )
    }

    fun warning(
        source: String,
        action: String,
        details: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) {
        StreamCenterLogger.logMetadata(
            tabName = resolveTabName(details),
            source = source,
            action = action,
            metadata = details,
            level = StreamCenterLogger.Level.WARNING,
            throwable = error,
        )
    }

    private fun resolveTabName(details: Map<String, Any?>): String {
        return TITLE_KEYS
            .firstNotNullOfOrNull { key -> details[key]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?: TAB_NAME
    }

    private const val TAB_NAME = "Fonti anime"
    private val TITLE_KEYS = listOf(
        "titolo_scheda",
        "titolo",
        "titolo_richiesto",
        "nome",
        "title",
    )
}
