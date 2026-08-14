package it.dogior.hadEnough.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal object StreamCenterTorrentRequestGate {
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()
    private val sourceSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val lastStartedAt = ConcurrentHashMap<String, Long>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    fun isCoolingDown(sourceKey: String): Boolean {
        val until = cooldownUntil[sourceKey] ?: return false
        if (until > System.currentTimeMillis()) return true
        cooldownUntil.remove(sourceKey, until)
        return false
    }

    fun remainingCooldownMs(sourceKey: String): Long {
        val until = cooldownUntil[sourceKey] ?: return 0L
        val remaining = until - System.currentTimeMillis()
        if (remaining > 0L) return remaining
        cooldownUntil.remove(sourceKey, until)
        return 0L
    }

    fun startCooldown(sourceKey: String, durationMs: Long) {
        if (durationMs <= 0L) return
        cooldownUntil[sourceKey] = System.currentTimeMillis() + durationMs
    }

    suspend fun <T> rateLimited(
        sourceKey: String,
        minimumIntervalMs: Long,
        block: suspend () -> T,
    ): T {
        val semaphore = sourceSemaphores.computeIfAbsent(sourceKey) {
            Semaphore(MAX_CONCURRENT_REQUESTS_PER_SOURCE)
        }
        return semaphore.withPermit {
            val mutex = sourceLocks.computeIfAbsent(sourceKey) { Mutex() }
            mutex.withLock {
                val now = System.currentTimeMillis()
                val elapsed = now - (lastStartedAt[sourceKey] ?: 0L)
                val waitMs = minimumIntervalMs - elapsed
                if (waitMs > 0L) delay(waitMs)
                lastStartedAt[sourceKey] = System.currentTimeMillis()
            }
            block()
        }
    }

    private const val MAX_CONCURRENT_REQUESTS_PER_SOURCE = 3
}
