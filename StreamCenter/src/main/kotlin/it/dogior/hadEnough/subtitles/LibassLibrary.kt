package it.dogior.hadEnough.subtitles

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.util.zip.ZipFile

internal object LibassLibrary {
    private const val ENTRY_ROOT = "libass"
    private val LIBRARIES = listOf("libstreamcenter_ass.so")

    @Volatile
    private var loaded: Boolean? = null

    @Volatile
    private var loading = false

    fun isReady(context: Context, pluginFile: String?): Boolean {
        loaded?.let { return it }
        preload(context, pluginFile)
        return false
    }

    @Synchronized
    fun preload(context: Context, pluginFile: String?) {
        if (loaded != null || loading) return
        loading = true
        Thread {
            loaded = runCatching { load(context, pluginFile) }
                .onFailure { AssLog.error("Caricamento libass non riuscito", it) }
                .getOrDefault(false)
            if (loaded == false) AssLog.warning("libass non disponibile su questo dispositivo")
            loading = false
        }.start()
    }

    private fun load(context: Context, pluginFile: String?): Boolean {
        val source = pluginFile?.let(::File)?.takeIf { it.isFile } ?: return false
        ZipFile(source).use { zip ->
            val abi = processAbis().firstOrNull { abi ->
                LIBRARIES.all { zip.getEntry("$ENTRY_ROOT/$abi/$it") != null }
            } ?: return false
            val root = File(context.filesDir, "streamcenter_libass")
            val key = "${source.length()}-${source.lastModified()}"
            root.listFiles()?.filterNot { it.name.startsWith(key) }?.forEach { it.deleteRecursively() }
            try {
                loadFrom(zip, abi, File(root, "$key/$abi"))
            } catch (error: UnsatisfiedLinkError) {
                loadFrom(zip, abi, File(root, "$key-${System.identityHashCode(javaClass.classLoader)}/$abi"))
            }
            warmUpFontCatalog()
            AssLog.info("libass caricato", mapOf("abi" to abi))
            return true
        }
    }

    private fun loadFrom(zip: ZipFile, abi: String, target: File) {
        target.mkdirs()
        LIBRARIES.forEach { name ->
            val file = File(target, name)
            val entry = zip.getEntry("$ENTRY_ROOT/$abi/$name")
            if (file.length() != entry.size) {
                zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        LIBRARIES.forEach { System.load(File(target, it).absolutePath) }
    }

    private fun warmUpFontCatalog() {
        val library = LibassNative.libraryInit()
        if (library == 0L) return
        LibassNative.rendererDone(LibassNative.rendererInit(library))
        LibassNative.libraryDone(library)
    }

    private fun processAbis(): List<String> {
        val is64Bit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Process.is64Bit()
        } else {
            System.getProperty("os.arch").orEmpty().contains("64")
        }
        val abis = if (is64Bit) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.toList().ifEmpty { Build.SUPPORTED_ABIS.toList() }
    }
}
