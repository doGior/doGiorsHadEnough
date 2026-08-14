package it.dogior.hadEnough.localsync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair

internal data class StreamCenterLocalSyncTrustedPeer(
    val id: String,
    val name: String,
    val publicKey: String,
    val pairedAtMs: Long,
    val lastSyncAtMs: Long,
)

internal object StreamCenterLocalSyncTrust {
    private const val PREFS = "streamcenter_local_sync_trust"
    private const val KEY_IDENTITY_PUBLIC = "identity_public"
    private const val KEY_IDENTITY_PRIVATE = "identity_private"
    private const val KEY_PEERS = "trusted_peers"
    private const val MAX_PEERS = 16

    @Volatile
    private var cachedIdentity: KeyPair? = null

    fun localIdentity(context: Context): KeyPair {
        cachedIdentity?.let { return it }
        return synchronized(this) {
            cachedIdentity?.let { return it }
            val preferences = prefs(context)
            val storedPublic = preferences.getString(KEY_IDENTITY_PUBLIC, null)
            val storedPrivate = preferences.getString(KEY_IDENTITY_PRIVATE, null)
            val restored = if (storedPublic != null && storedPrivate != null) {
                runCatching {
                    StreamCenterLocalSyncCrypto.keyPairFromEncoded(storedPublic, storedPrivate)
                }.getOrNull()
            } else {
                null
            }
            val identity = restored ?: StreamCenterLocalSyncCrypto.generateKeyPair().also { generated ->
                preferences.edit()
                    .putString(KEY_IDENTITY_PUBLIC, StreamCenterLocalSyncCrypto.encodePublicKey(generated))
                    .putString(KEY_IDENTITY_PRIVATE, StreamCenterLocalSyncCrypto.encodePrivateKey(generated))
                    .apply()
            }
            cachedIdentity = identity
            identity
        }
    }

    fun localPublicKey(context: Context): String =
        StreamCenterLocalSyncCrypto.encodePublicKey(localIdentity(context))

    fun localFingerprint(context: Context): String =
        StreamCenterLocalSyncCrypto.fingerprint(localPublicKey(context))

    fun trustedPeers(context: Context): List<StreamCenterLocalSyncTrustedPeer> =
        readPeers(prefs(context)).values.sortedByDescending { peer -> peer.lastSyncAtMs }

    fun isTrusted(context: Context, publicKey: String): Boolean =
        peerByPublicKey(context, publicKey) != null

    fun peerByPublicKey(context: Context, publicKey: String): StreamCenterLocalSyncTrustedPeer? {
        val id = runCatching { StreamCenterLocalSyncCrypto.fingerprint(publicKey) }.getOrNull() ?: return null
        return readPeers(prefs(context))[id]?.takeIf { peer -> peer.publicKey == publicKey }
    }

    fun rememberPeer(context: Context, name: String, publicKey: String): StreamCenterLocalSyncTrustedPeer {
        val id = StreamCenterLocalSyncCrypto.fingerprint(publicKey)
        val preferences = prefs(context)
        val peers = readPeers(preferences).toMutableMap()
        val now = System.currentTimeMillis()
        val existing = peers[id]
        val peer = StreamCenterLocalSyncTrustedPeer(
            id = id,
            name = name.trim().take(80).ifBlank { existing?.name ?: "Dispositivo" },
            publicKey = publicKey,
            pairedAtMs = existing?.pairedAtMs ?: now,
            lastSyncAtMs = existing?.lastSyncAtMs ?: 0L,
        )
        peers[id] = peer
        writePeers(preferences, capPeers(peers))
        return peer
    }

    fun markSynced(context: Context, id: String, atMs: Long = System.currentTimeMillis()) {
        val preferences = prefs(context)
        val peers = readPeers(preferences).toMutableMap()
        val existing = peers[id] ?: return
        peers[id] = existing.copy(lastSyncAtMs = atMs)
        writePeers(preferences, peers)
    }

    fun forgetPeer(context: Context, id: String) {
        val preferences = prefs(context)
        val peers = readPeers(preferences).toMutableMap()
        if (peers.remove(id) != null) writePeers(preferences, peers)
    }

    private fun capPeers(
        peers: MutableMap<String, StreamCenterLocalSyncTrustedPeer>,
    ): Map<String, StreamCenterLocalSyncTrustedPeer> {
        if (peers.size <= MAX_PEERS) return peers
        return peers.values
            .sortedByDescending { peer -> maxOf(peer.lastSyncAtMs, peer.pairedAtMs) }
            .take(MAX_PEERS)
            .associateBy { peer -> peer.id }
    }

    private fun readPeers(preferences: SharedPreferences): Map<String, StreamCenterLocalSyncTrustedPeer> {
        val raw = preferences.getString(KEY_PEERS, null) ?: return emptyMap()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val result = linkedMapOf<String, StreamCenterLocalSyncTrustedPeer>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val publicKey = entry.optString("publicKey").takeIf(String::isNotBlank) ?: continue
            val id = entry.optString("id").takeIf(String::isNotBlank)
                ?: runCatching { StreamCenterLocalSyncCrypto.fingerprint(publicKey) }.getOrNull()
                ?: continue
            result[id] = StreamCenterLocalSyncTrustedPeer(
                id = id,
                name = entry.optString("name", "Dispositivo").take(80),
                publicKey = publicKey,
                pairedAtMs = entry.optLong("pairedAt", 0L),
                lastSyncAtMs = entry.optLong("lastSync", 0L),
            )
        }
        return result
    }

    private fun writePeers(
        preferences: SharedPreferences,
        peers: Map<String, StreamCenterLocalSyncTrustedPeer>,
    ) {
        val array = JSONArray()
        peers.values.forEach { peer ->
            array.put(
                JSONObject()
                    .put("id", peer.id)
                    .put("name", peer.name)
                    .put("publicKey", peer.publicKey)
                    .put("pairedAt", peer.pairedAtMs)
                    .put("lastSync", peer.lastSyncAtMs),
            )
        }
        preferences.edit().putString(KEY_PEERS, array.toString()).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
