package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object AnimeUnityCache {
    private const val CACHE_SCHEMA = 1
    private const val CACHE_FILE_EXTENSION = "json"
    private const val DEFAULT_CACHE_DIRECTORY_NAME = "animeunity_cache"
    private const val MAX_DETAIL_ENTRIES = 750

    const val NAMESPACE_HOME = "home"
    const val NAMESPACE_DETAIL = "detail"
    const val NAMESPACE_ARCHIVE = "archive"
    const val NAMESPACE_ANIME_PAGE = "anime_page"
    const val NAMESPACE_EXTERNAL = "external"

    data class CacheRecord(
        val payload: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val expiresAtMs: Long,
        val isExpired: Boolean,
    )

    data class CacheStats(
        val entryCount: Int,
        val detailEntryCount: Int,
        val animePageEntryCount: Int,
        val expiredEntryCount: Int,
        val totalBytes: Long,
    )

    data class CacheEntrySnapshot(
        val fileName: String,
        val key: String,
        val namespace: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val expiresAtMs: Long,
        val lastAccessedAtMs: Long,
        val priority: Int,
        val pinned: Boolean,
        val isExpired: Boolean,
        val sizeBytes: Long,
        val payload: String,
    )

    private data class CacheFileMeta(
        val fileName: String,
        val namespace: String,
        val lastAccessedAtMs: Long,
        val priority: Int,
        val pinned: Boolean,
        val isExpired: Boolean,
        val sizeBytes: Long,
    )

    private data class StorageFileMeta(
        val fileName: String,
        val lastModifiedAtMs: Long,
        val sizeBytes: Long,
    )

    private interface CacheStorage {
        fun ensureReady()
        fun readText(fileName: String): String?
        fun writeText(fileName: String, text: String): Boolean
        fun deleteFile(fileName: String): Boolean
        fun touchFile(fileName: String, timestampMs: Long)
        fun listFiles(): List<StorageFileMeta>
    }

    @Volatile
    private var cacheStorage: CacheStorage? = null

    @Volatile
    private var maxCacheBytes: Long = AnimeUnityPlugin.DEFAULT_CACHE_MAX_SIZE_MB * 1024L * 1024L

    @Volatile
    private var maxCacheEntries: Int = AnimeUnityPlugin.DEFAULT_CACHE_MAX_ENTRIES

    private val cacheFileNameRegex = Regex("^[0-9a-f]{64}\\.$CACHE_FILE_EXTENSION$")

    fun init(context: Context, sharedPref: SharedPreferences? = AnimeUnityPlugin.activeSharedPref) {
        synchronized(this) {
            maxCacheEntries = AnimeUnityPlugin.getCacheMaxEntries(sharedPref)
            maxCacheBytes = AnimeUnityPlugin.getCacheMaxBytes(sharedPref)
            cacheStorage = FileCacheStorage(
                File(context.applicationContext.filesDir, DEFAULT_CACHE_DIRECTORY_NAME)
            ).apply {
                ensureReady()
            }
            trimIfNeeded()
        }
    }

    fun read(key: String, allowExpired: Boolean = false): CacheRecord? {
        return synchronized(this) {
            val storage = cacheStorage ?: return@synchronized null
            val fileName = cacheFileName(key)
            val json = readValidatedJson(storage, fileName, expectedKey = key)
                ?: return@synchronized null

            val now = System.currentTimeMillis()
            val expiresAtMs = json.optLong("expiresAtMs", 0L)
            val isExpired = expiresAtMs > 0L && now >= expiresAtMs
            if (isExpired && !allowExpired) {
                storage.deleteFile(fileName)
                return@synchronized null
            }

            storage.touchFile(fileName, now)

            CacheRecord(
                payload = json.optString("payload"),
                createdAtMs = json.optLong("createdAtMs", now),
                updatedAtMs = json.optLong("updatedAtMs", now),
                expiresAtMs = expiresAtMs,
                isExpired = isExpired,
            )
        }
    }

    fun write(
        key: String,
        namespace: String,
        payload: String,
        ttlMs: Long,
        expiresAtMs: Long? = null,
        priority: Int = 0,
        pinned: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val expiration = expiresAtMs ?: (now + ttlMs)

        synchronized(this) {
            val storage = cacheStorage ?: return
            val fileName = cacheFileName(key)
            val createdAtMs = readValidatedJson(storage, fileName, expectedKey = key)
                ?.optLong("createdAtMs", now)
                ?: now
            val json = JSONObject().apply {
                put("schema", CACHE_SCHEMA)
                put("key", key)
                put("namespace", namespace)
                put("createdAtMs", createdAtMs)
                put("updatedAtMs", now)
                put("expiresAtMs", expiration)
                put("lastAccessedAtMs", now)
                put("priority", priority)
                put("pinned", pinned)
                put("payload", payload)
            }

            if (storage.writeText(fileName, json.toString())) {
                trimIfNeeded()
            }
        }
    }

    fun remove(key: String) {
        synchronized(this) {
            cacheStorage?.deleteFile(cacheFileName(key))
        }
    }

    fun clear() {
        synchronized(this) {
            val storage = cacheStorage ?: return
            storage.listFiles().forEach { file ->
                storage.deleteFile(file.fileName)
            }
        }
    }

    fun stats(): CacheStats {
        return synchronized(this) {
            val entries = readAllMeta()
            CacheStats(
                entryCount = entries.size,
                detailEntryCount = entries.count { it.namespace == NAMESPACE_DETAIL },
                animePageEntryCount = entries.count { it.namespace == NAMESPACE_ANIME_PAGE },
                expiredEntryCount = entries.count { it.isExpired },
                totalBytes = entries.sumOf { it.sizeBytes },
            )
        }
    }

    fun entries(): List<CacheEntrySnapshot> {
        return synchronized(this) {
            val storage = cacheStorage ?: return@synchronized emptyList()
            val now = System.currentTimeMillis()

            storage.listFiles().mapNotNull { file ->
                val json = readValidatedJson(storage, file.fileName) ?: return@mapNotNull null
                val expiresAtMs = json.optLong("expiresAtMs", 0L)
                CacheEntrySnapshot(
                    fileName = file.fileName,
                    key = json.optString("key"),
                    namespace = json.optString("namespace"),
                    createdAtMs = json.optLong("createdAtMs", file.lastModifiedAtMs),
                    updatedAtMs = json.optLong("updatedAtMs", file.lastModifiedAtMs),
                    expiresAtMs = expiresAtMs,
                    lastAccessedAtMs = maxOf(
                        json.optLong("lastAccessedAtMs", 0L),
                        file.lastModifiedAtMs,
                    ),
                    priority = json.optInt("priority", 0),
                    pinned = json.optBoolean("pinned", false),
                    isExpired = expiresAtMs > 0L && now >= expiresAtMs,
                    sizeBytes = file.sizeBytes,
                    payload = json.optString("payload"),
                )
            }.sortedWith(
                compareByDescending<CacheEntrySnapshot> { it.lastAccessedAtMs }
                    .thenBy { it.namespace }
                    .thenBy { it.key }
            )
        }
    }

    private fun cacheFileName(key: String): String {
        return "${sha256(key)}.$CACHE_FILE_EXTENSION"
    }

    private fun readAllMeta(): List<CacheFileMeta> {
        val storage = cacheStorage ?: return emptyList()
        val now = System.currentTimeMillis()

        return storage.listFiles().mapNotNull { file ->
            val json = readValidatedJson(storage, file.fileName) ?: return@mapNotNull null
            val expiresAtMs = json.optLong("expiresAtMs", 0L)

            CacheFileMeta(
                fileName = file.fileName,
                namespace = json.optString("namespace"),
                lastAccessedAtMs = maxOf(
                    json.optLong("lastAccessedAtMs", 0L),
                    file.lastModifiedAtMs,
                ),
                priority = json.optInt("priority", 0),
                pinned = json.optBoolean("pinned", false),
                isExpired = expiresAtMs > 0L && now >= expiresAtMs,
                sizeBytes = file.sizeBytes,
            )
        }
    }

    private fun trimIfNeeded() {
        val storage = cacheStorage ?: return
        val entries = readAllMeta()
        var totalEntries = entries.size
        var totalBytes = entries.sumOf { it.sizeBytes }
        var detailEntries = entries.count { it.namespace == NAMESPACE_DETAIL }
        if (
            totalEntries <= maxCacheEntries &&
            totalBytes <= maxCacheBytes &&
            detailEntries <= MAX_DETAIL_ENTRIES
        ) {
            return
        }

        fun needsTrim(): Boolean {
            return totalEntries > maxCacheEntries ||
                totalBytes > maxCacheBytes ||
                detailEntries > MAX_DETAIL_ENTRIES
        }

        fun trim(candidates: List<CacheFileMeta>) {
            for (entry in candidates) {
                val shouldTrimByEntries = totalEntries > maxCacheEntries
                val shouldTrimByBytes = totalBytes > maxCacheBytes
                val shouldTrimByDetailCount =
                    detailEntries > MAX_DETAIL_ENTRIES && entry.namespace == NAMESPACE_DETAIL
                if (!shouldTrimByEntries && !shouldTrimByBytes && !shouldTrimByDetailCount) break

                if (storage.deleteFile(entry.fileName)) {
                    totalEntries -= 1
                    totalBytes -= entry.sizeBytes
                    if (entry.namespace == NAMESPACE_DETAIL) detailEntries -= 1
                }
            }
        }

        trim(sortedTrimCandidates(entries.filterNot { it.pinned }))
        if (needsTrim()) {
            trim(sortedTrimCandidates(entries.filter { it.pinned }))
        }
    }

    private fun sortedTrimCandidates(entries: List<CacheFileMeta>): List<CacheFileMeta> {
        return entries.sortedWith(
            compareBy<CacheFileMeta>(
                { !it.isExpired },
                { namespaceTrimRank(it.namespace) },
                { it.priority },
                { it.lastAccessedAtMs },
            )
        )
    }

    private fun namespaceTrimRank(namespace: String): Int {
        return when (namespace) {
            NAMESPACE_ARCHIVE -> 0
            NAMESPACE_HOME -> 1
            NAMESPACE_EXTERNAL -> 2
            NAMESPACE_ANIME_PAGE -> 3
            NAMESPACE_DETAIL -> 4
            else -> 2
        }
    }

    private fun readValidatedJson(
        storage: CacheStorage,
        fileName: String,
        expectedKey: String? = null,
    ): JSONObject? {
        val text = storage.readText(fileName)
        val json = runCatching { JSONObject(text ?: return null) }.getOrNull()
        if (json == null) {
            storage.deleteFile(fileName)
            return null
        }

        val hasExpectedSchema = json.optInt("schema") == CACHE_SCHEMA
        val hasExpectedKey = expectedKey == null || json.optString("key") == expectedKey
        if (!hasExpectedSchema || !hasExpectedKey) {
            storage.deleteFile(fileName)
            return null
        }

        return json
    }

    private fun writeJsonFile(file: File, text: String): Boolean {
        val parent = file.parentFile ?: return false
        parent.mkdirs()

        val tmpFile = File(parent, "${file.name}.tmp")
        val backupFile = File(parent, "${file.name}.bak")

        return try {
            tmpFile.delete()
            backupFile.delete()
            tmpFile.writeText(text)

            val hadExistingFile = file.exists()
            if (hadExistingFile && !file.renameTo(backupFile)) {
                tmpFile.delete()
                return false
            }

            if (tmpFile.renameTo(file)) {
                backupFile.delete()
                true
            } else {
                tmpFile.delete()
                if (hadExistingFile) backupFile.renameTo(file)
                false
            }
        } catch (_: Throwable) {
            tmpFile.delete()
            if (!file.exists() && backupFile.exists()) {
                backupFile.renameTo(file)
            }
            false
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private class FileCacheStorage(
        private val directory: File,
    ) : CacheStorage {
        override fun ensureReady() {
            directory.mkdirs()
        }

        override fun readText(fileName: String): String? {
            val file = File(directory, fileName)
            if (!file.exists() || !file.isFile) return null
            return runCatching { file.readText() }.getOrNull()
        }

        override fun writeText(fileName: String, text: String): Boolean {
            return writeJsonFile(File(directory, fileName), text)
        }

        override fun deleteFile(fileName: String): Boolean {
            val file = File(directory, fileName)
            return !file.exists() || file.delete()
        }

        override fun touchFile(fileName: String, timestampMs: Long) {
            File(directory, fileName).takeIf { it.exists() }?.setLastModified(timestampMs)
        }

        override fun listFiles(): List<StorageFileMeta> {
            return directory.listFiles { file ->
                file.isFile && cacheFileNameRegex.matches(file.name)
            }?.map { file ->
                StorageFileMeta(
                    fileName = file.name,
                    lastModifiedAtMs = file.lastModified(),
                    sizeBytes = file.length(),
                )
            }.orEmpty()
        }
    }
}
