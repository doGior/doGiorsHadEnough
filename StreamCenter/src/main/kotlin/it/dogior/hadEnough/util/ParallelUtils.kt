package it.dogior.hadEnough.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal suspend fun <T, R : Any> List<T>.mapChunkedParallel(
    chunkSize: Int,
    transform: suspend (T) -> R?,
): List<R> {
    val results = mutableListOf<R>()
    for (chunk in chunked(chunkSize)) {
        results += coroutineScope {
            chunk.map { item ->
                async(Dispatchers.IO) {
                    runCatching { transform(item) }.getOrNull()
                }
            }.awaitAll()
        }.filterNotNull()
    }
    return results
}
