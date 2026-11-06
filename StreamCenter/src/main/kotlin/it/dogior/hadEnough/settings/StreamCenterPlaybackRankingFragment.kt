package it.dogior.hadEnough.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import it.dogior.hadEnough.playback.DiscoveryOutcome
import it.dogior.hadEnough.playback.PlaybackInsightSummary
import it.dogior.hadEnough.playback.PlaybackTier
import it.dogior.hadEnough.playback.SourceDiscoverySummary
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
            val (summaries, discoveries) = withContext(Dispatchers.IO) {
                runCatching { StreamCenterPlaybackInsights.summaries() }.getOrDefault(emptyList()) to
                    runCatching { StreamCenterPlaybackInsights.discoverySummaries() }.getOrDefault(emptyList())
            }
            renderRanking(summaries, discoveries)
        }
    }

    private fun renderRanking(summaries: List<PlaybackInsightSummary>, discoveries: List<SourceDiscoverySummary>) {
        val container = rankingContainer ?: return
        container.removeAllViews()
        if (summaries.isEmpty()) {
            container.addView(emptyStateCard("Nessuna riproduzione misurata finora", COLOR_EXPERIMENTAL, "🎯"))
        }
        var position = 0
        summaries.groupBy(PlaybackInsightSummary::tier).toSortedMap(compareBy(PlaybackTier::ordinal)).forEach { (tier, group) ->
            container.addView(sectionHeading(tierHeading(tier)))
            group.forEach { summary ->
                position++
                container.addView(rankingRow(position, summary))
            }
        }
        container.addView(sectionHeading("Ritrovamento · dalla più veloce a trovare i link"))
        if (discoveries.isEmpty()) {
            container.addView(emptyStateCard("Nessuna ricerca di fonti misurata finora", COLOR_EXPERIMENTAL, "⏱️"))
            return
        }
        discoveries.forEachIndexed { index, summary -> container.addView(discoveryRow(index + 1, summary)) }
    }

    private fun discoveryRow(position: Int, summary: SourceDiscoverySummary): View = settingsRow(
        title = summary.sourceName,
        summary = discoverySummaryText(summary),
        accent = COLOR_EXPERIMENTAL,
        leadingView = iconBadge("$position", COLOR_EXPERIMENTAL, size = 36, marginEnd = 12),
    ) { showDiscoveryDetails(position, summary) }.view

    private fun discoverySummaryText(summary: SourceDiscoverySummary): String {
        val speed = summary.medianFirstLinkMs?.let { "primo link in ${seconds(it)}" } ?: "mai trovata"
        return "${summary.group.label} · ${foundText(summary)} · $speed"
    }

    private fun foundText(summary: SourceDiscoverySummary): String = when {
        !summary.isProvider -> "trovata ${summary.found} su ${summary.attempts}"
        summary.found == 1 -> "trovata 1 volta"
        else -> "trovata ${summary.found} volte"
    }

    private fun showDiscoveryDetails(position: Int, summary: SourceDiscoverySummary) {
        val lines = buildList {
            add("Posizione: $position")
            add("Tipo: ${summary.group.label}")
            add(
                if (summary.isProvider) foundText(summary).replaceFirstChar(Char::uppercase)
                else "Trovata: ${summary.found} su ${summary.attempts} ricerche (${(summary.foundRate * 100).toInt()}%)",
            )
            summary.medianFirstLinkMs?.let { add("Primo link, tempo tipico: ${seconds(it)}") }
            summary.fastestFirstLinkMs?.let { add("Primo link, più veloce: ${seconds(it)}") }
            summary.medianDurationMs?.let { add("Durata tipica della ricerca: ${seconds(it)}") }
            if (summary.found > 0) add("Link per ricerca: %.1f".format(Locale.ITALIAN, summary.averageLinks))
            DiscoveryOutcome.entries.filter { it != DiscoveryOutcome.FOUND }.forEach { outcome ->
                summary.outcomes[outcome]?.takeIf { it > 0 }?.let { add("${outcome.label}: $it") }
            }
        }
        val alertDialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("⏱️ ${summary.sourceName}", COLOR_EXPERIMENTAL))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun seconds(ms: Long): String = "%.1f s".format(Locale.ITALIAN, ms / 1000.0)

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
            summary.medianStartupMs?.let { add("Avvio tipico: ${seconds(it)}") }
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
                    if (result.isSuccess) renderRanking(emptyList(), emptyList())
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }
}
