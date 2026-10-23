package it.dogior.hadEnough

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.StreamCenter.Companion.ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS
import it.dogior.hadEnough.StreamCenter.Companion.STREMIO_KITSU_RESOLUTION_TIMEOUT_MS
import it.dogior.hadEnough.StreamCenter.Companion.TMDB_ANIME_EPISODE_METADATA_TIMEOUT_MS
import it.dogior.hadEnough.anime.metadata.AnimeDisplayTitles
import it.dogior.hadEnough.anime.metadata.containsJapaneseTitleScript
import it.dogior.hadEnough.anime.metadata.AnimeSeasonInfo
import it.dogior.hadEnough.anime.metadata.TmdbArtwork
import it.dogior.hadEnough.anime.metadata.buildAnimeEpisodes
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
import it.dogior.hadEnough.tmdb.TmdbApiEpisode
import it.dogior.hadEnough.tmdb.TmdbApiMapping
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.mapChunkedParallel
import it.dogior.hadEnough.util.normalizeTrailerUrl
import it.dogior.hadEnough.util.parseWholeAnimeEpisodeNumber
import it.dogior.hadEnough.util.runCatchingCancellable
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal fun StreamCenter.mergeAnimeActors(existing: List<ActorData>, resolved: List<ActorData>): List<ActorData> {
    if (existing.isEmpty()) return resolved
    fun nameKey(name: String): String = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank).sorted().joinToString(" ")
    val byName = resolved.groupBy { nameKey(it.actor.name) }
    return existing.map { actor ->
        val match = byName[nameKey(actor.actor.name)]?.singleOrNull()
        if (actor.voiceActor != null || match?.voiceActor == null) actor
        else actor.copy(
            actor = Actor(actor.actor.name, actor.actor.image ?: match.actor.image),
            role = actor.role ?: match.role,
            voiceActor = match.voiceActor,
        )
    }
}

internal suspend fun StreamCenter.completeAnimeActors(actors: List<ActorData>, anilistId: Int?, malId: Int?): List<ActorData> {
    if (performanceMode || actors.isNotEmpty() && actors.all { it.voiceActor != null }) return actors
    val resolved = runCatchingCancellable {
        withTimeoutOrNull(4_000L) { aniListMetadataClient.fetchCharacters(anilistId, malId) }
    }.getOrNull() ?: return actors
    return mergeAnimeActors(actors, resolved)
}

internal suspend fun StreamCenter.rebuildAnimeFromCache(entry: CachedMediaEntry): LoadResponse {
    val trailerUrl = cachedTrailer(entry)
    val cachedActors = entry.actors.map(CachedActor::toActorData)
    val animeType = entry.animeType?.let { runCatching { TvType.valueOf(it) }.getOrNull() } ?: TvType.Anime
    val applyCommon: suspend LoadResponse.() -> Unit = {
        apiName = this@rebuildAnimeFromCache.name
        posterUrl = entry.posterUrl
        backgroundPosterUrl = entry.backgroundPosterUrl
        logoUrl = entry.logoUrl
        plot = entry.plot
        tags = entry.tags.map(::normalizeTmdbStatusTag)
        year = entry.year
        duration = entry.duration
        contentRating = entry.contentRating
        comingSoon = entry.comingSoon
        actors = cachedActors
        recommendations = entry.recommendations.mapNotNull(this@rebuildAnimeFromCache::rebuildSearchItemFromCache)
        addStreamCenterTrackingIds(
            entry.trackingIds ?: StreamCenterTrackingIds(),
            showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
            visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
        )
        trailerUrl?.let { addTrailer(it) }
        addScore(entry.score)
    }
    return if (entry.animeMovie) {
        newMovieLoadResponse(entry.title, entry.url, animeType, dataUrl = entry.movieDataUrl ?: entry.url) {
            applyCommon()
        }
    } else {
        newAnimeLoadResponse(entry.title, entry.url, animeType) {
            applyCommon()
            showStatus = entry.showStatus?.let { runCatching { ShowStatus.valueOf(it) }.getOrNull() }
            applyAnimeCatalogTitles(
                englishTitle = entry.englishTitle,
                nativeTitle = entry.nativeTitle,
                alternativeTitles = entry.alternativeTitles,
            )
            addEpisodes(DubStatus.Subbed, entry.episodes.map(this@rebuildAnimeFromCache::rebuildEpisodeFromCache))
            if (entry.seasons.isNotEmpty()) {
                addSeasonNames(
                    entry.seasons.map { season ->
                        SeasonData(season = season.season, name = season.name, displaySeason = season.displaySeason)
                    },
                )
            }
        }
    }
}

internal fun StreamCenter.isAnilistOnlyUrl(url: String): Boolean {
    return url.contains(anilistOnlyPath) || Regex("""anilist\.co/anime/\d+""").containsMatchIn(url)
}

