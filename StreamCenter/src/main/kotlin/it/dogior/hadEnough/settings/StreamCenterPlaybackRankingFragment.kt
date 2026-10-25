package it.dogior.hadEnough.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import it.dogior.hadEnough.playback.PlaybackInsightSummary
import it.dogior.hadEnough.playback.PlaybackTier
import it.dogior.hadEnough.playback.StreamCenterPlaybackInsights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class StreamCenterPlaybackRankingFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Classifica fonti"

    override val screenIcon: String = "🧪"

    override val screenAccent: String get() = COLOR_EXPERIMENTAL

    override fun screenAction(): SettingsScreenAction = SettingsScreenAction(
        label = "Cancella",
        description = "Cancella la classifica delle fonti",
        onInvoke = { confirmClear() },
    )

    private var rankingContainer: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            minimumHeight = standardSubmenuMinimumHeight()
        }
        content.addView(experimentalBanner())
        rankingContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 2)
        }.also(content::addView)
        loadRanking()
        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        rankingContainer = null
        super.onDestroyView()
    }

    private fun loadRanking() {
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) {
                runCatching { StreamCenterPlaybackInsights.summaries() }.getOrDefault(emptyList())
            }
            renderRanking(summaries)
        }
    }

    private fun renderRanking(summaries: List<PlaybackInsightSummary>) {
        val container = rankingContainer ?: return
        container.removeAllViews()
        if (summaries.isEmpty()) {
            container.addView(emptyStateCard("Nessuna riproduzione misurata finora", COLOR_EXPERIMENTAL, "🎯"))
            return
        }
        var position = 0
        summaries.groupBy(PlaybackInsightSummary::tier).toSortedMap(compareBy(PlaybackTier::ordinal)).forEach { (tier, group) ->
            container.addView(sectionHeading(tierHeading(tier)))
            group.forEach { summary ->
                position++
                container.addView(rankingRow(position, summary))
            }
        }
    }

    private fun tierHeading(tier: PlaybackTier): String = when (tier) {
        PlaybackTier.RELIABLE -> "Affidabili · partono subito"
        PlaybackTier.NORMAL -> "Da valutare"
        PlaybackTier.POOR -> "Poco affidabili · in fondo all'elenco"
    }

    private fun rankingRow(position: Int, summary: PlaybackInsightSummary): View = settingsRow(
        title = summary.sourceName,
        summary = rowSummary(summary),
        accent = COLOR_EXPERIMENTAL,
        leadingView = iconBadge("$position", COLOR_EXPERIMENTAL, size = 36, marginEnd = 12),
    ) { showDetails(position, summary) }.view

    private fun rowSummary(summary: PlaybackInsightSummary): String {
        val rating = summary.score?.let { "$it punti" }
            ?: "in prova, mancano ${summary.missingSamples} riproduzioni"
        return "${summary.host} · $rating"
    }

    private fun showDetails(position: Int, summary: PlaybackInsightSummary) {
        val lines = buildList {
            add("Posizione: $position")
            add("Host: ${summary.host}")
            add("Fascia: ${summary.tier.title}")
            add("Punteggio: ${summary.score?.let { "$it su 100" } ?: "non ancora calcolato"}")
            add("Partite: ${summary.started} su ${summary.attempts} delle ultime prove")
            summary.medianStartupMs?.let { add("Avvio tipico: %.1f s".format(Locale.ITALIAN, it / 1000.0)) }
            add("Blocchi: %.1f a visione".format(Locale.ITALIAN, summary.stallsPerPlay))
            summary.height?.let { add("Qualità reale: ${it}p") }
        }
        val alertDialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("🧪 ${summary.sourceName}", COLOR_EXPERIMENTAL))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun confirmClear() {
        val alertDialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Cancellare la classifica?"))
            .setMessage("StreamCenter ricomincerà a imparare da zero quali fonti funzionano meglio.")
            .setPositiveButton("Cancella") { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { StreamCenterPlaybackInsights.clear() } }
                    saveToast(if (result.isSuccess) "Classifica cancellata" else "Impossibile cancellare la classifica")
                    if (result.isSuccess) renderRanking(emptyList())
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }
}
