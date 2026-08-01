package it.dogior.hadEnough.catalog

import android.content.SharedPreferences
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import it.dogior.hadEnough.stremio.StreamCenterStremioAddon
import it.dogior.hadEnough.stremio.StreamCenterStremioCatalogDescriptor
import it.dogior.hadEnough.stremio.StreamCenterStremioResource
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal data class StreamCenterCatalogSection(
    val key: String,
    val title: String,
    val path: String,
    val type: TvType,
    val defaultEnabled: Boolean = true,
    val trackingServiceKey: String? = null,
    val trackingListKey: String? = null,
    val stremioCatalog: StreamCenterStremioCatalogDescriptor? = null,
)

internal data class StreamCenterCatalogDefinition(
    val key: String,
    val title: String,
    val displayName: String,
    val websiteUrl: String,
    val iconUrl: String? = null,
    val sections: List<StreamCenterCatalogSection>,
    val supportedTypes: Set<TvType> = sections.mapTo(linkedSetOf()) { it.type },
    val stremioAddon: StreamCenterStremioAddon? = null,
)

internal data class StreamCenterCatalogPage(
    val items: List<SearchResponse>,
    val hasNext: Boolean,
)

internal interface StreamCenterCatalog {
    suspend fun section(
        api: MainAPI,
        section: StreamCenterCatalogSection,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage

    suspend fun search(
        api: MainAPI,
        query: String,
        page: Int,
        showScore: Boolean,
    ): StreamCenterCatalogPage
}

internal object StreamCenterCatalogs {
    const val CATEGORY_KEY = "catalogs"
    private const val PREF_CONFIGURED_CATALOGS = "catalogs"
    private const val PREF_CATALOG_SECTIONS_PREFIX = "catalogSections_"
    private const val PREF_CATALOG_SECTION_ORDER_PREFIX = "catalogSectionOrder_"
    private const val PREF_STREMIO_CATALOGS = "stremioCatalogs"
    private const val STREMIO_CATALOG_KEY_PREFIX = "stremioCatalog_"

    private val standardTrackingLists = listOf(
        "watching" to "Guardando",
        "completed" to "Completati",
        "on_hold" to "In pausa",
        "dropped" to "Interrotti",
        "plan_to_watch" to "Da guardare",
    )

    private fun trackingSections(
        catalogKey: String,
        type: TvType,
        includeRewatching: Boolean = false,
    ): List<StreamCenterCatalogSection> {
        val lists = standardTrackingLists + if (includeRewatching) {
            listOf("rewatching" to "Riguardando")
        } else {
            emptyList()
        }
        return lists.map { (listKey, title) ->
            StreamCenterCatalogSection(
                key = "${catalogKey}_tracking_$listKey",
                title = title,
                path = "tracking:$listKey",
                type = type,
                defaultEnabled = false,
                trackingServiceKey = catalogKey,
                trackingListKey = listKey,
            )
        }
    }

