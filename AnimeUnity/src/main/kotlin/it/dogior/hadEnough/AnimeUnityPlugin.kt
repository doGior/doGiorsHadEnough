package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.Calendar
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

        // Nomi personalizzati per sezione
        const val PREF_LATEST_TITLE = "latestTitle"
        const val PREF_CALENDAR_TITLE = "calendarTitle"
        const val PREF_ONGOING_TITLE = "ongoingTitle"
        const val PREF_POPULAR_TITLE = "popularTitle"
        const val PREF_BEST_TITLE = "bestTitle"
        const val PREF_UPCOMING_TITLE = "upcomingTitle"
        const val PREF_RANDOM_TITLE = "randomTitle"

        // Visualizzazione
        const val PREF_UNIFY_DUB_SUB_CARDS = "unifyDubSubCards"
        const val PREF_SHOW_DUB_SUB = "showDubSub"
        const val PREF_SHOW_EPISODE_NUMBER = "showEpisodeNumber"
        const val PREF_SHOW_SCORE = "showScore"
        const val PREF_SECTION_ORDER = "sectionOrder"
        const val PREF_ENABLE_ADVANCED_SEARCH = "enableAdvancedSearch"
        const val PREF_ADVANCED_SEARCH_TITLE = "advancedSearchTitle"
        const val PREF_ADVANCED_SEARCH_GENRE_ID = "advancedSearchGenreId"
        const val PREF_ADVANCED_SEARCH_YEAR = "advancedSearchYear"
        const val PREF_ADVANCED_SEARCH_ORDER = "advancedSearchOrder"
        const val PREF_ADVANCED_SEARCH_STATUS = "advancedSearchStatus"
        const val PREF_ADVANCED_SEARCH_TYPE = "advancedSearchType"
        const val PREF_ADVANCED_SEARCH_SEASON = "advancedSearchSeason"
        const val PREF_ADVANCED_SEARCH_COUNT = "advancedSearchCount"
        const val PREF_CACHE_MAX_ENTRIES = "cacheMaxEntries"
        const val PREF_CACHE_MAX_SIZE_MB = "cacheMaxSizeMb"

        const val DEFAULT_SITE_URL = "https://www.animeunity.so/"
        const val DEFAULT_SECTION_COUNT = 30
        const val DEFAULT_RANDOM_COUNT = 10
        const val MAX_SECTION_COUNT = 100
        const val DEFAULT_ADVANCED_SEARCH_COUNT = MAX_SECTION_COUNT
        const val DEFAULT_SECTION_ORDER = AnimeUnitySections.DEFAULT_ORDER
        const val DEFAULT_UNIFY_DUB_SUB_CARDS = true
        const val DEFAULT_CACHE_MAX_ENTRIES = 1000
        const val MIN_CACHE_MAX_ENTRIES = 50
        const val MAX_CACHE_MAX_ENTRIES = 10000
        const val DEFAULT_CACHE_MAX_SIZE_MB = 250
        const val MIN_CACHE_MAX_SIZE_MB = 16
        const val MAX_CACHE_MAX_SIZE_MB = 2048
        private const val ARCHIVE_OLDEST_YEAR = 1966
        private val defaultSectionKeys = AnimeUnitySections.defaultOrderKeys
        private val validSectionKeys = AnimeUnitySections.validKeys

        private val siteSchemeRegex = Regex("""(?i)^https?://""")
        private val validSiteHostRegex = Regex(
            pattern = """^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$""",
            option = RegexOption.IGNORE_CASE
        )
        private val advancedSearchGenreOptions = listOf(
            ArchiveGenreOption(51, "Action"),
            ArchiveGenreOption(21, "Adventure"),
            ArchiveGenreOption(43, "Avant Garde"),
            ArchiveGenreOption(59, "Boys Love"),
            ArchiveGenreOption(37, "Comedy"),
            ArchiveGenreOption(13, "Demons"),
            ArchiveGenreOption(22, "Drama"),
            ArchiveGenreOption(5, "Ecchi"),
            ArchiveGenreOption(9, "Fantasy"),
            ArchiveGenreOption(44, "Game"),
            ArchiveGenreOption(58, "Girls Love"),
            ArchiveGenreOption(52, "Gore"),
            ArchiveGenreOption(56, "Gourmet"),
            ArchiveGenreOption(15, "Harem"),
            ArchiveGenreOption(4, "Hentai"),
            ArchiveGenreOption(30, "Historical"),
            ArchiveGenreOption(3, "Horror"),
            ArchiveGenreOption(53, "Isekai"),
            ArchiveGenreOption(45, "Josei"),
            ArchiveGenreOption(14, "Kids"),
            ArchiveGenreOption(57, "Mahou Shoujo"),
            ArchiveGenreOption(31, "Martial Arts"),
            ArchiveGenreOption(38, "Mecha"),
            ArchiveGenreOption(46, "Military"),
            ArchiveGenreOption(16, "Music"),
            ArchiveGenreOption(24, "Mystery"),
            ArchiveGenreOption(32, "Parody"),
            ArchiveGenreOption(39, "Police"),
            ArchiveGenreOption(47, "Psychological"),
            ArchiveGenreOption(29, "Racing"),
            ArchiveGenreOption(54, "Reincarnation"),
            ArchiveGenreOption(17, "Romance"),
            ArchiveGenreOption(25, "Samurai"),
            ArchiveGenreOption(33, "School"),
            ArchiveGenreOption(40, "Sci-fi"),
            ArchiveGenreOption(49, "Seinen"),
            ArchiveGenreOption(18, "Shoujo"),
            ArchiveGenreOption(34, "Shounen"),
            ArchiveGenreOption(50, "Slice of Life"),
            ArchiveGenreOption(19, "Space"),
            ArchiveGenreOption(27, "Sports"),
            ArchiveGenreOption(35, "Super Power"),
            ArchiveGenreOption(42, "Supernatural"),
            ArchiveGenreOption(55, "Survival"),
            ArchiveGenreOption(48, "Thriller"),
            ArchiveGenreOption(20, "Vampire"),
        )
        private val advancedSearchOrderOptions = listOf(
            "Lista A-Z",
            "Lista Z-A",
            "Popolarit\u00E0",
            "Valutazione",
        )
        private val advancedSearchStatusOptions = listOf(
            "In Corso",
            "Terminato",
            "In Uscita",
            "Droppato",
        )
        private val advancedSearchTypeOptions = listOf(
            "TV",
            "TV Short",
            "OVA",
            "ONA",
            "Special",
            "Movie",
        )
        private val advancedSearchSeasonOptions = listOf(
            "Inverno",
            "Primavera",
            "Estate",
            "Autunno",
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

        fun getConfiguredSectionTitle(
            sharedPref: SharedPreferences?,
            prefKey: String,
            fallback: String,
        ): String {
            return sharedPref?.getString(prefKey, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallback
        }

        fun shouldUseUnifiedDubSubCards(sharedPref: SharedPreferences?): Boolean {
            return sharedPref?.getBoolean(
                PREF_UNIFY_DUB_SUB_CARDS,
                DEFAULT_UNIFY_DUB_SUB_CARDS,
            ) ?: DEFAULT_UNIFY_DUB_SUB_CARDS
        }

        fun getAdvancedSearchGenres(): List<ArchiveGenreOption> {
            return advancedSearchGenreOptions
        }

        fun getAdvancedSearchYearOptions(): List<String> {
            val latestYear = Calendar.getInstance().get(Calendar.YEAR) + 1
            return (latestYear downTo ARCHIVE_OLDEST_YEAR).map(Int::toString)
        }

        fun getAdvancedSearchOrderOptions(): List<String> {
            return advancedSearchOrderOptions
        }

        fun getAdvancedSearchStatusOptions(): List<String> {
            return advancedSearchStatusOptions
        }

        fun getAdvancedSearchTypeOptions(): List<String> {
            return advancedSearchTypeOptions
        }

        fun getAdvancedSearchSeasonOptions(): List<String> {
            return advancedSearchSeasonOptions
        }

        private fun getValidatedAdvancedOption(
            value: String?,
            options: List<String>,
        ): String? {
            val normalizedValue = value?.trim().orEmpty()
            if (normalizedValue.isBlank()) return null
            return options.firstOrNull { it.equals(normalizedValue, ignoreCase = true) }
        }

        fun getAdvancedSearchConfig(sharedPref: SharedPreferences?): AdvancedSearchConfig {
            val genreId = sharedPref?.getInt(PREF_ADVANCED_SEARCH_GENRE_ID, -1) ?: -1

            return AdvancedSearchConfig(
                enabled = sharedPref?.getBoolean(PREF_ENABLE_ADVANCED_SEARCH, false) ?: false,
                title = sharedPref?.getString(PREF_ADVANCED_SEARCH_TITLE, null)?.trim().orEmpty(),
                genre = advancedSearchGenreOptions.firstOrNull { it.id == genreId },
                year = getValidatedAdvancedOption(
                    sharedPref?.getString(PREF_ADVANCED_SEARCH_YEAR, null),
                    getAdvancedSearchYearOptions(),
                ),
                order = getValidatedAdvancedOption(
                    sharedPref?.getString(PREF_ADVANCED_SEARCH_ORDER, null),
                    advancedSearchOrderOptions,
                ),
                status = getValidatedAdvancedOption(
                    sharedPref?.getString(PREF_ADVANCED_SEARCH_STATUS, null),
                    advancedSearchStatusOptions,
                ),
                type = getValidatedAdvancedOption(
                    sharedPref?.getString(PREF_ADVANCED_SEARCH_TYPE, null),
                    advancedSearchTypeOptions,
                ),
                season = getValidatedAdvancedOption(
                    sharedPref?.getString(PREF_ADVANCED_SEARCH_SEASON, null),
                    advancedSearchSeasonOptions,
                ),
            )
        }

        fun isAdvancedSearchEnabled(sharedPref: SharedPreferences?): Boolean {
            return getAdvancedSearchConfig(sharedPref).enabled
        }

        fun getAdvancedSearchRequestData(sharedPref: SharedPreferences?): RequestData {
            val config = getAdvancedSearchConfig(sharedPref)

            return RequestData(
                title = config.title,
                type = config.type?.let(BooleanOrString::AsString) ?: BooleanOrString.AsBoolean(false),
                year = config.year?.let(BooleanOrString::AsString) ?: BooleanOrString.AsBoolean(false),
                orderBy = config.order?.let(BooleanOrString::AsString) ?: BooleanOrString.AsBoolean(false),
                status = config.status?.let(BooleanOrString::AsString) ?: BooleanOrString.AsBoolean(false),
                genres = config.genre?.let { listOf(it) } ?: BooleanOrString.AsBoolean(false),
                season = config.season?.let(BooleanOrString::AsString) ?: BooleanOrString.AsBoolean(false),
                dubbed = 0,
            )
        }

        fun getCacheMaxEntries(sharedPref: SharedPreferences?): Int {
            return (sharedPref?.getInt(PREF_CACHE_MAX_ENTRIES, DEFAULT_CACHE_MAX_ENTRIES)
                ?: DEFAULT_CACHE_MAX_ENTRIES)
                .coerceIn(MIN_CACHE_MAX_ENTRIES, MAX_CACHE_MAX_ENTRIES)
        }

        fun getCacheMaxSizeMb(sharedPref: SharedPreferences?): Int {
            return (sharedPref?.getInt(PREF_CACHE_MAX_SIZE_MB, DEFAULT_CACHE_MAX_SIZE_MB)
                ?: DEFAULT_CACHE_MAX_SIZE_MB)
                .coerceIn(MIN_CACHE_MAX_SIZE_MB, MAX_CACHE_MAX_SIZE_MB)
        }

        fun getCacheMaxBytes(sharedPref: SharedPreferences?): Long {
            return getCacheMaxSizeMb(sharedPref) * 1024L * 1024L
        }

        internal var activePlugin: AnimeUnityPlugin? = null
        internal var activeSharedPref: SharedPreferences? = null
    }

    private var sharedPref: SharedPreferences? = null

    override fun load(context: Context) {
        sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        AnimeUnityCache.init(context.applicationContext, sharedPref)
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
