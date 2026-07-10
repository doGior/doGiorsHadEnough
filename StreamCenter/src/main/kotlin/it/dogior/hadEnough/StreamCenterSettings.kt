package it.dogior.hadEnough

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val COLOR_BACKGROUND = "#11141A"
private const val COLOR_CARD = "#1A2029"
private const val COLOR_CARD_ALT = "#202733"
private const val COLOR_CARD_DISABLED = "#151922"
private const val COLOR_TEXT = "#F4F7FB"
private const val COLOR_MUTED = "#AAB3C2"
private const val COLOR_STROKE = "#2E3746"
private const val COLOR_ACCENT = "#4CC9F0"
private const val COLOR_SUCCESS = "#7CFF9D"
private const val COLOR_WARN = "#FFB703"
private const val COLOR_DANGER = "#FF7F7F"
private const val COLOR_THUMB_OFF = "#8A94A6"
private const val COLOR_TRACK_OFF = "#39424F"
private const val COLOR_INPUT_FILL = "#10151D"

private fun tint(color: String, alphaHex: String): String = "#$alphaHex${color.removePrefix("#")}"

private data class HomeRowState(
    val section: StreamCenterHomeSectionDefinition,
    var title: String,
    var enabled: Boolean,
    var count: Int,
)

abstract class StreamCenterBaseSettingsFragment : BottomSheetDialogFragment() {
    companion object {
        private var skipNextMainSettingsRestartPrompt = false
    }

    private var dismissCallback: (() -> Unit)? = null
    private var playedEnterAnimation = false

    protected val sharedPref: SharedPreferences?
        get() = StreamCenterPlugin.activeSharedPref

