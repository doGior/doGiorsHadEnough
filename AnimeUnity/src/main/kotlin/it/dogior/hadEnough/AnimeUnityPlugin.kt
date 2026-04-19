package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.Locale

@CloudstreamPlugin
class AnimeUnityPlugin : Plugin() {
    companion object {
        const val PREFS_NAME = "AnimeUnity"
        const val PREF_SITE_URL = "siteUrl"
        
        // Sezioni abilitate
        const val PREF_SHOW_LATEST_EPISODES = "showLatestEpisodes"
        const val PREF_SHOW_CALENDAR = "showCalendar"
        const val PREF_SHOW_RANDOM = "showRandom"
        const val PREF_SHOW_ONGOING = "showOngoing"
        const val PREF_SHOW_POPULAR = "showPopular"
        const val PREF_SHOW_BEST = "showBest"
        const val PREF_SHOW_UPCOMING = "showUpcoming"
        
        // Count per sezione
        const val PREF_LATEST_COUNT = "latestCount"
        const val PREF_CALENDAR_COUNT = "calendarCount"
        const val PREF_ONGOING_COUNT = "ongoingCount"
        const val PREF_POPULAR_COUNT = "popularCount"
        const val PREF_BEST_COUNT = "bestCount"
        const val PREF_UPCOMING_COUNT = "upcomingCount"
        const val PREF_RANDOM_COUNT = "randomCount"

        // Visualizzazione
        const val PREF_SHOW_DUB_SUB = "showDubSub"
        const val PREF_SHOW_EPISODE_NUMBER = "showEpisodeNumber"
        const val PREF_SHOW_SCORE = "showScore"

        const val PREF_SECTION_ORDER = "sectionOrder"

        const val DEFAULT_SITE_URL = "https://www.animeunity.so/"
        const val DEFAULT_SECTION_COUNT = 30
        const val MAX_SECTION_COUNT = 100
        const val DEFAULT_SECTION_ORDER = "latest,calendar,random,ongoing,popular,best,upcoming"
        private val defaultSectionKeys = DEFAULT_SECTION_ORDER.split(",")
        private val validSectionKeys = listOf(
            "latest",
            "calendar",
            "ongoing",
            "popular",
            "best",
            "upcoming",
            "random"
        )

        private val siteSchemeRegex = Regex("""(?i)^https?://""")
        private val validSiteHostRegex = Regex(
            pattern = """^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$""",
            option = RegexOption.IGNORE_CASE
        )

        private fun normalizeSiteUrl(value: String?): String? {
            val rawValue = value?.trim().orEmpty()
            if (rawValue.isBlank()) return null

            val withoutScheme = rawValue.replaceFirst(siteSchemeRegex, "")
            val normalizedHost = withoutScheme.trimEnd('/')

            if (normalizedHost.isBlank()) return null
            if (normalizedHost.contains("/") || normalizedHost.contains("?") || normalizedHost.contains("#")) {
                return null
            }
            if (!validSiteHostRegex.matches(normalizedHost)) {
                return null
            }

            return "https://${normalizedHost.lowercase(Locale.ROOT)}/"
        }

        fun isValidSiteUrl(value: String?): Boolean {
            return normalizeSiteUrl(value) != null
        }

        fun getValidatedSiteUrl(value: String?): String {
            return normalizeSiteUrl(value) ?: DEFAULT_SITE_URL
        }

        fun getConfiguredSiteUrl(sharedPref: SharedPreferences?): String {
            return getValidatedSiteUrl(sharedPref?.getString(PREF_SITE_URL, null))
        }

        fun getConfiguredBaseUrl(sharedPref: SharedPreferences?): String {
            return getConfiguredSiteUrl(sharedPref).removeSuffix("/")
        }

        fun getValidatedSectionOrder(value: String?): String {
            val normalizedSections = value
                ?.split(",")
                ?.map { it.trim().lowercase(Locale.ROOT) }
                ?.filter { it in validSectionKeys }
                ?.distinct()
                .orEmpty()

            return if (normalizedSections.isEmpty()) {
                DEFAULT_SECTION_ORDER
            } else {
                (normalizedSections + defaultSectionKeys.filterNot { it in normalizedSections })
                    .joinToString(",")
            }
        }

        fun getConfiguredSectionOrder(sharedPref: SharedPreferences?): String {
            return getValidatedSectionOrder(sharedPref?.getString(PREF_SECTION_ORDER, null))
        }

        internal var activePlugin: AnimeUnityPlugin? = null
        internal var activeSharedPref: SharedPreferences? = null
    }

    private var sharedPref: SharedPreferences? = null

    override fun load(context: Context) {
        sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        activePlugin = this
        activeSharedPref = sharedPref

        registerMainAPI(AnimeUnity(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            activePlugin = this
            activeSharedPref = sharedPref
            AnimeUnitySettings().show(activity.supportFragmentManager, "AnimeUnitySettings")
        }
    }
}
