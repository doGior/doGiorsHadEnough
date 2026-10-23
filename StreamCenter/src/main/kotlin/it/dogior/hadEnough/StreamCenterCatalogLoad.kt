package it.dogior.hadEnough

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal suspend fun StreamCenter.loadStremioCatalogMedia(
    url: String,
    definition: StreamCenterCatalogDefinition? = catalogDefinition,
    client: StreamCenterStremioCatalog? = stremioCatalogClient,
): LoadResponse {
    val media = client?.media(url)
        ?: throw IllegalStateException("Elemento del catalogo Stremio non disponibile")
    val type = stremioCatalogTvType(media.type)
        ?: throw IllegalArgumentException("Tipo Stremio non supportato")
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = when (type) {
            TvType.Movie -> listOf("movie")
            TvType.Anime -> listOf("series", "anime")
            TvType.Live -> listOf("tv", "channel")
            else -> listOf("series")
        },
        stremioId = media.id,
        imdbId = media.imdbId,
        tmdbId = media.tmdbId,
        anilistId = media.anilistId,
        malId = media.malId,
        kitsuId = media.kitsuId,
        catalogAddonKey = definition?.stremioAddon?.key,
    )
    val tmdbEnglishTitle = when (type) {
        TvType.Movie -> resolveTmdbEnglishTitle(media.tmdbId, isMovie = true)
        TvType.TvSeries -> resolveTmdbEnglishTitle(media.tmdbId, isMovie = false)
        else -> null
    }
    val torrentContext = when (type) {
        TvType.Live -> null
        TvType.Anime -> animeTorrentPlaybackContext(
            titles = listOf(media.name),
            year = media.year,
            isMovie = false,
            tabName = media.name,
            anilistId = media.anilistId,
            malId = media.malId,
            kitsuId = media.kitsuId,
            imdbId = media.imdbId,
        )
        else -> torrentPlaybackContext(
            titles = listOf(media.name),
            englishTitle = tmdbEnglishTitle,
            year = media.year,
            isAnime = false,
            isMovie = type == TvType.Movie,
            imdbId = media.imdbId,
        )
    }
    val catalogLogo = when (type) {
        TvType.Anime -> resolveTmdbLogo(
            isMovie = false,
            tmdbId = media.tmdbId?.toIntOrNull(),
            isAnime = true,
        )
        TvType.Movie, TvType.TvSeries -> resolveTmdbLogo(type == TvType.Movie, media.tmdbId?.toIntOrNull())
        else -> null
    }
    val applyMetadata: LoadResponse.() -> Unit = {
        apiName = this@loadStremioCatalogMedia.name
        posterUrl = media.posterUrl
        logoUrl = catalogLogo
        backgroundPosterUrl = media.backgroundUrl
        plot = media.description
        tags = media.genres
        year = media.year
        addScore(media.score?.let { value -> Score.from(value, 10) })
        addStreamCenterTrackingIds(
            StreamCenterTrackingIds(
                tmdb = media.tmdbId,
                imdb = media.imdbId,
                anilist = media.anilistId,
                mal = media.malId,
                kitsu = media.kitsuId,
            ),
        )
    }
    val response = when (type) {
        TvType.Movie -> newMovieLoadResponse(
            media.name,
            url,
            TvType.Movie,
            dataUrl = StreamCenterPlaybackData(
                stremio = stremioContext,
                torrent = torrentContext,
            ).toJson(),
        ) {
            applyMetadata()
        }
        TvType.Live -> newLiveStreamLoadResponse(
            media.name,
            url,
            StreamCenterPlaybackData(stremio = stremioContext).toJson(),
        ) {
            applyMetadata()
        }
        TvType.Anime -> {
            val episodes = buildStremioCatalogEpisodes(media, stremioContext, torrentContext)
            check(episodes.isNotEmpty()) { "L'add-on non fornisce gli episodi per questo anime" }
            newAnimeLoadResponse(media.name, url, TvType.Anime) {
                applyMetadata()
                addEpisodes(DubStatus.Subbed, episodes)
                addSeasonNames(buildAnimeSeasonData(episodes))
            }
        }
        else -> {
            val episodes = buildStremioCatalogEpisodes(media, stremioContext, torrentContext)
            check(episodes.isNotEmpty()) { "L'add-on non fornisce gli episodi per questa serie" }
            newTvSeriesLoadResponse(media.name, url, TvType.TvSeries, episodes) {
                applyMetadata()
                addSeasonNames(buildAnimeSeasonData(episodes))
            }
        }
    }
    val addonSource = definition?.stremioAddon?.name
        ?.let { "Add-on Stremio: $it" }
        ?: "Add-on Stremio"
    val playbackSources = (
        listOf(addonSource, "StreamCenter (contesto Stremio)") +
            torrentPlaybackProvenance(torrentContext)
        ).distinct()
    return response.withCardProvenance(
        defaultSource = addonSource,
        fieldSources = mapOf(
            "tipo_contenuto" to listOf(addonSource, "StreamCenter (conversione tipo)"),
            "id_sincronizzazione" to listOf(addonSource),
            "stagioni" to listOf(addonSource, "StreamCenter (raggruppamento episodi)"),
            "episodi" to listOf(addonSource),
            "episodi.dati_riproduzione" to playbackSources,
            "dati_riproduzione" to playbackSources,
        ),
    )
}

