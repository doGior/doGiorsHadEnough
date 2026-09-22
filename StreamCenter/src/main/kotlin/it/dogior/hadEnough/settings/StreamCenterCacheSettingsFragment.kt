package it.dogior.hadEnough.settings

import android.content.Context
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
import it.dogior.hadEnough.cache.CachedMediaEntry
import it.dogior.hadEnough.cache.CachedMediaSummary
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DETAIL_EPISODE_LIMIT = 20

class StreamCenterCacheSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Cache"

    override val screenAccent: String = COLOR_CACHE

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

        val enabledRow = switchRow(
            title = "Cache schede",
            summary = "Riapre all'istante le schede già visitate.",
            checked = StreamCenterMediaCache.isEnabled(sharedPref),
            defaultChecked = StreamCenterMediaCache.isEnabled(null),
            accent = COLOR_CACHE,
            icon = "⚡",
            fixedHeight = true,
        ) { enabled ->
            val preferences = sharedPref
            if (preferences == null) {
                saveToast("Impossibile aggiornare l'impostazione della cache")
            } else {
                preferences.edit().putBoolean(StreamCenterPlugin.PREF_MEDIA_CACHE_ENABLED, enabled).apply()
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
            title = "Gestione cache",
            summary = "Schede salvate, dimensione e scadenza.",
            icon = "🗂",
            accent = COLOR_CACHE,
            fillColor = COLOR_CARD_ALT,
            trailingViews = listOf(chevron(COLOR_CACHE)),
            fixedHeight = true,
        ) {
            showCacheArchive()
        }.view

        val limitsRow = settingsRow(
            title = "Limiti cache",
            onReset = {
                sharedPref?.edit()
                    ?.remove(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_ENTRIES)
                    ?.remove(StreamCenterPlugin.PREF_MEDIA_CACHE_MAX_MB)?.apply()
                applyCacheLimits()
            },
            summary = "Numero massimo di schede e dimensione.",
            icon = "📐",
            accent = COLOR_CACHE,
            fillColor = COLOR_CARD_ALT,
            trailingViews = listOf(chevron(COLOR_CACHE)),
            fixedHeight = true,
        ) {
            showLimitsDialog()
        }.view

        addAdaptiveCardGrid(content, listOf(enabledRow, manageRow, limitsRow))

        val statsText = bodyText("", 12).apply {
            setPadding(dp(6), dp(14), dp(6), 0)
        }
        statsView = statsText
        content.addView(statsText)
        refreshStats()

        content.addView(
            bodyText(
                "Le cache non sono consigliate su dispositivi poco potenti (es. Android TV e simili).",
                11,
            ).apply {
                setTextColor(android.graphics.Color.parseColor(COLOR_DANGER))
                alpha = 0.55f
                setPadding(dp(6), dp(6), dp(6), 0)
            },
        )

        return scroll(content, fixedSubmenuHeight = true)
    }

    private var statsView: TextView? = null

    override fun onDestroyView() {
        viewScope?.cancel()
        viewScope = null
        statsView = null
        super.onDestroyView()
    }

    private fun refreshStats() {
        val target = statsView ?: return
        viewScope?.launch(Dispatchers.IO) {
            val stats = runCatching { StreamCenterMediaCache.stats() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val ctx = context ?: return@withContext
                target.text = if (stats == null) {
                    "Nessuna scheda in cache."
                } else {
                    "Voci in cache: ${stats.entryCount}/${stats.maxEntries} • " +
                        "Dimensione totale: ${Formatter.formatShortFileSize(ctx, stats.totalBytes)}/" +
                        Formatter.formatShortFileSize(ctx, stats.maxBytes)
                }
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
            addView(bodyText("Numero massimo di schede (10–5000):", 12))
            addView(entriesInput)
            addView(bodyText("Dimensione massima in MB (1–2000):", 12).apply { setPadding(0, dp(14), 0, 0) })
            addView(mbInput)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Limiti cache"))
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

    private fun showCacheArchive() {
        val ctx = context ?: return
        viewScope?.launch(Dispatchers.IO) {
            val summaries = runCatching { StreamCenterMediaCache.listSummaries() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                showCacheArchiveDialog(summaries)
            }
        }
    }

    private fun showCacheArchiveDialog(summaries: List<CachedMediaSummary>) {
        val ctx = context ?: return
        if (summaries.isEmpty()) {
            val dialog = AlertDialog.Builder(ctx)
                .setCustomTitle(dialogTitle("Cache vuota"))
                .setMessage(
                    if (StreamCenterMediaCache.isEnabled(sharedPref)) {
                        "Non è ancora stata salvata alcuna scheda."
                    } else {
                        "La cache è disattivata. Attivala per iniziare a salvare le schede visitate."
                    },
                )
                .setNegativeButton("Chiudi", null)
                .create()
            applyDialogBackdrop(dialog)
            dialog.show()
            return
        }

        lateinit var dialog: AlertDialog
        val rowsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun renderRows(filter: String) {
            rowsContainer.removeAllViews()
            val query = filter.trim().lowercase(Locale.getDefault())
            val filtered = if (query.isBlank()) {
                summaries
            } else {
                summaries.filter { it.title.lowercase(Locale.getDefault()).contains(query) }
            }
            if (filtered.isEmpty()) {
                rowsContainer.addView(
                    bodyText("Nessun risultato.", 12).apply { setPadding(dp(4), dp(16), dp(4), dp(16)) },
                )
                return
            }
            filtered.forEach { summary ->
                val arrow = chevron(COLOR_CACHE)
                val deleteButton = deleteIconButton(
                    description = "Elimina la cache di ${summary.title}",
                    size = 34,
                ) {
                    showDeleteCacheConfirmation(summary, dialog)
                }
                rowsContainer.addView(
                    settingsRow(
                        title = summary.title,
                        summary = archiveSummaryLine(summary),
                        icon = iconFor(summary),
                        accent = COLOR_CACHE,
                        fillColor = COLOR_CARD_ALT,
                        trailingViews = listOf(deleteButton, arrow),
                        touchTarget = arrow,
                        topMargin = 8,
                    ) {
                        showCacheDetail(summary)
                    }.view,
                )
            }
        }
        renderRows("")
        val searchInput = input("").apply {
            hint = "Cerca…"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderRows(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(16))
            addView(searchInput)
            addView(
                ScrollView(ctx).apply {
                    addView(rowsContainer)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.heightPixels * 0.55f).toInt(),
                    ).apply { topMargin = dp(10) }
                },
            )
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Gestione cache"))
            .setView(container)
            .setPositiveButton("Elimina scadute", null)
            .setNeutralButton("Svuota tutto", null)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                showClearAllConfirmation(dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                viewScope?.launch(Dispatchers.IO) {
                    val removed = runCatching { StreamCenterMediaCache.cleanup() }.getOrDefault(0)
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        saveToast(if (removed > 0) "Eliminate $removed voci scadute" else "Nessuna voce scaduta")
                        dialog.dismiss()
                        refreshStats()
                        showCacheArchive()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showCacheDetail(summary: CachedMediaSummary) {
        viewScope?.launch(Dispatchers.IO) {
            val entry = runCatching { StreamCenterMediaCache.readRaw(summary.key) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                showCacheDetailDialog(summary, entry)
            }
        }
    }

    private fun showCacheDetailDialog(
        summary: CachedMediaSummary,
        entry: CachedMediaEntry?,
    ) {
        val ctx = context ?: return
        val text = buildCacheDetailText(ctx, summary, entry)
        val body = bodyText(text, 12).apply {
            setPadding(dp(20), dp(12), dp(20), dp(16))
            setTextIsSelectable(true)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(summary.title))
            .setView(ScrollView(ctx).apply { addView(body) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun buildCacheDetailText(ctx: Context, summary: CachedMediaSummary, entry: CachedMediaEntry?): String {
        if (entry == null) {
            return "Contenuto non leggibile.\n\n" +
                "Salvata il: ${dateLabel(summary.cachedAtMillis)}\n" +
                "Scadenza: ${expiryLabel(summary.expiresAtMillis)}\n" +
                "Dimensione: ${Formatter.formatFileSize(ctx, summary.sizeBytes)}"
        }
        return buildString {
            appendLine("Tipo: ${typeLabel(entry.type, entry.animeMovie)}")
            appendLine("Stato: ${statusLabel(entry.showStatus)}")
            if (entry.type == StreamCenterMediaCache.TYPE_ANIME) {
                entry.animeType?.takeIf(String::isNotBlank)?.let { appendLine("Formato: $it") }
            }
            entry.year?.let { appendLine("Anno: $it") }
            entry.duration?.let { appendLine("Durata: $it min") }
            entry.contentRating?.takeIf(String::isNotBlank)?.let { appendLine("Classificazione: $it") }
            entry.score?.takeIf(String::isNotBlank)?.let { appendLine("Voto: $it") }
            if (entry.comingSoon) appendLine("In arrivo: sì")
            entry.englishTitle?.takeIf(String::isNotBlank)?.let { appendLine("Titolo inglese: $it") }
            entry.nativeTitle?.takeIf(String::isNotBlank)?.let { appendLine("Titolo originale: $it") }
            if (entry.alternativeTitles.isNotEmpty()) {
                appendLine("Titoli alternativi: ${entry.alternativeTitles.joinToString(", ")}")
            }
            entry.trackingIds?.let { ids ->
                val idParts = listOfNotNull(
                    ids.tmdb?.let { "TMDB $it" },
                    ids.imdb?.let { "IMDb $it" },
                    ids.anilist?.let { "AniList $it" },
                    ids.mal?.let { "MAL $it" },
                    ids.kitsu?.let { "Kitsu $it" },
                    ids.simkl?.let { "Simkl $it" },
                )
                if (idParts.isNotEmpty()) appendLine("ID: ${idParts.joinToString(" · ")}")
            }

            appendLine()
            appendLine("Poster: ${entry.posterUrl ?: "—"}")
            entry.backgroundPosterUrl?.takeIf(String::isNotBlank)?.let { appendLine("Sfondo: $it") }
            entry.logoUrl?.takeIf(String::isNotBlank)?.let { appendLine("Logo: $it") }
            appendLine("Trailer: ${entry.trailerUrl ?: "—"}")
            appendLine("URL scheda: ${entry.url}")

            if (entry.tags.isNotEmpty()) {
                appendLine()
                appendLine("Tag: ${entry.tags.joinToString(", ")}")
            }
            entry.plot?.takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine("Trama:")
                appendLine(it)
            }

            if (entry.seasons.isNotEmpty()) {
                appendLine()
                appendLine("Stagioni: ${entry.seasons.size}")
                entry.seasons.forEach { season ->
                    val name = season.name?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                    appendLine("  · Stagione ${season.season}$name")
                }
            }

            appendLine()
            if (entry.recommendations.isEmpty()) {
                appendLine("Raccomandazioni: 0")
            } else {
                appendLine("Raccomandazioni: ${entry.recommendations.size}")
                entry.recommendations.take(DETAIL_EPISODE_LIMIT).forEach { rec ->
                    appendLine("  · ${rec.name}")
                }
                if (entry.recommendations.size > DETAIL_EPISODE_LIMIT) {
                    appendLine("  … e altre ${entry.recommendations.size - DETAIL_EPISODE_LIMIT}")
                }
            }
            if (entry.type != StreamCenterMediaCache.TYPE_MOVIE && !entry.animeMovie) {
                appendLine("Episodi: ${entry.episodes.size}")
                entry.episodes.take(DETAIL_EPISODE_LIMIT).forEach { ep ->
                    val se = listOfNotNull(ep.season?.let { "S$it" }, ep.episode?.let { "E$it" }).joinToString("")
                    appendLine("  · ${if (se.isNotBlank()) "$se " else ""}${ep.name ?: "Episodio"}")
                }
                if (entry.episodes.size > DETAIL_EPISODE_LIMIT) {
                    appendLine("  … e altri ${entry.episodes.size - DETAIL_EPISODE_LIMIT}")
                }
            }

            appendLine()
            appendLine("Salvata il: ${dateLabel(entry.cachedAtMillis)}")
            appendLine("Scadenza: ${dateLabel(entry.expiresAtMillis)} (${expiryLabel(entry.expiresAtMillis)})")
            append("Dimensione: ${Formatter.formatFileSize(ctx, summary.sizeBytes)}")
        }
    }

    private fun showDeleteCacheConfirmation(summary: CachedMediaSummary, archiveDialog: AlertDialog) {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Elimina cache"))
            .setMessage("Vuoi eliminare la cache di ${summary.title}?")
            .setPositiveButton("Elimina") { _, _ ->
                viewScope?.launch(Dispatchers.IO) {
                    val deleted = StreamCenterMediaCache.deleteMedia(summary.key)
                    withContext(Dispatchers.Main) {
                        saveToast(if (deleted) "Cache di ${summary.title} eliminata" else "Cache non eliminata o già assente")
                        archiveDialog.dismiss()
                        refreshStats()
                        showCacheArchive()
                    }
                }
            }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun showClearAllConfirmation(archiveDialog: AlertDialog) {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Svuota cache"))
            .setMessage("Vuoi eliminare tutte le schede salvate in cache?")
            .setPositiveButton("Svuota") { _, _ ->
                viewScope?.launch(Dispatchers.IO) {
                    StreamCenterMediaCache.clearAll()
                    withContext(Dispatchers.Main) {
                        saveToast("Cache svuotata")
                        archiveDialog.dismiss()
                        refreshStats()
                    }
                }
            }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun archiveSummaryLine(summary: CachedMediaSummary): String {
        val ctx = context
        val parts = buildList {
            add(typeLabel(summary.type, summary.animeMovie))
            when {
                summary.type == StreamCenterMediaCache.TYPE_MOVIE -> Unit
                summary.type == StreamCenterMediaCache.TYPE_ANIME && summary.animeMovie -> Unit
                else -> add("${summary.episodeCount} episodi")
            }
            add(expiryLabel(summary.expiresAtMillis))
            ctx?.let { add(Formatter.formatFileSize(it, summary.sizeBytes)) }
        }
        return parts.joinToString(" · ")
    }

    private fun iconFor(summary: CachedMediaSummary): String = when {
        summary.type == StreamCenterMediaCache.TYPE_ANIME && summary.animeMovie -> "🎥"
        summary.type == StreamCenterMediaCache.TYPE_ANIME -> "🌸"
        summary.type == StreamCenterMediaCache.TYPE_SERIES -> "📺"
        else -> "🎬"
    }

    private fun typeLabel(type: String, animeMovie: Boolean): String = when (type) {
        StreamCenterMediaCache.TYPE_SERIES -> "Serie"
        StreamCenterMediaCache.TYPE_ANIME -> if (animeMovie) "Anime film" else "Anime"
        else -> "Film"
    }

    private fun statusLabel(status: String?): String = when {
        status.equals("Ongoing", ignoreCase = true) -> "In corso"
        status.equals("Completed", ignoreCase = true) -> "Completata"
        else -> "Non specificato"
    }

    private fun dateLabel(millis: Long): String {
        if (millis <= 0L) return "Sconosciuta"
        return SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun expiryLabel(expiresAt: Long): String {
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0L) return "In scadenza"
        val minutes = remaining / (60L * 1000L)
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            days >= 2 -> "Scade tra $days giorni"
            hours >= 1 -> "Scade tra $hours ore"
            else -> "Scade tra ${minutes.coerceAtLeast(1)} min"
        }
    }
}