    val catalogs = listOf(
        StreamCenterCatalogDefinition(
            key = "tmdb",
            title = "TMDB",
            displayName = "StreamCenter (TMDB)",
            websiteUrl = "https://www.themoviedb.org",
            sections = listOf(
                StreamCenterCatalogSection("movie_popular", "Film popolari", "/movie", TvType.Movie),
                StreamCenterCatalogSection(
                    "movie_now_playing",
                    "Film al cinema",
                    "/movie/now-playing",
                    TvType.Movie,
                ),
                StreamCenterCatalogSection("movie_upcoming", "Film in arrivo", "/movie/upcoming", TvType.Movie),
                StreamCenterCatalogSection(
                    "movie_top_rated",
                    "Film più votati",
                    "/movie/top-rated",
                    TvType.Movie,
                ),
                StreamCenterCatalogSection("tv_popular", "Serie TV popolari", "/tv", TvType.TvSeries),
                StreamCenterCatalogSection(
                    "tv_airing_today",
                    "Serie TV oggi in onda",
                    "/tv/airing-today",
                    TvType.TvSeries,
                ),
                StreamCenterCatalogSection(
                    "tv_on_the_air",
                    "Serie TV in onda",
                    "/tv/on-the-air",
                    TvType.TvSeries,
                ),
                StreamCenterCatalogSection(
                    "tv_top_rated",
                    "Serie TV più votate",
                    "/tv/top-rated",
                    TvType.TvSeries,
                ),
            ),
        ),
        StreamCenterCatalogDefinition(
            key = "myanimelist",
            title = "MyAnimeList",
            displayName = "StreamCenter (MAL)",
            websiteUrl = "https://myanimelist.net",
            sections = listOf(
                StreamCenterCatalogSection("mal_top", "Migliori anime", "/topanime.php", TvType.Anime),
                StreamCenterCatalogSection(
                    "mal_airing",
                    "Anime in onda",
                    "/topanime.php?type=airing",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "mal_upcoming",
                    "Anime in arrivo",
                    "/topanime.php?type=upcoming",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "mal_popular",
                    "Anime più popolari",
                    "/topanime.php?type=bypopularity",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "mal_favorite",
                    "Anime più amati",
                    "/topanime.php?type=favorite",
                    TvType.Anime,
                ),
                *trackingSections("myanimelist", TvType.Anime).toTypedArray(),
            ),
            supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
        ),
        StreamCenterCatalogDefinition(
            key = "kitsu",
            title = "Kitsu",
            displayName = "StreamCenter (Kitsu)",
            websiteUrl = "https://kitsu.io",
            sections = listOf(
                StreamCenterCatalogSection(
                    "kitsu_popular",
                    "Anime più popolari",
                    "popular",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "kitsu_top",
                    "Anime più votati",
                    "top",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "kitsu_airing",
                    "Anime in onda",
                    "airing",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "kitsu_upcoming",
                    "Anime in arrivo",
                    "upcoming",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "kitsu_favorite",
                    "Anime più amati",
                    "favorite",
                    TvType.Anime,
                ),
                *trackingSections("kitsu", TvType.Anime).toTypedArray(),
            ),
            supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
        ),
        StreamCenterCatalogDefinition(
            key = "anilist",
            title = "AniList",
            displayName = "StreamCenter (AniList)",
            websiteUrl = "https://anilist.co",
            iconUrl = "https://avatars.githubusercontent.com/u/18018524?s=200&v=4",
            sections = listOf(
                StreamCenterCatalogSection(
                    "anilist_trending",
                    "Anime di tendenza",
                    "trending",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "anilist_season",
                    "Anime di questa stagione",
                    "season",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "anilist_popular",
                    "Anime più popolari",
                    "popular",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "anilist_top",
                    "Anime più votati",
                    "top",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "anilist_airing",
                    "Anime in onda",
                    "airing",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "anilist_upcoming",
                    "Anime in arrivo",
                    "upcoming",
                    TvType.Anime,
                ),
                *trackingSections("anilist", TvType.Anime, includeRewatching = true).toTypedArray(),
            ),
            supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
        ),
        StreamCenterCatalogDefinition(
            key = "simkl",
            title = "Simkl",
            displayName = "StreamCenter (Simkl)",
            websiteUrl = "https://simkl.com",
            sections = listOf(
                StreamCenterCatalogSection(
                    "simkl_movies_trending",
                    "Film di tendenza · Simkl",
                    "movies/trending",
                    TvType.Movie,
                ),
                StreamCenterCatalogSection(
                    "simkl_tv_trending",
                    "Serie TV di tendenza",
                    "tv/trending",
                    TvType.TvSeries,
                ),
                StreamCenterCatalogSection(
                    "simkl_tv_top",
                    "Serie TV più votate",
                    "tv/best/rating",
                    TvType.TvSeries,
                ),
                StreamCenterCatalogSection(
                    "simkl_tv_new",
                    "Nuove serie TV",
                    "tv/premieres/new",
                    TvType.TvSeries,
                ),
                StreamCenterCatalogSection(
                    "simkl_anime_popular",
                    "Anime più popolari",
                    "anime/best/watched",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "simkl_anime_top",
                    "Anime più votati",
                    "anime/best/rating",
                    TvType.Anime,
                ),
                StreamCenterCatalogSection(
                    "simkl_anime_upcoming",
                    "Anime in arrivo",
                    "anime/premieres/soon",
                    TvType.Anime,
                ),
                *trackingSections("simkl", TvType.TvSeries).toTypedArray(),
            ),
            supportedTypes = setOf(
                TvType.Movie,
                TvType.TvSeries,
                TvType.Anime,
                TvType.AnimeMovie,
                TvType.OVA,
            ),
        ),
    )