internal fun StreamCenter.extractAnilistIdFromText(text: String): Int? {
    return Regex("""(?:anilist\.co/anime/|/anilist/)(\d+)""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun StreamCenter.markMalOnlyUrl(malId: Int): String {
    val params = listOf(animeMarker, "$animeMalParam=$malId")
    return "https://myanimelist.net/anime/$malId?${params.joinToString("&")}"
}

internal fun StreamCenter.isMalOnlyUrl(url: String): Boolean {
    return url.contains(malOnlyPath) || Regex("""myanimelist\.net/anime/\d+""").containsMatchIn(url)
}

internal fun StreamCenter.extractMalIdFromText(text: String): Int? {
    return Regex("""(?:myanimelist\.net/anime/|/mal/)(\d+)""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal suspend fun StreamCenter.loadAnilistMedia(
    anilistId: Int?,
    malId: Int?,
    cacheable: Boolean = false,
): LoadResponse {
    val cacheKey = if (cacheable && StreamCenterMediaCache.isEnabled(sharedPref)) {
        StreamCenterMediaCache.animeKey(anilistId, malId)
    } else {
        null
    }
    if (cacheKey != null) {
        StreamCenterMediaCache.readByKey(cacheKey)
            ?.takeIf { it.animeTitlePreference == StreamCenterPlugin.getAnimeCardTitle(sharedPref) }
            ?.let { cached ->
            StreamCenterLogger.logTab(
                tabName = cached.title,
                action = "Scheda anime servita dalla cache",
                metadata = mapOf(
                    "chiave" to cached.key,
                    "episodi" to cached.episodes.size,
                    "scadenza_ms" to cached.expiresAtMillis,
                ),
            )
            return rebuildAnimeFromCache(cached)
        }
    }
    return try {
        resolveAnilistLoadResponse(anilistId, malId, cacheKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        StreamCenterLogger.logTabError(
            tabName = "AniList ${anilistId ?: "-"} · MAL ${malId ?: "-"}",
            action = "Caricamento scheda anime degradato",
            throwable = error,
        )
        minimalAnilistLoadResponse(anilistId, malId)
    }
}

internal suspend fun StreamCenter.minimalAnilistLoadResponse(anilistId: Int?, malId: Int?): LoadResponse {
    val url = markAnilistUrl(anilistId ?: 0, malId)
    return newAnimeLoadResponse("Anime", url, TvType.Anime) {
        addStreamCenterTrackingIds(StreamCenterTrackingIds(anilist = anilistId, mal = malId))
    }
}

internal fun StreamCenter.fallbackAnilistMetadata(anilistId: Int?, malId: Int?): AnilistLoadMetadata =
    AnilistLoadMetadata(
        anilistId = anilistId ?: 0,
        malId = malId,
        title = "Anime",
        titleRomaji = null,
        titleEnglish = null,
        titleNative = null,
        titleCandidates = emptyList(),
        originalTitle = null,
        format = null,
        poster = null,
        background = null,
        description = null,
        score = null,
        year = null,
        duration = null,
        episodes = null,
        status = null,
        genres = emptyList(),
        tags = emptyList(),
        isAdult = false,
        trailerUrl = null,
        characters = emptyList(),
        studios = emptyList(),
        source = null,
        season = null,
        nextAiringEpisode = null,
        nextAiringAtSeconds = null,
    )

internal suspend fun StreamCenter.resolveAnilistLoadResponse(
    anilistId: Int?,
    malId: Int?,
    cacheKey: String? = null,
): LoadResponse {
    val metadata = aniListMetadataClient.fetchMetadata(anilistId, malId)
        ?: fallbackAnilistMetadata(anilistId, malId)
    StreamCenterLogger.logMetadata(
        tabName = metadata.title,
        source = "AniList",
        action = "Metadati AniList acquisiti",
        metadata = mapOf(
            "id_anilist_richiesto" to anilistId,
            "id_mal_richiesto" to malId,
            "id_anilist" to metadata.anilistId,
            "id_mal" to metadata.malId,
            "formato" to metadata.format,
            "episodi_dichiarati" to metadata.episodes,
            "modalita_prestazioni" to performanceMode,
            "titoli_alternativi" to metadata.titleCandidates.size,
            "personaggi" to metadata.characters.size,
        ),
    )
    val resolvedAnilistId = metadata.anilistId
    val resolvedMalId = metadata.malId
    val isMovie = metadata.format.equals("MOVIE", ignoreCase = true)
    val shouldResolveJapaneseTitle = shouldResolveTorrentJapaneseTitle()
    val shouldResolveKitsu = !performanceMode ||
        activeStremioResolversNeedKitsuId() ||
        trackingServiceIsConnected(SyncIdName.Kitsu)
    val resolvedKitsuId = if (!shouldResolveKitsu) {
        null
    } else {
        runCatching {
            if (performanceMode) {
                withTimeoutOrNull(STREMIO_KITSU_RESOLUTION_TIMEOUT_MS) {
                    kitsuMetadataClient.resolveAnimeId(resolvedMalId, resolvedAnilistId)
                }
            } else {
                kitsuMetadataClient.resolveAnimeId(resolvedMalId, resolvedAnilistId)
            }
        }.getOrNull()
    }
    StreamCenterLogger.logMetadata(
        tabName = metadata.title,
        source = "Kitsu",
        action = "Identificativo Kitsu risolto",
        metadata = mapOf(
            "risoluzione_richiesta" to shouldResolveKitsu,
            "id_anilist" to resolvedAnilistId,
            "id_mal" to resolvedMalId,
            "id_kitsu" to resolvedKitsuId,
            "timeout_modalita_prestazioni" to (performanceMode && shouldResolveKitsu),
        ),
        level = if (shouldResolveKitsu && resolvedKitsuId == null) {
            StreamCenterLogger.Level.WARNING
        } else {
            StreamCenterLogger.Level.INFO
        },
    )
    val sourceSyncIds = listOf(
        AnimeSyncIds(
            anilistId = resolvedAnilistId,
            malId = resolvedMalId,
            kitsuId = resolvedKitsuId,
        )
    )
    val streamCenterMetadata = metadata.toStreamCenterMetadata()
    val matchMetadata = AnilistMetadata(
        title = metadata.title,
        titleCandidates = metadata.titleCandidates,
    )
    val animeTitlePreference = StreamCenterPlugin.getAnimeCardTitle(sharedPref)
    val includeAniZip = shouldResolveAnimeTorrentMetadata() || !performanceMode
    val (resolvedSources, resolvedSimklId) = coroutineScope {
        val sourcesDeferred = async(Dispatchers.IO) {
            resolveAnimePlaybackSources(
                metadata = streamCenterMetadata,
                matchMetadata = matchMetadata,
                syncIds = sourceSyncIds,
                aniZipIds = resolvedAnilistId to resolvedMalId,
                includeAniZip = includeAniZip,
                ignoreSourceFailures = true,
            )
        }
        val simklIdDeferred = async(Dispatchers.IO) {
            runCatching {
                resolveSimklId(
                    mal = resolvedMalId,
                    anilist = resolvedAnilistId,
                    allowedCategories = setOf("anime"),
                )
            }.getOrNull()
        }
        sourcesDeferred.await() to simklIdDeferred.await()
    }
    val animeUnitySources = resolvedSources.animeUnitySources
    val animeWorldSources = resolvedSources.animeWorldSources
    val animeSaturnSources = resolvedSources.animeSaturnSources
    val aniZipCatalog = resolvedSources.aniZipCatalog
    val recommendations = buildBaseCatalogAnimeUnityRecommendations(
        sources = animeUnitySources,
        anilistId = resolvedAnilistId,
        malId = resolvedMalId,
    )
    val stremioContext = StreamCenterStremioPlaybackContext(
        contentTypes = if (isMovie) listOf("movie", "anime") else listOf("series", "anime"),
        anilistId = resolvedAnilistId,
        malId = resolvedMalId,
        kitsuId = sourceSyncIds.firstOrNull()?.kitsuId,
    )
    val cardTitle = when (animeTitlePreference) {
        StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY -> animeUnitySources
            .firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) }
        StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> metadata.titleRomaji
        StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> metadata.titleEnglish
        StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> metadata.titleNative
        else -> metadata.titleEnglish
            ?: metadata.titleRomaji
    }?.trim()?.takeIf(String::isNotBlank) ?: metadata.title
    val torrentContext = animeTorrentPlaybackContext(
        titles = listOf(
            cardTitle,
            metadata.title,
            metadata.titleRomaji,
            metadata.titleEnglish,
            metadata.titleNative,
            metadata.originalTitle,
        ) + metadata.titleCandidates,
        year = metadata.year,
        isMovie = isMovie,
        tabName = cardTitle,
        aniZipCatalog = aniZipCatalog,
        anilistId = resolvedAnilistId,
        malId = resolvedMalId,
        kitsuId = resolvedKitsuId,
        knownAniListTitle = metadata.titleNative,
    )
    StreamCenterLogger.logTab(
        tabName = cardTitle,
        action = "Risoluzione fonti anime completata",
        metadata = mapOf(
            "preferenza_titolo" to animeTitlePreference,
            "titoli_animeunity" to animeUnitySources.size,
            "episodi_animeunity" to animeUnitySources.sumOf { it.episodeNumbers().size },
            "raccomandazioni_animeunity" to recommendations.size,
            "titoli_animeworld" to animeWorldSources.size,
            "episodi_animeworld" to animeWorldSources.sumOf { it.episodeNumbers().size },
            "titoli_animesaturn" to animeSaturnSources.size,
            "episodi_animesaturn" to animeSaturnSources.sumOf { it.episodeNumbers().size },
            "episodi_anizip" to aniZipCatalog.episodes.size,
            "id_kitsu" to resolvedKitsuId,
            "id_simkl" to resolvedSimklId,
        ),
    )
    StreamCenterLogger.logMetadata(
        tabName = cardTitle,
        source = "AniZip",
        action = "Catalogo episodi AniZip valutato",
        metadata = mapOf(
            "richiesto" to includeAniZip,
            "richiesto_per_torrent_ext" to shouldResolveJapaneseTitle,
            "episodi" to aniZipCatalog.episodes.size,
            "id_anilist" to aniZipCatalog.anilistId,
            "id_mal" to aniZipCatalog.malId,
            "id_kitsu" to aniZipCatalog.kitsuId,
            "id_tmdb" to aniZipCatalog.tmdbId,
        ),
        level = if (aniZipCatalog.episodes.isEmpty()) {
            StreamCenterLogger.Level.WARNING
        } else {
            StreamCenterLogger.Level.INFO
        },
    )
    val sourceUrl = markAnilistUrl(resolvedAnilistId, resolvedMalId)
    val sourceEpisodeNumbers = animeSourceEpisodeNumbers(
        animeUnitySources,
        animeWorldSources,
        animeSaturnSources,
    )
    val tmdbMetadata = resolveTmdbAnimeMetadata(
        anilistId = resolvedAnilistId,
        malId = resolvedMalId,
        titleCandidates = listOfNotNull(metadata.titleNative, metadata.titleEnglish, metadata.titleRomaji, metadata.title) + metadata.titleCandidates,
        year = metadata.year,
        sourceEpisodeNumbers = sourceEpisodeNumbers,
        aniZipCatalog = aniZipCatalog,
        isMovie = isMovie,
    )
    val animeType = if (isMovie) {
        TvType.AnimeMovie
    } else {
        when (metadata.format?.uppercase(Locale.ROOT)) {
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
    }
    StreamCenterLogger.logTab(
        tabName = cardTitle,
        action = "Metadati anime TMDB risolti",
        metadata = mapOf(
            "id_tmdb" to tmdbMetadata?.tmdbId,
            "stagione_tmdb" to tmdbMetadata?.season,
            "studio" to metadata.studios.firstOrNull(),
            "stagione_anno" to metadata.releaseInfo.seasonLabel,
            "episodi_tmdb" to tmdbMetadata?.episodes?.size,
            "display_minimo_animeunity" to (tmdbMetadata == null),
        ),
        level = if (tmdbMetadata == null && !performanceMode) {
            StreamCenterLogger.Level.WARNING
        } else {
            StreamCenterLogger.Level.INFO
        },
    )
    val response = renderAnimeResponse(
        isMovie = isMovie,
        animeType = animeType,
        fallbackTitle = cardTitle,
        fallbackShowStatus = anilistShowStatus(metadata.status),
        anilistReleaseInfo = metadata.releaseInfo,
        fallbackTrailerUrl = metadata.trailerUrl,
        titleCandidates = metadata.titleCandidates,
        romajiTitle = metadata.titleRomaji,
        englishTitle = metadata.titleEnglish,
        nativeTitle = metadata.titleNative,
        fallbackPoster = metadata.poster,
        fallbackBackground = metadata.background,
        sourceUrl = sourceUrl,
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
            anilist = resolvedAnilistId,
            mal = resolvedMalId,
            kitsu = resolvedKitsuId,
            simkl = resolvedSimklId,
            imdb = aniZipCatalog.imdbId,
        ),
        showTrackingAsTags = catalogDefinition == null &&
            StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
        cacheKey = cacheKey,
        nextAirDateMillis = metadata.nextAiringAtSeconds?.times(1000L),
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
            trackingSources = listOf("TMDB", "AniList", "MyAnimeList", "Kitsu", "Simkl"),
            torrentContext = torrentContext,
        ),
    )
}

