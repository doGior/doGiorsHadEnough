package it.dogior.hadEnough.anime.metadata

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.model.StreamCenterPlaybackData
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.DataStoreHelper

private fun seasonLabels(releases: List<AnimeSeriesEntry>, metadata: Map<Int, List<Episode>>): List<String> {
    val romans = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII")
    val romanSeason = Regex("""\b(XIII|XII|XI|IX|VIII|VII|VI|IV|III|II|X|V)\b(?=\s*(?:[:\-]|$))""")
    var current = 0
    val coordinates = releases.map { release ->
        val parsed = AnimeSeasonInfo.resolve(null, release.title, emptyList())
        val explicit = parsed.season ?: romanSeason.find(release.title)?.value?.let { romans.indexOf(it) + 1 }
            ?: metadata[release.id].orEmpty().mapNotNull { it.season?.takeIf { season -> season > 0 } }.distinct().singleOrNull()
        current = explicit ?: if (parsed.part != null && parsed.part > 1) current.coerceAtLeast(1) else current + 1
        current to parsed.part
    }
    val totals = coordinates.groupingBy { it.first }.eachCount()
    val parts = mutableMapOf<Int, Int>()
    return coordinates.map { (season, explicitPart) ->
        val part = explicitPart ?: ((parts[season] ?: 0) + 1)
        parts[season] = part
        "Stagione $season" + if (totals.getValue(season) > 1 || explicitPart != null) " Parte $part" else ""
    }
}

