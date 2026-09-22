package it.dogior.hadEnough.availability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class AvailabilityGroup(val title: String) {
    SITES("Siti e lettori"), METADATA("Metadati"), TORRENT("Torrent"),
    STREMIO("Add-on Stremio"), EXTENSIONS("Altre estensioni"), SECTIONS("Sezioni"),
}

internal enum class AvailabilityState(val label: String) {
    WAITING("In attesa"), RUNNING("Verifica in corso"), SUCCESS("Disponibile"),
    WARNING("Da verificare"), FAILURE("Errore"), DISABLED("Disattivata"), BLOCKED("Bloccata"),
}

internal data class AvailabilityResult(
    val state: AvailabilityState,
    val detail: String,
    val elapsedMs: Long? = null,
)

internal data class AvailabilityCheck(
    val id: String,
    val name: String,
    val group: AvailabilityGroup,
    val enabled: Boolean = true,
    val disabledReason: String = "Fonte disattivata nelle impostazioni",
    val timeoutMs: Long = 15_000L,
    val probe: suspend () -> AvailabilityResult,
)

internal object AvailabilityRunner {
    suspend fun run(
        checks: List<AvailabilityCheck>,
        includeDisabled: Boolean = false,
        canUseInternet: () -> Boolean = { true },
        concurrency: Int = 4,
        onProgress: suspend (AvailabilityCheck, AvailabilityResult) -> Unit,
    ): List<AvailabilityResult> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        checks.map { check ->
            async(Dispatchers.IO) {
                val result = if (!check.enabled && !includeDisabled) {
                    AvailabilityResult(AvailabilityState.DISABLED, check.disabledReason)
                } else semaphore.withPermit {
                    if (!canUseInternet()) {
                        AvailabilityResult(AvailabilityState.BLOCKED, "Protezione VPN: attiva una VPN per verificare la fonte")
                    } else {
                        onProgress(check, AvailabilityResult(AvailabilityState.RUNNING, ""))
                        val started = System.nanoTime()
                        val response = try {
                            withTimeoutOrNull(check.timeoutMs) { check.probe() }
                                ?: AvailabilityResult(AvailabilityState.FAILURE, "Timeout della verifica")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            AvailabilityResult(AvailabilityState.FAILURE, availabilityFailureDetail(error))
                        } catch (_: NotImplementedError) {
                            AvailabilityResult(AvailabilityState.WARNING, "Controllo non supportato dall'estensione")
                        }
                        currentCoroutineContext().ensureActive()
                        response.copy(elapsedMs = (System.nanoTime() - started) / 1_000_000)
                    }
                }
                onProgress(check, result)
                result
            }
        }.awaitAll()
    }
}

internal fun availabilityFailureDetail(error: Throwable): String = when (error) {
    is SocketTimeoutException -> "Timeout di rete"
    is UnknownHostException -> "Host non trovato: controlla DNS e connessione"
    is ConnectException -> "Connessione al server non riuscita"
    is SSLException -> "Errore del certificato o della connessione TLS"
    is IllegalArgumentException -> "Configurazione o indirizzo non valido"
    else -> "Verifica non riuscita (${error.javaClass.simpleName})"
}

internal fun availabilitySummary(results: Collection<AvailabilityResult>, total: Int): String {
    fun count(vararg states: AvailabilityState) = results.count { it.state in states }
    val completed = results.count { it.state != AvailabilityState.WAITING && it.state != AvailabilityState.RUNNING }
    return "$completed/$total verifiche completate\n" + listOf(
        "${count(AvailabilityState.SUCCESS)} disponibili",
        "${count(AvailabilityState.WARNING)} avvisi",
        "${count(AvailabilityState.FAILURE)} errori",
        "${count(AvailabilityState.DISABLED, AvailabilityState.BLOCKED)} escluse o bloccate",
    ).joinToString(" · ")
}

internal fun availabilityReport(
    checks: List<AvailabilityCheck>,
    results: Map<String, AvailabilityResult>,
): String = buildString {
    appendLine("Verifica API e Fonti — StreamCenter")
    appendLine(availabilitySummary(results.values, checks.size))
    appendLine("La verifica non garantisce la riproduzione. Sezioni attive: caricamento della prima pagina. Stremio: controllo del manifest e delle risorse dichiarate.")
    checks.groupBy { it.group }.forEach { (group, entries) ->
        appendLine()
        appendLine(group.title)
        entries.forEach { check ->
            val result = results[check.id] ?: AvailabilityResult(AvailabilityState.WAITING, "")
            append("${check.name}: ${result.state.label}")
            if (result.detail.isNotBlank()) append(" — ${result.detail}")
            result.elapsedMs?.let { append(" · $it ms") }
            appendLine()
        }
    }
}
