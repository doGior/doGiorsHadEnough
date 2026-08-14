package it.dogior.hadEnough.localsync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

internal data class StreamCenterLocalSyncVersion(
    val timestampMs: Long,
    val deleted: Boolean,
    val hash: String,
)

internal data class StreamCenterLocalSyncMergeResult(
    val sent: Int,
    val received: Int,
    val sentKeys: List<String>,
    val receivedKeys: List<String>,
)

internal interface StreamCenterLocalSyncMergeSource {
    fun manifest(): JSONObject

    fun collectValues(keys: List<String>): JSONObject

    fun applyRemote(values: JSONObject): List<String>
}

internal object StreamCenterLocalSyncVersionLog {
    private const val PREFS = "streamcenter_local_sync_versions"
    private const val KEY_VERSIONS = "versions"

    fun load(context: Context): MutableMap<String, StreamCenterLocalSyncVersion> {
        val raw = prefs(context).getString(KEY_VERSIONS, null) ?: return linkedMapOf()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return linkedMapOf()
        val result = linkedMapOf<String, StreamCenterLocalSyncVersion>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = root.optJSONObject(key) ?: continue
            result[key] = StreamCenterLocalSyncVersion(
                timestampMs = entry.optLong("t", 0L),
                deleted = entry.optInt("d", 0) == 1,
                hash = entry.optString("h", ""),
            )
        }
        return result
    }

    fun save(context: Context, versions: Map<String, StreamCenterLocalSyncVersion>) {
        val root = JSONObject()
        versions.forEach { (key, version) ->
            root.put(
                key,
                JSONObject()
                    .put("t", version.timestampMs)
                    .put("d", if (version.deleted) 1 else 0)
                    .put("h", version.hash),
            )
        }
        prefs(context).edit().putString(KEY_VERSIONS, root.toString()).apply()
    }

    fun reconcile(
        context: Context,
        currentHashes: Map<String, String>,
        inScope: (String) -> Boolean,
    ): MutableMap<String, StreamCenterLocalSyncVersion> {
        val now = System.currentTimeMillis()
        val log = load(context)
        currentHashes.forEach { (key, hash) ->
            val existing = log[key]
            if (existing == null || existing.deleted || existing.hash != hash) {
                log[key] = StreamCenterLocalSyncVersion(now, false, hash)
            }
        }
        log.keys.toList().forEach { key ->
            val version = log.getValue(key)
            if (!version.deleted && key !in currentHashes && inScope(key)) {
                log[key] = StreamCenterLocalSyncVersion(now, true, "")
            }
        }
        save(context, log)
        return log
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
