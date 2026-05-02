package it.dogior.hadEnough

import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import java.util.Locale

private const val COLOR_BACKGROUND = "#11141A"
private const val COLOR_CARD = "#1A2029"
private const val COLOR_CARD_ALT = "#202733"
private const val COLOR_TEXT = "#F4F7FB"
private const val COLOR_MUTED = "#AAB3C2"
private const val COLOR_STROKE = "#2E3746"
private const val COLOR_ACCENT = "#4CC9F0"
private const val COLOR_SUCCESS = "#7CFF9D"
private const val COLOR_WARN = "#FFB703"
private const val COLOR_DANGER = "#FF7F7F"

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

    protected val sharedPref: SharedPreferences?
        get() = StreamCenterPlugin.activeSharedPref

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }
    }

    protected fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    protected fun rootContainer(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
            background = solid(COLOR_BACKGROUND, 0)
        }
    }

    protected fun scroll(content: LinearLayout): ScrollView {
        return ScrollView(requireContext()).apply {
            isFillViewport = false
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
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(8))

            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 24, true))
            subtitle?.let {
                texts.addView(bodyText(it, 12).apply {
                    alpha = 0.78f
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(texts)

            if (actionText != null && onAction != null) {
                addView(actionButton(actionText, COLOR_ACCENT, onAction).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginStart = dp(10)
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

    protected fun chip(value: String, color: String = COLOR_ACCENT): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = outlined(color, "#1A${color.removePrefix("#")}", 999)
        }
    }

    protected fun card(
        title: String,
        summary: String,
        accent: String = COLOR_ACCENT,
        trailing: String = "Apri",
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(76)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = cardBackground()
            layoutParams = verticalParams(top = 10)
            setOnClickListener { onClick() }

            val accentBar = View(requireContext()).apply {
                background = solid(accent, 999)
                layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(12)
                }
            }
            addView(accentBar)

            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 17, true))
            texts.addView(bodyText(summary, 13).apply { setPadding(0, dp(4), 0, 0) })
            addView(texts)

            addView(TextView(requireContext()).apply {
                text = trailing
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(accent))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), 0, dp(6))
            })
        }
    }

    protected fun switchRow(
        title: String,
        summary: String,
        checked: Boolean,
        accent: String = COLOR_ACCENT,
        stateTitle: ((Boolean) -> String)? = null,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cardBackground(COLOR_CARD_ALT, radius = 10)
            layoutParams = verticalParams(top = 8)

            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleView = titleText(stateTitle?.invoke(checked) ?: title, 15, true)
            texts.addView(titleView)
            texts.addView(bodyText(summary, 12).apply { setPadding(0, dp(3), 0, 0) })
            addView(texts)

            val stripe = View(requireContext()).apply {
                background = solid(if (checked) accent else COLOR_STROKE, 999)
                layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(10)
                }
            }

            addView(Switch(requireContext()).apply {
                isChecked = checked
                showText = false
                setOnCheckedChangeListener { _, isChecked ->
                    titleView.text = stateTitle?.invoke(isChecked) ?: title
                    stripe.background = solid(if (isChecked) accent else COLOR_STROKE, 999)
                    onChanged(isChecked)
                }
                layoutParams = LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10)
                }
            })
            addView(stripe)
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
            background = outlined(color, "#18${color.removePrefix("#")}", 10)
            isClickable = true
            isFocusable = true
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
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = outlined(COLOR_STROKE, "#10151D", 8)
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

    protected fun outlined(strokeColor: String, fillColor: String, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillColor))
            setStroke(dp(1), Color.parseColor(strokeColor))
            cornerRadius = dp(radius).toFloat()
        }
    }

    protected fun cardBackground(
        fillColor: String = COLOR_CARD,
        strokeColor: String = COLOR_STROKE,
        radius: Int = 12,
    ): GradientDrawable {
        return outlined(strokeColor, fillColor, radius)
    }

    protected fun saveToast(message: String = "Impostazioni salvate") {
        showToast(message)
    }

    protected fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            showToast("Impossibile aprire il collegamento.")
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
        AlertDialog.Builder(dialogContext)
            .setTitle("Riavvia Applicazione")
            .setMessage(message)
            .setPositiveButton("Riavvia") { _, _ ->
                restartApp()
            }
            .setNegativeButton("Piu Tardi") { _, _ ->
                onLater?.invoke()
            }
            .show()
    }

    protected fun consumeMainSettingsRestartPromptSkip(): Boolean {
        val value = skipNextMainSettingsRestartPrompt
        skipNextMainSettingsRestartPrompt = false
        return value
    }

    private fun restartApp() {
        val appContext = context?.applicationContext ?: return
        val packageManager = appContext.packageManager
        val intent = packageManager.getLaunchIntentForPackage(appContext.packageName)
        val component = intent?.component ?: return
        val restartIntent = Intent.makeRestartActivityTask(component)
        appContext.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }
}

