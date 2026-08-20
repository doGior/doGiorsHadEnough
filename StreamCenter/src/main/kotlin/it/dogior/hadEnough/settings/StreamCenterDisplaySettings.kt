package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
class StreamCenterDisplaySettingsFragment : StreamCenterBaseSettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, dp(8), paddingRight, paddingBottom)
        }
        content.minimumHeight = standardSubmenuMinimumHeight()
        content.addView(
            header(
                title = "Preferenze",
                icon = "🖼️",
                accent = COLOR_DISPLAY,
            ),
        )
        val displayRows = mutableListOf<View>()
        displayRows.add(
            switchRow(
                title = "Valutazione",
                summary = "Mostra il voto sulle schede di\nAnime, Serie TV e Film.",
                checked = StreamCenterPlugin.shouldShowHomeScore(sharedPref),
                accent = COLOR_SCORE,
                icon = "⭐",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, enabled) }
            },
        )
        displayRows.add(
            switchRow(
                title = "SUB/DUB",
                summary = "Mostra se l'Anime è SUB, DUB o entrambe le versioni.",
                checked = StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref),
                accent = COLOR_ANIME_VARIANTS,
                icon = "🎙️",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_DUB_STATUS, enabled) }
            },
        )
        displayRows.add(
            switchRow(
                title = "Unifica SUB e DUB",
                summary = "Raggruppa le versioni sottotitolata e doppiata in un’unica scheda.",
                checked = StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref),
                accent = COLOR_ANIME_VARIANTS,
                icon = "🔗",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_GROUP_ANIME_DUB_SUB, enabled) }
            },
        )
        displayRows.add(
            switchRow(
                title = "Numero episodi",
                summary = "Mostra sulle schede Anime l'ultimo episodio disponibile.",
                checked = StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref),
                accent = COLOR_EPISODES,
                icon = "🔢",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, enabled) }
            },
        )
        displayRows.add(
            switchRow(
                title = "ID di tracciamento",
                summary = "Mostra gli ID MAL, AniList, Kitsu, Simkl e IMDb nelle schede del Catalogo Base.",
                checked = StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                accent = COLOR_TRACKING_IDS,
                icon = "🆔",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_TRACKING_IDS, enabled) }
            },
        )
        displayRows.add(
            switchRow(
                title = "Protezione VPN",
                summary = "Blocca tutte le richieste Internet di StreamCenter finch\u00E9 la VPN non \u00E8 attiva.",
                checked = StreamCenterPlugin.isVpnRequired(sharedPref),
                accent = COLOR_VPN_GUARD,
                icon = "\uD83D\uDEE1\uFE0F",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_REQUIRE_VPN, enabled) }
            },
        )
        displayRows.add(
            switchRow(
                title = "Modalità TV",
                summary = "UI pensata per la TV.",
                checked = isTvLikeDevice(),
                accent = COLOR_DISPLAY,
                icon = "📺",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_FORCE_TV_MODE, enabled) }
                saveToast("Riapri le impostazioni per applicare la modalità TV")
            },
        )
        displayRows.add(animeCardTitleRow())
        displayRows.add(visualEffectsRow())
        addAdaptiveCardGrid(content, displayRows)
        return scroll(content, fixedSubmenuHeight = true)
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
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            fixedHeight = true,
        ) { showAnimeCardTitlePicker(selectedTitle) }.view
    }

    private fun visualEffectsRow(): LinearLayout {
        val arrow = chevron(COLOR_VISUAL_EFFECTS)
        return settingsRow(
            title = "Effetti visivi",
            summary = "",
            icon = "\u2728",
            accent = COLOR_VISUAL_EFFECTS,
            fillColor = COLOR_CARD_ALT,
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            fixedHeight = true,
        ) { showVisualEffectsDialog() }.view
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
            accent = COLOR_DISPLAY,
        ) { selected ->
            sharedPref?.edit { putString(StreamCenterPlugin.PREF_ANIME_CARD_TITLE, selected.value) }
            selectedTitle.text = selected.label
        }
    }

    private fun showVisualEffectsDialog() {
        val ctx = context ?: return
        fun effectSelected(preferenceKey: String, defaultValue: Boolean): Boolean {
            return sharedPref?.getBoolean(preferenceKey, defaultValue) ?: defaultValue
        }

        fun effectOptionRow(
            icon: String,
            title: String,
            preferenceKey: String,
            optionAccent: String,
            defaultValue: Boolean = true,
        ): LinearLayout {
            return switchRow(
                title = title,
                summary = null,
                checked = effectSelected(preferenceKey, defaultValue),
                accent = optionAccent,
                icon = icon,
                strokeColor = tint(optionAccent, "55"),
                topMargin = 8,
            ) { enabled ->
                sharedPref?.edit { putBoolean(preferenceKey, enabled) }
                refreshVisibleSettingsEffects()
            }
        }

        val effectRows = mutableListOf<View>()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            effectRows.add(effectOptionRow(
                icon = "\uD83C\uDF9E\uFE0F",
                title = "Animazioni",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS,
                optionAccent = COLOR_VISUAL_EFFECTS,
            ))
            effectRows.add(effectOptionRow(
                icon = "\uD83C\uDF2B\uFE0F",
                title = "Sfocatura finestre",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR,
                optionAccent = COLOR_VISUAL_BLUR,
            ))
            effectRows.add(effectOptionRow(
                icon = "\u2728",
                title = "Intestazione StreamCenter",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE,
                optionAccent = COLOR_VISUAL_HEADER,
            ))
            effectRows.add(effectOptionRow(
                icon = "\uD83C\uDF0C",
                title = "Universo animato",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES,
                optionAccent = COLOR_PARTICLES,
            ))
            effectRows.add(effectOptionRow(
                icon = "\uD83C\uDF10",
                title = "Mostra IP pubblico",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PUBLIC_IP,
                optionAccent = COLOR_PUBLIC_IP,
                defaultValue = true,
            ))
        }
        addAdaptiveCardGrid(content, effectRows)

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Effetti visivi"))
            .setView(scrollableDialogView(content))
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }
}
