package it.dogior.hadEnough.localsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class StreamCenterLocalNetworkEndpoint(
    val interfaceName: String,
    val address: Inet4Address,
    val broadcastAddress: Inet4Address,
    val prefixLength: Short,
)

internal data class StreamCenterLocalSyncPresence(
    val identityKey: String,
    val name: String,
    val address: Inet4Address,
    val tcpPort: Int,
)

internal object StreamCenterLocalNetworkPolicy {
    private val blockedInterfaceParts = listOf(
        "tun",
        "tap",
        "vpn",
        "ppp",
        "rmnet",
        "ccmni",
        "pdp",
        "wwan",
        "mobile",
        "cell",
    )

    fun endpoints(): List<StreamCenterLocalNetworkEndpoint> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty()
        return interfaces
            .asSequence()
            .filter { networkInterface ->
                runCatching {
                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        !networkInterface.isVirtual &&
                        blockedInterfaceParts.none { part ->
                            networkInterface.name.orEmpty().contains(part, ignoreCase = true) ||
                                networkInterface.displayName.orEmpty().contains(part, ignoreCase = true)
                        }
                }.getOrDefault(false)
            }
            .flatMap { networkInterface ->
                networkInterface.interfaceAddresses.asSequence().mapNotNull { interfaceAddress ->
                    val address = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
                    val broadcast = interfaceAddress.broadcast as? Inet4Address ?: return@mapNotNull null
                    if (!isPrivate(address)) return@mapNotNull null
                    StreamCenterLocalNetworkEndpoint(
                        interfaceName = networkInterface.name,
                        address = address,
                        broadcastAddress = broadcast,
                        prefixLength = interfaceAddress.networkPrefixLength,
                    )
                }
            }
            .distinctBy { endpoint -> endpoint.address.hostAddress }
            .sortedBy { endpoint -> interfacePriority(endpoint.interfaceName) }
            .toList()
    }

    fun routeTo(remoteAddress: InetAddress): StreamCenterLocalNetworkEndpoint? {
        val remote = remoteAddress as? Inet4Address ?: return null
        if (!isPrivate(remote)) return null
        return endpoints().firstOrNull { endpoint -> sameSubnet(endpoint.address, remote, endpoint.prefixLength) }
    }

    fun isPrivate(address: InetAddress): Boolean {
        val ipv4 = address as? Inet4Address ?: return false
        val bytes = ipv4.address.map(Byte::toInt).map { value -> value and 0xFF }
        return when {
            bytes[0] == 10 -> true
            bytes[0] == 172 && bytes[1] in 16..31 -> true
            bytes[0] == 192 && bytes[1] == 168 -> true
            else -> false
        }
    }

    fun isSameSubnet(endpoint: StreamCenterLocalNetworkEndpoint, address: InetAddress): Boolean {
        val remote = address as? Inet4Address ?: return false
        return isPrivate(remote) && sameSubnet(endpoint.address, remote, endpoint.prefixLength)
    }

    private fun sameSubnet(first: Inet4Address, second: Inet4Address, prefixLength: Short): Boolean {
        val prefix = prefixLength.toInt().coerceIn(1, 32)
        val firstValue = first.address.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xFF) }
        val secondValue = second.address.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xFF) }
        val mask = if (prefix == 32) -1 else -1 shl (32 - prefix)
        return (firstValue and mask) == (secondValue and mask)
    }

    private fun interfacePriority(name: String): Int {
        val normalized = name.lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("wlan") || normalized.contains("wifi") -> 0
            normalized.startsWith("eth") || normalized.startsWith("en") -> 1
            normalized.contains("ap") -> 2
            else -> 3
        }
    }
}

internal class StreamCenterLocalSyncCancellation : Closeable {
    private val cancelled = AtomicBoolean(false)
    private val resourceLock = Any()
    private val resources = IdentityHashMap<Closeable, AtomicBoolean>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun <T : Closeable> track(resource: T): T {
        val closed = AtomicBoolean(false)
        val closeImmediately = synchronized(resourceLock) {
            if (isCancelled) {
                true
            } else {
                resources[resource] = closed
                false
            }
        }
        if (closeImmediately) closeSafely(resource, closed)
        return resource
    }

    fun close(resource: Closeable) {
        val closed = synchronized(resourceLock) { resources[resource] } ?: return
        closeSafely(resource, closed)
        synchronized(resourceLock) {
            if (resources[resource] === closed) resources.remove(resource)
        }
    }

    override fun close() {
        if (!cancelled.compareAndSet(false, true)) return
        val trackedResources = synchronized(resourceLock) {
            resources.entries.map { it.key to it.value }
        }
        trackedResources.forEach { (resource, closed) -> closeSafely(resource, closed) }
        synchronized(resourceLock) {
            trackedResources.forEach { (resource, closed) ->
                if (resources[resource] === closed) resources.remove(resource)
            }
        }
    }

    private fun closeSafely(resource: Closeable, closed: AtomicBoolean) {
        if (closed.compareAndSet(false, true)) runCatching(resource::close)
    }
}

internal object StreamCenterLocalSyncNetwork {
    private const val MAGIC = "SCLS"
    private const val PROTOCOL_VERSION = 3
    private const val DISCOVERY_PORT = 39_482
    private const val AUTO_PRESENCE_PORT = 39_483
    private const val AUTO_PRESENCE_INTERVAL_MS = 3_000L
    private const val DISCOVERY_INTERVAL_MS = 1_000L
    private const val SESSION_TIMEOUT_MS = 10 * 60 * 1_000L
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val MAX_DISCOVERY_BYTES = 8 * 1024
    private const val MAX_CONTROL_BYTES = 64 * 1024
    private const val MAX_TRANSFER_BYTES = 16 * 1024 * 1024
    private const val MAX_FAILED_AUTHENTICATIONS = 5
    private const val TRANSFER_CHUNK_BYTES = 64 * 1024

