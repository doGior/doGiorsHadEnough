package it.dogior.hadEnough

import it.dogior.hadEnough.tmdb.StreamCenterTmdbUserAgent

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.StreamCenter.Companion.TMDB_MEDIA_URL_REGEX
import it.dogior.hadEnough.cache.CachedActor
import it.dogior.hadEnough.cache.CachedEpisode
import it.dogior.hadEnough.cache.CachedMediaEntry
import it.dogior.hadEnough.cache.CachedSearchItem
import it.dogior.hadEnough.cache.CachedSeason
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.catalog.TmdbSeriesMetadata
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.tmdb.StreamCenterTmdbApiClient
import it.dogior.hadEnough.tmdb.TmdbApiEpisode
import it.dogior.hadEnough.tmdb.TmdbApiMapping
import it.dogior.hadEnough.tmdb.TmdbApiSeason
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterHostGate
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.cleanMetadataEpisodeTitle
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.cleanTmdbEpisodeDescription
import it.dogior.hadEnough.util.parseMetadataDate
import it.dogior.hadEnough.util.parseMetadataRuntime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal suspend fun StreamCenter.loadTmdbMedia(
    actualUrl: String,
    scHint: StreamingCommunityTitle? = null,
    strictTmdbMetadata: Boolean = false,
    cacheable: Boolean = false,
): LoadResponse {
    val isTvSeries = actualUrl.contains("/tv/")
    val cacheActive = cacheable && StreamCenterMediaCache.isEnabled(sharedPref)
    if (cacheActive) {
        StreamCenterMediaCache.readMedia(actualUrl)?.let { cached ->
            StreamCenterLogger.logTab(
                tabName = cached.title,
                action = "Scheda servita dalla cache",
                metadata = mapOf(
                    "chiave" to cached.key,
                    "tipo" to cached.type,
                    "episodi" to cached.episodes.size,
                    "scadenza_ms" to cached.expiresAtMillis,
                ),
            )
            return rebuildTmdbMediaFromCache(cached)
        }
    }
    val minimalMetadata = performanceMode && !strictTmdbMetadata
    val apiMedia = fetchTmdbApiMedia(isTvSeries, extractTmdbId(actualUrl)?.toIntOrNull(), minimalMetadata)
    val (doc, tmdbEnglishTitle) = coroutineScope {
        val englishTitleDeferred = async(Dispatchers.IO) {
            runCatching {
                resolveTmdbEnglishTitle(
                    tmdbId = extractTmdbId(actualUrl),
                    isMovie = !isTvSeries,
                )
            }.getOrNull()
        }
        val page = when {
            apiMedia == null -> getTmdbDocument(actualUrl)
            needsTmdbSourceIdentifiers() -> runCatching { getTmdbDocument(actualUrl) }.getOrNull()
            else -> null
        }
        page to englishTitleDeferred.await()
    }
    val metadata = apiMedia?.metadata ?: buildMetadata(
        requireNotNull(doc),
        actualUrl,
        minimalMetadata = minimalMetadata,
    ).let { base ->
        base.copy(
            logo = resolveTmdbLogo(!isTvSeries, base.tmdbId?.toIntOrNull()) ?: base.logo,
            trailerUrl = if (!performanceMode || strictTmdbMetadata) findTmdbTrailer(actualUrl, doc) else null,
        )
    }
    val sourceMetadata = if (apiMedia == null) metadata else doc?.let { buildMetadata(it, actualUrl, minimalMetadata = false) }
    val cardTitle = streamCenterUrlParameter(actualUrl, "title")
    val cardPoster = streamCenterUrlParameter(actualUrl, "poster")
    val streamingCommunityTitle = scHint ?: if (
        catalogDefinition == null && isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY)
    ) {
        sourceMetadata?.let { runCatching { streamingCommunityClient.findTitle(it, isTvSeries) }.getOrNull() }
    } else {
        null
    }
    val sc = streamingCommunityTitle
    val tmdbImdbId = doc?.let(this::extractTmdbImdbId)
    val resolvedStremioImdbId = if (
        tmdbImdbId == null &&
        sc?.imdbId == null &&
        sourceMetadata != null &&
        activeStremioResolversNeedImdbId(if (isTvSeries) "series" else "movie")
    ) {
        runCatching {
            StreamCenterStremioAddonClient.resolveImdbId(
                contentType = if (isTvSeries) "series" else "movie",
                titleCandidates = listOfNotNull(sourceMetadata.originalTitle, sourceMetadata.title),
                year = sourceMetadata.year,
            )
        }.getOrNull()
    } else {
        null
    }
    val playbackImdbId = tmdbImdbId ?: sc?.imdbId ?: resolvedStremioImdbId
    val responseImdbId = apiMedia?.imdbId ?: tmdbImdbId ?: sc?.imdbId.takeUnless { strictTmdbMetadata }
    val resolvedSimklIds = if (catalogDefinition == null) {
        runCatching {
            simklCatalog.resolveMediaIds(
                imdb = playbackImdbId ?: apiMedia?.imdbId,
                tmdb = metadata.tmdbId,
                allowedCategories = if (isTvSeries) setOf("tv") else setOf("movies"),
            )
        }.getOrNull()
    } else {
        null
    }
    val resolvedSimklId = resolvedSimklIds?.simkl?.takeIf { it > 0 }
    val resolvedTrackingIds = (resolvedSimklIds?.trackingIds() ?: StreamCenterTrackingIds()).copy(
        tmdb = metadata.tmdbId ?: resolvedSimklIds?.tmdb,
        imdb = responseImdbId ?: resolvedSimklIds?.imdb,
    )
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = if (isTvSeries) listOf("series") else listOf("movie"),
        imdbId = playbackImdbId,
        tmdbId = metadata.tmdbId,
    )

    val title = metadata.title.takeIf { it.isNotBlank() && it != "Sconosciuto" }
        ?: cardTitle
        ?: sc?.name.takeUnless { strictTmdbMetadata }
        ?: metadata.title
    val poster = metadata.poster
        ?: cardPoster
        ?: streamingCommunityClient.imageUrl(sc?.posterFilename).takeUnless { strictTmdbMetadata }
    val background = metadata.background
        ?: streamingCommunityClient.imageUrl(sc?.backgroundFilename).takeUnless { strictTmdbMetadata }
    val logo = metadata.logo
        ?: streamingCommunityClient.imageUrl(sc?.logoFilename).takeUnless { strictTmdbMetadata }
    val plot = metadata.plot ?: sc?.plot.takeUnless { strictTmdbMetadata }
    val tags = metadata.tags.distinctBy { it.lowercase(Locale.ROOT) }
        .ifEmpty { sc?.genres.orEmpty().takeUnless { strictTmdbMetadata }.orEmpty() }
    val year = metadata.year ?: sc?.year.takeUnless { strictTmdbMetadata }
    val score = metadata.score ?: sc?.score.takeUnless { strictTmdbMetadata }
    val contentRating = metadata.contentRating
        ?: sc?.age?.let { "$it+" }.takeUnless { strictTmdbMetadata }
    val torrentContext = torrentPlaybackContext(
        titles = if (apiMedia == null) {
            listOf(title, metadata.originalTitle, cardTitle, sc?.name)
        } else {
            listOf(sourceMetadata?.title?.takeUnless { it == "Sconosciuto" }, sourceMetadata?.originalTitle, sc?.name)
        },
        englishTitle = tmdbEnglishTitle,
        year = if (apiMedia == null) year else sourceMetadata?.year ?: sc?.year,
        isAnime = false,
        isMovie = !isTvSeries,
        imdbId = playbackImdbId,
    )
    StreamCenterLogger.logMetadata(
        tabName = title,
        source = "TMDB",
        action = "Metadati TMDB acquisiti",
        metadata = mapOf(
            "id_tmdb" to metadata.tmdbId,
            "tipo" to if (isTvSeries) "serie_tv" else "film",
            "modalita_minimale" to (performanceMode && !strictTmdbMetadata),
            "titolo_originale_disponibile" to !metadata.originalTitle.isNullOrBlank(),
            "titolo_inglese_torrent_disponibile" to !tmdbEnglishTitle.isNullOrBlank(),
            "trama_disponibile" to !metadata.plot.isNullOrBlank(),
            "poster_disponibile" to !metadata.poster.isNullOrBlank(),
            "tag" to metadata.tags.size,
            "persone" to metadata.people.size,
            "anno" to metadata.year,
            "imdb_disponibile" to !tmdbImdbId.isNullOrBlank(),
        ),
    )
    StreamCenterLogger.logMetadata(
        tabName = title,
        source = "StreamingCommunity",
        action = if (sc == null) {
            "Corrispondenza StreamingCommunity non trovata"
        } else {
            "Corrispondenza StreamingCommunity acquisita"
        },
        metadata = mapOf(
            "fonte_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY),
            "corrispondenza_presente" to (sc != null),
            "id_streamingcommunity" to sc?.id,
            "tipo" to sc?.type,
            "id_tmdb_associazione" to sc?.tmdbId,
        ),
        level = if (sc == null) StreamCenterLogger.Level.WARNING else StreamCenterLogger.Level.INFO,
    )
    val recommendations = if (strictTmdbMetadata) {
        runCatching { tmdbCatalog.recommendations(this, actualUrl, showCardScores) }.getOrDefault(emptyList())
    } else if (!performanceMode) {
        sc?.let { runCatching { fetchStreamingCommunityRecommendations(it) }.getOrDefault(emptyList()) }.orEmpty()
    } else {
        emptyList()
    }

    var snapshotEpisodes: List<Episode> = emptyList()
    var snapshotSeasons: List<SeasonData> = emptyList()
    var snapshotMovieDataUrl: String? = null
    var snapshotDuration = metadata.duration
    val response = if (isTvSeries) {
        val episodeFallbackPoster = poster.takeIf { !performanceMode || strictTmdbMetadata }
        val episodeMinimalMetadata = performanceMode && !strictTmdbMetadata
        val streamingCommunityEpisodeDetails = streamingCommunityTitle
            ?.let {
                runCatching {
                    streamingCommunityClient.episodeDetails(it, includeMetadata = !episodeMinimalMetadata)
                }.getOrNull()
            }
            .orEmpty()
        val streamingCommunityEpisodes = streamingCommunityEpisodeDetails.mapValues { it.value.playback }
        val episodes = runCatching {
            reconcileWithStreamingCommunityOrder(
                airedEpisodes = fetchEpisodes(
                    doc = doc,
                    actualUrl = actualUrl,
                    streamingCommunityEpisodes = streamingCommunityEpisodes,
                    stremioContext = stremioContext,
                    torrentContext = torrentContext,
                    fallbackPoster = episodeFallbackPoster,
                    minimalMetadata = episodeMinimalMetadata,
                ),
                actualUrl = actualUrl,
                streamingCommunityEpisodes = streamingCommunityEpisodes,
                stremioContext = stremioContext,
                torrentContext = torrentContext,
                fallbackPoster = episodeFallbackPoster,
                minimalMetadata = episodeMinimalMetadata,
                streamingCommunityMetadata = streamingCommunityEpisodeDetails.mapValues { it.value.metadata },
            ).ifEmpty {
                if (strictTmdbMetadata) {
                    emptyList()
                } else {
                    buildStreamingCommunityEpisodes(
                        streamingCommunityEpisodes,
                        poster.takeIf { !performanceMode },
                        stremioContext,
                        torrentContext,
                    )
                }
            }
        }.getOrDefault(emptyList())
        val seasonNames = if (strictTmdbMetadata) {
            runCatching { fetchTmdbSeasonNames(actualUrl, episodes) }
                .getOrDefault(buildAnimeSeasonData(episodes))
        } else {
            buildAnimeSeasonData(episodes)
        }
        StreamCenterLogger.logTab(
            tabName = title,
            action = "Dettagli serie TMDB aggregati",
            metadata = mapOf(
                "episodi_tmdb" to episodes.size,
                "episodi_streamingcommunity" to streamingCommunityEpisodes.size,
                "stagioni" to seasonNames.size,
                "raccomandazioni" to recommendations.size,
                "id_simkl" to resolvedSimklId,
                "id_imdb_risolto" to playbackImdbId,
            ),
        )
        snapshotEpisodes = episodes
        snapshotSeasons = seasonNames
        snapshotDuration = metadata.duration ?: TmdbSeriesMetadata.episodeRuntime(
            episodes.filter { (it.season ?: 1) > 0 }.map { it.runTime },
        )
        newTvSeriesLoadResponse(
            title,
            actualUrl,
            TvType.TvSeries,
            episodes,
        ) {
            if (!performanceMode || strictTmdbMetadata) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.plot = plot
                this.tags = tags
                this.year = year
                this.actors = metadata.people
                this.recommendations = recommendations
                this.contentRating = contentRating
                this.duration = snapshotDuration
                this.showStatus = metadata.showStatus
                    ?: streamingCommunityClient.showStatus(sc?.status).takeUnless { strictTmdbMetadata }
                this.comingSoon = metadata.comingSoon
            }
            addStreamCenterTrackingIds(
                resolvedTrackingIds,
                showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
            )
            addSeasonNames(seasonNames)
            if (!performanceMode || strictTmdbMetadata) {
                metadata.trailerUrl?.let { addTrailer(it) }
                addScore(score)
            }
        }
    } else {
        val moviePlaybackData = StreamCenterPlaybackData(
            tmdbUrl = actualUrl,
            streamingCommunity = streamingCommunityTitle?.let(streamingCommunityClient::moviePlayback),
            stremio = stremioContext,
            torrent = torrentContext,
        )
        snapshotMovieDataUrl = moviePlaybackData.toJson()
        StreamCenterLogger.logTab(
            tabName = title,
            action = "Dettagli film TMDB aggregati",
            metadata = mapOf(
                "raccomandazioni" to recommendations.size,
                "id_simkl" to resolvedSimklId,
                "id_imdb_risolto" to playbackImdbId,
                "fonte_riproduzione_streamingcommunity" to (streamingCommunityTitle != null),
            ),
        )
        newMovieLoadResponse(
            title,
            actualUrl,
            TvType.Movie,
            dataUrl = moviePlaybackData.toJson(),
        ) {
            if (!performanceMode || strictTmdbMetadata) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = metadata.duration ?: sc?.runtime.takeUnless { strictTmdbMetadata }
                this.actors = metadata.people
                this.recommendations = recommendations
                this.contentRating = contentRating
                this.comingSoon = metadata.comingSoon
            }
            addStreamCenterTrackingIds(
                resolvedTrackingIds,
                showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
                visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
            )
            if (!performanceMode || strictTmdbMetadata) {
                metadata.trailerUrl?.let { addTrailer(it) }
                addScore(score)
            }
        }
    }
    val titleSource = when {
        metadata.title.isNotBlank() && metadata.title != "Sconosciuto" -> "TMDB"
        !cardTitle.isNullOrBlank() -> "Parametri della card StreamCenter"
        !strictTmdbMetadata && !sc?.name.isNullOrBlank() -> "StreamingCommunity"
        else -> "TMDB"
    }
    val posterSource = when {
        !metadata.poster.isNullOrBlank() -> "TMDB"
        !cardPoster.isNullOrBlank() -> "Parametri della card StreamCenter"
        !strictTmdbMetadata && !sc?.posterFilename.isNullOrBlank() -> "StreamingCommunity"
        else -> "TMDB (valore non disponibile)"
    }
    val metadataOrStreamingCommunity = { tmdbAvailable: Boolean, scAvailable: Boolean ->
        when {
            tmdbAvailable -> listOf("TMDB")
            !strictTmdbMetadata && scAvailable -> listOf("StreamingCommunity")
            else -> listOf("TMDB (valore non disponibile)")
        }
    }
    val episodeSources = buildList {
        add("TMDB")
        if (streamingCommunityTitle != null) add("StreamingCommunity")
        add("Add-on Stremio abilitati")
        addAll(torrentPlaybackProvenance(torrentContext))
        add("StreamCenter (aggregazione episodio)")
    }
    val playbackPayloadSources = (
        listOf(
            "TMDB",
            "StreamingCommunity",
            "Add-on Stremio abilitati",
        ) +
            torrentPlaybackProvenance(torrentContext) +
            "StreamCenter (payload di riproduzione)"
        ).distinct()
    if (cacheActive) {
        StreamCenterMediaCache.keyFor(actualUrl)?.let { key ->
            val hasContent = !isTvSeries || snapshotEpisodes.isNotEmpty()
            if (hasContent && !metadata.comingSoon) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val cachedEpisodes = snapshotEpisodes.map { ep ->
                        CachedEpisode(
                            data = ep.data,
                            name = ep.name,
                            season = ep.season,
                            episode = ep.episode,
                            posterUrl = ep.posterUrl,
                            description = ep.description,
                            runTime = ep.runTime,
                            dateMillis = ep.date,
                            score = ep.score?.toInt(10_000),
                        )
                    }
                    val nextAir = cachedEpisodes.mapNotNull { it.dateMillis }.filter { it > now }.minOrNull()
                    val showStatusName = metadata.showStatus?.name
                    StreamCenterMediaCache.writeMedia(
                        CachedMediaEntry(
                            schemaVersion = StreamCenterMediaCache.SCHEMA_VERSION,
                            key = key,
                            url = actualUrl,
                            type = if (isTvSeries) StreamCenterMediaCache.TYPE_SERIES else StreamCenterMediaCache.TYPE_MOVIE,
                            title = title,
                            posterUrl = poster,
                            backgroundPosterUrl = background,
                            logoUrl = logo,
                            plot = plot,
                            tags = tags,
                            year = year,
                            duration = snapshotDuration,
                            contentRating = contentRating,
                            score = score,
                            showStatus = showStatusName,
                            comingSoon = metadata.comingSoon,
                            trailerUrl = metadata.trailerUrl,
                            trailerCheckedAtMillis = if (!performanceMode || strictTmdbMetadata) now else 0L,
                            trackingIds = resolvedTrackingIds,
                            actors = metadata.people.map(::CachedActor),
                            recommendations = recommendations.map {
                                CachedSearchItem(
                                    name = it.name,
                                    url = it.url,
                                    type = it.type?.name,
                                    posterUrl = it.posterUrl,
                                )
                            },
                            seasons = snapshotSeasons.map {
                                CachedSeason(it.season, it.name, it.displaySeason)
                            },
                            episodes = cachedEpisodes,
                            movieDataUrl = snapshotMovieDataUrl,
                            fromTmdbApi = apiMedia != null,
                            cachedAtMillis = now,
                            expiresAtMillis = StreamCenterMediaCache.computeMediaExpiry(
                                now, showStatusName, nextAir, isMovie = !isTvSeries,
                            ),
                        ),
                    )
                }
            }
        }
    }
    return response.withCardProvenance(
        defaultSource = "TMDB",
        fieldSources = mapOf(
            "titolo" to listOf(titleSource),
            "poster" to listOf(posterSource),
            "sfondo" to metadataOrStreamingCommunity(
                !metadata.background.isNullOrBlank(),
                !sc?.backgroundFilename.isNullOrBlank(),
            ),
            "logo" to metadataOrStreamingCommunity(
                !metadata.logo.isNullOrBlank(),
                !sc?.logoFilename.isNullOrBlank(),
            ),
            "trama" to metadataOrStreamingCommunity(
                !metadata.plot.isNullOrBlank(),
                !sc?.plot.isNullOrBlank(),
            ),
            "tag" to metadataOrStreamingCommunity(
                metadata.tags.isNotEmpty(),
                !sc?.genres.isNullOrEmpty(),
            ),
            "anno" to metadataOrStreamingCommunity(
                metadata.year != null,
                sc?.year != null,
            ),
            "punteggio" to metadataOrStreamingCommunity(
                !metadata.score.isNullOrBlank(),
                !sc?.score.isNullOrBlank(),
            ),
            "classificazione_contenuti" to metadataOrStreamingCommunity(
                !metadata.contentRating.isNullOrBlank(),
                sc?.age != null,
            ),
            "durata_minuti" to metadataOrStreamingCommunity(
                metadata.duration != null,
                sc?.runtime != null,
            ),
            "cast" to listOf("TMDB"),
            "trailer" to listOf("TMDB"),
            "in_arrivo" to listOf("TMDB"),
            "raccomandazioni" to listOf(
                if (strictTmdbMetadata) "TMDB" else "StreamingCommunity",
            ),
            "id_sincronizzazione" to listOf("TMDB", "IMDb", "Simkl"),
            "stato_trasmissione" to listOf("TMDB"),
            "stagioni" to listOf("TMDB", "StreamCenter (normalizzazione stagioni)"),
            "episodi" to episodeSources,
            "episodi.dati_riproduzione" to playbackPayloadSources,
            "dati_riproduzione" to playbackPayloadSources,
        ),
        fieldNotes = mapOf(
            "episodi" to "Ogni episodio conserva integralmente metadati e payload finale; le fonti elencate sono quelle combinate nel builder.",
            "raccomandazioni" to if (performanceMode && !strictTmdbMetadata) {
                "Valore vuoto perché la modalità prestazioni evita la richiesta opzionale."
            } else {
                "Fonte scelta in base alla modalità del catalogo."
            },
        ),
    )
}

