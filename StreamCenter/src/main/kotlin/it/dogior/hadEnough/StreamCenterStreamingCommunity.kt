package it.dogior.hadEnough

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.StreamCenter.Companion.SC_SEARCH_PAGE_SIZE
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun StreamCenter.fetchStreamingCommunityHome(
    data: String,
    limit: Int,
    showScore: Boolean,
): List<SearchResponse> {
    ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
    val parts = data.split(":")
    val type = parts.getOrNull(1) ?: "tv"
    val sliderName = parts.getOrNull(2) ?: "trending"
    val pageUrl = when {
        type == "tv" && sliderName == "upcoming" -> streamingCommunityMainUrl
        type == "tv" -> "$streamingCommunityMainUrl/tv-shows"
        type == "movie" -> "$streamingCommunityMainUrl/movies"
        else -> streamingCommunityMainUrl
    }

    val props = streamingCommunityClient.fetchPageProps(pageUrl) ?: return emptyList()
    val sliders = props.optJSONArray("sliders") ?: return emptyList()
    val titles = (0 until sliders.length())
        .asSequence()
        .mapNotNull { sliders.optJSONObject(it) }
        .firstOrNull { it.optNullableString("name").equals(sliderName, ignoreCase = true) }
        ?.optJSONArray("titles")
        ?: return emptyList()

    return titles.toStreamingCommunityHomeResponses(type, streamingCommunityClient.cdnUrl(props), limit, showScore)
}

internal suspend fun StreamCenter.fetchStreamingCommunityArchiveHome(
    data: String,
    limit: Int,
    showScore: Boolean,
): List<SearchResponse> {
    ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
    val parts = data.split(":")
    val type = parts.getOrNull(2) ?: "movie"
    val genreId = parts.getOrNull(3)?.toIntOrNull() ?: return emptyList()
    val pageUrl = "$streamingCommunityMainUrl/archive?type=$type&genre%5B%5D=$genreId&sort=views"

    val props = streamingCommunityClient.fetchPageProps(pageUrl) ?: return emptyList()
    val titles = props.optJSONArray("titles") ?: return emptyList()
    return titles.toStreamingCommunityHomeResponses(type, streamingCommunityClient.cdnUrl(props), limit, showScore)
}

internal suspend fun StreamCenter.fetchStreamingCommunityTvArchiveHome(
    filters: StreamCenterTvArchiveFilters,
    page: Int,
    limit: Int,
    showScore: Boolean,
): List<SearchResponse> {
    return fetchStreamingCommunityArchiveByType(filters, "tv", page, limit, showScore)
}

internal suspend fun StreamCenter.fetchStreamingCommunityMovieArchiveHome(
    filters: StreamCenterMovieArchiveFilters,
    page: Int,
    limit: Int,
    showScore: Boolean,
): List<SearchResponse> {
    return fetchStreamingCommunityArchiveByType(filters, "movie", page, limit, showScore)
}

