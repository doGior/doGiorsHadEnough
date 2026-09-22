package it.dogior.hadEnough.catalog

import android.content.SharedPreferences
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import it.dogior.hadEnough.StreamCenterHomeSectionDefinition
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.extensions.ExtensionCall
import it.dogior.hadEnough.extensions.InstalledExtensionSources
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class StreamCenterHomeImport(
    val kind: String,
    val sourceKey: String,
    val sourceName: String,
    val sectionKey: String,
    val title: String,
    val requestName: String = "",
    val requestData: String = "",
    val horizontalImages: Boolean = false,
) {
    val key: String get() = "import_" + MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(listOf(kind, sourceKey, sectionKey, requestData)).toString().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)

    val definition: StreamCenterHomeSectionDefinition get() = StreamCenterHomeSectionDefinition(
        key, StreamCenterHomeImports.DATA_PREFIX + key, StreamCenterPlugin.DEFAULT_HOME_COUNT,
        defaultTitle = "$sourceName: $title",
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind); put("sourceKey", sourceKey); put("sourceName", sourceName)
        put("sectionKey", sectionKey); put("title", title); put("requestName", requestName)
        put("requestData", requestData); put("horizontalImages", horizontalImages)
    }
}

internal data class StreamCenterHomeImportDiscovery(
    val sections: List<StreamCenterHomeImport>,
    val unavailableRequests: List<String>,
)

internal object StreamCenterHomeImports {
    const val PREF_SECTIONS = "homeImportedSections"
    const val DATA_PREFIX = "homeimport:"
    const val CATALOG = "catalog"
    const val EXTENSION = "extension"

    fun read(prefs: SharedPreferences?): List<StreamCenterHomeImport> {
        val array = runCatching { JSONArray(prefs?.getString(PREF_SECTIONS, "[]") ?: "[]") }
            .getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = json.optString("kind")
            val sourceKey = json.optString("sourceKey")
            val sectionKey = json.optString("sectionKey")
            if (kind !in setOf(CATALOG, EXTENSION) || sourceKey.isBlank() || sectionKey.isBlank()) {
                return@mapNotNull null
            }
            StreamCenterHomeImport(
                kind, sourceKey, json.optString("sourceName", sourceKey), sectionKey,
                json.optString("title", sectionKey), json.optString("requestName"),
                json.optString("requestData"), json.optBoolean("horizontalImages"),
            )
        }.distinctBy { it.key }
    }

    fun catalog(catalog: StreamCenterCatalogDefinition, section: StreamCenterCatalogSection) =
        StreamCenterHomeImport(CATALOG, catalog.key, catalog.title, section.key, section.title)

    fun extension(api: MainAPI, request: MainPageRequest, list: HomePageList) = StreamCenterHomeImport(
        EXTENSION, InstalledExtensionSources.sourceKey(api), api.name, list.name, list.name,
        request.name, request.data, request.horizontalImages,
    )

    suspend fun discoverExtensionSections(api: MainAPI, requests: List<MainPageRequest>): StreamCenterHomeImportDiscovery {
        val results = requests.distinct().mapChunkedParallel(3) { request ->
            val page = runCatchingCancellable {
                withTimeoutOrNull(20_000L) {
                    withContext(ExtensionCall) { api.getMainPage(1, request) }
                }
            }.getOrNull()
            request to page?.items.orEmpty().map { extension(api, request, it) }
        }
        return StreamCenterHomeImportDiscovery(
            sections = results.flatMap { it.second }.distinctBy { it.key },
            unavailableRequests = results.filter { it.second.isEmpty() }.map { it.first.name },
        )
    }

    fun add(prefs: SharedPreferences?, sections: List<StreamCenterHomeImport>) {
        prefs ?: return
        if (sections.isEmpty()) return
        val order = StreamCenterPlugin.getOrderedHomeSections(prefs).map { it.key }
        val updated = (read(prefs) + sections).distinctBy { it.key }
        prefs.edit()
            .putString(PREF_SECTIONS, JSONArray(updated.map { it.toJson() }).toString())
            .putString(StreamCenterPlugin.PREF_HOME_ORDER, (order + updated.map { it.key }).distinct().joinToString(","))
            .putBoolean(StreamCenterPlugin.PREF_HOME_FREE_ORDER, true)
            .apply()
    }

    fun remove(prefs: SharedPreferences?, key: String) {
        prefs ?: return
        val updated = read(prefs).filterNot { it.key == key }
        val order = StreamCenterPlugin.getOrderedHomeSections(prefs).map { it.key }.filterNot { it == key }
        prefs.edit()
            .putString(PREF_SECTIONS, JSONArray(updated.map { it.toJson() }).toString())
            .putString(StreamCenterPlugin.PREF_HOME_ORDER, order.joinToString(","))
            .remove(StreamCenterPlugin.sectionEnabledKey(key))
            .remove(StreamCenterPlugin.sectionTitleKey(key))
            .remove(StreamCenterPlugin.sectionCountKey(key))
            .apply()
    }

    suspend fun extensionPage(api: MainAPI, section: StreamCenterHomeImport, page: Int): HomePageResponse? =
        withTimeoutOrNull(20_000L) {
            withContext(ExtensionCall) {
                api.getMainPage(page, MainPageRequest(section.requestName, section.requestData, section.horizontalImages))
            }
        }

    fun extensionList(response: HomePageResponse?, section: StreamCenterHomeImport): HomePageList? =
        response?.items?.firstOrNull { it.name == section.sectionKey }
}
