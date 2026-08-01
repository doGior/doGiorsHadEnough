package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.util.StreamCenterLogger

internal object MetadataLog {
    private const val TAB_NAME = "Metadati"

    fun info(
        source: String,
        action: String,
        details: Map<String, Any?> = emptyMap(),
    ) = write(source, action, details, StreamCenterLogger.Level.INFO)

    fun warning(
        source: String,
        action: String,
        details: Map<String, Any?> = emptyMap(),
    ) = write(source, action, details, StreamCenterLogger.Level.WARNING)

    fun error(
        source: String,
        action: String,
        details: Map<String, Any?> = emptyMap(),
    ) = write(source, action, details, StreamCenterLogger.Level.ERROR)

    fun failure(
        source: String,
        action: String,
        error: Throwable? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        write(
            source = source,
            action = action,
            details = details,
            level = StreamCenterLogger.Level.ERROR,
            error = error,
        )
    }

    private fun write(
        source: String,
        action: String,
        details: Map<String, Any?>,
        level: StreamCenterLogger.Level,
        error: Throwable? = null,
    ) {
        StreamCenterLogger.logMetadata(
            tabName = resolveTabName(details),
            source = source,
            action = action,
            metadata = details,
            level = level,
            throwable = error,
        )
    }

    private fun resolveTabName(details: Map<String, Any?>): String {
        return TITLE_KEYS
            .firstNotNullOfOrNull { key -> details[key]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?: TAB_NAME
    }

    private val TITLE_KEYS = listOf(
        "titolo_scheda",
        "titolo",
        "titolo_richiesto",
        "nome",
        "title",
    )
}
