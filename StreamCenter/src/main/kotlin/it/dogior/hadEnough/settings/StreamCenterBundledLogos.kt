package it.dogior.hadEnough.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

internal object StreamCenterBundledLogos {
    private const val ENTRY_ROOT = "logos"

    private val logosByDomain = mapOf(
        "themoviedb.org" to "tmdb",
        "myanimelist.net" to "myanimelist",
        "anilist.co" to "anilist",
        "kitsu.io" to "kitsu",
        "kitsu.app" to "kitsu",
        "simkl.com" to "simkl",
        "imdb.com" to "imdb",
        "stremio.com" to "stremio",
        "telegram.org" to "telegram",
        "netflix.com" to "netflix",
        "primevideo.com" to "primevideo",
        "disneyplus.com" to "disneyplus",
        "tv.apple.com" to "appletv",
        "nowtv.it" to "nowtv",
        "max.com" to "max",
    )

    @Volatile
    private var pluginFile: String? = null

    @Volatile
    private var encoded: Map<String, ByteArray>? = null
    private val decoded = ConcurrentHashMap<String, Bitmap>()

    fun install(pluginFile: String?) {
        this.pluginFile = pluginFile
    }

    fun covers(url: String?): Boolean = logoName(url) != null

    fun bitmap(vararg urls: String?): Bitmap? {
        val name = urls.firstNotNullOfOrNull(::logoName) ?: return null
        decoded[name]?.let { return it }
        val bytes = encodedLogos()[name] ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return decoded.putIfAbsent(name, bitmap) ?: bitmap
    }

    private fun logoName(url: String?): String? {
        val host = url?.let { runCatching { URI(it.trim()).host }.getOrNull() }
            ?.lowercase(Locale.ROOT)
            ?: return null
        return logosByDomain.entries.firstOrNull { (domain, _) ->
            host == domain || host.endsWith(".$domain")
        }?.value
    }

    @Synchronized
    private fun encodedLogos(): Map<String, ByteArray> {
        encoded?.let { return it }
        val source = pluginFile?.let(::File)?.takeIf { it.isFile } ?: return emptyMap()
        val logos = runCatching {
            ZipFile(source).use { zip ->
                logosByDomain.values.toSet().mapNotNull { name ->
                    val entry = zip.getEntry("$ENTRY_ROOT/$name.png") ?: return@mapNotNull null
                    name to zip.getInputStream(entry).use { it.readBytes() }
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        encoded = logos
        return logos
    }
}
