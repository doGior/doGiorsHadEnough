package it.dogior.hadEnough

import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.settings.*
import it.dogior.hadEnough.iptv.StreamCenterIptv
import it.dogior.hadEnough.stremio.*

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.SyncWatchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class StreamCenterHomeSectionDefinition(
    val key: String,
    val data: String,
    val defaultCount: Int,
    val defaultEnabled: Boolean = true,
)

data class StreamCenterConfiguredHomeSection(
    val definition: StreamCenterHomeSectionDefinition,
    val title: String,
)

data class StreamCenterTrackingListStatus(
    val key: String,
    val title: String,
    val watchType: SyncWatchType,
)

data class StreamCenterTrackingService(
    val key: String,
    val title: String,
    val syncIdName: SyncIdName,
    val statuses: List<StreamCenterTrackingListStatus>,
)

data class StreamCenterTrackingListConfig(
    val service: StreamCenterTrackingService,
    val status: StreamCenterTrackingListStatus,
)

data class StreamCenterAnimeArchiveFilters(
    val genreId: Int? = null,
    val year: Int? = null,
    val order: String? = null,
    val status: String? = null,
    val type: String? = null,
    val season: String? = null,
    val dubbed: Boolean = false,
)

data class StreamCenterStreamingSource(
    val key: String,
    val title: String,
    val urlPrefKey: String,
    val defaultUrl: String,
    val category: String = "anime",
    val defaultEnabled: Boolean = true,
)

internal data class StreamCenterStremioManifestRefreshResult(
    val total: Int,
    val updated: Int,
) {
    val failed: Int
        get() = (total - updated).coerceAtLeast(0)
}

@CloudstreamPlugin
class StreamCenterPlugin : Plugin() {
    companion object {
        const val PREFS_NAME = "StreamCenter"
        const val PREF_SHOW_HOME_SCORE = "showHomeScore"
        const val PREF_SHOW_ANIME_HOME_DUB_STATUS = "showAnimeHomeDubStatus"
        const val PREF_SHOW_ANIME_HOME_EPISODE_NUMBER = "showAnimeHomeEpisodeNumber"
        const val PREF_ANIME_CARD_TITLE = "animeCardTitle"
        const val ANIME_CARD_TITLE_ANIZIP = "aniZip"
        const val ANIME_CARD_TITLE_ANIMEUNITY = "animeUnity"
        const val ANIME_CARD_TITLE_ROMAJI = "romaji"
        const val ANIME_CARD_TITLE_ENGLISH = "english"
        const val ANIME_CARD_TITLE_NATIVE = "native"
        const val PREF_PERFORMANCE_MODE = "performanceMode"
        const val PREF_VISUAL_EFFECTS_ANIMATIONS = "visualEffectsAnimations"
        const val PREF_VISUAL_EFFECTS_BLUR = "visualEffectsBlur"
        const val PREF_VISUAL_EFFECTS_TITLE = "visualEffectsTitle"
        const val PREF_VISUAL_EFFECTS_PARTICLES = "visualEffectsParticles"
        const val PREF_GROUP_ANIME_DUB_SUB = "groupAnimeDubSub"
        const val PREF_HOME_ORDER = "homeOrder"
        const val PREF_HOME_CATEGORY_ORDER = "homeCategoryOrder"
        const val PREF_HOME_LAYOUT_VERSION = "homeLayoutVersion"
        const val PREF_IPTV_FAVORITE_CHANNELS = "iptvFavoriteChannels"
        const val PREF_IPTV_REGION = "iptvRegion"
        const val PREF_IPTV_CUSTOM_SECTIONS = "iptvCustomSections"
        const val PREF_IPTV_CUSTOM_SECTION_COUNTER = "iptvCustomSectionCounter"
        const val IPTV_CUSTOM_SECTION_PREFIX = "live_custom_"
        const val PREF_ANIME_CUSTOM_SECTIONS = "animeCustomSections"
        const val PREF_ANIME_CUSTOM_SECTION_COUNTER = "animeCustomSectionCounter"
        const val ANIME_CUSTOM_SECTION_PREFIX = "anime_custom_"
        const val PREF_TRACKING_CUSTOM_SECTIONS = "trackingCustomSections"
        const val PREF_TRACKING_CUSTOM_SECTION_COUNTER = "trackingCustomSectionCounter"
        const val TRACKING_CUSTOM_SECTION_PREFIX = "tracking_custom_"
        const val CURRENT_HOME_LAYOUT_VERSION = 6
        const val PREF_SOURCE_ANIMEUNITY = "sourceAnimeUnity"
        const val PREF_SOURCE_ANIMEWORLD = "sourceAnimeWorld"
        const val PREF_SOURCE_ANIMESATURN = "sourceAnimeSaturn"
        const val PREF_SOURCE_STREAMINGCOMMUNITY = "sourceStreamingCommunity"
        const val PREF_SOURCE_VIDXGO = "sourceVidxGo"

        const val PREF_URL_ANIMEUNITY = "urlAnimeUnity"
        const val PREF_URL_ANIMEWORLD = "urlAnimeWorld"
        const val PREF_URL_ANIMESATURN = "urlAnimeSaturn"
        const val PREF_URL_STREAMINGCOMMUNITY = "urlStreamingCommunity"
        const val PREF_URL_VIDXGO = "urlVidxGo"

        const val DEFAULT_URL_ANIMEUNITY = "https://www.animeunity.so"
        const val DEFAULT_URL_ANIMEWORLD = "https://www.animeworld.ac"
        const val DEFAULT_URL_ANIMESATURN = "https://www.animesaturn.net"
        const val DEFAULT_URL_STREAMINGCOMMUNITY = "https://streamingcommunityz.sale"
        const val DEFAULT_URL_VIDXGO = "https://v.vidxgo.co"

        const val PREF_SOURCE_PRIORITY = "sourcePriority"
        const val PREF_STREMIO_ADDONS = "stremioAddons"
        private const val PREF_STREMIO_ADDON_ENABLED_PREFIX = "stremioAddonEnabled_"

        const val PREF_AUTO_UPDATE_SOURCE_URLS = "autoUpdateSourceUrls"

        const val PREF_ANILIST_RPM = "anilistRequestsPerMinute"
        const val DEFAULT_ANILIST_RPM = 30
        const val MIN_ANILIST_RPM = 5
        const val MAX_ANILIST_RPM = 90

        const val DEFAULT_HOME_COUNT = 24
        const val MIN_HOME_COUNT = 6
        const val MAX_HOME_COUNT = Int.MAX_VALUE

        val homeCategories = listOf(
            "anime",
            "tv",
            "movie",
            "live",
            "tracking",
            StreamCenterCatalogs.CATEGORY_KEY,
        )

        private val standardTrackingStatuses = listOf(
            StreamCenterTrackingListStatus("watching", "Guardando", SyncWatchType.WATCHING),
            StreamCenterTrackingListStatus("completed", "Completati", SyncWatchType.COMPLETED),
            StreamCenterTrackingListStatus("on_hold", "In pausa", SyncWatchType.ONHOLD),
            StreamCenterTrackingListStatus("dropped", "Interrotti", SyncWatchType.DROPPED),
            StreamCenterTrackingListStatus("plan_to_watch", "Da guardare", SyncWatchType.PLANTOWATCH),
        )

        val trackingServices = listOf(
            StreamCenterTrackingService(
                key = "myanimelist",
                title = "MyAnimeList",
                syncIdName = SyncIdName.MyAnimeList,
                statuses = standardTrackingStatuses,
            ),
            StreamCenterTrackingService(
                key = "kitsu",
                title = "Kitsu",
                syncIdName = SyncIdName.Kitsu,
                statuses = standardTrackingStatuses,
            ),
            StreamCenterTrackingService(
                key = "anilist",
                title = "AniList",
                syncIdName = SyncIdName.Anilist,
                statuses = standardTrackingStatuses + StreamCenterTrackingListStatus(
                    "rewatching",
                    "Riguardando",
                    SyncWatchType.REWATCHING,
                ),
            ),
            StreamCenterTrackingService(
                key = "simkl",
                title = "Simkl",
                syncIdName = SyncIdName.Simkl,
                statuses = standardTrackingStatuses,
            ),
        )

        internal const val FEEDBACK_ISSUES_URL =
            "https://github.com/doGior/doGiorsHadEnough/issues/new"

        val homeSections = listOf(
            StreamCenterHomeSectionDefinition(
                key = "anime_calendar",
                data = "au:calendar",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_latest",
                data = "au:latest",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_popular",
                data = "au:popular",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_trending",
                data = "sc:tv:trending",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_latest",
                data = "sc:tv:latest",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_top10",
                data = "sc:tv:top10",
                defaultCount = 10,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_upcoming",
                data = "sc:tv:upcoming",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_trending",
                data = "sc:movie:trending",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_latest",
                data = "sc:movie:latest",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_top10",
                data = "sc:movie:top10",
                defaultCount = 10,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_genre_azione",
                data = "sc:archive:movie:4",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_genre_commedia",
                data = "sc:archive:movie:12",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_genre_horror",
                data = "sc:archive:movie:7",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_genre_fantascienza",
                data = "sc:archive:movie:10",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_genre_animazione",
                data = "sc:archive:movie:19",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_genre_azione",
                data = "sc:archive:tv:13",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_genre_commedia",
                data = "sc:archive:tv:12",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_genre_crime",
                data = "sc:archive:tv:2",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_genre_scifi",
                data = "sc:archive:tv:3",
                defaultCount = 20,
                defaultEnabled = false,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_genre_dramma",
                data = "sc:archive:tv:1",
                defaultCount = 20,
                defaultEnabled = false,
            ),
        )

        val streamingSources = listOf(
            StreamCenterStreamingSource(
                key = PREF_SOURCE_STREAMINGCOMMUNITY,
                title = "StreamingCommunity",
                urlPrefKey = PREF_URL_STREAMINGCOMMUNITY,
                defaultUrl = DEFAULT_URL_STREAMINGCOMMUNITY,
                category = "tv",
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_VIDXGO,
                title = "VidxGo",
                urlPrefKey = PREF_URL_VIDXGO,
                defaultUrl = DEFAULT_URL_VIDXGO,
                category = "tv",
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMEUNITY,
                title = "AnimeUnity",
                urlPrefKey = PREF_URL_ANIMEUNITY,
                defaultUrl = DEFAULT_URL_ANIMEUNITY,
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMEWORLD,
                title = "AnimeWorld",
                urlPrefKey = PREF_URL_ANIMEWORLD,
                defaultUrl = DEFAULT_URL_ANIMEWORLD,
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMESATURN,
                title = "AnimeSaturn",
                urlPrefKey = PREF_URL_ANIMESATURN,
                defaultUrl = DEFAULT_URL_ANIMESATURN,
            ),
        )

        internal var activeSharedPref: SharedPreferences? = null
        internal var activeContext: Context? = null
        private var activePlugin: StreamCenterPlugin? = null

        internal fun refreshCatalogs() {
            activePlugin?.registerConfiguredCatalogs()
        }

        internal fun resetAllConfiguration(
            context: Context,
            sharedPref: SharedPreferences?,
        ) {
            val preferences = sharedPref
                ?: activeSharedPref
                ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val failures = listOfNotNull(
                runCatching {
                    check(preferences.edit().clear().commit()) {
                        "Impossibile cancellare la configurazione principale."
                    }
                }.exceptionOrNull(),
                runCatching { StreamCenterBackupManager.resetDirectory(context) }.exceptionOrNull(),
                runCatching { StreamCenter.resetRuntimeConfiguration() }.exceptionOrNull(),
                runCatching { StreamCenterStremioManifestRefreshNotice.reset() }.exceptionOrNull(),
            )
            if (failures.isNotEmpty()) {
                throw IllegalStateException(
                    "Non è stato possibile completare il ripristino della configurazione.",
                    failures.first(),
                )
            }
        }

        fun shouldShowHomeScore(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_HOME_SCORE, true) ?: true
        }

