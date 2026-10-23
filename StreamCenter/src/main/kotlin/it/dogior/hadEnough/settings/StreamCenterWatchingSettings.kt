package it.dogior.hadEnough.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.TvType
import it.dogior.hadEnough.catalog.CatchUpEpisode
import it.dogior.hadEnough.catalog.StreamCenterCatchUp
import it.dogior.hadEnough.catalog.WatchingEpisodeState
import it.dogior.hadEnough.catalog.WatchingSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private class WatchingEpisodeStrip(
    context: Context,
    private val states: List<WatchingEpisodeState>,
    private val watchedColor: Int,
    private val availableColor: Int,
    private val upcomingColor: Int,
    private val gap: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        if (states.isEmpty() || width == 0) return
        val radius = height / 2f
        if (states.size > MAX_SEGMENTS) {
            drawContinuous(canvas, radius)
            return
        }
        val segment = (width - gap * (states.size - 1)) / states.size
        states.forEachIndexed { index, state ->
            val left = index * (segment + gap)
            rect.set(left, 0f, left + segment, height.toFloat())
            paint.color = colorFor(state)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun drawContinuous(canvas: Canvas, radius: Float) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = upcomingColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        val available = states.count { it != WatchingEpisodeState.UPCOMING }
        val watched = states.count { it == WatchingEpisodeState.WATCHED }
        rect.right = width * available / states.size.toFloat()
        paint.color = availableColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        rect.right = width * watched / states.size.toFloat()
        paint.color = watchedColor
        if (watched > 0) canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun colorFor(state: WatchingEpisodeState): Int = when (state) {
        WatchingEpisodeState.WATCHED -> watchedColor
        WatchingEpisodeState.AVAILABLE -> availableColor
        WatchingEpisodeState.UPCOMING -> upcomingColor
    }

    private companion object {
        const val MAX_SEGMENTS = 40
    }
}

class StreamCenterWatchingSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Guardando"

    override val screenIcon: String = "▶️"

    override fun screenAction(): SettingsScreenAction = SettingsScreenAction(
        label = "Aggiorna",
        description = "Ricarica gli episodi usciti di tutte le serie che stai guardando",
        onInvoke = { refresh(force = true) },
    )

    private var heroContainer: LinearLayout? = null
    private var listContainer: LinearLayout? = null
    private var loadJob: Job? = null
    private var refreshing = false

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
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }.also(content::addView)
        render(null)
        refresh(force = false)
        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        heroContainer = null
        listContainer = null
        super.onDestroyView()
    }

    private fun refresh(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            render(withContext(Dispatchers.IO) { StreamCenterCatchUp.watching() })
            val job = withContext(Dispatchers.IO) { StreamCenterCatchUp.refreshWatching(force) } ?: return@launch
            refreshing = true
            render(withContext(Dispatchers.IO) { StreamCenterCatchUp.watching() })
            job.join()
            refreshing = false
            render(withContext(Dispatchers.IO) { StreamCenterCatchUp.watching() })
        }
    }

    private fun render(series: List<WatchingSeries>?) {
        val hero = heroContainer ?: return
        val list = listContainer ?: return
        hero.removeAllViews()
        list.removeAllViews()
        if (series == null) return
        hero.addView(heroCard(series))
        if (series.isEmpty()) {
            list.addView(emptyStateCard(
                "Segna una serie come \"Guardando\" in CloudStream e la ritrovi qui con gli episodi da recuperare",
                COLOR_ACCENT,
                icon = "📺",
            ).apply { wrapLastText() })
            return
        }
        val behind = series.filter { it.known && it.missing > 0 }
        val upToDate = series.filter { it.known && it.missing == 0 }
            .sortedWith(compareBy(nullsLast()) { it.nextAirDate })
        val unknown = series.filterNot { it.known }
        if (behind.isNotEmpty()) {
            list.addView(sectionHeading("Da recuperare"))
            addAdaptiveCardGrid(list, behind.map(::seriesCard))
        }
        if (upToDate.isNotEmpty()) {
            list.addView(sectionHeading("In pari"))
            addAdaptiveCardGrid(list, upToDate.map(::seriesCard))
        }
        if (unknown.isNotEmpty()) {
            list.addView(sectionHeading(if (refreshing) "Sto contando gli episodi" else "Episodi non ancora letti"))
            addAdaptiveCardGrid(list, unknown.map(::seriesCard))
        }
    }

    private fun heroCard(series: List<WatchingSeries>): View {
        val known = series.filter { it.known }
        val missing = known.sumOf { it.missing }
        val behind = known.count { it.missing > 0 }
        val caughtUp = series.isNotEmpty() && known.size == series.size && missing == 0
        val counting = !caughtUp && missing == 0 && known.size < series.size
        val value = when {
            caughtUp -> "✓"
            counting -> "…"
            else -> missing.toString()
        }
        return heroCountCard(
            value = value,
            accent = if (caughtUp) COLOR_SUCCESS else COLOR_ACCENT,
            title = heroTitle(series, missing, caughtUp, counting),
            summary = heroSummary(series, behind, known.size),
        )
    }

    private fun heroTitle(series: List<WatchingSeries>, missing: Int, caughtUp: Boolean, counting: Boolean): String = when {
        series.isEmpty() -> "Nessuna serie in corso"
        caughtUp -> "Sei in pari con tutto"
        counting -> "Episodi ancora da contare"
        missing == 1 -> "episodio da recuperare"
        else -> "episodi da recuperare"
    }

    private fun heroSummary(series: List<WatchingSeries>, behind: Int, known: Int): String {
        val total = if (series.size == 1) "1 serie in \"Guardando\"" else "${series.size} serie in \"Guardando\""
        val detail = when {
            series.isEmpty() -> "Qui compaiono le serie con l'etichetta \"Guardando\" di CloudStream"
            known < series.size && refreshing -> "Sto controllando gli episodi usciti"
            known < series.size -> "Tocca Aggiorna per leggere gli episodi mancanti"
            behind == 0 -> "Nessun episodio arretrato"
            behind == 1 -> "1 serie da recuperare"
            else -> "$behind serie da recuperare"
        }
        return if (series.isEmpty()) detail else "$total\n$detail"
    }

    private fun seriesCard(item: WatchingSeries): View {
        val ctx = requireContext()
        val behind = item.known && item.missing > 0
        val accent = if (item.known && !behind) COLOR_SUCCESS else COLOR_ACCENT
        val details = buildList {
            add(counterText(statusLine(item), 11).apply { setPadding(0, dp(3), 0, 0) })
            if (item.known && item.seasonEpisodes.isNotEmpty()) {
                add(WatchingEpisodeStrip(
                    context = ctx,
                    states = item.seasonEpisodes,
                    watchedColor = Color.parseColor(accent),
                    availableColor = Color.parseColor(tint(accent, "4D")),
                    upcomingColor = Color.parseColor(tint(COLOR_MUTED, "26")),
                    gap = dp(2).toFloat(),
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                        topMargin = dp(10)
                    }
                })
                add(counterText(seasonLine(item), 11).apply { setPadding(0, dp(5), 0, 0) })
            }
        }
        val (value, label) = when {
            !item.known -> "…" to if (refreshing) "conto" else "da leggere"
            item.missing == 0 -> "✓" to "in pari"
            else -> item.missing.toString() to "da vedere"
        }
        return mediaCard(
            poster = posterThumb(item.card.posterUrl, if (item.card.type == TvType.Anime) "🌸" else "📺", item.card.name),
            title = item.card.name,
            details = details,
            trailing = countBadge(value, label, if (item.known) accent else COLOR_MUTED, emphasized = item.known),
            accent = accent,
            description = listOfNotNull(item.card.name, statusLine(item), countDescription(item)).joinToString(". "),
        ) { openSeries(item) }
    }

    private fun statusLine(item: WatchingSeries): String = when {
        !item.known -> if (refreshing) "Sto leggendo gli episodi usciti" else "Episodi non ancora letti"
        item.inProgress != null -> "Stai guardando ${episodeLabel(item.inProgress)}"
        item.lastWatched != null -> "Visto fino a ${episodeLabel(item.lastWatched)}"
        else -> "Non hai ancora visto episodi"
    }

    private fun seasonLine(item: WatchingSeries): String {
        val watched = item.seasonEpisodes.count { it == WatchingEpisodeState.WATCHED }
        val total = item.seasonEpisodes.size
        val scope = item.season?.let { "Stagione $it" } ?: "Episodi"
        val progress = "$scope: $watched di $total visti"
        val next = item.nextAirDate?.let { "prossimo ${airDate(it)}" }
        return listOfNotNull(progress, next).joinToString(", ")
    }

    private fun countDescription(item: WatchingSeries): String? = when {
        !item.known -> null
        item.missing == 0 -> "In pari"
        item.missing == 1 -> "1 episodio da vedere"
        else -> "${item.missing} episodi da vedere"
    }

    private fun episodeLabel(episode: CatchUpEpisode): String {
        val number = episode.episode?.let { "E$it" } ?: "l'ultimo episodio"
        return episode.season?.let { "S$it $number" } ?: number
    }

    private fun airDate(millis: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        val days = ((startOfDay(target) - startOfDay(today)) / DAY_MS).toInt()
        return when (days) {
            0 -> "oggi"
            1 -> "domani"
            in 2..6 -> SimpleDateFormat("EEEE", Locale.ITALY).format(Date(millis))
            else -> "il " + SimpleDateFormat("d MMM", Locale.ITALY).format(Date(millis))
        }
    }

    private fun startOfDay(calendar: Calendar): Long = (calendar.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun openSeries(item: WatchingSeries) = openSearchResult(item.card)

    private fun LinearLayout.wrapLastText() {
        (getChildAt(childCount - 1) as? TextView)?.apply {
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
