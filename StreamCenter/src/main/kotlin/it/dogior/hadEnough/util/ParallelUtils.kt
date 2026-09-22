package it.dogior.hadEnough.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicInteger

internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

internal suspend fun <T, R : Any> List<T>.mapChunkedParallel(
    chunkSize: Int,
    transform: suspend (T) -> R?,
): List<R> {
    require(chunkSize > 0) { "Parallelism must be positive" }
    if (isEmpty()) return emptyList()
    val results = MutableList<R?>(size) { null }
    val nextIndex = AtomicInteger()
    coroutineScope {
        List(minOf(chunkSize, size)) {
            async(Dispatchers.IO) {
                while (true) {
                    ensureActive()
                    val index = nextIndex.getAndIncrement()
                    if (index >= size) break
                    results[index] = runCatchingCancellable { transform(this@mapChunkedParallel[index]) }.getOrNull()
                }
            }
        }.awaitAll()
    }
    return results.filterNotNull()
}
