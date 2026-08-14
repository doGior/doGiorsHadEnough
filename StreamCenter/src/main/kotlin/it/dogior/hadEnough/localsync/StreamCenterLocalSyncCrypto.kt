package it.dogior.hadEnough.localsync

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class StreamCenterLocalSyncSessionKeys(
    val clientAuthenticationKey: ByteArray,
    val serverAuthenticationKey: ByteArray,
    val payloadEncryptionKey: ByteArray,
    val acknowledgementEncryptionKey: ByteArray,
)

internal data class StreamCenterLocalSyncEncryptedData(
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

internal object StreamCenterLocalSyncCrypto {
    private val secureRandom = SecureRandom()

    fun generateKeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        }.generateKeyPair()
    }

    fun pairingCode(): String = secureRandom.nextInt(1_000_000).toString().padStart(6, '0')

    fun sessionId(): String = ByteArray(16).also(secureRandom::nextBytes).toHex()

    fun publicKey(value: String) = KeyFactory.getInstance("EC").generatePublic(
        X509EncodedKeySpec(decodeBase64(value)),
    )

    fun encodePublicKey(keyPair: KeyPair): String = encodeBase64(keyPair.public.encoded)

    fun encodePrivateKey(keyPair: KeyPair): String = encodeBase64(keyPair.private.encoded)

    fun keyPairFromEncoded(publicKeyBase64: String, privateKeyBase64: String): KeyPair {
        val factory = KeyFactory.getInstance("EC")
        val public = factory.generatePublic(X509EncodedKeySpec(decodeBase64(publicKeyBase64)))
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(decodeBase64(privateKeyBase64)))
        return KeyPair(public, private)
    }

    fun fingerprint(publicKeyBase64: String): String = sha256(decodeBase64(publicKeyBase64)).copyOf(8).toHex()

    fun deriveKeysFromIdentities(
        keyPair: KeyPair,
        peerPublicKey: String,
        sessionId: String,
    ): StreamCenterLocalSyncSessionKeys {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(publicKey(peerPublicKey), true)
        val sharedSecret = agreement.generateSecret()
        val salt = sha256("StreamCenterLocalSync-Identity|$sessionId".toByteArray(StandardCharsets.UTF_8))
        val pseudoRandomKey = hmacSha256(salt, sharedSecret)
        val keys = StreamCenterLocalSyncSessionKeys(
            clientAuthenticationKey = expand(pseudoRandomKey, "client-auth", 32),
            serverAuthenticationKey = expand(pseudoRandomKey, "server-auth", 32),
            payloadEncryptionKey = expand(pseudoRandomKey, "payload", 32),
            acknowledgementEncryptionKey = expand(pseudoRandomKey, "acknowledgement", 32),
        )
        sharedSecret.fill(0)
        pseudoRandomKey.fill(0)
        return keys
    }

    fun deriveKeys(
        keyPair: KeyPair,
        peerPublicKey: String,
        pairingCode: String,
        sessionId: String,
    ): StreamCenterLocalSyncSessionKeys {
        require(pairingCode.matches(Regex("\\d{6}"))) { "Codice di associazione non valido." }
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(publicKey(peerPublicKey), true)
        val sharedSecret = agreement.generateSecret()
        val salt = sha256("StreamCenterLocalSync|$sessionId|$pairingCode".toByteArray(StandardCharsets.UTF_8))
        val pseudoRandomKey = hmacSha256(salt, sharedSecret)
        val keys = StreamCenterLocalSyncSessionKeys(
            clientAuthenticationKey = expand(pseudoRandomKey, "client-auth", 32),
            serverAuthenticationKey = expand(pseudoRandomKey, "server-auth", 32),
            payloadEncryptionKey = expand(pseudoRandomKey, "payload", 32),
            acknowledgementEncryptionKey = expand(pseudoRandomKey, "acknowledgement", 32),
        )
        sharedSecret.fill(0)
        pseudoRandomKey.fill(0)
        return keys
    }

    fun authenticationProof(key: ByteArray, transcript: String): ByteArray =
        hmacSha256(key, transcript.toByteArray(StandardCharsets.UTF_8))

    fun proofsMatch(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: String): StreamCenterLocalSyncEncryptedData {
        val initializationVector = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, initializationVector),
        )
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return StreamCenterLocalSyncEncryptedData(
            initializationVector = initializationVector,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    fun decrypt(
        key: ByteArray,
        encrypted: StreamCenterLocalSyncEncryptedData,
        associatedData: String,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, encrypted.initializationVector),
        )
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(encrypted.ciphertext)
    }

    fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private fun expand(pseudoRandomKey: ByteArray, label: String, length: Int): ByteArray {
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val input = previous + label.toByteArray(StandardCharsets.UTF_8) + counter.toByte()
            previous = hmacSha256(pseudoRandomKey, input)
            val copyLength = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, copyLength)
            offset += copyLength
            counter += 1
        }
        return output
    }

    private fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray {
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun ByteArray.toHex(): String = joinToString("") { value -> "%02x".format(value) }
}