class StreamCenterSettings : StreamCenterBaseSettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        content.addView(
            header(
                title = "StreamCenter",
                subtitle = StreamCenterPlugin.getBuildInfoText(),
                actionText = "Salva",
                onAction = { dismiss() },
            ),
        )

        content.addView(statusStrip())
        content.addView(
            card(
                title = "Schede",
                summary = "Dettagli visibili nella Home.",
                accent = COLOR_SUCCESS,
            ) {
                StreamCenterDisplaySettingsFragment().show(parentFragmentManager, "StreamCenterDisplaySettings")
            },
        )
        content.addView(
            card(
                title = "Home",
                summary = "Sezioni, Ordine, Nomi e Limite Elementi.",
                accent = COLOR_ACCENT,
            ) {
                StreamCenterHomeSettingsFragment().show(parentFragmentManager, "StreamCenterHomeSettings")
            },
        )
        content.addView(
            card(
                title = "Fonti Streaming",
                summary = "Scegli quali Sorgenti usare per Film, Serie e Anime.",
                accent = COLOR_WARN,
            ) {
                StreamCenterSourcesSettingsFragment().show(parentFragmentManager, "StreamCenterSourcesSettings")
            },
        )
        content.addView(
            card(
                title = "Cache e Sessioni",
                summary = "Controlla Cache locale, Cookie e Sessioni salvate.",
                accent = "#C084FC",
            ) {
                StreamCenterCacheSettingsFragment().show(parentFragmentManager, "StreamCenterCacheSettings")
            },
        )
        content.addView(
            card(
                title = "Supporto",
                summary = "Segnala problemi, proponi miglioramenti o ripristina tutto.",
                accent = COLOR_DANGER,
            ) {
                StreamCenterSupportSettingsFragment().show(parentFragmentManager, "StreamCenterSupportSettings")
            },
        )

        return scroll(content)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (consumeMainSettingsRestartPromptSkip()) return
        offerRestartPrompt(
            "Hai chiuso le Impostazioni di StreamCenter.\nVuoi riavviare l'app ora?",
            suppressNextMainClosePrompt = false,
        )
    }

    private fun statusStrip(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(2))
            layoutParams = verticalParams(top = 2)

            val enabledSources = StreamCenterPlugin.streamingSources.count {
                StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, it.key)
            }
            addView(chip("Fonti $enabledSources/${StreamCenterPlugin.streamingSources.size}", COLOR_WARN))
            addView(chip(if (StreamCenterPlugin.shouldShowHomeScore(sharedPref)) "Valutazione Home: On" else "Valutazione Home: Off", COLOR_SUCCESS).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            })
            addView(chip("${StreamCenterPlugin.getConfiguredHomeSections(sharedPref).size} Sezioni", COLOR_ACCENT).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            })
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
        content.addView(header("Schede", "Preferenze di Visualizzazione della Home."))
        content.addView(
            switchRow(
                title = "Mostra Valutazione",
                summary = "Aggiunge il voto alle schede nella Home.\nDisattivarlo rende la Home piu rapida.",
                checked = StreamCenterPlugin.shouldShowHomeScore(sharedPref),
                accent = COLOR_SUCCESS,
                stateTitle = { isChecked ->
                    if (isChecked) "Nascondi Valutazione" else "Mostra Valutazione"
                },
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, enabled) }
                saveToast()
            },
        )
        return scroll(content)
    }
}

class StreamCenterSourcesSettingsFragment : StreamCenterBaseSettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        content.addView(header("Fonti Streaming", "Disattiva le Fonti lente o che non vuoi usare."))

        StreamCenterPlugin.streamingSources.forEachIndexed { index, source ->
            content.addView(
                switchRow(
                    title = source.title,
                    summary = source.summary,
                    checked = StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, source.key),
                    accent = if (index == 0) COLOR_ACCENT else COLOR_WARN,
                ) { enabled ->
                    sharedPref?.edit { putBoolean(source.key, enabled) }
                    saveToast()
                },
            )
        }

        return scroll(content)
    }
}

class StreamCenterHomeSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val rows = mutableListOf<HomeRowState>()
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
                subtitle = "Personalizza Ordine, Nomi, Visibilita e Quantita.",
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

        content.addView(actionButton("Ripristina Home Predefinita", COLOR_WARN) {
            resetHome()
        }.apply {
            layoutParams = verticalParams(top = 12)
        })

        return scroll(content)
    }

    private fun loadRows() {
        rows.clear()
        val byKey = StreamCenterPlugin.homeSections.associateBy { it.key }
        val orderedKeys = sharedPref
            ?.getString(StreamCenterPlugin.PREF_HOME_ORDER, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in byKey }
            ?.distinct()
            .orEmpty()
        val order = orderedKeys + StreamCenterPlugin.homeSections.map { it.key }.filterNot { it in orderedKeys }
        order.mapNotNull { byKey[it] }.forEach { section ->
            rows += HomeRowState(
                section = section,
                title = StreamCenterPlugin.getHomeSectionTitle(sharedPref, section),
                enabled = StreamCenterPlugin.isHomeSectionEnabled(sharedPref, section),
                count = StreamCenterPlugin.getHomeSectionCount(sharedPref, section),
            )
        }
    }

    private fun renderRows() {
        rowsContainer.removeAllViews()
        rows.forEachIndexed { index, row ->
            rowsContainer.addView(homeRow(index, row))
        }
    }

    private fun homeRow(index: Int, row: HomeRowState): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cardBackground(if (row.enabled) COLOR_CARD else "#151922", radius = 12)
            layoutParams = verticalParams(top = 8)

            val topLine = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(titleText(StreamCenterPlugin.getDefaultHomeSectionTitle(row.section.key).substringBefore(" ("), 11, true).apply {
                setTextColor(Color.parseColor(if (row.enabled) COLOR_ACCENT else COLOR_MUTED))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            topLine.addView(Switch(requireContext()).apply {
                isChecked = row.enabled
                showText = false
                setOnCheckedChangeListener { _, checked ->
                    row.enabled = checked
                    rowView.background = cardBackground(if (checked) COLOR_CARD else "#151922", radius = 12)
                }
            })
            addView(topLine)

            val titleInput = input(row.title).apply {
                filters = arrayOf(InputFilter.LengthFilter(58))
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) row.title = text?.toString()?.trim().orEmpty()
                }
            }
            addView(titleInput.apply {
                layoutParams = verticalParams(top = 8)
            })

            val controls = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 8)
            }
            controls.addView(bodyText("Elementi", 12).apply {
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            controls.addView(input(row.count.toString(), widthDp = 58).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                filters = arrayOf(InputFilter.LengthFilter(2))
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) row.count = normalizedCount(text?.toString())
                }
            })
            controls.addView(moveButton("Su") {
                row.title = titleInput.text?.toString()?.trim().orEmpty()
                row.count = normalizedCount((controls.getChildAt(1) as? EditText)?.text?.toString())
                moveRow(index, -1)
            })
            controls.addView(moveButton("Giu") {
                row.title = titleInput.text?.toString()?.trim().orEmpty()
                row.count = normalizedCount((controls.getChildAt(1) as? EditText)?.text?.toString())
                moveRow(index, 1)
            })
            addView(controls)
        }
    }

    private fun moveButton(textValue: String, onClick: () -> Unit): TextView {
        return actionButton(textValue, COLOR_ACCENT, onClick).apply {
            minimumHeight = dp(38)
            layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
    }

    private fun moveRow(index: Int, direction: Int) {
        val target = index + direction
        if (target !in rows.indices) return
        rows[index] = rows[target].also { rows[target] = rows[index] }
        renderRows()
    }

    private fun normalizedCount(raw: String?): Int {
        return raw
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(StreamCenterPlugin.MIN_HOME_COUNT, StreamCenterPlugin.MAX_HOME_COUNT)
            ?: StreamCenterPlugin.DEFAULT_HOME_COUNT
    }

    private fun saveRows() {
        rowsContainer.clearFocus()
        sharedPref?.edit {
            putString(StreamCenterPlugin.PREF_HOME_ORDER, rows.joinToString(",") { it.section.key })
            rows.forEach { row ->
                putBoolean(StreamCenterPlugin.sectionEnabledKey(row.section.key), row.enabled)
                putString(
                    StreamCenterPlugin.sectionTitleKey(row.section.key),
                    row.title.takeIf { it.isNotBlank() }
                        ?: StreamCenterPlugin.getDefaultHomeSectionTitle(row.section.key),
                )
                putInt(StreamCenterPlugin.sectionCountKey(row.section.key), row.count)
            }
        }
        saveToast("Home Salvata")
    }

    private fun resetHome() {
        sharedPref?.edit {
            remove(StreamCenterPlugin.PREF_HOME_ORDER)
            StreamCenterPlugin.homeSections.forEach { section ->
                remove(StreamCenterPlugin.sectionEnabledKey(section.key))
                remove(StreamCenterPlugin.sectionTitleKey(section.key))
                remove(StreamCenterPlugin.sectionCountKey(section.key))
            }
        }
        loadRows()
        renderRows()
        offerRestartPrompt("Le Impostazioni della Home sono state ripristinate.\nVuoi riavviare l'app ora?")
    }
}

