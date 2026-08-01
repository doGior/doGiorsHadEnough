package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class AnimeMetadataHttpClient {
    suspend fun getText(
        url: String,
        accept: String,
        source: String = "Provider metadati",
        operation: String = "Richiesta metadati",
        details: Map<String, Any?> = emptyMap(),
        beforeRequest: suspend () -> Unit = {},
    ): String? {
        repeat(REQUEST_ATTEMPTS) { attempt ->
            val attemptNumber = attempt + 1
            val requestDetails = details + mapOf(
                "operazione" to operation,
                "tentativo" to attemptNumber,
                "tentativi_massimi" to REQUEST_ATTEMPTS,
                "timeout_secondi" to REQUEST_TIMEOUT_SECONDS,
            )
            MetadataLog.info(source, "Richiesta metadata avviata", requestDetails)
            beforeRequest()
            val responseResult = try {
                Result.success(
                    app.get(
                        url,
                        headers = mapOf("Accept" to accept),
                        cacheTime = 0,
                        timeout = REQUEST_TIMEOUT_SECONDS,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            val response = responseResult.getOrNull()
            if (response != null && response.code in 200..299 && response.text.isNotBlank()) {
                val responseText = response.text
                MetadataLog.info(
                    source,
                    "Richiesta metadata completata",
                    requestDetails + mapOf(
                        "stato_http" to response.code,
                        "dimensione_risposta_caratteri" to responseText.length,
                    ),
                )
                return responseText
            }
            val retryable = response == null ||
                response.code == 408 ||
                response.code == 429 ||
                response.code in 500..599
            val failureDetails = requestDetails + buildMap<String, Any?> {
                response?.let { put("stato_http", it.code) }
                put("motivo", when {
                    response == null -> "errore_di_rete"
                    response.code in 200..299 -> "risposta_vuota"
                    else -> "stato_http_non_valido"
                })
                put("nuovo_tentativo", retryable && attemptNumber < REQUEST_ATTEMPTS)
                if (retryable && attemptNumber < REQUEST_ATTEMPTS) {
                    put("attesa_prima_del_nuovo_tentativo_ms", RETRY_DELAY_MS * attemptNumber)
                }
            }
            MetadataLog.failure(
                source = source,
                action = if (retryable && attemptNumber < REQUEST_ATTEMPTS) {
                    "Richiesta metadata non riuscita: nuovo tentativo programmato"
                } else {
                    "Richiesta metadata non riuscita"
                },
                error = responseResult.exceptionOrNull(),
                details = failureDetails,
            )
            if (!retryable) return null
            if (attempt + 1 < REQUEST_ATTEMPTS) {
                delay(RETRY_DELAY_MS * attemptNumber)
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