internal fun StreamCenter.buildStremioCatalogEpisodes(
    media: StreamCenterStremioCatalogItem,
    stremioContext: StreamCenterStremioPlaybackContext,
    torrentContext: StreamCenterTorrentPlaybackContext?,
): List<Episode> {
    return media.videos
        .distinctBy { video -> video.id }
        .mapIndexed { index, video ->
            val season = video.season ?: 1
            val episode = video.episode ?: index + 1
            newEpisode(
                StreamCenterPlaybackData(
                    stremio = stremioContext.copy(
                        stremioVideoId = video.id,
                        season = season,
                        episode = episode,
                    ),
                    torrent = torrentContext?.forEpisode(season, episode),
                ).toJson(),
            ) {
                name = video.title ?: "Episodio $episode"
                this.season = season
                this.episode = episode
                posterUrl = video.posterUrl ?: media.posterUrl
                description = video.description
            }
        }
        .sortedWith(compareBy({ episode -> episode.season ?: 1 }, { episode -> episode.episode ?: 0 }))
}

internal fun StreamCenter.stremioCatalogTvType(type: String): TvType? = when (type.lowercase(Locale.ROOT)) {
    "movie" -> TvType.Movie
    "series" -> TvType.TvSeries
    "anime" -> TvType.Anime
    "tv", "channel" -> TvType.Live
    else -> null
}

