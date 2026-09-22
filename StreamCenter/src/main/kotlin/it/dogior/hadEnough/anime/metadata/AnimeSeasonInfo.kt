package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SeasonData
import it.dogior.hadEnough.model.TmdbAnimeMetadata
import org.jsoup.nodes.Document
import java.util.Locale

internal data class AnimeSeasonInfo(
    val season: Int?,
    val part: Int?,
    val seasonName: String?,
    private val seriesTitles: List<String>,
) {
    fun title(preferredTitle: String): String {
        var title = preferredTitle.trim().takeUnless { it.isBlank() || it in setOf("Anime", "Sconosciuto") }
            ?: seriesTitles.firstOrNull() ?: preferredTitle
        val explicitSeason = seasonNumber(title)
        val explicitPart = partNumber(title)
        title = stripNumberedLabels(title)
        if (season != null && seriesTitles.any { key(title) == "${key(it)} $season" }) {
            title = title.removeSuffix(season.toString()).trim()
        }
        if (seasonName != null && seriesTitles.any { key(it) == key(title) } && key(seasonName) != key(title)) {
            title = if (key(seasonName).startsWith("${key(title)} ")) seasonName else "$title: $seasonName"
        }
        val labels = listOfNotNull(
            (season ?: explicitSeason)?.takeIf { it > 1 || explicitSeason != null || part != null }
                ?.let { "Stagione $it" },
            (part ?: explicitPart)?.let { "Parte $it" },
        )
        return (listOf(title) + labels).joinToString(" - ")
    }

    fun seasons(episodes: List<Episode>): List<SeasonData> = episodes.mapNotNull(Episode::season)
        .distinct().sorted().map { number ->
            SeasonData(
                season = number,
                name = if (number == season) listOfNotNull(seasonName, part?.let { "Parte $it" })
                    .joinToString(" - ").takeIf(String::isNotBlank) else null,
                displaySeason = number.takeIf { it > 0 },
            )
        }

    companion object {
        private val seasonLabel = Regex(
            """\b(?:(?:season|stagione)\s+(\d+)|(\d+)(?:st|nd|rd|th|ª|a)\s+(?:season|stagione))\b""",
            RegexOption.IGNORE_CASE,
        )
        private val partLabel = Regex(
            """\b(?:(?:part|parte|cour)\s+(\d+)|(\d+)(?:st|nd|rd|th|ª|a)\s+(?:part|parte|cour))\b""",
            RegexOption.IGNORE_CASE,
        )
        private fun number(regex: Regex, title: String): Int? = regex.find(title)?.groupValues
            ?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { value -> value > 0 } }

        private fun seasonNumber(title: String): Int? = number(seasonLabel, title)
        private fun partNumber(title: String): Int? = number(partLabel, title)
        private fun key(title: String): String = title.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

        private fun stripNumberedLabels(title: String): String = title
            .replace(seasonLabel, "").replace(partLabel, "")
            .replace(Regex("""\(\s*\)|\[\s*]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '—', ':', ',')

        fun resolve(tmdb: TmdbAnimeMetadata?, preferredTitle: String, titleCandidates: List<String>): AnimeSeasonInfo {
            val titles = listOf(preferredTitle) + titleCandidates
            return AnimeSeasonInfo(
                season = tmdb?.season ?: titles.mapNotNull(::seasonNumber).distinct().singleOrNull(),
                part = partNumber(preferredTitle) ?: titles.mapNotNull(::partNumber).distinct().singleOrNull(),
                seasonName = meaningfulName(tmdb?.seasonName),
                seriesTitles = listOfNotNull(tmdb?.title, tmdb?.englishTitle, tmdb?.originalTitle),
            )
        }

        private fun meaningfulName(raw: String?): String? = raw?.trim()?.takeIf(String::isNotBlank)
            ?.takeUnless {
                Regex("""(?i)(?:season|stagione)\s+\d+|specials|speciali""").matches(it)
            }

        fun tmdbSeasonName(document: Document, tmdbId: Int, season: Int): String? {
            if (!TmdbArtwork.isSeason(document, tmdbId, season)) return null
            val heading = document.selectFirst("section.header h2, .season_wrapper h2")?.clone() ?: return null
            heading.select(".release_date, .tag").remove()
            val title = heading.text().replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()
            return meaningfulName(title)
        }
    }
}