internal fun StreamCenter.animeSourceEpisodeNumbers(
    animeUnitySources: List<AnimeUnityTitleSources>,
    animeWorldSources: List<AnimeWorldTitleSources>,
    animeSaturnSources: List<AnimeSaturnTitleSources>,
): Set<Int> {
    return (
        animeUnitySources.flatMap { it.episodeNumbers() } +
            animeWorldSources.flatMap { it.episodeNumbers() } +
            animeSaturnSources.flatMap { it.episodeNumbers() }
        )
        .mapNotNull(::parseWholeAnimeEpisodeNumber)
        .filter { it > 0 }
        .toSet()
}

internal suspend fun StreamCenter.resolveAnimePlaybackSources(
    metadata: StreamCenterMetadata,
    matchMetadata: AnilistMetadata,
    syncIds: List<AnimeSyncIds>,
    aniZipIds: Pair<Int?, Int?>? = null,
    includeAniZip: Boolean,
    ignoreSourceFailures: Boolean = false,
): ResolvedLoadSources = coroutineScope {
    StreamCenterLogger.logTab(
        tabName = metadata.title,
        action = "Risoluzione fonti anime avviata",
        metadata = mapOf(
            "anilist_id" to syncIds.mapNotNull(AnimeSyncIds::anilistId),
            "mal_id" to syncIds.mapNotNull(AnimeSyncIds::malId),
            "kitsu_id" to syncIds.mapNotNull(AnimeSyncIds::kitsuId),
            "animeunity_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY),
            "animeworld_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD),
            "animesaturn_abilitata" to isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN),
            "anizip_richiesto" to (includeAniZip && aniZipIds != null),
            "fallimenti_ignorati" to ignoreSourceFailures,
        ),
    )
    val animeUnityDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY)) {
        async(Dispatchers.IO) {
            fetchAnimeSource(metadata.title, "AnimeUnity", ignoreSourceFailures) {
                animeUnitySourceClient.fetchSources(metadata, matchMetadata, syncIds)
            }
        }
    } else {
        null
    }
    val animeWorldDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) {
        async(Dispatchers.IO) {
            fetchAnimeSource(metadata.title, "AnimeWorld", ignoreSourceFailures) {
                animeWorldSourceClient.fetchSources(metadata, matchMetadata, syncIds)
            }
        }
    } else {
        null
    }
    val animeSaturnDeferred = if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) {
        async(Dispatchers.IO) {
            fetchAnimeSource(metadata.title, "AnimeSaturn", ignoreSourceFailures) {
                animeSaturnSourceClient.fetchSources(metadata, matchMetadata, syncIds)
            }
        }
    } else {
        null
    }
    val aniZipDeferred = if (includeAniZip && aniZipIds != null) {
        async(Dispatchers.IO) {
            StreamCenterLogger.logMetadata(
                tabName = metadata.title,
                source = "AniZip",
                action = "Ricerca catalogo episodi avviata",
                metadata = mapOf("id_anilist" to aniZipIds.first, "id_mal" to aniZipIds.second),
            )
            var timedOut = false
            val result = try {
                if (shouldResolveAnimeTorrentMetadata()) {
                    withTimeoutOrNull(ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS) {
                        aniZipMetadataClient.fetch(aniZipIds.first, aniZipIds.second)
                    } ?: AniZipEpisodeCatalog().also { timedOut = true }
                } else {
                    aniZipMetadataClient.fetch(aniZipIds.first, aniZipIds.second)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                StreamCenterLogger.logTabError(
                    tabName = metadata.title,
                    action = "Ricerca catalogo episodi AniZip non riuscita",
                    throwable = error,
                    metadata = mapOf("id_anilist" to aniZipIds.first, "id_mal" to aniZipIds.second),
                )
                AniZipEpisodeCatalog()
            }
            StreamCenterLogger.logMetadata(
                tabName = metadata.title,
                source = "AniZip",
                action = "Ricerca catalogo episodi completata",
                metadata = mapOf(
                    "episodi" to result.episodes.size,
                    "id_anilist" to result.anilistId,
                    "id_mal" to result.malId,
                    "id_kitsu" to result.kitsuId,
                    "id_tmdb" to result.tmdbId,
                    "timeout" to timedOut,
                ),
                level = if (timedOut || (result.episodes.isEmpty())) {
                    StreamCenterLogger.Level.WARNING
                } else {
                    StreamCenterLogger.Level.INFO
                },
            )
            result
        }
    } else {
        null
    }
    val animeUnitySources = animeUnityDeferred?.await().orEmpty()
    val animeWorldSources = animeWorldDeferred?.await().orEmpty()
    val animeSaturnSources = animeSaturnDeferred?.await().orEmpty()
    val aniZipCatalog = aniZipDeferred?.await() ?: AniZipEpisodeCatalog()
    val resolved = ResolvedLoadSources(
        animeUnitySources = animeUnitySources,
        animeWorldSources = animeWorldSources,
        animeSaturnSources = animeSaturnSources,
        aniZipCatalog = aniZipCatalog,
    )
    StreamCenterLogger.logTab(
        tabName = metadata.title,
        action = "Risoluzione fonti anime completata",
        metadata = mapOf(
            "titoli_animeunity" to animeUnitySources.size,
            "titoli_animeworld" to animeWorldSources.size,
            "titoli_animesaturn" to animeSaturnSources.size,
            "episodi_anizip" to aniZipCatalog.episodes.size,
        ),
    )
    resolved
}

