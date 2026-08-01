package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*

import android.animation.ArgbEvaluator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin
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
        content.addView(
            switchRow(
                title = "Valutazione",
                summary = "Mostra il voto nelle schede di Anime, Film e Serie TV.",
                checked = StreamCenterPlugin.shouldShowHomeScore(sharedPref),
                accent = COLOR_SCORE,
                icon = "⭐",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, enabled) }
            },
        )
        content.addView(
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
        content.addView(
            switchRow(
                title = "Unifica SUB e DUB",
                summary = "Raggruppa le versioni sottotitolata e doppiata nella stessa scheda.",
                checked = StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref),
                accent = COLOR_ANIME_VARIANTS,
                icon = "🔗",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_GROUP_ANIME_DUB_SUB, enabled) }
            },
        )
        content.addView(
            switchRow(
                title = "Numero episodi",
                summary = "Mostra nelle schede Anime l'ultimo episodio disponibile.",
                checked = StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref),
                accent = COLOR_EPISODES,
                icon = "🔢",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, enabled) }
            },
        )
        content.addView(
            switchRow(
                title = "ID di tracciamento",
                summary = "Mostra gli ID MAL, AniList, Kitsu e Simkl nelle schede del Catalogo Base.",
                checked = StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                accent = COLOR_TRACKING_IDS,
                icon = "🆔",
                fixedHeight = true,
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_TRACKING_IDS, enabled) }
            },
        )
        content.addView(animeCardTitleRow())
        content.addView(visualEffectsRow())
        val displayAccents = listOf(
            COLOR_SCORE,
            COLOR_ANIME_VARIANTS,
            COLOR_ANIME_VARIANTS,
            COLOR_EPISODES,
            COLOR_TRACKING_IDS,
            COLOR_DISPLAY,
            COLOR_VISUAL_EFFECTS,
        )
        startBorderSparkleCycle(displayAccents.mapIndexedNotNull { index, accent ->
            (content.getChildAt(index + 1) as? ViewGroup)?.let { BorderSparkleTarget(it, accent) }
        })
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
            summary = "Toggle per Animazioni, Sfocature, Bagliori e IP pubblico.",
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
            SettingsChoiceOption("Italiano", StreamCenterPlugin.ANIME_CARD_TITLE_ANIZIP, "IT"),
            SettingsChoiceOption("Da AnimeUnity", StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY, "AU"),
            SettingsChoiceOption("Romaji", StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI, "R"),
            SettingsChoiceOption("Inglese", StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH, "EN"),
            SettingsChoiceOption("Nativo", StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE, "文"),
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

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(effectOptionRow(
                icon = "\uD83C\uDF9E\uFE0F",
                title = "Animazioni",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS,
                optionAccent = COLOR_VISUAL_EFFECTS,
            ))
            addView(effectOptionRow(
                icon = "\uD83C\uDF2B\uFE0F",
                title = "Sfocatura finestre",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR,
                optionAccent = COLOR_VISUAL_BLUR,
            ))
            addView(effectOptionRow(
                icon = "\u2728",
                title = "Intestazione StreamCenter",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE,
                optionAccent = COLOR_VISUAL_HEADER,
            ))
            addView(effectOptionRow(
                icon = "\uD83C\uDF0C",
                title = "Universo animato",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES,
                optionAccent = COLOR_PARTICLES,
            ))
            addView(effectOptionRow(
                icon = "\uD83C\uDF10",
                title = "Mostra IP pubblico",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PUBLIC_IP,
                optionAccent = COLOR_PUBLIC_IP,
                defaultValue = true,
            ))
        }

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Effetti visivi"))
            .setView(content)
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }
}