internal suspend fun StreamCenter.fetchStreamingCommunityArchiveByType(
    filters: StreamCenterTvArchiveFilters,
    type: String,
    page: Int,
    limit: Int,
    showScore: Boolean,
): List<SearchResponse> {
    ensureUpdatedSourceDomain(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
    val query = buildList {
        add("type=$type")
        filters.genreId?.let { add("genre%5B%5D=$it") }
        filters.year?.let { add("year=$it") }
        filters.minimumScore?.let { add("score=$it") }
        filters.countryId?.let { add("country%5B%5D=$it") }
        filters.service?.let { add("service=$it") }
        add("sort=${filters.sort ?: "release_date"}")
        if (page > 1) add("page=$page")
    }.joinToString("&")
    val props = streamingCommunityClient.fetchPageProps("$streamingCommunityMainUrl/archive?$query")
        ?: return emptyList()
    val titles = props.optJSONArray("titles") ?: return emptyList()
    return titles.toStreamingCommunityHomeResponses(
        type = type,
        cdnUrl = streamingCommunityClient.cdnUrl(props),
        limit = limit,
        showScore = showScore,
    )
}

internal suspend fun StreamCenter.fetchStreamingCommunityRecommendations(
    title: StreamingCommunityTitle,
    limit: Int = 20,
): List<SearchResponse> {
    return runCatching {
        streamingCommunityClient.fetchRelatedTitles(title, limit)
            .mapNotNull(this@fetchStreamingCommunityRecommendations::streamingCommunityRelatedResponse)
            .distinctBy { it.url }
            .take(limit)
    }.getOrDefault(emptyList())
}

internal fun StreamCenter.streamingCommunityRelatedResponse(title: StreamingCommunityTitle): SearchResponse? {
    val url = "$mainUrl$scHomePath${title.id}-${title.slug}?$scHomeTypeParam=${title.type}"
    val poster = streamingCommunityClient.imageUrl(title.posterFilename)
    val scoreValue = title.score
    return when (title.type) {
        "tv" -> newTvSeriesSearchResponse(title.name, url, TvType.TvSeries) {
            this.posterUrl = poster
            if (showCardScores) scoreValue?.let { this.score = Score.from(it, 10) }
        }
        "movie" -> newMovieSearchResponse(title.name, url, TvType.Movie) {
            this.posterUrl = poster
            if (showCardScores) scoreValue?.let { this.score = Score.from(it, 10) }
        }
        else -> null
    }
}

internal data class ScHomeCard(
    val name: String,
    val url: String,
    val poster: String?,
    val scScore: String?,
)

internal fun JSONArray.streamingCommunityPosterFilename(): String? {
    var fallback: String? = null
    for (index in 0 until length()) {
        val image = optJSONObject(index) ?: continue
        val filename = image.optNullableString("filename") ?: continue
        if (image.optNullableString("type") == "poster") return filename
        if (fallback == null) fallback = filename
    }
    return fallback
}

internal suspend fun StreamCenter.searchStreamingCommunityWithTypoFallback(
    query: String,
    page: Int,
    includeFallbacks: Boolean = true,
): Pair<List<SearchResponse>, Boolean> {
    return searchWithTypoFallback(
        query = query,
        page = page,
        search = this@searchStreamingCommunityWithTypoFallback::searchStreamingCommunity,
        includeFallbacks = includeFallbacks,
    )
}

internal suspend fun StreamCenter.searchStreamingCommunity(
    query: String,
    page: Int,
): Pair<List<SearchResponse>, Boolean> {
    val empty = emptyList<SearchResponse>() to false
    val props = streamingCommunityClient.fetchSearchPage(query, page) ?: return empty
    val cdnUrl = streamingCommunityClient.cdnUrl(props)
    val titles = props.optJSONArray("titles") ?: return empty

    val seen = mutableSetOf<String>()
    val items = buildList {
        for (index in 0 until titles.length()) {
            val title = titles.optJSONObject(index) ?: continue
            val type = title.optNullableString("type") ?: "tv"
            val response = title.toStreamingCommunityHomeResponse(type, cdnUrl, showScore = true)
                ?: continue
            if (seen.add(response.url)) {
                registerSearchTitleAliases(response.url, title.streamingCommunitySearchTitleAliases())
                add(response)
            }
        }
    }
    return items to (titles.length() >= SC_SEARCH_PAGE_SIZE)
}

internal fun JSONObject.streamingCommunitySearchTitleAliases(): List<String> = buildList {
    listOf("name", "original_name", "original_title", "title", "title_original")
        .mapNotNull(::optNullableString)
        .forEach(::add)
    val translations = optJSONArray("translations") ?: return@buildList
    for (index in 0 until translations.length()) {
        val translation = translations.optJSONObject(index) ?: continue
        if (!translation.optNullableString("key").equals("name", ignoreCase = true)) continue
        translation.optNullableString("value")?.let(::add)
    }
}

internal suspend fun StreamCenter.loadStreamingCommunityHomeTitle(url: String): LoadResponse {
    val type = parseQueryParams(url)[scHomeTypeParam] ?: "tv"
    val idSlug = url.substringAfter(scHomePath).substringBefore("?")
    val id = idSlug.substringBefore("-").toIntOrNull()
        ?: error("StreamingCommunity: id non valido")
    val slug = idSlug.substringAfter("-", "")
    val scPointerKey = "$type-$id"
    val cacheEnabled = StreamCenterMediaCache.isEnabled(sharedPref)
    if (cacheEnabled) {
        StreamCenterMediaCache.resolveScPointer(scPointerKey)?.let { mediaKey ->
            StreamCenterMediaCache.readByKey(mediaKey)?.let { cached ->
                StreamCenterLogger.logTab(
                    tabName = cached.title,
                    action = "Scheda servita dalla cache",
                    metadata = mapOf(
                        "chiave" to cached.key,
                        "sc" to scPointerKey,
                        "episodi" to cached.episodes.size,
                        "senza_richiesta_sc" to true,
                    ),
                )
                return rebuildTmdbMediaFromCache(cached)
            }
        }
    }
    val baseTitle = StreamingCommunityTitle(
        id = id,
        slug = slug,
        name = slug,
        type = type,
        tmdbId = null,
        year = null,
        seasons = emptyList(),
    )
    val detail = runCatching { streamingCommunityClient.fetchTitleDetail(baseTitle) }
        .getOrNull()
        ?: baseTitle
    detail.tmdbId?.let { tmdbId ->
        val tmdbPath = if (type == "tv") "tv" else "movie"
        val tmdbUrl = "$mainUrl/$tmdbPath/$tmdbId"
        runCatching { loadTmdbMedia(tmdbUrl, scHint = detail, cacheable = true) }
            .getOrNull()
            ?.let { response ->
                if (cacheEnabled) {
                    StreamCenterMediaCache.writeScPointer(scPointerKey, StreamCenterMediaCache.keyFor(tmdbUrl))
                }
                return response
            }
    }
    return loadStreamingCommunityOnly(detail)
}

internal fun StreamCenter.buildStreamingCommunityEpisodes(
    payloads: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
    fallbackPoster: String?,
    stremioContext: StreamCenterStremioPlaybackContext? = null,
    torrentContext: StreamCenterTorrentPlaybackContext? = null,
    episodeMetadata: Map<Pair<Int, Int>, StreamingCommunityEpisode> = emptyMap(),
    minimalMetadata: Boolean = false,
): List<Episode> {
    return payloads.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { (seasonEpisode, playback) ->
            val metadata = episodeMetadata[seasonEpisode]
            newEpisode(
                StreamCenterPlaybackData(
                    streamingCommunity = playback,
                    stremio = stremioContext?.copy(
                        season = seasonEpisode.first,
                        episode = seasonEpisode.second,
                    ),
                    torrent = torrentContext?.forEpisode(
                        seasonEpisode.first,
                        seasonEpisode.second,
                    ),
                ).toJson(),
            ) {
                season = seasonEpisode.first
                episode = seasonEpisode.second
                name = if (minimalMetadata) {
                    "Episodio ${seasonEpisode.second}"
                } else {
                    metadata?.name ?: "Episodio ${seasonEpisode.second}"
                }
                metadata?.airDate?.let { addDate(it) }
                if (!minimalMetadata) {
                    posterUrl = streamingCommunityClient.imageUrl(metadata?.posterFilename) ?: fallbackPoster
                    description = metadata?.plot
                    runTime = metadata?.runtime
                    score = metadata?.score?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
                        ?.let { Score.from(it.toString(), 10) }
                }
            }
        }
}

internal suspend fun StreamCenter.loadStreamingCommunityOnly(title: StreamingCommunityTitle): LoadResponse {
    val sourceUrl = "$mainUrl$scHomePath${title.id}-${title.slug}?$scHomeTypeParam=${title.type}"
    val poster = streamingCommunityClient.imageUrl(title.posterFilename)
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = if (title.type == "tv") listOf("series") else listOf("movie"),
        imdbId = title.imdbId,
        tmdbId = title.tmdbId?.toString(),
    )
    val tmdbEnglishTitle = resolveTmdbEnglishTitle(
        tmdbId = title.tmdbId?.toString(),
        isMovie = title.type != "tv",
    )
    val torrentContext = torrentPlaybackContext(
        titles = listOf(title.name),
        englishTitle = tmdbEnglishTitle,
        year = title.year,
        isAnime = false,
        isMovie = title.type != "tv",
        imdbId = title.imdbId,
    )
    val resolvedSimklId = resolveSimklId(
        imdb = title.imdbId,
        tmdb = title.tmdbId?.toString(),
        allowedCategories = if (title.type == "tv") setOf("tv") else setOf("movies"),
    )
    val recommendations = if (!performanceMode) {
        fetchStreamingCommunityRecommendations(title)
    } else {
        emptyList()
    }
    val response = if (title.type == "tv") {
        val episodes = buildStreamingCommunityEpisodes(
            streamingCommunityClient.episodePayloads(title),
            poster.takeIf { !performanceMode },
            stremioContext,
            torrentContext,
        )
        newTvSeriesLoadResponse(title.name, sourceUrl, TvType.TvSeries, episodes) {
            if (!performanceMode) {
                this.posterUrl = poster
                this.year = title.year
                this.backgroundPosterUrl = streamingCommunityClient.imageUrl(title.backgroundFilename)
                this.logoUrl = streamingCommunityClient.imageUrl(title.logoFilename)
                this.plot = title.plot
                this.tags = title.genres
                this.showStatus = streamingCommunityClient.showStatus(title.status)
                this.contentRating = title.age?.let { "$it+" }
                this.recommendations = recommendations
            }
            addStreamCenterTrackingIds(
                StreamCenterTrackingIds(
                    tmdb = title.tmdbId?.toString(),
                    imdb = title.imdbId,
                    simkl = resolvedSimklId,
                ),
                showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
            )
            addSeasonNames(buildAnimeSeasonData(episodes))
            if (!performanceMode) addScore(title.score)
        }
    } else {
        val playback = streamingCommunityClient.moviePlayback(title)
        newMovieLoadResponse(
            title.name,
            sourceUrl,
            TvType.Movie,
            dataUrl = StreamCenterPlaybackData(
                streamingCommunity = playback,
                stremio = stremioContext,
                torrent = torrentContext,
            ).toJson(),
        ) {
            if (!performanceMode) {
                this.posterUrl = poster
                this.year = title.year
                this.backgroundPosterUrl = streamingCommunityClient.imageUrl(title.backgroundFilename)
                this.logoUrl = streamingCommunityClient.imageUrl(title.logoFilename)
                this.plot = title.plot
                this.tags = title.genres
                this.duration = title.runtime
                this.contentRating = title.age?.let { "$it+" }
                this.recommendations = recommendations
            }
            addStreamCenterTrackingIds(
                StreamCenterTrackingIds(
                    tmdb = title.tmdbId?.toString(),
                    imdb = title.imdbId,
                    simkl = resolvedSimklId,
                ),
                showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
            )
            if (!performanceMode) addScore(title.score)
        }
    }
    val torrentProvenance = torrentPlaybackProvenance(torrentContext)
    val playbackPayloadSources = (
        listOf("StreamingCommunity", "Add-on Stremio abilitati") +
            torrentProvenance +
            "StreamCenter (payload di riproduzione)"
        ).distinct()
    return response.withCardProvenance(
        defaultSource = "StreamingCommunity",
        fieldSources = mapOf(
            "tipo_contenuto" to listOf("StreamingCommunity", "StreamCenter (conversione tipo)"),
            "classificazione_contenuti" to listOf(
                "StreamingCommunity",
                "StreamCenter (formattazione età)",
            ),
            "raccomandazioni" to listOf("StreamingCommunity"),
            "id_sincronizzazione" to listOf(
                "StreamingCommunity",
                "TMDB",
                "IMDb",
                "Simkl",
            ),
            "stagioni" to listOf(
                "StreamingCommunity",
                "StreamCenter (normalizzazione stagioni)",
            ),
            "episodi" to (
                listOf("StreamingCommunity", "Add-on Stremio abilitati") +
                    torrentProvenance
                ).distinct(),
            "episodi.nome" to listOf("StreamCenter (nome episodio predefinito)"),
            "episodi.poster" to listOf("StreamingCommunity (poster scheda)"),
            "episodi.stagione" to listOf("StreamingCommunity"),
            "episodi.episodio" to listOf("StreamingCommunity"),
            "episodi.dati_riproduzione" to playbackPayloadSources,
            "dati_riproduzione" to playbackPayloadSources,
        ),
        fieldNotes = if (performanceMode) {
            mapOf(
                "poster" to "Valore omesso dalla scheda finale in modalità prestazioni.",
                "trama" to "Valore omesso dalla scheda finale in modalità prestazioni.",
                "raccomandazioni" to "Richiesta opzionale non eseguita in modalità prestazioni.",
            )
        } else {
            emptyMap()
        },
    )
}

