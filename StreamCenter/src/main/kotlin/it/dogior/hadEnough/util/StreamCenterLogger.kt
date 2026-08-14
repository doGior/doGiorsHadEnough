package it.dogior.hadEnough.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object StreamCenterLogger {
    const val PREF_ENABLED = "loggingEnabled"
    const val PREF_RETENTION_MODE = "loggingRetentionMode"
    const val PREF_RETENTION_VALUE = "loggingRetentionValue"
    const val PREF_RETENTION_DAYS = "loggingRetentionDays"
    const val PREF_RETENTION_LOG_COUNT = "loggingRetentionLogCount"

    private const val LOG_DIRECTORY_NAME = "streamcenter-logs"
    private const val LOG_FILE_PREFIX = "streamcenter-log-"
    private const val LOG_FILE_EXTENSION = ".jsonl"
    private const val LOG_FORMAT_VERSION = 2
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    private val LEGACY_RETENTION_MODES = setOf("DAYS", "LOG_COUNT")

    private const val RECORD_TYPE_SESSION = "session"
    private const val RECORD_TYPE_EVENT = "event"
    private const val SECTION_MENU = "menu"
    private const val SECTION_TAB = "tab"

    private val lock = Any()
    private var applicationContext: Context? = null
    private var activePreferences: SharedPreferences? = null
    private var activeSession: Session? = null
    private var writingEnabled = false

    enum class Level {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
    }

    data class RetentionPolicy(
        val days: Int? = null,
        val maximumLogCount: Int? = null,
    ) {
        val isEnabled: Boolean
            get() = days != null || maximumLogCount != null
    }

    data class SourcedValue(
        val value: Any?,
        val sources: List<String>,
        val note: String? = null,
    )

    data class LogFileInfo(
        val id: String,
        val name: String,
        val createdAt: String,
        val lastModified: Long,
        val sizeBytes: Long,
        val isCurrent: Boolean,
    )

    private data class Session(
        val id: String,
        val file: File,
        val createdAt: String,
    )

    private data class ParsedRecord(
        val timestamp: String,
        val level: String,
        val section: String,
        val tabName: String?,
        val action: String,
        val metadata: LinkedHashMap<String, String>,
        val errorType: String?,
        val errorMessage: String?,
        val errorStackTrace: String?,
    )

    private data class ParsedLog(
        val id: String,
        val createdAt: String,
        val sessionMetadata: LinkedHashMap<String, String>,
        val records: List<ParsedRecord>,
    )

    fun isEnabled(preferences: SharedPreferences?): Boolean {
        return preferences?.getBoolean(PREF_ENABLED, false) ?: false
    }

    fun retentionPolicy(preferences: SharedPreferences?): RetentionPolicy {
        val days = preferences?.getInt(PREF_RETENTION_DAYS, 0)?.takeIf { it > 0 }
        val maximumLogCount = preferences?.getInt(PREF_RETENTION_LOG_COUNT, 0)?.takeIf { it > 0 }
        if (days != null || maximumLogCount != null) {
            return RetentionPolicy(days, maximumLogCount)
        }

        val legacyValue = preferences?.getInt(PREF_RETENTION_VALUE, 0)?.takeIf { it > 0 }
            ?: return RetentionPolicy()
        return when (preferences.getString(PREF_RETENTION_MODE, null)) {
            "DAYS" -> RetentionPolicy(days = legacyValue)
            "LOG_COUNT" -> RetentionPolicy(maximumLogCount = legacyValue)
            else -> RetentionPolicy()
        }
    }

    fun setRetentionPolicy(preferences: SharedPreferences, policy: RetentionPolicy) {
        val days = policy.days?.takeIf { it > 0 }
        val maximumLogCount = policy.maximumLogCount?.takeIf { it > 0 }
        preferences.edit().apply {
            days?.let { putInt(PREF_RETENTION_DAYS, it) } ?: remove(PREF_RETENTION_DAYS)
            maximumLogCount?.let { putInt(PREF_RETENTION_LOG_COUNT, it) }
                ?: remove(PREF_RETENTION_LOG_COUNT)
            remove(PREF_RETENTION_MODE)
            remove(PREF_RETENTION_VALUE)
        }.apply()
    }

    fun isDefaultRetentionPreference(key: String, value: Any?): Boolean {
        return when (key) {
            PREF_RETENTION_MODE -> value !is String || value !in LEGACY_RETENTION_MODES
            PREF_RETENTION_VALUE -> value !is Int || value <= 0
            PREF_RETENTION_DAYS, PREF_RETENTION_LOG_COUNT -> value !is Int || value <= 0
            else -> false
        }
    }

    fun setEnabled(preferences: SharedPreferences, enabled: Boolean) {
        val wasEnabled = isEnabled(preferences)

        synchronized(lock) {
            activePreferences = preferences
            if (!enabled && wasEnabled) {
                appendMenuLocked(
                    action = "Registrazione disabilitata dall'utente",
                    metadata = mapOf("stato_precedente" to "abilitato"),
                    level = Level.INFO,
                    throwable = null,
                )
            }

            preferences.edit().putBoolean(PREF_ENABLED, enabled).apply()

            if (!enabled) {
                writingEnabled = false
                return
            }

            val context = applicationContext
            if (!wasEnabled || !writingEnabled || activeSession == null) {
                if (context != null) {
                    createSessionLocked(context, emptyMap())
                    writingEnabled = activeSession != null
                    appendMenuLocked(
                        action = "Registrazione abilitata dall'utente",
                        metadata = mapOf("stato_precedente" to "disabilitato"),
                        level = Level.INFO,
                        throwable = null,
                    )
                }
            } else {
                writingEnabled = true
                appendMenuLocked(
                    action = "Registrazione confermata dall'utente",
                    metadata = mapOf("stato" to "gia_abilitata"),
                    level = Level.DEBUG,
                    throwable = null,
                )
            }
        }
    }

    fun startSession(
        context: Context,
        preferences: SharedPreferences?,
        sessionMetadata: Map<String, Any?> = emptyMap(),
    ) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            activePreferences = preferences
            activeSession = null
            writingEnabled = isEnabled(preferences)
            val directory = logDirectory(context.applicationContext)
            val retentionPolicy = retentionPolicy(preferences)
            if (!writingEnabled) {
                pruneLogsLocked(directory, retentionPolicy)
                return
            }

            createSessionLocked(context.applicationContext, sessionMetadata)
            writingEnabled = activeSession != null
            val removedLogs = pruneLogsLocked(directory, retentionPolicy, activeSession?.file)
            if (removedLogs > 0) {
                appendMenuLocked(
                    action = "Pulizia automatica log completata",
                    metadata = retentionMetadata(retentionPolicy, removedLogs),
                    level = Level.INFO,
                    throwable = null,
                )
            }
            appendMenuLocked(
                action = "Avvio di StreamCenter completato",
                metadata = mapOf(
                    "registrazione" to "attiva",
                    "formato_log" to LOG_FORMAT_VERSION,
                ),
                level = Level.INFO,
                throwable = null,
            )
        }
    }

    fun pruneLogs(context: Context, preferences: SharedPreferences?): Int {
        synchronized(lock) {
            applicationContext = context.applicationContext
            activePreferences = preferences
            val retentionPolicy = retentionPolicy(preferences)
            val removedLogs = pruneLogsLocked(
                directory = logDirectory(context.applicationContext),
                retentionPolicy = retentionPolicy,
                except = activeSession?.file,
            )
            if (removedLogs > 0) {
                appendMenuLocked(
                    action = "Pulizia automatica log completata",
                    metadata = retentionMetadata(retentionPolicy, removedLogs),
                    level = Level.INFO,
                    throwable = null,
                )
            }
            return removedLogs
        }
    }

    fun logMenu(
        action: String,
        metadata: Map<String, Any?> = emptyMap(),
        level: Level = Level.INFO,
        throwable: Throwable? = null,
    ) {
        synchronized(lock) {
            appendMenuLocked(action, metadata, level, throwable)
        }
    }

    fun warning(action: String, details: Map<String, Any?> = emptyMap()) {
        logMenu(action = action, metadata = details, level = Level.WARNING)
    }

    fun logTab(
        tabName: String,
        action: String,
        metadata: Map<String, Any?> = emptyMap(),
        level: Level = Level.INFO,
        throwable: Throwable? = null,
    ) {
        synchronized(lock) {
            appendRecordLocked(
                section = SECTION_TAB,
                tabName = tabName,
                action = action,
                metadata = metadata,
                level = level,
                throwable = throwable,
            )
        }
    }

    fun logMetadata(
        tabName: String,
        source: String,
        action: String = "Metadati elaborati",
        metadata: Map<String, Any?> = emptyMap(),
        level: Level = Level.INFO,
        throwable: Throwable? = null,
    ) {
        val details = LinkedHashMap<String, Any?>()
        details.putAll(metadata)
        details["fonte_metadati"] = source
        logTab(tabName, action, details, level, throwable)
    }

    fun logMenuError(
        action: String,
        throwable: Throwable,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        logMenu(action, metadata, Level.ERROR, throwable)
    }

    fun logTabError(
        tabName: String,
        action: String,
        throwable: Throwable,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        logTab(tabName, action, metadata, Level.ERROR, throwable)
    }

    fun listLogs(context: Context): List<LogFileInfo> {
        val currentFile = synchronized(lock) { activeSession?.file }
        return logDirectory(context)
            .listFiles { file -> file.isFile && file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION) }
            ?.sortedByDescending(File::lastModified)
            ?.map { file ->
                val parsed = parseLog(file)
                LogFileInfo(
                    id = parsed?.id ?: file.nameWithoutExtension,
                    name = file.nameWithoutExtension,
                    createdAt = parsed?.createdAt ?: formatTimestamp(file.lastModified()),
                    lastModified = file.lastModified(),
                    sizeBytes = file.length(),
                    isCurrent = currentFile?.absolutePath == file.absolutePath,
                )
            }
            .orEmpty()
    }

    fun readLog(context: Context, logId: String): String? {
        val file = findLogFile(context, logId) ?: return null
        return parseLog(file)?.let(::renderLog)
    }

    fun deleteLog(context: Context, logId: String): Boolean {
        synchronized(lock) {
            applicationContext = context.applicationContext
            val file = findLogFile(context, logId) ?: return false
            val isCurrent = activeSession?.file?.absolutePath == file.absolutePath
            val deleted = file.delete()
            if (!deleted) {
                appendMenuLocked(
                    action = "Eliminazione sessione di log non riuscita",
                    metadata = mapOf("id_sessione" to logId),
                    level = Level.ERROR,
                    throwable = null,
                )
                return false
            }

            if (isCurrent) {
                activeSession = null
                writingEnabled = false
            }

            if (isEnabled(activePreferences)) {
                appendMenuLocked(
                    action = "Sessione di log eliminata dall'utente",
                    metadata = mapOf(
                        "id_sessione" to logId,
                        "sessione_corrente" to isCurrent,
                    ),
                    level = Level.INFO,
                    throwable = null,
                )
            }
            return true
        }
    }

    private fun findLogFile(context: Context, logId: String): File? {
        return logDirectory(context)
            .listFiles { candidate ->
                candidate.isFile && candidate.name.startsWith(LOG_FILE_PREFIX) &&
                    candidate.name.endsWith(LOG_FILE_EXTENSION)
            }
            ?.firstOrNull { candidate ->
                parseLog(candidate)?.id == logId || candidate.nameWithoutExtension == logId
            }
    }

    private fun appendMenuLocked(
        action: String,
        metadata: Map<String, Any?>,
        level: Level,
        throwable: Throwable?,
    ) {
        appendRecordLocked(SECTION_MENU, null, action, metadata, level, throwable)
    }

    private fun appendRecordLocked(
        section: String,
        tabName: String?,
        action: String,
        metadata: Map<String, Any?>,
        level: Level,
        throwable: Throwable?,
    ) {
        if (!isEnabled(activePreferences)) {
            writingEnabled = false
            return
        }
        val session = ensureWritableSessionLocked() ?: return

        val record = JSONObject()
            .put("type", RECORD_TYPE_EVENT)
            .put("timestamp", currentTimestamp())
            .put("epoch_ms", System.currentTimeMillis())
            .put("level", level.name)
            .put("section", section)
            .put("action", sanitizeText(action))
            .put("metadata", metadataToJson(metadata))

        tabName
            ?.let(::sanitizeTabName)
            ?.takeIf(String::isNotBlank)
            ?.let { record.put("tab", it) }
        throwable?.let {
            record.put("error_type", it.javaClass.simpleName.ifBlank { it.javaClass.name })
            record.put(
                "error_message",
                sanitizeMetadataText(it.message ?: "Nessun dettaglio disponibile"),
            )
            record.put("error_stacktrace", sanitizeMetadataText(it.stackTraceToString()))
        }
        appendJsonLine(session.file, record)
    }

    private fun ensureWritableSessionLocked(): Session? {
        activeSession?.takeIf { writingEnabled }?.let { return it }
        val context = applicationContext ?: return null
        createSessionLocked(context, emptyMap())
        writingEnabled = activeSession != null
        return activeSession
    }

    private fun createSessionLocked(context: Context, sessionMetadata: Map<String, Any?>) {
        val directory = logDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) return
        if (!directory.isDirectory) return

        val id = "session-${sessionFileTimestamp()}-${UUID.randomUUID().toString().take(8)}"
        val file = File(directory, "$LOG_FILE_PREFIX$id$LOG_FILE_EXTENSION")
        val createdAt = currentTimestamp()
        val session = Session(id, file, createdAt)
        val header = JSONObject()
            .put("type", RECORD_TYPE_SESSION)
            .put("format", LOG_FORMAT_VERSION)
            .put("id", id)
            .put("created_at", createdAt)
            .put("metadata", metadataToJson(sessionMetadata))

        if (!appendJsonLine(file, header)) return
        activeSession = session
    }

    private fun pruneLogsLocked(
        directory: File,
        retentionPolicy: RetentionPolicy,
        except: File? = null,
    ): Int {
        if (!retentionPolicy.isEnabled || !directory.isDirectory) return 0
        val removedByAge = retentionPolicy.days
            ?.let { days -> pruneLogsByAgeLocked(directory, days, except) }
            ?: 0
        val removedByCount = retentionPolicy.maximumLogCount
            ?.let { maximumLogCount -> pruneLogsByCountLocked(directory, maximumLogCount, except) }
            ?: 0
        return removedByAge + removedByCount
    }

    private fun pruneLogsByAgeLocked(
        directory: File,
        retentionDays: Int,
        except: File?,
    ): Int {
        val threshold = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY
        return logFiles(directory)
            .asSequence()
            .filter { file -> file.absolutePath != except?.absolutePath && file.lastModified() < threshold }
            .count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    private fun pruneLogsByCountLocked(
        directory: File,
        maximumLogCount: Int,
        except: File?,
    ): Int {
        val protectedPath = except?.absolutePath
        val logsToRemove = logFiles(directory)
            .filter { file -> file.absolutePath != protectedPath }
            .sortedByDescending(File::lastModified)
            .drop((maximumLogCount - if (except == null) 0 else 1).coerceAtLeast(0))
        return logsToRemove.count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    private fun logFiles(directory: File): List<File> {
        return directory
            .listFiles { file ->
                file.isFile && file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)
            }
            .orEmpty()
            .toList()
    }

    private fun retentionMetadata(
        policy: RetentionPolicy,
        removedLogs: Int,
    ): Map<String, Any> {
        return buildMap<String, Any> {
            policy.days?.let { days -> put("giorni", days) }
            policy.maximumLogCount?.let { maximumLogCount -> put("numero_massimo_log", maximumLogCount) }
            put("sessioni_eliminate", removedLogs)
        }
    }

    private fun appendJsonLine(file: File, value: JSONObject): Boolean {
        return runCatching {
            BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
            ).use { writer ->
                writer.append(value.toString())
                writer.newLine()
            }
        }.isSuccess
    }

    private fun logDirectory(context: Context): File {
        return File(context.applicationContext.filesDir, LOG_DIRECTORY_NAME)
    }

    private fun parseLog(file: File): ParsedLog? {
        var id = file.nameWithoutExtension
        var createdAt = formatTimestamp(file.lastModified())
        val sessionMetadata = LinkedHashMap<String, String>()
        val records = mutableListOf<ParsedRecord>()

        val readResult = runCatching {
            file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val json = runCatching { JSONObject(line) }.getOrNull()
                    if (json == null) {
                        records += unreadableRecord(
                            lineNumber = index + 1,
                            rawLine = line,
                            reason = "JSON non valido o riga incompleta",
                        )
                        return@forEachIndexed
                    }
                    when (json.optString("type")) {
                        RECORD_TYPE_SESSION -> {
                            id = json.optString("id", id).ifBlank { id }
                            createdAt = json.optString("created_at", createdAt).ifBlank { createdAt }
                            sessionMetadata.putAll(jsonToMetadata(json.optJSONObject("metadata")))
                        }

                        RECORD_TYPE_EVENT -> {
                            records += ParsedRecord(
                                timestamp = json.optString("timestamp", "Orario non disponibile"),
                                level = json.optString("level", Level.INFO.name),
                                section = json.optString("section", SECTION_MENU),
                                tabName = json.optString("tab").takeIf(String::isNotBlank),
                                action = json.optString("action", "Azione non specificata"),
                                metadata = jsonToMetadata(json.optJSONObject("metadata")),
                                errorType = json.optString("error_type").takeIf(String::isNotBlank),
                                errorMessage = json.optString("error_message").takeIf(String::isNotBlank),
                                errorStackTrace = json.optString("error_stacktrace").takeIf(String::isNotBlank),
                            )
                        }

                        else -> {
                            records += unreadableRecord(
                                lineNumber = index + 1,
                                rawLine = line,
                                reason = "Tipo di record sconosciuto",
                            )
                        }
                    }
                }
            }
        }
        if (readResult.isFailure) return null

        return ParsedLog(id, createdAt, sessionMetadata, records)
    }

    private fun unreadableRecord(
        lineNumber: Int,
        rawLine: String,
        reason: String,
    ): ParsedRecord {
        return ParsedRecord(
            timestamp = "Orario non disponibile",
            level = Level.WARNING.name,
            section = SECTION_MENU,
            tabName = null,
            action = "Riga del log non interpretabile",
            metadata = linkedMapOf(
                "numero_riga" to lineNumber.toString(),
                "motivo" to reason,
                "contenuto_integrale" to rawLine,
            ),
            errorType = null,
            errorMessage = null,
            errorStackTrace = null,
        )
    }

    private fun renderLog(log: ParsedLog): String {
        val menuRecords = log.records.filter { it.section != SECTION_TAB }
        val tabRecords = LinkedHashMap<String, MutableList<ParsedRecord>>()
        log.records
            .filter { it.section == SECTION_TAB }
            .forEach { record ->
                val tab = record.tabName ?: "Scheda non identificata"
                tabRecords.getOrPut(tab) { mutableListOf() } += record
            }
        val tabActionCount = tabRecords.values.sumOf { records -> records.size }

        return buildString {
            appendLine("LOG")
            appendLine("════════════════════════════")
            appendLine()
            appendLine("-- INFORMAZIONI MENU")
            appendLine()
            appendLine("--- Riepilogo")
            appendLine("    • Azioni menu: ${menuRecords.size}")
            appendLine("    • Schede consultate: ${tabRecords.size}")
            appendLine("    • Azioni nelle schede: $tabActionCount")
            appendLine()
            appendLine("--- Informazioni sessione")
            appendLine("    • ID: ${log.id}")
            appendLine("    • Creato: ${log.createdAt}")
            log.sessionMetadata.forEach { (key, value) ->
                appendLine("    • ${formatMetadataKey(key)}: $value")
            }
            appendLine()
            appendLine("--- Azioni menu")
            if (menuRecords.isEmpty()) {
                appendLine("    • Nessuna azione di menu registrata")
            } else {
                menuRecords.forEach { appendRenderedRecord(this, "    ", it) }
            }

            appendLine()
            appendLine("-- INFORMAZIONI SCHEDE")
            appendLine()
            if (tabRecords.isEmpty()) {
                appendLine("--- Nessuna scheda aperta in questa sessione")
            } else {
                tabRecords.forEach { (tab, records) ->
                    appendLine("--- $tab")
                    records.forEach { appendRenderedRecord(this, "    ", it) }
                }
            }
        }.trimEnd()
    }

    private fun appendRenderedRecord(builder: StringBuilder, indent: String, record: ParsedRecord) {
        builder.appendLine("$indent[${compactTimestamp(record.timestamp)} | ${formatLevel(record.level)}] ${record.action}")
        record.metadata.forEach { (key, value) ->
            val lines = value.lineSequence().toList().ifEmpty { listOf("") }
            if (lines.size == 1) {
                builder.appendLine("$indent  • ${formatMetadataKey(key)}: ${lines.first()}")
            } else {
                builder.appendLine("$indent  • ${formatMetadataKey(key)}:")
                lines.forEach { line -> builder.appendLine("$indent      $line") }
            }
        }
        record.errorType?.let { builder.appendLine("$indent  • Errore: $it") }
        record.errorMessage?.let { builder.appendLine("$indent  • Dettaglio errore: $it") }
        record.errorStackTrace?.let { stackTrace ->
            builder.appendLine("$indent  • Stack trace:")
            stackTrace.lineSequence().forEach { line ->
                builder.appendLine("$indent      $line")
            }
        }
        builder.appendLine()
    }

    private fun compactTimestamp(timestamp: String): String {
        val compactTime = timestamp
            .substringAfter(' ', timestamp)
            .substringBefore(' ')
            .substringBeforeLast('.')
        return compactTime.takeIf { ':' in it } ?: timestamp
    }

    private fun formatLevel(level: String): String = when (level) {
        Level.DEBUG.name -> "DEBUG"
        Level.INFO.name -> "INFO"
        Level.WARNING.name -> "ATTENZIONE"
        Level.ERROR.name -> "ERRORE"
        else -> level
    }

    private fun metadataToJson(metadata: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        metadata.forEach { (key, value) ->
            val safeKey = sanitizeMetadataKey(key)
            putUnique(json, safeKey, sanitizeMetadataValue(safeKey, value))
        }
        return json
    }

    private fun jsonToMetadata(json: JSONObject?): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (json == null) return result
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            result[key] = renderMetadataValue(json.opt(key))
        }
        return result
    }

    private fun sanitizeMetadataKey(key: Any?): String {
        return sanitizeText(key?.toString().orEmpty())
            .replace(Regex("[^A-Za-z0-9_àèéìòóùÀÈÉÌÒÓÙ -]"), "_")
            .ifBlank { "dato" }
    }

    private fun sanitizeMetadataValue(key: String, value: Any?): Any {
        if (isSensitiveKey(key)) return REDACTED_VALUE
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is SourcedValue -> JSONObject()
                .put(SOURCED_VALUE_MARKER, true)
                .put(
                    "fonti",
                    JSONArray(
                        value.sources
                            .map(::sanitizeMetadataText)
                            .filter(String::isNotBlank)
                            .distinct(),
                    ),
                )
                .put("valore", sanitizeMetadataValue(key, value.value))
                .apply {
                    value.note
                        ?.let(::sanitizeMetadataText)
                        ?.takeIf(String::isNotBlank)
                        ?.let { put("nota", it) }
                }
            is JSONObject -> sanitizeJsonObject(value)
            is JSONArray -> sanitizeJsonArray(key, value)
            is Map<*, *> -> {
                val result = JSONObject()
                value.forEach { (nestedKey, nestedValue) ->
                    val safeNestedKey = sanitizeMetadataKey(nestedKey)
                    putUnique(
                        result,
                        safeNestedKey,
                        sanitizeMetadataValue(safeNestedKey, nestedValue),
                    )
                }
                result
            }
            is Iterable<*> -> JSONArray().apply {
                value.forEach { item -> put(sanitizeMetadataValue(key, item)) }
            }
            is Array<*> -> JSONArray().apply {
                value.forEach { item -> put(sanitizeMetadataValue(key, item)) }
            }
            is BooleanArray -> JSONArray().apply { value.forEach { put(it) } }
            is ByteArray -> JSONArray().apply { value.forEach { put(it.toInt()) } }
            is ShortArray -> JSONArray().apply { value.forEach { put(it.toInt()) } }
            is IntArray -> JSONArray().apply { value.forEach { put(it) } }
            is LongArray -> JSONArray().apply { value.forEach { put(it) } }
            is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
            is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
            is CharArray -> JSONArray().apply { value.forEach { put(it.toString()) } }
            is Boolean, is Number -> value
            is Throwable -> JSONObject()
                .put("tipo", value.javaClass.name)
                .put("messaggio", sanitizeMetadataText(value.message.orEmpty()))
                .put("stack_trace", sanitizeMetadataText(value.stackTraceToString()))
            else -> sanitizeMetadataText(value.toString())
        }
    }

    private fun sanitizeJsonObject(value: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = value.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val safeKey = sanitizeMetadataKey(rawKey)
            putUnique(result, safeKey, sanitizeMetadataValue(safeKey, value.opt(rawKey)))
        }
        return result
    }

    private fun putUnique(target: JSONObject, baseKey: String, value: Any?) {
        var key = baseKey
        var duplicateIndex = 2
        while (target.has(key)) {
            key = "${baseKey}_$duplicateIndex"
            duplicateIndex += 1
        }
        target.put(key, value)
    }

    private fun sanitizeJsonArray(key: String, value: JSONArray): JSONArray {
        return JSONArray().apply {
            for (index in 0 until value.length()) {
                put(sanitizeMetadataValue(key, value.opt(index)))
            }
        }
    }

    private fun renderMetadataValue(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return "null"
        if (value is JSONObject && value.optBoolean(SOURCED_VALUE_MARKER, false)) {
            val sources = value.optJSONArray("fonti")
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }
                .orEmpty()
            val renderedValue = renderPlainJsonValue(value.opt("valore"))
            return buildString {
                append("Fonte: ")
                append(sources.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "non identificata")
                appendLine()
                if ('\n' in renderedValue) {
                    appendLine("Valore:")
                    append(renderedValue)
                } else {
                    append("Valore: ")
                    append(renderedValue)
                }
                value.optString("nota")
                    .takeIf(String::isNotBlank)
                    ?.let { note ->
                        appendLine()
                        append("Nota: ")
                        append(note)
                    }
            }
        }
        return renderPlainJsonValue(value)
    }

    private fun renderPlainJsonValue(value: Any?): String {
        val displayValue = jsonValueForDisplay(value)
        return when (displayValue) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> displayValue.toString(2)
            is JSONArray -> displayValue.toString(2)
            else -> displayValue.toString()
        }
    }

    private fun jsonValueForDisplay(value: Any?): Any? {
        if (value is JSONObject && value.optBoolean(SOURCED_VALUE_MARKER, false)) {
            return JSONObject()
                .put("fonte", jsonValueForDisplay(value.optJSONArray("fonti") ?: JSONArray()))
                .put("valore", jsonValueForDisplay(value.opt("valore")))
                .apply {
                    value.optString("nota")
                        .takeIf(String::isNotBlank)
                        ?.let { put("nota", it) }
                }
        }
        if (value is JSONObject) {
            val display = JSONObject()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                display.put(key, jsonValueForDisplay(value.opt(key)))
            }
            return display
        }
        if (value is JSONArray) {
            return JSONArray().apply {
                for (index in 0 until value.length()) {
                    put(jsonValueForDisplay(value.opt(index)))
                }
            }
        }
        return value
    }

    private fun sanitizeTabName(tabName: String): String {
        return sanitizeText(tabName).ifBlank { "Scheda non identificata" }
    }

    private fun sanitizeText(value: String): String {
        return sanitizeMetadataText(value)
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun sanitizeMetadataText(value: String): String {
        return value
            .replace(SENSITIVE_VALUE_PATTERN, "[omesso per privacy]")
            .replace(URL_PATTERN) { match -> sanitizeUrl(match.value) }
            .trim()
    }

    private fun sanitizeUrl(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "[URL omesso]"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.trim()
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) return "[URL omesso]"
        val authority = buildString {
            append(host)
            if (uri.port != -1) append(":${uri.port}")
        }
        val path = uri.encodedPath.orEmpty().takeIf { it != "/" }.orEmpty()
        val query = uri.encodedQuery
            ?.split('&')
            ?.joinToString("&") { component ->
                val encodedKey = component.substringBefore('=')
                val decodedKey = Uri.decode(encodedKey)
                if (isSensitiveQueryParameter(decodedKey)) {
                    "$encodedKey=$REDACTED_QUERY_VALUE"
                } else {
                    component
                }
            }
            ?.takeIf(String::isNotBlank)
            ?.let { "?$it" }
            .orEmpty()
        val fragment = uri.encodedFragment
            ?.takeUnless(SENSITIVE_VALUE_PATTERN::containsMatchIn)
            ?.let { "#$it" }
            .orEmpty()
        return "$scheme://$authority$path$query$fragment"
    }

    private fun isSensitiveKey(key: String): Boolean = SENSITIVE_KEY_PATTERN.containsMatchIn(key)

    private fun isSensitiveQueryParameter(key: String): Boolean {
        return SENSITIVE_QUERY_PARAMETER_PATTERN.containsMatchIn(key)
    }

    private fun formatMetadataKey(key: String): String {
        return key.replace('_', ' ').replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ITALY) else char.toString()
        }
    }

    private fun currentTimestamp(): String = formatTimestamp(System.currentTimeMillis())

    private fun sessionFileTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Rome")
        }.format(Date())
    }

    private fun formatTimestamp(epochMillis: Long): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS z", Locale.ITALY).apply {
            timeZone = TimeZone.getTimeZone("Europe/Rome")
        }.format(Date(epochMillis))
    }

    private val SENSITIVE_KEY_PATTERN = Regex(
        """(?i)(token|authorization|cookie|password|passwd|secret|api[-_]?key|apikey|credential|bearer|(?:php|j)?session(?:id|token|cookie))""",
    )
    private val SENSITIVE_QUERY_PARAMETER_PATTERN = Regex(
        """(?i)(token|authorization|auth|cookie|password|passwd|secret|api[-_]?key|apikey|session|credential|bearer|signature|signed|sig|access[-_]?key|private[-_]?key)""",
    )
    private val SENSITIVE_VALUE_PATTERN = Regex(
        """(?i)(bearer\s+[^\s,;]+|(?:token|authorization|cookie|password|secret|api[-_]?key)\s*[=:]\s*[^\s,;]+)""",
    )
    private val URL_PATTERN = Regex("""https?://[^\s'"]+""", RegexOption.IGNORE_CASE)
    private const val SOURCED_VALUE_MARKER = "__streamcenter_sourced_value"
    private const val REDACTED_VALUE = "[omesso per privacy]"
    private const val REDACTED_QUERY_VALUE = "%5Bomesso%20per%20privacy%5D"
}
