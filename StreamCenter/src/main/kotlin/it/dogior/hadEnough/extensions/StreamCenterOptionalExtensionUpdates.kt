package it.dogior.hadEnough.extensions

import android.app.Activity
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal data class StreamCenterDownloadedExtension(
    val entry: StreamCenterOptionalExtension,
    val filePath: String,
    val version: Int,
)

internal data class StreamCenterOptionalUpdateResult(
    val checked: Int,
    val updated: List<String>,
    val failed: List<String>,
)

internal interface StreamCenterOptionalUpdateBackend {
    fun installed(): List<StreamCenterDownloadedExtension>
    suspend fun plugins(repositoryUrl: String): List<SitePlugin>?
    suspend fun download(installed: StreamCenterDownloadedExtension, plugin: SitePlugin): Boolean
}

internal object StreamCenterOptionalExtensionUpdates {
    private val refreshLock = Mutex()

    internal fun needsUpdate(installedVersion: Int, onlineVersion: Int): Boolean =
        onlineVersion == -1 || onlineVersion > installedVersion

    internal fun repositoryUrl(entry: StreamCenterOptionalExtension): String =
        if (entry.key == StreamCenterOptionalExtensions.PRISYNX) StreamCenterPriSynx.REPOSITORY_URL
        else StreamCenterUtilityExtensions.REPOSITORY_URL

    internal suspend fun refresh(backend: StreamCenterOptionalUpdateBackend): StreamCenterOptionalUpdateResult = refreshLock.withLock {
        val installed = backend.installed().distinctBy { it.entry.key }
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val repositories = installed.map { repositoryUrl(it.entry) }.distinct().associateWith { url ->
            runCatchingCancellable { backend.plugins(url) ?: error("Repository non disponibile") }
        }
        for (saved in installed) {
            currentCoroutineContext().ensureActive()
            runCatchingCancellable {
                val plugins = repositories.getValue(repositoryUrl(saved.entry)).getOrThrow()
                val online = if (saved.entry.key == StreamCenterOptionalExtensions.PRISYNX) {
                    StreamCenterPriSynx.selectPlugin(plugins)
                } else StreamCenterUtilityExtensions.selectPlugin(StreamCenterOptionalExtensions.utility(saved.entry)!!, plugins)
                if (needsUpdate(saved.version, online.version)) {
                    val didUpdate = withDownloadLock(saved.entry) {
                        val current = backend.installed().firstOrNull { it.entry.key == saved.entry.key && it.filePath == saved.filePath }
                        if (current == null || !needsUpdate(current.version, online.version)) false
                        else {
                            check(backend.download(current, online)) { "Aggiornamento non riuscito" }
                            true
                        }
                    }
                    if (didUpdate) updated.add(saved.entry.name)
                }
            }.onFailure { failed.add(saved.entry.name) }
        }
        StreamCenterOptionalUpdateResult(installed.size, updated, failed)
    }

    private suspend fun <T> withDownloadLock(entry: StreamCenterOptionalExtension, block: suspend () -> T): T =
        if (entry.key == StreamCenterOptionalExtensions.PRISYNX) StreamCenterPriSynx.withDownloadLock(block)
        else StreamCenterUtilityExtensions.withDownloadLock(StreamCenterOptionalExtensions.utility(entry)!!, block)

    suspend fun refresh(activity: Activity, preferences: SharedPreferences?): StreamCenterOptionalUpdateResult {
        val backend = object : StreamCenterOptionalUpdateBackend {
            override fun installed(): List<StreamCenterDownloadedExtension> = PluginManager.getPluginsOnline().mapNotNull { data ->
                if (!data.isOnline || data.url.isNullOrBlank() || !File(data.filePath).isFile) return@mapNotNull null
                val info = InstalledExtensionInfo(data.filePath, data.internalName, data.url)
                val entry = StreamCenterOptionalExtensions.entries.firstOrNull { entry ->
                    if (entry.key == StreamCenterOptionalExtensions.PRISYNX) StreamCenterPriSynx.isPriSynx(info.name, info.downloadUrl)
                    else StreamCenterOptionalExtensions.utility(entry)?.matches(info) == true
                } ?: return@mapNotNull null
                StreamCenterDownloadedExtension(entry, data.filePath, data.version)
            }

            override suspend fun plugins(repositoryUrl: String): List<SitePlugin>? {
                StreamCenterVpnGuard.requireInternetAccess(preferences)
                return withTimeoutOrNull(20_000L) {
                    val repository = app.get(RepositoryManager.convertRawGitUrl(repositoryUrl), cacheTime = 0)
                        .parsedSafe<Repository>() ?: return@withTimeoutOrNull null
                    repository.pluginLists.flatMap { url ->
                        app.get(RepositoryManager.convertRawGitUrl(url), cacheTime = 0)
                            .parsed<Array<SitePlugin>>().toList()
                    }
                }
            }

            override suspend fun download(installed: StreamCenterDownloadedExtension, plugin: SitePlugin): Boolean {
                StreamCenterVpnGuard.requireInternetAccess(preferences)
                return withTimeoutOrNull(120_000L) {
                    PluginManager.downloadPlugin(activity, plugin.url, plugin.fileHash, plugin.internalName,
                        File(installed.filePath), loadPlugin = true)
                } == true
            }
        }
        if (backend.installed().isNotEmpty()) StreamCenterVpnGuard.requireInternetAccess(preferences)
        return try {
            refresh(backend)
        } catch (error: LinkageError) {
            throw IllegalStateException("Aggiorna CloudStream per aggiornare le estensioni da StreamCenter.", error)
        }
    }
}