    protected val reduceMotion: Boolean
        get() = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            dismissWithAnimation = !reduceMotion
            behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = false
            }
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        if (!playedEnterAnimation) {
            playedEnterAnimation = true
            if (!reduceMotion) {
                view?.let { content ->
                    content.alpha = 0f
                    content.translationY = dp(28).toFloat()
                    content.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissCallback?.invoke()
        dismissCallback = null
    }

    fun onDismissed(callback: () -> Unit): StreamCenterBaseSettingsFragment {
        dismissCallback = callback
        return this
    }

    protected fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    protected fun rootContainer(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
    }

    protected fun scroll(content: LinearLayout): ScrollView {
        return ScrollView(requireContext()).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_BACKGROUND))
                val radius = dp(22).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    protected fun header(
        title: String,
        subtitle: String? = null,
        icon: String? = null,
        accent: String = COLOR_ACCENT,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))

            icon?.let {
                addView(iconBadge(it, accent, size = 46, marginEnd = 12))
            }

            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginEnd = dp(8)
                }
            }
            texts.addView(titleText(title, 22, true))
            subtitle?.let {
                texts.addView(bodyText(it, 12).apply {
                    alpha = 0.82f
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(texts)

            if (actionText != null && onAction != null) {
                addView(actionButton(actionText, accent, onAction).apply {
                    minWidth = dp(82)
                    minimumHeight = dp(40)
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginStart = dp(12)
                    }
                })
            }
        }
    }

    protected fun titleText(value: String, size: Int = 18, bold: Boolean = true): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(Color.parseColor(COLOR_TEXT))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    protected fun bodyText(value: String, size: Int = 13): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(Color.parseColor(COLOR_MUTED))
            setLineSpacing(dp(1).toFloat(), 1.0f)
        }
    }

    protected fun sectionLabel(value: String): TextView {
        return bodyText(value.uppercase(), 11).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
    }

    protected fun chip(value: String, color: String = COLOR_ACCENT): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = outlined(tint(color, "66"), tint(color, "1A"), 999)
        }
    }

    protected fun iconBadge(
        emoji: String,
        accent: String = COLOR_ACCENT,
        size: Int = 40,
        marginEnd: Int = 0,
    ): TextView {
        return TextView(requireContext()).apply {
            text = emoji
            textSize = if (size >= 44) 20f else 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = outlined(tint(accent, "40"), tint(accent, "1C"), 13)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                this.marginEnd = dp(marginEnd)
            }
        }
    }

    protected fun chevron(accent: String): TextView {
        return TextView(requireContext()).apply {
            text = "›"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accent))
            setPadding(dp(10), 0, dp(4), dp(2))
        }
    }

    protected fun styledSwitch(
        checked: Boolean,
        accent: String = COLOR_ACCENT,
        onChanged: (Boolean) -> Unit,
    ): SwitchCompat {
        return SwitchCompat(requireContext()).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(
                states,
                intArrayOf(Color.parseColor(accent), Color.parseColor(COLOR_THUMB_OFF)),
            )
            trackTintList = ColorStateList(
                states,
                intArrayOf(Color.parseColor(tint(accent, "66")), Color.parseColor(COLOR_TRACK_OFF)),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(12)
            }
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
    }

    protected fun switchRow(
        title: String,
        summary: String,
        checked: Boolean,
        accent: String = COLOR_ACCENT,
        icon: String? = null,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_CARD_ALT, radius = 14)
            layoutParams = verticalParams(top = 10)
            clipChildren = false
            clipToPadding = false

            val badge = icon?.let {
                iconBadge(it, accent, size = 38, marginEnd = 12).also(::addView)
            }

            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 15, true))
            texts.addView(bodyText(summary, 12).apply { setPadding(0, dp(3), 0, 0) })
            addView(texts)

            addView(styledSwitch(checked, accent) { isChecked ->
                playToggleFeedback(rowView, badge, accent, isChecked)
                onChanged(isChecked)
            })
        }
    }

    protected fun playToggleFeedback(row: View, badge: View?, accent: String, enabled: Boolean) {
        if (reduceMotion) return
        badge?.apply {
            animate().cancel()
            scaleX = 1f
            scaleY = 1f
            rotation = 0f
            val targetScale = if (enabled) 1.35f else 0.82f
            animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .rotation(if (enabled) 14f else 0f)
                .setDuration(150L)
                .withEndAction {
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .rotation(0f)
                        .setDuration(220L)
                        .start()
                }
                .start()
        }
        val background = row.background as? GradientDrawable ?: return
        val base = Color.parseColor(COLOR_CARD_ALT)
        val flash = if (enabled) {
            ColorUtils.blendARGB(base, Color.parseColor(accent), 0.30f)
        } else {
            ColorUtils.blendARGB(base, Color.parseColor(COLOR_THUMB_OFF), 0.25f)
        }
        ValueAnimator.ofObject(ArgbEvaluator(), base, flash, base).apply {
            duration = 550L
            addUpdateListener { animator -> background.setColor(animator.animatedValue as Int) }
            start()
        }
    }

    protected fun animateCardFill(
        view: View,
        fromColor: String,
        toColor: String,
        strokeColor: String = COLOR_STROKE,
        radius: Int = 14,
    ) {
        if (reduceMotion) {
            view.background = cardBackground(toColor, strokeColor, radius)
            return
        }
        val drawable = cardBackground(fromColor, strokeColor, radius)
        view.background = drawable
        ValueAnimator.ofObject(
            ArgbEvaluator(),
            Color.parseColor(fromColor),
            Color.parseColor(toColor),
        ).apply {
            duration = 260L
            addUpdateListener { animator -> drawable.setColor(animator.animatedValue as Int) }
            start()
        }
    }

    protected fun actionButton(textValue: String, color: String, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            text = textValue
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = interactiveBackground(tint(color, "18"), color, 12, strokeColor = tint(color, "88"))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    protected fun iconButton(
        symbol: String,
        description: String? = null,
        accent: String = COLOR_ACCENT,
        size: Int = 34,
        onClick: () -> Unit,
    ): TextView {
        return TextView(requireContext()).apply {
            text = symbol
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accent))
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = interactiveBackground(tint(accent, "14"), accent, 999, strokeColor = tint(accent, "55"))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    protected fun input(value: String, widthDp: Int? = null): EditText {
        return EditText(requireContext()).apply {
            setText(value)
            setTextColor(Color.parseColor(COLOR_TEXT))
            setHintTextColor(Color.parseColor(COLOR_MUTED))
            textSize = 13f
            setSingleLine(true)
            maxLines = 1
            setSelectAllOnFocus(false)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    outlined(COLOR_ACCENT, COLOR_INPUT_FILL, 10),
                )
                addState(intArrayOf(), outlined(COLOR_STROKE, COLOR_INPUT_FILL, 10))
            }
            layoutParams = LinearLayout.LayoutParams(
                widthDp?.let(::dp) ?: 0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (widthDp == null) 1f else 0f,
            )
        }
    }

    protected fun verticalParams(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(top)
        }
    }

    protected fun solid(color: String, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
        }
    }

    protected fun outlined(
        strokeColor: String,
        fillColor: String,
        radius: Int,
        strokeWidth: Int = 1,
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillColor))
            setStroke(dp(strokeWidth), Color.parseColor(strokeColor))
            cornerRadius = dp(radius).toFloat()
        }
    }

    protected fun cardBackground(
        fillColor: String = COLOR_CARD,
        strokeColor: String = COLOR_STROKE,
        radius: Int = 14,
    ): GradientDrawable {
        return outlined(strokeColor, fillColor, radius)
    }

    protected fun interactiveBackground(
        fill: String,
        accent: String,
        radius: Int,
        strokeColor: String = COLOR_STROKE,
    ): Drawable {
        val states = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                outlined(accent, tint(accent, "2E"), radius, strokeWidth = 2),
            )
            addState(intArrayOf(), outlined(strokeColor, fill, radius))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor(tint(accent, "33"))),
            states,
            solid("#FFFFFF", radius),
        )
    }

    protected fun saveToast(message: String = "Impostazioni salvate") {
        showToast(message)
    }

    protected fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            showToast("Impossibile aprire il link.")
        }
    }

    protected fun offerRestartPrompt(
        message: String,
        onLater: (() -> Unit)? = null,
        suppressNextMainClosePrompt: Boolean = true,
    ) {
        if (suppressNextMainClosePrompt) {
            skipNextMainSettingsRestartPrompt = true
        }
        val dialogContext = context ?: return
        val appContext = dialogContext.applicationContext
        AlertDialog.Builder(dialogContext)
            .setTitle("Riavvia l'app")
            .setMessage(message)
            .setPositiveButton("Riavvia") { _, _ ->
                restartApp(appContext)
            }
            .setNegativeButton("Più tardi") { _, _ ->
                onLater?.invoke()
            }
            .show()
    }

    protected fun consumeMainSettingsRestartPromptSkip(): Boolean {
        val value = skipNextMainSettingsRestartPrompt
        skipNextMainSettingsRestartPrompt = false
        return value
    }

    private fun restartApp(appContext: Context) {
        val packageManager = appContext.packageManager
        val intent = packageManager.getLaunchIntentForPackage(appContext.packageName)
        val component = intent?.component ?: return
        val restartIntent = Intent.makeRestartActivityTask(component)
        appContext.startActivity(restartIntent)
        android.os.Process.killProcess(android.os.Process.myPid())
        Runtime.getRuntime().exit(0)
    }
}