internal suspend fun StreamCenter.loadAniListCatalogMedia(url: String): LoadResponse {
    val requestedId = aniListCatalog.mediaId(url)
        ?: throw IllegalArgumentException("Identificativo AniList non valido")
    val metadata = aniListMetadataClient.fetchMetadata(
        anilistId = requestedId,
        malId = null,
        forceFullMetadata = true,
    ) ?: error("Metadati AniList non trovati")
    val anilistId = metadata.anilistId
    val malId = metadata.malId
    val isMovie = metadata.format.equals("MOVIE", ignoreCase = true)
    val kitsuId = runCatching {
        kitsuMetadataClient.resolveAnimeId(malId, anilistId)
    }.getOrNull()
    val syncIds = listOf(
        AnimeSyncIds(
            anilistId = anilistId,
            malId = malId,
            kitsuId = kitsuId,
        )
    )
    val resolvedSources = resolveAnimePlaybackSources(
        metadata = metadata.toStreamCenterMetadata(),
        matchMetadata = AnilistMetadata(
            title = metadata.title,
            titleCandidates = metadata.titleCandidates,
        ),
        syncIds = syncIds,
        aniZipIds = anilistId to malId,
        includeAniZip = !performanceMode || shouldResolveAnimeTorrentMetadata(),
    )
    val animeUnitySources = resolvedSources.animeUnitySources
    val animeWorldSources = resolvedSources.animeWorldSources
    val animeSaturnSources = resolvedSources.animeSaturnSources
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
        anilistId = anilistId,
        malId = malId,
        kitsuId = kitsuId,
    )
    val title = aniListCatalog.preferredTitle(metadata)
    val torrentContext = animeTorrentPlaybackContext(
        titles = listOf(
            title,
            metadata.title,
            metadata.titleRomaji,
            metadata.titleEnglish,
            metadata.titleNative,
            metadata.originalTitle,
        ) + metadata.titleCandidates,
        year = metadata.year,
        isMovie = isMovie,
        tabName = title,
        aniZipCatalog = resolvedSources.aniZipCatalog,
        anilistId = anilistId,
        malId = malId,
        kitsuId = kitsuId,
        knownAniListTitle = metadata.titleNative,
    )
    val sourceUrl = "https://anilist.co/anime/$anilistId"
    val tmdbMetadata = resolveTmdbAnimeMetadata(
        anilistId = anilistId,
        malId = malId,
        titleCandidates = listOfNotNull(metadata.titleNative, metadata.titleEnglish, metadata.titleRomaji, metadata.title) + metadata.titleCandidates,
        year = metadata.year,
        sourceEpisodeNumbers = animeSourceEpisodeNumbers(
            animeUnitySources,
            animeWorldSources,
            animeSaturnSources,
        ),
        aniZipCatalog = resolvedSources.aniZipCatalog,
        isMovie = isMovie,
    )
    val recommendations = buildBaseCatalogAnimeUnityRecommendations(
        sources = animeUnitySources,
        anilistId = anilistId,
        malId = malId,
    )
    val animeType = if (isMovie) {
        TvType.AnimeMovie
    } else {
        when (metadata.format?.uppercase(Locale.ROOT)) {
            "OVA", "ONA", "SPECIAL", "MUSIC" -> TvType.OVA
            else -> TvType.Anime
        }
    }
    val response = renderAnimeResponse(
        isMovie = isMovie,
        animeType = animeType,
        fallbackTitle = title,
        fallbackShowStatus = anilistShowStatus(metadata.status),
        anilistReleaseInfo = metadata.releaseInfo,
        titleCandidates = metadata.titleCandidates,
        fallbackTrailerUrl = metadata.trailerUrl,
        romajiTitle = metadata.titleRomaji,
        englishTitle = metadata.titleEnglish,
        nativeTitle = metadata.titleNative,
        sourceUrl = sourceUrl,
        fallbackPoster = metadata.poster,
        fallbackBackground = metadata.background,
        tmdb = tmdbMetadata,
        animeUnitySources = animeUnitySources,
        animeWorldSources = animeWorldSources,
        animeSaturnSources = animeSaturnSources,
        recommendations = recommendations,
        actors = metadata.characters,
        studios = metadata.studios,
        stremioContext = stremioContext,
        torrentContext = torrentContext,
        trackingIds = StreamCenterTrackingIds(
            anilist = anilistId,
            mal = malId,
            kitsu = kitsuId,
        ),
        showTrackingAsTags = catalogDefinition == null &&
            StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
    )
    val playbackSourceNames = buildList {
        if (animeUnitySources.isNotEmpty()) add("AnimeUnity")
        if (animeWorldSources.isNotEmpty()) add("AnimeWorld")
        if (animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
    }
    return response.withCardProvenance(
        defaultSource = "TMDB",
        fieldSources = animeCardProvenance(
            playbackSources = playbackSourceNames,
            trackingSources = listOf("TMDB", "AniList", "MyAnimeList", "Kitsu"),
            torrentContext = torrentContext,
        ),
    )
}