internal object AnimeSeasonRoutes {
    private const val PREFIX = "https://streamcenter.invalid/anime-seasons/v1/"
    fun isGrouped(url: String) = url.startsWith(PREFIX)
    fun wrap(url: String): String = if (isGrouped(url)) url else PREFIX +
        Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun unwrap(url: String): String? = if (!isGrouped(url)) null else runCatching {
        String(Base64.decode(url.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            .takeIf { it.startsWith("https://") && !isGrouped(it) }
    }.getOrNull()

    fun card(card: SearchResponse, enabled: Boolean): SearchResponse {
        if (card.type !in setOf(TvType.Anime, TvType.OVA)) return card
        val url = if (enabled) wrap(card.url) else unwrap(card.url) ?: card.url
        return when (card) {
            is AnimeSearchResponse -> card.copy(url = url)
            is TvSeriesSearchResponse -> card.copy(url = url)
            else -> card
        }
    }
}

internal data class AnimeSeasonPlayback(
    val anilistId: Int,
    val malId: Int? = null,
    val episode: Int,
    val episodeCount: Int,
)

internal class DisabledAnimeTracking : AbstractMutableMap<String, String>() {
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>> get() = mutableSetOf()
    override fun put(key: String, value: String): String? = null
}

internal fun animeReleaseEpisodes(response: AnimeLoadResponse): List<Episode> =
    response.episodes[DubStatus.Subbed]?.takeIf { it.isNotEmpty() }
        ?: response.episodes.values.firstOrNull { it.isNotEmpty() }.orEmpty()

internal fun selectAnimeSeasonEpisode(response: AnimeLoadResponse, target: AnimeSeasonPlayback): Episode? {
    return numberedAnimeReleaseEpisodes(response, target.episodeCount).firstOrNull { it.episode == target.episode }
}

private fun numberedAnimeReleaseEpisodes(response: AnimeLoadResponse, count: Int?): List<Episode> {
    val episodes = animeReleaseEpisodes(response).filter { (it.episode ?: 0) > 0 }.sortedBy { it.episode }
    if (count == null || count <= 0) return episodes
    if (episodes.any { it.episode == 1 } || episodes.all { it.episode!! <= count }) {
        return episodes.filter { it.episode!! <= count }
    }
    val numbered = episodes.distinctBy { it.episode }.take(count)
    if (numbered.size != count || numbered.zipWithNext().any { (a, b) -> b.episode != a.episode!! + 1 }) return emptyList()
    return numbered.mapIndexed { index, episode -> episode.copy(episode = index + 1) }
}

internal suspend fun MainAPI.groupAnimeSeasons(
    selected: AnimeLoadResponse,
    selectedAnilistId: Int?,
    entries: List<AnimeSeriesEntry>,
    groupedUrl: String,
    relatedEpisodes: Map<Int, List<Episode>> = emptyMap(),
): TvSeriesLoadResponse {
    val releases = entries.filter { it.isSeries }
    val labels = seasonLabels(releases, relatedEpisodes)
    val seed = animeReleaseEpisodes(selected)
    val episodes = mutableListOf<Episode>()
    val seasons = mutableListOf<SeasonData>()
    var includedSelected = false
    var initialSeason: Int? = null
    releases.forEachIndexed { index, release ->
        val number = index + 1
        val isSelected = release.id == selectedAnilistId
        val available = if (isSelected) {
            val metadata = relatedEpisodes[release.id].orEmpty().associateBy { it.episode }
            val native = numberedAnimeReleaseEpisodes(selected, release.episodeCount).associateBy { it.episode }
            (native.keys + metadata.keys).filterNotNull().sorted().map { episodeNumber ->
                val episode = native[episodeNumber] ?: metadata.getValue(episodeNumber)
                val details = metadata[episodeNumber]
                episode.copy(
                    season = number,
                    name = episode.name?.takeUnless { it.matches(Regex("Episodio \\d+")) } ?: details?.name ?: episode.name,
                    posterUrl = details?.posterUrl ?: episode.posterUrl,
                    description = episode.description ?: details?.description,
                    score = episode.score ?: details?.score,
                    runTime = episode.runTime ?: details?.runTime,
                    date = episode.date ?: details?.date,
                )
            }
        } else if (!relatedEpisodes[release.id].isNullOrEmpty()) {
            relatedEpisodes.getValue(release.id).map { it.copy(season = number) }
        } else {
            (1..release.availableEpisodes).map { episode ->
                newEpisode(StreamCenterPlaybackData(animeSeason = AnimeSeasonPlayback(
                    release.id, release.malId, episode, release.episodeCount ?: release.availableEpisodes,
                )).toJson()) {
                    this.name = "Episodio $episode"
                    this.season = number
                    this.episode = episode
                    this.posterUrl = release.poster
                }
            }
        }
        if (available.isEmpty()) return@forEachIndexed
        if (isSelected) { includedSelected = true; initialSeason = number }
        episodes += available
        seasons += SeasonData(number, labels[index], null)
        if (isSelected) {
            val extras = seed.filter { (it.episode ?: 0) <= 0 }
            if (extras.isNotEmpty()) {
                val extraSeason = 10_000 + number
                episodes += extras.mapIndexed { index, episode -> episode.copy(season = extraSeason, episode = index + 1) }
                seasons += SeasonData(extraSeason, "${labels[index]} Extra", null)
            }
        }
    }
    if (!includedSelected && seed.isNotEmpty()) {
        val number = releases.size + 1
        episodes += seed.map { it.copy(season = number) }
        seasons += SeasonData(number, "Stagione ${AnimeSeasonInfo.resolve(null, selected.name, emptyList()).season ?: 1}", null)
        initialSeason = number
    }
    val response = newTvSeriesLoadResponse(selected.name, groupedUrl, TvType.Anime, episodes) {
        uniqueUrl = if (releases.isNotEmpty()) {
            "https://streamcenter.invalid/anime-series/v1/" + releases.joinToString("-") { it.id.toString() } +
                "/entry-${selectedAnilistId ?: groupedUrl.hashCode()}"
        } else "$groupedUrl#single"
        posterUrl = selected.posterUrl
        backgroundPosterUrl = selected.backgroundPosterUrl
        logoUrl = selected.logoUrl
        plot = listOfNotNull(
            "Stagioni collegate non disponibili: è mostrata solo l'uscita selezionata.".takeIf { entries.isEmpty() },
            selected.plot).joinToString("\n\n")
        tags = selected.tags.orEmpty().filterNot {
            Regex("^(MAL|AniList|Kitsu|Simkl|IMDb|TMDB) ").containsMatchIn(it)
        }
        year = selected.year
        duration = selected.duration
        score = selected.score
        actors = selected.actors
        trailers = selected.trailers.toMutableList()
        recommendations = selected.recommendations
        contentRating = selected.contentRating
        posterHeaders = selected.posterHeaders
        comingSoon = episodes.isEmpty()
        seasonNames = seasons
        syncData = DisabledAnimeTracking()
    }
    initialSeason?.let { DataStoreHelper.setResultSeason(response.getId(), it) }
    return response
}