class StreamCenterSettings : StreamCenterBaseSettingsFragment() {
    private var sourcesChip: TextView? = null
    private var sectionsChip: TextView? = null
    private var homeStatus: TextView? = null
    private var sourcesStatus: TextView? = null
    private var mainContent: View? = null
    private var openSubmenus = 0
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshStatusStrip()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        mainContent = content
        content.addView(
            header(
                title = "StreamCenter",
                subtitle = StreamCenterPlugin.getBuildInfoText(),
                icon = "🍿",
                actionText = "Chiudi",
                onAction = { dismiss() },
            ),
        )

        content.addView(statusStrip())
        content.addView(
            switchRow(
                title = "Modalità prestazioni",
                summary = "Carica solo Home, Episodi e la prima Fonte disponibile.",
                checked = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref),
                accent = COLOR_SUCCESS,
                icon = "⚡",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_PERFORMANCE_MODE, enabled) }
                (dialog as? BottomSheetDialog)?.dismissWithAnimation = !enabled
                StreamCenter.clearCaches()
                saveToast(if (enabled) "Modalità prestazioni attivata" else "Modalità prestazioni disattivata")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Schede",
                summary = "Gestisci i dettagli mostrati nelle schede di Home e Ricerca.",
                icon = "🖼️",
                accent = COLOR_SUCCESS,
            ) {
                showSubmenu(StreamCenterDisplaySettingsFragment(), "StreamCenterDisplaySettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Home",
                summary = "Personalizza sezioni, ordine, nomi e numero di titoli mostrati.",
                icon = "🏠",
                accent = COLOR_ACCENT,
                status = "",
                onStatusReady = { homeStatus = it },
            ) {
                showSubmenu(StreamCenterHomeSettingsFragment(), "StreamCenterHomeSettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Fonti streaming",
                summary = "Scegli le fonti attive, aggiorna i link e definisci la priorità.",
                icon = "📡",
                accent = COLOR_WARN,
                status = "",
                onStatusReady = { sourcesStatus = it },
            ) {
                showSubmenu(StreamCenterSourcesSettingsFragment(), "StreamCenterSourcesSettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Supporto",
                summary = "Controlla lo stato delle fonti, invia feedback o ripristina le impostazioni.",
                icon = "🛟",
                accent = COLOR_DANGER,
            ) {
                showSubmenu(StreamCenterSupportSettingsFragment(), "StreamCenterSupportSettings")
            },
        )

        return scroll(content)
    }

    override fun onStart() {
        super.onStart()
        sharedPref?.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        refreshStatusStrip()
    }

    override fun onStop() {
        sharedPref?.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onStop()
    }

    override fun onDismiss(dialog: DialogInterface) {
        val shouldSkipRestartPrompt = consumeMainSettingsRestartPromptSkip()
        if (!shouldSkipRestartPrompt) {
            offerRestartPrompt(
                "Per applicare tutte le modifiche è necessario riavviare l'app.\nVuoi riavviarla adesso?",
                suppressNextMainClosePrompt = false,
            )
        }
        super.onDismiss(dialog)
    }

    private fun statusStrip(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(12))
            background = cardBackground(COLOR_CARD_ALT, radius = 14)
            layoutParams = verticalParams(top = 4)

            addView(sectionLabel("Riepilogo"))
            val chips = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 8)
            }
            sourcesChip = chip("", COLOR_WARN)
            chips.addView(sourcesChip)
            sectionsChip = chip("", COLOR_ACCENT).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            }
            chips.addView(sectionsChip)
            addView(chips)
            refreshStatusStrip()
        }
    }

    private fun refreshStatusStrip() {
        val enabledSources = StreamCenterPlugin.streamingSources.count {
            StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, it.key)
        }
        sourcesChip?.text = "📡 Fonti ${enabledSources}/${StreamCenterPlugin.streamingSources.size}"
        val enabledSections = StreamCenterPlugin.getConfiguredHomeSections(sharedPref).size
        sectionsChip?.text = "🧩 $enabledSections sezioni"
        homeStatus?.text = "$enabledSections attive"
        sourcesStatus?.text = "$enabledSources attive"
    }

    private fun settingsMenuCard(
        title: String,
        summary: String,
        icon: String,
        accent: String,
        status: String? = null,
        onStatusReady: ((TextView) -> Unit)? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(84)
            setPadding(dp(14), dp(13), dp(10), dp(13))
            background = interactiveBackground(COLOR_CARD, accent, 16)
            layoutParams = verticalParams(top = 10)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(iconBadge(icon, accent, size = 44, marginEnd = 12))
            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 16, true))
            texts.addView(bodyText(summary, 12).apply { setPadding(0, dp(4), 0, 0) })
            addView(texts)

            if (status != null) {
                val statusView = chip(status, accent)
                addView(statusView)
                onStatusReady?.invoke(statusView)
            }
            addView(chevron(accent))
        }
    }

    private fun showSubmenu(fragment: StreamCenterBaseSettingsFragment, tag: String) {
        openSubmenus += 1
        updateMainBackdrop()
        fragment.onDismissed {
            openSubmenus = (openSubmenus - 1).coerceAtLeast(0)
            updateMainBackdrop()
        }.show(parentFragmentManager, tag)
    }

    private fun updateMainBackdrop() {
        val content = mainContent ?: return
        val hasSubmenu = openSubmenus > 0
        val targetAlpha = if (hasSubmenu) 0.58f else 1f
        content.animate().cancel()
        if (reduceMotion) {
            content.alpha = targetAlpha
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                content.setRenderEffect(null)
            }
            return
        }
        content.animate()
            .alpha(targetAlpha)
            .setDuration(140L)
            .start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            content.setRenderEffect(
                if (hasSubmenu) {
                    RenderEffect.createBlurEffect(dp(5).toFloat(), dp(5).toFloat(), Shader.TileMode.CLAMP)
                } else {
                    null
                },
            )
        }
    }
}

