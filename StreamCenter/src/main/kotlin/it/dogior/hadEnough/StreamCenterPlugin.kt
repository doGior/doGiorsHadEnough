package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File
import java.util.Calendar

data class StreamCenterHomeSectionDefinition(
    val key: String,
    val data: String,
    val defaultCount: Int,
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
    val defaultEnabled: Boolean = true,
)

@CloudstreamPlugin
class StreamCenterPlugin : Plugin() {
    companion object {
        const val PREFS_NAME = "StreamCenter"
        const val PREF_SHOW_HOME_SCORE = "showHomeScore"
        const val PREF_HOME_ORDER = "homeOrder"
        const val PREF_SOURCE_ANIMEUNITY = "sourceAnimeUnity"
        const val PREF_SOURCE_ANIMEWORLD = "sourceAnimeWorld"
        const val PREF_SOURCE_HENTAIWORLD = "sourceHentaiWorld"
        const val PREF_SOURCE_STREAMINGCOMMUNITY = "sourceStreamingCommunity"

        const val DEFAULT_HOME_COUNT = 24
        const val MIN_HOME_COUNT = 6
        const val MAX_HOME_COUNT = 60

        private const val TMDB_URL = "https://www.themoviedb.org"

        internal const val FEEDBACK_ISSUES_URL =
            "https://github.com/doGior/doGiorsHadEnough/issues/new"

        val homeSections = listOf(
            StreamCenterHomeSectionDefinition(
                key = "tv_calendar",
                data = "$TMDB_URL/tv/airing-today?without_keywords=210024",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_on_air",
                data = "$TMDB_URL/tv/on-the-air?without_keywords=210024",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "tv_top",
                data = "$TMDB_URL/tv/top-rated?without_keywords=210024",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_now",
                data = "$TMDB_URL/movie/now-playing?without_keywords=210024",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_upcoming",
                data = "$TMDB_URL/movie/upcoming?without_keywords=210024",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "movie_top",
                data = "$TMDB_URL/movie/top-rated?without_keywords=210024",
                defaultCount = 20,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_calendar",
                data = "anilist:anime_calendar",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_announced",
                data = "anilist:anime_latest",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_season_top",
                data = "anilist:anime_season_top",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_top",
                data = "anilist:anime_top",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_movie_latest",
                data = "anilist:anime_movie_latest",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
            StreamCenterHomeSectionDefinition(
                key = "anime_movie_top",
                data = "anilist:anime_movie_top",
                defaultCount = DEFAULT_HOME_COUNT,
            ),
        )

        val streamingSources = listOf(
            StreamCenterStreamingSource(
                key = PREF_SOURCE_STREAMINGCOMMUNITY,
                title = "StreamingCommunity",
                summary = "Film e serie TV",
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMEUNITY,
                title = "AnimeUnity",
                summary = "Anime sub/dub tramite ID AniList o MAL",
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_ANIMEWORLD,
                title = "AnimeWorld",
                summary = "Anime sub/dub tramite ID AniList o MAL",
            ),
            StreamCenterStreamingSource(
                key = PREF_SOURCE_HENTAIWORLD,
                title = "HentaiWorld",
                summary = "Fonte 18+ potrebbe non funzionare sempre",
            ),
        )

        internal var activePlugin: StreamCenterPlugin? = null
        internal var activeSharedPref: SharedPreferences? = null

        fun shouldShowHomeScore(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(PREF_SHOW_HOME_SCORE, true) ?: true
        }

        fun isStreamingSourceEnabled(sharedPref: SharedPreferences?, prefKey: String): Boolean {
            val source = streamingSources.firstOrNull { it.key == prefKey } ?: return true
            return sharedPref?.getBoolean(prefKey, source.defaultEnabled) ?: source.defaultEnabled
        }

        fun isHomeSectionEnabled(
            sharedPref: SharedPreferences?,
            section: StreamCenterHomeSectionDefinition,
        ): Boolean {
            return sharedPref?.getBoolean(sectionEnabledKey(section.key), true) ?: true
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
            return when {
                shortCommit != null && rawBuildCompletedAt.isNotEmpty() ->
                    "Commit $shortCommit | Build $rawBuildCompletedAt"
                shortCommit != null -> "Commit $shortCommit"
                else -> "Informazioni build non disponibili"
            }
        }

        fun sectionEnabledKey(sectionKey: String): String = "home_${sectionKey}_enabled"
        fun sectionTitleKey(sectionKey: String): String = "home_${sectionKey}_title"
        fun sectionCountKey(sectionKey: String): String = "home_${sectionKey}_count"

        fun defaultHomeOrder(): String = homeSections.joinToString(",") { it.key }

        fun getDefaultHomeSectionTitle(sectionKey: String): String {
            return when (sectionKey) {
                "tv_calendar" -> "Serie TV - Calendario (${currentItalianWeekdayName()})"
                "tv_on_air" -> "Serie TV - In onda"
                "tv_top" -> "Serie TV - Pi\u00f9 votate"
                "movie_now" -> "Film - Al cinema"
                "movie_upcoming" -> "Film - In arrivo"
                "movie_top" -> "Film - Pi\u00f9 votati"
                "anime_calendar" -> "Anime - Calendario (${currentItalianWeekdayName()})"
                "anime_announced" -> "Anime - Annunciati"
                "anime_season_top" -> "Anime - Pi\u00f9 votati della stagione"
                "anime_top" -> "Anime - Pi\u00f9 votati"
                "anime_movie_latest" -> "Film Anime - Recenti"
                "anime_movie_top" -> "Film Anime - Pi\u00f9 votati"
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
        StreamCenter.setCacheDirectory(File(context.cacheDir, "streamcenter_tmdb_cache"))
        activePlugin = this
        activeSharedPref = sharedPref

        registerMainAPI(StreamCenter(sharedPref))

        openSettings = { ctx ->
            if (ctx is AppCompatActivity) {
                activePlugin = this
                activeSharedPref = sharedPref
                StreamCenterSettings().show(ctx.supportFragmentManager, "StreamCenterSettings")
            }
        }
    }
}
