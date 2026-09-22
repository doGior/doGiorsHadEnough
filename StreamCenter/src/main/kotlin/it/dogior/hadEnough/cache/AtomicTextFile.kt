package it.dogior.hadEnough.cache

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object AtomicTextFile {
    fun read(file: File): String {
        recover(file)
        return file.readText(Charsets.UTF_8)
    }

    fun write(file: File, text: String) {
        recover(file)
        val pending = File(file.path + ".new")
        val backup = File(file.path + ".bak")
        try {
            FileOutputStream(pending).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) {
                throw IOException("Unable to back up ${file.name}")
            }
            if (!pending.renameTo(file)) {
                throw IOException("Unable to replace ${file.name}")
            }
            backup.delete()
        } catch (error: Exception) {
            if (!file.exists() && backup.exists() && !backup.renameTo(file)) {
                error.addSuppressed(IOException("Unable to restore ${file.name}"))
            }
            throw error
        } finally {
            pending.delete()
        }
    }

    fun recover(file: File) {
        val backup = File(file.path + ".bak")
        if (!file.exists() && backup.exists() && !backup.renameTo(file)) {
            throw IOException("Unable to recover ${file.name}")
        }
        if (file.isFile) backup.delete()
        File(file.path + ".new").delete()
    }

    fun recoverDirectory(directory: File) {
        directory.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".json.bak") || it.name.endsWith(".json.new")) }
            .map { File(directory, it.name.substringBeforeLast('.')) }
            .distinct()
            .forEach(::recover)
    }
}
