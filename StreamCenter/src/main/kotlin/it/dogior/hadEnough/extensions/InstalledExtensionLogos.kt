package it.dogior.hadEnough.extensions

import com.lagradost.cloudstream3.plugins.RepositoryManager
import it.dogior.hadEnough.cache.ExpiringCache
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.mapChunkedParallel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal object InstalledExtensionLogos {
    private val catalogs = ExpiringCache<String, Map<String, String>>(32, 30 * 60_000L)
    private val failures = ExpiringCache<String, Boolean>(32, 60_000L)
    private val refreshLock = Mutex()

    suspend fun resolve(extension: InstalledExtension): String? {
        val url = extension.downloadUrl ?: return null
        StreamCenterVpnGuard.requireInternetAccess()
        return refreshLock.withLock {
            val repositories = (RepositoryManager.getRepositories().toList() + RepositoryManager.PREBUILT_REPOSITORIES)
                .distinctBy { it.url }
            withTimeoutOrNull(20_000L) {
                repositories.filter { catalogs[it.url] == null && failures[it.url] == null }.mapChunkedParallel(3) { repo ->
                    val plugins = withTimeoutOrNull(10_000L) { RepositoryManager.getRepoPlugins(repo) }
                    currentCoroutineContext().ensureActive()
                    if (plugins.isNullOrEmpty()) {
                        failures.put(repo.url, true)
                    } else {
                        catalogs.put(repo.url, plugins.mapNotNull { wrapper ->
                            normalizeIconUrl(wrapper.plugin.iconUrl)?.let { wrapper.plugin.url to it }
                        }.toMap())
                    }
                    true
                }
            }
            repositories.filter { catalogs[it.url] == null && failures[it.url] == null }
                .forEach { failures.put(it.url, true) }
            repositories.firstNotNullOfOrNull { catalogs[it.url]?.get(url) }
        }
    }

    internal fun normalizeIconUrl(value: String?): String? {
        val url = value?.trim()?.replace("%size%", "128")?.replace("%exact_size%", "128")
            ?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val uri = URI(url)
            url.takeIf { uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() }
        }.getOrNull()
    }
}
