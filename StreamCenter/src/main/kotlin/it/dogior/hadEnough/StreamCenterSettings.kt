package it.dogior.hadEnough

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
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
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
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

private data class SourceRowState(
    val source: StreamCenterStreamingSource,
    var enabled: Boolean,
    var baseUrl: String,
)

private data class SourceTestResult(
    val source: StreamCenterStreamingSource,
    val enabled: Boolean,
    val url: String,
    val ok: Boolean,
    val details: String,
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
        secondaryActionText: String? = null,
        onSecondaryAction: (() -> Unit)? = null,
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

            if (secondaryActionText != null && onSecondaryAction != null) {
                addView(actionButton(secondaryActionText, COLOR_MUTED, onSecondaryAction).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginStart = dp(10)
                    }
                })
            }

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

    protected fun EditText.afterTextChanged(action: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                action(s?.toString().orEmpty())
            }
        })
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

    protected fun promptRestartAfterSave(message: String) {
        val dialogContext = context ?: return

        AlertDialog.Builder(dialogContext)
            .setTitle("Riavvia Applicazione")
            .setMessage(message)
            .setPositiveButton("Riavvia") { _, _ ->
                dismiss()
                restartApp()
            }
            .setNegativeButton("Piu Tardi", null)
            .show()
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
        val component = intent?.component ?: run {
            showToast("Impossibile riavviare automaticamente l'app. Chiudila e riaprila manualmente.")
            return
        }
        val restartIntent = Intent.makeRestartActivityTask(component).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val pendingFlags = PendingIntent.FLAG_CANCEL_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(appContext, 118503, restartIntent, pendingFlags)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            appContext.startActivity(restartIntent)
        } else {
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pendingIntent)
        }

        dismissAllowingStateLoss()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
            Runtime.getRuntime().exit(0)
        }, 150L)
    }
}

