package it.dogior.hadEnough.remote

import android.content.Context
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncEvent
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncListener
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncNetwork
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncOffer
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncResult
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncSessionKeys
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncState
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncStorage
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncTrust
import it.dogior.hadEnough.util.StreamCenterLogger
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyPair

internal class StreamCenterRemoteChannel(
    private val keys: StreamCenterLocalSyncSessionKeys,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    isInitiator: Boolean,
) {
    private val outgoing = if (isInitiator) "c2s" else "s2c"
    private val incoming = if (isInitiator) "s2c" else "c2s"
    private var sent = 0L
    private var received = 0L

    @Synchronized
    fun send(message: JSONObject) {
        sent += 1
        StreamCenterLocalSyncNetwork.writeSecureMessage(
            output,
            keys.payloadEncryptionKey,
            message,
            "remote|$outgoing|$sent",
        )
    }

    fun receive(): JSONObject {
        val expected = received + 1
        val message = StreamCenterLocalSyncNetwork.readSecureMessage(
            input,
            keys.payloadEncryptionKey,
            "remote|$incoming|$expected",
        )
        received = expected
        return message
    }
}

internal class StreamCenterRemoteIdentity(
    val keyPair: KeyPair,
    val publicKey: String,
    val name: String,
    val isTrusted: (String) -> Boolean,
) {
    companion object {
        fun of(context: Context): StreamCenterRemoteIdentity {
            val app = context.applicationContext
            return StreamCenterRemoteIdentity(
                keyPair = StreamCenterLocalSyncTrust.localIdentity(app),
                publicKey = StreamCenterLocalSyncTrust.localPublicKey(app),
                name = StreamCenterLocalSyncStorage.deviceName(),
                isTrusted = { key -> StreamCenterLocalSyncTrust.isTrusted(app, key) },
            )
        }
    }
}

internal object StreamCenterRemoteLogListener : StreamCenterLocalSyncListener {
    override fun onStateChanged(state: StreamCenterLocalSyncState, message: String) = Unit
    override fun onEvent(event: StreamCenterLocalSyncEvent) {
        if (event.progress != null) return
        StreamCenterLogger.logMenu(
            action = "Telecomando: ${event.message}",
            metadata = mapOf("dettaglio" to event.detail),
        )
    }

    override fun onOfferFound(offer: StreamCenterLocalSyncOffer) = Unit
    override fun onPairingCodeReady(code: String) = Unit
    override fun onCompleted(result: StreamCenterLocalSyncResult) = Unit
    override fun onError(message: String, error: Throwable?) = Unit
}
