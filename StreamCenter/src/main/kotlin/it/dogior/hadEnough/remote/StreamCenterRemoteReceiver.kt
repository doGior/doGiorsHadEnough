package it.dogior.hadEnough.remote

import android.content.Context
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncCancellation
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncChannel
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncNetwork
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncSessionKeys
import it.dogior.hadEnough.util.StreamCenterLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

internal object StreamCenterRemoteReceiver {
    private const val MAXIMUM_SESSIONS = 2
    private const val STATE_INTERVAL_MS = 1_000L
    private const val MAXIMUM_COMMAND_AGE_MS = 3_000L
    private const val RETRY_DELAY_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var cancellation: StreamCenterLocalSyncCancellation? = null

    @Synchronized
    fun update(context: Context, shouldRun: Boolean) {
        if (shouldRun && job?.isActive != true) start(context.applicationContext)
        if (!shouldRun) stop()
    }

    @Synchronized
    fun stop(): Job? {
        val running = job
        cancellation?.close()
        cancellation = null
        running?.cancel()
        job = null
        return running
    }

    private fun start(context: Context) {
        val windowCancellation = StreamCenterLocalSyncCancellation()
        cancellation = windowCancellation
        job = scope.launch {
            while (isActive && !windowCancellation.isCancelled && !StreamCenterPlugin.completeResetInProgress) {
                try {
                    val identity = StreamCenterRemoteIdentity.of(context)
                    StreamCenterLocalSyncNetwork.serveAuto(
                        localIdentity = identity.keyPair,
                        localIdentityKey = identity.publicKey,
                        localName = identity.name,
                        isTrusted = identity.isTrusted,
                        cancellation = windowCancellation,
                        listener = StreamCenterRemoteLogListener,
                        channel = StreamCenterLocalSyncChannel.REMOTE,
                        maximumSessions = MAXIMUM_SESSIONS,
                    ) { peerName, _, _, keys, input, output ->
                        serve(context, peerName, keys, input, output)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (windowCancellation.isCancelled) break
                    StreamCenterLogger.warning("Telecomando: ascolto non disponibile", mapOf("errore" to error.toString()))
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun serve(
        context: Context,
        peerName: String,
        keys: StreamCenterLocalSyncSessionKeys,
        input: DataInputStream,
        output: DataOutputStream,
    ) = coroutineScope {
        val channel = StreamCenterRemoteChannel(keys, input, output, isInitiator = false)
        var clockOffset: Long? = null
        val publisher = launch {
            while (isActive) {
                channel.send(stateMessage())
                delay(STATE_INTERVAL_MS)
            }
        }
        try {
            while (isActive && !StreamCenterPlugin.completeResetInProgress) {
                val message = channel.receive()
                val receivedAt = System.currentTimeMillis()
                val sentAt = message.optLong("sentAt", 0L)
                when (message.optString("type")) {
                    "hello", "ping" -> if (clockOffset == null) clockOffset = receivedAt - sentAt
                    "command" -> {
                        val offset = clockOffset ?: continue
                        val age = receivedAt - (sentAt + offset)
                        val command = RemoteCommand.parse(
                            action = message.optString("action"),
                            positionMs = message.takeIf { it.has("positionMs") }?.optLong("positionMs"),
                            subtitleId = message.optString("subtitleId").takeIf { message.has("subtitleId") },
                        )
                        if (command == null || age > MAXIMUM_COMMAND_AGE_MS) {
                            StreamCenterLogger.warning(
                                "Telecomando: comando scartato",
                                mapOf("azione" to message.optString("action"), "eta_ms" to age, "da" to peerName),
                            )
                            continue
                        }
                        StreamCenterRemotePlayer.execute(command)
                    }
                    "open" -> {
                        val card = RemoteCard(
                            url = message.optString("url").take(2_048),
                            apiName = message.optString("apiName").take(120),
                            name = message.optString("name").take(200),
                        )
                        if (card.url.isNotBlank() && card.apiName.isNotBlank()) StreamCenterRemoteNavigator.openCard(card)
                    }
                    "play" -> RemoteHandoff.fromJson(message.optJSONObject("handoff") ?: JSONObject())
                        ?.let(StreamCenterRemoteNavigator::play)
                    "takeOver" -> {
                        val parked = StreamCenterRemoteNavigator.takeParked()
                        val handoff = StreamCenterRemotePlayer.handoff() ?: parked
                        if (handoff != null) StreamCenterRemotePlayer.close()
                        channel.send(JSONObject().put("type", "handoff").putOpt("handoff", handoff?.toJson()))
                    }
                    "queueAdd" -> RemoteQueueItem.fromJson(message.optJSONObject("item") ?: JSONObject())
                        ?.let { StreamCenterRemoteQueue.put(context, it) }
                    "queueRemove" -> StreamCenterRemoteQueue.remove(context, message.optString("id"))
                }
            }
        } finally {
            publisher.cancel()
        }
    }

    private fun stateMessage(): JSONObject = JSONObject()
        .put("type", "state")
        .putOpt("state", StreamCenterRemotePlayer.state?.toJson())
        .put("parked", StreamCenterRemoteNavigator.parkedHandoff() != null)
}
