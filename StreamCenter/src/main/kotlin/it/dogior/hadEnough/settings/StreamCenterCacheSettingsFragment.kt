package it.dogior.hadEnough.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.StreamCenter
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamCenterCacheSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Cache"


    private var viewScope: CoroutineScope? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewScope?.cancel()
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            minimumHeight = standardSubmenuMinimumHeight()
        }

        val meter = usageMeter()
        usageMeter = meter
        content.addView(meter.view)

        val enabledRow = switchRow(
            title = "Cache schede",
            summary = "Riapre all'istante le schede già visitate",
            checked = StreamCenterMediaCache.isEnabled(sharedPref),
            defaultChecked = StreamCenterMediaCache.isEnabled(null),
            accent = COLOR_ACCENT,
            icon = "⚡",
            fixedHeight = true,
        ) { enabled ->
            val preferences = sharedPref
            if (preferences == null) {
                saveToast("Impossibile aggiornare l'impostazione della cache")
            } else {
                preferences.edit().putBoolean(StreamCenterPlugin.PREF_MEDIA_CACHE_ENABLED, enabled).apply()
                cacheOptionRows.forEach { animateRowEnabledAppearance(it, enabled) }
                if (enabled) {
                    saveToast("Cache attivata")
                } else {
                    viewScope?.launch(Dispatchers.IO) {
                        StreamCenterMediaCache.clearAll()
                        withContext(Dispatchers.Main) {
                            saveToast("Cache disattivata e svuotata")
                            refreshStats()
                        }
                    }
                }
            }
        }

        val manageRow = settingsRow(
            title = "Schede salvate",
            summary = "Sfoglia, controlla la scadenza ed elimina",
            icon = "🗂",
            accent = COLOR_ACCENT,
            trailingViews = listOf(chevron(COLOR_ACCENT)),
        ) {
            openScreen(StreamCenterCacheArchiveSettingsFragment(), "StreamCenterCacheArchiveSettings")
        }.view

        val limitsLabel = bodyText(cacheLimitsSummary(), 12)
        val limitsRow = settingsRow(
            title = "Limiti",
            onReset = {
                sharedPref?.edit()
                    ?.remove(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_ENTRIES)
                    ?.remove(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_MB)?.apply()
                applyCacheLimits()
            },
            summaryView = limitsLabel,
            icon = "📐",
            accent = COLOR_ACCENT,
            trailingViews = listOf(chevron(COLOR_ACCENT)),
        ) {
            showLimitsDialog()
        }.view
        this.limitsLabel = limitsLabel

        val durationLabel = bodyText(cacheDurationSummary(), 12)
        val durationRow = settingsRow(
            title = "Durata",
            summaryView = durationLabel,
            onReset = {
                sharedPref?.edit()?.remove(StreamCenterPlugin.PREF_MEDIA_CACHE_COMPLETED_DAYS)?.apply()
                durationLabel.text = cacheDurationSummary()
                applyCacheDuration()
            },
            icon = "🕒",
            accent = COLOR_ACCENT,
            trailingViews = listOf(chevron(COLOR_ACCENT)),
        ) {
            showDurationDialog(durationLabel)
        }.view

        val homeCalendarRow = switchRow(
            title = "Calendario anime",
            summary = "Salva il calendario della Home per aprirlo subito",
            checked = StreamCenterPlugin.isHomeCalendarCacheEnabled(sharedPref),
            defaultChecked = StreamCenterPlugin.isHomeCalendarCacheEnabled(null),
            accent = COLOR_ACCENT,
            icon = "📅",
        ) { enabled ->
            val preferences = sharedPref
            if (preferences == null) {
                saveToast("Impossibile aggiornare l'impostazione della cache")
            } else {
                preferences.edit().putBoolean(StreamCenterPlugin.PREF_HOME_CALENDAR_CACHE_ENABLED, enabled).apply()
                StreamCenter.clearHomeCalendarCache()
                saveToast(if (enabled) "Cache calendario attivata" else "Cache calendario disattivata e svuotata")
            }
        }
        val clearCalendarRow = settingsRow(
            title = "Svuota calendario",
            summary = "Lo ricarica alla prossima apertura della Home",
            icon = "🗑",
            accent = COLOR_ACCENT,
        ) {
            StreamCenter.clearHomeCalendarCache()
            saveToast("Cache calendario svuotata")
        }.view

        content.addView(sectionHeading("Schede"))
        addAdaptiveCardGrid(content, listOf(enabledRow, manageRow, limitsRow, durationRow))
        cacheOptionRows.clear()
        cacheOptionRows += listOf(manageRow, limitsRow, durationRow)
        if (!StreamCenterMediaCache.isEnabled(sharedPref)) cacheOptionRows.forEach { it.alpha = settingsRowDisabledAlpha }
        content.addView(sectionHeading("Calendario Home"))
        addAdaptiveCardGrid(content, listOf(homeCalendarRow, clearCalendarRow))
        content.addView(
            bodyText("Su Android TV e dispositivi poco potenti è meglio lasciare la cache spenta", 12).apply {
                setPadding(dp(6), dp(14), dp(6), 0)
            },
        )
        refreshStats()

        return scroll(content, fixedSubmenuHeight = true)
    }

    private var usageMeter: UsageMeterViews? = null
    private var limitsLabel: TextView? = null
    private val cacheOptionRows = mutableListOf<View>()

    override fun onDestroyView() {
        viewScope?.cancel()
        viewScope = null
        usageMeter = null
        limitsLabel = null
        cacheOptionRows.clear()
        super.onDestroyView()
    }

    private fun cacheLimitsSummary(): String =
        "Fino a ${StreamCenterPlugin.mediaCacheMaxEntries(sharedPref)} schede e ${StreamCenterPlugin.mediaCacheMaxMb(sharedPref)} MB"

    private fun refreshStats() {
        val meter = usageMeter ?: return
        limitsLabel?.text = cacheLimitsSummary()
        viewScope?.launch(Dispatchers.IO) {
            val stats = runCatching { StreamCenterMediaCache.stats() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val ctx = context ?: return@withContext
                if (stats == null || stats.entryCount == 0) {
                    meter.update(
                        "Nessuna scheda",
                        if (StreamCenterMediaCache.isEnabled(sharedPref)) "Le schede che apri verranno salvate qui"
                        else "Attiva la cache per riaprire subito le schede già viste",
                        0f,
                    )
                    return@withContext
                }
                val used = Formatter.formatShortFileSize(ctx, stats.totalBytes)
                val maximum = Formatter.formatShortFileSize(ctx, stats.maxBytes)
                meter.update(
                    if (stats.entryCount == 1) "1 scheda" else "${stats.entryCount} schede",
                    "$used di $maximum, massimo ${stats.maxEntries} schede",
                    maxOf(
                        stats.totalBytes.toFloat() / stats.maxBytes.coerceAtLeast(1L),
                        stats.entryCount.toFloat() / stats.maxEntries.coerceAtLeast(1),
                    ),
                )
            }
        }
    }

    private fun showLimitsDialog() {
        val ctx = context ?: return
        val entriesInput = input(StreamCenterPlugin.mediaCacheMaxEntries(sharedPref).toString(), widthDp = 130).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val mbInput = input(StreamCenterPlugin.mediaCacheMaxMb(sharedPref).toString(), widthDp = 130).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        resetOnLongPress(entriesInput, "Numero massimo di schede") {
            sharedPref?.edit()?.remove(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_ENTRIES)?.apply()
            entriesInput.setText(StreamCenterPlugin.mediaCacheMaxEntries(sharedPref).toString())
            entriesInput.error = null
            applyCacheLimits()
        }
        resetOnLongPress(mbInput, "Dimensione massima della cache") {
            sharedPref?.edit()?.remove(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_MB)?.apply()
            mbInput.setText(StreamCenterPlugin.mediaCacheMaxMb(sharedPref).toString())
            mbInput.error = null
            applyCacheLimits()
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(sectionLabel("Numero massimo di schede (10–5000)"))
            addView(entriesInput)
            addView(sectionLabel("Dimensione massima in MB (1–2000)").apply { setPadding(0, dp(14), 0, 0) })
            addView(mbInput)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Limiti"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val prefs = sharedPref
                val entries = entriesInput.text.toString().trim().toIntOrNull()
                val mb = mbInput.text.toString().trim().toIntOrNull()
                if (prefs == null || entries == null || mb == null) {
                    saveToast("Valori non validi")
                    return@setOnClickListener
                }
                prefs.edit()
                    .putInt(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_ENTRIES, entries.coerceIn(10, 5000))
                    .putInt(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_MB, mb.coerceIn(1, 2000))
                    .apply()
                saveToast("Limiti aggiornati")
                dialog.dismiss()
                applyCacheLimits()
            }
        }
        dialog.show()
    }

    private fun applyCacheLimits() {
        viewScope?.launch(Dispatchers.IO) {
            StreamCenterMediaCache.applyLimits()
            withContext(Dispatchers.Main) { refreshStats() }
        }
    }

    private fun cacheDurationSummary(): String =
        "Conclusi ${StreamCenterPlugin.mediaCacheCompletedDays(sharedPref)} giorni, in corso fino al prossimo episodio"

    private fun showDurationDialog(durationLabel: TextView) {
        val ctx = context ?: return
        val daysInput = input(StreamCenterPlugin.mediaCacheCompletedDays(sharedPref).toString(), widthDp = 130).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(sectionLabel("Giorni per film, serie e anime conclusi (1–${StreamCenterMediaCache.MAX_RETENTION_DAYS})"))
            addView(daysInput)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Durata"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val days = daysInput.text.toString().trim().toIntOrNull()
                val prefs = sharedPref
                if (prefs == null || days == null || days !in 1..StreamCenterMediaCache.MAX_RETENTION_DAYS) {
                    daysInput.error = "Inserisci un valore da 1 a ${StreamCenterMediaCache.MAX_RETENTION_DAYS}"
                    return@setOnClickListener
                }
                prefs.edit().putInt(StreamCenterPlugin.PREF_MEDIA_CACHE_COMPLETED_DAYS, days).apply()
                durationLabel.text = cacheDurationSummary()
                applyCacheDuration()
                saveToast("Durata cache aggiornata")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun applyCacheDuration() {
        viewScope?.launch(Dispatchers.IO) {
            StreamCenterMediaCache.refreshCompletedExpiry()
            withContext(Dispatchers.Main) { refreshStats() }
        }
    }
}