internal suspend fun StreamCenter.loadMyAnimeListMedia(url: String): LoadResponse {
    val media = myAnimeListCatalog.media(url)
    val isMovie = media.type == TvType.AnimeMovie
    val resolvedKitsuId = runCatching {
        kitsuMetadataClient.resolveAnimeId(media.id, null)
    }.getOrNull()
    val syncIds = listOf(
        AnimeSyncIds(
            anilistId = null,
            malId = media.id,
            kitsuId = resolvedKitsuId,
        )
    )
    val sourceMetadata = StreamCenterMetadata(
        title = media.title,
        originalTitle = media.japaneseTitle,
        plot = media.synopsis,
        poster = media.posterUrl,
        background = null,
        tags = media.genres + media.themes + media.demographics,
        year = media.year,
        tmdbId = null,
        score = media.score,
        people = media.characters,
        contentRating = media.contentRating,
        showStatus = media.status,
        comingSoon = media.comingSoon,
        duration = media.duration,
        trailerUrl = media.trailerUrl,
    )
    val matchMetadata = AnilistMetadata(
        title = media.title,
        titleCandidates = media.titleCandidates,
    )
    val resolvedSources = resolveAnimePlaybackSources(
        metadata = sourceMetadata,
        matchMetadata = matchMetadata,
        syncIds = syncIds,
        aniZipIds = null to media.id,
        includeAniZip = !performanceMode || shouldResolveAnimeTorrentMetadata(),
    )
    val animeUnitySources = resolvedSources.animeUnitySources
    val animeWorldSources = resolvedSources.animeWorldSources
    val animeSaturnSources = resolvedSources.animeSaturnSources
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
        malId = media.id,
        kitsuId = resolvedKitsuId,
    )
    val torrentContext = animeTorrentPlaybackContext(
        titles = media.titleCandidates,
        year = media.year,
        isMovie = isMovie,
        tabName = media.title,
        aniZipCatalog = resolvedSources.aniZipCatalog,
        malId = media.id,
        kitsuId = resolvedKitsuId,
        knownMyAnimeListTitle = media.japaneseTitle,
    )
    val tmdbMetadata = resolveTmdbAnimeMetadata(
        anilistId = resolvedSources.aniZipCatalog.anilistId,
        malId = media.id,
        titleCandidates = media.titleCandidates,
        year = media.year,
        sourceEpisodeNumbers = animeSourceEpisodeNumbers(
            animeUnitySources,
            animeWorldSources,
            animeSaturnSources,
        ),
        aniZipCatalog = resolvedSources.aniZipCatalog,
        isMovie = isMovie,
    )
    val recommendations = buildBaseCatalogAnimeUnityRecommendations(
        sources = animeUnitySources,
        anilistId = resolvedSources.aniZipCatalog.anilistId,
        malId = media.id,
    )
    val response = renderAnimeResponse(
        isMovie = isMovie,
        animeType = media.type,
        fallbackTitle = media.title,
        fallbackShowStatus = media.status,
        titleCandidates = media.titleCandidates,
        fallbackTrailerUrl = media.trailerUrl,
        romajiTitle = media.title,
        englishTitle = media.englishTitle,
        nativeTitle = media.japaneseTitle,
        sourceUrl = media.url,
        fallbackPoster = media.posterUrl,
        tmdb = tmdbMetadata,
        animeUnitySources = animeUnitySources,
        animeWorldSources = animeWorldSources,
        animeSaturnSources = animeSaturnSources,
        recommendations = recommendations,
        actors = completeAnimeActors(media.characters, null, media.id),
        studios = media.studios,
        stremioContext = stremioContext,
        torrentContext = torrentContext,
        trackingIds = StreamCenterTrackingIds(
            mal = media.id,
            kitsu = resolvedKitsuId,
        ),
        showTrackingAsTags = catalogDefinition == null &&
            StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
    )
    val playbackSourceNames = buildList {
        if (animeUnitySources.isNotEmpty()) add("AnimeUnity")
        if (animeWorldSources.isNotEmpty()) add("AnimeWorld")
        if (animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
    }
    return response.withCardProvenance(
        defaultSource = "TMDB",
        fieldSources = animeCardProvenance(
            playbackSources = playbackSourceNames,
            trackingSources = listOf("TMDB", "MyAnimeList", "Kitsu"),
            torrentContext = torrentContext,
        ),
    )
}

