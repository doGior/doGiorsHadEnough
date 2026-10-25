package it.dogior.hadEnough.extensions

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

internal class StreamCenterExtensionSettingsAccess(private val loader: ClassLoader) {
    constructor(plugin: Plugin) : this(plugin.javaClass.classLoader!!)

    fun singleton(name: String): Any = loader.loadClass(name).getField("INSTANCE").get(null)!!

    fun constant(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(null)

    fun call(target: Any, name: String, vararg arguments: Any?): Any? {
        val method = (target.javaClass.methods + target.javaClass.declaredMethods).distinct().singleOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.size == arguments.size &&
                candidate.parameterTypes.zip(arguments).all { (type, argument) ->
                    argument == null && !type.isPrimitive || argument != null && boxed(type).isInstance(argument)
                }
        } ?: error("Impostazione non supportata dalla versione installata: $name")
        return invoke { method.apply { isAccessible = true }.invoke(target, *arguments) }
    }

    fun create(name: String, vararg arguments: Any): Any {
        val constructor = loader.loadClass(name).declaredConstructors.single { candidate ->
            candidate.parameterTypes.size == arguments.size &&
                candidate.parameterTypes.zip(arguments).all { (type, argument) -> type.isInstance(argument) }
        }
        return invoke { constructor.apply { isAccessible = true }.newInstance(*arguments) }
    }

    suspend fun callSuspending(target: Any, name: String, vararg arguments: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            call(target, name, *arguments, continuation)
        }

    fun watchPartyManager(plugin: Plugin): Any = plugin.javaClass.getDeclaredField("manager")
        .apply { isAccessible = true }.get(plugin)!!

    private fun <T> invoke(block: () -> T): T = try {
        block()
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        else -> type
    }
}

internal data class StreamCenterOptionalExtension(
    val key: String,
    val name: String,
    val author: String,
    val summary: String,
    val icon: String,
    val panelClass: String? = null,
    val closeViewId: String? = null,
) {
    val logo: String get() = if (key == StreamCenterOptionalExtensions.WATCH_ADVANCED) "watchparty" else name.lowercase()
    val credit: String get() = "Autore: $author"
}

internal object StreamCenterOptionalExtensions {
    const val PRISYNX = "prisynx"
    const val WATCH_ADVANCED = "watchparty-advanced"
    val entries = listOf(
        StreamCenterOptionalExtension(PRISYNX, StreamCenterPriSynx.NAME, StreamCenterPriSynx.AUTHOR,
            "Fonti e cataloghi di PriSynx Center", "🧩"),
    ) + StreamCenterUtilityExtensions.extensions.map { extension ->
        val (panel, close) = when (extension.name) {
            "SleepGuard" -> "sleepguard.SleepGuardSettingsFragment" to "sg_close"
            "WatchParty" -> "watchparty.WatchPartySettingsFragmentCloudStream" to "wp_close"
            "Wrapped" -> "wrapped.WrappedFragment" to "wrapped_close"
            else -> error("Estensione opzionale non supportata")
        }
        StreamCenterOptionalExtension(extension.key, extension.name, extension.author, extension.summary,
            extension.icon, "it.dogior.hadEnough.$panel", close)
    }

    fun entry(key: String): StreamCenterOptionalExtension? = if (key == WATCH_ADVANCED) {
        entries.first { it.name == "WatchParty" }.copy(key = key, name = "WatchParty · Avanzate",
            panelClass = "it.dogior.hadEnough.watchparty.WatchPartyAdvancedSettingsFragmentCloudStream",
            closeViewId = "wpa_close")
    } else entries.firstOrNull { it.key == key }

    fun utility(entry: StreamCenterOptionalExtension): StreamCenterUtilityExtension? =
        StreamCenterUtilityExtensions.extensions.firstOrNull {
            it.key == entry.key || entry.key == WATCH_ADVANCED && it.name == "WatchParty"
        }

    fun loaded(entry: StreamCenterOptionalExtension): Plugin? = if (entry.key == PRISYNX) {
        StreamCenterPriSynx.loaded()
    } else utility(entry)?.let(StreamCenterUtilityExtensions::installed)?.let(StreamCenterUtilityExtensions::loaded)

    internal fun matches(entry: StreamCenterOptionalExtension, info: InstalledExtensionInfo): Boolean = if (entry.key == PRISYNX) {
        val name = info.name.replace(Regex("\\.(cs3|zip)$", RegexOption.IGNORE_CASE), "")
        StreamCenterPriSynx.isPriSynx(name, info.downloadUrl)
    } else utility(entry)?.matches(info) == true

    internal fun installedPlugins(): List<InstalledExtensionInfo> =
        (PluginManager.getPluginsOnline().toList() + PluginManager.getPluginsLocal().toList())
            .filter { File(it.filePath).isFile }
            .map { InstalledExtensionInfo(it.filePath, it.internalName, it.url) }

    fun isInstalled(entry: StreamCenterOptionalExtension): Boolean = installedPlugins().any { matches(entry, it) }

    fun installedVersionLabel(entry: StreamCenterOptionalExtension): String {
        val path = installedPlugins().firstOrNull { matches(entry, it) }?.filePath
        val version = path?.let { filePath ->
            (PluginManager.getPluginsOnline().toList() + PluginManager.getPluginsLocal().toList())
                .firstOrNull { it.filePath == filePath }?.version
        }
        return when (version) {
            null, Int.MIN_VALUE -> "Versione non disponibile"
            -1 -> "Versione dinamica"
            else -> "Versione $version"
        }
    }

    fun isInstalling(entry: StreamCenterOptionalExtension): Boolean = if (entry.key == PRISYNX) {
        StreamCenterPriSynx.isInstalling
    } else utility(entry)?.let(StreamCenterUtilityExtensions::isInstalling) == true
}