internal suspend fun <T> StreamCenter.fetchAnimeSource(
    tabName: String,
    source: String,
    ignoreFailures: Boolean,
    fetch: suspend () -> List<T>,
): List<T> {
    return try {
        StreamCenterLogger.logMetadata(
            tabName = tabName,
            source = source,
            action = "Ricerca fonte anime avviata",
            metadata = mapOf("fallimenti_ignorati" to ignoreFailures),
        )
        val result = fetch()
        StreamCenterLogger.logMetadata(
            tabName = tabName,
            source = source,
            action = "Ricerca fonte anime completata",
            metadata = mapOf("corrispondenze_titolo" to result.size),
            level = if (result.isEmpty()) StreamCenterLogger.Level.WARNING else StreamCenterLogger.Level.INFO,
        )
        result
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        StreamCenterLogger.logTabError(
            tabName = tabName,
            action = "Ricerca fonte anime non riuscita",
            throwable = error,
            metadata = mapOf("fonte_metadati" to source, "fallimenti_ignorati" to ignoreFailures),
        )
        if (ignoreFailures) emptyList() else throw error
    }
}

internal fun AnimeLoadResponse.applyAnimeCatalogTitles(
    englishTitle: String?,
    nativeTitle: String?,
    alternativeTitles: Iterable<String>,
) {
    engName = englishTitle?.trim()?.takeIf(String::isNotBlank)
    japName = nativeTitle?.trim()?.takeIf(String::isNotBlank)
    synonyms = (alternativeTitles + listOfNotNull(engName, japName))
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.equals(name, ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }
}