    fun allCatalogs(sharedPref: SharedPreferences?): List<StreamCenterCatalogDefinition> =
        catalogs + stremioCatalogs(sharedPref)

    fun stremioCatalogDefinition(
        addon: StreamCenterStremioAddon,
    ): StreamCenterCatalogDefinition? {
        val sections = addon.catalogs.mapNotNull { descriptor ->
            val type = stremioTvType(descriptor.type) ?: return@mapNotNull null
            if (descriptor.requiredExtra.any { name -> !name.equals("skip", ignoreCase = true) }) {
                return@mapNotNull null
            }
            StreamCenterCatalogSection(
                key = stremioSectionKey(descriptor),
                title = descriptor.name,
                path = descriptor.id,
                type = type,
                stremioCatalog = descriptor,
            )
        }.distinctBy(StreamCenterCatalogSection::key)
        if (sections.isEmpty()) return null
        val titleCounts = sections.groupingBy { section ->
            section.title.trim().lowercase(Locale.ROOT)
        }.eachCount()
        val assignedTitleCounts = mutableMapOf<String, Int>()
        val titledSections = sections.map { section ->
            val baseTitle = section.title.trim()
            val typeTitle = if ((titleCounts[baseTitle.lowercase(Locale.ROOT)] ?: 0) > 1) {
                "$baseTitle — ${stremioSectionTypeTitle(section.type)}"
            } else {
                baseTitle
            }
            val titleKey = typeTitle.lowercase(Locale.ROOT)
            val occurrence = (assignedTitleCounts[titleKey] ?: 0) + 1
            assignedTitleCounts[titleKey] = occurrence
            section.copy(title = if (occurrence == 1) typeTitle else "$typeTitle $occurrence")
        }
        return StreamCenterCatalogDefinition(
            key = STREMIO_CATALOG_KEY_PREFIX + addon.key,
            title = addon.name,
            displayName = "StreamCenter (${addon.name})",
            websiteUrl = addon.manifestUrl,
            iconUrl = addon.logoUrl,
            sections = titledSections,
            stremioAddon = addon,
        )
    }

    fun stremioCatalogAddon(
        sharedPref: SharedPreferences?,
        addonKey: String,
    ): StreamCenterStremioAddon? {
        return stremioCatalogs(sharedPref)
            .mapNotNull(StreamCenterCatalogDefinition::stremioAddon)
            .firstOrNull { addon -> addon.key == addonKey }
    }

    fun configuredCatalogs(sharedPref: SharedPreferences?): List<StreamCenterCatalogDefinition> {
        val catalogByKey = allCatalogs(sharedPref).associateBy(StreamCenterCatalogDefinition::key)
        return configuredCatalogKeys(sharedPref).mapNotNull(catalogByKey::get)
    }

    fun isConfigured(sharedPref: SharedPreferences?, catalog: StreamCenterCatalogDefinition): Boolean {
        return configuredCatalogs(sharedPref).any { it.key == catalog.key }
    }

    fun selectedSections(
        sharedPref: SharedPreferences?,
        catalog: StreamCenterCatalogDefinition,
    ): List<StreamCenterCatalogSection> {
        if (!isConfigured(sharedPref, catalog)) return emptyList()
        val storedSelection = sharedPref?.getString(catalogSectionsKey(catalog.key), null)
        val selectedKeys = storedSelection
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
        val orderedSections = orderedSections(sharedPref, catalog)
        if (storedSelection == null) return orderedSections.filter(StreamCenterCatalogSection::defaultEnabled)
        return orderedSections.filter { it.key in selectedKeys }
    }

    fun orderedSections(
        sharedPref: SharedPreferences?,
        catalog: StreamCenterCatalogDefinition,
    ): List<StreamCenterCatalogSection> {
        val sectionByKey = catalog.sections.associateBy { it.key }
        val savedSections = sharedPref?.getString(catalogSectionOrderKey(catalog.key), null)
            ?.split(",")
            ?.mapNotNull(sectionByKey::get)
            ?.distinctBy { it.key }
            .orEmpty()
        val savedKeys = savedSections.mapTo(mutableSetOf()) { it.key }
        return savedSections + catalog.sections.filterNot { it.key in savedKeys }
    }