internal suspend fun StreamCenter.rebuildTmdbMediaFromCache(entry: CachedMediaEntry): LoadResponse {
    val trailerUrl = cachedTrailer(entry)
    val applyCommon: suspend LoadResponse.() -> Unit = {
        apiName = this@rebuildTmdbMediaFromCache.name
        posterUrl = entry.posterUrl
        backgroundPosterUrl = entry.backgroundPosterUrl
        logoUrl = entry.logoUrl
        plot = entry.plot
        tags = entry.tags.map(::normalizeTmdbStatusTag)
        year = entry.year
        duration = entry.duration
        contentRating = entry.contentRating
        comingSoon = entry.comingSoon
        actors = entry.actors.map(CachedActor::toActorData)
        recommendations = entry.recommendations.mapNotNull(this@rebuildTmdbMediaFromCache::rebuildSearchItemFromCache)
        addStreamCenterTrackingIds(
            entry.trackingIds ?: StreamCenterTrackingIds(),
            showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
            visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
        )
        trailerUrl?.let { addTrailer(it) }
        addScore(entry.score)
    }
    return if (entry.type == StreamCenterMediaCache.TYPE_SERIES) {
        newTvSeriesLoadResponse(
            entry.title,
            entry.url,
            TvType.TvSeries,
            entry.episodes.map(this@rebuildTmdbMediaFromCache::rebuildEpisodeFromCache),
        ) {
            applyCommon()
            showStatus = entry.showStatus?.let { runCatching { ShowStatus.valueOf(it) }.getOrNull() }
            addSeasonNames(
                entry.seasons.map { season ->
                    SeasonData(season = season.season, name = season.name, displaySeason = season.displaySeason)
                },
            )
        }
    } else {
        newMovieLoadResponse(
            entry.title,
            entry.url,
            TvType.Movie,
            dataUrl = entry.movieDataUrl ?: entry.url,
        ) {
            applyCommon()
        }
    }
}