        fun shouldShowAnimeHomeDubStatus(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_ANIME_HOME_DUB_STATUS, true) ?: true
        }

        fun shouldShowAnimeHomeEpisodeNumber(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, true) ?: true
        }

        fun getAnimeCardTitle(sharedPref: SharedPreferences?): String {
            return when (sharedPref?.getString(PREF_ANIME_CARD_TITLE, ANIME_CARD_TITLE_ANIZIP)) {
                ANIME_CARD_TITLE_ANIMEUNITY -> ANIME_CARD_TITLE_ANIMEUNITY
                ANIME_CARD_TITLE_ROMAJI -> ANIME_CARD_TITLE_ROMAJI
                ANIME_CARD_TITLE_ENGLISH -> ANIME_CARD_TITLE_ENGLISH
                ANIME_CARD_TITLE_NATIVE -> ANIME_CARD_TITLE_NATIVE
                else -> ANIME_CARD_TITLE_ANIZIP
            }
        }

        fun isPerformanceModeEnabled(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_PERFORMANCE_MODE, false) ?: false
        }

        private fun isVisualEffectEnabled(sharedPref: SharedPreferences?, preferenceKey: String): Boolean {
            return !isPerformanceModeEnabled(sharedPref) &&
                (sharedPref?.getBoolean(preferenceKey, true) ?: true)
        }

        fun areVisualAnimationsEnabled(sharedPref: SharedPreferences?): Boolean {
            return isVisualEffectEnabled(sharedPref, PREF_VISUAL_EFFECTS_ANIMATIONS)
        }

        fun areVisualBlursEnabled(sharedPref: SharedPreferences?): Boolean {
            return isVisualEffectEnabled(sharedPref, PREF_VISUAL_EFFECTS_BLUR)
        }

        fun areVisualTitleEffectsEnabled(sharedPref: SharedPreferences?): Boolean {
            return isVisualEffectEnabled(sharedPref, PREF_VISUAL_EFFECTS_TITLE)
        }

        fun areVisualParticlesEnabled(sharedPref: SharedPreferences?): Boolean {
            return isVisualEffectEnabled(sharedPref, PREF_VISUAL_EFFECTS_PARTICLES)
        }

        fun shouldGroupAnimeVariants(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_GROUP_ANIME_DUB_SUB, true) ?: true
        }

        fun isStreamingSourceEnabled(sharedPref: SharedPreferences?, prefKey: String): Boolean {
            val source = streamingSources.firstOrNull { it.key == prefKey } ?: return false
            return sharedPref?.getBoolean(prefKey, source.defaultEnabled) ?: source.defaultEnabled
        }

        internal fun getStremioAddons(sharedPref: SharedPreferences?): List<StreamCenterStremioAddon> {
            val raw = sharedPref?.getString(PREF_STREMIO_ADDONS, null) ?: return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val manifestUrl = item.optString("manifestUrl").trim()
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    if (key.isBlank() || manifestUrl.isBlank() || id.isBlank() || name.isBlank()) continue
                    add(
                        StreamCenterStremioAddon(
                            key = key,
                            manifestUrl = manifestUrl,
                            id = id,
                            name = name,
                            version = item.optString("version").trim().takeIf(String::isNotBlank),
                            logoUrl = item.optString("logo").trim().takeIf(String::isNotBlank),
                            types = item.optStringList("types"),
                            idPrefixes = item.optStringList("idPrefixes"),
                            resources = item.optJSONArray("resources")?.let { resources ->
                                buildList {
                                    for (resourceIndex in 0 until resources.length()) {
                                        val resource = resources.optJSONObject(resourceIndex) ?: continue
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
                            }.orEmpty(),
                        ),
                    )
                }
            }.distinctBy { it.key }
        }

        internal fun saveStremioAddon(sharedPref: SharedPreferences?, addon: StreamCenterStremioAddon) {
            val existing = getStremioAddons(sharedPref).toMutableList()
            val index = existing.indexOfFirst { it.key == addon.key }
            if (index >= 0) existing[index] = addon else existing += addon
            sharedPref?.edit()?.putString(
                PREF_STREMIO_ADDONS,
                JSONArray().apply { existing.forEach { put(it.toPreferenceJson()) } }.toString(),
            )?.apply()
        }

        internal suspend fun refreshStremioAddonManifests(
            sharedPref: SharedPreferences?,
        ): StreamCenterStremioManifestRefreshResult {
            val prefs = sharedPref
                ?: return StreamCenterStremioManifestRefreshResult(total = 0, updated = 0)
            val originals = getStremioAddons(prefs)
            if (originals.isEmpty()) {
                return StreamCenterStremioManifestRefreshResult(total = 0, updated = 0)
            }
            val semaphore = Semaphore(STREMIO_MANIFEST_REFRESH_CONCURRENCY)
            val fetched = supervisorScope {
                originals.map { original ->
                    async(Dispatchers.IO) {
                        original to semaphore.withPermit {
                            runCatching {
                                StreamCenterStremioAddonClient.readManifest(original.manifestUrl)
                            }
                        }
                    }
                }.awaitAll()
            }
            var updated = 0
            fetched.forEach { (original, result) ->
                val replacement = result.getOrNull() ?: return@forEach
                val current = getStremioAddons(prefs).firstOrNull { it.key == original.key }
                    ?: return@forEach
                if (current.manifestUrl != original.manifestUrl) return@forEach
                if (replaceStremioAddon(prefs, current, replacement)) updated += 1
            }
            return StreamCenterStremioManifestRefreshResult(
                total = originals.size,
                updated = updated,
            )
        }

        internal fun replaceStremioAddon(
            sharedPref: SharedPreferences?,
            previous: StreamCenterStremioAddon,
            replacement: StreamCenterStremioAddon,
        ): Boolean {
            val prefs = sharedPref ?: return false
            val existing = getStremioAddons(prefs).toMutableList()
            val index = existing.indexOfFirst { it.key == previous.key }
            if (index < 0) return false
            val collisionIndex = existing.indexOfFirst { it.key == replacement.key }
            val mergesExisting = replacement.key != previous.key && collisionIndex >= 0
            if (mergesExisting) {
                val collision = existing[collisionIndex]
                if (collision.id != replacement.id || collision.manifestUrl != replacement.manifestUrl) {
                    return false
                }
            }

            val wasEnabled = isStremioAddonEnabled(prefs, previous.key) ||
                (mergesExisting && isStremioAddonEnabled(prefs, replacement.key))
            if (mergesExisting) {
                existing[collisionIndex] = replacement
                existing.removeAt(index)
            } else {
                existing[index] = replacement
            }
            prefs.edit().apply {
                putString(
                    PREF_STREMIO_ADDONS,
                    JSONArray().apply { existing.forEach { put(it.toPreferenceJson()) } }.toString(),
                )
                if (replacement.key != previous.key) {
                    putBoolean(stremioEnabledPrefKey(replacement.key), wasEnabled)
                    remove(stremioEnabledPrefKey(previous.key))
                    val updatedPriority = getSourcePriorityOrder(prefs)
                        .map { key -> if (key == previous.key) replacement.key else key }
                        .distinct()
                    putString(PREF_SOURCE_PRIORITY, updatedPriority.joinToString(","))
                }
            }.apply()
            return true
        }

        fun removeStremioAddon(sharedPref: SharedPreferences?, addonKey: String) {
            val prefs = sharedPref ?: return
            val retained = getStremioAddons(prefs).filterNot { it.key == addonKey }
            prefs.edit().apply {
                if (retained.isEmpty()) remove(PREF_STREMIO_ADDONS)
                else putString(
                    PREF_STREMIO_ADDONS,
                    JSONArray().apply { retained.forEach { put(it.toPreferenceJson()) } }.toString(),
                )
                remove(stremioEnabledPrefKey(addonKey))
                val order = getSourcePriorityOrder(prefs).filterNot { it == addonKey }
                putString(PREF_SOURCE_PRIORITY, order.joinToString(","))
            }.apply()
        }

        fun isStremioAddonEnabled(sharedPref: SharedPreferences?, addonKey: String): Boolean {
            return sharedPref?.getBoolean(stremioEnabledPrefKey(addonKey), true) ?: true
        }

        fun setStremioAddonEnabled(sharedPref: SharedPreferences?, addonKey: String, enabled: Boolean) {
            sharedPref?.edit()?.putBoolean(stremioEnabledPrefKey(addonKey), enabled)?.apply()
        }

        private fun normalizeSourceUrl(url: String): String {
            val cleaned = url.trim().trimEnd('/')
            if (cleaned.isBlank()) return ""
            return if ("://" in cleaned) cleaned else "https://$cleaned"
        }

        fun getSourceBaseUrl(sharedPref: SharedPreferences?, prefKey: String): String {
            val source = streamingSources.firstOrNull { it.key == prefKey }
                ?: return ""
            val stored = sharedPref
                ?.getString(source.urlPrefKey, null)
                ?.let(::normalizeSourceUrl)
                ?.takeIf { it.isNotBlank() }
            return stored ?: source.defaultUrl.trimEnd('/')
        }

        fun setSourceBaseUrl(sharedPref: SharedPreferences?, prefKey: String, url: String) {
            val source = streamingSources.firstOrNull { it.key == prefKey } ?: return
            val cleaned = normalizeSourceUrl(url)
            sharedPref?.edit()?.apply {
                if (cleaned.isBlank() || cleaned == source.defaultUrl.trimEnd('/')) {
                    remove(source.urlPrefKey)
                } else {
                    putString(source.urlPrefKey, cleaned)
                }
            }?.apply()
        }

        fun resetSourceUrls(sharedPref: SharedPreferences?) {
            sharedPref?.edit()?.apply {
                streamingSources.forEach { remove(it.urlPrefKey) }
            }?.apply()
        }

        fun getSourcePriorityOrder(sharedPref: SharedPreferences?): List<String> {
            val defaultOrder = streamingSources.map { it.key } + getStremioAddons(sharedPref).map { it.key }
            val stored = sharedPref
                ?.getString(PREF_SOURCE_PRIORITY, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { key -> key in defaultOrder }
                ?.distinct()
                .orEmpty()
            return stored + defaultOrder.filterNot { it in stored }
        }

        fun setSourcePriorityOrder(sharedPref: SharedPreferences?, order: List<String>) {
            val validKeys = streamingSources.map { it.key } + getStremioAddons(sharedPref).map { it.key }
            val normalized = order.filter { it in validKeys }.distinct() + validKeys.filterNot { it in order }
            sharedPref?.edit()?.putString(PREF_SOURCE_PRIORITY, normalized.joinToString(","))?.apply()
        }

        private fun stremioEnabledPrefKey(addonKey: String): String =
            PREF_STREMIO_ADDON_ENABLED_PREFIX + addonKey

        private const val STREMIO_MANIFEST_REFRESH_CONCURRENCY = 4

        private fun StreamCenterStremioAddon.toPreferenceJson(): JSONObject = JSONObject().apply {
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
        }

        private fun JSONObject.optStringList(key: String): List<String> =
            optJSONArray(key)?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.orEmpty()

        fun isSourceUrlAutoUpdateEnabled(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_AUTO_UPDATE_SOURCE_URLS, true) ?: true
        }

        fun getAnilistRequestsPerMinute(sharedPref: SharedPreferences?): Int {
            return (sharedPref?.getInt(PREF_ANILIST_RPM, DEFAULT_ANILIST_RPM) ?: DEFAULT_ANILIST_RPM)
                .coerceIn(MIN_ANILIST_RPM, MAX_ANILIST_RPM)
        }

        fun getAnilistMinIntervalMs(sharedPref: SharedPreferences?): Long {
            return 60_000L / getAnilistRequestsPerMinute(sharedPref).coerceAtLeast(1)
        }

        fun isHomeSectionEnabled(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): Boolean {
            return sharedPref?.getBoolean(sectionEnabledKey(section.key), section.defaultEnabled)
                ?: section.defaultEnabled
        }

        private const val DAY_PLACEHOLDER = "%Giorno%"
        private const val NUMERIC_DAY_PLACEHOLDER = "%GiornoNumerico%"
        private const val DATE_PLACEHOLDER = "%Data%"
        private const val MONTH_PLACEHOLDER = "%Mese%"
        private const val NUMERIC_MONTH_PLACEHOLDER = "%MeseNumerico%"
        private const val YEAR_PLACEHOLDER = "%Anno%"
        private const val WEEK_PLACEHOLDER = "%Settimana%"
        private const val CHANNELS_PLACEHOLDER = "%Canali%"
        private const val SHORT_DAY_PLACEHOLDER = "%d%"
        private const val SHORT_WEEKDAY_PLACEHOLDER = "%ddd%"
        private const val FULL_WEEKDAY_PLACEHOLDER = "%dddd%"
        private const val SHORT_MONTH_PLACEHOLDER = "%m%"
        private const val PADDED_MONTH_PLACEHOLDER = "%mm%"
        private const val SHORT_MONTH_NAME_PLACEHOLDER = "%mmm%"
        private const val FULL_MONTH_NAME_PLACEHOLDER = "%mmmm%"
        private const val SHORT_YEAR_PLACEHOLDER = "%yy%"
        private const val FULL_YEAR_PLACEHOLDER = "%yyyy%"
        private val dayPlaceholderPattern = Regex(DAY_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val numericDayPlaceholderPattern = Regex(NUMERIC_DAY_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val datePlaceholderPattern = Regex(DATE_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val monthPlaceholderPattern = Regex(MONTH_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val numericMonthPlaceholderPattern = Regex(NUMERIC_MONTH_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val yearPlaceholderPattern = Regex(YEAR_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val weekPlaceholderPattern = Regex(WEEK_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val channelsPlaceholderPattern = Regex(CHANNELS_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val shortDayPlaceholderPattern = Regex(SHORT_DAY_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val paddedDayPlaceholderPattern = Regex("%dd[%&]", RegexOption.IGNORE_CASE)
        private val shortWeekdayPlaceholderPattern = Regex(SHORT_WEEKDAY_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val fullWeekdayPlaceholderPattern = Regex(FULL_WEEKDAY_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val shortMonthPlaceholderPattern = Regex(SHORT_MONTH_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val paddedMonthPlaceholderPattern = Regex(PADDED_MONTH_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val shortMonthNamePlaceholderPattern = Regex(SHORT_MONTH_NAME_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val fullMonthNamePlaceholderPattern = Regex(FULL_MONTH_NAME_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val shortYearPlaceholderPattern = Regex(SHORT_YEAR_PLACEHOLDER, RegexOption.IGNORE_CASE)
        private val fullYearPlaceholderPattern = Regex(FULL_YEAR_PLACEHOLDER, RegexOption.IGNORE_CASE)

        fun getHomeSectionTitleTemplate(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): String {
            val title = sharedPref
                ?.getString(sectionTitleKey(section.key), null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: getDefaultHomeSectionTitle(section.key)
            return if (
                section.key == "anime_calendar" &&
                (
                    (title.startsWith("Anime: calendario (") && title.endsWith(")")) ||
                        title.equals("Anime - %Giorno% Calendario", ignoreCase = true)
                    )
            ) {
                getDefaultHomeSectionTitle(section.key)
            } else {
                title
            }
        }

        fun getHomeSectionTitle(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): String {
            val template = getHomeSectionTitleTemplate(sharedPref, section)
            val calendar = Calendar.getInstance(Locale.ITALY).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
            }
            val channelCount = section.key
                .takeIf { it.startsWith(IPTV_CUSTOM_SECTION_PREFIX) }
                ?.let { getIptvSectionChannelIds(sharedPref, it).size }
            return resolveHomeTitlePlaceholders(template, calendar, channelCount)
        }

        internal fun resolveHomeTitlePlaceholders(
            template: String,
            calendar: Calendar,
            channelCount: Int? = null,
        ): String {
            val numericDay = calendar.get(Calendar.DAY_OF_MONTH)
            val numericMonth = calendar.get(Calendar.MONTH) + 1
            val year = calendar.get(Calendar.YEAR)
            val weekday = italianWeekdayName(calendar)
            val month = italianMonthName(calendar.get(Calendar.MONTH))
            val replacements = mutableListOf(
                dayPlaceholderPattern to weekday,
                numericDayPlaceholderPattern to numericDay.toString(),
                datePlaceholderPattern to String.format(
                    Locale.ITALY,
                    "%02d/%02d/%04d",
                    numericDay,
                    numericMonth,
                    year,
                ),
                monthPlaceholderPattern to month,
                numericMonthPlaceholderPattern to String.format(Locale.ITALY, "%02d", numericMonth),
                yearPlaceholderPattern to year.toString(),
                weekPlaceholderPattern to calendar.get(Calendar.WEEK_OF_YEAR).toString(),
                shortDayPlaceholderPattern to numericDay.toString(),
                paddedDayPlaceholderPattern to String.format(Locale.ITALY, "%02d", numericDay),
                shortWeekdayPlaceholderPattern to weekday.take(3),
                fullWeekdayPlaceholderPattern to weekday,
                shortMonthPlaceholderPattern to numericMonth.toString(),
                paddedMonthPlaceholderPattern to String.format(Locale.ITALY, "%02d", numericMonth),
                shortMonthNamePlaceholderPattern to month.take(3),
                fullMonthNamePlaceholderPattern to month,
                shortYearPlaceholderPattern to String.format(Locale.ITALY, "%02d", year % 100),
                fullYearPlaceholderPattern to year.toString(),
            )
            channelCount?.let { replacements += channelsPlaceholderPattern to it.toString() }
            return replacements.fold(template) { resolvedTitle, (pattern, value) ->
                pattern.replace(resolvedTitle, value)
            }
        }

        fun getHomeSectionCount(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): Int {
            return (sharedPref?.getInt(sectionCountKey(section.key), section.defaultCount)
                ?: section.defaultCount)
                .coerceIn(MIN_HOME_COUNT, MAX_HOME_COUNT)
        }

        fun getHomeSectionCount(sharedPref: SharedPreferences?, data: String): Int {
            val normalizedData = data.substringBefore("&page=").substringBefore("?page=")
            val section = getAllHomeSections(sharedPref).firstOrNull {
                it.data == data || normalizedData.startsWith(it.data.substringBefore("&page=").substringBefore("?page="))
            } ?: return DEFAULT_HOME_COUNT
            return getHomeSectionCount(sharedPref, section)
        }

        fun getConfiguredHomeSections(sharedPref: SharedPreferences?): List<StreamCenterConfiguredHomeSection> {
            val allSections = getAllHomeSections(sharedPref)
            val byKey = allSections.associateBy { it.key }
            val orderedKeys = sharedPref
                ?.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it in byKey }
                ?.distinct()
                .orEmpty()
            val normalizedOrder = orderedKeys + allSections.map { it.key }.filterNot { it in orderedKeys }

            return normalizedOrder
                .mapNotNull { byKey[it] }
                .filter { isHomeSectionEnabled(sharedPref, it) }
                .filter { isHomeCategoryEnabled(sharedPref, homeSectionCategoryKey(it)) }
                .sortedBy { homeSectionCategoryRank(sharedPref, it) }
                .map { section ->
                    StreamCenterConfiguredHomeSection(
                        definition = section,
                        title = getHomeSectionTitle(sharedPref, section),
                    )
                }
        }

        fun getBuildInfoText(): String {
            val rawCommit = BuildConfig.BUILD_COMMIT_SHA.trim()
            val rawBuildCompletedAt = BuildConfig.BUILD_COMPLETED_AT_ROME.trim()
            val shortCommit = rawCommit.takeIf { it.isNotEmpty() && it != "unknown" }?.take(7)
            val buildCompletedAt = rawBuildCompletedAt.replace(' ', ' ')
            return when {
                shortCommit != null && buildCompletedAt.isNotEmpty() ->
                    "Commit $shortCommit\nBuild $buildCompletedAt"
                shortCommit != null -> "Commit $shortCommit"
                else -> "???"
            }
        }

        fun sectionEnabledKey(sectionKey: String): String = "home_${sectionKey}_enabled"
        fun sectionTitleKey(sectionKey: String): String = "home_${sectionKey}_title"
        fun sectionCountKey(sectionKey: String): String = "home_${sectionKey}_count"
        fun homeCategoryEnabledKey(categoryKey: String): String = "home_category_${categoryKey}_enabled"

        fun defaultHomeOrder(): String = homeSections.joinToString(",") { it.key }

        fun homeSectionCategoryKey(section: StreamCenterHomeSectionDefinition): String {
            return when {
                section.key.startsWith("live_") -> "live"
                section.key.startsWith("anime_") -> "anime"
                section.key.startsWith("tv_") -> "tv"
                section.key.startsWith("movie_") -> "movie"
                section.key.startsWith(TRACKING_CUSTOM_SECTION_PREFIX) -> "tracking"
                else -> "other"
            }
        }

        fun homeSectionCategory(section: StreamCenterHomeSectionDefinition): String {
            return when (homeSectionCategoryKey(section)) {
                "anime" -> "Anime"
                "tv" -> "Serie TV"
                "movie" -> "Film"
                "tracking" -> "Tracciamento"
                "live" -> "TV"
                else -> "Altro"
            }
        }

        fun getHomeCategoryOrder(sharedPref: SharedPreferences?): List<String> {
            val stored = sharedPref
                ?.getString(PREF_HOME_CATEGORY_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it in homeCategories }
                ?.distinct()
                .orEmpty()
            return stored + homeCategories.filterNot { it in stored }
        }

        fun isHomeCategoryEnabled(sharedPref: SharedPreferences?, categoryKey: String): Boolean {
            return sharedPref?.getBoolean(homeCategoryEnabledKey(categoryKey), true) ?: true
        }

        fun homeSectionCategoryRank(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): Int {
            return getHomeCategoryOrder(sharedPref).indexOf(homeSectionCategoryKey(section))
                .takeIf { it >= 0 }
                ?: Int.MAX_VALUE
        }

        fun getDefaultHomeSectionTitle(sectionKey: String): String {
            return when (sectionKey) {
                "anime_calendar" -> "Anime - Calendario (%Giorno%)"
                "anime_latest" -> "Anime: ultimi episodi"
                "anime_popular" -> "Anime: popolari"
                "tv_trending" -> "Serie TV: titoli del momento"
                "tv_latest" -> "Serie TV: aggiunte di recente"
                "tv_top10" -> "Serie TV: top 10 di oggi"
                "tv_upcoming" -> "Serie TV: in arrivo"
                "movie_trending" -> "Film: titoli del momento"
                "movie_latest" -> "Film: aggiunti di recente"
                "movie_top10" -> "Film: top 10 di oggi"
                "movie_genre_azione" -> "Film: azione"
                "movie_genre_commedia" -> "Film: commedia"
                "movie_genre_horror" -> "Film: horror"
                "movie_genre_fantascienza" -> "Film: fantascienza"
                "movie_genre_animazione" -> "Film: animazione"
                "tv_genre_azione" -> "Serie TV: azione e avventura"
                "tv_genre_commedia" -> "Serie TV: commedia"
                "tv_genre_crime" -> "Serie TV: crime"
                "tv_genre_scifi" -> "Serie TV: sci-fi e fantasy"
                "tv_genre_dramma" -> "Serie TV: dramma"
                else -> if (sectionKey.startsWith(TRACKING_CUSTOM_SECTION_PREFIX)) {
                    "Lista di tracciamento"
                } else if (sectionKey.startsWith(IPTV_CUSTOM_SECTION_PREFIX)) {
                    "TV - i miei canali"
                } else if (sectionKey.startsWith(ANIME_CUSTOM_SECTION_PREFIX)) {
                    "Anime: sezione personalizzata"
                } else {
                    sectionKey
                }
            }
        }

        fun migrateLegacyIptvFavorites(prefs: SharedPreferences) {
            val legacyChannels = prefs.getStringSet(PREF_IPTV_FAVORITE_CHANNELS, emptySet()).orEmpty()
            if (legacyChannels.isEmpty()) {
                if (prefs.contains(PREF_IPTV_FAVORITE_CHANNELS)) {
                    prefs.edit().remove(PREF_IPTV_FAVORITE_CHANNELS).apply()
                }
                return
            }
            val legacyTitle = prefs.getString(sectionTitleKey("live_favorites"), null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "TV - i miei canali"
            val sectionKey = createIptvCustomSection(prefs, legacyTitle) ?: return
            val order = prefs.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.joinToString(",") { key ->
                    if (key.trim() == "live_favorites") sectionKey else key.trim()
                }
            prefs.edit().apply {
                putStringSet(iptvSectionChannelsKey(sectionKey), legacyChannels)
                putBoolean(
                    sectionEnabledKey(sectionKey),
                    prefs.getBoolean(sectionEnabledKey("live_favorites"), true),
                )
                if (order != null) putString(PREF_HOME_ORDER, order)
                remove(PREF_IPTV_FAVORITE_CHANNELS)
                remove(sectionEnabledKey("live_favorites"))
                remove(sectionTitleKey("live_favorites"))
                remove(sectionCountKey("live_favorites"))
            }.apply()
        }

        fun migrateTvUpcomingHomeSection(prefs: SharedPreferences) {
            val knownKeys = getAllHomeSections(prefs).map { it.key }.toSet()
            val currentOrder = prefs.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it in knownKeys }
                ?.distinct()
                .orEmpty()
            if (currentOrder.isEmpty()) return
            if ("tv_upcoming" in currentOrder) return

            val insertAt = currentOrder.indexOf("tv_top10").let { index ->
                if (index >= 0) index + 1 else currentOrder.size
            }
            val updatedOrder = currentOrder.toMutableList().apply {
                add(insertAt, "tv_upcoming")
            }
            prefs.edit().putString(PREF_HOME_ORDER, updatedOrder.joinToString(",")).apply()
        }

        fun migrateTrackingHomeCategory(prefs: SharedPreferences) {
            val stored = prefs.getString(PREF_HOME_CATEGORY_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it in homeCategories }
                ?.distinct()
                .orEmpty()
            if (stored.isEmpty()) return
            val updated = stored.toMutableList()
            if ("tracking" in updated) {
                val isPreviousDefault = updated == listOf("anime", "tv", "movie", "tracking", "live") ||
                    updated == listOf("anime", "tv", "tracking", "movie", "live")
                if (!isPreviousDefault) return
                updated.remove("tracking")
                updated.add(updated.indexOf("live").let { if (it >= 0) it + 1 else updated.size }, "tracking")
            } else {
                val insertAt = updated.indexOf("live").let { liveIndex ->
                    if (liveIndex >= 0) liveIndex + 1 else updated.indexOf("movie").coerceAtLeast(0)
                }
                updated.add(insertAt, "tracking")
            }
            prefs.edit().putString(PREF_HOME_CATEGORY_ORDER, updated.joinToString(",")).apply()
        }

        fun iptvSectionChannelsKey(sectionKey: String): String = "iptvSectionChannels_$sectionKey"

        fun getIptvCustomSectionKeys(sharedPref: SharedPreferences?): List<String> {
            return sharedPref?.getString(PREF_IPTV_CUSTOM_SECTIONS, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.startsWith(IPTV_CUSTOM_SECTION_PREFIX) }
                ?.distinct()
                .orEmpty()
        }

        fun iptvCustomSectionDefinition(sectionKey: String): StreamCenterHomeSectionDefinition {
            return StreamCenterHomeSectionDefinition(
                key = sectionKey,
                data = "iptv:section:$sectionKey",
                defaultCount = MAX_HOME_COUNT,
                defaultEnabled = true,
            )
        }

        fun createIptvCustomSection(sharedPref: SharedPreferences?, name: String): String? {
            val prefs = sharedPref ?: return null
            val counter = prefs.getInt(PREF_IPTV_CUSTOM_SECTION_COUNTER, 0) + 1
            val sectionKey = "$IPTV_CUSTOM_SECTION_PREFIX$counter"
            val keys = getIptvCustomSectionKeys(prefs) + sectionKey
            prefs.edit()
                .putInt(PREF_IPTV_CUSTOM_SECTION_COUNTER, counter)
                .putString(PREF_IPTV_CUSTOM_SECTIONS, keys.joinToString(","))
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() }
                        ?: getDefaultHomeSectionTitle(sectionKey),
                )
                .putBoolean(sectionEnabledKey(sectionKey), true)
                .apply()
            return sectionKey
        }

        fun deleteIptvCustomSection(sharedPref: SharedPreferences?, sectionKey: String) {
            val prefs = sharedPref ?: return
            val keys = getIptvCustomSectionKeys(prefs).filterNot { it == sectionKey }
            val order = prefs.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filterNot { it == sectionKey }
                ?.joinToString(",")
            prefs.edit().apply {
                putString(PREF_IPTV_CUSTOM_SECTIONS, keys.joinToString(","))
                if (order != null) putString(PREF_HOME_ORDER, order)
                remove(iptvSectionChannelsKey(sectionKey))
                remove(iptvSectionOrderKey(sectionKey))
                remove(sectionEnabledKey(sectionKey))
                remove(sectionTitleKey(sectionKey))
                remove(sectionCountKey(sectionKey))
            }.apply()
        }

        fun getIptvSectionChannelIds(sharedPref: SharedPreferences?, sectionKey: String): Set<String> {
            return sharedPref?.getStringSet(iptvSectionChannelsKey(sectionKey), emptySet())
                ?.toSet()
                .orEmpty()
        }

        fun iptvSectionOrderKey(sectionKey: String): String = "iptvSectionOrder_$sectionKey"

        fun getIptvSectionChannelOrder(
            sharedPref: SharedPreferences?,
            sectionKey: String,
        ): List<String> {
            val ids = getIptvSectionChannelIds(sharedPref, sectionKey)
            val stored = sharedPref?.getString(iptvSectionOrderKey(sectionKey), null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val known = stored.filter { it in ids }
            return known + ids.filterNot { it in known }
        }

        fun setIptvSectionChannels(
            sharedPref: SharedPreferences?,
            sectionKey: String,
            orderedIds: List<String>,
        ) {
            sharedPref?.edit()
                ?.putStringSet(iptvSectionChannelsKey(sectionKey), orderedIds.toSet())
                ?.putString(iptvSectionOrderKey(sectionKey), orderedIds.joinToString(","))
                ?.apply()
        }

        fun getAllIptvSelectedChannelIds(sharedPref: SharedPreferences?): Set<String> {
            return getIptvCustomSectionKeys(sharedPref)
                .flatMap { getIptvSectionChannelIds(sharedPref, it) }
                .toSet()
        }

        private fun trackingSelectionKey(sectionKey: String): String = "trackingSelection_$sectionKey"

        fun getTrackingCustomSectionKeys(sharedPref: SharedPreferences?): List<String> {
            return sharedPref?.getString(PREF_TRACKING_CUSTOM_SECTIONS, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.startsWith(TRACKING_CUSTOM_SECTION_PREFIX) }
                ?.distinct()
                .orEmpty()
        }

        fun trackingCustomSectionDefinition(sectionKey: String): StreamCenterHomeSectionDefinition {
            return StreamCenterHomeSectionDefinition(
                key = sectionKey,
                data = "tracking:$sectionKey",
                defaultCount = DEFAULT_HOME_COUNT,
                defaultEnabled = true,
            )
        }

        fun getTrackingListConfig(
            sharedPref: SharedPreferences?,
            sectionKey: String,
        ): StreamCenterTrackingListConfig? {
            if (!sectionKey.startsWith(TRACKING_CUSTOM_SECTION_PREFIX)) return null
            val values = sharedPref?.getString(trackingSelectionKey(sectionKey), null)
                ?.split("|", limit = 2)
                ?: return null
            val service = trackingServices.firstOrNull { it.key == values.getOrNull(0) } ?: return null
            val status = service.statuses.firstOrNull { it.key == values.getOrNull(1) } ?: return null
            return StreamCenterTrackingListConfig(service, status)
        }

        fun createTrackingCustomSection(
            sharedPref: SharedPreferences?,
            service: StreamCenterTrackingService,
            status: StreamCenterTrackingListStatus,
            name: String,
        ): String? {
            val prefs = sharedPref ?: return null
            if (status !in service.statuses) return null
            val counter = prefs.getInt(PREF_TRACKING_CUSTOM_SECTION_COUNTER, 0) + 1
            val sectionKey = "$TRACKING_CUSTOM_SECTION_PREFIX$counter"
            val keys = getTrackingCustomSectionKeys(prefs) + sectionKey
            val defaultName = "${service.title} - ${status.title}"
            prefs.edit()
                .putInt(PREF_TRACKING_CUSTOM_SECTION_COUNTER, counter)
                .putString(PREF_TRACKING_CUSTOM_SECTIONS, keys.joinToString(","))
                .putString(trackingSelectionKey(sectionKey), "${service.key}|${status.key}")
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() } ?: defaultName,
                )
                .putInt(sectionCountKey(sectionKey), DEFAULT_HOME_COUNT)
                .putBoolean(sectionEnabledKey(sectionKey), true)
                .apply()
            return sectionKey
        }

        fun deleteTrackingCustomSection(sharedPref: SharedPreferences?, sectionKey: String) {
            val prefs = sharedPref ?: return
            val keys = getTrackingCustomSectionKeys(prefs).filterNot { it == sectionKey }
            val order = prefs.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filterNot { it == sectionKey }
                ?.joinToString(",")
            prefs.edit().apply {
                putString(PREF_TRACKING_CUSTOM_SECTIONS, keys.joinToString(","))
                if (order != null) putString(PREF_HOME_ORDER, order)
                remove(trackingSelectionKey(sectionKey))
                remove(sectionEnabledKey(sectionKey))
                remove(sectionTitleKey(sectionKey))
                remove(sectionCountKey(sectionKey))
            }.apply()
        }

        private fun animeCustomFiltersKey(sectionKey: String): String = "animeCustomFilters_$sectionKey"

        fun getAnimeCustomSectionKeys(sharedPref: SharedPreferences?): List<String> {
            return sharedPref?.getString(PREF_ANIME_CUSTOM_SECTIONS, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.startsWith(ANIME_CUSTOM_SECTION_PREFIX) }
                ?.distinct()
                .orEmpty()
        }

        fun animeCustomSectionDefinition(sectionKey: String): StreamCenterHomeSectionDefinition {
            return StreamCenterHomeSectionDefinition(
                key = sectionKey,
                data = "au:archive:$sectionKey",
                defaultCount = DEFAULT_HOME_COUNT,
            )
        }

        fun createAnimeCustomSection(
            sharedPref: SharedPreferences?,
            filters: StreamCenterAnimeArchiveFilters,
            count: Int,
            name: String,
        ): String? {
            val prefs = sharedPref ?: return null
            val counter = prefs.getInt(PREF_ANIME_CUSTOM_SECTION_COUNTER, 0) + 1
            val sectionKey = "$ANIME_CUSTOM_SECTION_PREFIX$counter"
            val keys = getAnimeCustomSectionKeys(prefs) + sectionKey
            prefs.edit()
                .putInt(PREF_ANIME_CUSTOM_SECTION_COUNTER, counter)
                .putString(PREF_ANIME_CUSTOM_SECTIONS, keys.joinToString(","))
                .putString(animeCustomFiltersKey(sectionKey), animeFiltersToJson(filters))
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() } ?: getDefaultHomeSectionTitle(sectionKey),
                )
                .putInt(sectionCountKey(sectionKey), count.coerceIn(MIN_HOME_COUNT, MAX_HOME_COUNT))
                .putBoolean(sectionEnabledKey(sectionKey), true)
                .apply()
            return sectionKey
        }

        fun getAnimeCustomSectionFilters(
            sharedPref: SharedPreferences?,
            sectionKey: String,
        ): StreamCenterAnimeArchiveFilters? {
            if (!sectionKey.startsWith(ANIME_CUSTOM_SECTION_PREFIX)) return null
            val raw = sharedPref?.getString(animeCustomFiltersKey(sectionKey), null) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                StreamCenterAnimeArchiveFilters(
                    genreId = json.optInt("genreId").takeIf { it > 0 },
                    year = json.optInt("year").takeIf { it > 0 },
                    order = json.optString("order").takeIf { it.isNotBlank() },
                    status = json.optString("status").takeIf { it.isNotBlank() },
                    type = json.optString("type").takeIf { it.isNotBlank() },
                    season = json.optString("season").takeIf { it.isNotBlank() },
                    dubbed = json.optBoolean("dubbed", false),
                )
            }.getOrNull()
        }

        fun updateAnimeCustomSection(
            sharedPref: SharedPreferences?,
            sectionKey: String,
            filters: StreamCenterAnimeArchiveFilters,
            count: Int,
            name: String,
        ): Boolean {
            val prefs = sharedPref ?: return false
            if (sectionKey !in getAnimeCustomSectionKeys(prefs)) return false
            prefs.edit()
                .putString(animeCustomFiltersKey(sectionKey), animeFiltersToJson(filters))
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() } ?: getDefaultHomeSectionTitle(sectionKey),
                )
                .putInt(sectionCountKey(sectionKey), count.coerceIn(MIN_HOME_COUNT, MAX_HOME_COUNT))
                .apply()
            return true
        }

        private fun animeFiltersToJson(filters: StreamCenterAnimeArchiveFilters): String = JSONObject().apply {
            filters.genreId?.let { put("genreId", it) }
            filters.year?.let { put("year", it) }
            filters.order?.let { put("order", it) }
            filters.status?.let { put("status", it) }
            filters.type?.let { put("type", it) }
            filters.season?.let { put("season", it) }
            put("dubbed", filters.dubbed)
        }.toString()

        fun deleteAnimeCustomSection(sharedPref: SharedPreferences?, sectionKey: String) {
            val prefs = sharedPref ?: return
            val keys = getAnimeCustomSectionKeys(prefs).filterNot { it == sectionKey }
            val order = prefs.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filterNot { it == sectionKey }
                ?.joinToString(",")
            prefs.edit().apply {
                putString(PREF_ANIME_CUSTOM_SECTIONS, keys.joinToString(","))
                if (order != null) putString(PREF_HOME_ORDER, order)
                remove(animeCustomFiltersKey(sectionKey))
                remove(sectionEnabledKey(sectionKey))
                remove(sectionTitleKey(sectionKey))
                remove(sectionCountKey(sectionKey))
            }.apply()
        }

        fun getAllHomeSections(sharedPref: SharedPreferences?): List<StreamCenterHomeSectionDefinition> {
            return homeSections +
                getAnimeCustomSectionKeys(sharedPref).map(::animeCustomSectionDefinition) +
                getTrackingCustomSectionKeys(sharedPref).map(::trackingCustomSectionDefinition) +
                getIptvCustomSectionKeys(sharedPref).map(::iptvCustomSectionDefinition)
        }

        fun getIptvRegion(sharedPref: SharedPreferences?): String {
            val stored = sharedPref?.getString(PREF_IPTV_REGION, "italy").orEmpty()
            return stored.takeIf { key -> StreamCenterIptv.regions.any { it.key == key } } ?: "italy"
        }

        fun setIptvRegion(sharedPref: SharedPreferences?, regionKey: String) {
            sharedPref?.edit()?.putString(PREF_IPTV_REGION, regionKey)?.apply()
        }

        private fun italianWeekdayName(calendar: Calendar): String {
            return when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "Lunedi"
                Calendar.TUESDAY -> "Martedi"
                Calendar.WEDNESDAY -> "Mercoledi"
                Calendar.THURSDAY -> "Giovedi"
                Calendar.FRIDAY -> "Venerdi"
                Calendar.SATURDAY -> "Sabato"
                else -> "Domenica"
            }
        }

        private fun italianMonthName(month: Int): String {
            return listOf(
                "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
                "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
            ).getOrElse(month) { "" }
        }
    }

    private var sharedPref: SharedPreferences? = null
    private val registeredCatalogKeys = mutableSetOf<String>()

    private fun registerConfiguredCatalogs() {
        val preferences = sharedPref ?: return
        if (!isHomeCategoryEnabled(preferences, StreamCenterCatalogs.CATEGORY_KEY)) return
        StreamCenterCatalogs.configuredCatalogs(preferences).forEach { catalog ->
            if (registeredCatalogKeys.add(catalog.key)) {
                registerMainAPI(StreamCenter(preferences, catalogDefinition = catalog))
            }
        }
    }

    override fun load(context: Context) {
        sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        activeSharedPref = sharedPref
        activeContext = context.applicationContext
        activePlugin = this

        sharedPref?.let { prefs ->
            if (prefs.getInt(PREF_HOME_LAYOUT_VERSION, 0) < CURRENT_HOME_LAYOUT_VERSION) {
                prefs.edit()
                    .putString(PREF_HOME_ORDER, defaultHomeOrder())
                    .putInt(PREF_HOME_LAYOUT_VERSION, CURRENT_HOME_LAYOUT_VERSION)
                    .apply()
            }
            if (prefs.contains("stremioSections") || prefs.contains("stremioSectionsMigrationVersion")) {
                prefs.edit()
                    .remove("stremioSections")
                    .remove("stremioSectionsMigrationVersion")
                    .apply()
            }
            migrateLegacyIptvFavorites(prefs)
            migrateTvUpcomingHomeSection(prefs)
            migrateTrackingHomeCategory(prefs)
        }

        registerMainAPI(StreamCenter(sharedPref))
        registerMainAPI(StreamCenter(sharedPref, StreamCenter.SEARCH_SECTION_MOVIES))
        registerMainAPI(StreamCenter(sharedPref, StreamCenter.SEARCH_SECTION_SERIES))
        registerMainAPI(StreamCenter(sharedPref, StreamCenter.SEARCH_SECTION_ANIME))
        registerMainAPI(StreamCenter(sharedPref, StreamCenter.SEARCH_SECTION_LIVE))
        registerConfiguredCatalogs()

        openSettings = { ctx ->
            if (ctx is AppCompatActivity) {
                activeSharedPref = sharedPref
                StreamCenterSettings().show(ctx.supportFragmentManager, "StreamCenterSettings")
            }
        }
    }
}
