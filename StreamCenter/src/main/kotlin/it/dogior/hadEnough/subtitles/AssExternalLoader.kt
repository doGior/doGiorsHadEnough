package it.dogior.hadEnough.subtitles

import android.content.Context
import android.net.Uri
import com.lagradost.cloudstream3.app
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object AssExternalLoader {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val SNIFF_CHARS = 4096
    private val ASS_MARKERS = listOf("[script info]", "[v4+ styles]", "[v4 styles]")

    suspend fun load(context: Context, url: String, headers: Map<String, String>): ByteArray? {
        val raw = read(context, url, headers) ?: return null
        if (raw.isEmpty() || raw.size > MAX_BYTES) return null
        val text = decode(raw)
        val head = text.take(SNIFF_CHARS).lowercase()
        if (ASS_MARKERS.none { it in head }) return null
        return text.toByteArray(Charsets.UTF_8)
    }

    private suspend fun read(context: Context, url: String, headers: Map<String, String>): ByteArray? {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> app.get(url, headers = headers).body.bytes()
            "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            "file" -> uri.path?.let(::File)?.takeIf { it.isFile }?.readBytes()
            null -> File(url).takeIf { it.isFile }?.readBytes()
            else -> null
        }
    }

    private fun decode(raw: ByteArray): String {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return String(raw, 3, raw.size - 3, Charsets.UTF_8)
        }
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
        }
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        }
        return strictUtf8(raw) ?: String(raw, Charset.forName("windows-1252"))
    }

    private fun strictUtf8(raw: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