internal fun StreamCenter.rebuildEpisodeFromCache(cached: CachedEpisode): Episode = newEpisode(cached.data) {
    this.name = cached.name
    this.season = cached.season
    this.episode = cached.episode
    this.posterUrl = cached.posterUrl
    this.description = cleanTmdbEpisodeDescription(cached.description)
    this.runTime = cached.runTime
    this.date = cached.dateMillis
    this.score = cached.score?.let { Score.from(it, 10_000) }
}

internal fun StreamCenter.rebuildSearchItemFromCache(item: CachedSearchItem): SearchResponse? {
    if (item.name.isBlank() || item.url.isBlank()) return null
    val type = item.type?.let { runCatching { TvType.valueOf(it) }.getOrNull() }
    return if (type == TvType.Movie) {
        newMovieSearchResponse(item.name, item.url, TvType.Movie) { posterUrl = item.posterUrl }
    } else {
        newTvSeriesSearchResponse(item.name, item.url, type ?: TvType.TvSeries) { posterUrl = item.posterUrl }
    }
}

internal fun StreamCenter.buildMetadata(
    doc: Document,
    actualUrl: String,
    minimalMetadata: Boolean = performanceMode,
): StreamCenterMetadata {
    if (minimalMetadata) {
        return StreamCenterMetadata(
            title = getLocalizedTitle(doc).ifBlank { "Sconosciuto" },
            originalTitle = null,
            plot = null,
            poster = null,
            background = null,
            logo = null,
            tags = emptyList(),
            year = null,
            tmdbId = extractTmdbId(actualUrl),
            score = null,
            people = emptyList(),
            contentRating = null,
            showStatus = null,
            comingSoon = false,
            duration = null,
            trailerUrl = null,
        )
    }
    val title = getLocalizedTitle(doc).ifBlank { "Sconosciuto" }
    val originalTitle = extractAnyFact(doc, "Titolo originale", "Nome Originale", "Original Title", "Original Name")
    val status = extractAnyFact(doc, "Stato", "Status")
    val originalLanguage = extractAnyFact(doc, "Lingua Originale", "Original Language")
    val type = extractAnyFact(doc, "Tipo", "Type")
    val budget = extractAnyFact(doc, "Budget")
    val revenue = extractAnyFact(doc, "Incasso", "Revenue")
    val genres = doc.select("span.genres a")
        .mapNotNull { cleanText(it.text()) }
        .distinctBy { it.lowercase(Locale.ROOT) }
    val isTvSeries = actualUrl.contains("/tv/")
    val originalTitleTag = originalTitle?.takeIf { !it.equals(title, ignoreCase = true) }
    val tags = if (isTvSeries) {
        buildList {
            originalTitleTag?.let { add("Nome originale: $it") }
            extractTmdbNetworks(doc)?.let { add(it) }
            status?.let { add(normalizeTmdbStatusTag("Stato: $it")) }
            type?.let { add("Tipo: $it") }
            originalLanguage?.let { add("Lingua originale: $it") }
            addAll(genres)
        }
    } else {
        buildList {
            originalTitleTag?.let { add("Titolo originale: $it") }
            originalLanguage?.let { add("Lingua originale: $it") }
            budget?.let { add("Budget: $it") }
            revenue?.let { add("Incasso: $it") }
            addAll(genres)
        }
    }.distinctBy { it.lowercase(Locale.ROOT) }
    val images = doc.select("meta[property=og:image]").mapNotNull { cleanText(it.attr("content")) }
    val score = extractTmdbScore(doc)

    return StreamCenterMetadata(
        title = title,
        originalTitle = originalTitle,
        plot = cleanText(doc.selectFirst("meta[property=og:description]")?.attr("content")),
        poster = images.firstOrNull(),
        background = images.getOrNull(1),
        logo = extractTmdbLogo(doc),
        tags = tags,
        year = parseYear(doc),
        tmdbId = extractTmdbId(actualUrl),
        score = score,
        people = (parseActors(doc) + parseCrew(doc)).distinct(),
        contentRating = extractContentRating(doc),
        showStatus = mapShowStatus(status),
        comingSoon = isComingSoon(status),
        duration = parseMetadataRuntime(doc.selectFirst("span.runtime")?.text()),
        trailerUrl = extractTrailerUrl(doc),
    )
}

