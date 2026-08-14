package it.dogior.hadEnough.localsync

import android.content.Context
import it.dogior.hadEnough.util.StreamCenterLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class StreamCenterLocalSyncAutoCoordinator(
    context: Context,
    private val listener: StreamCenterLocalSyncListener,
) {
    private val appContext = context.applicationContext
    private var scope: CoroutineScope? = null
    private var cancellation: StreamCenterLocalSyncCancellation? = null
    private var activeWindowCancellation: StreamCenterLocalSyncCancellation? = null
    private val passRunning = AtomicBoolean(false)

    val isRunning: Boolean
        get() = scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.isActive == true

    fun start() {
        if (isRunning) return
        if (!StreamCenterLocalSyncAutoConfig.isOperational(appContext)) {
            log("Avvio ignorato: auto-sync non operativo (disattivo o nessun dispositivo fidato)")
            return
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val newCancellation = StreamCenterLocalSyncCancellation()
        scope = newScope
        cancellation = newCancellation

        log("Auto-sync pianificato")
        newScope.launch {
            while (isActive && !newCancellation.isCancelled) {
                delayUntilNextSharedCycle()
                if (newCancellation.isCancelled) break
                runSyncWindow(newCancellation)
            }
        }
    }

    fun forcePeerSync(peerId: String): Boolean {
        if (StreamCenterLocalSyncAutoConfig.categories(appContext).isEmpty()) {
            listener.onEvent(StreamCenterLocalSyncEvent("Sync forzata ignorata", "Nessuna categoria selezionata"))
            return false
        }
        if (activeWindowCancellation != null) {
            listener.onEvent(StreamCenterLocalSyncEvent("Sync forzata in attesa", "E' gia' aperta una finestra di sincronizzazione"))
            return false
        }
        val currentScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val currentCancellation = cancellation ?: StreamCenterLocalSyncCancellation()
        currentScope.launch {
            try {
                runSyncWindow(currentCancellation, targetPeerId = peerId)
            } finally {
                if (scope == null) {
                    currentCancellation.close()
                    currentScope.cancel()
                }
            }
        }
        return true
    }

    fun stop() {
        activeWindowCancellation?.close()
        cancellation?.close()
        scope?.cancel()
        cancellation = null
        scope = null
        log("Auto-sync fermato")
    }

    private suspend fun delayUntilNextSharedCycle() {
        val intervalMs = StreamCenterLocalSyncAutoConfig.intervalMinutes(appContext) * 60_000L
        val now = System.currentTimeMillis()
        val nextCycle = ((now / intervalMs) + 1) * intervalMs
        delay((nextCycle - now).coerceAtLeast(1L))
    }

    private suspend fun runSyncWindow(
        lifecycleCancellation: StreamCenterLocalSyncCancellation,
        targetPeerId: String? = null,
    ) {
        if (!passRunning.compareAndSet(false, true)) return
        val windowCancellation = StreamCenterLocalSyncCancellation()
        activeWindowCancellation = windowCancellation
        val identity = StreamCenterLocalSyncTrust.localIdentity(appContext)
        val identityKey = StreamCenterLocalSyncTrust.localPublicKey(appContext)
        val name = StreamCenterLocalSyncStorage.deviceName()
        val isTrusted: (String) -> Boolean = { key -> StreamCenterLocalSyncTrust.isTrusted(appContext, key) }
        try {
            val openingMessage = if (targetPeerId == null) {
                "Finestra automatica aperta"
            } else {
                "Finestra manuale aperta"
            }
            listener.onStateChanged(StreamCenterLocalSyncState.DISCOVERING, openingMessage)
            listener.onEvent(
                StreamCenterLocalSyncEvent(
                    openingMessage,
                    "Ricerca dispositivi fidati per alcuni secondi",
                ),
            )
            coroutineScope {
                val server = launch {
                    runCatching {
                        StreamCenterLocalSyncNetwork.serveAuto(
                            localIdentity = identity,
                            localIdentityKey = identityKey,
                            localName = name,
                            isTrusted = isTrusted,
                            cancellation = windowCancellation,
                            listener = listener,
                            onSession = ::onSession,
                        )
                    }.onFailure { error -> reportFailure("server automatico", error, windowCancellation) }
                }
                delay(WINDOW_SERVER_READY_DELAY_MS)
                runPass(
                    identity = identity,
                    identityKey = identityKey,
                    name = name,
                    isTrusted = isTrusted,
                    cancellation = windowCancellation,
                    targetPeerId = targetPeerId,
                )
                delay(WINDOW_TRANSFER_GRACE_MS)
                windowCancellation.close()
                server.join()
            }
        } finally {
            windowCancellation.close()
            if (activeWindowCancellation === windowCancellation) activeWindowCancellation = null
            passRunning.set(false)
            if (!lifecycleCancellation.isCancelled) {
                listener.onStateChanged(
                    StreamCenterLocalSyncState.COMPLETED,
                    "Finestra di sincronizzazione chiusa",
                )
                listener.onEvent(StreamCenterLocalSyncEvent("Finestra di sincronizzazione chiusa"))
            }
        }
    }

    private suspend fun runPass(
        identity: java.security.KeyPair,
        identityKey: String,
        name: String,
        isTrusted: (String) -> Boolean,
        cancellation: StreamCenterLocalSyncCancellation,
        targetPeerId: String? = null,
    ) {
        val peers = StreamCenterLocalSyncNetwork.scanTrustedPeers(
            isTrusted = isTrusted,
            localIdentityKey = identityKey,
            timeoutMs = SCAN_TIMEOUT_MS,
            cancellation = cancellation,
        )
        val targetFound = targetPeerId?.let { id ->
            peers.any { peer -> StreamCenterLocalSyncCrypto.fingerprint(peer.identityKey) == id }
        } ?: true
        if (targetPeerId != null && !targetFound) {
            listener.onEvent(
                StreamCenterLocalSyncEvent(
                    "Dispositivo non raggiungibile",
                    "Tieni premuto anche l'altro dispositivo entro pochi secondi.",
                ),
            )
            return
        }
        val localFingerprint = StreamCenterLocalSyncTrust.localFingerprint(appContext)
        peers.forEach { peer ->
            if (cancellation.isCancelled) return
            val peerFingerprint = StreamCenterLocalSyncCrypto.fingerprint(peer.identityKey)
            if (targetPeerId != null && peerFingerprint != targetPeerId) return@forEach
            if (localFingerprint >= peerFingerprint) return@forEach
            runCatching {
                StreamCenterLocalSyncNetwork.connectAuto(
                    peer = peer,
                    localIdentity = identity,
                    localIdentityKey = identityKey,
                    localName = name,
                    cancellation = cancellation,
                    listener = listener,
                    onSession = ::onSession,
                )
            }.onFailure { error -> reportFailure("connessione a ${peer.name}", error, cancellation) }
        }
    }

    private suspend fun onSession(
        peerName: String,
        peerIdentityKey: String,
        isInitiator: Boolean,
        keys: StreamCenterLocalSyncSessionKeys,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        val categories = StreamCenterLocalSyncAutoConfig.categories(appContext)
        val source = StreamCenterLocalSyncStorage.mergeSource(appContext, categories)
        val peerId = StreamCenterLocalSyncCrypto.fingerprint(peerIdentityKey)
        val mode = StreamCenterLocalSyncAutoConfig.peerMode(appContext, peerId)
        val result = StreamCenterLocalSyncNetwork.runMergeExchange(
            isInitiator = isInitiator,
            source = source,
            allowSend = mode.canSend,
            allowReceive = mode.canReceive,
            keys = keys,
            input = input,
            output = output,
            listener = listener,
        )
        StreamCenterLocalSyncTrust.markSynced(appContext, peerId)
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Sincronizzazione automatica riuscita",
                detail = "$peerName · ${mode.title} · ${result.sent} inviate · ${result.received} ricevute",
            ),
        )
        StreamCenterLocalSyncStorage.transferredMediaSummaries(appContext, result.sentKeys)
            .takeIf { it.isNotEmpty() }
            ?.let { entries ->
                listener.onEvent(
                    StreamCenterLocalSyncEvent(
                        message = "Contenuti inviati",
                        detail = entries.joinToString("\n"),
                    ),
                )
            }
        StreamCenterLocalSyncStorage.transferredMediaSummaries(appContext, result.receivedKeys)
            .takeIf { it.isNotEmpty() }
            ?.let { entries ->
                listener.onEvent(
                    StreamCenterLocalSyncEvent(
                        message = "Contenuti ricevuti",
                        detail = entries.joinToString("\n"),
                    ),
                )
            }
        val changed = result.sent + result.received
        if (changed > 0) {
            StreamCenterLocalSyncNotifier.notifySync(
                context = appContext,
                title = "Sync Locale",
                message = "Sincronizzato con $peerName · ${result.sent} inviate · ${result.received} ricevute",
            )
        }
        log(
            "Sessione automatica completata",
            mapOf(
                "dispositivo" to peerName,
                "modalita" to mode.wireValue,
                "inviate" to result.sent,
                "ricevute" to result.received,
            ),
        )
    }

    private fun reportFailure(
        action: String,
        error: Throwable,
        cancellation: StreamCenterLocalSyncCancellation,
    ) {
        if (cancellation.isCancelled) return
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Auto-sync: $action non riuscita",
                detail = error.message?.take(160) ?: error.javaClass.simpleName,
            ),
        )
    }

    private fun log(action: String, metadata: Map<String, Any?> = emptyMap()) {
        StreamCenterLogger.logMenu(action = "Sync Locale Auto · $action", metadata = metadata)
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 8_000L
        const val WINDOW_SERVER_READY_DELAY_MS = 400L
        const val WINDOW_TRANSFER_GRACE_MS = 20_000L
    }
}
