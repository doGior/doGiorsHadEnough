package it.dogior.hadEnough

import it.dogior.hadEnough.StreamCenter.Companion.ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS
import it.dogior.hadEnough.anime.metadata.AnimeJapaneseTitleHints
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.cleanText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal fun StreamCenter.torrentPlaybackContext(
    titles: Iterable<String?>,
    englishTitle: String? = null,
    year: Int? = null,
    isAnime: Boolean,
    isMovie: Boolean,
    japaneseTitle: String? = null,
    imdbId: String? = null,
): StreamCenterTorrentPlaybackContext? {
    val baseTitles = titles
        .mapNotNull(::cleanText)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
    val normalizedEnglishTitle = englishTitle
        ?.let(::cleanText)
        ?.takeIf(String::isNotBlank)
    val normalizedTitles = buildList {
        baseTitles.firstOrNull()?.let(::add)
        normalizedEnglishTitle?.let(::add)
        addAll(baseTitles.drop(1))
    }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(6)
    if (normalizedTitles.isEmpty()) return null
    return StreamCenterTorrentPlaybackContext(
        titles = normalizedTitles,
        englishTitle = normalizedEnglishTitle,
        year = year,
        isAnime = isAnime,
        isMovie = isMovie,
        japaneseTitle = japaneseTitle?.let(::cleanText),
        imdbId = imdbId?.trim()?.takeIf { id -> id.startsWith("tt", ignoreCase = true) },
    )
}

internal fun StreamCenter.shouldResolveTorrentJapaneseTitle(): Boolean =
    StreamCenterPlugin.isTorrentEnabled(sharedPref)

internal fun StreamCenter.shouldResolveTmdbEnglishTitle(): Boolean {
    return StreamCenterPlugin.isTorrentEnabled(sharedPref) ||
        InstalledExtensionSources.enabledKeys(sharedPref).isNotEmpty()
}

internal suspend fun StreamCenter.resolveTmdbEnglishTitle(
    tmdbId: String?,
    isMovie: Boolean,
): String? {
    if (!shouldResolveTmdbEnglishTitle()) return null
    val normalizedId = tmdbId
        ?.trim()
        ?.takeIf { value -> value.all(Char::isDigit) }
        ?: return null
    return runCatching {
        tmdbCatalog.englishTitle(
            type = if (isMovie) "movie" else "tv",
            id = normalizedId,
        )
    }.getOrNull()
}

internal fun StreamCenter.shouldResolveAnimeTorrentMetadata(): Boolean {
    return StreamCenterPlugin.isTorrentEnabled(sharedPref)
}