internal suspend fun StreamCenter.getTmdbDocument(
    url: String,
    page: Int? = null,
): Document {
    val requestUrl = stripStreamCenterParams(normalizeTmdbUrl(url, page))
    val html = fetchText {
        StreamCenterHostGate.request(requestUrl) {
            app.get(
                requestUrl,
                headers = StreamCenterTmdbUserAgent.webHeaders(headers, sharedPref),
            )
        }.text
    }
    return Jsoup.parse(html, requestUrl)
}

internal suspend fun StreamCenter.fetchText(fetch: suspend () -> String): String = fetch()

internal suspend fun StreamCenter.fetchTmdbSeasonNames(
    actualUrl: String,
    episodes: List<Episode>,
): List<SeasonData> {
    val mediaMatch = TMDB_MEDIA_URL_REGEX.find(actualUrl) ?: return buildAnimeSeasonData(episodes)
    if (!mediaMatch.groupValues[1].equals("tv", ignoreCase = true)) return emptyList()
    val tmdbId = mediaMatch.groupValues[2]
    if (StreamCenterTmdbApiClient.isActive()) {
        fetchTmdbApiSeasons(tmdbId.toInt())?.let { seasons ->
            val namesBySeason = seasons.associate { it.number to it.name }
            return episodes.mapNotNull(Episode::season).distinct().sorted().map { season ->
                SeasonData(
                    season = season,
                    name = meaningfulSeasonName(namesBySeason[season], season),
                    displaySeason = season.takeIf { it > 0 },
                )
            }
        }
    }
    val seasonDocument = getTmdbDocument("https://www.themoviedb.org/tv/$tmdbId/seasons")
    val namesBySeason = seasonDocument.select("a[href*=/season/]")
        .mapNotNull { anchor ->
            val href = anchor.attr("href").takeUnless { it.contains("/episode/") }
                ?: return@mapNotNull null
            val season = extractSeasonNumber(href) ?: return@mapNotNull null
            season to anchor.text().trim().takeIf(String::isNotBlank)
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, names) -> names.firstOrNull { !it.isNullOrBlank() } }
    return episodes.mapNotNull(Episode::season)
        .distinct()
        .sorted()
        .map { season ->
            SeasonData(
                season = season,
                name = meaningfulSeasonName(namesBySeason[season], season),
                displaySeason = season.takeIf { it > 0 },
            )
        }
}

