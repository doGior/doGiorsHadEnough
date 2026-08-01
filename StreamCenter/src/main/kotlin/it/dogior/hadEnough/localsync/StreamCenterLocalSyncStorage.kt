package it.dogior.hadEnough.localsync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import it.dogior.hadEnough.settings.StreamCenterConfigurationStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object StreamCenterLocalSyncStorage {
    private const val DATASTORE_PREFERENCES = "rebuild_preference"
    private const val FORMAT = "StreamCenterLocalSync"
    private const val FORMAT_VERSION = 2
    private const val MAX_UNCOMPRESSED_BYTES = 32 * 1024 * 1024
    private const val ACCOUNT_INDEX_KEY = "data_store_helper/account_key_index"
    private const val BACKUP_HEADER_PREFIX = "BACKUP_download_header_cache"
    private const val DOWNLOAD_HEADER_PREFIX = "download_header_cache"

    private val libraryFolders = setOf(
        "result_watch_state",
        "result_watch_state_data",
        "result_subscribed_state_data",
        "result_favorites_state_data",
        "result_resume_watching_2",
        "result_resume_watching",
        "result_episode",
        "result_season",
        "result_dub",
        "video_watch_state",
        "video_pos_dur",
    )

    private val nonTransferableKeyParts = listOf(
        "anilist_cached_list",
        "mal_cached_list",
        "kitsu_cached_list",
        "PLUGINS_KEY",
        "PLUGINS_KEY_LOCAL",
        "auth_tokens",
        "auth_ids",
        "biometric_key",
        "nginx_user",
        "download_path_key",
        "download_path_key_visual",
        "backup_path_key",
        "backup_dir_path_key",
        "anilist_token",
        "anilist_user",
        "mal_user",
        "mal_token",
        "mal_refresh_token",
        "mal_unixtime",
        "open_subtitles_user",
        "subdl_user",
        "simkl_token",
        "BACKUP_download_episode_cache",
        "download_episode_cache",
        "download_info",
        "download_resume_queue_key",
        "download_resume_2",
        "download_queue_key",
        "auto_download_plugins_key2",
    )

    fun createPayload(context: Context, type: StreamCenterLocalSyncPayloadType): StreamCenterLocalSyncPayload {
        val datastore = datastore(context)
        val settings = settings(context)
        val streamCenterPreferences = StreamCenterConfigurationStore.preferences(context)
        val account = currentAccount(datastore)
        val datastoreValues = when (type) {
            StreamCenterLocalSyncPayloadType.ALL,
            StreamCenterLocalSyncPayloadType.CLOUDSTREAM -> cloudStreamConfigurationValues(datastore.all)
            StreamCenterLocalSyncPayloadType.LIBRARY,
            StreamCenterLocalSyncPayloadType.STREAMCENTER -> emptyMap()
        }
        val settingsValues = when (type) {
            StreamCenterLocalSyncPayloadType.ALL,
            StreamCenterLocalSyncPayloadType.CLOUDSTREAM -> settings.all.filterKeys(::isTransferable)
            StreamCenterLocalSyncPayloadType.LIBRARY,
            StreamCenterLocalSyncPayloadType.STREAMCENTER -> emptyMap()
        }
        val libraryValues = when (type) {
            StreamCenterLocalSyncPayloadType.ALL,
            StreamCenterLocalSyncPayloadType.LIBRARY -> libraryValues(datastore.all, account)
            StreamCenterLocalSyncPayloadType.CLOUDSTREAM,
            StreamCenterLocalSyncPayloadType.STREAMCENTER -> emptyMap()
        }
        val streamCenterValues = when (type) {
            StreamCenterLocalSyncPayloadType.ALL,
            StreamCenterLocalSyncPayloadType.STREAMCENTER ->
                StreamCenterConfigurationStore.snapshot(streamCenterPreferences)
            StreamCenterLocalSyncPayloadType.CLOUDSTREAM,
            StreamCenterLocalSyncPayloadType.LIBRARY -> emptyMap()
        }
        val libraryItemCount = libraryItemCount(libraryValues.keys)
        val progressCount = progressCount(libraryValues.keys)
        val entryCount = datastoreValues.size + settingsValues.size + libraryValues.size + streamCenterValues.size
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("type", type.wireValue)
            .put("createdAt", System.currentTimeMillis())
            .put("sourceDevice", deviceName())
            .put(
                "sourceAccount",
                account.takeIf {
                    type == StreamCenterLocalSyncPayloadType.ALL || type == StreamCenterLocalSyncPayloadType.LIBRARY
                },
            )
            .put("datastore", encodePreferences(datastoreValues))
            .put("settings", encodePreferences(settingsValues))
            .put("library", encodePreferences(libraryValues))
            .put("streamCenter", encodePreferences(streamCenterValues))
            .put(
                "stats",
                JSONObject()
                    .put("entries", entryCount)
                    .put("libraryItems", libraryItemCount)
                    .put("progressEntries", progressCount),
            )
        val uncompressed = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(uncompressed.size <= MAX_UNCOMPRESSED_BYTES) { "La configurazione supera il limite di sicurezza." }
        return StreamCenterLocalSyncPayload(
            type = type,
            compressedBytes = compress(uncompressed),
            uncompressedSize = uncompressed.size,
            entryCount = entryCount,
            libraryItemCount = libraryItemCount,
            progressCount = progressCount,
            sourceAccount = account.takeIf {
                type == StreamCenterLocalSyncPayloadType.ALL || type == StreamCenterLocalSyncPayloadType.LIBRARY
            },
        )
    }

    fun applyPayload(
        context: Context,
        compressedBytes: ByteArray,
        expectedType: StreamCenterLocalSyncPayloadType,
    ): StreamCenterLocalSyncResult {
        val root = JSONObject(String(decompress(compressedBytes), StandardCharsets.UTF_8))
        require(root.optString("format") == FORMAT) { "Formato di sincronizzazione non riconosciuto." }
        require(root.optInt("version", -1) == FORMAT_VERSION) { "Versione di sincronizzazione non supportata." }
        val type = StreamCenterLocalSyncPayloadType.fromWireValue(root.optString("type"))
            ?: throw IllegalArgumentException("Tipo di configurazione non valido.")
        require(type == expectedType) { "Il contenuto ricevuto non corrisponde all'offerta." }
        val datastoreValues = decodePreferences(root.getJSONObject("datastore"))
        val settingsValues = decodePreferences(root.getJSONObject("settings"))
        val libraryValues = decodePreferences(root.getJSONObject("library"))
        val streamCenterValues = root.optJSONObject("streamCenter")
            ?.let(::decodePreferences)
            .orEmpty()
        when (type) {
            StreamCenterLocalSyncPayloadType.ALL ->
                applyAll(context, datastoreValues, settingsValues, libraryValues, streamCenterValues)
            StreamCenterLocalSyncPayloadType.CLOUDSTREAM ->
                applyCloudStream(context, datastoreValues, settingsValues, libraryValues, streamCenterValues)
            StreamCenterLocalSyncPayloadType.LIBRARY -> {
                require(datastoreValues.isEmpty()) { "La libreria contiene configurazioni CloudStream inattese." }
                require(streamCenterValues.isEmpty()) { "La libreria contiene dati StreamCenter inattesi." }
                applyLibrary(context, libraryValues, settingsValues)
            }
            StreamCenterLocalSyncPayloadType.STREAMCENTER -> {
                require(libraryValues.isEmpty()) { "La configurazione StreamCenter contiene una libreria inattesa." }
                applyStreamCenter(context, datastoreValues, settingsValues, streamCenterValues)
            }
        }
        val stats = root.optJSONObject("stats")
        return StreamCenterLocalSyncResult(
            type = type,
            sent = false,
            entryCount = datastoreValues.size + settingsValues.size + libraryValues.size + streamCenterValues.size,
            libraryItemCount = stats?.optInt("libraryItems", 0) ?: 0,
            progressCount = stats?.optInt("progressEntries", 0) ?: 0,
            peerName = root.optString("sourceDevice", "Dispositivo remoto"),
            restartRequired = true,
        )
    }

    fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter(String::isNotBlank)
            .distinctBy { value -> value.lowercase(Locale.ROOT) }
            .joinToString(" ")
            .ifBlank { "Dispositivo Android" }
            .take(80)
    }

    private fun applyAll(
        context: Context,
        datastoreValues: Map<String, Any>,
        settingsValues: Map<String, Any>,
        libraryValues: Map<String, Any>,
        streamCenterValues: Map<String, Any>,
    ) {
        validateCloudStreamConfiguration(datastoreValues, settingsValues)
        require(libraryValues.keys.all(::isValidLibraryPayloadKey)) { "La libreria contiene chiavi non valide." }
        val datastore = datastore(context)
        val settings = settings(context)
        val streamCenter = StreamCenterConfigurationStore.preferences(context)
        val previousDatastore = datastore.all.toMap()
        val previousSettings = settings.all.toMap()
        val previousStreamCenter = streamCenter.all.toMap()
        runCatching {
            replaceCloudStreamConfiguration(datastore, datastoreValues)
            replaceTransferable(settings, settingsValues)
            replaceLibrary(datastore, libraryValues)
            StreamCenterConfigurationStore.replace(streamCenter, streamCenterValues)
        }.getOrElse { error ->
            runCatching { replaceAll(settings, previousSettings) }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { replaceAll(datastore, previousDatastore) }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { replaceAll(streamCenter, previousStreamCenter) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun applyCloudStream(
        context: Context,
        datastoreValues: Map<String, Any>,
        settingsValues: Map<String, Any>,
        libraryValues: Map<String, Any>,
        streamCenterValues: Map<String, Any>,
    ) {
        require(libraryValues.isEmpty()) { "La configurazione CloudStream contiene una libreria inattesa." }
        require(streamCenterValues.isEmpty()) { "La configurazione CloudStream contiene dati StreamCenter inattesi." }
        validateCloudStreamConfiguration(datastoreValues, settingsValues)
        val datastore = datastore(context)
        val settings = settings(context)
        val previousDatastore = datastore.all.toMap()
        val previousSettings = settings.all.toMap()
        runCatching {
            replaceTransferable(settings, settingsValues)
            replaceCloudStreamConfiguration(datastore, datastoreValues)
        }.getOrElse { error ->
            runCatching { replaceAll(settings, previousSettings) }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { replaceAll(datastore, previousDatastore) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun validateCloudStreamConfiguration(
        datastoreValues: Map<String, Any>,
        settingsValues: Map<String, Any>,
    ) {
        require(datastoreValues.keys.all(::isCloudStreamConfigurationKey)) {
            "Il datastore contiene dati che non appartengono alla configurazione CloudStream."
        }
        require(settingsValues.keys.all(::isTransferable)) { "Le impostazioni contengono chiavi non trasferibili." }
    }

    private fun applyLibrary(
        context: Context,
        datastoreValues: Map<String, Any>,
        settingsValues: Map<String, Any>,
    ) {
        require(settingsValues.isEmpty()) { "Una libreria locale non può modificare le impostazioni." }
        require(datastoreValues.keys.all(::isValidLibraryPayloadKey)) { "La libreria contiene chiavi non valide." }
        val datastore = datastore(context)
        replaceLibrary(datastore, datastoreValues)
    }

    private fun replaceLibrary(
        datastore: SharedPreferences,
        libraryValues: Map<String, Any>,
    ) {
        val account = currentAccount(datastore)
        val currentLibraryKeys = datastore.all.keys.filter { key -> isLibraryKey(key, account) }
        val currentIds = currentLibraryKeys.mapTo(mutableSetOf()) { key -> key.substringAfterLast('/') }
        val keysToRemove = currentLibraryKeys + datastore.all.keys.filter { key ->
            val prefix = key.substringBefore('/')
            val id = key.substringAfterLast('/')
            prefix in setOf(BACKUP_HEADER_PREFIX, DOWNLOAD_HEADER_PREFIX) && id in currentIds
        }
        val editor = datastore.edit()
        keysToRemove.forEach(editor::remove)
        libraryValues.forEach { (relativeKey, value) ->
            val targetKey = if (relativeKey.startsWith("@global/")) {
                relativeKey.removePrefix("@global/")
            } else {
                "$account/$relativeKey"
            }
            put(editor, targetKey, value)
        }
        check(editor.commit()) { "Non è stato possibile applicare la libreria locale." }
        val expectedKeys = libraryValues.keys.mapTo(mutableSetOf()) { relativeKey ->
            if (relativeKey.startsWith("@global/")) relativeKey.removePrefix("@global/") else "$account/$relativeKey"
        }
        check(expectedKeys.all(datastore::contains)) { "La verifica della libreria ricevuta non è riuscita." }
    }

    private fun applyStreamCenter(
        context: Context,
        datastoreValues: Map<String, Any>,
        settingsValues: Map<String, Any>,
        streamCenterValues: Map<String, Any>,
    ) {
        require(datastoreValues.isEmpty()) { "La configurazione StreamCenter non può modificare il datastore CloudStream." }
        require(settingsValues.isEmpty()) { "La configurazione StreamCenter non può modificare le impostazioni CloudStream." }
        StreamCenterConfigurationStore.replace(
            StreamCenterConfigurationStore.preferences(context),
            streamCenterValues,
        )
    }

    private fun replaceTransferable(preferences: SharedPreferences, values: Map<String, Any>) {
        val editor = preferences.edit()
        preferences.all.keys.filter(::isTransferable).forEach(editor::remove)
        values.forEach { (key, value) -> put(editor, key, value) }
        check(editor.commit()) { "Non è stato possibile applicare la configurazione ricevuta." }
    }

    private fun replaceCloudStreamConfiguration(
        preferences: SharedPreferences,
        values: Map<String, Any>,
    ) {
        val editor = preferences.edit()
        preferences.all.keys.filter(::isCloudStreamConfigurationKey).forEach(editor::remove)
        values.forEach { (key, value) -> put(editor, key, value) }
        check(editor.commit()) { "Non è stato possibile applicare la configurazione CloudStream." }
    }

    private fun replaceAll(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> value?.let { put(editor, key, it) } }
        check(editor.commit()) { "Non è stato possibile ripristinare la configurazione precedente." }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
            is Float -> editor.putFloat(key, value)
            is Long -> editor.putLong(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> throw IllegalArgumentException("Tipo di preferenza non supportato per $key.")
        }
    }

    private fun encodePreferences(values: Map<String, *>): JSONObject {
        val buckets = linkedMapOf(
            "_Bool" to JSONObject(),
            "_Int" to JSONObject(),
            "_String" to JSONObject(),
            "_Float" to JSONObject(),
            "_Long" to JSONObject(),
            "_StringSet" to JSONObject(),
        )
        values.toSortedMap().forEach { (key, value) ->
            when (value) {
                is Boolean -> buckets.getValue("_Bool").put(key, value)
                is Int -> buckets.getValue("_Int").put(key, value)
                is String -> buckets.getValue("_String").put(key, value)
                is Float -> buckets.getValue("_Float").put(key, value.toDouble())
                is Long -> buckets.getValue("_Long").put(key, value)
                is Set<*> -> {
                    require(value.all { item -> item is String }) { "La preferenza $key contiene valori non supportati." }
                    buckets.getValue("_StringSet").put(
                        key,
                        JSONArray(value.filterIsInstance<String>().sorted()),
                    )
                }
                null -> Unit
                else -> throw IllegalArgumentException("Tipo di preferenza non supportato per $key.")
            }
        }
        return JSONObject().apply {
            buckets.forEach { (name, bucket) -> put(name, bucket) }
        }
    }

    private fun decodePreferences(root: JSONObject): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        decodeBucket(root, "_Bool") { bucket, key -> bucket.getBoolean(key) }.forEach { putUnique(result, it) }
        decodeBucket(root, "_Int") { bucket, key -> bucket.getInt(key) }.forEach { putUnique(result, it) }
        decodeBucket(root, "_String") { bucket, key -> bucket.getString(key) }.forEach { putUnique(result, it) }
        decodeBucket(root, "_Float") { bucket, key -> bucket.getDouble(key).toFloat() }.forEach { putUnique(result, it) }
        decodeBucket(root, "_Long") { bucket, key -> bucket.getLong(key) }.forEach { putUnique(result, it) }
        decodeBucket(root, "_StringSet") { bucket, key ->
            val array = bucket.getJSONArray(key)
            buildSet { repeat(array.length()) { index -> add(array.getString(index)) } }
        }.forEach { putUnique(result, it) }
        return result
    }

    private fun decodeBucket(
        root: JSONObject,
        name: String,
        decoder: (JSONObject, String) -> Any,
    ): List<Pair<String, Any>> {
        val bucket = root.optJSONObject(name) ?: return emptyList()
        return buildList {
            val keys = bucket.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(key.isNotBlank() && key.length <= 512) { "Chiave di configurazione non valida." }
                add(key to decoder(bucket, key))
            }
        }
    }

    private fun putUnique(target: MutableMap<String, Any>, entry: Pair<String, Any>) {
        require(target.put(entry.first, entry.second) == null) { "Chiave duplicata nel contenuto ricevuto." }
    }

    private fun cloudStreamConfigurationValues(values: Map<String, *>): Map<String, Any> =
        values.mapNotNull { (key, value) ->
            if (value != null && isCloudStreamConfigurationKey(key)) key to value else null
        }.toMap()

    private fun libraryValues(values: Map<String, *>, account: String): Map<String, Any> {
        val selected = linkedMapOf<String, Any>()
        val contentIds = mutableSetOf<String>()
        values.forEach { (key, value) ->
            if (value != null && isLibraryKey(key, account)) {
                val relative = key.removePrefix("$account/")
                selected[relative] = value
                contentIds += key.substringAfterLast('/')
            }
        }
        values.forEach { (key, value) ->
            val prefix = key.substringBefore('/')
            val id = key.substringAfterLast('/')
            if (
                value != null &&
                prefix in setOf(BACKUP_HEADER_PREFIX, DOWNLOAD_HEADER_PREFIX) &&
                id in contentIds &&
                isTransferable(key)
            ) {
                selected["@global/$key"] = value
            }
        }
        return selected
    }

    private fun currentAccount(datastore: SharedPreferences): String {
        val raw = datastore.getString(ACCOUNT_INDEX_KEY, null)?.trim()
        return raw?.trim('"')?.toIntOrNull()?.coerceAtLeast(0)?.toString() ?: "0"
    }

    private fun isLibraryKey(key: String, account: String): Boolean {
        val prefix = "$account/"
        if (!key.startsWith(prefix)) return false
        return key.removePrefix(prefix).substringBefore('/') in libraryFolders
    }

    private fun isAnyLibraryKey(key: String): Boolean {
        val parts = key.split('/', limit = 3)
        return parts.size == 3 && parts[0].toIntOrNull() != null && parts[1] in libraryFolders
    }

    private fun isLibraryHeaderKey(key: String): Boolean =
        key.substringBefore('/') in setOf(BACKUP_HEADER_PREFIX, DOWNLOAD_HEADER_PREFIX)

    private fun isCloudStreamConfigurationKey(key: String): Boolean =
        isTransferable(key) && !isAnyLibraryKey(key) && !isLibraryHeaderKey(key)

    private fun isValidLibraryPayloadKey(key: String): Boolean {
        if (key.startsWith("@global/")) {
            val raw = key.removePrefix("@global/")
            return raw.substringBefore('/') in setOf(BACKUP_HEADER_PREFIX, DOWNLOAD_HEADER_PREFIX) &&
                raw.substringAfter('/', "").isNotBlank()
        }
        return key.substringBefore('/') in libraryFolders && key.substringAfter('/', "").isNotBlank()
    }

    private fun isTransferable(key: String): Boolean {
        val normalizedKey = key.lowercase(Locale.ROOT)
        return nonTransferableKeyParts.none { part -> normalizedKey.contains(part.lowercase(Locale.ROOT)) }
    }

    private fun libraryItemCount(keys: Set<String>): Int {
        return keys.count { key -> key.substringBefore('/') == "result_watch_state_data" }
    }

    private fun progressCount(keys: Set<String>): Int {
        return keys.count { key -> key.substringBefore('/') in setOf("video_pos_dur", "video_watch_state") }
    }

    private fun datastore(context: Context): SharedPreferences =
        context.getSharedPreferences(DATASTORE_PREFERENCES, Context.MODE_PRIVATE)

    private fun settings(context: Context): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    private fun compress(value: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { stream -> stream.write(value) }
        return output.toByteArray()
    }

    private fun decompress(value: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(value)).use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_UNCOMPRESSED_BYTES) { "Il contenuto decompresso supera il limite di sicurezza." }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