class StreamCenterDisplaySettingsFragment : StreamCenterBaseSettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        content.addView(
            header(
                title = "Schede",
                subtitle = "Gestisci i dettagli mostrati nelle schede di Home e Ricerca.",
                icon = "🖼️",
                accent = COLOR_SUCCESS,
            ),
        )
        content.addView(
            switchRow(
                title = "Valutazione",
                summary = "Mostra il voto nelle schede di Anime, Film e Serie TV.",
                checked = StreamCenterPlugin.shouldShowHomeScore(sharedPref),
                accent = COLOR_SUCCESS,
                icon = "⭐",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, enabled) }
                saveToast()
            },
        )
        content.addView(
            switchRow(
                title = "SUB/DUB",
                summary = "Indica nelle schede Anime se un titolo è sottotitolato, doppiato o disponibile in entrambe le versioni.",
                checked = StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref),
                accent = COLOR_ACCENT,
                icon = "🎙️",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_DUB_STATUS, enabled) }
                saveToast()
            },
        )
        content.addView(
            switchRow(
                title = "Unifica SUB e DUB",
                summary = "Raggruppa le versioni sottotitolata e doppiata nella stessa scheda.",
                checked = StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref),
                accent = COLOR_ACCENT,
                icon = "🔗",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_GROUP_ANIME_DUB_SUB, enabled) }
                saveToast()
            },
        )
        content.addView(
            switchRow(
                title = "Numero episodi",
                summary = "Mostra nelle schede Anime l'ultimo episodio disponibile.",
                checked = StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref),
                accent = COLOR_WARN,
                icon = "🔢",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, enabled) }
                saveToast()
            },
        )
        content.addView(
            switchRow(
                title = "Ricerca divisa per tipo",
                summary = "Separa la ricerca in sezioni dedicate a Film, Serie TV e Anime.",
                checked = StreamCenterPlugin.shouldSplitSearchResultsByType(sharedPref),
                accent = COLOR_DANGER,
                icon = "🔍",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SEARCH_SPLIT_BY_TYPE, enabled) }
                saveToast()
            },
        )
        return scroll(content)
    }
}

private data class SourceRowState(
    val source: StreamCenterStreamingSource,
    var enabled: Boolean,
    var url: String,
)

class StreamCenterSourcesSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val rows = mutableListOf<SourceRowState>()
    private lateinit var rowsContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        loadRows()

        val content = rootContainer()
        content.addView(
            header(
                title = "Fonti streaming",
                subtitle = "Scegli le fonti attive, aggiorna i link e definisci la priorità.",
                icon = "📡",
                accent = COLOR_WARN,
                actionText = "Ripristina",
                onAction = { confirmReset() },
            ),
        )

        content.addView(
            switchRow(
                title = "Aggiornamento automatico dei domini",
                summary = "Quando una fonte cambia dominio, il nuovo link viene rilevato e salvato automaticamente.",
                checked = StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref),
                accent = COLOR_SUCCESS,
                icon = "🔄",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_AUTO_UPDATE_SOURCE_URLS, enabled) }
                saveToast()
            },
        )

        rowsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 10)
        }
        content.addView(rowsContainer)
        renderRows()

        return scroll(content)
    }

    private fun loadRows() {
        rows.clear()
        val byKey = StreamCenterPlugin.streamingSources.associateBy { it.key }
        StreamCenterPlugin.getSourcePriorityOrder(sharedPref)
            .mapNotNull { byKey[it] }
            .forEach { source ->
                rows += SourceRowState(
                    source = source,
                    enabled = StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, source.key),
                    url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, source.key),
                )
            }
    }

    private fun renderRows() {
        rowsContainer.removeAllViews()
        rows.forEachIndexed { index, row ->
            rowsContainer.addView(sourceRow(index, row))
        }
    }

    private fun priorityBadge(index: Int, enabled: Boolean): TextView {
        val color = if (enabled) COLOR_WARN else COLOR_MUTED
        return TextView(requireContext()).apply {
            text = "${index + 1}"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = outlined(tint(color, "55"), tint(color, "1C"), 999)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(10)
            }
        }
    }

    private fun sourceRow(index: Int, row: SourceRowState): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(12))
            background = cardBackground(if (row.enabled) COLOR_CARD else COLOR_CARD_DISABLED)
            layoutParams = verticalParams(top = 8)

            val topLine = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(priorityBadge(index, row.enabled))
            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(row.source.title, 15, true))
            texts.addView(bodyText(row.source.summary, 12).apply { setPadding(0, dp(3), 0, 0) })
            topLine.addView(texts)
            topLine.addView(styledSwitch(row.enabled, COLOR_SUCCESS) { checked ->
                row.enabled = checked
                animateCardFill(
                    rowView,
                    fromColor = if (checked) COLOR_CARD_DISABLED else COLOR_CARD,
                    toColor = if (checked) COLOR_CARD else COLOR_CARD_DISABLED,
                )
                sharedPref?.edit { putBoolean(row.source.key, checked) }
            })
            addView(topLine)

            val linkRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 10)
            }
            linkRow.addView(bodyText("Link", 12).apply {
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(10)
                }
            })
            linkRow.addView(input(row.url).apply {
                filters = arrayOf(InputFilter.LengthFilter(120))
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val value = text?.toString()?.trim().orEmpty()
                        val previousUrl = row.url
                        StreamCenterPlugin.setSourceBaseUrl(sharedPref, row.source.key, value)
                        row.url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, row.source.key)
                        if (row.url != previousUrl) {
                            StreamCenter.clearCaches()
                        }
                    }
                }
            })
            addView(linkRow)

            val controls = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 10)
            }
            controls.addView(bodyText("Priorità", 12).apply {
                typeface = Typeface.DEFAULT_BOLD
            })
            controls.addView(iconButton("↑", "Alza la priorità di ${row.source.title}") { moveRow(index, -1) })
            controls.addView(iconButton("↓", "Abbassa la priorità di ${row.source.title}") { moveRow(index, 1) })
            addView(controls)
        }
    }

    private fun moveRow(index: Int, direction: Int) {
        val target = index + direction
        if (target !in rows.indices) return
        rowsContainer.clearFocus()
        rows[index] = rows[target].also { rows[target] = rows[index] }
        StreamCenterPlugin.setSourcePriorityOrder(sharedPref, rows.map { it.source.key })
        renderRows()
    }

    private fun confirmReset() {
        AlertDialog.Builder(requireContext())
            .setTitle("Ripristina fonti")
            .setMessage("Vuoi ripristinare i link originali e l'ordine predefinito delle fonti?")
            .setPositiveButton("Ripristina") { _, _ ->
                rowsContainer.clearFocus()
                StreamCenterPlugin.resetSourceUrls(sharedPref)
                sharedPref?.edit { remove(StreamCenterPlugin.PREF_SOURCE_PRIORITY) }
                StreamCenter.clearCaches()
                loadRows()
                renderRows()
                saveToast("Fonti ripristinate")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}

class StreamCenterHomeSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val rows = mutableListOf<HomeRowState>()
    private val categoryOrder = mutableListOf<String>()
    private val categoryEnabled = mutableMapOf<String, Boolean>()
    private lateinit var rowsContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        loadRows()

        val content = rootContainer()
        content.addView(
            header(
                title = "Home",
                subtitle = "Personalizza sezioni, ordine, nomi e numero di titoli mostrati.",
                icon = "🏠",
                actionText = "Salva",
            ) {
                saveRows()
            },
        )

        rowsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 4)
        }
        content.addView(rowsContainer)
        renderRows()

        content.addView(actionButton("Ripristina Home predefinita", COLOR_WARN) {
            resetHome()
        }.apply {
            layoutParams = verticalParams(top = 14)
        })

        return scroll(content)
    }

    private fun loadRows() {
        rows.clear()
        categoryOrder.clear()
        categoryOrder += StreamCenterPlugin.getHomeCategoryOrder(sharedPref)
        categoryEnabled.clear()
        StreamCenterPlugin.homeCategories.forEach { categoryKey ->
            categoryEnabled[categoryKey] = StreamCenterPlugin.isHomeCategoryEnabled(sharedPref, categoryKey)
        }
        val byKey = StreamCenterPlugin.homeSections.associateBy { it.key }
        val orderedKeys = sharedPref
            ?.getString(StreamCenterPlugin.PREF_HOME_ORDER, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in byKey }
            ?.distinct()
            .orEmpty()
        val order = orderedKeys + StreamCenterPlugin.homeSections.map { it.key }.filterNot { it in orderedKeys }
        order.mapNotNull { byKey[it] }
            .sortedBy { categoryOrder.indexOf(StreamCenterPlugin.homeSectionCategoryKey(it)) }
            .forEach { section ->
            rows += HomeRowState(
                section = section,
                title = StreamCenterPlugin.getHomeSectionTitle(sharedPref, section),
                enabled = StreamCenterPlugin.isHomeSectionEnabled(sharedPref, section),
                count = normalizedCount(
                    StreamCenterPlugin.getHomeSectionCount(sharedPref, section).toString(),
                    section,
                ),
            )
        }
    }

    private fun renderRows() {
        rowsContainer.removeAllViews()
        var previousCategoryKey: String? = null
        rows.forEachIndexed { index, row ->
            val categoryKey = StreamCenterPlugin.homeSectionCategoryKey(row.section)
            if (categoryKey != previousCategoryKey) {
                rowsContainer.addView(categoryHeader(categoryKey, previousCategoryKey == null))
                previousCategoryKey = categoryKey
            }
            rowsContainer.addView(homeRow(index, row))
        }
    }

    private fun categoryHeader(categoryKey: String, isFirst: Boolean): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val accent = categoryAccent(categoryKey)
            val enabled = categoryEnabled[categoryKey] ?: true
            tag = "home-category:$categoryKey"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            alpha = if (enabled) 1f else 0.55f
            background = cardBackground(
                if (enabled) COLOR_CARD_ALT else "#171B22",
                if (enabled) tint(accent, "77") else COLOR_STROKE,
            )
            layoutParams = verticalParams(top = if (isFirst) 0 else 18)

            addView(iconBadge(categoryEmoji(categoryKey), accent, size = 38, marginEnd = 10))
            val labels = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            labels.addView(titleText(categoryTitle(categoryKey), 16, true))
            labels.addView(bodyText("${rows.count { StreamCenterPlugin.homeSectionCategoryKey(it.section) == categoryKey }} sezioni", 11).apply {
                setPadding(0, dp(2), 0, 0)
            })
            addView(labels)
            addView(styledSwitch(categoryEnabled[categoryKey] ?: true, accent) { checked ->
                categoryEnabled[categoryKey] = checked
                updateCategoryAppearance(categoryKey, checked)
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            })
            addView(iconButton("↑", "Sposta ${categoryTitle(categoryKey)} in alto", accent) {
                moveCategory(categoryKey, -1)
            })
            addView(iconButton("↓", "Sposta ${categoryTitle(categoryKey)} in basso", accent) {
                moveCategory(categoryKey, 1)
            })
        }
    }

    private fun categoryTitle(categoryKey: String): String {
        return when (categoryKey) {
            "anime" -> "Anime"
            "tv" -> "Serie TV"
            "movie" -> "Film"
            else -> "Altro"
        }
    }

    private fun categoryEmoji(categoryKey: String): String {
        return when (categoryKey) {
            "anime" -> "🎌"
            "tv" -> "📺"
            "movie" -> "🎬"
            else -> "📁"
        }
    }

    private fun categoryAccent(categoryKey: String): String {
        return when (categoryKey) {
            "anime" -> COLOR_ACCENT
            "tv" -> COLOR_SUCCESS
            "movie" -> COLOR_WARN
            else -> COLOR_MUTED
        }
    }

    private fun moveCategory(categoryKey: String, direction: Int) {
        val index = categoryOrder.indexOf(categoryKey)
        val target = index + direction
        if (index < 0 || target !in categoryOrder.indices) return
        categoryOrder[index] = categoryOrder[target].also { categoryOrder[target] = categoryOrder[index] }
        rows.sortBy { categoryOrder.indexOf(StreamCenterPlugin.homeSectionCategoryKey(it.section)) }
        renderRows()
    }

    private fun updateCategoryAppearance(categoryKey: String, enabled: Boolean) {
        for (index in 0 until rowsContainer.childCount) {
            val view = rowsContainer.getChildAt(index)
            when (view.tag) {
                "home-category:$categoryKey" -> {
                    view.animate().cancel()
                    if (reduceMotion) {
                        view.alpha = if (enabled) 1f else 0.55f
                    } else {
                        view.animate().alpha(if (enabled) 1f else 0.55f).setDuration(220L).start()
                    }
                    animateCardFill(
                        view,
                        fromColor = if (enabled) "#171B22" else COLOR_CARD_ALT,
                        toColor = if (enabled) COLOR_CARD_ALT else "#171B22",
                        strokeColor = if (enabled) tint(categoryAccent(categoryKey), "77") else COLOR_STROKE,
                    )
                }
                else -> {
                    val sectionKey = (view.tag as? String)
                        ?.removePrefix("home-section:")
                        ?.takeIf { view.tag == "home-section:$it" }
                        ?: continue
                    val row = rows.firstOrNull { it.section.key == sectionKey } ?: continue
                    if (StreamCenterPlugin.homeSectionCategoryKey(row.section) != categoryKey) continue
                    view.animate().cancel()
                    if (reduceMotion) {
                        view.alpha = if (enabled) 1f else 0.5f
                    } else {
                        view.animate().alpha(if (enabled) 1f else 0.5f).setDuration(220L).start()
                    }
                    animateCardFill(
                        view,
                        fromColor = if (!enabled && row.enabled) COLOR_CARD else COLOR_CARD_DISABLED,
                        toColor = if (enabled && row.enabled) COLOR_CARD else COLOR_CARD_DISABLED,
                    )
                }
            }
        }
    }

    private fun homeRow(index: Int, row: HomeRowState): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            val categoryKey = StreamCenterPlugin.homeSectionCategoryKey(row.section)
            val accent = categoryAccent(categoryKey)
            val categoryIsEnabled = categoryEnabled[categoryKey] ?: true
            tag = "home-section:${row.section.key}"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(12))
            alpha = if (categoryIsEnabled) 1f else 0.5f
            background = cardBackground(
                if (row.enabled && categoryIsEnabled) COLOR_CARD else COLOR_CARD_DISABLED,
            )
            layoutParams = verticalParams(top = 8)

            val topLine = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val sectionLabelView = titleText(StreamCenterPlugin.getDefaultHomeSectionTitle(row.section.key).substringBefore(" ("), 12, true).apply {
                setTextColor(Color.parseColor(if (row.enabled) accent else COLOR_MUTED))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            topLine.addView(sectionLabelView)
            topLine.addView(styledSwitch(row.enabled, accent) { checked ->
                row.enabled = checked
                val categoryIsOn = categoryEnabled[categoryKey] ?: true
                sectionLabelView.setTextColor(Color.parseColor(if (checked) accent else COLOR_MUTED))
                animateCardFill(
                    rowView,
                    fromColor = if (!checked && categoryIsOn) COLOR_CARD else COLOR_CARD_DISABLED,
                    toColor = if (checked && categoryIsOn) COLOR_CARD else COLOR_CARD_DISABLED,
                )
            })
            addView(topLine)

            val titleInput: EditText = input(row.title).apply {
                filters = arrayOf(InputFilter.LengthFilter(58))
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) row.title = text?.toString()?.trim().orEmpty()
                }
            }
            addView(titleInput.apply {
                layoutParams = verticalParams(top = 10)
            })

            val controls = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 10)
            }
            controls.addView(bodyText("Titoli", 12).apply {
                typeface = Typeface.DEFAULT_BOLD
            })
            val countInput: EditText = input(row.count.toString(), widthDp = 46).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                filters = arrayOf(
                    InputFilter.LengthFilter(2),
                    maxCountFilter(maxCountFor(row.section)),
                )
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) row.count = normalizedCount(text?.toString(), row.section)
                }
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            }
            fun stepCount(delta: Int) {
                val current = countInput.text?.toString()?.trim()?.toIntOrNull() ?: row.count
                val next = (current + delta)
                    .coerceIn(StreamCenterPlugin.MIN_HOME_COUNT, maxCountFor(row.section))
                row.count = next
                countInput.setText(next.toString())
            }
            controls.addView(iconButton("−", "Riduci il numero di titoli", accent) { stepCount(-1) })
            controls.addView(countInput)
            controls.addView(iconButton("+", "Aumenta il numero di titoli", accent) { stepCount(1) })
            controls.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            controls.addView(iconButton("↑", "Sposta la sezione in alto", accent) {
                row.title = titleInput.text?.toString()?.trim().orEmpty()
                row.count = normalizedCount(countInput.text?.toString(), row.section)
                moveRow(index, -1)
            })
            controls.addView(iconButton("↓", "Sposta la sezione in basso", accent) {
                row.title = titleInput.text?.toString()?.trim().orEmpty()
                row.count = normalizedCount(countInput.text?.toString(), row.section)
                moveRow(index, 1)
            })
            addView(controls)
        }
    }

    private fun moveRow(index: Int, direction: Int) {
        val target = index + direction
        if (target !in rows.indices) return
        if (StreamCenterPlugin.homeSectionCategory(rows[index].section) !=
            StreamCenterPlugin.homeSectionCategory(rows[target].section)
        ) return
        rows[index] = rows[target].also { rows[target] = rows[index] }
        renderRows()
    }

    private fun maxCountFor(section: StreamCenterHomeSectionDefinition): Int {
        return when (section.key) {
            "tv_top10", "movie_top10" -> 10
            else -> StreamCenterPlugin.MAX_HOME_COUNT
        }
    }

    private fun maxCountFilter(maxCount: Int): InputFilter {
        return InputFilter { source, start, end, destination, dstart, dend ->
            val candidate = StringBuilder(destination)
                .replace(dstart, dend, source.subSequence(start, end).toString())
                .toString()
            if (candidate.isBlank() || candidate.toIntOrNull()?.let { it <= maxCount } == true) {
                null
            } else {
                ""
            }
        }
    }

    private fun normalizedCount(raw: String?, section: StreamCenterHomeSectionDefinition): Int {
        return raw
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(StreamCenterPlugin.MIN_HOME_COUNT, maxCountFor(section))
            ?: minOf(StreamCenterPlugin.DEFAULT_HOME_COUNT, maxCountFor(section))
    }

    private fun saveRows() {
        rowsContainer.clearFocus()
        sharedPref?.edit {
            putString(StreamCenterPlugin.PREF_HOME_ORDER, rows.joinToString(",") { it.section.key })
            putString(StreamCenterPlugin.PREF_HOME_CATEGORY_ORDER, categoryOrder.joinToString(","))
            categoryEnabled.forEach { (categoryKey, enabled) ->
                putBoolean(StreamCenterPlugin.homeCategoryEnabledKey(categoryKey), enabled)
            }
            rows.forEach { row ->
                row.count = normalizedCount(row.count.toString(), row.section)
                putBoolean(StreamCenterPlugin.sectionEnabledKey(row.section.key), row.enabled)
                putString(
                    StreamCenterPlugin.sectionTitleKey(row.section.key),
                    row.title.takeIf { it.isNotBlank() }
                        ?: StreamCenterPlugin.getDefaultHomeSectionTitle(row.section.key),
                )
                putInt(StreamCenterPlugin.sectionCountKey(row.section.key), row.count)
            }
        }
        saveToast("Home salvata")
    }

    private fun resetHome() {
        sharedPref?.edit {
            remove(StreamCenterPlugin.PREF_HOME_ORDER)
            remove(StreamCenterPlugin.PREF_HOME_CATEGORY_ORDER)
            StreamCenterPlugin.homeCategories.forEach { categoryKey ->
                remove(StreamCenterPlugin.homeCategoryEnabledKey(categoryKey))
            }
            StreamCenterPlugin.homeSections.forEach { section ->
                remove(StreamCenterPlugin.sectionEnabledKey(section.key))
                remove(StreamCenterPlugin.sectionTitleKey(section.key))
                remove(StreamCenterPlugin.sectionCountKey(section.key))
            }
        }
        loadRows()
        renderRows()
        offerRestartPrompt("La Home è stata ripristinata.\nVuoi riavviare l'app adesso?")
    }
}

