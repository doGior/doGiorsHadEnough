package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay

internal class AnimeMetadataHttpClient {
    suspend fun getText(
        url: String,
        accept: String,
        beforeRequest: suspend () -> Unit = {},
    ): String? {
        repeat(REQUEST_ATTEMPTS) { attempt ->
            beforeRequest()
            val response = runCatching {
                app.get(
                    url,
                    headers = mapOf("Accept" to accept),
                    cacheTime = 0,
                    timeout = REQUEST_TIMEOUT_SECONDS,
                )
            }.getOrNull()
            if (response != null && response.code in 200..299 && response.text.isNotBlank()) {
                return response.text
            }
            val retryable = response == null ||
                response.code == 408 ||
                response.code == 429 ||
                response.code in 500..599
            if (!retryable) return null
            if (attempt + 1 < REQUEST_ATTEMPTS) {
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return null
    }

    private companion object {
        const val REQUEST_ATTEMPTS = 2
        const val REQUEST_TIMEOUT_SECONDS = 20L
        const val RETRY_DELAY_MS = 500L
    }
}
