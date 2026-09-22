package it.dogior.hadEnough.extensions

import android.content.SharedPreferences
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginManager
import it.dogior.hadEnough.StreamCenter
import java.security.MessageDigest
import java.util.Locale

internal data class InstalledExtensionSource(val key: String, val api: MainAPI)

internal data class InstalledExtensionInfo(val filePath: String, val name: String, val downloadUrl: String? = null)

internal data class InstalledExtension(
    val key: String,
    val name: String,
    val sources: List<InstalledExtensionSource>,
    val downloadUrl: String? = null,
) {
    val summary: String
        get() {
            val types = sources.flatMap { it.api.supportedTypes }.toSet()
            val languages = sources.map { it.api.lang.trim().uppercase(Locale.ROOT) }
                .filter(String::isNotBlank).distinct().sorted().joinToString(" / ")
            return buildList {
                if (languages.isNotEmpty()) add(languages)
                if (TvType.Movie in types) add("Film")
                if (TvType.TvSeries in types) add("Serie TV")
                if (types.any { it in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) }) add("Anime")
            }.joinToString(" · ")
        }

    fun isEnabled(selected: Set<String>): Boolean = key in selected || sources.any { it.key in selected }

    fun updateSelection(selected: Set<String>, enabled: Boolean): Set<String> = selected.toMutableSet().apply {
        remove(key)
        removeAll(sources.map { it.key }.toSet())
        if (enabled) add(key)
    }
}

internal object InstalledExtensionSources {
    const val PREF_ENABLED = "installedExtensionSources"
    private val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    fun available(forHome: Boolean = false): List<InstalledExtension> {
        val apis = APIHolder.apis
        val plugins = (PluginManager.getPluginsOnline().toList() + PluginManager.getPluginsLocal().toList()).map {
            InstalledExtensionInfo(it.filePath, it.internalName, it.url)
        }
        return discover(apis.withLock { apis.toList() }, plugins, forHome)
    }

    internal fun discover(apis: List<MainAPI>, plugins: List<InstalledExtensionInfo> = emptyList(), forHome: Boolean = false): List<InstalledExtension> {
        val ownPlugins = apis.filterIsInstance<StreamCenter>()
            .mapNotNull { it.sourcePlugin?.takeIf(String::isNotBlank) }.toSet()
        val metadata = plugins.associateBy { it.filePath }
        return apis.filter { api ->
            api !is StreamCenter && api.sourcePlugin !in ownPlugins &&
                (if (forHome) api.hasMainPage else api.supportedTypes.any { it in supportedTypes })
        }.map { api -> InstalledExtensionSource(sourceKey(api), api) }
            .groupBy { it.api.sourcePlugin?.takeIf(String::isNotBlank) ?: it.key }
            .map { (path, sources) ->
                val plugin = metadata[path]
                val pluginName = plugin?.name?.takeIf(String::isNotBlank)
                    ?: sources.first().api.sourcePlugin?.substringAfterLast('/')?.substringAfterLast('\\')
                    ?: sources.first().api.name
                val name = pluginName.removeSuffix(".cs3").removeSuffix(".zip").removeSuffix("Provider")
                    .ifBlank { sources.first().api.name }
                val url = plugin?.downloadUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                InstalledExtension(
                    key = "extension-group:" + hash(url ?: path),
                    name = name,
                    sources = sources.distinctBy { it.key }.sortedWith(
                        compareBy({ !it.api.name.equals(name, ignoreCase = true) }, { it.api.name.lowercase(Locale.ROOT) }),
                    ),
                    downloadUrl = url,
                )
            }.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.key }))
    }

    internal fun sourceKey(api: MainAPI): String {
        val identity = listOf(api.javaClass.name, api.name, api.lang).joinToString("\n")
        return "extension:" + hash(identity)
    }

    private fun hash(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun enabledKeys(preferences: SharedPreferences?): Set<String> =
        preferences?.getStringSet(PREF_ENABLED, emptySet()).orEmpty().toSet()

    fun enabled(preferences: SharedPreferences?): List<InstalledExtension> {
        val selected = enabledKeys(preferences)
        if (selected.isEmpty()) return emptyList()
        return available().filter { it.isEnabled(selected) }
    }

    fun setEnabled(preferences: SharedPreferences?, extension: InstalledExtension, enabled: Boolean) {
        val selected = extension.updateSelection(enabledKeys(preferences), enabled)
        preferences?.edit()?.putStringSet(PREF_ENABLED, selected)?.apply()
    }
}
