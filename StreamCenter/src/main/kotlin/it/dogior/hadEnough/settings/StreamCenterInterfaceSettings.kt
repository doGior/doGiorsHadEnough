package it.dogior.hadEnough.settings

import it.dogior.hadEnough.StreamCenterPlugin

import android.graphics.Color
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamCenterInterfaceSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Aspetto"

    override val screenIcon: String = "✨"


    private var setPerformanceChecked: ((Boolean) -> Unit)? = null
    private val effectRows = mutableListOf<View>()
    private var pendingThemeExport: String? = null
    private val themeExportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingThemeExport
        pendingThemeExport = null
        if (uri != null && payload != null) {
            val resolver = context?.contentResolver ?: return@registerForActivityResult
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        checkNotNull(resolver.openOutputStream(uri, "wt")) { "File non scrivibile" }
                            .bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                    }
                }
                if (isAdded) saveToast(if (result.isSuccess) "Tema esportato" else "Impossibile esportare il tema")
            }
        }
    }
    private val themeImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = context?.contentResolver ?: return@registerForActivityResult
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = checkNotNull(resolver.openInputStream(uri)) { "File non leggibile" }.use { stream ->
                            val buffer = ByteArray(StreamCenterCustomThemes.MAX_FILE_BYTES + 1)
                            var count = 0
                            while (count < buffer.size) {
                                val read = stream.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                count += read
                            }
                            buffer.copyOf(count)
                        }
                        require(bytes.size <= StreamCenterCustomThemes.MAX_FILE_BYTES) { "File tema troppo grande" }
                        StreamCenterCustomThemes.importTheme(bytes.toString(Charsets.UTF_8))
                    }
                }
                if (isAdded && view != null) result.fold(
                    onSuccess = { showThemeEditor(it, editing = false) },
                    onFailure = { saveToast(it.message ?: "Impossibile importare il tema") },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingThemeExport = savedInstanceState?.getString("pendingThemeExport")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingThemeExport?.let { outState.putString("pendingThemeExport", it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
        content.minimumHeight = standardSubmenuMinimumHeight()

        content.addView(sectionHeading("Tema e dispositivo", topMargin = 0))
        content.addView(themeRow())
        addAdaptiveCardGrid(content, listOf(performanceRow(), tvModeRow()))

        content.addView(sectionHeading("Effetti"))
        effectRows.clear()
        effectRows += effectRow(
            icon = "🎞️",
            title = "Animazioni",
            summary = "Transizioni e movimenti",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS,
            optionAccent = COLOR_ACCENT,
        )
        effectRows += effectRow(
            icon = "🌫️",
            title = "Sfocatura finestre",
            summary = "Sfondo sfocato dietro i dialoghi",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR,
            optionAccent = COLOR_ACCENT,
        )
        effectRows += effectRow(
            icon = "✨",
            title = "Intestazione",
            summary = "Riflesso colorato sul titolo in alto",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE,
            optionAccent = COLOR_ACCENT,
        )
        effectRows += effectRow(
            icon = "🌌",
            title = "Sfondo animato",
            summary = "Puntini in movimento e onda luminosa",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES,
            optionAccent = COLOR_ACCENT,
        )
        effectRows += effectRow(
            icon = "🌐",
            title = "Mostra IP pubblico",
            summary = "In fondo al menu principale",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PUBLIC_IP,
            optionAccent = COLOR_ACCENT,
        )
        addAdaptiveCardGrid(content, effectRows)
        applyPerformanceState(StreamCenterPlugin.isPerformanceModeEnabled(sharedPref))

        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        effectRows.clear()
        setPerformanceChecked = null
        super.onDestroyView()
    }

    private fun themeRow(): View {
        val arrow = chevron(COLOR_ACCENT)
        return settingsRow(
            title = "Tema",
            summary = StreamCenterThemes.selectedLabel(sharedPref),
            leadingView = themeSwatch(StreamCenterThemes.selectedKey(sharedPref)).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            },
            trailingViews = listOf(arrow),
            touchTarget = arrow,
        ) { showThemeDialog() }.view
    }

    private fun showThemeDialog() {
        val selected = StreamCenterThemes.selectedKey(sharedPref)
        val options = StreamCenterThemes.available(sharedPref).map { Triple(it.key, it.label, it.description) } +
            Triple(StreamCenterThemes.ROTATION_KEY, "Rotazione", "Un tema a caso a ogni apertura")
        val list = groupColumn().apply { setPadding(dp(20), dp(12), dp(20), dp(20)) }
        lateinit var dialog: AlertDialog
        options.forEach { (key, label, description) ->
            val check = bodyText(if (key == selected) "✓" else "", 18).apply {
                setTextColor(Color.parseColor(COLOR_ACCENT))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            list.addView(settingsRow(
                title = label,
                summary = description,
                leadingView = themeSwatch(key).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
                },
                trailingViews = listOf(check),
                topMargin = 8,
                accessibilityState = { if (key == selected) "Selezionato" else null },
            ) {
                dialog.dismiss()
                val custom = StreamCenterCustomThemes.load(sharedPref).firstOrNull { it.key == key }
                if (custom != null) showCustomThemeActions(custom)
                else if (key != selected) applyTheme(key)
            }.view)
        }
        list.addView(settingsRow(
            title = "Crea tema personalizzato", summary = "Scegli una base e personalizza tutti i colori",
            icon = "🎨", topMargin = 16,
        ) {
            dialog.dismiss()
            showThemeEditor(StreamCenterThemes.active, editing = false, suggestedName = "Il mio tema")
        }.view)
        list.addView(settingsRow(
            title = "Importa tema", summary = "Apri un file JSON di StreamCenter", icon = "📥", topMargin = 8,
        ) {
            dialog.dismiss()
            runCatching { themeImportPicker.launch(arrayOf("*/*")) }
                .onFailure { saveToast("Nessuna app disponibile per aprire i file") }
        }.view)
        dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Tema"))
            .setView(scrollableDialogView(list))
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun applyTheme(key: String) {
        sharedPref?.edit { putString(StreamCenterThemes.PREF_THEME, key) }
        StreamCenterThemes.applyFor(sharedPref)
        val host = settingsHost
        if (host != null) host.restyle() else saveToast("Riapri le impostazioni per vedere il nuovo tema")
    }

    private fun showCustomThemeActions(palette: StreamCenterPalette) {
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle(palette.label))
            .setItems(arrayOf("Applica", "Modifica", "Duplica", "Esporta tema", "Elimina")) { _, index ->
                when (index) {
                    0 -> applyTheme(palette.key)
                    1 -> showThemeEditor(palette, editing = true)
                    2 -> showThemeEditor(palette, editing = false, suggestedName = "${palette.label.take(50)} (copia)")
                    3 -> exportTheme(palette)
                    4 -> confirmDeleteTheme(palette)
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun exportTheme(palette: StreamCenterPalette) {
        pendingThemeExport = StreamCenterCustomThemes.export(palette)
        val name = palette.label.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        runCatching { themeExportPicker.launch("StreamCenter_$name.json") }.onFailure {
            pendingThemeExport = null
            saveToast("Nessuna app disponibile per salvare i file")
        }
    }

    private fun confirmDeleteTheme(palette: StreamCenterPalette) {
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Elimina tema"))
            .setMessage("Eliminare «${palette.label}»? Se è attivo, verrà applicato Acqua notturna.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ ->
                val prefs = sharedPref ?: return@setPositiveButton
                val wasSelected = StreamCenterThemes.selectedKey(prefs) == palette.key
                StreamCenterCustomThemes.delete(prefs, palette.key)
                if (wasSelected) applyTheme(StreamCenterThemes.ocean.key) else showThemeDialog()
            }.create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun showThemeEditor(
        palette: StreamCenterPalette,
        editing: Boolean,
        suggestedName: String = palette.label,
    ) {
        val content = groupColumn().apply { setPadding(dp(20), dp(12), dp(20), dp(20)) }
        content.addView(bodyText("Nome del tema", 13))
        val nameInput = input(suggestedName).apply {
            filters = arrayOf(InputFilter.LengthFilter(StreamCenterCustomThemes.MAX_NAME_LENGTH))
            layoutParams = verticalParams(8)
            contentDescription = "Nome del tema"
        }
        content.addView(nameInput)
        content.addView(bodyText("Colori in formato #RRGGBB. Il tema viene applicato dopo il salvataggio.", 12).apply {
            layoutParams = verticalParams(12)
        })

        val preview = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = verticalParams(12)
        }
        val previewTitle = bodyText("Anteprima del tema", 16)
        val previewSummary = bodyText("Testo secondario e schede", 12)
        val previewCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = verticalParams(10)
        }
        val previewAction = bodyText("✓ Colore principale", 14)
        val previewStatuses = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        val previewSuccess = bodyText("Conferma   ", 12)
        val previewDanger = bodyText("Errore", 12)
        previewStatuses.addView(previewSuccess)
        previewStatuses.addView(previewDanger)
        preview.addView(previewTitle)
        preview.addView(previewSummary)
        previewCard.addView(previewAction)
        previewCard.addView(previewStatuses)
        preview.addView(previewCard)
        content.addView(preview)

        val fields = linkedMapOf<String, EditText>()
        val swatches = linkedMapOf<String, View>()
        fun updatePreview() {
            val values = fields.mapValues { it.value.text.toString().trim() }
            values.forEach { (key, value) ->
                if (StreamCenterCustomThemes.colorPattern.matches(value)) swatches[key]?.background = solid(value, 8)
            }
            if (values.size != StreamCenterCustomThemes.colorLabels.size ||
                values.values.any { !StreamCenterCustomThemes.colorPattern.matches(it) }) return
            fun color(key: String) = Color.parseColor(values.getValue(key))
            preview.background = outlined(values.getValue("stroke"), values.getValue("background"), 12)
            previewTitle.setTextColor(color("text"))
            previewSummary.setTextColor(color("muted"))
            previewCard.background = outlined(values.getValue("stroke"), values.getValue("surface"), 8)
            previewAction.setTextColor(color("accent"))
            previewSuccess.setTextColor(color("success"))
            previewDanger.setTextColor(color("danger"))
        }

        content.addView(settingsRow(
            title = "Colori da un tema esistente", summary = "Carica una palette come punto di partenza",
            icon = "🎨", topMargin = 12,
        ) {
            val bases = StreamCenterThemes.available(sharedPref)
            val picker = AlertDialog.Builder(requireContext())
                .setCustomTitle(dialogTitle("Palette di partenza"))
                .setItems(bases.map { it.label }.toTypedArray()) { _, index ->
                    StreamCenterCustomThemes.colors(bases[index]).forEach { (key, value) -> fields[key]?.setText(value) }
                }
                .setNegativeButton("Annulla", null).create()
            applyDialogBackdrop(picker)
            picker.show()
        }.view)

        val initial = StreamCenterCustomThemes.colors(palette)
        StreamCenterCustomThemes.colorLabels.forEach { (key, label) ->
            content.addView(bodyText(label, 13).apply { layoutParams = verticalParams(14) })
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(6)
            }
            val swatch = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = solid(initial.getValue(key), 8)
            }
            swatches[key] = swatch
            row.addView(swatch)
            val field = input(initial.getValue(key)).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.LengthFilter(7))
                contentDescription = "$label, colore esadecimale"
            }
            fields[key] = field
            field.doAfterTextChanged { updatePreview() }
            row.addView(field)
            content.addView(row)
        }
        updatePreview()
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle(if (editing) "Modifica tema" else "Crea tema"))
            .setView(scrollableDialogView(content))
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva e applica", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val invalid = fields.entries.firstOrNull {
                !StreamCenterCustomThemes.colorPattern.matches(it.value.text.toString().trim())
            }
            if (invalid != null) {
                invalid.value.error = "Usa il formato #RRGGBB"
                invalid.value.requestFocus()
                return@setOnClickListener
            }
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                nameInput.error = "Inserisci un nome"
                nameInput.requestFocus()
                return@setOnClickListener
            }
            val prefs = sharedPref ?: run {
                saveToast("Impossibile salvare il tema")
                return@setOnClickListener
            }
            val colors = fields.mapValues { it.value.text.toString() }
            val saved = if (editing) StreamCenterCustomThemes.create(name, colors, palette.key)
                else StreamCenterCustomThemes.create(name, colors)
            StreamCenterCustomThemes.save(prefs, saved)
            dialog.dismiss()
            applyTheme(saved.key)
        }
    }

    private fun themeSwatch(key: String): View {
        val palettes = StreamCenterThemes.available(sharedPref).firstOrNull { it.key == key }?.let(::listOf)
            ?: StreamCenterThemes.all.take(6)
        return FrameLayout(requireContext()).apply {
            background = outlined(COLOR_STROKE, palettes.first().background, 10)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val single = palettes.size == 1
            val dot = if (single) 14 else 7
            val columns = if (single) 1 else 3
            val rows = (palettes.size + columns - 1) / columns
            val step = dp(10).toFloat()
            palettes.forEachIndexed { index, palette ->
                addView(View(requireContext()).apply {
                    background = solid(palette.accent, 999)
                    layoutParams = FrameLayout.LayoutParams(dp(dot), dp(dot), Gravity.CENTER)
                    translationX = (index % columns - (columns - 1) / 2f) * step
                    translationY = (index / columns - (rows - 1) / 2f) * step
                })
            }
        }
    }

    private fun performanceRow(): View = switchRow(
        title = "Modalità Prestazioni",
        summary = "Spegne tutti gli effetti e limita i metadati",
        checked = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref),
        defaultChecked = StreamCenterPlugin.isPerformanceModeEnabled(null),
        accent = COLOR_ACCENT,
        icon = "⚡",
        fixedHeight = true,
        bindChecked = { setter -> setPerformanceChecked = setter },
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_PERFORMANCE_MODE, enabled) }
        refreshVisibleSettingsEffects()
        applyPerformanceState(enabled)
        saveToast(if (enabled) "Modalità Prestazioni ON" else "Modalità Prestazioni OFF")
    }

    private fun tvModeRow(): View = switchRow(
        title = "Modalità TV",
        summary = "Interfaccia pensata per il telecomando",
        checked = StreamCenterPlugin.isForceTvModeEnabled(sharedPref),
        defaultChecked = StreamCenterPlugin.isForceTvModeEnabled(null),
        accent = COLOR_ACCENT,
        icon = "📺",
        fixedHeight = true,
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_FORCE_TV_MODE, enabled) }
        saveToast("Riapri le impostazioni per applicare la modalità TV")
    }

    private fun effectRow(
        icon: String,
        title: String,
        summary: String,
        preferenceKey: String,
        optionAccent: String,
    ): View = switchRow(
        title = title,
        summary = summary,
        checked = sharedPref?.getBoolean(preferenceKey, true) ?: true,
        defaultChecked = true,
        accent = optionAccent,
        icon = icon,
        fixedHeight = true,
    ) { enabled ->
        sharedPref?.edit { putBoolean(preferenceKey, enabled) }
        refreshVisibleSettingsEffects()
    }

    private fun applyPerformanceState(performanceMode: Boolean) {
        effectRows.forEach { row ->
            row.alpha = if (performanceMode) 0.45f else 1f
            setRowInteractive(row, !performanceMode)
        }
    }

    private fun setRowInteractive(row: View, interactive: Boolean) {
        row.isEnabled = interactive
        row.isClickable = interactive
        row.isFocusable = interactive
        (row as? LinearLayout)?.let { group ->
            for (index in 0 until group.childCount) {
                group.getChildAt(index).isEnabled = interactive
            }
        }
    }
}