class StreamCenterCacheSettingsFragment : StreamCenterBaseSettingsFragment() {
    private lateinit var statsSummary: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        content.addView(header("Cache e Sessioni", "Gestione locale di Cache, Cookie e dati temporanei."))

        val statsCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground()
            layoutParams = verticalParams(top = 8)
        }
        statsCard.addView(titleText("Stato Cache", 17, true))
        statsSummary = bodyText("", 13).apply { setPadding(0, dp(6), 0, 0) }
        statsCard.addView(statsSummary)
        content.addView(statsCard)
        updateStats()

        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
        }
        actions.addView(actionButton("Svuota Cache", COLOR_WARN) {
            confirm(
                title = "Svuota Cache",
                message = "Vuoi cancellare cache TMDB, AniList, MAL e fonti streaming?",
            ) {
                StreamCenter.clearAllCaches()
                updateStats()
                saveToast("Cache Svuotata")
            }
        })
        actions.addView(actionButton("Cancella Cookie e Sessioni", COLOR_DANGER) {
            StreamCenter.clearSessionCaches()
            updateStats()
            saveToast("Sessioni Cancellate")
        }.apply {
            layoutParams = verticalParams(top = 8)
        })
        content.addView(actions)

        return scroll(content)
    }

    private fun updateStats() {
        val stats = StreamCenter.getCacheStats()
        statsSummary.text = "Memoria: ${stats.memoryEntries} elementi\nDisco: ${stats.diskEntries} elementi, ${stats.diskSizeLabel}"
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Conferma") { _, _ -> action() }
            .setNegativeButton("Annulla", null)
            .show()
    }
}

class StreamCenterSupportSettingsFragment : StreamCenterBaseSettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        content.addView(header("Supporto", StreamCenterPlugin.getBuildInfoText()))

        content.addView(actionButton("Segnala un Problema", COLOR_DANGER) {
            openFeedback("[problema]: ")
        }.apply {
            layoutParams = verticalParams(top = 8)
        })
        content.addView(actionButton("Suggerisci un Miglioramento", COLOR_SUCCESS) {
            openFeedback("[suggerimento]: ")
        }.apply {
            layoutParams = verticalParams(top = 8)
        })
        content.addView(actionButton("Ripristina Tutte le Impostazioni", COLOR_WARN) {
            AlertDialog.Builder(requireContext())
                .setTitle("Ripristina Impostazioni")
                .setMessage("Vuoi riportare StreamCenter alle impostazioni iniziali?")
                .setPositiveButton("Ripristina") { _, _ ->
                    sharedPref?.edit { clear() }
                    offerRestartPrompt("Le Impostazioni sono state ripristinate.\nVuoi riavviare l'app ora?")
                }
                .setNegativeButton("Annulla", null)
                .show()
        }.apply {
            layoutParams = verticalParams(top = 14)
        })

        return scroll(content)
    }

    private fun openFeedback(prefix: String) {
        val title = Uri.encode("StreamCenter $prefix")
        openUrl("${StreamCenterPlugin.FEEDBACK_ISSUES_URL}?title=$title")
    }
}
