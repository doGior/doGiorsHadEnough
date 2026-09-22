package it.dogior.hadEnough.settings

import it.dogior.hadEnough.StreamCenterPlugin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.edit

class StreamCenterInterfaceSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Interfaccia"

    override val screenIcon: String = "✨"

    override val screenAccent: String = COLOR_PERFORMANCE

    private var setPerformanceChecked: ((Boolean) -> Unit)? = null
    private val effectRows = mutableListOf<View>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
        content.minimumHeight = standardSubmenuMinimumHeight()

        addAdaptiveCardGrid(content, listOf(performanceRow(), tvModeRow()))

        content.addView(sectionHeading("Effetti", COLOR_VISUAL_EFFECTS))
        effectRows.clear()
        effectRows += effectRow(
            icon = "🎞️",
            title = "Animazioni",
            summary = "Transizioni e movimenti.",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS,
            optionAccent = COLOR_VISUAL_EFFECTS,
        )
        effectRows += effectRow(
            icon = "🌫️",
            title = "Sfocatura finestre",
            summary = "Sfondo sfocato dietro i dialoghi.",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR,
            optionAccent = COLOR_VISUAL_BLUR,
        )
        effectRows += effectRow(
            icon = "✨",
            title = "Intestazione",
            summary = "Bagliore sul titolo in alto.",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE,
            optionAccent = COLOR_VISUAL_HEADER,
        )
        effectRows += effectRow(
            icon = "🌌",
            title = "Universo animato",
            summary = "Particelle e aurora sullo sfondo.",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES,
            optionAccent = COLOR_PARTICLES,
        )
        effectRows += effectRow(
            icon = "🌐",
            title = "Mostra IP pubblico",
            summary = "In fondo al menu principale.",
            preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PUBLIC_IP,
            optionAccent = COLOR_PUBLIC_IP,
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

    private fun performanceRow(): View = switchRow(
        title = "Modalità Prestazioni",
        summary = "Spegne tutti gli effetti e limita i metadati.",
        checked = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref),
        defaultChecked = StreamCenterPlugin.isPerformanceModeEnabled(null),
        accent = COLOR_PERFORMANCE,
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
        summary = "Interfaccia pensata per il telecomando.",
        checked = StreamCenterPlugin.isForceTvModeEnabled(sharedPref),
        defaultChecked = StreamCenterPlugin.isForceTvModeEnabled(null),
        accent = COLOR_DISPLAY,
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
        strokeColor = tint(optionAccent, "55"),
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
