package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal data class StreamCenterBackupFile(
    val name: String,
    val lastModified: Long,
    val size: Long,
    val uri: Uri? = null,
    val file: File? = null,
)

internal data class StreamCenterBackupRestoreResult(
    val preferenceCount: Int,
    val sourceVersion: String,
)

internal object StreamCenterBackupManager {
    private const val STORAGE_PREFERENCES = "StreamCenterBackupStorage"
    private const val STORAGE_DIRECTORY_URI = "directoryUri"
    private const val BACKUP_FORMAT = "StreamCenterBackup"
    private const val BACKUP_SCHEMA_VERSION = 1
    private const val BACKUP_EXTENSION = ".streamcenter"
    private const val BACKUP_MIME_TYPE = "application/vnd.streamcenter.backup"
    private val backupExtensionPattern = Regex(
        "(?:\\.streamcenter(?:\\.json)?|\\.json)$",
        RegexOption.IGNORE_CASE,
    )

    fun defaultFileName(): String {
        val timestamp = SimpleDateFormat("dd-MM-yyyy-HH-mm-ss", Locale.ITALY).format(Date())
        return "StreamCenter_${timestamp}-V${BuildConfig.PLUGIN_VERSION}"
    }

    fun resolveFileNameTemplate(template: String): String {
        val calendar = Calendar.getInstance(Locale.ITALY).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
        }
        val replacements = listOf(
            Regex("%Ora%", RegexOption.IGNORE_CASE) to twoDigits(calendar.get(Calendar.HOUR_OF_DAY)),
            Regex("%Minuti%", RegexOption.IGNORE_CASE) to twoDigits(calendar.get(Calendar.MINUTE)),
            Regex("%Secondi%", RegexOption.IGNORE_CASE) to twoDigits(calendar.get(Calendar.SECOND)),
            Regex("%Versione%", RegexOption.IGNORE_CASE) to BuildConfig.PLUGIN_VERSION,
        )
        return replacements.fold(
            StreamCenterPlugin.resolveHomeTitlePlaceholders(template, calendar),
        ) { resolvedName, (pattern, value) ->
            pattern.replace(resolvedName, value)
        }
    }

    fun normalizedFileName(template: String): String {
        val withoutExtension = fileNameWithoutExtension(template)
        val resolved = resolveFileNameTemplate(withoutExtension)
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "-")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(120)
        val baseName = resolved.ifBlank { defaultFileName() }
        return "$baseName$BACKUP_EXTENSION"
    }

    fun fileNameWithoutExtension(fileName: String): String {
        return fileName.trim().replace(backupExtensionPattern, "")
    }

    fun selectDirectory(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val permissionFlags = directoryPermissionFlags()
        resolver.takePersistableUriPermission(uri, permissionFlags)
        val preferences = storagePreferences(context)
        val previousUri = preferences.getString(STORAGE_DIRECTORY_URI, null)
            ?.let(Uri::parse)
            ?.takeIf { it != uri }
        check(preferences.edit().putString(STORAGE_DIRECTORY_URI, uri.toString()).commit()) {
            "Impossibile salvare il percorso scelto."
        }
        previousUri?.let {
            runCatching { resolver.releasePersistableUriPermission(it, permissionFlags) }
        }
    }

    fun resetDirectory(context: Context) {
        val preferences = storagePreferences(context)
        val previousUri = preferences.getString(STORAGE_DIRECTORY_URI, null)?.let(Uri::parse)
        check(preferences.edit().clear().commit()) {
            "Impossibile ripristinare il percorso predefinito."
        }
        previousUri?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(it, directoryPermissionFlags())
            }
        }
    }

    fun locationLabel(context: Context): String {
        val treeUri = selectedTreeUri(context)
            ?: return "Archivio privato dell'app › Documents › StreamCenter · predefinito"
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return "Cartella personalizzata"
        val volume = documentId.substringBefore(':', "")
        val path = documentId.substringAfter(':', documentId).replace("/", " › ")
        val prefix = when {
            volume.equals("primary", ignoreCase = true) -> "Memoria interna"
            volume.isNotBlank() -> volume
            else -> "Cartella"
        }
        return listOf(prefix, path).filter { it.isNotBlank() }.joinToString(" › ")
    }

    fun export(
        context: Context,
        preferences: SharedPreferences,
        requestedName: String,
    ): StreamCenterBackupFile {
        val content = createBackupJson(preferences).toString(2)
        val requestedFileName = normalizedFileName(requestedName)
        val treeUri = selectedTreeUri(context)
        return if (treeUri == null) {
            exportToDefaultDirectory(context, requestedFileName, content)
        } else {
            exportToTree(context, treeUri, requestedFileName, content)
        }
    }

    fun list(context: Context): List<StreamCenterBackupFile> {
        val files = selectedTreeUri(context)?.let { listTreeFiles(context, it) }
            ?: listDefaultFiles(context)
        return files.sortedWith(
            compareByDescending<StreamCenterBackupFile> { it.lastModified }
                .thenByDescending { it.name.lowercase(Locale.ITALY) },
        )
    }

    fun renameBackup(
        context: Context,
        backupFile: StreamCenterBackupFile,
        requestedName: String,
    ): StreamCenterBackupFile {
        val normalizedName = normalizedFileName(requestedName)
        if (normalizedName.equals(backupFile.name, ignoreCase = true)) return backupFile
        backupFile.file?.let { source ->
            val directory = source.parentFile
                ?: throw IllegalStateException("La cartella del backup non è disponibile.")
            val existingNames = directory.listFiles()
                .orEmpty()
                .filterNot { it.absolutePath == source.absolutePath }
                .mapTo(mutableSetOf()) { it.name.lowercase(Locale.ITALY) }
            val target = File(directory, uniqueName(normalizedName, existingNames))
            check(source.exists() && source.renameTo(target)) { "Impossibile rinominare il backup." }
            return StreamCenterBackupFile(
                name = target.name,
                lastModified = target.lastModified(),
                size = target.length(),
                file = target,
            )
        }
        val sourceUri = backupFile.uri
            ?: throw IllegalArgumentException("Il file di backup non è più disponibile.")
        val treeUri = selectedTreeUri(context)
            ?: throw IllegalStateException("La cartella del backup non è più selezionata.")
        val existingNames = listTreeFiles(context, treeUri)
            .filterNot { it.uri == sourceUri }
            .mapTo(mutableSetOf()) { it.name.lowercase(Locale.ITALY) }
        val targetName = uniqueName(normalizedName, existingNames)
        val targetUri = DocumentsContract.renameDocument(
            context.contentResolver,
            sourceUri,
            targetName,
        ) ?: throw IllegalStateException("Impossibile rinominare il backup.")
        return StreamCenterBackupFile(
            name = targetName,
            lastModified = System.currentTimeMillis(),
            size = backupFile.size,
            uri = targetUri,
        )
    }

    fun deleteBackup(context: Context, backupFile: StreamCenterBackupFile) {
        backupFile.file?.let { file ->
            check(file.exists() && file.delete()) { "Impossibile eliminare il backup." }
            return
        }
        val uri = backupFile.uri
            ?: throw IllegalArgumentException("Il file di backup non è più disponibile.")
        check(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            "Impossibile eliminare il backup."
        }
    }

    fun import(
        context: Context,
        preferences: SharedPreferences,
        backupFile: StreamCenterBackupFile,
    ): StreamCenterBackupRestoreResult {
        val backup = parseBackup(readText(context, backupFile))
        val editor = preferences.edit().clear()
        backup.preferences.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Il ripristino delle impostazioni non è riuscito." }
        return StreamCenterBackupRestoreResult(
            preferenceCount = backup.preferences.size,
            sourceVersion = backup.pluginVersion,
        )
    }

    private data class ParsedBackup(
        val pluginVersion: String,
        val preferences: Map<String, Any>,
    )

    private fun createBackupJson(preferences: SharedPreferences): JSONObject {
        val values = JSONObject()
        preferences.all.toSortedMap().forEach { (key, value) ->
            val encoded = JSONObject()
            when (value) {
                is String -> encoded.put("type", "string").put("value", value)
                is Boolean -> encoded.put("type", "boolean").put("value", value)
                is Int -> encoded.put("type", "int").put("value", value)
                is Long -> encoded.put("type", "long").put("value", value)
                is Float -> encoded.put("type", "float").put("value", value.toDouble())
                is Set<*> -> encoded.put("type", "stringSet").put(
                    "value",
                    JSONArray(value.filterIsInstance<String>().sorted()),
                )
                else -> return@forEach
            }
            values.put(key, encoded)
        }
        return JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("schemaVersion", BACKUP_SCHEMA_VERSION)
            .put("pluginVersion", BuildConfig.PLUGIN_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("preferenceCount", values.length())
            .put("preferences", values)
    }

    private fun parseBackup(content: String): ParsedBackup {
        val root = runCatching { JSONObject(content) }.getOrElse {
            throw IllegalArgumentException("Il file selezionato non contiene un backup valido.")
        }
        require(root.optString("format") == BACKUP_FORMAT) {
            "Il file selezionato non è un backup di StreamCenter."
        }
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(schemaVersion in 1..BACKUP_SCHEMA_VERSION) {
            "Questa versione del backup non è ancora supportata."
        }
        val encodedPreferences = root.optJSONObject("preferences")
            ?: throw IllegalArgumentException("Il backup non contiene alcuna configurazione valida.")
        val decodedPreferences = linkedMapOf<String, Any>()
        val keys = encodedPreferences.keys()
        runCatching {
            while (keys.hasNext()) {
                val key = keys.next()
                val encoded = encodedPreferences.optJSONObject(key)
                    ?: throw IllegalArgumentException("Il backup contiene una preferenza non valida.")
                val value = when (encoded.optString("type")) {
                    "string" -> encoded.getString("value")
                    "boolean" -> encoded.getBoolean("value")
                    "int" -> encoded.getInt("value")
                    "long" -> encoded.getLong("value")
                    "float" -> encoded.getDouble("value").toFloat()
                    "stringSet" -> encoded.getJSONArray("value").let { array ->
                        buildSet {
                            repeat(array.length()) { index -> add(array.getString(index)) }
                        }
                    }
                    else -> throw IllegalArgumentException("Il backup contiene un tipo di preferenza non supportato.")
                }
                decodedPreferences[key] = value
            }
        }.getOrElse { error ->
            if (error is IllegalArgumentException) throw error
            throw IllegalArgumentException("Il backup contiene dati danneggiati.")
        }
        return ParsedBackup(
            pluginVersion = root.optString("pluginVersion", "sconosciuta"),
            preferences = decodedPreferences,
        )
    }

    private fun exportToDefaultDirectory(
        context: Context,
        requestedFileName: String,
        content: String,
    ): StreamCenterBackupFile {
        val directory = defaultDirectory(context)
        check(directory.exists() || directory.mkdirs()) { "Impossibile creare la cartella dei backup." }
        val file = uniqueFile(directory, requestedFileName)
        val temporaryFile = File(directory, ".${file.name}.${System.nanoTime()}.tmp")
        runCatching {
            temporaryFile.writeText(content, Charsets.UTF_8)
            check(temporaryFile.renameTo(file)) { "Impossibile completare il salvataggio del backup." }
        }.onFailure {
            temporaryFile.delete()
            throw it
        }
        return StreamCenterBackupFile(file.name, file.lastModified(), file.length(), file = file)
    }

    private fun exportToTree(
        context: Context,
        treeUri: Uri,
        requestedFileName: String,
        content: String,
    ): StreamCenterBackupFile {
        val existingNames = listTreeFiles(context, treeUri).mapTo(mutableSetOf()) { it.name.lowercase(Locale.ITALY) }
        val fileName = uniqueName(requestedFileName, existingNames)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val documentUri = DocumentsContract.createDocument(
            context.contentResolver,
            rootDocumentUri,
            BACKUP_MIME_TYPE,
            fileName,
        ) ?: throw IllegalStateException("Impossibile creare il file di backup nella cartella scelta.")
        runCatching {
            context.contentResolver.openOutputStream(documentUri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(content)
            } ?: throw IllegalStateException("Impossibile scrivere il file di backup.")
        }.onFailure {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, documentUri) }
            throw it
        }
        return StreamCenterBackupFile(fileName, System.currentTimeMillis(), content.length.toLong(), uri = documentUri)
    }

    private fun listDefaultFiles(context: Context): List<StreamCenterBackupFile> {
        val directory = defaultDirectory(context)
        if (!directory.exists()) return emptyList()
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && isBackupFileName(it.name) }
            .map { StreamCenterBackupFile(it.name, it.lastModified(), it.length(), file = it) }
    }

    private fun listTreeFiles(context: Context, treeUri: Uri): List<StreamCenterBackupFile> {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(mimeTypeIndex)
                    val name = cursor.getString(nameIndex).orEmpty()
                    if (
                        mimeType != DocumentsContract.Document.MIME_TYPE_DIR &&
                        isBackupFileName(name)
                    ) {
                        val childDocumentId = cursor.getString(documentIdIndex)
                        add(
                            StreamCenterBackupFile(
                                name = name,
                                lastModified = cursor.getLong(modifiedIndex),
                                size = cursor.getLong(sizeIndex),
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId),
                            ),
                        )
                    }
                }
            }
        }.orEmpty()
    }

    private fun readText(context: Context, backupFile: StreamCenterBackupFile): String {
        backupFile.file?.let { return it.readText(Charsets.UTF_8) }
        val uri = backupFile.uri ?: throw IllegalArgumentException("Il file di backup non è più disponibile.")
        return context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Impossibile leggere il file di backup.")
    }

    private fun selectedTreeUri(context: Context): Uri? {
        return storagePreferences(context).getString(STORAGE_DIRECTORY_URI, null)?.let(Uri::parse)
    }

    private fun storagePreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(STORAGE_PREFERENCES, Context.MODE_PRIVATE)
    }

    private fun directoryPermissionFlags(): Int {
        return Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private fun defaultDirectory(context: Context): File {
        val documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "documents")
        return File(documents, "StreamCenter")
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val existingNames = directory.listFiles().orEmpty().mapTo(mutableSetOf()) { it.name.lowercase(Locale.ITALY) }
        return File(directory, uniqueName(requestedName, existingNames))
    }

    private fun uniqueName(requestedName: String, existingNames: Set<String>): String {
        if (requestedName.lowercase(Locale.ITALY) !in existingNames) return requestedName
        val baseName = requestedName.removeSuffix(BACKUP_EXTENSION)
        var copyNumber = 2
        while (true) {
            val candidate = "$baseName ($copyNumber)$BACKUP_EXTENSION"
            if (candidate.lowercase(Locale.ITALY) !in existingNames) return candidate
            copyNumber += 1
        }
    }

    private fun isBackupFileName(fileName: String): Boolean {
        return fileName.endsWith(BACKUP_EXTENSION, ignoreCase = true) ||
            fileName.endsWith("$BACKUP_EXTENSION.json", ignoreCase = true)
    }

    private fun twoDigits(value: Int): String = String.format(Locale.ITALY, "%02d", value)
}