internal suspend fun StreamCenter.animeTorrentPlaybackContext(
    titles: Iterable<String?>,
    year: Int? = null,
    isMovie: Boolean,
    tabName: String,
    aniZipCatalog: AniZipEpisodeCatalog? = null,
    anilistId: Int? = null,
    malId: Int? = null,
    kitsuId: Int? = null,
    imdbId: String? = null,
    knownKitsuTitle: String? = null,
    knownAniListTitle: String? = null,
    knownMyAnimeListTitle: String? = null,
): StreamCenterTorrentPlaybackContext? {
    val baseContext = torrentPlaybackContext(
        titles = titles,
        year = year,
        isAnime = true,
        isMovie = isMovie,
        imdbId = imdbId,
    ) ?: return null
    val shouldResolveJapaneseTitle = shouldResolveTorrentJapaneseTitle()
    if (!shouldResolveAnimeTorrentMetadata() && !shouldResolveJapaneseTitle) return baseContext

    val startedAt = System.currentTimeMillis()
    if (shouldResolveJapaneseTitle) {
        StreamCenterLogger.logMetadata(
            tabName = tabName,
            source = "Torrent · EXT",
            action = "Risoluzione titolo giapponese avviata",
            metadata = mapOf(
                "ordine_provider" to listOf("Kitsu", "AniList", "MyAnimeList"),
                "id_anilist" to anilistId,
                "id_myanimelist" to malId,
                "id_kitsu" to kitsuId,
                "catalogo_anizip_gia_disponibile" to (aniZipCatalog != null),
            ),
        )
    }

    var aniZipTimedOut = false
    var aniZipFailed = false
    val resolvedAniZipCatalog = if (aniZipCatalog != null) {
        aniZipCatalog
    } else if (anilistId != null || malId != null) {
        try {
            withTimeoutOrNull(ANIME_JAPANESE_TITLE_ANIZIP_TIMEOUT_MS) {
                aniZipMetadataClient.fetch(anilistId, malId)
            } ?: AniZipEpisodeCatalog().also { aniZipTimedOut = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            aniZipFailed = true
            StreamCenterLogger.logTabError(
                tabName = tabName,
                action = "Recupero metadati Torrent AniZip non riuscito",
                throwable = error,
                metadata = mapOf("id_anilist" to anilistId, "id_myanimelist" to malId),
            )
            AniZipEpisodeCatalog()
        }
    } else {
        AniZipEpisodeCatalog()
    }

    val rawEpisodeNumberings = resolvedAniZipCatalog.episodes
        .mapValues { (_, metadata) ->
            StreamCenterTorrentEpisodeNumbering(
                seasonNumber = metadata.seasonNumber?.takeIf { number -> number > 0 },
                seasonEpisodeNumber = metadata.episodeNumber?.takeIf { number -> number > 0 },
                absoluteEpisodeNumber = metadata.absoluteEpisodeNumber
                    ?.takeIf { number -> number > 0 },
            )
        }
        .filterValues { numbering ->
            numbering.seasonNumber != null ||
                numbering.seasonEpisodeNumber != null ||
                numbering.absoluteEpisodeNumber != null
        }
    val episodeNumberings = normalizeDominantEpisodeOffsets(rawEpisodeNumberings)
    val alternateEpisodeCount = episodeNumberings.count { (localNumber, numbering) ->
        listOfNotNull(numbering.seasonEpisodeNumber, numbering.absoluteEpisodeNumber)
            .any { number -> number != localNumber }
    }
    val contextWithEpisodeNumberings = baseContext.copy(
        episodeNumberings = episodeNumberings.ifEmpty { null },
        imdbId = baseContext.imdbId ?: resolvedAniZipCatalog.imdbId,
    )
    StreamCenterLogger.logMetadata(
        tabName = tabName,
        source = "Torrent · AniZip",
        action = if (alternateEpisodeCount == 0) {
            "Numerazione alternativa episodi Torrent non trovata"
        } else {
            "Numerazione alternativa episodi Torrent risolta"
        },
        metadata = mapOf(
            "episodi_anizip" to resolvedAniZipCatalog.episodes.size,
            "episodi_con_numerazione_alternativa" to alternateEpisodeCount,
            "episodi_con_coordinate_strutturate" to episodeNumberings.size,
        ),
        level = if (alternateEpisodeCount == 0) {
            StreamCenterLogger.Level.WARNING
        } else {
            StreamCenterLogger.Level.INFO
        },
    )
    if (!shouldResolveJapaneseTitle) return contextWithEpisodeNumberings

    val resolution = try {
        animeJapaneseTitleResolver.resolve(
            aniZipCatalog = resolvedAniZipCatalog,
            kitsuId = kitsuId,
            anilistId = anilistId,
            malId = malId,
            hints = AnimeJapaneseTitleHints(
                kitsu = listOfNotNull(knownKitsuTitle),
                aniList = listOfNotNull(knownAniListTitle),
                myAnimeList = listOfNotNull(knownMyAnimeListTitle),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        StreamCenterLogger.logTabError(
            tabName = tabName,
            action = "Risoluzione titolo giapponese Torrent non riuscita",
            throwable = error,
            metadata = mapOf(
                "id_anilist" to anilistId,
                "id_myanimelist" to malId,
                "id_kitsu" to kitsuId,
            ),
        )
        return contextWithEpisodeNumberings
    }

    val sourceName = resolution.source?.logName
    StreamCenterLogger.logMetadata(
        tabName = tabName,
        source = "Torrent · EXT",
        action = if (resolution.title != null) {
            "Titolo giapponese Torrent risolto"
        } else {
            "Titolo giapponese Torrent non trovato"
        },
        metadata = mapOf(
            "fonte_titolo" to sourceName,
            "fallback_usato" to (resolution.attempts.size > 1),
            "cache" to resolution.cacheHit,
            "script_giapponese_valido" to (resolution.title != null),
            "anizip_timeout" to aniZipTimedOut,
            "anizip_errore" to aniZipFailed,
            "durata_ms" to (System.currentTimeMillis() - startedAt),
            "tentativi" to resolution.attempts.map { attempt ->
                mapOf(
                    "provider" to attempt.source.logName,
                    "stato" to attempt.status.toString(),
                    "origine" to attempt.origin,
                    "identificativo" to attempt.identifier,
                    "durata_ms" to attempt.durationMs,
                    "tipo_errore" to attempt.errorType,
                )
            },
        ),
        level = if (resolution.title != null) {
            StreamCenterLogger.Level.INFO
        } else {
            StreamCenterLogger.Level.WARNING
        },
    )
    return contextWithEpisodeNumberings.copy(japaneseTitle = resolution.title)
}

internal fun StreamCenter.torrentPlaybackProvenance(
    context: StreamCenterTorrentPlaybackContext?,
): List<String> {
    if (context == null || !StreamCenterPlugin.isTorrentEnabled(sharedPref)) {
        return emptyList()
    }
    return if (StreamCenterPlugin.isTorrentEnabled(sharedPref)) {
        listOf("Fonti Torrent abilitate", "StreamCenter (contesto Torrent)")
    } else {
        emptyList()
    }
}