class StreamCenterSupportSettingsFragment : StreamCenterBaseSettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        content.addView(
            header(
                title = "Supporto",
                subtitle = "Verifica lo stato, invia feedback o ripristina le impostazioni.",
                icon = "🛟",
                accent = COLOR_DANGER,
            ),
        )

        content.addView(supportCard(
            icon = "🐞",
            title = "Segnala un problema",
            summary = "Apri una segnalazione su GitHub.",
            accent = COLOR_DANGER,
        ) {
            openFeedback("[problema]: ")
        })
        content.addView(supportCard(
            icon = "💡",
            title = "Proponi un miglioramento",
            summary = "Suggerisci una nuova funzione o una modifica.",
            accent = COLOR_SUCCESS,
        ) {
            openFeedback("[suggerimento]: ")
        })
        content.addView(supportCard(
            icon = "📡",
            title = "Verifica API e fonti",
            summary = "Controlla la raggiungibilità dei servizi e delle fonti.",
            accent = COLOR_ACCENT,
        ) {
            checkApis()
        })
        content.addView(supportCard(
            icon = "♻️",
            title = "Ripristina tutte le impostazioni",
            summary = "Cancella preferenze, sessioni e dati salvati.",
            accent = COLOR_WARN,
        ) {
            AlertDialog.Builder(requireContext())
                .setTitle("Ripristina impostazioni")
                .setMessage(
                    "Vuoi riportare StreamCenter alle impostazioni iniziali? " +
                        "Verranno cancellati preferenze, sessioni e dati temporanei salvati.",
                )
                .setPositiveButton("Ripristina") { _, _ ->
                    sharedPref?.edit { clear() }
                    StreamCenter.clearCaches()
                    offerRestartPrompt("Impostazioni ripristinate e dati salvati puliti.\nVuoi riavviare l'app adesso?")
                }
                .setNegativeButton("Annulla", null)
                .show()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
        })

        return scroll(content)
    }

    private fun supportCard(
        icon: String,
        title: String,
        summary: String,
        accent: String,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = interactiveBackground(COLOR_CARD, accent, 16)
            layoutParams = verticalParams(top = 10)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(iconBadge(icon, accent, size = 42, marginEnd = 12))
            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 15, true))
            texts.addView(bodyText(summary, 12).apply { setPadding(0, dp(3), 0, 0) })
            addView(texts)
            addView(chevron(accent))
        }
    }

    private fun checkApis() {
        saveToast("Verifica di API e fonti in corso…")
        val prefs = sharedPref
        CoroutineScope(Dispatchers.IO).launch {
            val results = runCatching { StreamCenter.checkApisAvailability(prefs) }
                .getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                showGroupedApiResults(results)
            }
        }
    }

    private fun showGroupedApiResults(results: List<Pair<String, Boolean>>) {
        val ctx = context ?: return
        if (results.isEmpty()) {
            AlertDialog.Builder(ctx)
                .setTitle("Stato di API e fonti")
                .setMessage("Impossibile completare la verifica.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val apiNames = setOf("AniList", "MyAnimeList (Jikan)", "Kitsu", "TMDB", "Mappe anime (Fribb)")
        val apiResults = results.filter { (name, _) -> name in apiNames }
        val sourceResults = results.filterNot { (name, _) -> name in apiNames }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        content.addView(statusColumn("API", apiResults).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        })
        content.addView(statusColumn("Fonti streaming", sourceResults).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        })

        AlertDialog.Builder(ctx)
            .setTitle("Stato di API e fonti")
            .setView(content)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun statusColumn(title: String, items: List<Pair<String, Boolean>>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText(title, 15, true).apply {
                setTextColor(Color.parseColor(COLOR_ACCENT))
            })
            if (items.isEmpty()) {
                addView(bodyText("Nessun elemento", 12).apply {
                    setPadding(0, dp(8), 0, 0)
                })
            } else {
                items.forEach { (name, ok) ->
                    addView(statusResultRow(name, ok))
                }
            }
        }
    }

    private fun statusResultRow(name: String, ok: Boolean): TextView {
        return bodyText("${if (ok) "✔" else "✖"}  $name", 13).apply {
            setTextColor(Color.parseColor(if (ok) COLOR_SUCCESS else COLOR_DANGER))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun openFeedback(prefix: String) {
        val title = Uri.encode("StreamCenter $prefix")
        openUrl("${StreamCenterPlugin.FEEDBACK_ISSUES_URL}?title=$title")
    }
}