internal suspend fun StreamCenter.reconcileWithStreamingCommunityOrder(
    airedEpisodes: List<Episode>,
    actualUrl: String,
    streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
    stremioContext: StreamCenterStremioPlaybackContext?,
    torrentContext: StreamCenterTorrentPlaybackContext?,
    fallbackPoster: String?,
    minimalMetadata: Boolean,
    streamingCommunityMetadata: Map<Pair<Int, Int>, StreamingCommunityEpisode> = emptyMap(),
): List<Episode> {
    if (streamingCommunityEpisodes.isEmpty()) return airedEpisodes
    val airedKeys = airedEpisodes.episodeKeys()
    val airedUncovered = streamingCommunityEpisodes.keys.count { it !in airedKeys }
    if (airedUncovered == 0) return airedEpisodes
    val streamingCommunitySeasons = streamingCommunityEpisodes.keys
        .map { it.first }
        .filter { it > 0 }
        .distinct()
    val expectedSeasonCount = streamingCommunitySeasons.size
    val groupEpisodes = runCatching {
        fetchEpisodeGroupEpisodes(
            actualUrl = actualUrl,
            expectedSeasonCount = expectedSeasonCount,
            streamingCommunityEpisodes = streamingCommunityEpisodes,
            stremioContext = stremioContext,
            torrentContext = torrentContext,
            fallbackPoster = fallbackPoster,
            minimalMetadata = minimalMetadata,
        )
    }.getOrDefault(emptyList())
    val groupKeys = groupEpisodes.episodeKeys()
    val groupUncovered = streamingCommunityEpisodes.keys.count { it !in groupKeys }
    val useGroup = groupUncovered < airedUncovered
    StreamCenterLogger.logTab(
        tabName = actualUrl,
        action = "Ordine episodi riconciliato con StreamingCommunity",
        metadata = mapOf(
            "episodi_aired" to airedEpisodes.size,
            "episodi_gruppo" to groupEpisodes.size,
            "stagioni_attese" to expectedSeasonCount,
            "sc_non_coperti_aired" to airedUncovered,
            "sc_non_coperti_gruppo" to groupUncovered,
            "gruppo_usato" to useGroup,
            "ordine_streamingcommunity_usato" to !useGroup,
        ),
    )
    return if (useGroup) {
        groupEpisodes
    } else {
        buildStreamingCommunityEpisodes(
            payloads = streamingCommunityEpisodes,
            fallbackPoster = null,
            stremioContext = stremioContext,
            torrentContext = torrentContext,
            episodeMetadata = streamingCommunityMetadata,
            minimalMetadata = minimalMetadata,
        )
    }
}

internal fun List<Episode>.episodeKeys(): Set<Pair<Int, Int>> {
    return mapNotNull { episode ->
        val season = episode.season ?: return@mapNotNull null
        val number = episode.episode ?: return@mapNotNull null
        season to number
    }.toSet()
}