internal suspend fun StreamCenter.resolveTmdbAnimeMetadata(
    anilistId: Int?,
    malId: Int?,
    titleCandidates: List<String>,
    year: Int?,
    sourceEpisodeNumbers: Set<Int>,
    aniZipCatalog: AniZipEpisodeCatalog,
    isMovie: Boolean,
): TmdbAnimeMetadata? {
    if (performanceMode) return null
    val mappedRef = if (isMovie) {
        resolveTmdbAnimeShowViaSimkl(malId, anilistId, sourceEpisodeNumbers, aniZipCatalog, isMovie = true)
    } else {
        runCatchingCancellable {
            withTimeoutOrNull(TMDB_ANIME_EPISODE_METADATA_TIMEOUT_MS) {
                tmdbAnimeEpisodeMetadataClient.resolveShow(
                    anilistId = anilistId ?: 0,
                    sourceEpisodeNumbers = sourceEpisodeNumbers,
                    aniZipCatalog = aniZipCatalog,
                )
            }
        }.getOrNull()
            ?: resolveTmdbAnimeShowViaSimkl(malId, anilistId, sourceEpisodeNumbers, aniZipCatalog, isMovie = false)
    }
    val ref = mappedRef?.takeIf { isMovie || it.episodes.isNotEmpty() } ?: runCatchingCancellable {
        withTimeoutOrNull(TMDB_ANIME_EPISODE_METADATA_TIMEOUT_MS) {
            val searchedIds = tmdbCatalog.resolveAnimeIds(titleCandidates, year, isMovie)
            if (isMovie) {
                searchedIds.singleOrNull()?.let { TmdbAnimeShowRef(it, season = null) }
            } else {
                val matches = searchedIds.mapChunkedParallel(3) { searchedId ->
                    tmdbAnimeEpisodeMetadataClient.resolveShowByTmdbId(searchedId, sourceEpisodeNumbers, aniZipCatalog)
                }.filterNotNull().filter { it.episodes.isNotEmpty() }
                if (searchedIds.size == 1) matches.singleOrNull() else {
                    matches.filter { match ->
                        sourceEpisodeNumbers.isNotEmpty() && sourceEpisodeNumbers.all { it in match.episodes }
                    }.singleOrNull()
                }
            }
        }
    }.getOrNull() ?: mappedRef ?: return null
    val tmdbId = ref.tmdbId
    val kind = if (isMovie) "movie" else "tv"
    val showUrl = "https://www.themoviedb.org/$kind/$tmdbId"
    val season = ref.season
    val englishTitle = runCatchingCancellable { tmdbCatalog.englishTitle(kind, tmdbId.toString()) }.getOrNull()
    val apiMedia = fetchTmdbApiMedia(isTv = !isMovie, tmdbId = tmdbId, minimal = false, isAnime = true)
    val artwork = if (apiMedia != null) {
        apiAnimeArtwork(apiMedia, tmdbId, season, isMovie)
    } else {
        scrapedAnimeArtwork(showUrl, tmdbId, season, isMovie)
    } ?: return null
    val base = artwork.base
    if (base.title.isBlank() || base.title == "Sconosciuto") return null
    return TmdbAnimeMetadata(
        tmdbId = tmdbId,
        season = ref.season,
        title = base.title,
        englishTitle = englishTitle,
        originalTitle = base.originalTitle,
        poster = if (isMovie) base.poster else artwork.seasonPoster,
        background = if (isMovie) base.background else artwork.seasonBackground,
        logo = artwork.logo,
        plot = base.plot?.takeUnless {
            it.contains("Non abbiamo una descrizione", ignoreCase = true) ||
                it.contains("We don't have an overview", ignoreCase = true)
        },
        genres = artwork.genres,
        streamingPlatforms = if (isMovie) null else extractTmdbWatchProviders(showUrl),
        budget = artwork.budget,
        revenue = artwork.revenue,
        duration = base.duration ?: if (isMovie) null else {
            TmdbSeriesMetadata.episodeRuntime(ref.seasonEpisodes.map { it.runTime })
                ?: TmdbSeriesMetadata.episodeRuntime(ref.episodes.values.map { it.runTime })
                ?: artwork.seasonRuntime
        },
        score = base.score,
        contentRating = base.contentRating,
        showStatus = base.showStatus,
        comingSoon = base.comingSoon,
        trailerUrl = base.trailerUrl,
        alternativeTitles = emptyList(),
        episodes = ref.episodes,
        seasonEpisodes = ref.seasonEpisodes,
        seasonName = artwork.seasonName,
    )
}