    suspend fun send(
        payload: StreamCenterLocalSyncPayload,
        localIdentityKey: String,
        cancellation: StreamCenterLocalSyncCancellation,
        listener: StreamCenterLocalSyncListener,
        rememberPeer: (name: String, identityPublicKey: String) -> Unit = { _, _ -> },
    ): StreamCenterLocalSyncResult = coroutineScope {
        val endpoint = StreamCenterLocalNetworkPolicy.endpoints().firstOrNull()
            ?: throw IllegalStateException("Nessuna interfaccia Wi-Fi o Ethernet privata disponibile.")
        val serverKeyPair = StreamCenterLocalSyncCrypto.generateKeyPair()
        val serverPublicKey = StreamCenterLocalSyncCrypto.encodePublicKey(serverKeyPair)
        val pairingCode = StreamCenterLocalSyncCrypto.pairingCode()
        val sessionId = StreamCenterLocalSyncCrypto.sessionId()
        val serverSocket = cancellation.track(ServerSocket())
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(0), 2)
        serverSocket.soTimeout = 1_000
        val offer = StreamCenterLocalSyncOffer(
            sessionId = sessionId,
            type = payload.type,
            senderName = StreamCenterLocalSyncStorage.deviceName(),
            senderAddress = endpoint.address.hostAddress.orEmpty(),
            senderPublicKey = serverPublicKey,
            senderIdentityKey = localIdentityKey,
            tcpPort = serverSocket.localPort,
            compressedSize = payload.compressedBytes.size,
            uncompressedSize = payload.uncompressedSize,
            entryCount = payload.entryCount,
            libraryItemCount = payload.libraryItemCount,
            progressCount = payload.progressCount,
        )
        listener.onPairingCodeReady(pairingCode)
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Interfaccia locale selezionata",
                detail = "${endpoint.interfaceName} · ${endpoint.address.hostAddress}/${endpoint.prefixLength}",
            ),
        )
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Server locale pronto",
                detail = "TCP ${endpoint.address.hostAddress}:${serverSocket.localPort}",
            ),
        )
        listener.onStateChanged(StreamCenterLocalSyncState.ADVERTISING, "In attesa del dispositivo ricevente")
        val broadcaster = launch(Dispatchers.IO) {
            broadcastOffer(endpoint, offer, cancellation, listener)
        }
        val deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS
        var failedAuthentications = 0
        try {
            while (!cancellation.isCancelled && System.currentTimeMillis() < deadline) {
                ensureActive()
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: SocketException) {
                    if (cancellation.isCancelled) throw InterruptedException("Sincronizzazione annullata.")
                    throw error
                }
                cancellation.track(socket)
                var rejected = false
                try {
                    val remoteAddress = socket.inetAddress
                    require(StreamCenterLocalNetworkPolicy.isSameSubnet(endpoint, remoteAddress)) {
                        "Connessione rifiutata: il dispositivo non appartiene alla rete locale privata."
                    }
                    listener.onEvent(
                        StreamCenterLocalSyncEvent(
                            message = "Dispositivo ricevente connesso",
                            detail = remoteAddress.hostAddress,
                        ),
                    )
                    val result = handleSenderConnection(
                        socket = socket,
                        offer = offer,
                        payload = payload,
                        serverKeyPair = serverKeyPair,
                        serverPublicKey = serverPublicKey,
                        serverIdentityKey = localIdentityKey,
                        pairingCode = pairingCode,
                        listener = listener,
                        rememberPeer = rememberPeer,
                    )
                    if (result != null) return@coroutineScope result
                    rejected = true
                } catch (error: Exception) {
                    if (cancellation.isCancelled) throw error
                    rejected = true
                    listener.onEvent(
                        StreamCenterLocalSyncEvent(
                            message = "Connessione ricevente rifiutata",
                            detail = error.message?.take(160) ?: error.javaClass.simpleName,
                        ),
                    )
                } finally {
                    cancellation.close(socket)
                }
                if (rejected) {
                    failedAuthentications += 1
                    listener.onEvent(
                        StreamCenterLocalSyncEvent(
                            message = "Autenticazione rifiutata",
                            detail = "Tentativo $failedAuthentications di $MAX_FAILED_AUTHENTICATIONS",
                        ),
                    )
                    check(failedAuthentications < MAX_FAILED_AUTHENTICATIONS) {
                        "Troppi tentativi di associazione non validi."
                    }
                }
            }
            if (cancellation.isCancelled) throw InterruptedException("Sincronizzazione annullata.")
            throw SocketTimeoutException("Nessun dispositivo si è collegato entro dieci minuti.")
        } finally {
            broadcaster.cancelAndJoin()
            cancellation.close(serverSocket)
        }
    }

    suspend fun discover(
        cancellation: StreamCenterLocalSyncCancellation,
        listener: StreamCenterLocalSyncListener,
    ) = withContext(Dispatchers.IO) {
        check(StreamCenterLocalNetworkPolicy.endpoints().isNotEmpty()) {
            "Nessuna interfaccia Wi-Fi o Ethernet privata disponibile."
        }
        val socket = cancellation.track(DatagramSocket(null))
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(DISCOVERY_PORT))
        socket.soTimeout = 1_000
        val seenSessions = mutableSetOf<String>()
        listener.onStateChanged(StreamCenterLocalSyncState.DISCOVERING, "Ricerca dei dispositivi sulla rete locale")
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Ricevitore discovery attivo",
                detail = "UDP 0.0.0.0:$DISCOVERY_PORT · solo mittenti IPv4 privati",
            ),
        )
        try {
            while (!cancellation.isCancelled) {
                ensureActive()
                val buffer = ByteArray(MAX_DISCOVERY_BYTES)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: SocketException) {
                    if (cancellation.isCancelled) break
                    throw error
                }
                if (!StreamCenterLocalNetworkPolicy.isPrivate(packet.address)) continue
                val offer = parseOffer(
                    String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8),
                    packet.address,
                ) ?: continue
                if (seenSessions.add(offer.sessionId)) {
                    listener.onEvent(
                        StreamCenterLocalSyncEvent(
                            message = "Offerta locale trovata",
                            detail = "${offer.senderName} · ${offer.type.title} · ${offer.senderAddress}:${offer.tcpPort}",
                        ),
                    )
                    listener.onOfferFound(offer)
                }
            }
        } finally {
            cancellation.close(socket)
        }
    }

    suspend fun receive(
        offer: StreamCenterLocalSyncOffer,
        pairingCode: String,
        localIdentityKey: String,
        cancellation: StreamCenterLocalSyncCancellation,
        listener: StreamCenterLocalSyncListener,
        rememberPeer: (name: String, identityPublicKey: String) -> Unit = { _, _ -> },
        applyPayload: (ByteArray, StreamCenterLocalSyncPayloadType) -> StreamCenterLocalSyncResult,
    ): StreamCenterLocalSyncResult = withContext(Dispatchers.IO) {
        require(pairingCode.matches(Regex("\\d{6}"))) { "Inserisci il codice di sei cifre mostrato dal mittente." }
        require(offer.compressedSize in 1..MAX_TRANSFER_BYTES) { "Dimensione del trasferimento non valida." }
        val remoteAddress = parsePrivateIpv4(offer.senderAddress)
            ?: throw SecurityException("Indirizzo locale del mittente non valido.")
        val endpoint = StreamCenterLocalNetworkPolicy.routeTo(remoteAddress)
            ?: throw SecurityException("Il mittente non appartiene alla rete locale privata.")
        val socket = cancellation.track(Socket())
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(endpoint.address, 0))
            listener.onEvent(
                StreamCenterLocalSyncEvent(
                    message = "Connessione locale in apertura",
                    detail = "${endpoint.address.hostAddress} → ${remoteAddress.hostAddress}:${offer.tcpPort}",
                ),
            )
            socket.connect(InetSocketAddress(remoteAddress, offer.tcpPort), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            listener.onStateChanged(StreamCenterLocalSyncState.AUTHENTICATING, "Verifica cifrata del codice")
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val clientKeyPair = StreamCenterLocalSyncCrypto.generateKeyPair()
            val clientPublicKey = StreamCenterLocalSyncCrypto.encodePublicKey(clientKeyPair)
            val keys = StreamCenterLocalSyncCrypto.deriveKeys(
                keyPair = clientKeyPair,
                peerPublicKey = offer.senderPublicKey,
                pairingCode = pairingCode,
                sessionId = offer.sessionId,
            )
            val transcript = authenticationTranscript(
                offer.sessionId,
                clientPublicKey,
                offer.senderPublicKey,
                localIdentityKey,
                offer.senderIdentityKey,
            )
            val clientProof = StreamCenterLocalSyncCrypto.authenticationProof(
                keys.clientAuthenticationKey,
                "client|$transcript",
            )
            val hello = JSONObject()
                .put("sessionId", offer.sessionId)
                .put("receiverName", StreamCenterLocalSyncStorage.deviceName())
                .put("publicKey", clientPublicKey)
                .put("identityKey", localIdentityKey)
                .put("proof", StreamCenterLocalSyncCrypto.encodeBase64(clientProof))
            writeFrame(output, hello.toString().toByteArray(StandardCharsets.UTF_8))
            output.flush()
            check(input.readBoolean()) { "Codice di associazione non corretto." }
            val serverProof = readFrame(input, MAX_CONTROL_BYTES)
            val expectedServerProof = StreamCenterLocalSyncCrypto.authenticationProof(
                keys.serverAuthenticationKey,
                "server|$transcript",
            )
            check(
                StreamCenterLocalSyncCrypto.proofsMatch(expectedServerProof, serverProof),
            ) { "L'identità crittografica del mittente non è valida." }

            offer.senderIdentityKey.takeIf(String::isNotBlank)?.let { identity ->
                rememberPeer(offer.senderName, identity)
            }
            listener.onEvent(
                StreamCenterLocalSyncEvent(
                    message = "Associazione verificata",
                    detail = "ECDH P-256 · AES-256-GCM",
                ),
            )
            listener.onStateChanged(StreamCenterLocalSyncState.TRANSFERRING, "Ricezione cifrata in corso")
            val encryptedPayload = readEncrypted(
                input = input,
                maximumCiphertextBytes = MAX_TRANSFER_BYTES + 32,
                expectedBytes = offer.compressedSize + 16,
                listener = listener,
                progressMessage = "Ricezione payload",
            )
            val compressedPayload = StreamCenterLocalSyncCrypto.decrypt(
                key = keys.payloadEncryptionKey,
                encrypted = encryptedPayload,
                associatedData = payloadAssociatedData(offer),
            )
            check(compressedPayload.size == offer.compressedSize) { "Dimensione del payload ricevuto non valida." }
            listener.onEvent(
                StreamCenterLocalSyncEvent(
                    message = "Integrità crittografica verificata",
                    detail = "${compressedPayload.size} byte cifrati e autenticati",
                    progress = 100,
                ),
            )
            listener.onStateChanged(StreamCenterLocalSyncState.APPLYING, "Applicazione della configurazione")
            val application = runCatching { applyPayload(compressedPayload, offer.type) }
            val acknowledgement = application.fold(
                onSuccess = { result ->
                    JSONObject()
                        .put("status", "applied")
                        .put("entries", result.entryCount)
                        .put("libraryItems", result.libraryItemCount)
                        .put("progressEntries", result.progressCount)
                },
                onFailure = {
                    JSONObject().put("status", "failed")
                },
            )
            val encryptedAcknowledgement = StreamCenterLocalSyncCrypto.encrypt(
                key = keys.acknowledgementEncryptionKey,
                plaintext = acknowledgement.toString().toByteArray(StandardCharsets.UTF_8),
                associatedData = acknowledgementAssociatedData(offer),
            )
            writeEncrypted(output, encryptedAcknowledgement, null, "")
            output.flush()
            application.getOrThrow().copy(peerName = offer.senderName)
        } finally {
            cancellation.close(socket)
        }
    }

    suspend fun serveAuto(
        localIdentity: KeyPair,
        localIdentityKey: String,
        localName: String,
        isTrusted: (identityKey: String) -> Boolean,
        cancellation: StreamCenterLocalSyncCancellation,
        listener: StreamCenterLocalSyncListener,
        onSession: suspend (
            peerName: String,
            peerIdentityKey: String,
            isInitiator: Boolean,
            keys: StreamCenterLocalSyncSessionKeys,
            input: DataInputStream,
            output: DataOutputStream,
        ) -> Unit,
    ): Unit = coroutineScope {
        val endpoints = StreamCenterLocalNetworkPolicy.endpoints()
        require(endpoints.isNotEmpty()) {
            "Nessuna interfaccia Wi-Fi o Ethernet privata disponibile."
        }
        val serverSocket = cancellation.track(ServerSocket())
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(0), 4)
        serverSocket.soTimeout = 1_000
        val presence = endpoints.map { networkEndpoint ->
            launch(Dispatchers.IO) {
                broadcastPresence(networkEndpoint, localIdentityKey, localName, serverSocket.localPort, cancellation)
            }
        }
        try {
            while (!cancellation.isCancelled) {
                ensureActive()
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: SocketException) {
                    if (cancellation.isCancelled) break
                    throw error
                }
                cancellation.track(socket)
                launch(Dispatchers.IO) {
                    try {
                        require(StreamCenterLocalNetworkPolicy.routeTo(socket.inetAddress) != null) {
                            "Dispositivo fuori dalla rete locale privata."
                        }
                        handleAutoServer(socket, localIdentity, localIdentityKey, isTrusted, listener, onSession)
                    } catch (error: Exception) {
                        if (!cancellation.isCancelled) {
                            listener.onEvent(
                                StreamCenterLocalSyncEvent(
                                    message = "Sessione automatica rifiutata",
                                    detail = error.message?.take(160) ?: error.javaClass.simpleName,
                                ),
                            )
                        }
                    } finally {
                        cancellation.close(socket)
                    }
                }
            }
        } finally {
            presence.forEach { job -> job.cancelAndJoin() }
            cancellation.close(serverSocket)
        }
    }

    suspend fun scanTrustedPeers(
        isTrusted: (identityKey: String) -> Boolean,
        localIdentityKey: String,
        timeoutMs: Long,
        cancellation: StreamCenterLocalSyncCancellation,
    ): List<StreamCenterLocalSyncPresence> = withContext(Dispatchers.IO) {
        val socket = cancellation.track(DatagramSocket(null))
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(AUTO_PRESENCE_PORT))
        socket.soTimeout = 500
        val found = linkedMapOf<String, StreamCenterLocalSyncPresence>()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (!cancellation.isCancelled && System.currentTimeMillis() < deadline) {
                ensureActive()
                val buffer = ByteArray(MAX_DISCOVERY_BYTES)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: SocketException) {
                    if (cancellation.isCancelled) break
                    throw error
                }
                val remote = packet.address as? Inet4Address ?: continue
                if (!StreamCenterLocalNetworkPolicy.isPrivate(remote)) continue
                val presence = parsePresence(
                    String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8),
                    remote,
                ) ?: continue
                if (presence.identityKey == localIdentityKey) continue
                if (!isTrusted(presence.identityKey)) continue
                found[presence.identityKey] = presence
            }
        } finally {
            cancellation.close(socket)
        }
        found.values.toList()
    }

    suspend fun connectAuto(
        peer: StreamCenterLocalSyncPresence,
        localIdentity: KeyPair,
        localIdentityKey: String,
        localName: String,
        cancellation: StreamCenterLocalSyncCancellation,
        listener: StreamCenterLocalSyncListener,
        onSession: suspend (
            peerName: String,
            peerIdentityKey: String,
            isInitiator: Boolean,
            keys: StreamCenterLocalSyncSessionKeys,
            input: DataInputStream,
            output: DataOutputStream,
        ) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val endpoint = StreamCenterLocalNetworkPolicy.routeTo(peer.address)
            ?: throw SecurityException("Il dispositivo non appartiene alla rete locale privata.")
        val socket = cancellation.track(Socket())
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(endpoint.address, 0))
            socket.connect(InetSocketAddress(peer.address, peer.tcpPort), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val sessionId = StreamCenterLocalSyncCrypto.sessionId()
            val keys = StreamCenterLocalSyncCrypto.deriveKeysFromIdentities(localIdentity, peer.identityKey, sessionId)
            val transcript = autoTranscript(sessionId, localIdentityKey, peer.identityKey)
            val clientProof = StreamCenterLocalSyncCrypto.authenticationProof(
                keys.clientAuthenticationKey,
                "client|$transcript",
            )
            val hello = JSONObject()
                .put("sessionId", sessionId)
                .put("identityKey", localIdentityKey)
                .put("name", localName)
                .put("proof", StreamCenterLocalSyncCrypto.encodeBase64(clientProof))
            writeFrame(output, hello.toString().toByteArray(StandardCharsets.UTF_8))
            output.flush()
            check(input.readBoolean()) { "Il dispositivo remoto non ha riconosciuto l'identità." }
            val serverProof = readFrame(input, MAX_CONTROL_BYTES)
            val expectedServerProof = StreamCenterLocalSyncCrypto.authenticationProof(
                keys.serverAuthenticationKey,
                "server|$transcript",
            )
            check(StreamCenterLocalSyncCrypto.proofsMatch(expectedServerProof, serverProof)) {
                "Identità del dispositivo remoto non valida."
            }
            listener.onEvent(StreamCenterLocalSyncEvent("Sessione automatica avviata", peer.name))
            onSession(peer.name, peer.identityKey, true, keys, input, output)
        } finally {
            cancellation.close(socket)
        }
    }

    private suspend fun handleAutoServer(
        socket: Socket,
        localIdentity: KeyPair,
        localIdentityKey: String,
        isTrusted: (identityKey: String) -> Boolean,
        listener: StreamCenterLocalSyncListener,
        onSession: suspend (
            peerName: String,
            peerIdentityKey: String,
            isInitiator: Boolean,
            keys: StreamCenterLocalSyncSessionKeys,
            input: DataInputStream,
            output: DataOutputStream,
        ) -> Unit,
    ) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        val hello = JSONObject(String(readFrame(input, MAX_CONTROL_BYTES), StandardCharsets.UTF_8))
        val sessionId = hello.optString("sessionId")
        require(sessionId.matches(Regex("[a-f0-9]{32}"))) { "Sessione automatica non valida." }
        val peerIdentityKey = hello.getString("identityKey")
        require(isTrusted(peerIdentityKey)) { "Dispositivo non fidato." }
        val peerName = hello.optString("name", "Dispositivo").take(80)
        val keys = StreamCenterLocalSyncCrypto.deriveKeysFromIdentities(localIdentity, peerIdentityKey, sessionId)
        val transcript = autoTranscript(sessionId, peerIdentityKey, localIdentityKey)
        val expectedClientProof = StreamCenterLocalSyncCrypto.authenticationProof(
            keys.clientAuthenticationKey,
            "client|$transcript",
        )
        val actualClientProof = StreamCenterLocalSyncCrypto.decodeBase64(hello.getString("proof"))
        val authenticated = StreamCenterLocalSyncCrypto.proofsMatch(expectedClientProof, actualClientProof)
        output.writeBoolean(authenticated)
        if (!authenticated) {
            output.flush()
            return
        }
        val serverProof = StreamCenterLocalSyncCrypto.authenticationProof(
            keys.serverAuthenticationKey,
            "server|$transcript",
        )
        writeFrame(output, serverProof)
        output.flush()
        listener.onEvent(StreamCenterLocalSyncEvent("Sessione automatica accettata", peerName))
        onSession(peerName, peerIdentityKey, false, keys, input, output)
    }

    suspend fun runMergeExchange(
        isInitiator: Boolean,
        source: StreamCenterLocalSyncMergeSource,
        allowSend: Boolean,
        allowReceive: Boolean,
        keys: StreamCenterLocalSyncSessionKeys,
        input: DataInputStream,
        output: DataOutputStream,
        listener: StreamCenterLocalSyncListener,
    ): StreamCenterLocalSyncMergeResult {
        val localManifest = source.manifest()
        val peerManifest: JSONObject
        if (isInitiator) {
            writeSecureJson(output, keys, localManifest, "manifest")
            peerManifest = readSecureJson(input, keys, "manifest")
        } else {
            peerManifest = readSecureJson(input, keys, "manifest")
            writeSecureJson(output, keys, localManifest, "manifest")
        }
        val wantedKeys = if (allowReceive) keysWherePeerNewer(localManifest, peerManifest) else emptyList()
        val wanted = JSONObject().put("keys", JSONArray(wantedKeys))
        val peerWanted: JSONObject
        if (isInitiator) {
            writeSecureJson(output, keys, wanted, "wanted")
            peerWanted = readSecureJson(input, keys, "wanted")
        } else {
            peerWanted = readSecureJson(input, keys, "wanted")
            writeSecureJson(output, keys, wanted, "wanted")
        }
        val valuesForPeer = if (allowSend) {
            source.collectValues(stringList(peerWanted.optJSONArray("keys")))
        } else {
            JSONObject()
        }
        var receivedKeys = emptyList<String>()
        if (isInitiator) {
            writeSecureJson(output, keys, valuesForPeer, "values")
            val received = readSecureJson(input, keys, "values")
            if (allowReceive) receivedKeys = source.applyRemote(received)
        } else {
            val received = readSecureJson(input, keys, "values")
            if (allowReceive) receivedKeys = source.applyRemote(received)
            writeSecureJson(output, keys, valuesForPeer, "values")
        }
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Merge automatico completato",
                detail = "${valuesForPeer.length()} voci inviate · ${receivedKeys.size} ricevute",
                progress = 100,
            ),
        )
        return StreamCenterLocalSyncMergeResult(
            sent = valuesForPeer.length(),
            received = receivedKeys.size,
            sentKeys = objectKeys(valuesForPeer),
            receivedKeys = receivedKeys,
        )
    }

    private fun objectKeys(value: JSONObject): List<String> {
        val keys = value.keys()
        return buildList {
            while (keys.hasNext()) add(keys.next())
        }
    }

    private fun keysWherePeerNewer(local: JSONObject, peer: JSONObject): List<String> {
        val result = mutableListOf<String>()
        val keys = peer.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val peerTs = peer.optJSONObject(key)?.optLong("t", 0L) ?: 0L
            val localTs = local.optJSONObject(key)?.optLong("t", 0L) ?: 0L
            if (peerTs > localTs) result.add(key)
        }
        return result
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun writeSecureJson(
        output: DataOutputStream,
        keys: StreamCenterLocalSyncSessionKeys,
        json: JSONObject,
        step: String,
    ) {
        val encrypted = StreamCenterLocalSyncCrypto.encrypt(
            key = keys.payloadEncryptionKey,
            plaintext = json.toString().toByteArray(StandardCharsets.UTF_8),
            associatedData = "$MAGIC|$PROTOCOL_VERSION|merge|$step",
        )
        writeEncrypted(output, encrypted, null, step)
        output.flush()
    }

    private fun readSecureJson(
        input: DataInputStream,
        keys: StreamCenterLocalSyncSessionKeys,
        step: String,
    ): JSONObject {
        val encrypted = readEncrypted(input, MAX_TRANSFER_BYTES + 32, null, NoopListener, step)
        val plaintext = StreamCenterLocalSyncCrypto.decrypt(
            key = keys.payloadEncryptionKey,
            encrypted = encrypted,
            associatedData = "$MAGIC|$PROTOCOL_VERSION|merge|$step",
        )
        return JSONObject(String(plaintext, StandardCharsets.UTF_8))
    }

    private val NoopListener = object : StreamCenterLocalSyncListener {
        override fun onStateChanged(state: StreamCenterLocalSyncState, message: String) = Unit
        override fun onEvent(event: StreamCenterLocalSyncEvent) = Unit
        override fun onOfferFound(offer: StreamCenterLocalSyncOffer) = Unit
        override fun onPairingCodeReady(code: String) = Unit
        override fun onCompleted(result: StreamCenterLocalSyncResult) = Unit
        override fun onError(message: String, error: Throwable?) = Unit
    }

    private suspend fun broadcastPresence(
        endpoint: StreamCenterLocalNetworkEndpoint,
        localIdentityKey: String,
        localName: String,
        tcpPort: Int,
        cancellation: StreamCenterLocalSyncCancellation,
    ) {
        val socket = cancellation.track(DatagramSocket(InetSocketAddress(endpoint.address, 0)))
        socket.broadcast = true
        val bytes = encodePresence(localIdentityKey, localName, tcpPort).toByteArray(StandardCharsets.UTF_8)
        try {
            while (!cancellation.isCancelled) {
                socket.send(DatagramPacket(bytes, bytes.size, endpoint.broadcastAddress, AUTO_PRESENCE_PORT))
                delay(AUTO_PRESENCE_INTERVAL_MS)
            }
        } finally {
            cancellation.close(socket)
        }
    }

    private fun encodePresence(identityKey: String, name: String, tcpPort: Int): String =
        JSONObject()
            .put("magic", MAGIC)
            .put("version", PROTOCOL_VERSION)
            .put("kind", "presence")
            .put("identityKey", identityKey)
            .put("name", name)
            .put("tcpPort", tcpPort)
            .toString()

    private fun parsePresence(raw: String, address: Inet4Address): StreamCenterLocalSyncPresence? = runCatching {
        val root = JSONObject(raw)
        if (root.optString("magic") != MAGIC || root.optInt("version", -1) != PROTOCOL_VERSION) return@runCatching null
        if (root.optString("kind") != "presence") return@runCatching null
        val identityKey = root.getString("identityKey")
        require(StreamCenterLocalSyncCrypto.decodeBase64(identityKey).size in 64..512)
        val port = root.getInt("tcpPort")
        require(port in 1..65_535)
        StreamCenterLocalSyncPresence(
            identityKey = identityKey,
            name = root.optString("name", "Dispositivo").take(80),
            address = address,
            tcpPort = port,
        )
    }.getOrNull()

    private fun autoTranscript(
        sessionId: String,
        clientIdentityKey: String,
        serverIdentityKey: String,
    ): String = "$MAGIC|$PROTOCOL_VERSION|auto|$sessionId|$clientIdentityKey|$serverIdentityKey"

    private suspend fun broadcastOffer(
        endpoint: StreamCenterLocalNetworkEndpoint,
        offer: StreamCenterLocalSyncOffer,
        cancellation: StreamCenterLocalSyncCancellation,
        listener: StreamCenterLocalSyncListener,
    ) {
        val socket = cancellation.track(DatagramSocket(InetSocketAddress(endpoint.address, 0)))
        socket.broadcast = true
        val bytes = encodeOffer(offer).toByteArray(StandardCharsets.UTF_8)
        var announcement = 0
        try {
            while (!cancellation.isCancelled) {
                announcement += 1
                socket.send(
                    DatagramPacket(
                        bytes,
                        bytes.size,
                        endpoint.broadcastAddress,
                        DISCOVERY_PORT,
                    ),
                )
                listener.onEvent(
                    StreamCenterLocalSyncEvent(
                        message = "Annuncio locale inviato #$announcement",
                        detail = "UDP ${endpoint.broadcastAddress.hostAddress}:$DISCOVERY_PORT",
                    ),
                )
                delay(DISCOVERY_INTERVAL_MS)
            }
        } finally {
            cancellation.close(socket)
        }
    }

    private fun handleSenderConnection(
        socket: Socket,
        offer: StreamCenterLocalSyncOffer,
        payload: StreamCenterLocalSyncPayload,
        serverKeyPair: KeyPair,
        serverPublicKey: String,
        serverIdentityKey: String,
        pairingCode: String,
        listener: StreamCenterLocalSyncListener,
        rememberPeer: (name: String, identityPublicKey: String) -> Unit,
    ): StreamCenterLocalSyncResult? {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        val hello = JSONObject(String(readFrame(input, MAX_CONTROL_BYTES), StandardCharsets.UTF_8))
        require(hello.optString("sessionId") == offer.sessionId) { "Sessione ricevente non valida." }
        val receiverName = hello.optString("receiverName", "Dispositivo ricevente").take(80)
        val clientPublicKey = hello.getString("publicKey")
        val clientIdentityKey = hello.optString("identityKey").takeIf(String::isNotBlank)
        val actualClientProof = StreamCenterLocalSyncCrypto.decodeBase64(hello.getString("proof"))
        val keys = StreamCenterLocalSyncCrypto.deriveKeys(
            keyPair = serverKeyPair,
            peerPublicKey = clientPublicKey,
            pairingCode = pairingCode,
            sessionId = offer.sessionId,
        )
        val transcript = authenticationTranscript(
            offer.sessionId,
            clientPublicKey,
            serverPublicKey,
            clientIdentityKey.orEmpty(),
            serverIdentityKey,
        )
        val expectedClientProof = StreamCenterLocalSyncCrypto.authenticationProof(
            keys.clientAuthenticationKey,
            "client|$transcript",
        )
        val authenticated = StreamCenterLocalSyncCrypto.proofsMatch(expectedClientProof, actualClientProof)
        output.writeBoolean(authenticated)
        if (!authenticated) {
            output.flush()
            return null
        }
        val serverProof = StreamCenterLocalSyncCrypto.authenticationProof(
            keys.serverAuthenticationKey,
            "server|$transcript",
        )
        writeFrame(output, serverProof)
        output.flush()

        clientIdentityKey?.let { identity -> rememberPeer(receiverName, identity) }
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Ricevitore autenticato",
                detail = "$receiverName · ECDH P-256 · AES-256-GCM",
            ),
        )
        val encryptedPayload = StreamCenterLocalSyncCrypto.encrypt(
            key = keys.payloadEncryptionKey,
            plaintext = payload.compressedBytes,
            associatedData = payloadAssociatedData(offer),
        )
        listener.onStateChanged(StreamCenterLocalSyncState.TRANSFERRING, "Invio cifrato in corso")
        writeEncrypted(output, encryptedPayload, listener, "Invio payload")
        output.flush()
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Payload inviato",
                detail = "${payload.compressedBytes.size} byte · autenticazione GCM inclusa",
                progress = 100,
            ),
        )
        val encryptedAcknowledgement = readEncrypted(
            input = input,
            maximumCiphertextBytes = MAX_CONTROL_BYTES,
            expectedBytes = null,
            listener = listener,
            progressMessage = "Ricezione conferma",
        )
        val acknowledgement = JSONObject(
            String(
                StreamCenterLocalSyncCrypto.decrypt(
                    key = keys.acknowledgementEncryptionKey,
                    encrypted = encryptedAcknowledgement,
                    associatedData = acknowledgementAssociatedData(offer),
                ),
                StandardCharsets.UTF_8,
            ),
        )
        check(acknowledgement.optString("status") == "applied") {
            "Il dispositivo ricevente non ha applicato la configurazione."
        }
        listener.onEvent(
            StreamCenterLocalSyncEvent(
                message = "Applicazione confermata dal ricevitore",
                detail = receiverName,
                progress = 100,
            ),
        )
        return StreamCenterLocalSyncResult(
            type = payload.type,
            sent = true,
            entryCount = payload.entryCount,
            libraryItemCount = payload.libraryItemCount,
            progressCount = payload.progressCount,
            peerName = receiverName,
            restartRequired = false,
        )
    }

    private fun encodeOffer(offer: StreamCenterLocalSyncOffer): String {
        return JSONObject()
            .put("magic", MAGIC)
            .put("version", PROTOCOL_VERSION)
            .put("sessionId", offer.sessionId)
            .put("type", offer.type.wireValue)
            .put("senderName", offer.senderName)
            .put("publicKey", offer.senderPublicKey)
            .put("identityKey", offer.senderIdentityKey)
            .put("tcpPort", offer.tcpPort)
            .put("compressedSize", offer.compressedSize)
            .put("uncompressedSize", offer.uncompressedSize)
            .put("entries", offer.entryCount)
            .put("libraryItems", offer.libraryItemCount)
            .put("progressEntries", offer.progressCount)
            .toString()
    }

    private fun parseOffer(raw: String, senderAddress: InetAddress): StreamCenterLocalSyncOffer? {
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("magic") != MAGIC || root.optInt("version", -1) != PROTOCOL_VERSION) return null
            val type = StreamCenterLocalSyncPayloadType.fromWireValue(root.optString("type")) ?: return null
            val compressedSize = root.getInt("compressedSize")
            val uncompressedSize = root.getInt("uncompressedSize")
            require(compressedSize in 1..MAX_TRANSFER_BYTES)
            require(uncompressedSize in 1..32 * 1024 * 1024)
            val sessionId = root.getString("sessionId")
            require(sessionId.matches(Regex("[a-f0-9]{32}")))
            val port = root.getInt("tcpPort")
            require(port in 1..65_535)
            val publicKey = root.getString("publicKey")
            require(StreamCenterLocalSyncCrypto.decodeBase64(publicKey).size in 64..512)
            val identityKey = root.getString("identityKey")
            require(StreamCenterLocalSyncCrypto.decodeBase64(identityKey).size in 64..512)
            StreamCenterLocalSyncOffer(
                sessionId = sessionId,
                type = type,
                senderName = root.optString("senderName", "Dispositivo mittente").take(80),
                senderAddress = senderAddress.hostAddress.orEmpty(),
                senderPublicKey = publicKey,
                senderIdentityKey = identityKey,
                tcpPort = port,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                entryCount = root.optInt("entries", 0).coerceAtLeast(0),
                libraryItemCount = root.optInt("libraryItems", 0).coerceAtLeast(0),
                progressCount = root.optInt("progressEntries", 0).coerceAtLeast(0),
            )
        }.getOrNull()
    }

    private fun parsePrivateIpv4(value: String): Inet4Address? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || part.any { char -> !char.isDigit() }) return null
            val number = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index] = number.toByte()
        }
        val address = InetAddress.getByAddress(bytes) as? Inet4Address ?: return null
        return address.takeIf(StreamCenterLocalNetworkPolicy::isPrivate)
    }

    private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_CONTROL_BYTES) { "Messaggio di controllo troppo grande." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readFrame(input: DataInputStream, maximumBytes: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..maximumBytes) { "Dimensione del messaggio di controllo non valida." }
        return ByteArray(size).also(input::readFully)
    }

    private fun writeEncrypted(
        output: DataOutputStream,
        encrypted: StreamCenterLocalSyncEncryptedData,
        listener: StreamCenterLocalSyncListener?,
        progressMessage: String,
    ) {
        output.writeInt(encrypted.initializationVector.size)
        output.write(encrypted.initializationVector)
        output.writeInt(encrypted.ciphertext.size)
        var offset = 0
        var lastProgress = -1
        while (offset < encrypted.ciphertext.size) {
            val count = minOf(TRANSFER_CHUNK_BYTES, encrypted.ciphertext.size - offset)
            output.write(encrypted.ciphertext, offset, count)
            offset += count
            val progress = offset * 100 / encrypted.ciphertext.size
            if (listener != null && progress != lastProgress) {
                lastProgress = progress
                listener.onEvent(
                    StreamCenterLocalSyncEvent(
                        message = progressMessage,
                        detail = "$offset / ${encrypted.ciphertext.size} byte",
                        progress = progress,
                    ),
                )
            }
        }
    }

    private fun readEncrypted(
        input: DataInputStream,
        maximumCiphertextBytes: Int,
        expectedBytes: Int?,
        listener: StreamCenterLocalSyncListener,
        progressMessage: String,
    ): StreamCenterLocalSyncEncryptedData {
        val initializationVectorSize = input.readInt()
        require(initializationVectorSize == 12) { "Vettore di inizializzazione non valido." }
        val initializationVector = ByteArray(initializationVectorSize).also(input::readFully)
        val ciphertextSize = input.readInt()
        require(ciphertextSize in 17..maximumCiphertextBytes) { "Dimensione del contenuto cifrato non valida." }
        expectedBytes?.let { expected -> require(ciphertextSize == expected) { "Dimensione cifrata inattesa." } }
        val ciphertext = ByteArray(ciphertextSize)
        var offset = 0
        var lastProgress = -1
        while (offset < ciphertextSize) {
            val count = input.read(ciphertext, offset, minOf(TRANSFER_CHUNK_BYTES, ciphertextSize - offset))
            check(count > 0) { "Trasferimento interrotto prima del completamento." }
            offset += count
            val progress = offset * 100 / ciphertextSize
            if (progress != lastProgress) {
                lastProgress = progress
                listener.onEvent(
                    StreamCenterLocalSyncEvent(
                        message = progressMessage,
                        detail = "$offset / $ciphertextSize byte",
                        progress = progress,
                    ),
                )
            }
        }
        return StreamCenterLocalSyncEncryptedData(initializationVector, ciphertext)
    }

    private fun authenticationTranscript(
        sessionId: String,
        clientPublicKey: String,
        serverPublicKey: String,
        clientIdentityKey: String,
        serverIdentityKey: String,
    ): String = "$MAGIC|$PROTOCOL_VERSION|$sessionId|$clientPublicKey|$serverPublicKey|" +
        "$clientIdentityKey|$serverIdentityKey"

    private fun payloadAssociatedData(offer: StreamCenterLocalSyncOffer): String =
        "$MAGIC|$PROTOCOL_VERSION|${offer.sessionId}|${offer.type.wireValue}|payload"

    private fun acknowledgementAssociatedData(offer: StreamCenterLocalSyncOffer): String =
        "$MAGIC|$PROTOCOL_VERSION|${offer.sessionId}|${offer.type.wireValue}|acknowledgement"
}
