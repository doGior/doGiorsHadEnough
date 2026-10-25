package it.dogior.hadEnough.remote

import android.content.Context
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncCancellation
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncChannel
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncNetwork
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncPresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface StreamCenterRemoteClientListener {
    fun onStatus(message: String, connected: Boolean)
    fun onState(state: RemotePlayerState?, parked: Boolean)
    fun onHandoff(handoff: RemoteHandoff?)
}

internal class StreamCenterRemoteClient(
    context: Context,
    private val listener: StreamCenterRemoteClientListener,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: Job? = null
    private var cancellation: StreamCenterLocalSyncCancellation? = null

    @Volatile
    private var channel: StreamCenterRemoteChannel? = null

    @Volatile
    var target: StreamCenterLocalSyncPresence? = null
        private set

    suspend fun scan(): List<StreamCenterLocalSyncPresence> = scanReceivers(appContext)

    @Synchronized
    fun connect(peer: StreamCenterLocalSyncPresence) {
        disconnect()
        target = peer
        val sessionCancellation = StreamCenterLocalSyncCancellation()
        cancellation = sessionCancellation
        connection = scope.launch {
            var attempt = 0
            var current = peer
            while (isActive && !sessionCancellation.isCancelled && !StreamCenterPlugin.completeResetInProgress) {
                try {
                    listener.onStatus("Collegamento a ${current.name}…", false)
                    session(appContext, current, sessionCancellation) { remote ->
                        attempt = 0
                        channel = remote
                        listener.onStatus("Collegato a ${current.name}", true)
                        while (isActive) {
                            val message = remote.receive()
                            when (message.optString("type")) {
                                "state" -> listener.onState(
                                    RemotePlayerState.fromJson(message.optJSONObject("state")),
                                    message.optBoolean("parked"),
                                )
                                "handoff" -> listener.onHandoff(
                                    message.optJSONObject("handoff")?.let(RemoteHandoff::fromJson),
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    channel = null
                }
                if (sessionCancellation.isCancelled) break
                val wait = RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                attempt += 1
                listener.onStatus("${current.name} non raggiungibile, riprovo tra ${wait / 1_000} s", false)
                delay(wait)
                scanReceivers(appContext).firstOrNull { it.identityKey == current.identityKey }?.let { current = it }
            }
        }
    }

    fun command(command: RemoteCommand) {
        val message = JSONObject().put("type", "command").put("action", RemoteCommand.actionOf(command))
        when (command) {
            is RemoteCommand.SeekTo -> message.put("positionMs", command.positionMs)
            is RemoteCommand.Subtitle -> message.put("subtitleId", command.id ?: JSONObject.NULL)
            else -> Unit
        }
        send(message)
    }

    fun send(message: JSONObject): Boolean {
        val remote = channel ?: return false
        scope.launch { runCatching { remote.send(message.put("sentAt", System.currentTimeMillis())) } }
        return true
    }

    @Synchronized
    fun disconnect() {
        cancellation?.close()
        cancellation = null
        connection?.cancel()
        connection = null
        channel = null
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 3_000L
        private const val PING_INTERVAL_MS = 5_000L
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        suspend fun scanReceivers(context: Context): List<StreamCenterLocalSyncPresence> {
            if (StreamCenterPlugin.completeResetInProgress) return emptyList()
            val identity = StreamCenterRemoteIdentity.of(context)
            val scanCancellation = StreamCenterLocalSyncCancellation()
            return try {
                StreamCenterLocalSyncNetwork.scanTrustedPeers(
                    isTrusted = identity.isTrusted,
                    localIdentityKey = identity.publicKey,
                    timeoutMs = SCAN_TIMEOUT_MS,
                    cancellation = scanCancellation,
                    channel = StreamCenterLocalSyncChannel.REMOTE,
                )
            } finally {
                scanCancellation.close()
            }
        }

        suspend fun deliver(
            context: Context,
            peer: StreamCenterLocalSyncPresence,
            message: JSONObject,
            awaitType: String? = null,
        ): JSONObject? = withContext(Dispatchers.IO) {
            val oneShot = StreamCenterLocalSyncCancellation()
            try {
                var reply: JSONObject? = null
                session(context.applicationContext, peer, oneShot) { remote ->
                    remote.send(message.put("sentAt", System.currentTimeMillis()))
                    while (awaitType != null) {
                        val incoming = remote.receive()
                        if (incoming.optString("type") == awaitType) {
                            reply = incoming
                            break
                        }
                    }
                }
                reply
            } finally {
                oneShot.close()
            }
        }

        private suspend fun session(
            context: Context,
            peer: StreamCenterLocalSyncPresence,
            cancellation: StreamCenterLocalSyncCancellation,
            block: suspend (StreamCenterRemoteChannel) -> Unit,
        ) {
            val identity = StreamCenterRemoteIdentity.of(context)
            StreamCenterLocalSyncNetwork.connectAuto(
                peer = peer,
                localIdentity = identity.keyPair,
                localIdentityKey = identity.publicKey,
                localName = identity.name,
                cancellation = cancellation,
                listener = StreamCenterRemoteLogListener,
                channel = StreamCenterLocalSyncChannel.REMOTE,
            ) { _, _, _, keys, input, output ->
                val remote = StreamCenterRemoteChannel(keys, input, output, isInitiator = true)
                remote.send(JSONObject().put("type", "hello").put("sentAt", System.currentTimeMillis()))
                coroutineScope {
                    val pinger = launch {
                        while (isActive) {
                            delay(PING_INTERVAL_MS)
                            remote.send(JSONObject().put("type", "ping").put("sentAt", System.currentTimeMillis()))
                        }
                    }
                    try {
                        block(remote)
                    } finally {
                        pinger.cancel()
                    }
                }
            }
        }
    }
}
