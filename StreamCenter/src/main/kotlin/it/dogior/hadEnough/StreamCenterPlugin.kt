package it.dogior.hadEnough

import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.settings.*
import it.dogior.hadEnough.iptv.StreamCenterIptv
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.StreamCenterTorrentPreferences
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterVpnGuard

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
    val genreIds: List<Int> = emptyList(),
    val year: Int? = null,
    val order: String? = null,
    val status: String? = null,
    val type: String? = null,
    val season: String? = null,
    val dubbed: Boolean = false,
) {
    val selectedGenreIds: List<Int>
        get() = (genreIds + listOfNotNull(genreId)).filter { it > 0 }.distinct()
}

data class StreamCenterTvArchiveFilters(
    val genreId: Int? = null,
    val year: Int? = null,
    val minimumScore: Int? = null,
    val countryId: Int? = null,
    val sort: String? = null,
)

typealias StreamCenterMovieArchiveFilters = StreamCenterTvArchiveFilters

data class StreamCenterStreamingSource(
    val key: String,
    val title: String,
    val urlPrefKey: String,
    val defaultUrl: String,
    val category: String = "anime",
    val defaultEnabled: Boolean = true,
    val isPinned: Boolean = false,
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
        const val PREF_SHOW_TRACKING_IDS = "showTrackingIds"
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
        const val PREF_VISUAL_EFFECTS_PUBLIC_IP = "visualEffectsPublicIp"
        const val PREF_REQUIRE_VPN = "requireVpn"
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
        const val PREF_TV_CUSTOM_SECTIONS = "tvCustomSections"
        const val PREF_TV_CUSTOM_SECTION_COUNTER = "tvCustomSectionCounter"
        const val TV_CUSTOM_SECTION_PREFIX = "tv_custom_"
        const val PREF_MOVIE_CUSTOM_SECTIONS = "movieCustomSections"
        const val PREF_MOVIE_CUSTOM_SECTION_COUNTER = "movieCustomSectionCounter"
        const val MOVIE_CUSTOM_SECTION_PREFIX = "movie_custom_"
        const val PREF_TRACKING_CUSTOM_SECTIONS = "trackingCustomSections"
        const val PREF_TRACKING_CUSTOM_SECTION_COUNTER = "trackingCustomSectionCounter"
        const val TRACKING_CUSTOM_SECTION_PREFIX = "tracking_custom_"
        const val CURRENT_HOME_LAYOUT_VERSION = 6
        const val PREF_SOURCE_ANIMEUNITY = "sourceAnimeUnity"
        const val PREF_SOURCE_ANIMEWORLD = "sourceAnimeWorld"
        const val PREF_SOURCE_ANIMESATURN = "sourceAnimeSaturn"
        const val PREF_SOURCE_STREAMINGCOMMUNITY = "sourceStreamingCommunity"
        const val PREF_SOURCE_VIXCLOUD = "sourceVixCloud"
        const val PREF_SOURCE_VIXSRC = "sourceVixSrc"
        const val PREF_SOURCE_VIDXGO = "sourceVidxGo"
        const val PREF_TORRENT_ENABLED = "torrentEnabled"

        const val PREF_URL_ANIMEUNITY = "urlAnimeUnity"
        const val PREF_URL_ANIMEWORLD = "urlAnimeWorld"
        const val PREF_URL_ANIMESATURN = "urlAnimeSaturn"
        const val PREF_URL_STREAMINGCOMMUNITY = "urlStreamingCommunity"
        const val PREF_URL_VIXCLOUD = "urlVixCloud"
        const val PREF_URL_VIXSRC = "urlVixSrc"
        const val PREF_URL_VIDXGO = "urlVidxGo"

        const val DEFAULT_URL_ANIMEUNITY = "https://www.animeunity.so"
        const val DEFAULT_URL_ANIMEWORLD = "https://www.animeworld.ac"
        const val DEFAULT_URL_ANIMESATURN = "https://www.animesaturn.net"
        const val DEFAULT_URL_STREAMINGCOMMUNITY = "https://streamingcommunityz.miami"
        const val DEFAULT_URL_VIXCLOUD = "https://vixcloud.co"
        const val DEFAULT_URL_VIXSRC = "https://vixsrc.to"
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
        const val MIN_HOME_COUNT = 1
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
                key = "anime_random",
                data = "au:random",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_popular",
                data = "au:popular",
                defaultCount = DEFAULT_HOME_COUNT,
                defaultEnabled = false,
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
        )

        val streamingSources = listOf(
            StreamCenterStreamingSource(
                key = PREF_SOURCE_STREAMINGCOMMUNITY,
                title = "StreamingCommunity",
                urlPrefKey = PREF_URL_STREAMINGCOMMUNITY,
                defaultUrl = DEFAULT_URL_STREAMINGCOMMUNITY,
                category = "tv",
                isPinned = true,
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_VIXCLOUD,
                title = "VixCloud",
                urlPrefKey = PREF_URL_VIXCLOUD,
                defaultUrl = DEFAULT_URL_VIXCLOUD,
                category = "tv",
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_VIXSRC,
                title = "VixSrc",
                urlPrefKey = PREF_URL_VIXSRC,
                defaultUrl = DEFAULT_URL_VIXSRC,
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

        fun shouldShowTrackingIds(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_TRACKING_IDS, false) ?: false
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

        fun shouldShowPublicIp(sharedPref: SharedPreferences?): Boolean {
            return !isPerformanceModeEnabled(sharedPref) &&
                (sharedPref?.getBoolean(PREF_VISUAL_EFFECTS_PUBLIC_IP, true) ?: true)
        }

        fun isVpnRequired(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_REQUIRE_VPN, false) ?: false
        }

        internal fun isDefaultVpnRequirementPreference(key: String, value: Any?): Boolean {
            return key == PREF_REQUIRE_VPN && value == false
        }

        fun shouldGroupAnimeVariants(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_GROUP_ANIME_DUB_SUB, true) ?: true
        }

        fun isStreamingSourceEnabled(sharedPref: SharedPreferences?, prefKey: String): Boolean {
            val source = streamingSources.firstOrNull { it.key == prefKey } ?: return false
            return sharedPref?.getBoolean(prefKey, source.defaultEnabled) ?: source.defaultEnabled
        }

        fun isTorrentEnabled(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_TORRENT_ENABLED, false) ?: false
        }

        fun setTorrentEnabled(sharedPref: SharedPreferences?, enabled: Boolean) {
            sharedPref?.edit()?.apply {
                if (enabled) putBoolean(PREF_TORRENT_ENABLED, true)
                else remove(PREF_TORRENT_ENABLED)
            }?.apply()
        }

        fun resetTorrentConfiguration(sharedPref: SharedPreferences?) {
            val preferences = sharedPref ?: return
            val obsoleteTorrentKeys = preferences.all.keys.filter(
                StreamCenterTorrentPreferences::isObsoletePreference,
            )
            preferences.edit().apply {
                obsoleteTorrentKeys.forEach(::remove)
                StreamCenterTorrentPreferences.reset(this)
            }.apply()
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
                            catalogs = item.optJSONArray("catalogs")?.let { catalogs ->
                                buildList {
                                    for (catalogIndex in 0 until catalogs.length()) {
                                        val catalog = catalogs.optJSONObject(catalogIndex) ?: continue
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
            StreamCenterVpnGuard.requireInternetAccess(sharedPref)
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
                                StreamCenterStremioAddonClient.readStreamingAddon(original.manifestUrl)
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

        internal fun resetSourcesConfiguration(sharedPref: SharedPreferences?) {
            val preferences = sharedPref ?: return
            val stremioEnabledKeys = preferences.all.keys.filter {
                it.startsWith(PREF_STREMIO_ADDON_ENABLED_PREFIX)
            }
            val obsoleteTorrentKeys = preferences.all.keys.filter(
                StreamCenterTorrentPreferences::isObsoletePreference,
            )
            preferences.edit().apply {
                streamingSources.forEach { source ->
                    remove(source.key)
                    remove(source.urlPrefKey)
                }
                remove(PREF_TORRENT_ENABLED)
                obsoleteTorrentKeys.forEach(::remove)
                StreamCenterTorrentPreferences.reset(this)
                remove(PREF_SOURCE_PRIORITY)
                remove(PREF_STREMIO_ADDONS)
                remove(PREF_AUTO_UPDATE_SOURCE_URLS)
                stremioEnabledKeys.forEach { key -> remove(key) }
            }.apply()
        }

        internal fun isObsoleteTorrentPreference(key: String): Boolean =
            StreamCenterTorrentPreferences.isObsoletePreference(key)

        internal fun isDefaultTorrentPreference(key: String, value: Any?): Boolean {
            if (key == PREF_TORRENT_ENABLED) return value == false
            return StreamCenterTorrentPreferences.isDefaultPreference(key, value)
        }

        fun getSourcePriorityOrder(sharedPref: SharedPreferences?): List<String> {
            val pinnedKeys = streamingSources.filter(StreamCenterStreamingSource::isPinned).map { it.key }
            val defaultOrder = streamingSources.map { it.key } + getStremioAddons(sharedPref).map { it.key }
            val stored = sharedPref
                ?.getString(PREF_SOURCE_PRIORITY, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { key -> key in defaultOrder }
                ?.distinct()
                .orEmpty()
                .toMutableList()
            if (
                PREF_SOURCE_VIXSRC !in stored &&
                (PREF_SOURCE_VIXCLOUD in stored || PREF_SOURCE_VIDXGO in stored)
            ) {
                val insertAt = when {
                    PREF_SOURCE_VIXCLOUD in stored -> stored.indexOf(PREF_SOURCE_VIXCLOUD) + 1
                    PREF_SOURCE_VIDXGO in stored -> stored.indexOf(PREF_SOURCE_VIDXGO)
                    else -> 0
                }
                stored.add(insertAt, PREF_SOURCE_VIXSRC)
            }
            val orderedKeys = stored + defaultOrder.filterNot { it in stored }
            return pinnedKeys + orderedKeys.filterNot { it in pinnedKeys }
        }

        fun setSourcePriorityOrder(sharedPref: SharedPreferences?, order: List<String>) {
            val pinnedKeys = streamingSources.filter(StreamCenterStreamingSource::isPinned).map { it.key }
            val validKeys = streamingSources.map { it.key } + getStremioAddons(sharedPref).map { it.key }
            val normalized = order.filter { it in validKeys }.distinct() + validKeys.filterNot { it in order }
            val pinnedFirst = pinnedKeys + normalized.filterNot { it in pinnedKeys }
            sharedPref?.edit()?.putString(PREF_SOURCE_PRIORITY, pinnedFirst.joinToString(","))?.apply()
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
        private const val TOTAL_PLACEHOLDER = "%Totale%"
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
        private val totalPlaceholderPattern = Regex(TOTAL_PLACEHOLDER, RegexOption.IGNORE_CASE)
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
        private val legacyRenderedAnimeCalendarTitle = Regex(
            """Anime:\s*calendario\s*\((?:Lunedi|Martedi|Mercoledi|Giovedi|Venerdi|Sabato|Domenica)\)""",
            RegexOption.IGNORE_CASE,
        )

        fun getHomeSectionTitleTemplate(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): String {
            val title = sharedPref
                ?.getString(sectionTitleKey(section.key), null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: getDefaultHomeSectionTitle(section.key)
            return if (section.key == "anime_calendar" && isDefaultAnimeCalendarTitle(title)) {
                getDefaultHomeSectionTitle(section.key)
            } else {
                title
            }
        }

        private fun isDefaultAnimeCalendarTitle(title: String): Boolean {
            val normalized = title.trim()
            return normalized.equals(getDefaultHomeSectionTitle("anime_calendar"), ignoreCase = true) ||
                normalized.equals("Anime - Calendario (%Giorno%)", ignoreCase = true) ||
                normalized.equals("Anime - %Giorno% Calendario", ignoreCase = true) ||
                legacyRenderedAnimeCalendarTitle.matches(normalized)
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
            itemCount: Int? = null,
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
            itemCount?.let { replacements += totalPlaceholderPattern to it.toString() }
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
            return getHomeSectionOrder(sharedPref)
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

        fun getHomeSectionOrder(sharedPref: SharedPreferences?): List<String> {
            val sections = getAllHomeSections(sharedPref)
            val availableKeys = sections.map { it.key }
            val order = sharedPref
                ?.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map(String::trim)
                ?.filter { it in availableKeys }
                ?.distinct()
                .orEmpty()
                .toMutableList()

            sections.forEachIndexed { index, section ->
                if (section.key in order) return@forEachIndexed
                val nextKey = sections.asSequence()
                    .drop(index + 1)
                    .map { it.key }
                    .firstOrNull { it in order }
                val previousKey = sections.asSequence()
                    .take(index)
                    .map { it.key }
                    .lastOrNull { it in order }
                val insertionIndex = when {
                    nextKey != null -> order.indexOf(nextKey)
                    previousKey != null -> order.indexOf(previousKey) + 1
                    else -> order.size
                }
                order.add(insertionIndex, section.key)
            }
            return order
        }

        internal fun isDefaultHomePreference(key: String, value: Any?): Boolean {
            if (key == PREF_HOME_ORDER) {
                return (value as? String)
                    ?.split(",")
                    ?.map(String::trim) == homeSections.map { it.key }
            }
            if (key == PREF_HOME_CATEGORY_ORDER) {
                return (value as? String)
                    ?.split(",")
                    ?.map(String::trim) == homeCategories
            }
            if (key.startsWith("home_category_") && key.endsWith("_enabled")) {
                val categoryKey = key.removePrefix("home_category_").removeSuffix("_enabled")
                return categoryKey in homeCategories && value == true
            }
            val section = homeSections.firstOrNull { definition ->
                key == sectionEnabledKey(definition.key) ||
                    key == sectionTitleKey(definition.key) ||
                    key == sectionCountKey(definition.key)
            } ?: return false
            return when (key) {
                sectionEnabledKey(section.key) -> value == section.defaultEnabled
                sectionCountKey(section.key) -> value == section.defaultCount
                sectionTitleKey(section.key) -> {
                    val title = (value as? String)?.trim() ?: return false
                    if (section.key == "anime_calendar") {
                        isDefaultAnimeCalendarTitle(title)
                    } else {
                        title == getDefaultHomeSectionTitle(section.key)
                    }
                }
                else -> false
            }
        }

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
                "anime_calendar" -> "Anime: calendario (%Giorno%)"
                "anime_latest" -> "Anime: ultimi episodi"
                "anime_random" -> "Anime: random"
                "anime_popular" -> "Anime: popolari"
                "tv_trending" -> "Serie TV: titoli del momento"
                "tv_latest" -> "Serie TV: aggiunte di recente"
                "tv_top10" -> "Serie TV: top 10 di oggi"
                "movie_trending" -> "Film: titoli del momento"
                "movie_latest" -> "Film: aggiunti di recente"
                "movie_top10" -> "Film: top 10 di oggi"
                else -> if (sectionKey.startsWith(TRACKING_CUSTOM_SECTION_PREFIX)) {
                    "Lista di tracciamento"
                } else if (sectionKey.startsWith(IPTV_CUSTOM_SECTION_PREFIX)) {
                    "TV - i miei canali"
                } else if (sectionKey.startsWith(ANIME_CUSTOM_SECTION_PREFIX)) {
                    "Anime: qualsiasi"
                } else if (sectionKey.startsWith(TV_CUSTOM_SECTION_PREFIX)) {
                    "Serie TV: qualsiasi"
                } else if (sectionKey.startsWith(MOVIE_CUSTOM_SECTION_PREFIX)) {
                    "Film: qualsiasi"
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

        private fun removeObsoleteHomeSectionPreferences(prefs: SharedPreferences) {
            val knownSectionKeys = getAllHomeSections(prefs).mapTo(mutableSetOf()) { it.key }
            val sectionPreferencePattern = Regex("^home_(.+)_(enabled|title|count)$")
            val obsoletePreferenceKeys = prefs.all.keys.filter { preferenceKey ->
                if (preferenceKey.startsWith("home_category_")) return@filter false
                val sectionKey = sectionPreferencePattern.matchEntire(preferenceKey)
                    ?.groupValues
                    ?.getOrNull(1)
                sectionKey != null && sectionKey !in knownSectionKeys
            }
            val storedOrder = prefs.getString(PREF_HOME_ORDER, null)
            val normalizedOrder = getHomeSectionOrder(prefs)
            val currentOrder = storedOrder?.split(",")?.map(String::trim).orEmpty()
            val shouldUpdateOrder = storedOrder != null && currentOrder != normalizedOrder
            if (obsoletePreferenceKeys.isEmpty() && !shouldUpdateOrder) return

            prefs.edit().apply {
                obsoletePreferenceKeys.forEach(::remove)
                if (shouldUpdateOrder) putString(PREF_HOME_ORDER, normalizedOrder.joinToString(","))
            }.apply()
        }

        private fun removeObsoleteTorrentPreferences(prefs: SharedPreferences) {
            val obsoleteKeys = prefs.all.keys.filter(::isObsoleteTorrentPreference)
            if (obsoleteKeys.isEmpty()) return
            prefs.edit().apply {
                obsoleteKeys.forEach(::remove)
            }.apply()
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
                val legacyGenreId = json.optInt("genreId").takeIf { it > 0 }
                val genreIds = json.optJSONArray("genreIds")
                    ?.let { values ->
                        buildList {
                            for (index in 0 until values.length()) {
                                values.optInt(index).takeIf { it > 0 }?.let(::add)
                            }
                        }
                    }
                    .orEmpty()
                StreamCenterAnimeArchiveFilters(
                    genreId = legacyGenreId,
                    genreIds = genreIds.ifEmpty { listOfNotNull(legacyGenreId) },
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
            val genreIds = filters.selectedGenreIds
            genreIds.firstOrNull()?.let { put("genreId", it) }
            if (genreIds.isNotEmpty()) put("genreIds", JSONArray(genreIds))
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

        private fun tvCustomFiltersKey(sectionKey: String): String = "tvCustomFilters_$sectionKey"

        fun getTvCustomSectionKeys(sharedPref: SharedPreferences?): List<String> {
            return sharedPref?.getString(PREF_TV_CUSTOM_SECTIONS, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.startsWith(TV_CUSTOM_SECTION_PREFIX) }
                ?.distinct()
                .orEmpty()
        }

        fun tvCustomSectionDefinition(sectionKey: String): StreamCenterHomeSectionDefinition {
            return StreamCenterHomeSectionDefinition(
                key = sectionKey,
                data = "sc:archive:tv_custom:$sectionKey",
                defaultCount = DEFAULT_HOME_COUNT,
            )
        }

        fun createTvCustomSection(
            sharedPref: SharedPreferences?,
            filters: StreamCenterTvArchiveFilters,
            count: Int,
            name: String,
        ): String? {
            val prefs = sharedPref ?: return null
            val counter = prefs.getInt(PREF_TV_CUSTOM_SECTION_COUNTER, 0) + 1
            val sectionKey = "$TV_CUSTOM_SECTION_PREFIX$counter"
            val keys = getTvCustomSectionKeys(prefs) + sectionKey
            prefs.edit()
                .putInt(PREF_TV_CUSTOM_SECTION_COUNTER, counter)
                .putString(PREF_TV_CUSTOM_SECTIONS, keys.joinToString(","))
                .putString(tvCustomFiltersKey(sectionKey), tvFiltersToJson(filters))
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() } ?: getDefaultHomeSectionTitle(sectionKey),
                )
                .putInt(sectionCountKey(sectionKey), count.coerceIn(MIN_HOME_COUNT, MAX_HOME_COUNT))
                .putBoolean(sectionEnabledKey(sectionKey), true)
                .apply()
            return sectionKey
        }

        fun getTvCustomSectionFilters(
            sharedPref: SharedPreferences?,
            sectionKey: String,
        ): StreamCenterTvArchiveFilters? {
            if (!sectionKey.startsWith(TV_CUSTOM_SECTION_PREFIX)) return null
            val raw = sharedPref?.getString(tvCustomFiltersKey(sectionKey), null) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                StreamCenterTvArchiveFilters(
                    genreId = json.optInt("genreId").takeIf { it > 0 },
                    year = json.optInt("year").takeIf { it > 0 },
                    minimumScore = json.optInt("minimumScore").takeIf { it in 1..10 },
                    countryId = json.optInt("countryId").takeIf { it > 0 },
                    sort = json.optString("sort").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }

        fun updateTvCustomSection(
            sharedPref: SharedPreferences?,
            sectionKey: String,
            filters: StreamCenterTvArchiveFilters,
            count: Int,
            name: String,
        ): Boolean {
            val prefs = sharedPref ?: return false
            if (sectionKey !in getTvCustomSectionKeys(prefs)) return false
            prefs.edit()
                .putString(tvCustomFiltersKey(sectionKey), tvFiltersToJson(filters))
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() } ?: getDefaultHomeSectionTitle(sectionKey),
                )
                .putInt(sectionCountKey(sectionKey), count.coerceIn(MIN_HOME_COUNT, MAX_HOME_COUNT))
                .apply()
            return true
        }

        private fun tvFiltersToJson(filters: StreamCenterTvArchiveFilters): String = JSONObject().apply {
            filters.genreId?.let { put("genreId", it) }
            filters.year?.let { put("year", it) }
            filters.minimumScore?.let { put("minimumScore", it) }
            filters.countryId?.let { put("countryId", it) }
            filters.sort?.let { put("sort", it) }
        }.toString()

        fun deleteTvCustomSection(sharedPref: SharedPreferences?, sectionKey: String) {
            val prefs = sharedPref ?: return
            val keys = getTvCustomSectionKeys(prefs).filterNot { it == sectionKey }
            val order = prefs.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filterNot { it == sectionKey }
                ?.joinToString(",")
            prefs.edit().apply {
                putString(PREF_TV_CUSTOM_SECTIONS, keys.joinToString(","))
                if (order != null) putString(PREF_HOME_ORDER, order)
                remove(tvCustomFiltersKey(sectionKey))
                remove(sectionEnabledKey(sectionKey))
                remove(sectionTitleKey(sectionKey))
                remove(sectionCountKey(sectionKey))
            }.apply()
        }

        private fun movieCustomFiltersKey(sectionKey: String): String = "movieCustomFilters_$sectionKey"

        fun getMovieCustomSectionKeys(sharedPref: SharedPreferences?): List<String> {
            return sharedPref?.getString(PREF_MOVIE_CUSTOM_SECTIONS, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.startsWith(MOVIE_CUSTOM_SECTION_PREFIX) }
                ?.distinct()
                .orEmpty()
        }

        fun movieCustomSectionDefinition(sectionKey: String): StreamCenterHomeSectionDefinition {
            return StreamCenterHomeSectionDefinition(
                key = sectionKey,
                data = "sc:archive:movie_custom:$sectionKey",
                defaultCount = DEFAULT_HOME_COUNT,
            )
        }

        fun createMovieCustomSection(
            sharedPref: SharedPreferences?,
            filters: StreamCenterMovieArchiveFilters,
            count: Int,
            name: String,
        ): String? {
            val prefs = sharedPref ?: return null
            val counter = prefs.getInt(PREF_MOVIE_CUSTOM_SECTION_COUNTER, 0) + 1
            val sectionKey = "$MOVIE_CUSTOM_SECTION_PREFIX$counter"
            val keys = getMovieCustomSectionKeys(prefs) + sectionKey
            prefs.edit()
                .putInt(PREF_MOVIE_CUSTOM_SECTION_COUNTER, counter)
                .putString(PREF_MOVIE_CUSTOM_SECTIONS, keys.joinToString(","))
                .putString(movieCustomFiltersKey(sectionKey), tvFiltersToJson(filters))
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() } ?: getDefaultHomeSectionTitle(sectionKey),
                )
                .putInt(sectionCountKey(sectionKey), count.coerceIn(MIN_HOME_COUNT, MAX_HOME_COUNT))
                .putBoolean(sectionEnabledKey(sectionKey), true)
                .apply()
            return sectionKey
        }

        fun getMovieCustomSectionFilters(
            sharedPref: SharedPreferences?,
            sectionKey: String,
        ): StreamCenterMovieArchiveFilters? {
            if (!sectionKey.startsWith(MOVIE_CUSTOM_SECTION_PREFIX)) return null
            val raw = sharedPref?.getString(movieCustomFiltersKey(sectionKey), null) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                StreamCenterMovieArchiveFilters(
                    genreId = json.optInt("genreId").takeIf { it > 0 },
                    year = json.optInt("year").takeIf { it > 0 },
                    minimumScore = json.optInt("minimumScore").takeIf { it in 1..10 },
                    countryId = json.optInt("countryId").takeIf { it > 0 },
                    sort = json.optString("sort").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }

        fun updateMovieCustomSection(
            sharedPref: SharedPreferences?,
            sectionKey: String,
            filters: StreamCenterMovieArchiveFilters,
            count: Int,
            name: String,
        ): Boolean {
            val prefs = sharedPref ?: return false
            if (sectionKey !in getMovieCustomSectionKeys(prefs)) return false
            prefs.edit()
                .putString(movieCustomFiltersKey(sectionKey), tvFiltersToJson(filters))
                .putString(
                    sectionTitleKey(sectionKey),
                    name.trim().takeIf { it.isNotBlank() } ?: getDefaultHomeSectionTitle(sectionKey),
                )
                .putInt(sectionCountKey(sectionKey), count.coerceIn(MIN_HOME_COUNT, MAX_HOME_COUNT))
                .apply()
            return true
        }

        fun deleteMovieCustomSection(sharedPref: SharedPreferences?, sectionKey: String) {
            val prefs = sharedPref ?: return
            val keys = getMovieCustomSectionKeys(prefs).filterNot { it == sectionKey }
            val order = prefs.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filterNot { it == sectionKey }
                ?.joinToString(",")
            prefs.edit().apply {
                putString(PREF_MOVIE_CUSTOM_SECTIONS, keys.joinToString(","))
                if (order != null) putString(PREF_HOME_ORDER, order)
                remove(movieCustomFiltersKey(sectionKey))
                remove(sectionEnabledKey(sectionKey))
                remove(sectionTitleKey(sectionKey))
                remove(sectionCountKey(sectionKey))
            }.apply()
        }

        fun getAllHomeSections(sharedPref: SharedPreferences?): List<StreamCenterHomeSectionDefinition> {
            return homeSections +
                getAnimeCustomSectionKeys(sharedPref).map(::animeCustomSectionDefinition) +
                getTvCustomSectionKeys(sharedPref).map(::tvCustomSectionDefinition) +
                getMovieCustomSectionKeys(sharedPref).map(::movieCustomSectionDefinition) +
                getTrackingCustomSectionKeys(sharedPref).map(::trackingCustomSectionDefinition) +
                getIptvCustomSectionKeys(sharedPref).map(::iptvCustomSectionDefinition)
        }

        internal fun resetHomeCategoryConfiguration(
            sharedPref: SharedPreferences?,
            categoryKey: String,
        ) {
            val preferences = sharedPref ?: return
            if (categoryKey !in homeCategories) return

            val customSectionKeys = when (categoryKey) {
                "anime" -> getAnimeCustomSectionKeys(preferences)
                "tv" -> getTvCustomSectionKeys(preferences)
                "movie" -> getMovieCustomSectionKeys(preferences)
                "live" -> getIptvCustomSectionKeys(preferences)
                "tracking" -> getTrackingCustomSectionKeys(preferences)
                else -> emptyList()
            }
            val defaultSectionKeys = homeSections
                .filter { homeSectionCategoryKey(it) == categoryKey }
                .map(StreamCenterHomeSectionDefinition::key)
            val categorySectionKeys = (defaultSectionKeys + customSectionKeys).toSet()
            val updatedOrder = preferences.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.let { order ->
                    val insertionIndex = order.indexOfFirst { it in categorySectionKeys }
                        .takeIf { it >= 0 }
                        ?: order.size
                    val retained = order.filterNot { it in categorySectionKeys }
                    val targetIndex = insertionIndex.coerceAtMost(retained.size)
                    (retained.take(targetIndex) + defaultSectionKeys + retained.drop(targetIndex))
                        .distinct()
                        .joinToString(",")
                }

            preferences.edit().apply {
                homeSections
                    .filter { homeSectionCategoryKey(it) == categoryKey }
                    .forEach { section ->
                        remove(sectionEnabledKey(section.key))
                        remove(sectionTitleKey(section.key))
                        remove(sectionCountKey(section.key))
                    }
                customSectionKeys.forEach { sectionKey ->
                    remove(sectionEnabledKey(sectionKey))
                    remove(sectionTitleKey(sectionKey))
                    remove(sectionCountKey(sectionKey))
                    when (categoryKey) {
                        "anime" -> remove(animeCustomFiltersKey(sectionKey))
                        "tv" -> remove(tvCustomFiltersKey(sectionKey))
                        "movie" -> remove(movieCustomFiltersKey(sectionKey))
                        "live" -> {
                            remove(iptvSectionChannelsKey(sectionKey))
                            remove(iptvSectionOrderKey(sectionKey))
                        }
                        "tracking" -> remove(trackingSelectionKey(sectionKey))
                    }
                }
                updatedOrder?.let { putString(PREF_HOME_ORDER, it) }
                remove(homeCategoryEnabledKey(categoryKey))
                when (categoryKey) {
                    "anime" -> {
                        remove(PREF_ANIME_CUSTOM_SECTIONS)
                        remove(PREF_ANIME_CUSTOM_SECTION_COUNTER)
                    }
                    "tv" -> {
                        remove(PREF_TV_CUSTOM_SECTIONS)
                        remove(PREF_TV_CUSTOM_SECTION_COUNTER)
                    }
                    "movie" -> {
                        remove(PREF_MOVIE_CUSTOM_SECTIONS)
                        remove(PREF_MOVIE_CUSTOM_SECTION_COUNTER)
                    }
                    "live" -> {
                        remove(PREF_IPTV_FAVORITE_CHANNELS)
                        remove(PREF_IPTV_REGION)
                        remove(PREF_IPTV_CUSTOM_SECTIONS)
                        remove(PREF_IPTV_CUSTOM_SECTION_COUNTER)
                    }
                    "tracking" -> {
                        remove(PREF_TRACKING_CUSTOM_SECTIONS)
                        remove(PREF_TRACKING_CUSTOM_SECTION_COUNTER)
                    }
                }
            }.apply()

            if (categoryKey == StreamCenterCatalogs.CATEGORY_KEY) {
                StreamCenterCatalogs.reset(preferences)
            }
        }

        internal fun resetHomeConfiguration(sharedPref: SharedPreferences?) {
            val preferences = sharedPref ?: return
            val sectionPreferencePrefixes = listOf(
                "home_",
                "iptvSectionChannels_",
                "iptvSectionOrder_",
                "trackingSelection_",
                "animeCustomFilters_",
                "tvCustomFilters_",
                "movieCustomFilters_",
            )
            val sectionPreferenceKeys = preferences.all.keys.filter { key ->
                sectionPreferencePrefixes.any { prefix -> key.startsWith(prefix) }
            }
            preferences.edit().apply {
                sectionPreferenceKeys.forEach { key -> remove(key) }
                remove(PREF_HOME_ORDER)
                remove(PREF_HOME_CATEGORY_ORDER)
                remove(PREF_HOME_LAYOUT_VERSION)
                remove(PREF_IPTV_FAVORITE_CHANNELS)
                remove(PREF_IPTV_REGION)
                remove(PREF_IPTV_CUSTOM_SECTIONS)
                remove(PREF_IPTV_CUSTOM_SECTION_COUNTER)
                remove(PREF_ANIME_CUSTOM_SECTIONS)
                remove(PREF_ANIME_CUSTOM_SECTION_COUNTER)
                remove(PREF_TV_CUSTOM_SECTIONS)
                remove(PREF_TV_CUSTOM_SECTION_COUNTER)
                remove(PREF_MOVIE_CUSTOM_SECTIONS)
                remove(PREF_MOVIE_CUSTOM_SECTION_COUNTER)
                remove(PREF_TRACKING_CUSTOM_SECTIONS)
                remove(PREF_TRACKING_CUSTOM_SECTION_COUNTER)
            }.apply()
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
        StreamCenterLogger.startSession(
            context = context,
            preferences = sharedPref,
            sessionMetadata = mapOf(
                "plugin" to "StreamCenter",
                "versione_plugin" to BuildConfig.PLUGIN_VERSION,
                "commit_build" to BuildConfig.BUILD_COMMIT_SHA,
                "build_completata" to BuildConfig.BUILD_COMPLETED_AT_ROME,
            ),
        )

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
            removeObsoleteHomeSectionPreferences(prefs)
            removeObsoleteTorrentPreferences(prefs)
            migrateTrackingHomeCategory(prefs)
        }

        it.dogior.hadEnough.localsync.StreamCenterLocalSyncAutoRunner.attach(context.applicationContext)

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
