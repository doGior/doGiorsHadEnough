package it.dogior.hadEnough.cache

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

internal class PersistentCalendarCache(private val directory: File) {
    private data class Entry(val key: String, val expiresAt: Long, val document: String)
    private val file = File(directory, "calendar.cache")
    private var generation = 0L

    @Synchronized fun generation(): Long = generation

    @Synchronized fun read(key: String, expectedExpiry: Long): String? {
        val entry = readEntry() ?: return null
        if (entry.expiresAt <= System.currentTimeMillis() || entry.expiresAt != expectedExpiry) {
            deleteFiles()
            return null
        }
        return entry.document.takeIf { entry.key == key }
    }

    @Synchronized fun write(key: String, document: String, expiresAt: Long, expectedGeneration: Long) {
        if (generation != expectedGeneration || expiresAt <= System.currentTimeMillis()) return
        check(directory.isDirectory || directory.mkdirs()) { "Impossibile creare la cache calendario" }
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        AtomicTextFile.write(file, "streamcenter-calendar-v1\n$expiresAt\n$encodedKey\n$document")
    }

    @Synchronized fun purgeExpired() {
        val entry = readEntry() ?: return
        if (entry.expiresAt <= System.currentTimeMillis()) deleteFiles()
    }

    @Synchronized fun clear() {
        generation++
        deleteFiles()
    }

    private fun deleteFiles() {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
    }

    private fun readEntry(): Entry? = try {
        AtomicTextFile.recover(file)
        if (!file.isFile) null else {
            val parts = AtomicTextFile.read(file).split('\n', limit = 4)
            check(parts.size == 4 && parts[0] == "streamcenter-calendar-v1")
            Entry(
                URLDecoder.decode(parts[2], "UTF-8"),
                parts[1].toLong(),
                parts[3],
            )
        }
    } catch (_: Exception) {
        deleteFiles()
        null
    }
}
