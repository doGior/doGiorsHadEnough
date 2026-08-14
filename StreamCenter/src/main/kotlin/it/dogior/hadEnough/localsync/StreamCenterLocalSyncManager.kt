package it.dogior.hadEnough.localsync

import android.content.Context
import it.dogior.hadEnough.util.StreamCenterLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class StreamCenterLocalSyncManager(
    context: Context,
    private val listener: StreamCenterLocalSyncListener,
) {
    private val applicationContext = context.applicationContext
    private var scope = newScope()
    private var activeJob: Job? = null
    private var cancellation: StreamCenterLocalSyncCancellation? = null

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    fun startSending(categories: Set<StreamCenterLocalSyncCategory>) {
        val sessionCancellation = beginSession()
        val sessionListener = loggingListener()
        sessionListener.onStateChanged(
            StreamCenterLocalSyncState.PREPARING,
            "Preparazione di ${categories.joinToString(", ") { it.title.lowercase() }}",
        )
        launchSession(
            sessionCancellation = sessionCancellation,
            operation = {
                val payload = StreamCenterLocalSyncStorage.createSelectivePayload(applicationContext, categories)
                sessionListener.onEvent(
                    StreamCenterLocalSyncEvent(
                        message = "Snapshot creato",
                        detail = "${payload.entryCount} voci · ${payload.uncompressedSize} byte non compressi",
                    ),
                )
                sessionListener.onEvent(
                    StreamCenterLocalSyncEvent(
                        message = "Compressione completata",
                        detail = "${payload.compressedBytes.size} byte pronti per la cifratura",
                    ),
                )
                StreamCenterLocalSyncNetwork.send(
                    payload = payload,
                    localIdentityKey = StreamCenterLocalSyncTrust.localPublicKey(applicationContext),
                    cancellation = sessionCancellation,
                    listener = sessionListener,
                    rememberPeer = { name, key ->
                        StreamCenterLocalSyncTrust.rememberPeer(applicationContext, name, key)
                    },
                )
            },
            onSuccess = { result ->
                sessionListener.onStateChanged(StreamCenterLocalSyncState.COMPLETED, "Invio completato")
                sessionListener.onCompleted(result)
            },
        )
    }

    fun startReceiving() {
        val sessionCancellation = beginSession()
        val sessionListener = loggingListener()
        launchSession(
            sessionCancellation = sessionCancellation,
            operation = {
                StreamCenterLocalSyncNetwork.discover(
                    cancellation = sessionCancellation,
                    listener = sessionListener,
                )
            },
        )
    }

    fun acceptOffer(offer: StreamCenterLocalSyncOffer, pairingCode: String) {
        val sessionCancellation = beginSession()
        val sessionListener = loggingListener()
        launchSession(
            sessionCancellation = sessionCancellation,
            operation = {
                StreamCenterLocalSyncNetwork.receive(
                    offer = offer,
                    pairingCode = pairingCode,
                    localIdentityKey = StreamCenterLocalSyncTrust.localPublicKey(applicationContext),
                    cancellation = sessionCancellation,
                    listener = sessionListener,
                    rememberPeer = { name, key ->
                        StreamCenterLocalSyncTrust.rememberPeer(applicationContext, name, key)
                    },
                ) { bytes, type ->
                    StreamCenterLocalSyncStorage.applyPayload(applicationContext, bytes, type)
                }
            },
            onSuccess = { result ->
                sessionListener.onStateChanged(StreamCenterLocalSyncState.COMPLETED, "Configurazione applicata")
                sessionListener.onCompleted(result)
            },
        )
    }

    fun cancel() {
        val wasRunning = stopActiveSession()
        if (wasRunning) {
            listener.onStateChanged(StreamCenterLocalSyncState.CANCELLED, "Sincronizzazione annullata")
            listener.onEvent(StreamCenterLocalSyncEvent("Sessione locale chiusa dall'utente"))
            log("Sessione annullata")
        }
    }

    fun close() {
        stopActiveSession()
        scope.cancel()
    }

    private fun beginSession(): StreamCenterLocalSyncCancellation {
        stopActiveSession()
        if (scope.coroutineContext[Job]?.isActive != true) scope = newScope()
        return StreamCenterLocalSyncCancellation().also { cancellation = it }
    }

    private fun stopActiveSession(): Boolean {
        val sessionCancellation = cancellation
        val sessionJob = activeJob
        val wasRunning = sessionJob?.isActive == true
        sessionCancellation?.close()
        sessionJob?.cancel()
        if (cancellation === sessionCancellation) cancellation = null
        if (activeJob === sessionJob) activeJob = null
        return wasRunning
    }

    private fun <T> launchSession(
        sessionCancellation: StreamCenterLocalSyncCancellation,
        operation: suspend () -> T,
        onSuccess: (T) -> Unit = {},
    ) {
        lateinit var sessionJob: Job
        sessionJob = scope.launch(start = CoroutineStart.LAZY) {
            var closingReason = "termine_operazione"
            try {
                runCatching { operation() }
                    .onSuccess(onSuccess)
                    .onFailure { error ->
                        closingReason = if (error is CancellationException || sessionCancellation.isCancelled) {
                            "interruzione"
                        } else {
                            "errore"
                        }
                        handleFailure(error, sessionCancellation)
                    }
            } finally {
                sessionCancellation.close()
                if (cancellation === sessionCancellation) cancellation = null
                if (activeJob === sessionJob) activeJob = null
                log(
                    "Chiusura sessione completata",
                    mapOf("motivo" to closingReason),
                )
            }
        }
        activeJob = sessionJob
        sessionJob.start()
    }

    private fun handleFailure(error: Throwable, sessionCancellation: StreamCenterLocalSyncCancellation) {
        if (error is CancellationException || sessionCancellation.isCancelled) return
        val message = error.message?.takeIf(String::isNotBlank) ?: "Errore imprevisto nella sincronizzazione locale."
        listener.onStateChanged(StreamCenterLocalSyncState.ERROR, message)
        listener.onError(message, error)
        StreamCenterLogger.logMenuError(
            action = "Errore Sync Locale",
            throwable = error,
            metadata = mapOf("messaggio" to message),
        )
    }

    private fun loggingListener(): StreamCenterLocalSyncListener {
        return object : StreamCenterLocalSyncListener by listener {
            override fun onStateChanged(state: StreamCenterLocalSyncState, message: String) {
                log("Stato aggiornato", mapOf("stato" to state.name, "messaggio" to message))
                listener.onStateChanged(state, message)
            }

            override fun onEvent(event: StreamCenterLocalSyncEvent) {
                if (event.progress == null || event.progress == 0 || event.progress == 100 || event.progress % 10 == 0) {
                    log(
                        "Evento trasferimento",
                        mapOf(
                            "evento" to event.message,
                            "dettaglio" to event.detail,
                            "progresso" to event.progress,
                        ),
                    )
                }
                listener.onEvent(event)
            }

            override fun onPairingCodeReady(code: String) {
                log("Codice di associazione generato")
                listener.onPairingCodeReady(code)
            }

            override fun onOfferFound(offer: StreamCenterLocalSyncOffer) {
                log(
                    "Offerta locale rilevata",
                    mapOf(
                        "tipo" to offer.type.wireValue,
                        "mittente" to offer.senderName,
                        "dimensione" to offer.compressedSize,
                    ),
                )
                listener.onOfferFound(offer)
            }

            override fun onCompleted(result: StreamCenterLocalSyncResult) {
                log(
                    "Trasferimento completato",
                    mapOf(
                        "tipo" to result.type.wireValue,
                        "direzione" to if (result.sent) "invio" else "ricezione",
                        "voci" to result.entryCount,
                        "elementi_libreria" to result.libraryItemCount,
                        "progressi" to result.progressCount,
                        "dispositivo" to result.peerName,
                    ),
                )
                listener.onCompleted(result)
            }
        }
    }

    private fun log(action: String, metadata: Map<String, Any?> = emptyMap()) {
        StreamCenterLogger.logMenu(
            action = "Sync Locale · $action",
            metadata = metadata,
        )
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
