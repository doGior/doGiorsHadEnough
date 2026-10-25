package it.dogior.hadEnough.remote

import android.content.Context
import android.content.SharedPreferences
import it.dogior.hadEnough.StreamCenterPlugin
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

internal object StreamCenterRemoteQueue {
    private const val PREFS = "streamcenter_remote_queue"
    private const val MAX_ITEMS = 200
    private val idPattern = Regex("[a-f0-9-]{36}")
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isValidId(id: String): Boolean = idPattern.matches(id)

    fun items(context: Context): List<RemoteQueueItem> =
        preferences(context).all.mapNotNull { (key, value) ->
            if (!isValidId(key) || value !is String) return@mapNotNull null
            runCatching { RemoteQueueItem.fromJson(JSONObject(value)) }.getOrNull()?.takeIf { it.id == key }
        }.sortedWith(compareBy<RemoteQueueItem> { it.order }.thenBy { it.addedAt })

    fun isEmpty(context: Context): Boolean = items(context).isEmpty()

    fun add(context: Context, url: String, apiName: String, name: String, poster: String?, addedBy: String): RemoteQueueItem? {
        val now = System.currentTimeMillis()
        val item = RemoteQueueItem(
            id = UUID.randomUUID().toString(),
            url = url,
            apiName = apiName,
            name = name,
            poster = poster,
            addedAt = now,
            addedBy = addedBy,
            order = (items(context).maxOfOrNull { it.order } ?: now) + 1,
        )
        return item.takeIf { put(context, it) }
    }

    fun put(context: Context, item: RemoteQueueItem): Boolean {
        if (StreamCenterPlugin.completeResetInProgress || !isValidId(item.id)) return false
        val current = items(context)
        if (current.any { it.id != item.id && it.url == item.url && it.apiName == item.apiName }) return false
        if (current.none { it.id == item.id } && current.size >= MAX_ITEMS) return false
        write(context) { putString(item.id, item.toJson().toString()) }
        return true
    }

    fun remove(context: Context, id: String) {
        if (!isValidId(id)) return
        write(context) { remove(id) }
    }

    fun move(context: Context, id: String, delta: Int) {
        val current = items(context).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in current.indices) return
        val moved = current.removeAt(index)
        current.add(target, moved)
        val base = System.currentTimeMillis()
        write(context) {
            current.forEachIndexed { position, item ->
                val reordered = item.copy(order = base + position)
                if (reordered.order != item.order) putString(item.id, reordered.toJson().toString())
            }
        }
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().commit()
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun write(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        if (StreamCenterPlugin.completeResetInProgress) return
        preferences(context).edit().apply(block).commit()
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }
}
