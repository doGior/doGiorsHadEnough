package it.dogior.hadEnough.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

internal fun nextCalendarDayStartMillis(): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal object StreamCenterHomeCalendarCache {
    @Volatile private var storage: PersistentCalendarCache? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cleanup: Job? = null

    @Synchronized fun initialize(filesDir: File, enabled: Boolean) {
        cleanup?.cancel()
        val cache = PersistentCalendarCache(File(filesDir, "streamcenter_home_calendar_cache"))
        storage = cache
        if (enabled) cache.purgeExpired() else cache.clear()
        cleanup = scope.launch {
            while (isActive) {
                delay((nextCalendarDayStartMillis() - System.currentTimeMillis()).coerceAtLeast(1L))
                cache.purgeExpired()
            }
        }
    }

    fun storage(): PersistentCalendarCache? = storage

    fun clear() = storage?.clear()
}