class StreamCenterSettings : StreamCenterBaseSettingsFragment() {
    private lateinit var statusContainer: LinearLayout
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::statusContainer.isInitialized) {
            renderStatusStrip()
        }
    }

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

        statusContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(2))
            layoutParams = verticalParams(top = 2)
        }
        content.addView(statusContainer)
        renderStatusStrip()
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

    override fun onStart() {
        super.onStart()
        sharedPref?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onStop() {
        sharedPref?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onStop()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (consumeMainSettingsRestartPromptSkip()) return
        offerRestartPrompt(
            "Hai chiuso le Impostazioni di StreamCenter.\nVuoi riavviare l'app ora?",
            suppressNextMainClosePrompt = false,
        )
    }

    private fun renderStatusStrip() {
        statusContainer.removeAllViews()
        val enabledSources = StreamCenterPlugin.streamingSources.count {
            StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, it.key)
        }
        statusContainer.addView(chip("Fonti $enabledSources/${StreamCenterPlugin.streamingSources.size}", COLOR_WARN))
        statusContainer.addView(chip(if (StreamCenterPlugin.shouldShowHomeScore(sharedPref)) "Valutazione Home: On" else "Valutazione Home: Off", COLOR_SUCCESS).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
        })
        statusContainer.addView(chip("${StreamCenterPlugin.getConfiguredHomeSections(sharedPref).size} Sezioni", COLOR_ACCENT).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
        })
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
    private val rows = mutableListOf<SourceRowState>()
    private lateinit var counter: TextView
    private lateinit var testSummary: TextView
    private lateinit var rowsContainer: LinearLayout
    private var sourceTestJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        loadRows()
        val content = rootContainer()
        content.addView(
            header(
                "Fonti Streaming",
                "Scegli Fonti, priorita e indirizzi da usare nel player.",
                actionText = "Salva",
            ) {
                saveRows()
                saveToast("Fonti Salvate")
            },
        )
        counter = chip("", COLOR_WARN)
        content.addView(counter.apply {
            layoutParams = verticalParams(top = 2)
        })
        content.addView(actionButton("Test Fonti", COLOR_SUCCESS) {
            testSources()
        }.apply {
            layoutParams = verticalParams(top = 8)
        })
        testSummary = bodyText("Test Fonti: non eseguito", 12).apply {
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        content.addView(testSummary)

        rowsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 6)
        }
        content.addView(rowsContainer)
        renderRows()

        return scroll(content)
    }

    override fun onDestroyView() {
        sourceTestJob?.cancel()
        sourceTestJob = null
        super.onDestroyView()
    }

    private fun loadRows() {
        rows.clear()
        StreamCenterPlugin.getStreamingSourcesInPriority(sharedPref).forEach { source ->
            rows += SourceRowState(
                source = source,
                enabled = StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, source.key),
                baseUrl = StreamCenterPlugin.getSourceBaseUrl(sharedPref, source.key),
            )
        }
    }

    private fun renderRows() {
        updateCounter()
        rowsContainer.removeAllViews()
        rows.forEachIndexed { index, row ->
            rowsContainer.addView(sourceRow(index, row))
        }
    }

    private fun sourceRow(index: Int, row: SourceRowState): LinearLayout {
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
            topLine.addView(titleText("${index + 1}. ${row.source.title}", 16, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            topLine.addView(Switch(requireContext()).apply {
                isChecked = row.enabled
                showText = false
                setOnCheckedChangeListener { _, checked ->
                    row.enabled = checked
                    rowView.background = cardBackground(if (checked) COLOR_CARD else "#151922", radius = 12)
                    saveRows(showMessage = false)
                    updateCounter()
                }
            })
            addView(topLine)
            addView(bodyText(row.source.summary, 12).apply {
                setPadding(0, dp(3), 0, 0)
            })

            val urlInput = input(row.baseUrl).apply {
                hint = row.source.defaultBaseUrl
                filters = arrayOf(InputFilter.LengthFilter(120))
                afterTextChanged { value ->
                    row.baseUrl = value.trim()
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        row.baseUrl = normalizeBaseUrl(text?.toString(), row.source.defaultBaseUrl)
                        setText(row.baseUrl)
                        saveRows(showMessage = false)
                    }
                }
            }
            addView(urlInput.apply {
                layoutParams = verticalParams(top = 8)
            })

            val controls = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 8)
            }
            controls.addView(moveButton("Su") {
                row.baseUrl = normalizeBaseUrl(urlInput.text?.toString(), row.source.defaultBaseUrl)
                moveRow(index, -1)
            })
            controls.addView(moveButton("Giu") {
                row.baseUrl = normalizeBaseUrl(urlInput.text?.toString(), row.source.defaultBaseUrl)
                moveRow(index, 1)
            })
            controls.addView(actionButton("Reset Link", COLOR_WARN) {
                row.baseUrl = row.source.defaultBaseUrl
                urlInput.setText(row.baseUrl)
                saveRows(showMessage = false)
            }.apply {
                minimumHeight = dp(38)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
            })
            addView(controls)
        }
    }

    private fun moveButton(textValue: String, onClick: () -> Unit): TextView {
        return actionButton(textValue, COLOR_ACCENT, onClick).apply {
            minimumHeight = dp(38)
            layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun moveRow(index: Int, direction: Int) {
        val target = index + direction
        if (target !in rows.indices) return
        rows[index] = rows[target].also { rows[target] = rows[index] }
        saveRows(showMessage = false)
        renderRows()
    }

    private fun saveRows(showMessage: Boolean = true) {
        rowsContainer.clearFocus()
        sharedPref?.edit {
            putString(StreamCenterPlugin.PREF_SOURCE_ORDER, rows.joinToString(",") { it.source.key })
            rows.forEach { row ->
                putBoolean(row.source.key, row.enabled)
                putString(
                    StreamCenterPlugin.sourceUrlKey(row.source.key),
                    normalizeBaseUrl(row.baseUrl, row.source.defaultBaseUrl),
                )
            }
        }
        if (showMessage) saveToast("Fonti Salvate")
    }

    private fun testSources() {
        if (sourceTestJob?.isActive == true) {
            saveToast("Test fonti gia in corso")
            return
        }

        saveRows(showMessage = false)
        if (rows.isEmpty()) {
            testSummary.text = "Test Fonti: nessuna fonte configurata"
            return
        }

        testSummary.setTextColor(Color.parseColor(COLOR_WARN))
        testSummary.text = "Test Fonti: controllo in corso..."
        sourceTestJob = CoroutineScope(Dispatchers.Main).launch {
            val results = withContext(Dispatchers.IO) {
                supervisorScope {
                    rows.map { row ->
                        async { testSource(row) }
                    }.awaitAll()
                }
            }
            if (!isAdded) return@launch

            val okCount = results.count { it.ok }
            val failedCount = results.size - okCount
            testSummary.setTextColor(Color.parseColor(if (failedCount == 0) COLOR_SUCCESS else COLOR_DANGER))
            testSummary.text = "Test Fonti: $okCount OK, $failedCount KO"

            AlertDialog.Builder(requireContext())
                .setTitle("Test Fonti")
                .setMessage(formatTestReport(results))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private suspend fun testSource(row: SourceRowState): SourceTestResult {
        val testUrl = testUrlFor(row)
        val startedAt = System.currentTimeMillis()

        return try {
            val response = app.get(
                testUrl,
                headers = sourceTestHeaders(testUrl),
                timeout = 10_000L,
            )
            val elapsedMs = System.currentTimeMillis() - startedAt
            val bodyLength = runCatching { response.text.length }.getOrDefault(0)
            val ok = response.isSuccessful && bodyLength > 0
            SourceTestResult(
                source = row.source,
                enabled = row.enabled,
                url = testUrl,
                ok = ok,
                details = "HTTP ${response.code} - ${elapsedMs}ms - ${bodyLength} caratteri",
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            SourceTestResult(
                source = row.source,
                enabled = row.enabled,
                url = testUrl,
                ok = false,
                details = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            )
        }
    }

    private fun testUrlFor(row: SourceRowState): String {
        val baseUrl = normalizeBaseUrl(row.baseUrl, row.source.defaultBaseUrl).trimEnd('/')
        return when (row.source.key) {
            StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY -> "$baseUrl/it/archive"
            StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY -> "$baseUrl/archivio"
            StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD -> "$baseUrl/filter?sort=0&keyword=test"
            StreamCenterPlugin.PREF_SOURCE_ANIMESATURN -> "$baseUrl/animelist?search=test"
            StreamCenterPlugin.PREF_SOURCE_HENTAIWORLD -> "$baseUrl/archive?search=test"
            StreamCenterPlugin.PREF_SOURCE_HENTAISATURN -> "$baseUrl/hentailist?search=test"
            else -> baseUrl
        }
    }

    private fun sourceTestHeaders(testUrl: String): Map<String, String> {
        return mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
            "Referer" to testUrl.substringBeforeLast('/', missingDelimiterValue = testUrl),
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        )
    }

    private fun formatTestReport(results: List<SourceTestResult>): String {
        return results.joinToString("\n\n") { result ->
            val state = if (result.enabled) "attiva" else "disattivata"
            val status = if (result.ok) "OK" else "KO"
            "$status - ${result.source.title} ($state)\n${result.url}\n${result.details}"
        }
    }

    private fun updateCounter() {
        val enabled = rows.count { it.enabled }
        counter.text = "Fonti $enabled/${rows.size}"
    }

    private fun normalizeBaseUrl(value: String?, fallback: String): String {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: fallback
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        return withScheme.trimEnd('/')
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
                onAction = { saveRows() },
                secondaryActionText = "Indietro",
                onSecondaryAction = { dismiss() },
            ),
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
                afterTextChanged { value ->
                    row.title = value.trim()
                }
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
            val countInput = input(row.count.toString(), widthDp = 58).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                filters = arrayOf(InputFilter.LengthFilter(2))
                hint = "${StreamCenterPlugin.MIN_HOME_COUNT}-${StreamCenterPlugin.MAX_HOME_COUNT}"
            }
            controls.addView(countInput)
            controls.addView(moveButton("Su") {
                syncRowsFromInputs()
                moveRow(index, -1)
            })
            controls.addView(moveButton("Giu") {
                syncRowsFromInputs()
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

    private fun normalizedCount(raw: String?, fallback: Int = StreamCenterPlugin.DEFAULT_HOME_COUNT): Int {
        return raw
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(StreamCenterPlugin.MIN_HOME_COUNT, StreamCenterPlugin.MAX_HOME_COUNT)
            ?: fallback.coerceIn(StreamCenterPlugin.MIN_HOME_COUNT, StreamCenterPlugin.MAX_HOME_COUNT)
    }

    private fun syncRowsFromInputs(updateInputs: Boolean = false) {
        rows.forEachIndexed { index, row ->
            val rowView = rowsContainer.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
            val topLine = rowView.getChildAt(0) as? LinearLayout
            val titleInput = rowView.getChildAt(1) as? EditText
            val controls = rowView.getChildAt(2) as? LinearLayout
            val enabledSwitch = topLine?.getChildAt(1) as? Switch
            val countInput = controls?.getChildAt(1) as? EditText

            row.enabled = enabledSwitch?.isChecked ?: row.enabled
            row.title = titleInput?.text?.toString()?.trim().orEmpty()
            row.count = normalizedCount(countInput?.text?.toString(), row.count)
            if (updateInputs) countInput?.setText(row.count.toString())
        }
    }

    private fun saveRows() {
        rowsContainer.clearFocus()
        syncRowsFromInputs(updateInputs = true)
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
        sharedPref?.edit(commit = true) {
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
                    resetAllSettings()
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

    private fun resetAllSettings() {
        sharedPref?.edit {
            clear()
        }
        context?.applicationContext?.let { appContext ->
            StreamCenter.setCacheDirectory(File(appContext.cacheDir, "streamcenter_tmdb_cache"))
            StreamCenter.clearAllCaches()
        }

        promptRestartAfterSave(
            "Impostazioni ripristinate. Vuoi riavviare l'applicazione ora per applicare subito i valori predefiniti?"
        )
    }
}