internal suspend fun StreamCenter.loadKitsuMedia(url: String): LoadResponse {
    val media = kitsuCatalog.media(url)
    val title = kitsuCatalog.preferredTitle(media)
    val isMovie = media.type == TvType.AnimeMovie
    val syncIds = listOf(
        AnimeSyncIds(
            anilistId = media.anilistId,
            malId = media.malId,
            kitsuId = media.id,
        ),
    )
    val sourceMetadata = StreamCenterMetadata(
        title = media.title,
        originalTitle = media.nativeTitle,
        plot = media.synopsis,
        poster = media.posterUrl,
        background = media.backgroundUrl,
        tags = media.categories,
        year = media.year,
        tmdbId = null,
        score = media.score,
        people = media.characters,
        contentRating = media.contentRating,
        showStatus = media.showStatus,
        comingSoon = media.comingSoon,
        duration = media.duration,
        trailerUrl = media.trailerUrl,
    )
    val matchMetadata = AnilistMetadata(
        title = media.title,
        titleCandidates = media.titleCandidates,
    )
    val resolvedSources = resolveAnimePlaybackSources(
        metadata = sourceMetadata,
        matchMetadata = matchMetadata,
        syncIds = syncIds,
        aniZipIds = media.anilistId to media.malId,
        includeAniZip = !performanceMode || shouldResolveAnimeTorrentMetadata(),
    )
    val animeUnitySources = resolvedSources.animeUnitySources
    val animeWorldSources = resolvedSources.animeWorldSources
    val animeSaturnSources = resolvedSources.animeSaturnSources
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
        anilistId = media.anilistId,
        malId = media.malId,
        kitsuId = media.id,
    )
    val torrentContext = animeTorrentPlaybackContext(
        titles = listOf(title) + media.titleCandidates,
        year = media.year,
        isMovie = isMovie,
        tabName = title,
        aniZipCatalog = resolvedSources.aniZipCatalog,
        anilistId = media.anilistId,
        malId = media.malId,
        kitsuId = media.id,
        knownKitsuTitle = media.nativeTitle,
    )
    val tmdbMetadata = resolveTmdbAnimeMetadata(
        anilistId = media.anilistId ?: resolvedSources.aniZipCatalog.anilistId,
        malId = media.malId,
        titleCandidates = media.titleCandidates,
        year = media.year,
        sourceEpisodeNumbers = animeSourceEpisodeNumbers(
            animeUnitySources,
            animeWorldSources,
            animeSaturnSources,
        ),
        aniZipCatalog = resolvedSources.aniZipCatalog,
        isMovie = isMovie,
    )
    val recommendations = buildBaseCatalogAnimeUnityRecommendations(
        sources = animeUnitySources,
        anilistId = media.anilistId ?: resolvedSources.aniZipCatalog.anilistId,
        malId = media.malId,
    )
    val response = renderAnimeResponse(
        isMovie = isMovie,
        animeType = media.type,
        fallbackTitle = title,
        fallbackShowStatus = media.showStatus,
        titleCandidates = media.titleCandidates,
        fallbackTrailerUrl = media.trailerUrl,
        romajiTitle = media.romajiTitle,
        englishTitle = media.englishTitle,
        nativeTitle = media.nativeTitle,
        sourceUrl = media.url,
        fallbackPoster = media.posterUrl,
        fallbackBackground = media.backgroundUrl,
        tmdb = tmdbMetadata,
        animeUnitySources = animeUnitySources,
        animeWorldSources = animeWorldSources,
        animeSaturnSources = animeSaturnSources,
        recommendations = recommendations,
        actors = completeAnimeActors(media.characters, media.anilistId, media.malId),
        studios = emptyList(),
        stremioContext = stremioContext,
        torrentContext = torrentContext,
        trackingIds = StreamCenterTrackingIds(
            anilist = media.anilistId,
            mal = media.malId,
            kitsu = media.id,
        ),
        showTrackingAsTags = catalogDefinition == null &&
            StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
    )
    val playbackSourceNames = buildList {
        if (animeUnitySources.isNotEmpty()) add("AnimeUnity")
        if (animeWorldSources.isNotEmpty()) add("AnimeWorld")
        if (animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
    }
    return response.withCardProvenance(
        defaultSource = "TMDB",
        fieldSources = animeCardProvenance(
            playbackSources = playbackSourceNames,
            trackingSources = listOf("TMDB", "Kitsu", "AniList", "MyAnimeList"),
            torrentContext = torrentContext,
        ),
    )
}

