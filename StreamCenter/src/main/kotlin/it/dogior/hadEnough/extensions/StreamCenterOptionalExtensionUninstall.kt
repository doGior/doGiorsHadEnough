package it.dogior.hadEnough.extensions

import android.os.Environment
import com.lagradost.cloudstream3.plugins.PluginManager
import java.io.File

internal interface StreamCenterOptionalUninstallBackend {
    fun installed(): List<InstalledExtensionInfo>
    suspend fun remove(info: InstalledExtensionInfo): Boolean
}

internal object StreamCenterOptionalExtensionUninstall {
    internal fun targets(entry: StreamCenterOptionalExtension, installed: List<InstalledExtensionInfo>): List<InstalledExtensionInfo> {
        require(entry in StreamCenterOptionalExtensions.entries) { "Estensione non supportata" }
        return installed.filter { StreamCenterOptionalExtensions.matches(entry, it) }.distinctBy { it.filePath }
    }

    internal suspend fun uninstall(entry: StreamCenterOptionalExtension, backend: StreamCenterOptionalUninstallBackend): Int {
        suspend fun remove(): Int {
            val installed = targets(entry, backend.installed())
            installed.forEach { info ->
                check(backend.remove(info)) { "Impossibile disinstallare ${entry.name}. Riprova." }
            }
            return installed.size
        }
        return if (entry.key == StreamCenterOptionalExtensions.PRISYNX) StreamCenterPriSynx.withDownloadLock { remove() }
        else StreamCenterUtilityExtensions.withDownloadLock(StreamCenterOptionalExtensions.utility(entry)
            ?: error("Estensione non supportata")) { remove() }
    }

    internal fun localSource(directory: File, info: InstalledExtensionInfo): File {
        val source = File(directory, File(info.filePath).name).canonicalFile
        check(source.parentFile == directory.canonicalFile) { "Percorso dell'estensione locale non valido" }
        return source
    }

    @Suppress("DEPRECATION")
    suspend fun uninstall(entry: StreamCenterOptionalExtension): Int = uninstall(entry, object : StreamCenterOptionalUninstallBackend {
        override fun installed() = StreamCenterOptionalExtensions.installedPlugins()

        override suspend fun remove(info: InstalledExtensionInfo): Boolean {
            if (PluginManager.getPluginsLocal().any { it.filePath == info.filePath }) {
                val directory = File(Environment.getExternalStorageDirectory(), "Cloudstream3/plugins")
                val source = localSource(directory, info)
                if (source.exists() && source != File(info.filePath).canonicalFile) {
                    check(source.isFile && runCatching { source.delete() }.getOrDefault(false)) {
                        "Impossibile rimuovere il file locale di ${entry.name}. Controlla i permessi di archiviazione di CloudStream."
                    }
                }
            }
            return PluginManager.deletePlugin(File(info.filePath))
        }
    })
}
