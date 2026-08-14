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

    fun createSelectivePayload(
        context: Context,
        categories: Set<StreamCenterLocalSyncCategory>,
    ): StreamCenterLocalSyncPayload {
        val datastore = datastore(context)
        val settings = settings(context)
        val streamCenterPreferences = StreamCenterConfigurationStore.preferences(context)
        val account = currentAccount(datastore)
        val includeCloudStream = StreamCenterLocalSyncCategory.CLOUDSTREAM_CONFIG in categories
        val includeLibrary = StreamCenterLocalSyncCategory.LIBRARY in categories
        val includeStreamCenter = StreamCenterLocalSyncCategory.STREAMCENTER_CONFIG in categories
        val datastoreValues = if (includeCloudStream) cloudStreamConfigurationValues(datastore.all) else emptyMap()
        val settingsValues = if (includeCloudStream) settings.all.filterKeys(::isTransferable) else emptyMap()
        val libraryValues = if (includeLibrary) libraryValues(datastore.all, account) else emptyMap()
        val streamCenterValues = if (includeStreamCenter) {
            StreamCenterConfigurationStore.snapshot(streamCenterPreferences)
        } else {
            emptyMap()
        }
        val libraryItemCount = libraryItemCount(libraryValues.keys)
        val progressCount = progressCount(libraryValues.keys)
        val entryCount = datastoreValues.size + settingsValues.size + libraryValues.size + streamCenterValues.size
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("type", StreamCenterLocalSyncPayloadType.SELECTIVE.wireValue)
            .put("categories", JSONArray(categories.map { it.wireValue }))
            .put("createdAt", System.currentTimeMillis())
            .put("sourceDevice", deviceName())
            .put("sourceAccount", account.takeIf { includeLibrary })
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
            type = StreamCenterLocalSyncPayloadType.SELECTIVE,
            compressedBytes = compress(uncompressed),
            uncompressedSize = uncompressed.size,
            entryCount = entryCount,
            libraryItemCount = libraryItemCount,
            progressCount = progressCount,
            sourceAccount = account.takeIf { includeLibrary },
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
            StreamCenterLocalSyncPayloadType.SELECTIVE -> {
                val categories = root.optJSONArray("categories")?.let { array ->
                    buildSet {
                        for (index in 0 until array.length()) {
                            StreamCenterLocalSyncCategory.fromWireValue(array.optString(index))?.let(::add)
                        }
                    }
                }.orEmpty()
                applySelective(context, categories, datastoreValues, settingsValues, libraryValues, streamCenterValues)
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


    fun mergeSource(
        context: Context,
        categories: Set<StreamCenterLocalSyncCategory>,
    ): StreamCenterLocalSyncMergeSource {
        val appContext = context.applicationContext
        val live = collectSyncEntries(appContext, categories)
        val hashes = live.mapValues { (_, value) -> stableHash(value) }
        val log = StreamCenterLocalSyncVersionLog.reconcile(appContext, hashes) { key ->
            categoryOfKey(key)?.let { category -> category in categories } == true
        }
        return object : StreamCenterLocalSyncMergeSource {
            override fun manifest(): JSONObject {
                val root = JSONObject()
                log.forEach { (key, version) ->
                    if (categoryOfKey(key)?.let { it in categories } != true) return@forEach
                    root.put(key, JSONObject().put("t", version.timestampMs).put("d", if (version.deleted) 1 else 0))
                }
                return root
            }

            override fun collectValues(keys: List<String>): JSONObject {
                val root = JSONObject()
                keys.forEach { key ->
                    val version = log[key] ?: return@forEach
                    if (categoryOfKey(key)?.let { it in categories } != true) return@forEach
                    val entry = JSONObject().put("t", version.timestampMs)
                    val value = live[key]
                    if (version.deleted || value == null) {
                        entry.put("d", 1)
                    } else {
                        val encoded = encodeValue(value)
                        val fields = encoded.keys()
                        while (fields.hasNext()) {
                            val field = fields.next()
                            entry.put(field, encoded.get(field))
                        }
                    }
                    root.put(key, entry)
                }
                return root
            }

            override fun applyRemote(values: JSONObject): List<String> {
                val appliedKeys = mutableListOf<String>()
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val entry = values.optJSONObject(key) ?: continue
                    val category = categoryOfKey(key) ?: continue
                    if (category !in categories || !isAcceptableMergeKey(key)) continue
                    val remoteTs = entry.optLong("t", 0L)
                    val localTs = log[key]?.timestampMs ?: 0L
                    if (remoteTs <= localTs) continue
                    if (entry.optInt("d", 0) == 1) {
                        writeMergeEntry(appContext, key, null)
                        log[key] = StreamCenterLocalSyncVersion(remoteTs, true, "")
                    } else {
                        val value = decodeValue(entry) ?: continue
                        writeMergeEntry(appContext, key, value)
                        log[key] = StreamCenterLocalSyncVersion(remoteTs, false, stableHash(value))
                    }
                    appliedKeys += key
                }
                if (appliedKeys.isNotEmpty()) StreamCenterLocalSyncVersionLog.save(appContext, log)
                return appliedKeys
            }
        }
    }

    fun transferredMediaSummaries(context: Context, mergeKeys: List<String>): List<String> {
        val preferences = datastore(context.applicationContext)
        val account = currentAccount(preferences)
        return mergeKeys.asSequence()
            .mapNotNull { mergeKey ->
                if (!mergeKey.startsWith("lib|")) return@mapNotNull null
                val relative = mergeKey.removePrefix("lib|")
                val folder = relative.substringBefore('/')
                if (folder !in TRANSFERRED_MEDIA_FOLDERS) return@mapNotNull null
                val id = relative.substringAfter('/', "")
                if (id.isBlank()) return@mapNotNull null
                val value = preferences.all["$account/$relative"] as? String
                val json = value?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                val parentId = json?.optInt("parentId", -1)?.takeIf { it >= 0 }?.toString() ?: id
                val title = json?.optString("name")?.trim().orEmpty()
                    .ifBlank { libraryTitle(preferences, account, parentId) }
                    .ifBlank { "Contenuto $parentId" }
                val details = buildList {
                    json?.optInt("season", -1)?.takeIf { it >= 0 }?.let { season ->
                        val episode = json.optInt("episode", -1).takeIf { it >= 0 }
                        add(if (episode == null) "Stagione $season" else "S$season E$episode")
                    }
                    val position = json?.optLong("position", -1L) ?: -1L
                    val duration = json?.optLong("duration", -1L) ?: -1L
                    if (position >= 0L && duration > 0L) {
                        add("${formatDuration(position)} / ${formatDuration(duration)}")
                    }
                    if (isEmpty() && folder == "result_watch_state_data") add("Libreria")
                }
                "$title${details.takeIf { it.isNotEmpty() }?.joinToString(" · ", " · ").orEmpty()}"
            }
            .distinct()
            .take(MAX_TRANSFERRED_MEDIA_SUMMARIES)
            .toList()
    }

    private fun libraryTitle(preferences: SharedPreferences, account: String, id: String): String {
        val raw = preferences.getString("$account/result_watch_state_data/$id", null) ?: return ""
        return runCatching { JSONObject(raw).optString("name").trim() }.getOrDefault("")
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(Locale.ITALY, minutes, seconds)
    }

    private fun collectSyncEntries(
        context: Context,
        categories: Set<StreamCenterLocalSyncCategory>,
    ): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        val datastore = datastore(context)
        if (StreamCenterLocalSyncCategory.CLOUDSTREAM_CONFIG in categories) {
            cloudStreamConfigurationValues(datastore.all).forEach { (key, value) -> result["ds|$key"] = value }
            settings(context).all.forEach { (key, value) ->
                if (value != null && isTransferable(key)) result["st|$key"] = value
            }
        }
        if (StreamCenterLocalSyncCategory.LIBRARY in categories) {
            val account = currentAccount(datastore)
            libraryValues(datastore.all, account).forEach { (key, value) -> result["lib|$key"] = value }
        }
        if (StreamCenterLocalSyncCategory.STREAMCENTER_CONFIG in categories) {
            StreamCenterConfigurationStore.snapshot(StreamCenterConfigurationStore.preferences(context))
                .forEach { (key, value) -> result["sc|$key"] = value }
        }
        return result
    }

    private fun categoryOfKey(key: String): StreamCenterLocalSyncCategory? =
        when (key.substringBefore('|', "")) {
            "ds", "st" -> StreamCenterLocalSyncCategory.CLOUDSTREAM_CONFIG
            "lib" -> StreamCenterLocalSyncCategory.LIBRARY
            "sc" -> StreamCenterLocalSyncCategory.STREAMCENTER_CONFIG
            else -> null
        }

    private fun isAcceptableMergeKey(key: String): Boolean {
        val relative = key.substringAfter('|', "")
        if (relative.isBlank() || relative.length > 512) return false
        return when (key.substringBefore('|', "")) {
            "ds" -> isCloudStreamConfigurationKey(relative)
            "st" -> isTransferable(relative)
            "sc" -> true
            "lib" -> isValidLibraryPayloadKey(relative)
            else -> false
        }
    }

    private fun writeMergeEntry(context: Context, key: String, value: Any?) {
        val namespace = key.substringBefore('|', "")
        val relative = key.substringAfter('|', "")
        val preferences = when (namespace) {
            "ds", "lib" -> datastore(context)
            "st" -> settings(context)
            "sc" -> StreamCenterConfigurationStore.preferences(context)
            else -> return
        }
        val targetKey = if (namespace == "lib") {
            if (relative.startsWith("@global/")) {
                relative.removePrefix("@global/")
            } else {
                "${currentAccount(datastore(context))}/$relative"
            }
        } else {
            relative
        }
        val editor = preferences.edit()
        if (value == null) editor.remove(targetKey) else put(editor, targetKey, value)
        editor.apply()
    }

    private fun encodeValue(value: Any): JSONObject = JSONObject().apply {
        when (value) {
            is Boolean -> put("b", value)
            is Int -> put("i", value)
            is String -> put("s", value)
            is Float -> put("f", value.toDouble())
            is Long -> put("l", value)
            is Set<*> -> put("ss", JSONArray(value.filterIsInstance<String>().sorted()))
            else -> throw IllegalArgumentException("Tipo di preferenza non supportato per la sincronizzazione.")
        }
    }

    private fun decodeValue(entry: JSONObject): Any? = when {
        entry.has("b") -> entry.getBoolean("b")
        entry.has("i") -> entry.getInt("i")
        entry.has("s") -> entry.getString("s")
        entry.has("f") -> entry.getDouble("f").toFloat()
        entry.has("l") -> entry.getLong("l")
        entry.has("ss") -> {
            val array = entry.getJSONArray("ss")
            buildSet { repeat(array.length()) { index -> add(array.getString(index)) } }
        }
        else -> null
    }

    private fun stableHash(value: Any): String {
        val encoded = when (value) {
            is Set<*> -> "ss:" + value.filterIsInstance<String>().sorted().joinToString("\u0000")
            is Float -> "f:" + value.toRawBits().toString()
            is Boolean -> "b:$value"
            is Int -> "i:$value"
            is Long -> "l:$value"
            is String -> "s:$value"
            else -> "x:$value"
        }
        return encoded.hashCode().toString()
    }

    private fun applySelective(
        context: Context,
        categories: Set<StreamCenterLocalSyncCategory>,
        datastoreValues: Map<String, Any>,
        settingsValues: Map<String, Any>,
        libraryValues: Map<String, Any>,
        streamCenterValues: Map<String, Any>,
    ) {
        val datastore = datastore(context)
        val settings = settings(context)
        val streamCenter = StreamCenterConfigurationStore.preferences(context)
        if (StreamCenterLocalSyncCategory.CLOUDSTREAM_CONFIG in categories) {
            validateCloudStreamConfiguration(datastoreValues, settingsValues)
            replaceTransferable(settings, settingsValues)
            replaceCloudStreamConfiguration(datastore, datastoreValues)
        }
        if (StreamCenterLocalSyncCategory.LIBRARY in categories) {
            require(libraryValues.keys.all(::isValidLibraryPayloadKey)) { "La libreria contiene chiavi non valide." }
            replaceLibrary(datastore, libraryValues)
        }
        if (StreamCenterLocalSyncCategory.STREAMCENTER_CONFIG in categories) {
            StreamCenterConfigurationStore.replace(streamCenter, streamCenterValues)
        }
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

    private val TRANSFERRED_MEDIA_FOLDERS = setOf(
        "result_resume_watching_2",
        "result_resume_watching",
        "result_watch_state_data",
        "video_pos_dur",
    )
    private const val MAX_TRANSFERRED_MEDIA_SUMMARIES = 12
}