    fun saveCatalog(
        sharedPref: SharedPreferences?,
        catalog: StreamCenterCatalogDefinition,
        selectedSectionKeys: Collection<String>,
        orderedSectionKeys: Collection<String> = catalog.sections.map { it.key },
    ): Boolean {
        val preferences = sharedPref ?: return false
        val availableKeys = catalog.sections.mapTo(linkedSetOf()) { it.key }
        val selectedKeys = selectedSectionKeys.filter { it in availableKeys }.distinct()
        if (selectedKeys.isEmpty()) return false
        val orderedKeys = orderedSectionKeys.filter { it in availableKeys }.distinct()
        val completeOrder = orderedKeys + catalog.sections.map { it.key }.filterNot { it in orderedKeys }
        val configuredKeys = configuredCatalogs(preferences).map { it.key }
        val updatedKeys = if (catalog.key in configuredKeys) configuredKeys else configuredKeys + catalog.key
        preferences.edit().apply {
            if (catalog.stremioAddon != null) {
                val retained = stremioCatalogs(preferences).filterNot { it.key == catalog.key }
                putString(
                    PREF_STREMIO_CATALOGS,
                    JSONArray().apply {
                        (retained + catalog).forEach { definition ->
                            definition.stremioAddon?.let { stremioAddon ->
                                put(stremioAddon.toStremioCatalogJson())
                            }
                        }
                    }.toString(),
                )
            }
            putString(PREF_CONFIGURED_CATALOGS, updatedKeys.joinToString(","))
            putString(catalogSectionsKey(catalog.key), selectedKeys.joinToString(","))
            putString(catalogSectionOrderKey(catalog.key), completeOrder.joinToString(","))
        }.apply()
        return true
    }

    fun removeCatalog(sharedPref: SharedPreferences?, catalog: StreamCenterCatalogDefinition) {
        val preferences = sharedPref ?: return
        val configuredKeys = configuredCatalogs(preferences).map { it.key }.filterNot { it == catalog.key }
        preferences.edit().apply {
            if (catalog.stremioAddon != null) {
                val retained = stremioCatalogs(preferences).filterNot { it.key == catalog.key }
                if (retained.isEmpty()) {
                    remove(PREF_STREMIO_CATALOGS)
                } else {
                    putString(
                        PREF_STREMIO_CATALOGS,
                        JSONArray().apply {
                            retained.forEach { definition ->
                                definition.stremioAddon?.let { addon ->
                                    put(addon.toStremioCatalogJson())
                                }
                            }
                        }.toString(),
                    )
                }
            }
            putString(PREF_CONFIGURED_CATALOGS, configuredKeys.joinToString(","))
            remove(catalogSectionsKey(catalog.key))
            remove(catalogSectionOrderKey(catalog.key))
        }.apply()
    }

    fun reset(sharedPref: SharedPreferences?) {
        val preferences = sharedPref ?: return
        preferences.edit().apply {
            remove(PREF_CONFIGURED_CATALOGS)
            allCatalogs(preferences).forEach {
                remove(catalogSectionsKey(it.key))
                remove(catalogSectionOrderKey(it.key))
            }
            remove(PREF_STREMIO_CATALOGS)
        }.apply()
    }

    private fun configuredCatalogKeys(sharedPref: SharedPreferences?): List<String> {
        return sharedPref?.getString(PREF_CONFIGURED_CATALOGS, null)
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
    }

    private fun stremioCatalogs(sharedPref: SharedPreferences?): List<StreamCenterCatalogDefinition> {
        val raw = sharedPref?.getString(PREF_STREMIO_CATALOGS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val addon = array.optJSONObject(index)?.toStremioCatalogAddon() ?: continue
                stremioCatalogDefinition(addon)?.let(::add)
            }
        }.distinctBy(StreamCenterCatalogDefinition::key)
    }