internal suspend fun StreamCenter.loadSimklMedia(url: String): LoadResponse {
    val media = simklCatalog.media(url)
    val isAnime = media.category == "anime"
    val isMovie = media.category == "movies" || media.type == TvType.AnimeMovie
    val syncIds = listOf(
        AnimeSyncIds(
            anilistId = media.ids.anilist,
            malId = media.ids.mal,
            kitsuId = media.ids.kitsu,
        ),
    )
    val sourceMetadata = StreamCenterMetadata(
        title = media.title,
        originalTitle = media.englishTitle,
        plot = media.plot,
        poster = media.posterUrl,
        background = media.backgroundUrl,
        tags = media.tags,
        year = media.year,
        tmdbId = media.ids.tmdb,
        score = media.score,
        people = media.actors,
        contentRating = media.contentRating,
        showStatus = media.showStatus,
        comingSoon = media.comingSoon,
        duration = media.runtime,
        trailerUrl = media.trailerUrl,
    )
    val (simklEpisodes, resolvedSources) = coroutineScope {
        val episodesDeferred: kotlinx.coroutines.Deferred<List<StreamCenterSimklEpisode>>? =
            if (isMovie) null else async(Dispatchers.IO) {
                runCatching { simklCatalog.episodes(media) }.getOrDefault(emptyList())
            }
        val sourcesDeferred = if (isAnime) async(Dispatchers.IO) {
            resolveAnimePlaybackSources(
                metadata = sourceMetadata,
                matchMetadata = AnilistMetadata(media.title, media.titleCandidates),
                syncIds = syncIds,
                aniZipIds = media.ids.anilist to media.ids.mal,
                includeAniZip = !performanceMode || shouldResolveAnimeTorrentMetadata(),
            )
        } else {
            null
        }
        episodesDeferred?.await().orEmpty() to (sourcesDeferred?.await() ?: ResolvedLoadSources())
    }
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = when {
            isAnime && isMovie -> listOf("movie", "anime")
            isAnime -> listOf("series", "anime")
            isMovie -> listOf("movie")
            else -> listOf("series")
        },
        imdbId = media.ids.imdb,
        tmdbId = media.ids.tmdb,
        anilistId = media.ids.anilist,
        malId = media.ids.mal,
        kitsuId = media.ids.kitsu,
        simklId = media.ids.simkl,
    )
    val tmdbEnglishTitle = if (isAnime) {
        null
    } else {
        resolveTmdbEnglishTitle(media.ids.tmdb, isMovie)
    }
    val torrentContext = if (isAnime) {
        animeTorrentPlaybackContext(
            titles = media.titleCandidates,
            year = media.year,
            isMovie = isMovie,
            tabName = media.title,
            aniZipCatalog = resolvedSources.aniZipCatalog,
            anilistId = media.ids.anilist,
            malId = media.ids.mal,
            kitsuId = media.ids.kitsu,
            imdbId = media.ids.imdb,
        )
    } else {
        torrentPlaybackContext(
            titles = media.titleCandidates,
            englishTitle = tmdbEnglishTitle ?: media.englishTitle,
            year = media.year,
            isAnime = false,
            isMovie = isMovie,
            imdbId = media.ids.imdb,
        )
    }
    if (isAnime) {
        val tmdbMetadata = resolveTmdbAnimeMetadata(
            anilistId = media.ids.anilist ?: resolvedSources.aniZipCatalog.anilistId,
            malId = media.ids.mal,
            titleCandidates = media.titleCandidates,
            year = media.year,
            sourceEpisodeNumbers = animeSourceEpisodeNumbers(
                resolvedSources.animeUnitySources,
                resolvedSources.animeWorldSources,
                resolvedSources.animeSaturnSources,
            ),
            aniZipCatalog = resolvedSources.aniZipCatalog,
            isMovie = isMovie,
        )
        val animeRecommendations = buildBaseCatalogAnimeUnityRecommendations(
            sources = resolvedSources.animeUnitySources,
            anilistId = media.ids.anilist ?: resolvedSources.aniZipCatalog.anilistId,
            malId = media.ids.mal,
        )
        val response = renderAnimeResponse(
            isMovie = isMovie,
            animeType = media.type,
            fallbackTitle = media.title,
            fallbackShowStatus = media.showStatus,
            titleCandidates = media.titleCandidates,
            fallbackTrailerUrl = media.trailerUrl,
            englishTitle = media.englishTitle,
            sourceUrl = media.url,
            fallbackPoster = media.posterUrl,
            fallbackBackground = media.backgroundUrl,
            tmdb = tmdbMetadata,
            animeUnitySources = resolvedSources.animeUnitySources,
            animeWorldSources = resolvedSources.animeWorldSources,
            animeSaturnSources = resolvedSources.animeSaturnSources,
            recommendations = animeRecommendations,
            actors = media.actors,
            studios = media.studios,
            stremioContext = stremioContext,
            torrentContext = torrentContext,
            trackingIds = media.trackingIds(),
            showTrackingAsTags = catalogDefinition == null &&
                StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
        )
        val playbackSourceNames = buildList {
            if (resolvedSources.animeUnitySources.isNotEmpty()) add("AnimeUnity")
            if (resolvedSources.animeWorldSources.isNotEmpty()) add("AnimeWorld")
            if (resolvedSources.animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
        }
        return response.withCardProvenance(
            defaultSource = "TMDB",
            fieldSources = animeCardProvenance(
                playbackSources = playbackSourceNames,
                trackingSources = listOf("TMDB", "Simkl", "IMDb", "AniList", "MyAnimeList", "Kitsu"),
                torrentContext = torrentContext,
            ),
        )
    }
    val recommendations = media.recommendations.map { recommendation ->
        when (recommendation.type) {
            TvType.Movie -> newMovieSearchResponse(recommendation.title, recommendation.url, recommendation.type) {
                posterUrl = recommendation.posterUrl
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(
                recommendation.title,
                recommendation.url,
                recommendation.type,
            ) {
                posterUrl = recommendation.posterUrl
            }
            else -> newAnimeSearchResponse(recommendation.title, recommendation.url, recommendation.type) {
                posterUrl = recommendation.posterUrl
            }
        }
    }
    val tags = (media.tags + media.studios.map { "Studio: $it" })
        .distinctBy { it.lowercase(Locale.ROOT) }
    val mediaLogo = resolveTmdbLogo(isMovie, media.ids.tmdb?.toIntOrNull())

    if (isMovie) {
        val playbackData = StreamCenterPlaybackData(
            animeUnity = resolvedSources.animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
            animeWorld = resolvedSources.animeWorldSources.flatMap { it.firstPlaybacks() },
            animeSaturn = resolvedSources.animeSaturnSources.flatMap { it.firstPlaybacks() },
            stremio = stremioContext,
            torrent = torrentContext,
        )
        val response = newMovieLoadResponse(media.title, media.url, media.type, dataUrl = playbackData.toJson()) {
            apiName = this@loadSimklMedia.name
            posterUrl = media.posterUrl
            logoUrl = mediaLogo
            backgroundPosterUrl = media.backgroundUrl
            plot = media.plot
            this.tags = tags
            year = media.year
            duration = media.runtime
            contentRating = media.contentRating
            actors = media.actors
            this.recommendations = recommendations
            comingSoon = media.comingSoon
            addStreamCenterTrackingIds(media.trackingIds())
            media.trailerUrl?.let { addTrailer(it) }
            addScore(media.score)
        }
        val playbackSourceNames = buildList {
            if (resolvedSources.animeUnitySources.isNotEmpty()) add("AnimeUnity")
            if (resolvedSources.animeWorldSources.isNotEmpty()) add("AnimeWorld")
            if (resolvedSources.animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
            }
        return response.withCardProvenance(
            defaultSource = "Simkl",
            fieldSources = animeProviderCardSources(
                metadataSource = "Simkl",
                playbackSources = playbackSourceNames,
                episodeMetadataSources = listOf("Simkl"),
                trackingSources = listOf(
                    "Simkl",
                    "TMDB",
                    "IMDb",
                    "AniList",
                    "MyAnimeList",
                    "Kitsu",
                ),
                torrentContext = torrentContext,
            ),
        )
    }

    val metadataEpisodes = simklEpisodes.map { episode ->
        newEpisode("") {
            name = episode.title ?: "Episodio ${episode.episode}"
            season = episode.season
            this.episode = episode.episode
            posterUrl = episode.posterUrl
            description = episode.description
            score = episode.score
            runTime = episode.runTime
            episode.date?.let { addDate(it) }
        }
    }
    val episodes = metadataEpisodes.map { episode ->
        newEpisode(
            StreamCenterPlaybackData(
                stremio = stremioContext.copy(
                    season = episode.season,
                    episode = episode.episode,
                ),
                torrent = torrentContext?.forEpisode(episode.season, episode.episode),
            ).toJson(),
        ) {
            name = episode.name
            season = episode.season
            this.episode = episode.episode
            posterUrl = episode.posterUrl ?: media.posterUrl
            description = episode.description
            score = episode.score
            runTime = episode.runTime
            episode.date?.let { date = it }
        }
    }
    val response = newTvSeriesLoadResponse(media.title, media.url, TvType.TvSeries, episodes) {
        apiName = this@loadSimklMedia.name
        posterUrl = media.posterUrl
        logoUrl = mediaLogo
        backgroundPosterUrl = media.backgroundUrl
        plot = media.plot
        this.tags = tags
        year = media.year
        duration = media.runtime
        contentRating = media.contentRating
        actors = media.actors
        this.recommendations = recommendations
        showStatus = media.showStatus
        comingSoon = media.comingSoon
        addSeasonNames(buildAnimeSeasonData(episodes))
        addStreamCenterTrackingIds(media.trackingIds())
        media.trailerUrl?.let { addTrailer(it) }
        addScore(media.score)
    }
    val playbackSourceNames = buildList {
        if (resolvedSources.animeUnitySources.isNotEmpty()) add("AnimeUnity")
        if (resolvedSources.animeWorldSources.isNotEmpty()) add("AnimeWorld")
        if (resolvedSources.animeSaturnSources.isNotEmpty()) add("AnimeSaturn")
    }
    return response.withCardProvenance(
        defaultSource = "Simkl",
        fieldSources = animeProviderCardSources(
            metadataSource = "Simkl",
            playbackSources = playbackSourceNames,
            episodeMetadataSources = listOf("Simkl"),
            trackingSources = listOf(
                "Simkl",
                "TMDB",
                "IMDb",
                "AniList",
                "MyAnimeList",
                "Kitsu",
            ),
            torrentContext = torrentContext,
        ),
    )
}
