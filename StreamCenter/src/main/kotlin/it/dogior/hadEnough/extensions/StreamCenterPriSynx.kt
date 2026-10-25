package it.dogior.hadEnough.extensions

import android.app.Activity
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal object StreamCenterPriSynx {
    const val NAME = "PriSynx"
    const val AUTHOR = "PriSynx"
    const val REPOSITORY_URL =
        "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/center.json"
    private const val REPOSITORY_PATH = "/qwertyuiop8899/prisynx_cs3/"
    private val installLock = Mutex()
    val isInstalling: Boolean get() = installLock.isLocked

    internal fun isPriSynx(name: String, downloadUrl: String?): Boolean {
        if (downloadUrl.isNullOrBlank()) return name.equals(NAME, ignoreCase = true)
        return name.equals(NAME, ignoreCase = true) && isPluginUrl(downloadUrl)
    }

    private fun isPluginUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("raw.githubusercontent.com", ignoreCase = true) &&
            uri.userInfo == null && uri.port == -1 &&
            uri.path.startsWith(REPOSITORY_PATH) && uri.path.endsWith("/$NAME.cs3")
    }.getOrDefault(false)

    fun installed(forHome: Boolean = false): InstalledExtension? =
        InstalledExtensionSources.available(forHome).firstOrNull { isPriSynx(it.name, it.downloadUrl) }

    fun loaded(): Plugin? {
        val path = installed()?.sources?.firstOrNull()?.api?.sourcePlugin ?: return null
        return synchronized(PluginManager.plugins) { PluginManager.plugins[path] as? Plugin }
    }

    internal fun selectPlugin(plugins: List<SitePlugin>): SitePlugin {
        val plugin = plugins.singleOrNull { it.internalName == NAME && isPluginUrl(it.url) }
            ?: error("PriSynx non è disponibile nel repository indicato.")
        check(plugin.status == 1 || plugin.status == 2) { "PriSynx è temporaneamente disabilitato dal repository." }
        check(plugin.apiVersion == 1) { "Questa versione di PriSynx richiede un aggiornamento di CloudStream." }
        return plugin
    }

    internal suspend fun <T> withDownloadLock(block: suspend () -> T): T {
        check(installLock.tryLock()) { "Download di PriSynx già in corso." }
        try {
            return block()
        } finally {
            installLock.unlock()
        }
    }

    suspend fun install(activity: Activity, preferences: SharedPreferences?) = withDownloadLock {
        try {
            if (installed() != null) return@withDownloadLock
            val completed = withTimeoutOrNull(120_000L) {
                StreamCenterVpnGuard.requireInternetAccess(preferences)
                val repository = RepositoryData(name = "PriSynx Center", url = REPOSITORY_URL)
                val plugins = RepositoryManager.getRepoPlugins(repository)
                    ?: error("Impossibile leggere il repository PriSynx. Riprova più tardi.")
                val plugin = selectPlugin(plugins.map { it.plugin })
                RepositoryManager.addRepository(repository)
                StreamCenterVpnGuard.requireInternetAccess(preferences)
                check(PluginManager.downloadPlugin(
                    activity = activity,
                    pluginUrl = plugin.url,
                    pluginHash = plugin.fileHash,
                    internalName = plugin.internalName,
                    repositoryUrl = REPOSITORY_URL,
                    loadPlugin = true,
                )) { "Download o caricamento di PriSynx non riuscito. Riprova più tardi." }
                check(installed() != null) { "PriSynx scaricato. Riavvia CloudStream per caricare le sue fonti." }
                true
            }
            check(completed == true) { "Tempo di attesa per PriSynx scaduto. Controlla la connessione e riprova." }
        } catch (error: LinkageError) {
            throw IllegalStateException("Aggiorna CloudStream per installare PriSynx da StreamCenter.", error)
        }
    }
}