    private fun StreamCenterStremioAddon.toStremioCatalogJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("manifestUrl", manifestUrl)
        put("id", id)
        put("name", name)
        version?.let { put("version", it) }
        logoUrl?.let { put("logo", it) }
        put("types", JSONArray(types))
        put("idPrefixes", JSONArray(idPrefixes))
        put(
            "resources",
            JSONArray().apply {
                resources.forEach { resource ->
                    put(
                        JSONObject().apply {
                            put("name", resource.name)
                            put("types", JSONArray(resource.types))
                            put("idPrefixes", JSONArray(resource.idPrefixes))
                        },
                    )
                }
            },
        )
        put(
            "catalogs",
            JSONArray().apply {
                catalogs.forEach { catalog ->
                    put(
                        JSONObject().apply {
                            put("id", catalog.id)
                            put("type", catalog.type)
                            put("name", catalog.name)
                            put("extra", JSONArray(catalog.extra))
                            put("requiredExtra", JSONArray(catalog.requiredExtra))
                        },
                    )
                }
            },
        )
    }

    private fun JSONObject.toStremioCatalogAddon(): StreamCenterStremioAddon? {
        val key = optString("key").trim()
        val manifestUrl = optString("manifestUrl").trim()
        val id = optString("id").trim()
        val name = optString("name").trim()
        if (key.isBlank() || manifestUrl.isBlank() || id.isBlank() || name.isBlank()) return null
        val resources = optJSONArray("resources")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val resource = array.optJSONObject(index) ?: continue
                    val resourceName = resource.optString("name").trim()
                    if (resourceName.isBlank()) continue
                    add(
                        StreamCenterStremioResource(
                            name = resourceName,
                            types = resource.optStringList("types"),
                            idPrefixes = resource.optStringList("idPrefixes"),
                        ),
                    )
                }
            }
        }.orEmpty()
        val catalogs = optJSONArray("catalogs")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val catalog = array.optJSONObject(index) ?: continue
                    val catalogId = catalog.optString("id").trim()
                    val catalogType = catalog.optString("type").trim()
                    val catalogName = catalog.optString("name").trim()
                    if (catalogId.isBlank() || catalogType.isBlank() || catalogName.isBlank()) continue
                    add(
                        StreamCenterStremioCatalogDescriptor(
                            id = catalogId,
                            type = catalogType,
                            name = catalogName,
                            extra = catalog.optStringList("extra"),
                            requiredExtra = catalog.optStringList("requiredExtra"),
                        ),
                    )
                }
            }
        }.orEmpty()
        return StreamCenterStremioAddon(
            key = key,
            manifestUrl = manifestUrl,
            id = id,
            name = name,
            version = optString("version").trim().takeIf(String::isNotBlank),
            logoUrl = optString("logo").trim().takeIf(String::isNotBlank),
            types = optStringList("types"),
            idPrefixes = optStringList("idPrefixes"),
            resources = resources,
            catalogs = catalogs,
        )
    }

    private fun JSONObject.optStringList(key: String): List<String> = optJSONArray(key)?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.orEmpty()

    private fun stremioTvType(type: String): TvType? = when (type.lowercase(Locale.ROOT)) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "tv", "channel" -> TvType.Live
        else -> null
    }

    private fun stremioSectionTypeTitle(type: TvType): String = when (type) {
        TvType.Movie -> "Film"
        TvType.TvSeries -> "Serie TV"
        TvType.Anime, TvType.AnimeMovie, TvType.OVA -> "Anime"
        TvType.Live -> "TV"
        else -> "Altro"
    }

    private fun stremioSectionKey(catalog: StreamCenterStremioCatalogDescriptor): String {
        val source = "${catalog.type.lowercase(Locale.ROOT)}:${catalog.id}"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "stremio_$fingerprint"
    }

    fun sectionData(catalog: StreamCenterCatalogDefinition, section: StreamCenterCatalogSection): String {
        return "catalog:${catalog.key}:${section.key}"
    }

    fun sectionForData(
        catalog: StreamCenterCatalogDefinition,
        data: String,
    ): StreamCenterCatalogSection? {
        val prefix = "catalog:${catalog.key}:"
        if (!data.startsWith(prefix)) return null
        val sectionKey = data.removePrefix(prefix)
        return catalog.sections.firstOrNull { it.key == sectionKey }
    }

    private fun catalogSectionsKey(catalogKey: String): String {
        return "$PREF_CATALOG_SECTIONS_PREFIX$catalogKey"
    }

    private fun catalogSectionOrderKey(catalogKey: String): String {
        return "$PREF_CATALOG_SECTION_ORDER_PREFIX$catalogKey"
    }
}
