package it.dogior.hadEnough.catalog

import it.dogior.hadEnough.util.parseMetadataRuntime
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

internal object TmdbSeriesMetadata {
    fun matchingSearchIds(cards: Iterable<Element>, titles: List<String>, year: Int?, isMovie: Boolean): List<Int> {
        if (year == null) return emptyList()
        val keys = titleKeys(titles)
        val kind = if (isMovie) "movie" else "tv"
        val path = Regex("/$kind/(\\d+)(?:[-/?#]|$)")
        val ids = cards.mapNotNull { card ->
            val heading = card.selectFirst("h2, h3")?.clone() ?: return@mapNotNull null
            heading.select(".adult").remove()
            if (titleKey(heading.text()) !in keys) return@mapNotNull null
            val date = card.selectFirst("span.subheader, span.release_date, span.date, p")?.text().orEmpty()
            if (Regex("\\b(?:18|19|20|21)\\d{2}\\b").find(date)?.value?.toIntOrNull() != year) return@mapNotNull null
            card.select("a[href]").firstNotNullOfOrNull { anchor ->
                path.find(anchor.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinct()
        return ids
    }

    data class SearchCandidate(val id: Int, val titles: List<String>, val year: Int?)

    fun matchingIds(candidates: Iterable<SearchCandidate>, titles: List<String>, year: Int?): List<Int> {
        if (year == null) return emptyList()
        val keys = titleKeys(titles)
        return candidates
            .filter { candidate -> candidate.year == year && candidate.titles.any { titleKey(it) in keys } }
            .map(SearchCandidate::id)
            .distinct()
    }

    private fun titleKey(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun titleKeys(titles: List<String>) = titles.map(::titleKey).filter(String::isNotBlank).toSet()

    fun episodeRuntime(values: Iterable<Int?>): Int? = values
        .filterNotNull()
        .filter { it > 0 }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    fun seasonRuntime(document: Document): Int? = episodeRuntime(
        document.select(".episode_list .card div.date span.runtime")
            .map { parseMetadataRuntime(it.text()) },
    )
}
