package it.dogior.hadEnough.cache

internal class ExpiringCache<K, V : Any>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry<V>(val value: V, val storedAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    init {
        require(maxEntries > 0)
        require(ttlMillis > 0)
    }

    @Synchronized
    operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (nowMillis() - entry.storedAt >= ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = Entry(value, nowMillis())
        while (entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}
