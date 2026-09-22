package it.dogior.hadEnough.settings

import it.dogior.hadEnough.StreamCenterPlugin

import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit

abstract class StreamCenterCardSettingsFragment : StreamCenterBaseSettingsFragment() {
    private data class TrackingService(
        val fallback: String,
        val label: String,
        val preferenceKey: String,
        val websiteUrl: String,
    )

    protected fun cardContentRows(): List<View> = listOf(
        scoreRow(),
        dubStatusRow(),
        episodeNumberRow(),
        groupVariantsRow(),
        groupSeasonsRow(),
        animeCardTitleRow(),
        trackingIdsRow(),
    )

    private fun scoreRow(): View = switchRow(
        title = "Valutazione",
        summary = "Mostra il voto sulle schede di\nAnime, Serie TV e Film.",
        checked = StreamCenterPlugin.shouldShowHomeScore(sharedPref),
        defaultChecked = StreamCenterPlugin.shouldShowHomeScore(null),
        accent = COLOR_SCORE,
        icon = "⭐",
        fixedHeight = true,
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, enabled) }
    }

    private fun dubStatusRow(): View = switchRow(
        title = "SUB/DUB",
        summary = "Mostra se l'Anime è SUB, DUB o entrambe le versioni.",
        checked = StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref),
        defaultChecked = StreamCenterPlugin.shouldShowAnimeHomeDubStatus(null),
        accent = COLOR_ANIME_VARIANTS,
        icon = "🎙️",
        fixedHeight = true,
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_DUB_STATUS, enabled) }
    }

    private fun episodeNumberRow(): View = switchRow(
        title = "Numero episodi",
        summary = "Mostra sulle schede Anime l'ultimo episodio disponibile.",
        checked = StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref),
        defaultChecked = StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(null),
        accent = COLOR_EPISODES,
        icon = "🔢",
        fixedHeight = true,
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, enabled) }
    }

    private fun groupVariantsRow(): View = switchRow(
        title = "Unifica SUB e DUB",
        summary = "Raggruppa le versioni sottotitolata e doppiata in un’unica scheda.",
        checked = StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref),
        defaultChecked = StreamCenterPlugin.shouldGroupAnimeVariants(null),
        accent = COLOR_ANIME_VARIANTS,
        icon = "🔗",
        fixedHeight = true,
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_GROUP_ANIME_DUB_SUB, enabled) }
    }

    private fun groupSeasonsRow(): View = switchRow(
        title = "Unifica stagioni Anime",
        summary = "Stagioni e parti nella stessa scheda. Disattiva il tracciamento.",
        checked = StreamCenterPlugin.shouldGroupAnimeSeasons(sharedPref),
        defaultChecked = false,
        accent = COLOR_ANIME_VARIANTS,
        icon = "📚",
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_GROUP_ANIME_SEASONS, enabled) }
    }

    private fun animeCardTitleRow(): LinearLayout {
        val selectedTitle = bodyText(animeCardTitleLabel(), 12)
        val arrow = chevron(COLOR_DISPLAY)
        return settingsRow(
            title = "Titolo Anime",
            icon = "✍️",
            accent = COLOR_DISPLAY,
            fillColor = COLOR_CARD_ALT,
            summaryView = selectedTitle,
            onReset = {
                sharedPref?.edit { remove(StreamCenterPlugin.PREF_ANIME_CARD_TITLE) }
                selectedTitle.text = animeCardTitleLabel()
            },
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            fixedHeight = true,
        ) { showAnimeCardTitlePicker(selectedTitle) }.view
    }

    private fun trackingIdsRow(): LinearLayout {
        val arrow = chevron(COLOR_TRACKING_IDS)
        return settingsRow(
            title = "ID di tracciamento",
            summary = "MyAnimeList, AniList, Kitsu, Simkl, IMDb, TMDB.",
            onReset = { sharedPref?.edit { remove(StreamCenterPlugin.PREF_SHOW_TRACKING_IDS) } },
            icon = "🆔",
            accent = COLOR_TRACKING_IDS,
            fillColor = COLOR_CARD_ALT,
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            fixedHeight = true,
        ) { showTrackingIdsDialog() }.view
    }

    private fun animeCardTitleLabel(): String {
        return when (StreamCenterPlugin.getAnimeCardTitle(sharedPref)) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY -> "Da AnimeUnity"
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> "Romaji"
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> "Inglese"
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> "Nativo"
            else -> "Italiano"
        }
    }

    private fun showAnimeCardTitlePicker(selectedTitle: TextView) {
        val options = listOf(
            SettingsChoiceOption("Italiano", StreamCenterPlugin.ANIME_CARD_TITLE_ANIZIP, "🇮🇹"),
            SettingsChoiceOption(
                "Da AnimeUnity",
                StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY,
                "AU",
                badgeWebsiteUrl = StreamCenterPlugin.getSourceBaseUrl(
                    sharedPref,
                    StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY,
                ),
            ),
            SettingsChoiceOption("Romaji", StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI, "🇯🇵"),
            SettingsChoiceOption("Inglese", StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH, "🇬🇧"),
            SettingsChoiceOption("Nativo", StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE, "🈯"),
        )
        showSettingsChoiceDialog(
            title = "Titolo Anime",
            options = options,
            selectedValue = StreamCenterPlugin.getAnimeCardTitle(sharedPref),
            defaultValue = StreamCenterPlugin.getAnimeCardTitle(null),
            accent = COLOR_DISPLAY,
            closeLabel = "Chiudi",
        ) { selected ->
            sharedPref?.edit { putString(StreamCenterPlugin.PREF_ANIME_CARD_TITLE, selected.value) }
            selectedTitle.text = selected.label
        }
    }

    private fun showTrackingIdsDialog() {
        val ctx = context ?: return

        val animeServices = listOf(
            TrackingService("🌸", "MyAnimeList", StreamCenterPlugin.PREF_SHOW_ID_MAL, "https://myanimelist.net"),
            TrackingService("📊", "AniList", StreamCenterPlugin.PREF_SHOW_ID_ANILIST, "https://anilist.co"),
            TrackingService("🦊", "Kitsu", StreamCenterPlugin.PREF_SHOW_ID_KITSU, "https://kitsu.io"),
        )
        val serieTvFilmServices = listOf(
            TrackingService("📺", "Simkl", StreamCenterPlugin.PREF_SHOW_ID_SIMKL, "https://simkl.com"),
            TrackingService("🎬", "IMDb", StreamCenterPlugin.PREF_SHOW_ID_IMDB, "https://www.imdb.com"),
            TrackingService("🎥", "TMDB", StreamCenterPlugin.PREF_SHOW_ID_TMDB, "https://www.themoviedb.org"),
        )

        fun serviceRow(service: TrackingService): LinearLayout = switchRow(
            title = service.label,
            checked = sharedPref?.getBoolean(service.preferenceKey, true) ?: true,
            defaultChecked = true,
            accent = COLOR_TRACKING_IDS,
            leadingView = siteIconBadge(
                fallback = service.fallback,
                accent = COLOR_TRACKING_IDS,
                contentDescription = service.label,
                websiteUrl = service.websiteUrl,
            ),
            strokeColor = tint(COLOR_TRACKING_IDS, "55"),
            topMargin = 8,
        ) { enabled ->
            sharedPref?.edit { putBoolean(service.preferenceKey, enabled) }
        }

        val animeRows = animeServices.map(::serviceRow)
        val serieTvFilmRows = serieTvFilmServices.map(::serviceRow)
        val serviceRows = animeRows + serieTvFilmRows

        fun applyMasterState(enabled: Boolean) {
            serviceRows.forEach { setRowEnabled(it, enabled) }
        }

        val master = switchRow(
            title = "Mostra ID di tracciamento",
            summary = "Mostra gli ID sulle schede. I servizi qui sotto scelgono quali appaiono.",
            checked = StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
            defaultChecked = StreamCenterPlugin.shouldShowTrackingIds(null),
            accent = COLOR_TRACKING_IDS,
            icon = "🆔",
            strokeColor = tint(COLOR_TRACKING_IDS, "55"),
            topMargin = 8,
        ) { enabled ->
            sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_TRACKING_IDS, enabled) }
            applyMasterState(enabled)
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(master)
        }
        content.addView(sectionHeading("Anime", COLOR_TRACKING_IDS, topMargin = 14))
        addAdaptiveCardGrid(content, animeRows)
        content.addView(sectionHeading("Serie TV / Film", COLOR_TRACKING_IDS, topMargin = 14))
        addAdaptiveCardGrid(content, serieTvFilmRows)

        applyMasterState(StreamCenterPlugin.shouldShowTrackingIds(sharedPref))

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("ID di tracciamento"))
            .setView(scrollableDialogView(content))
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun setRowEnabled(row: View, enabled: Boolean) {
        row.alpha = when {
            !enabled -> 0.45f
            rowSwitchChecked(row) -> 1f
            else -> settingsRowDisabledAlpha
        }
        row.isEnabled = enabled
        row.isClickable = enabled
        row.isFocusable = enabled
        (row as? ViewGroup)?.let { setControlsEnabled(it, enabled) }
    }

    private fun rowSwitchChecked(view: View): Boolean = when (view) {
        is CompoundButton -> view.isChecked
        is ViewGroup -> (0 until view.childCount).any { rowSwitchChecked(view.getChildAt(it)) }
        else -> false
    }

    private fun setControlsEnabled(group: ViewGroup, enabled: Boolean) {
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is CompoundButton -> child.isEnabled = enabled
                is ViewGroup -> setControlsEnabled(child, enabled)
            }
        }
    }
}