private data class TmdbAnimeArtwork(
    val base: StreamCenterMetadata,
    val genres: List<String>,
    val budget: String?,
    val revenue: String?,
    val logo: String?,
    val seasonPoster: String?,
    val seasonBackground: String?,
    val seasonName: String?,
    val seasonRuntime: Int?,
)

private suspend fun StreamCenter.apiAnimeArtwork(
    apiMedia: TmdbApiMedia,
    tmdbId: Int,
    season: Int?,
    isMovie: Boolean,
): TmdbAnimeArtwork {
    val seasonJson = if (isMovie) null else season?.let { fetchTmdbApiSeason(tmdbId, it) }
    val seasonEpisodes = TmdbApiMapping.episodes(seasonJson)
    return TmdbAnimeArtwork(
        base = apiMedia.metadata,
        genres = apiMedia.details.genres,
        budget = apiMedia.details.budget,
        revenue = apiMedia.details.revenue,
        logo = apiMedia.logo,
        seasonPoster = TmdbApiMapping.image(seasonJson?.optString("poster_path"), "w500"),
        seasonBackground = seasonEpisodes.firstNotNullOfOrNull(TmdbApiEpisode::still),
        seasonName = seasonJson?.optString("name")?.let { AnimeSeasonInfo.tmdbSeasonName(it) },
        seasonRuntime = TmdbSeriesMetadata.episodeRuntime(seasonEpisodes.map(TmdbApiEpisode::runtime)),
    )
}

private suspend fun StreamCenter.scrapedAnimeArtwork(
    showUrl: String,
    tmdbId: Int,
    season: Int?,
    isMovie: Boolean,
): TmdbAnimeArtwork? {
    val doc = runCatchingCancellable { getTmdbDocument(showUrl) }.getOrNull() ?: return null
    val seasonDocument = if (isMovie) null else season?.let {
        runCatchingCancellable { getTmdbDocument("$showUrl/season/$season") }.getOrNull()
            ?.takeIf { TmdbArtwork.isSeason(it, tmdbId, season) }
    }
    return TmdbAnimeArtwork(
        base = buildMetadata(doc, showUrl, minimalMetadata = false),
        genres = doc.select("span.genres a")
            .mapNotNull { cleanText(it.text()) }
            .distinctBy { it.lowercase(Locale.ROOT) },
        budget = extractAnyFact(doc, "Budget"),
        revenue = extractAnyFact(doc, "Incasso", "Revenue"),
        logo = resolveTmdbLogo(isMovie, tmdbId, isAnime = true),
        seasonPoster = seasonDocument?.let { TmdbArtwork.seasonPoster(it, tmdbId, season!!) },
        seasonBackground = seasonDocument?.let { TmdbArtwork.seasonStill(it, tmdbId, season!!) },
        seasonName = seasonDocument?.let { AnimeSeasonInfo.tmdbSeasonName(it, tmdbId, season!!) },
        seasonRuntime = seasonDocument?.let(TmdbSeriesMetadata::seasonRuntime),
    )
}

internal suspend fun StreamCenter.resolveTmdbIdViaSimkl(malId: Int?, anilistId: Int?): Int? {
    if (malId == null && anilistId == null) return null
    val url = runCatchingCancellable {
        simklCatalog.resolveMediaUrl(
            mal = malId,
            anilist = anilistId,
            allowedCategories = setOf("anime"),
        )
    }.getOrNull() ?: return null
    val media = runCatchingCancellable { simklCatalog.media(url) }.getOrNull() ?: return null
    return media.ids.tmdb?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}

