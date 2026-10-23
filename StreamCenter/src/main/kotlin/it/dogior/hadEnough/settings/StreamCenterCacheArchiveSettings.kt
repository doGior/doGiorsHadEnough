package it.dogior.hadEnough.settings

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import it.dogior.hadEnough.cache.CacheStats
import it.dogior.hadEnough.cache.CachedMediaEntry
import it.dogior.hadEnough.cache.CachedMediaSummary
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DETAIL_EPISODE_LIMIT = 20
private const val HOUR_MS = 60L * 60L * 1000L
private const val DAY_MS = 24L * HOUR_MS

private enum class CacheSortField(
    val label: String,
    val ascendingLabel: String,
    val descendingLabel: String,
    val defaultAscending: Boolean,
) {
    SAVED("Salvataggio", "Meno recenti", "Più recenti", false),
    TITLE("Titolo", "A–Z", "Z–A", true),
    EXPIRY("Scadenza", "Più vicine", "Più lontane", true),
    SIZE("Dimensione", "Più piccole", "Più grandi", false),
    EPISODES("Episodi", "Meno episodi", "Più episodi", false),
}

private data class CacheSortOrder(
    val field: CacheSortField = CacheSortField.SAVED,
    val ascending: Boolean = field.defaultAscending,
) {
    val directionLabel: String
        get() = "${if (ascending) "↑" else "↓"} ${if (ascending) this.field.ascendingLabel else this.field.descendingLabel}"

    fun nextField(): CacheSortOrder =
        CacheSortOrder(CacheSortField.entries[(field.ordinal + 1) % CacheSortField.entries.size])
}

class StreamCenterCacheArchiveSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Schede salvate"

    override val screenIcon: String = "🗂"

    override fun screenAction(): SettingsScreenAction = SettingsScreenAction(
        label = "Svuota",
        description = "Elimina tutte le schede salvate in cache",
        onInvoke = { confirmClearAll() },
    )

    private var summaries: List<CachedMediaSummary>? = null
    private var stats: CacheStats? = null
    private var query = ""
    private var sortOrder = CacheSortOrder()
    private var heroContainer: LinearLayout? = null
    private var listContainer: LinearLayout? = null
    private var fieldButton: TextView? = null
    private var directionButton: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
        content.minimumHeight = standardSubmenuMinimumHeight()
        heroContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }.also(content::addView)
        content.addView(input(query).apply {
            hint = "Cerca una scheda"
            layoutParams = verticalParams(top = 12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    renderList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        })
        content.addView(sortControls())
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }.also(content::addView)
        load()
        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        heroContainer = null
        listContainer = null
        fieldButton = null
        directionButton = null
        super.onDestroyView()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (loadedSummaries, loadedStats) = withContext(Dispatchers.IO) {
                runCatching { StreamCenterMediaCache.listSummaries() }.getOrDefault(emptyList()) to
                    runCatching { StreamCenterMediaCache.stats() }.getOrNull()
            }
            summaries = loadedSummaries
            stats = loadedStats
            renderHero()
            renderList()
        }
    }

    private fun sortControls(): LinearLayout {
        val field = actionButton("Cambia criterio", COLOR_ACCENT) {
            sortOrder = sortOrder.nextField()
            refreshSortLabels()
            renderList()
        }
        val direction = actionButton("Inverti ordine", COLOR_ACCENT) {
            sortOrder = sortOrder.copy(ascending = !sortOrder.ascending)
            refreshSortLabels()
            renderList()
        }
        fieldButton = field
        directionButton = direction
        refreshSortLabels()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 8)
            listOf(field, direction).forEachIndexed { index, button ->
                button.minimumHeight = dp(44)
                button.setPadding(dp(8), dp(10), dp(8), dp(10))
                button.maxLines = 1
                addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 1) marginStart = dp(8)
                })
            }
        }
    }

    private fun refreshSortLabels() {
        fieldButton?.apply {
            text = "↻ ${sortOrder.field.label}"
            contentDescription = "Ordina per ${sortOrder.field.label}. Premi per cambiare criterio."
        }
        directionButton?.apply {
            text = sortOrder.directionLabel
            contentDescription = "${sortOrder.directionLabel}. Premi per invertire l'ordine."
        }
    }

    private fun renderHero() {
        val hero = heroContainer ?: return
        val items = summaries ?: return
        val ctx = context ?: return
        hero.removeAllViews()
        val now = System.currentTimeMillis()
        val expired = items.count { it.expiresAtMillis <= now }
        val expiringSoon = items.count { it.expiresAtMillis in (now + 1)..(now + DAY_MS) }
        val usage = stats?.let {
            "${Formatter.formatShortFileSize(ctx, it.totalBytes)} di ${Formatter.formatShortFileSize(ctx, it.maxBytes)}"
        }
        val summary = when {
            items.isEmpty() && !StreamCenterMediaCache.isEnabled(sharedPref) ->
                "La cache è spenta: attivala per riaprire all'istante le schede già viste"
            items.isEmpty() -> "Le schede che apri verranno salvate qui"
            else -> listOfNotNull(
                usage,
                when (expiringSoon) {
                    0 -> "Nessuna scade entro domani"
                    1 -> "1 scade entro domani"
                    else -> "$expiringSoon scadono entro domani"
                },
            ).joinToString("\n")
        }
        hero.addView(heroCountCard(
            value = items.size.toString(),
            accent = COLOR_ACCENT,
            title = when (items.size) {
                0 -> "Nessuna scheda salvata"
                1 -> "scheda salvata"
                else -> "schede salvate"
            },
            summary = summary,
        ))
        if (expired > 0) {
            hero.addView(settingsRow(
                title = "Elimina le schede scadute",
                summary = if (expired == 1) "1 scheda scaduta" else "$expired schede scadute",
                icon = "🧹",
                accent = COLOR_DANGER,
                trailingViews = listOf(chevron(COLOR_DANGER)),
            ) { deleteExpired() }.view)
        }
    }

    private fun renderList() {
        val list = listContainer ?: return
        val items = summaries ?: return
        list.removeAllViews()
        if (items.isEmpty()) return
        val needle = query.trim().lowercase(Locale.getDefault())
        val filtered = if (needle.isBlank()) items else items.filter { it.title.lowercase(Locale.getDefault()).contains(needle) }
        if (filtered.isEmpty()) {
            list.addView(emptyStateCard("Nessuna scheda trovata per \"$query\"", COLOR_ACCENT, icon = "🔎"))
            return
        }
        val now = System.currentTimeMillis()
        val sorted = sortSummaries(filtered)
        val expired = sorted.filter { it.expiresAtMillis <= now }
        val active = sorted.filter { it.expiresAtMillis > now }
        if (expired.isNotEmpty()) {
            list.addView(sectionHeading("Scadute"))
            addAdaptiveCardGrid(list, expired.map { summaryCard(it, now) })
        }
        if (active.isNotEmpty()) {
            list.addView(sectionHeading("Valide"))
            addAdaptiveCardGrid(list, active.map { summaryCard(it, now) })
        }
    }

    private fun summaryCard(summary: CachedMediaSummary, now: Long): View {
        val expired = summary.expiresAtMillis <= now
        val accent = if (expired) COLOR_DANGER else COLOR_ACCENT
        val lifetime = (summary.expiresAtMillis - summary.cachedAtMillis).coerceAtLeast(1L)
        val remaining = (summary.expiresAtMillis - now).coerceAtLeast(0L)
        val (value, label) = remainingBadge(remaining)
        return mediaCard(
            poster = posterThumb(summary.posterUrl, iconFor(summary), summary.title),
            title = summary.title,
            details = listOf(
                counterText(metaLine(summary), 11).apply { setPadding(0, dp(3), 0, 0) },
                fractionBar(remaining.toFloat() / lifetime, accent),
                counterText(expiryLine(summary, now), 11).apply { setPadding(0, dp(5), 0, 0) },
            ),
            trailing = countBadge(value, label, accent),
            accent = accent,
            description = "${summary.title}. ${metaLine(summary)}. ${expiryLine(summary, now)}",
        ) { showDetail(summary) }
    }

    private fun remainingBadge(remaining: Long): Pair<String, String> {
        val days = remaining / DAY_MS
        val hours = remaining / HOUR_MS
        return when {
            remaining <= 0L -> "!" to "scaduta"
            days >= 1 -> days.toString() to if (days == 1L) "giorno" else "giorni"
            hours >= 1 -> hours.toString() to if (hours == 1L) "ora" else "ore"
            else -> (remaining / 60_000L).coerceAtLeast(1L).toString() to "min"
        }
    }

    private fun metaLine(summary: CachedMediaSummary): String = buildList {
        add(typeLabel(summary.type, summary.animeMovie))
        summary.year?.let { add(it.toString()) }
        if (hasEpisodes(summary)) add(if (summary.episodeCount == 1) "1 episodio" else "${summary.episodeCount} episodi")
    }.joinToString(", ")

    private fun expiryLine(summary: CachedMediaSummary, now: Long): String {
        val size = context?.let { Formatter.formatShortFileSize(it, summary.sizeBytes) }
        val expiry = if (summary.expiresAtMillis <= now) "Scaduta" else "Scade ${expiryDate(summary.expiresAtMillis)}"
        return listOfNotNull(expiry, size).joinToString(", ")
    }

    private fun expiryDate(millis: Long): String {
        val remaining = millis - System.currentTimeMillis()
        return when {
            remaining < DAY_MS -> "alle " + SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(millis))
            else -> "il " + SimpleDateFormat("d MMM", Locale.ITALY).format(Date(millis))
        }
    }

    private fun hasEpisodes(summary: CachedMediaSummary): Boolean =
        summary.type != StreamCenterMediaCache.TYPE_MOVIE && !summary.animeMovie

    private fun sortSummaries(items: List<CachedMediaSummary>): List<CachedMediaSummary> {
        val byTitle = compareBy<CachedMediaSummary> { it.title.lowercase(Locale.ROOT) }.thenBy { it.key }
        val byField = when (sortOrder.field) {
            CacheSortField.SAVED -> compareBy<CachedMediaSummary> { it.cachedAtMillis }
            CacheSortField.TITLE -> byTitle
            CacheSortField.EXPIRY -> compareBy<CachedMediaSummary> { it.expiresAtMillis }
            CacheSortField.SIZE -> compareBy<CachedMediaSummary> { it.sizeBytes }
            CacheSortField.EPISODES -> compareBy<CachedMediaSummary> { it.episodeCount }
        }
        return items.sortedWith((if (sortOrder.ascending) byField else byField.reversed()).then(byTitle))
    }

    private fun showDetail(summary: CachedMediaSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                runCatching { StreamCenterMediaCache.readRaw(summary.key) }.getOrNull()
            }
            val ctx = context ?: return@launch
            val body = bodyText(detailText(ctx, summary, entry), 12).apply {
                setPadding(dp(20), dp(12), dp(20), dp(16))
                setTextIsSelectable(true)
            }
            val dialog = AlertDialog.Builder(ctx)
                .setCustomTitle(dialogTitle(summary.title))
                .setView(ScrollView(ctx).apply { addView(body) })
                .setPositiveButton("Elimina") { _, _ -> delete(summary) }
                .setNegativeButton("Chiudi", null)
                .create()
            applyDialogBackdrop(dialog)
            dialog.show()
        }
    }

    private fun delete(summary: CachedMediaSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { StreamCenterMediaCache.deleteMedia(summary.key) }
            saveToast(if (deleted) "Cache di ${summary.title} eliminata" else "Cache non eliminata o già assente")
            load()
        }
    }

    private fun deleteExpired() {
        viewLifecycleOwner.lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { StreamCenterMediaCache.cleanup() }.getOrDefault(0)
            }
            saveToast(if (removed > 0) "Eliminate $removed schede scadute" else "Nessuna scheda scaduta")
            load()
        }
    }

    private fun confirmClearAll() {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Svuota cache"))
            .setMessage("Vuoi eliminare tutte le schede salvate in cache?")
            .setPositiveButton("Svuota") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { StreamCenterMediaCache.clearAll() }
                    saveToast("Cache svuotata")
                    load()
                }
            }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun detailText(ctx: Context, summary: CachedMediaSummary, entry: CachedMediaEntry?): String {
        if (entry == null) {
            return "Contenuto non leggibile.\n\n" +
                "Salvata il: ${dateLabel(summary.cachedAtMillis)}\n" +
                "Scadenza: ${dateLabel(summary.expiresAtMillis)}\n" +
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
                if (idParts.isNotEmpty()) appendLine("ID: ${idParts.joinToString(", ")}")
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
                    val name = season.name?.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()
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
            appendLine("Scadenza: ${dateLabel(entry.expiresAtMillis)}")
            append("Dimensione: ${Formatter.formatFileSize(ctx, summary.sizeBytes)}")
        }
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
}
