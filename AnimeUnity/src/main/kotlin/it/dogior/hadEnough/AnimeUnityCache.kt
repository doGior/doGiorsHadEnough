package it.dogior.hadEnough

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object AnimeUnityCache {
    private const val CACHE_SCHEMA = 1
    private const val CACHE_FILE_EXTENSION = "json"
    private const val MAX_CACHE_BYTES = 250L * 1024L * 1024L
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

    private data class CacheFileMeta(
        val file: File,
        val namespace: String,
        val lastAccessedAtMs: Long,
        val priority: Int,
        val pinned: Boolean,
        val isExpired: Boolean,
        val sizeBytes: Long,
    )

    @Volatile
    private var cacheDirectory: File? = null

    fun init(context: Context) {
        cacheDirectory = File(context.filesDir, "animeunity_cache").apply {
            mkdirs()
        }
    }

    fun read(key: String, allowExpired: Boolean = false): CacheRecord? {
        val file = getCacheFile(key) ?: return null
        if (!file.exists()) return null

        return synchronized(this) {
            val json = readValidatedJson(file, expectedKey = key)
                ?: return@synchronized null

            val now = System.currentTimeMillis()
            val expiresAtMs = json.optLong("expiresAtMs", 0L)
            val isExpired = expiresAtMs > 0L && now >= expiresAtMs
            if (isExpired && !allowExpired) {
                file.delete()
                return@synchronized null
            }

            file.setLastModified(now)

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
        val file = getCacheFile(key) ?: return
        val now = System.currentTimeMillis()
        val expiration = expiresAtMs ?: (now + ttlMs)

        synchronized(this) {
            file.parentFile?.mkdirs()
            val createdAtMs = readValidatedJson(file, expectedKey = key)
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

            if (writeJsonFile(file, json)) {
                trimIfNeeded()
            }
        }
    }

    fun remove(key: String) {
        synchronized(this) {
            getCacheFile(key)?.delete()
        }
    }

    fun clear() {
        synchronized(this) {
            cacheDirectory?.deleteRecursively()
            cacheDirectory?.mkdirs()
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

    private fun getCacheFile(key: String): File? {
        val directory = cacheDirectory ?: return null
        val hash = sha256(key)
        return File(directory, "$hash.$CACHE_FILE_EXTENSION")
    }

    private fun readAllMeta(): List<CacheFileMeta> {
        val directory = cacheDirectory ?: return emptyList()
        val files = directory.listFiles { file ->
            file.isFile && file.extension == CACHE_FILE_EXTENSION
        } ?: return emptyList()
        val now = System.currentTimeMillis()

        return files.mapNotNull { file ->
            val json = readValidatedJson(file) ?: return@mapNotNull null
            val expiresAtMs = json.optLong("expiresAtMs", 0L)

            CacheFileMeta(
                file = file,
                namespace = json.optString("namespace"),
                lastAccessedAtMs = maxOf(json.optLong("lastAccessedAtMs", 0L), file.lastModified()),
                priority = json.optInt("priority", 0),
                pinned = json.optBoolean("pinned", false),
                isExpired = expiresAtMs > 0L && now >= expiresAtMs,
                sizeBytes = file.length(),
            )
        }
    }

    private fun trimIfNeeded() {
        val entries = readAllMeta()
        var totalBytes = entries.sumOf { it.sizeBytes }
        var detailEntries = entries.count { it.namespace == NAMESPACE_DETAIL }
        if (totalBytes <= MAX_CACHE_BYTES && detailEntries <= MAX_DETAIL_ENTRIES) return

        fun needsTrim(): Boolean {
            return totalBytes > MAX_CACHE_BYTES || detailEntries > MAX_DETAIL_ENTRIES
        }

        fun trim(candidates: List<CacheFileMeta>) {
            for (entry in candidates) {
                val shouldTrimByBytes = totalBytes > MAX_CACHE_BYTES
                val shouldTrimByDetailCount =
                    detailEntries > MAX_DETAIL_ENTRIES && entry.namespace == NAMESPACE_DETAIL
                if (!shouldTrimByBytes && !shouldTrimByDetailCount) break

                if (entry.file.delete()) {
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

    private fun readValidatedJson(file: File, expectedKey: String? = null): JSONObject? {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (json == null) {
            file.delete()
            return null
        }

        val hasExpectedSchema = json.optInt("schema") == CACHE_SCHEMA
        val hasExpectedKey = expectedKey == null || json.optString("key") == expectedKey
        if (!hasExpectedSchema || !hasExpectedKey) {
            file.delete()
            return null
        }

        return json
    }

    private fun writeJsonFile(file: File, json: JSONObject): Boolean {
        val parent = file.parentFile ?: return false
        parent.mkdirs()

        val tmpFile = File(parent, "${file.name}.tmp")
        val backupFile = File(parent, "${file.name}.bak")

        return try {
            tmpFile.delete()
            backupFile.delete()
            tmpFile.writeText(json.toString())

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
}