internal suspend fun StreamCenter.resolveTmdbAnimeShowViaSimkl(
    malId: Int?,
    anilistId: Int?,
    sourceEpisodeNumbers: Set<Int>,
    aniZipCatalog: AniZipEpisodeCatalog,
    isMovie: Boolean,
): TmdbAnimeShowRef? {
    val tmdbId = resolveTmdbIdViaSimkl(malId, anilistId) ?: return null
    if (isMovie) return TmdbAnimeShowRef(tmdbId, 1)
    return runCatchingCancellable {
        withTimeoutOrNull(TMDB_ANIME_EPISODE_METADATA_TIMEOUT_MS) {
            tmdbAnimeEpisodeMetadataClient.resolveShowByTmdbId(tmdbId, sourceEpisodeNumbers, aniZipCatalog)
        }
    }.getOrNull() ?: TmdbAnimeShowRef(tmdbId, season = null)
}

internal suspend fun StreamCenter.renderAnimeResponse(
    isMovie: Boolean,
    animeType: TvType,
    fallbackTitle: String,
    fallbackShowStatus: ShowStatus? = null,
    anilistReleaseInfo: AnilistReleaseInfo? = null,
    titleCandidates: List<String> = emptyList(),
    italianTitle: String? = null,
    romajiTitle: String? = null,
    englishTitle: String? = null,
    nativeTitle: String? = null,
    fallbackPoster: String? = null,
    fallbackBackground: String? = null,
    fallbackTrailerUrl: String? = null,
    sourceUrl: String,
    tmdb: TmdbAnimeMetadata?,
    animeUnitySources: List<AnimeUnityTitleSources>,
    animeWorldSources: List<AnimeWorldTitleSources>,
    animeSaturnSources: List<AnimeSaturnTitleSources>,
    recommendations: List<SearchResponse>,
    actors: List<ActorData>,
    studios: List<String>,
    stremioContext: StreamCenterStremioPlaybackContext,
    torrentContext: StreamCenterTorrentPlaybackContext?,
    trackingIds: StreamCenterTrackingIds,
    showTrackingAsTags: Boolean,
    nextAiring: NextAiring? = null,
    cacheKey: String? = null,
    nextAirDateMillis: Long? = null,
): LoadResponse {
    val releaseInfo = anilistReleaseInfo ?: runCatchingCancellable {
        aniListMetadataClient.fetchReleaseInfo(trackingIds.anilist, trackingIds.mal)
    }.getOrNull()
    val titlePreference = StreamCenterPlugin.getAnimeCardTitle(sharedPref)
    val trailerUrl = normalizeTrailerUrl(fallbackTrailerUrl) ?: normalizeTrailerUrl(tmdb?.trailerUrl)
    val displayTitles = AnimeDisplayTitles(
        fallback = fallbackTitle, italian = italianTitle ?: tmdb?.title, english = englishTitle,
        romaji = romajiTitle, native = nativeTitle,
        animeUnity = animeUnitySources.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) },
    )
    val preferredTitle = displayTitles.preferred(titlePreference)
    val seasonInfo = AnimeSeasonInfo.resolve(tmdb, preferredTitle, titleCandidates).let { info ->
        if (!containsJapaneseTitleScript(preferredTitle) && info.seasonName?.let(::containsJapaneseTitleScript) == true) {
            info.copy(seasonName = null)
        } else info
    }
    val resolvedShowStatus = fallbackShowStatus ?: tmdb?.showStatus
    val comingSoon = resolvedShowStatus != ShowStatus.Completed && tmdb?.comingSoon == true
    val title = if (isMovie) displayTitles.forDisplay(tmdb?.title?.takeIf(String::isNotBlank) ?: fallbackTitle)
        else seasonInfo.title(preferredTitle)
    val displayEnglishTitle = englishTitle?.takeIf(String::isNotBlank)?.let(seasonInfo::title)
    val displayNativeTitle = nativeTitle?.takeIf(String::isNotBlank)?.let(seasonInfo::title)
    val poster = tmdb?.poster ?: fallbackPoster ?: animeUnitySources.firstNotNullOfOrNull { it.posterUrl }
    val background = if (isMovie) {
        tmdb?.background ?: fallbackBackground
    } else {
        fallbackBackground ?: tmdb?.background ?: poster
    }
    val plot = tmdb?.plot
        ?: animeUnitySources.firstNotNullOfOrNull { it.plot?.takeIf(String::isNotBlank) }
    val originalTag = tmdb?.originalTitle
        ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        ?.let { "${if (isMovie) "Titolo originale" else "Nome originale"}: $it" }
    val studioTag = studios.filter(String::isNotBlank).takeIf { it.isNotEmpty() }
        ?.let { "Studio: ${it.joinToString(", ")}" }
    val tagsWithOriginal = if (isMovie) {
        listOfNotNull(
            originalTag,
            studioTag,
            tmdb?.budget?.let { "Budget: $it" },
            tmdb?.revenue?.let { "Incasso: $it" },
        )
    } else {
        listOfNotNull(
            originalTag,
            releaseInfo?.seasonLabel,
            studioTag,
            tmdb?.streamingPlatforms,
        )
    }.plus(tmdb?.genres.orEmpty()).distinctBy { it.lowercase(Locale.ROOT) }
    val finalIds = if (tmdb != null && trackingIds.tmdb == null) {
        trackingIds.copy(tmdb = tmdb.tmdbId.toString())
    } else {
        trackingIds
    }
    val episodes = buildAnimeEpisodes(
        animeUnitySources = animeUnitySources,
        animeWorldSources = animeWorldSources,
        animeSaturnSources = animeSaturnSources,
        tmdb = tmdb,
        fallbackPoster = poster,
        stremioContext = stremioContext,
        torrentContext = torrentContext,
        displaySeason = seasonInfo.season,
    )
    val seasons = seasonInfo.seasons(episodes)
    val duration = tmdb?.duration ?: if (isMovie) null else {
        TmdbSeriesMetadata.episodeRuntime(episodes.filter { (it.season ?: 1) > 0 }.map { it.runTime })
    }
    var animeMovieDataUrl: String? = null
    val response = if (isMovie) {
        val playbackData = StreamCenterPlaybackData(
            animeUnity = animeUnitySources.firstNotNullOfOrNull { it.firstPlayback() },
            animeWorld = animeWorldSources.flatMap { it.firstPlaybacks() },
            animeSaturn = animeSaturnSources.flatMap { it.firstPlaybacks() },
            stremio = stremioContext,
            torrent = torrentContext,
        )
        val moviePlaybackJson = playbackData.toJson()
        animeMovieDataUrl = moviePlaybackJson
        newMovieLoadResponse(title, sourceUrl, animeType, dataUrl = moviePlaybackJson) {
            this.posterUrl = poster
            this.logoUrl = tmdb?.logo
            this.backgroundPosterUrl = background
            this.plot = plot
            this.tags = tagsWithOriginal
            this.year = releaseInfo?.year
            this.duration = duration
            this.contentRating = tmdb?.contentRating
            this.actors = actors
            this.recommendations = recommendations
            this.comingSoon = comingSoon
            addStreamCenterTrackingIds(
                finalIds,
                showAsTags = showTrackingAsTags,
                visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
            )
            trailerUrl?.let { addTrailer(it) }
            addScore(tmdb?.score)
        }
    } else {
        newAnimeLoadResponse(title, sourceUrl, animeType) {
            this.posterUrl = poster
            this.logoUrl = tmdb?.logo
            this.backgroundPosterUrl = background
            this.plot = plot
            this.tags = tagsWithOriginal
            this.year = releaseInfo?.year
            this.duration = duration
            this.contentRating = tmdb?.contentRating
            this.actors = actors
            this.recommendations = recommendations
            this.showStatus = resolvedShowStatus
            this.comingSoon = comingSoon
            applyAnimeCatalogTitles(
                englishTitle = displayEnglishTitle,
                nativeTitle = displayNativeTitle,
                alternativeTitles = titleCandidates,
            )
            nextAiring?.let { this.nextAiring = it }
            addEpisodes(DubStatus.Subbed, episodes)
            addSeasonNames(seasons)
            addStreamCenterTrackingIds(
                finalIds,
                showAsTags = showTrackingAsTags,
                visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
            )
            trailerUrl?.let { addTrailer(it) }
            addScore(tmdb?.score)
        }
    }
    if (cacheKey != null && !comingSoon && (isMovie || episodes.isNotEmpty())) {
        runCatching {
            val now = System.currentTimeMillis()
            val cachedEpisodes = episodes.map { ep ->
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
            val nextAir = nextAirDateMillis?.takeIf { it > now }
                ?: nextAiring?.unixTime?.times(1000L)?.takeIf { it > now }
                ?: cachedEpisodes.mapNotNull { it.dateMillis }.filter { it > now }.minOrNull()
            val showStatusName = resolvedShowStatus?.name
            StreamCenterMediaCache.writeMedia(
                CachedMediaEntry(
                    schemaVersion = StreamCenterMediaCache.SCHEMA_VERSION,
                    key = cacheKey,
                    url = sourceUrl,
                    type = StreamCenterMediaCache.TYPE_ANIME,
                    title = title,
                    posterUrl = poster,
                    backgroundPosterUrl = background,
                    logoUrl = tmdb?.logo,
                    plot = plot,
                    tags = tagsWithOriginal,
                    year = releaseInfo?.year,
                    duration = duration,
                    contentRating = tmdb?.contentRating,
                    score = tmdb?.score,
                    showStatus = showStatusName,
                    comingSoon = comingSoon,
                    trailerUrl = trailerUrl,
                    trailerCheckedAtMillis = now,
                    trackingIds = finalIds,
                    actors = actors.map(::CachedActor),
                    recommendations = recommendations.map {
                        CachedSearchItem(
                            name = it.name,
                            url = it.url,
                            type = it.type?.name,
                            posterUrl = it.posterUrl,
                        )
                    },
                    seasons = seasons.map { CachedSeason(it.season, it.name, it.displaySeason) },
                    episodes = cachedEpisodes,
                    movieDataUrl = animeMovieDataUrl,
                    animeType = animeType.name,
                    animeMovie = isMovie,
                    animeTitlePreference = titlePreference,
                    englishTitle = displayEnglishTitle,
                    nativeTitle = displayNativeTitle,
                    alternativeTitles = titleCandidates,
                    cachedAtMillis = now,
                    expiresAtMillis = StreamCenterMediaCache.computeMediaExpiry(
                        now, showStatusName, nextAir, isMovie = isMovie,
                    ),
                ),
            )
        }
    }
    return response
}

internal fun StreamCenter.buildAnimeSeasonData(episodes: List<Episode>): List<SeasonData> {
    return episodes.mapNotNull { it.season }
        .distinct()
        .sorted()
        .map { season ->
            SeasonData(
                season = season,
                displaySeason = season.takeIf { it > 0 },
            )
        }
}

internal fun StreamCenter.meaningfulSeasonName(rawName: String?, season: Int): String? {
    val name = rawName?.trim()?.takeIf(String::isNotBlank) ?: return null
    val normalized = name.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    val genericLabels = setOf(
        "season $season",
        "stagione $season",
        "specials",
        "speciali",
    )
    return if (normalized in genericLabels) null else name
}
