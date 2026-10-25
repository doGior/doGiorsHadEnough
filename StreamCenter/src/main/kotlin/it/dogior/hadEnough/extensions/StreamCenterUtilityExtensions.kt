package it.dogior.hadEnough.extensions

import android.app.Activity
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI

internal data class StreamCenterUtilityExtension(
    val name: String,
    val summary: String,
    val icon: String,
    val author: String = "DieGon",
) {
    val key: String get() = "utility-$name"

    internal fun isDownloadUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("raw.githubusercontent.com", ignoreCase = true) &&
            uri.userInfo == null && uri.port == -1 &&
            uri.path.startsWith("/DieGon7771/ItaliaInStreaming/") && uri.path.endsWith("/$name.cs3")
    }.getOrDefault(false)

    internal fun matches(info: InstalledExtensionInfo): Boolean {
        val localName = info.name.replace(Regex("\\.(cs3|zip)$", RegexOption.IGNORE_CASE), "")
        return localName.equals(name, ignoreCase = true) &&
            (info.downloadUrl.isNullOrBlank() || isDownloadUrl(info.downloadUrl))
    }
}

internal object StreamCenterUtilityExtensions {
    const val REPOSITORY_URL =
        "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/builds/repo.json"
    val extensions = listOf(
        StreamCenterUtilityExtension("SleepGuard", "Mette in pausa dopo un periodo di inattività · Beta", "🌙"),
        StreamCenterUtilityExtension("WatchParty", "Visione sincronizzata con gli amici e chat · Beta", "🎉"),
        StreamCenterUtilityExtension("Wrapped", "Statistiche di visione e riepilogo condivisibile", "📊"),
    )
    private val installLocks = extensions.associate { it.key to Mutex() }

    fun isInstalling(extension: StreamCenterUtilityExtension): Boolean = installLocks[extension.key]?.isLocked == true

    internal fun findInstalled(
        extension: StreamCenterUtilityExtension,
        plugins: List<InstalledExtensionInfo>,
    ): InstalledExtensionInfo? = plugins.firstOrNull(extension::matches)

    fun installed(extension: StreamCenterUtilityExtension): InstalledExtensionInfo? = findInstalled(
        extension,
        (PluginManager.getPluginsOnline().toList() + PluginManager.getPluginsLocal().toList())
            .filter { File(it.filePath).isFile }
            .map { InstalledExtensionInfo(it.filePath, it.internalName, it.url) },
    )

    fun loaded(info: InstalledExtensionInfo): Plugin? = synchronized(PluginManager.plugins) {
        PluginManager.plugins[info.filePath] as? Plugin
    }

    internal fun selectPlugin(extension: StreamCenterUtilityExtension, plugins: List<SitePlugin>): SitePlugin {
        val plugin = plugins.singleOrNull { it.internalName == extension.name && extension.isDownloadUrl(it.url) }
            ?: error("${extension.name} non è disponibile nel repository ItaliaInStreaming.")
        check(plugin.status in 1..3) { "${extension.name} è temporaneamente disabilitato dal repository." }
        check(plugin.apiVersion == 1) { "Questa versione di ${extension.name} richiede un aggiornamento di CloudStream." }
        return plugin
    }

    internal suspend fun <T> withDownloadLock(extension: StreamCenterUtilityExtension, block: suspend () -> T): T {
        val lock = installLocks.getValue(extension.key)
        check(lock.tryLock()) { "Download di ${extension.name} già in corso." }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    suspend fun install(extension: StreamCenterUtilityExtension, activity: Activity, preferences: SharedPreferences?) = withDownloadLock(extension) {
        try {
            if (installed(extension) != null) return@withDownloadLock
            val completed = withTimeoutOrNull(120_000L) {
                StreamCenterVpnGuard.requireInternetAccess(preferences)
                val repository = RepositoryData(name = "DieGon Repository", url = REPOSITORY_URL)
                val plugins = RepositoryManager.getRepoPlugins(repository)
                    ?: error("Impossibile leggere il repository ItaliaInStreaming. Riprova più tardi.")
                val plugin = selectPlugin(extension, plugins.map { it.plugin })
                RepositoryManager.addRepository(repository)
                StreamCenterVpnGuard.requireInternetAccess(preferences)
                check(PluginManager.downloadPlugin(
                    activity = activity,
                    pluginUrl = plugin.url,
                    pluginHash = plugin.fileHash,
                    internalName = plugin.internalName,
                    repositoryUrl = REPOSITORY_URL,
                    loadPlugin = true,
                )) { "Download o caricamento di ${extension.name} non riuscito. Riprova più tardi." }
                check(installed(extension) != null) { "${extension.name} scaricato. Riavvia CloudStream per caricarlo." }
                true
            }
            check(completed == true) { "Tempo di attesa per ${extension.name} scaduto. Controlla la connessione e riprova." }
        } catch (error: LinkageError) {
            throw IllegalStateException("Aggiorna CloudStream per installare ${extension.name} da StreamCenter.", error)
        }
    }
}
