package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.Calendar

data class StreamCenterHomeSectionDefinition(
    val key: String,
    val data: String,
    val defaultCount: Int,
    val defaultEnabled: Boolean = true,
)

data class StreamCenterConfiguredHomeSection(
    val definition: StreamCenterHomeSectionDefinition,
    val title: String,
    val count: Int,
)

data class StreamCenterStreamingSource(
    val key: String,
    val title: String,
    val summary: String,
    val urlPrefKey: String,
    val defaultUrl: String,
    val defaultEnabled: Boolean = true,
)

@CloudstreamPlugin
class StreamCenterPlugin : Plugin() {
    companion object {
        const val PREFS_NAME = "StreamCenter"
        const val PREF_SHOW_HOME_SCORE = "showHomeScore"
        const val PREF_SHOW_ANIME_HOME_DUB_STATUS = "showAnimeHomeDubStatus"
        const val PREF_SHOW_ANIME_HOME_EPISODE_NUMBER = "showAnimeHomeEpisodeNumber"
        const val PREF_PERFORMANCE_MODE = "performanceMode"
        const val PREF_SEARCH_SPLIT_BY_TYPE = "searchSplitByType"
        const val PREF_GROUP_ANIME_DUB_SUB = "groupAnimeDubSub"
        const val PREF_HOME_ORDER = "homeOrder"
        const val PREF_HOME_CATEGORY_ORDER = "homeCategoryOrder"
        const val PREF_HOME_LAYOUT_VERSION = "homeLayoutVersion"
        const val CURRENT_HOME_LAYOUT_VERSION = 4
        const val PREF_SOURCE_ANIMEUNITY = "sourceAnimeUnity"
        const val PREF_SOURCE_ANIMEWORLD = "sourceAnimeWorld"
        const val PREF_SOURCE_ANIMESATURN = "sourceAnimeSaturn"
        const val PREF_SOURCE_HENTAIWORLD = "sourceHentaiWorld"
        const val PREF_SOURCE_STREAMINGCOMMUNITY = "sourceStreamingCommunity"

        const val PREF_URL_ANIMEUNITY = "urlAnimeUnity"
        const val PREF_URL_ANIMEWORLD = "urlAnimeWorld"
        const val PREF_URL_ANIMESATURN = "urlAnimeSaturn"
        const val PREF_URL_STREAMINGCOMMUNITY = "urlStreamingCommunity"
        const val PREF_URL_HENTAIWORLD = "urlHentaiWorld"

        const val DEFAULT_URL_ANIMEUNITY = "https://www.animeunity.so"
        const val DEFAULT_URL_ANIMEWORLD = "https://www.animeworld.ac"
        const val DEFAULT_URL_ANIMESATURN = "https://www.animesaturn.net"
        const val DEFAULT_URL_STREAMINGCOMMUNITY = "https://streamingcommunityz.pizza"
        const val DEFAULT_URL_HENTAIWORLD = "https://www.hentaiworld.me"

        const val PREF_SOURCE_PRIORITY = "sourcePriority"

        const val PREF_AUTO_UPDATE_SOURCE_URLS = "autoUpdateSourceUrls"

        const val PREF_ANILIST_RPM = "anilistRequestsPerMinute"
        const val DEFAULT_ANILIST_RPM = 30
        const val MIN_ANILIST_RPM = 5
        const val MAX_ANILIST_RPM = 90

        const val DEFAULT_HOME_COUNT = 24
        const val MIN_HOME_COUNT = 6
        const val MAX_HOME_COUNT = 60

        val homeCategories = listOf("anime", "tv", "movie")

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
                summary = "Film e Serie TV in italiano.",
                urlPrefKey = PREF_URL_STREAMINGCOMMUNITY,
                defaultUrl = DEFAULT_URL_STREAMINGCOMMUNITY,
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMEUNITY,
                title = "AnimeUnity",
                summary = "Anime SUB e DUB\ncon abbinamento AniList/MAL.",
                urlPrefKey = PREF_URL_ANIMEUNITY,
                defaultUrl = DEFAULT_URL_ANIMEUNITY,
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMEWORLD,
                title = "AnimeWorld",
                summary = "Anime SUB e DUB\ncon abbinamento AniList/MAL.",
                urlPrefKey = PREF_URL_ANIMEWORLD,
                defaultUrl = DEFAULT_URL_ANIMEWORLD,
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMESATURN,
                title = "AnimeSaturn",
                summary = "Anime SUB e DUB\ncon abbinamento AniList/MAL.",
                urlPrefKey = PREF_URL_ANIMESATURN,
                defaultUrl = DEFAULT_URL_ANIMESATURN,
            ),
        )

        val hentaiWorldSource = StreamCenterStreamingSource(
            key = PREF_SOURCE_HENTAIWORLD,
            title = "HentaiWorld",
            summary = "Fonte 18+, non sempre disponibile.",
            urlPrefKey = PREF_URL_HENTAIWORLD,
            defaultUrl = DEFAULT_URL_HENTAIWORLD,
            defaultEnabled = false,
        )

        internal var activePlugin: StreamCenterPlugin? = null
        internal var activeSharedPref: SharedPreferences? = null

        fun shouldShowHomeScore(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_HOME_SCORE, true) ?: true
        }

        fun shouldShowAnimeHomeDubStatus(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_ANIME_HOME_DUB_STATUS, true) ?: true
        }

        fun shouldShowAnimeHomeEpisodeNumber(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, true) ?: true
        }

        fun isPerformanceModeEnabled(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_PERFORMANCE_MODE, false) ?: false
        }

        fun shouldSplitSearchResultsByType(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SEARCH_SPLIT_BY_TYPE, true) ?: true
        }

        fun shouldGroupAnimeVariants(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_GROUP_ANIME_DUB_SUB, true) ?: true
        }

        private fun allStreamingSources(): List<StreamCenterStreamingSource> {
            return streamingSources + hentaiWorldSource
        }

        fun isStreamingSourceEnabled(sharedPref: SharedPreferences?, prefKey: String): Boolean {
            val source = allStreamingSources().firstOrNull { it.key == prefKey } ?: return false
            return sharedPref?.getBoolean(prefKey, source.defaultEnabled) ?: source.defaultEnabled
        }

        private fun normalizeSourceUrl(url: String): String {
            val cleaned = url.trim().trimEnd('/')
            if (cleaned.isBlank()) return ""
            return if ("://" in cleaned) cleaned else "https://$cleaned"
        }

        fun getSourceBaseUrl(sharedPref: SharedPreferences?, prefKey: String): String {
            val source = allStreamingSources().firstOrNull { it.key == prefKey }
                ?: return ""
            val stored = sharedPref
                ?.getString(source.urlPrefKey, null)
                ?.let(::normalizeSourceUrl)
                ?.takeIf { it.isNotBlank() }
            return stored ?: source.defaultUrl.trimEnd('/')
        }

        fun setSourceBaseUrl(sharedPref: SharedPreferences?, prefKey: String, url: String) {
            val source = allStreamingSources().firstOrNull { it.key == prefKey } ?: return
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
                allStreamingSources().forEach { remove(it.urlPrefKey) }
            }?.apply()
        }

        fun getSourcePriorityOrder(sharedPref: SharedPreferences?): List<String> {
            val defaultOrder = streamingSources.map { it.key }
            val stored = sharedPref
                ?.getString(PREF_SOURCE_PRIORITY, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { key -> streamingSources.any { it.key == key } }
                ?.distinct()
                .orEmpty()
            return stored + defaultOrder.filterNot { it in stored }
        }

        fun setSourcePriorityOrder(sharedPref: SharedPreferences?, order: List<String>) {
            sharedPref?.edit()?.putString(PREF_SOURCE_PRIORITY, order.joinToString(","))?.apply()
        }

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

        fun getHomeSectionTitle(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): String {
            return sharedPref
                ?.getString(sectionTitleKey(section.key), null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: getDefaultHomeSectionTitle(section.key)
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
            val section = homeSections.firstOrNull {
                it.data == data || normalizedData.startsWith(it.data.substringBefore("&page=").substringBefore("?page="))
            } ?: return DEFAULT_HOME_COUNT
            return getHomeSectionCount(sharedPref, section)
        }

        fun getConfiguredHomeSections(sharedPref: SharedPreferences?): List<StreamCenterConfiguredHomeSection> {
            val byKey = homeSections.associateBy { it.key }
            val orderedKeys = sharedPref
                ?.getString(PREF_HOME_ORDER, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it in byKey }
                ?.distinct()
                .orEmpty()
            val normalizedOrder = orderedKeys + homeSections.map { it.key }.filterNot { it in orderedKeys }

            return normalizedOrder
                .mapNotNull { byKey[it] }
                .filter { isHomeSectionEnabled(sharedPref, it) }
                .filter { isHomeCategoryEnabled(sharedPref, homeSectionCategoryKey(it)) }
                .sortedBy { homeSectionCategoryRank(sharedPref, it) }
                .map { section ->
                    StreamCenterConfiguredHomeSection(
                        definition = section,
                        title = getHomeSectionTitle(sharedPref, section),
                        count = getHomeSectionCount(sharedPref, section),
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
                else -> "Informazioni build non disponibili"
            }
        }

        fun sectionEnabledKey(sectionKey: String): String = "home_${sectionKey}_enabled"
        fun sectionTitleKey(sectionKey: String): String = "home_${sectionKey}_title"
        fun sectionCountKey(sectionKey: String): String = "home_${sectionKey}_count"
        fun homeCategoryEnabledKey(categoryKey: String): String = "home_category_${categoryKey}_enabled"

        fun defaultHomeOrder(): String = homeSections.joinToString(",") { it.key }

        fun homeSectionCategoryKey(section: StreamCenterHomeSectionDefinition): String {
            return when {
                section.key.startsWith("anime_") -> "anime"
                section.key.startsWith("tv_") -> "tv"
                section.key.startsWith("movie_") -> "movie"
                else -> "other"
            }
        }

        fun homeSectionCategory(section: StreamCenterHomeSectionDefinition): String {
            return when (homeSectionCategoryKey(section)) {
                "anime" -> "Anime"
                "tv" -> "Serie TV"
                "movie" -> "Film"
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
                "anime_calendar" -> "Anime: calendario (${currentItalianWeekdayName()})"
                "anime_latest" -> "Anime: ultimi episodi"
                "anime_popular" -> "Anime: popolari"
                "tv_trending" -> "Serie TV: titoli del momento"
                "tv_latest" -> "Serie TV: aggiunte di recente"
                "tv_top10" -> "Serie TV: Top 10 di oggi"
                "movie_trending" -> "Film: titoli del momento"
                "movie_latest" -> "Film: aggiunti di recente"
                "movie_top10" -> "Film: Top 10 di oggi"
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
                else -> sectionKey
            }
        }

        private fun currentItalianWeekdayName(): String {
            return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "Lunedi"
                Calendar.TUESDAY -> "Martedi"
                Calendar.WEDNESDAY -> "Mercoledi"
                Calendar.THURSDAY -> "Giovedi"
                Calendar.FRIDAY -> "Venerdi"
                Calendar.SATURDAY -> "Sabato"
                else -> "Domenica"
            }
        }
    }

    private var sharedPref: SharedPreferences? = null

    override fun load(context: Context) {
        sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        activePlugin = this
        activeSharedPref = sharedPref

        sharedPref?.let { prefs ->
            if (prefs.getInt(PREF_HOME_LAYOUT_VERSION, 0) < CURRENT_HOME_LAYOUT_VERSION) {
                prefs.edit()
                    .putString(PREF_HOME_ORDER, defaultHomeOrder())
                    .putInt(PREF_HOME_LAYOUT_VERSION, CURRENT_HOME_LAYOUT_VERSION)
                    .apply()
            }
        }

        registerMainAPI(StreamCenter(sharedPref))
        registerMainAPI(StreamCenter(sharedPref, StreamCenter.SEARCH_SECTION_MOVIES))
        registerMainAPI(StreamCenter(sharedPref, StreamCenter.SEARCH_SECTION_SERIES))
        registerMainAPI(StreamCenter(sharedPref, StreamCenter.SEARCH_SECTION_ANIME))

        openSettings = { ctx ->
            if (ctx is AppCompatActivity) {
                activePlugin = this
                activeSharedPref = sharedPref
                StreamCenterSettings().show(ctx.supportFragmentManager, "StreamCenterSettings")
            }
        }
    }
}