internal suspend fun StreamCenter.fetchEpisodes(
    doc: Document?,
    actualUrl: String,
    targetSeason: Int? = null,
    streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData> = emptyMap(),
    stremioContext: StreamCenterStremioPlaybackContext? = null,
    torrentContext: StreamCenterTorrentPlaybackContext? = null,
    fallbackPoster: String? = null,
    minimalMetadata: Boolean = false,
): List<Episode> = coroutineScope {
    val apiTmdbId = extractTmdbId(actualUrl)?.toIntOrNull()?.takeIf { StreamCenterTmdbApiClient.isActive() }
    val apiEpisodes = if (actualUrl.contains("/tv/") && apiTmdbId != null) {
        val seasonNumbers = targetSeason?.let(::listOf) ?: fetchTmdbApiSeasons(apiTmdbId)?.map(TmdbApiSeason::number)
        seasonNumbers?.map { season ->
            async(Dispatchers.IO) { fetchTmdbApiSeason(apiTmdbId, season) }
        }?.awaitAll()?.takeIf { seasons -> seasons.all { it != null } }?.flatMap { season ->
            apiSeasonEpisodes(
                episodes = TmdbApiMapping.episodes(season),
                tmdbId = apiTmdbId,
                streamingCommunityEpisodes = streamingCommunityEpisodes,
                stremioContext = stremioContext,
                torrentContext = torrentContext,
                fallbackPoster = fallbackPoster,
                minimalMetadata = minimalMetadata,
            )
        }
    } else {
        null
    }
    if (apiEpisodes != null) {
        apiEpisodes
    } else if (!actualUrl.contains("/tv/")) {
        emptyList()
    } else {
        val seasonUrls = targetSeason
            ?.let { listOf(buildTmdbSeasonUrl(actualUrl, it)) }
            ?: fetchSeasonUrls(doc, actualUrl)

        seasonUrls.map { seasonUrl ->
            async(Dispatchers.IO) {
                val fallbackSeason = extractSeasonNumber(seasonUrl)
                runCatching { getTmdbDocument(seasonUrl) }
                    .getOrNull()
                    ?.let {
                        parseSeasonEpisodes(
                            seasonDoc = it,
                            fallbackSeason = fallbackSeason,
                            streamingCommunityEpisodes = streamingCommunityEpisodes,
                            stremioContext = stremioContext,
                            torrentContext = torrentContext,
                            fallbackPoster = fallbackPoster,
                            minimalMetadata = minimalMetadata,
                        )
                    }
                    .orEmpty()
            }
        }.awaitAll().flatten()
    }
}

