package it.dogior.hadEnough.settings

import android.content.SharedPreferences
import it.dogior.hadEnough.StreamCenterPlugin

internal class StreamCenterSettingsRestartState {
    private var baseline: Map<String, Any>? = null
    private var explicitlyRequired = false

    fun reset(preferences: SharedPreferences?) {
        baseline = snapshot(preferences)
        explicitlyRequired = false
    }

    fun requireRestart() {
        explicitlyRequired = true
    }

    fun consume(preferences: SharedPreferences?): Boolean {
        val changed = explicitlyRequired || baseline?.let { it != snapshot(preferences) } == true
        baseline = null
        explicitlyRequired = false
        return changed
    }

    private fun snapshot(preferences: SharedPreferences?): Map<String, Any> = with(StreamCenterPlugin) {
        val ignoredKeys = setOf(
            "streamcenter_au_session", "streamcenter_sc_session",
            PREF_HOME_ORDER, PREF_HOME_FREE_ORDER, PREF_HOME_CATEGORY_ORDER,
            PREF_ANIME_CUSTOM_SECTION_COUNTER, PREF_TV_CUSTOM_SECTION_COUNTER, PREF_MOVIE_CUSTOM_SECTION_COUNTER,
            PREF_TRACKING_CUSTOM_SECTION_COUNTER, PREF_IPTV_CUSTOM_SECTION_COUNTER, PREF_IPTV_CUSTOM_PLAYLIST_COUNTER,
        ) + streamingSources.map { it.urlPrefKey }
        val values = StreamCenterConfigurationStore.portable(preferences?.all.orEmpty())
            .filterKeys { it !in ignoredKeys && !it.startsWith("home_") && !it.startsWith("stremioAddonEnabled_") }
            .toMutableMap()

        values[PREF_HOME_ORDER] = getOrderedHomeSections(preferences).map { section ->
            listOf(section.key, getHomeSectionTitleTemplate(preferences, section),
                getHomeSectionCount(preferences, section), isHomeSectionEnabled(preferences, section))
        }
        values[PREF_HOME_CATEGORY_ORDER] = getHomeCategoryOrder(preferences)
        homeCategories.forEach { values[homeCategoryEnabledKey(it)] = isHomeCategoryEnabled(preferences, it) }

        values[PREF_STREMIO_ADDONS] = getStremioAddons(preferences).map {
            it.manifestUrl to isStremioAddonEnabled(preferences, it.key)
        }
        values[PREF_SOURCE_PRIORITY] = getSourcePriorityOrder(preferences)
        streamingSources.forEach { values[it.key] = isStreamingSourceEnabled(preferences, it.key) }

        values[PREF_SHOW_HOME_SCORE] = shouldShowHomeScore(preferences)
        values[PREF_SHOW_ANIME_HOME_DUB_STATUS] = shouldShowAnimeHomeDubStatus(preferences)
        values[PREF_SHOW_ANIME_HOME_EPISODE_NUMBER] = shouldShowAnimeHomeEpisodeNumber(preferences)
        values[PREF_SHOW_TRACKING_IDS] = shouldShowTrackingIds(preferences)
        listOf(PREF_SHOW_ID_MAL, PREF_SHOW_ID_ANILIST, PREF_SHOW_ID_KITSU, PREF_SHOW_ID_SIMKL,
            PREF_SHOW_ID_IMDB, PREF_SHOW_ID_TMDB).forEach { values[it] = preferences?.getBoolean(it, true) ?: true }
        values[PREF_ANIME_CARD_TITLE] = getAnimeCardTitle(preferences)
        values[PREF_GROUP_ANIME_DUB_SUB] = shouldGroupAnimeVariants(preferences)
        values[PREF_GROUP_ANIME_SEASONS] = shouldGroupAnimeSeasons(preferences)
        values[PREF_PERFORMANCE_MODE] = isPerformanceModeEnabled(preferences)
        values[PREF_FORCE_TV_MODE] = isForceTvModeEnabled(preferences)
        values[PREF_REQUIRE_VPN] = isVpnRequired(preferences)
        values[PREF_TORRENT_ENABLED] = isTorrentEnabled(preferences)
        values[PREF_TMDB_SEARCH_FALLBACK] = isTmdbSearchFallbackEnabled(preferences)
        values[PREF_AUTO_UPDATE_SOURCE_URLS] = isSourceUrlAutoUpdateEnabled(preferences)
        values[PREF_MEDIA_CACHE_ENABLED] = isMediaCacheEnabled(preferences)
        values[PREF_MEDIA_CACHE_MAX_ENTRIES] = mediaCacheMaxEntries(preferences)
        values[PREF_MEDIA_CACHE_MAX_MB] = mediaCacheMaxMb(preferences)
        values[PREF_ANILIST_RPM] = getAnilistRequestsPerMinute(preferences)
        values[PREF_VISUAL_EFFECTS_ANIMATIONS] = areVisualAnimationsEnabled(preferences)
        values[PREF_VISUAL_EFFECTS_BLUR] = areVisualBlursEnabled(preferences)
        values[PREF_VISUAL_EFFECTS_TITLE] = areVisualTitleEffectsEnabled(preferences)
        values[PREF_VISUAL_EFFECTS_PARTICLES] = areVisualParticlesEnabled(preferences)
        values[PREF_VISUAL_EFFECTS_PUBLIC_IP] = shouldShowPublicIp(preferences)
        values
    }
}
