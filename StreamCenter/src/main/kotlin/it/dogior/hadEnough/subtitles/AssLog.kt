package it.dogior.hadEnough.subtitles

import it.dogior.hadEnough.util.StreamCenterLogger

internal object AssLog {
    private const val TAB = "Sottotitoli ASS"

    fun info(action: String, metadata: Map<String, Any?> = emptyMap()) {
        runCatching { StreamCenterLogger.logTab(TAB, action, metadata) }
    }

    fun warning(action: String, metadata: Map<String, Any?> = emptyMap()) {
        runCatching { StreamCenterLogger.logTab(TAB, action, metadata, StreamCenterLogger.Level.WARNING) }
    }

    fun error(action: String, throwable: Throwable, metadata: Map<String, Any?> = emptyMap()) {
        runCatching { StreamCenterLogger.logTabError(TAB, action, throwable, metadata) }
    }
}