internal suspend fun StreamCenter.fetchEpisodeGroupEpisodes(
    actualUrl: String,
    expectedSeasonCount: Int,
    streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
    stremioContext: StreamCenterStremioPlaybackContext?,
    torrentContext: StreamCenterTorrentPlaybackContext?,
    fallbackPoster: String?,
    minimalMetadata: Boolean,
): List<Episode> = coroutineScope {
    val mediaMatch = TMDB_MEDIA_URL_REGEX.find(actualUrl) ?: return@coroutineScope emptyList()
    if (!mediaMatch.groupValues[1].equals("tv", ignoreCase = true)) return@coroutineScope emptyList()
    val tmdbId = mediaMatch.groupValues[2]
    if (StreamCenterTmdbApiClient.isActive()) {
        fetchTmdbApiEpisodeGroup(tmdbId.toInt(), expectedSeasonCount)?.let { groups ->
            return@coroutineScope groups.flatMap { group ->
                apiSeasonEpisodes(
                    episodes = group.episodes,
                    tmdbId = tmdbId.toInt(),
                    streamingCommunityEpisodes = streamingCommunityEpisodes,
                    stremioContext = stremioContext,
                    torrentContext = torrentContext,
                    fallbackPoster = fallbackPoster,
                    minimalMetadata = minimalMetadata,
                    digitalSeason = group.digitalSeason,
                )
            }
        }
    }
    val groupsDoc = runCatching {
        getTmdbDocument("https://www.themoviedb.org/tv/$tmdbId/episode_groups")
    }.getOrNull() ?: return@coroutineScope emptyList()
    val groupId = selectEpisodeGroupId(groupsDoc, expectedSeasonCount) ?: return@coroutineScope emptyList()
    val groupDoc = runCatching {
        getTmdbDocument("https://www.themoviedb.org/tv/$tmdbId/episode_group/$groupId")
    }.getOrNull() ?: return@coroutineScope emptyList()
    val subGroups = groupDoc.select("section.panel.season h2 a[href*=/group/]")
        .mapIndexedNotNull { index, anchor ->
            val href = anchor.attr("href").takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val seasonNumber = Regex("""(\d+)""").find(anchor.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (index + 1)
            seasonNumber to normalizeTmdbUrl(href)
        }
    subGroups.map { (seasonNumber, groupUrl) ->
        async(Dispatchers.IO) {
            runCatching { getTmdbDocument(groupUrl) }
                .getOrNull()
                ?.let {
                    parseSeasonEpisodes(
                        seasonDoc = it,
                        fallbackSeason = seasonNumber,
                        streamingCommunityEpisodes = streamingCommunityEpisodes,
                        stremioContext = stremioContext,
                        torrentContext = torrentContext,
                        fallbackPoster = fallbackPoster,
                        minimalMetadata = minimalMetadata,
                        digitalSeason = seasonNumber,
                    )
                }
                .orEmpty()
        }
    }.awaitAll().flatten()
}

internal fun StreamCenter.selectEpisodeGroupId(groupsDoc: Document, expectedSeasonCount: Int): String? {
    val groups = groupsDoc.select("h2 a[href*=/episode_group/]").mapNotNull { anchor ->
        val href = anchor.attr("href")
        val id = Regex("""/episode_group/([a-f0-9]{24})""").find(href)?.groupValues?.getOrNull(1)
            ?: return@mapNotNull null
        val countsText = anchor.parents().firstOrNull { it.tagName() == "h2" }
            ?.nextElementSibling()
            ?.text()
            .orEmpty()
        val seasonCount = Regex("""(\d+)\s*(?:gruppi|groups)""", RegexOption.IGNORE_CASE)
            .find(countsText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val isDigital = anchor.text().lowercase(Locale.ROOT).let { "digital" in it || "digitale" in it }
        Triple(id, seasonCount, isDigital)
    }.distinctBy { it.first }
    return groups.firstOrNull { it.second == expectedSeasonCount }?.first
        ?: groups.firstOrNull { it.third }?.first
}

internal suspend fun StreamCenter.fetchSeasonUrls(doc: Document?, actualUrl: String): List<String> {
    val mediaMatch = TMDB_MEDIA_URL_REGEX.find(actualUrl) ?: return emptyList()
    if (!mediaMatch.groupValues[1].equals("tv", ignoreCase = true)) return emptyList()
    val tmdbId = mediaMatch.groupValues[2]
    val path = "tv/$tmdbId"

    val seasonIndex = runCatching {
        getTmdbDocument("https://www.themoviedb.org/$path/seasons")
    }.getOrNull()
    val seasonUrls = seasonIndex?.let(this@fetchSeasonUrls::extractSeasonLinks).orEmpty()
    return seasonUrls.ifEmpty { doc?.let(this@fetchSeasonUrls::extractSeasonLinks).orEmpty() }
}

internal fun StreamCenter.buildTmdbSeasonUrl(actualUrl: String, seasonNumber: Int): String {
    val baseUrl = stripStreamCenterParams(normalizeTmdbUrl(actualUrl)).substringBefore("?").trimEnd('/')
    return normalizeTmdbUrl("$baseUrl/season/$seasonNumber")
}

internal fun StreamCenter.extractSeasonLinks(doc: Document): List<String> {
    return doc.select("a[href*=/season/]")
        .mapNotNull { anchor ->
            val href = anchor.attr("href")
                .substringBefore("#")
                .takeIf { it.contains("/season/") && !it.contains("/episode/") }
                ?: return@mapNotNull null
            normalizeTmdbUrl(href)
        }
        .filter { extractSeasonNumber(it) != null }
        .distinctBy { extractSeasonNumber(it) }
        .sortedWith(compareBy({ extractSeasonNumber(it) ?: Int.MAX_VALUE }, { it }))
}

internal fun StreamCenter.extractSeasonNumber(url: String): Int? {
    return Regex("""/season/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

internal fun StreamCenter.parseSeasonEpisodes(
    seasonDoc: Document,
    fallbackSeason: Int?,
    streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
    stremioContext: StreamCenterStremioPlaybackContext? = null,
    torrentContext: StreamCenterTorrentPlaybackContext? = null,
    fallbackPoster: String? = null,
    minimalMetadata: Boolean = false,
    digitalSeason: Int? = null,
): List<Episode> {
    return seasonDoc.select("div.episode_list div.card").mapIndexedNotNull { index, card ->
        val anchor = card.selectFirst("a[data-episode-number][data-season-number]")
            ?: card.selectFirst("a[href*=/episode/]")
        val rawHref = anchor?.attr("href")?.takeIf { it.isNotBlank() && it.contains("/episode/") }
            ?: card.attr("data-url").takeIf { it.isNotBlank() && it.contains("/episode/") }
        val dataUrl = rawHref?.let { normalizeTmdbUrl(it) }.orEmpty()
        val airedEpisode = anchor?.attr("data-episode-number")?.toIntOrNull()
            ?: rawHref?.let { Regex("""/episode/(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val airedSeason = anchor?.attr("data-season-number")?.toIntOrNull()
            ?: rawHref?.let { extractSeasonNumber(it) }
            ?: fallbackSeason
        val displaySeason = digitalSeason ?: airedSeason
        val displayEpisode = if (digitalSeason != null) {
            card.selectFirst("span.episode_number")?.text()?.trim()?.toIntOrNull() ?: (index + 1)
        } else {
            airedEpisode
        }
        val title = if (minimalMetadata) {
            null
        } else {
            cleanMetadataEpisodeTitle(
                card.selectFirst("div.episode_title h3 a")?.text()
                    ?: anchor?.text()
            )
        }

        if (dataUrl.isBlank() && displayEpisode == null && title == null) return@mapIndexedNotNull null

        buildTmdbEpisode(
            dataUrl = dataUrl,
            airedSeason = airedSeason,
            airedEpisode = airedEpisode,
            displaySeason = displaySeason,
            displayEpisode = displayEpisode,
            title = title,
            airDate = parseMetadataDate(card.selectFirst("div.date span.date")?.text()),
            posterUrl = card.selectFirst("img.backdrop")?.extractImageUrl() ?: card.selectFirst("img")?.extractImageUrl(),
            description = cleanTmdbEpisodeDescription(card.selectFirst("div.overview p")?.text()),
            runtime = parseMetadataRuntime(card.selectFirst("span.runtime")?.text()),
            ratingPercent = (
                card.selectFirst("div.rating")?.text()
                    ?: card.selectFirst("[data-percent]")?.attr("data-percent")
                )
                ?.let { Regex("""\d+""").find(it)?.value }
                ?.toIntOrNull(),
            streamingCommunityEpisodes = streamingCommunityEpisodes,
            stremioContext = stremioContext,
            torrentContext = torrentContext,
            fallbackPoster = fallbackPoster,
            minimalMetadata = minimalMetadata,
        )
    }
}

internal fun StreamCenter.apiSeasonEpisodes(
    episodes: List<TmdbApiEpisode>,
    tmdbId: Int,
    streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
    stremioContext: StreamCenterStremioPlaybackContext?,
    torrentContext: StreamCenterTorrentPlaybackContext?,
    fallbackPoster: String?,
    minimalMetadata: Boolean,
    digitalSeason: Int? = null,
): List<Episode> = episodes.mapIndexed { index, episode ->
    buildTmdbEpisode(
        dataUrl = "https://www.themoviedb.org/tv/$tmdbId/season/${episode.season}/episode/${episode.episode}",
        airedSeason = episode.season,
        airedEpisode = episode.episode,
        displaySeason = digitalSeason ?: episode.season,
        displayEpisode = if (digitalSeason != null) index + 1 else episode.episode,
        title = if (minimalMetadata) null else episode.title,
        airDate = episode.airDate,
        posterUrl = episode.still,
        description = episode.overview,
        runtime = episode.runtime,
        ratingPercent = episode.ratingPercent,
        streamingCommunityEpisodes = streamingCommunityEpisodes,
        stremioContext = stremioContext,
        torrentContext = torrentContext,
        fallbackPoster = fallbackPoster,
        minimalMetadata = minimalMetadata,
    )
}

internal fun StreamCenter.buildTmdbEpisode(
    dataUrl: String,
    airedSeason: Int?,
    airedEpisode: Int?,
    displaySeason: Int?,
    displayEpisode: Int?,
    title: String?,
    airDate: String?,
    posterUrl: String?,
    description: String?,
    runtime: Int?,
    ratingPercent: Int?,
    streamingCommunityEpisodes: Map<Pair<Int, Int>, StreamingCommunityPlaybackData>,
    stremioContext: StreamCenterStremioPlaybackContext?,
    torrentContext: StreamCenterTorrentPlaybackContext?,
    fallbackPoster: String?,
    minimalMetadata: Boolean,
): Episode {
    val streamingCommunityPlayback = displaySeason?.let { season ->
        displayEpisode?.let { episode -> streamingCommunityEpisodes[season to episode] }
    }
    val sourcePayload = buildStreamCenterEpisodePayload(
        tmdbUrl = dataUrl,
        streamingCommunity = streamingCommunityPlayback,
        stremioContext = stremioContext?.copy(
            season = airedSeason,
            episode = airedEpisode,
        ),
        torrentContext = torrentContext?.forEpisode(airedSeason, airedEpisode),
    )
    return newEpisode(sourcePayload) {
        this.name = if (minimalMetadata) {
            displayEpisode?.let { "Episodio $it" }
        } else {
            title
        }
        this.season = displaySeason
        this.episode = displayEpisode
        airDate?.let { this.addDate(it) }
        if (!minimalMetadata) {
            this.posterUrl = posterUrl ?: fallbackPoster
            this.description = description
            this.runTime = runtime
            this.score = ratingPercent
                ?.takeIf { it > 0 }
                ?.div(10.0)
                ?.let { Score.from(it.toString(), 10) }
        }
    }
}

internal fun StreamCenter.buildStreamCenterEpisodePayload(
    tmdbUrl: String,
    streamingCommunity: StreamingCommunityPlaybackData? = null,
    stremioContext: StreamCenterStremioPlaybackContext? = null,
    torrentContext: StreamCenterTorrentPlaybackContext? = null,
): String {
    if (streamingCommunity == null && stremioContext == null && torrentContext == null) {
        return tmdbUrl
    }
    return StreamCenterPlaybackData(
        tmdbUrl = tmdbUrl.takeIf(String::isNotBlank),
        streamingCommunity = streamingCommunity,
        stremio = stremioContext,
        torrent = torrentContext,
    ).toJson()
}
