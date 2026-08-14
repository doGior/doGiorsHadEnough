package it.dogior.hadEnough.torrent

internal const val TORRENT_SOURCE_TIMEOUT_MS = 75_000L
internal const val TORRENT_TOTAL_TIMEOUT_MS = 90_000L
internal const val TORRENT_PERFORMANCE_TOTAL_TIMEOUT_MS = 15_000L

internal data class StreamCenterTorrentEpisodeNumbering(
    val seasonNumber: Int? = null,
    val seasonEpisodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
)

internal enum class StreamCenterTorrentEpisodeCoordinateKind {
    LOCAL,
    SEASON,
    ABSOLUTE,
    LEGACY,
}

internal data class StreamCenterTorrentEpisodeCoordinate(
    val season: Int?,
    val episode: Int,
    val kind: StreamCenterTorrentEpisodeCoordinateKind,
)

internal data class StreamCenterTorrentPlaybackContext(
    val titles: List<String>,
    val englishTitle: String? = null,
    val japaneseTitle: String? = null,
    val year: Int? = null,
    val isAnime: Boolean = false,
    val isMovie: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeNumberAliases: Map<Int, List<Int>>? = null,
    val episodeNumberings: Map<Int, StreamCenterTorrentEpisodeNumbering>? = null,
    val imdbId: String? = null,
)

internal fun StreamCenterTorrentPlaybackContext.episodeCoordinatesForSearch(): List<StreamCenterTorrentEpisodeCoordinate> {
    val localEpisode = episode?.takeIf { it > 0 } ?: return emptyList()
    val numbering = episodeNumberings.orEmpty()[localEpisode]
    return buildList {
        add(
            StreamCenterTorrentEpisodeCoordinate(
                season = season?.takeIf { it > 0 },
                episode = localEpisode,
                kind = StreamCenterTorrentEpisodeCoordinateKind.LOCAL,
            )
        )
        numbering?.seasonEpisodeNumber
            ?.takeIf { it > 0 }
            ?.let { seasonEpisode ->
                add(
                    StreamCenterTorrentEpisodeCoordinate(
                        season = numbering.seasonNumber?.takeIf { it > 0 }
                            ?: season?.takeIf { it > 0 },
                        episode = seasonEpisode,
                        kind = StreamCenterTorrentEpisodeCoordinateKind.SEASON,
                    )
                )
            }
        numbering?.absoluteEpisodeNumber
            ?.takeIf { it > 0 }
            ?.let { absoluteEpisode ->
                add(
                    StreamCenterTorrentEpisodeCoordinate(
                        season = null,
                        episode = absoluteEpisode,
                        kind = StreamCenterTorrentEpisodeCoordinateKind.ABSOLUTE,
                    )
                )
            }
        episodeNumberAliases.orEmpty()[localEpisode].orEmpty()
            .filter { it > 0 }
            .forEach { alias ->
                add(
                    StreamCenterTorrentEpisodeCoordinate(
                        season = null,
                        episode = alias,
                        kind = StreamCenterTorrentEpisodeCoordinateKind.LEGACY,
                    )
                )
            }
    }.distinctBy { coordinate -> coordinate.season to coordinate.episode }
}

internal fun StreamCenterTorrentPlaybackContext.episodeNumbersForSearch(): List<Int> {
    return episodeCoordinatesForSearch()
        .map(StreamCenterTorrentEpisodeCoordinate::episode)
        .distinct()
}

internal fun normalizeDominantEpisodeOffsets(
    numberings: Map<Int, StreamCenterTorrentEpisodeNumbering>,
): Map<Int, StreamCenterTorrentEpisodeNumbering> {
    if (numberings.size < 2) return numberings

    val dominantOffsets = numberings.entries
        .filter { (localEpisode, numbering) ->
            localEpisode > 0 && numbering.seasonEpisodeNumber?.let { it > 0 } == true
        }
        .groupBy { (_, numbering) -> numbering.seasonNumber?.takeIf { it > 0 } }
        .mapNotNull { (seasonNumber, entries) ->
            val offsets = entries.groupingBy { (localEpisode, numbering) ->
                requireNotNull(numbering.seasonEpisodeNumber) - localEpisode
            }.eachCount()
            val highestSupport = offsets.values.maxOrNull() ?: return@mapNotNull null
            val dominant = offsets.entries.singleOrNull { (_, support) ->
                support == highestSupport
            } ?: return@mapNotNull null
            if (highestSupport < 2 || highestSupport * 2 <= entries.size) {
                return@mapNotNull null
            }
            seasonNumber to dominant.key
        }
        .toMap()
    val absoluteEntries = numberings.entries.filter { (localEpisode, numbering) ->
        localEpisode > 0 && numbering.absoluteEpisodeNumber?.let { it > 0 } == true
    }
    val absoluteOffsets = absoluteEntries.groupingBy { (localEpisode, numbering) ->
        requireNotNull(numbering.absoluteEpisodeNumber) - localEpisode
    }.eachCount()
    val highestAbsoluteSupport = absoluteOffsets.values.maxOrNull()
    val dominantAbsoluteOffset = highestAbsoluteSupport
        ?.let { highestSupport ->
            absoluteOffsets.entries.singleOrNull { (_, support) -> support == highestSupport }
                ?.takeIf {
                    highestSupport >= 2 && highestSupport * 2 > absoluteEntries.size
                }
                ?.key
        }
    if (dominantOffsets.isEmpty() && dominantAbsoluteOffset == null) return numberings

    return numberings.mapValues { (localEpisode, numbering) ->
        val seasonKey = numbering.seasonNumber?.takeIf { it > 0 }
        val normalizedSeasonEpisode = dominantOffsets[seasonKey]
            ?.let { dominantOffset -> localEpisode + dominantOffset }
            ?.takeIf { localEpisode > 0 && it > 0 }
            ?: numbering.seasonEpisodeNumber
        val normalizedAbsoluteEpisode = dominantAbsoluteOffset
            ?.let { dominantOffset -> localEpisode + dominantOffset }
            ?.takeIf { localEpisode > 0 && it > 0 }
            ?: numbering.absoluteEpisodeNumber
        numbering.copy(
            seasonEpisodeNumber = normalizedSeasonEpisode,
            absoluteEpisodeNumber = normalizedAbsoluteEpisode,
        )
    }
}

internal fun StreamCenterTorrentPlaybackContext.forEpisode(
    season: Int?,
    episode: Int?,
): StreamCenterTorrentPlaybackContext {
    val resolvedEpisode = episode?.takeIf { value -> value > 0 }
    val aliasesForEpisode = resolvedEpisode
        ?.let { number -> episodeNumberAliases.orEmpty()[number] }
        ?.takeIf(List<Int>::isNotEmpty)
        ?.let { aliases -> mapOf(resolvedEpisode to aliases) }
    val numberingForEpisode = resolvedEpisode
        ?.let { number -> episodeNumberings.orEmpty()[number] }
        ?.let { numbering -> mapOf(resolvedEpisode to numbering) }
    return copy(
        season = season,
        episode = episode,
        episodeNumberAliases = aliasesForEpisode,
        episodeNumberings = numberingForEpisode,
    )
}

internal enum class StreamCenterExtCategory(
    val id: Int,
    val displayName: String,
) {
    MOVIES(1, "Movies"),
    TV(2, "TV"),
    ANIME(7, "Anime"),
}

internal fun StreamCenterTorrentPlaybackContext.extCategory(): StreamCenterExtCategory = when {
    isAnime -> StreamCenterExtCategory.ANIME
    isMovie -> StreamCenterExtCategory.MOVIES
    else -> StreamCenterExtCategory.TV
}

internal enum class StreamCenterExtDomain(
    val preferenceValue: String,
    val title: String,
    val baseUrl: String,
    val defaultEnabled: Boolean,
    val requiresCloudflare: Boolean,
) {
    PRIMARY(
        preferenceValue = "primary",
        title = "EXT principale",
        baseUrl = "https://ext.to",
        defaultEnabled = true,
        requiresCloudflare = true,
    ),
    SECONDARY(
        preferenceValue = "secondary",
        title = "EXT secondario",
        baseUrl = "https://extto.com",
        defaultEnabled = true,
        requiresCloudflare = false,
    ),
    PROXY(
        preferenceValue = "proxy",
        title = "Proxy EXT",
        baseUrl = "https://extranet.torrentbay.st",
        defaultEnabled = false,
        requiresCloudflare = true,
    ),
    ;

    companion object {
        fun fromPreference(value: String?): StreamCenterExtDomain? =
            entries.firstOrNull { domain -> domain.preferenceValue == value }
    }
}

internal enum class StreamCenterExtContainLocation(
    val preferenceValue: String,
    val title: String,
    val description: String,
) {
    TITLE(
        preferenceValue = "title",
        title = "Solo nel Titolo",
        description = "Più rapido e preciso.",
    ),
    FILES(
        preferenceValue = "files",
        title = "Solo nei File",
        description = "Utile per i batch.",
    ),
    TITLE_AND_FILES(
        preferenceValue = "title_and_files",
        title = "Titolo e File",
        description = "Esegue entrambe le ricerche.",
    ),
    ;

    companion object {
        fun fromPreference(value: String?): StreamCenterExtContainLocation =
            entries.firstOrNull { location -> location.preferenceValue == value } ?: TITLE
    }
}

internal data class StreamCenterExtReleaseSource(
    val id: Int,
    val title: String,
    val categories: Set<StreamCenterExtCategory>,
    val reliabilityWeight: Int,
)

internal object StreamCenterExtReleaseSources {
    val all: List<StreamCenterExtReleaseSource> = listOf(
        StreamCenterExtReleaseSource(
            id = 1,
            title = "1337x",
            categories = setOf(
                StreamCenterExtCategory.MOVIES,
                StreamCenterExtCategory.TV,
                StreamCenterExtCategory.ANIME,
            ),
            reliabilityWeight = 5,
        ),
        StreamCenterExtReleaseSource(
            id = 2,
            title = "EZTV",
            categories = setOf(StreamCenterExtCategory.TV),
            reliabilityWeight = 7,
        ),
        StreamCenterExtReleaseSource(
            id = 3,
            title = "YTS",
            categories = setOf(StreamCenterExtCategory.MOVIES),
            reliabilityWeight = 6,
        ),
        StreamCenterExtReleaseSource(
            id = 4,
            title = "ThePirateBay",
            categories = setOf(
                StreamCenterExtCategory.MOVIES,
                StreamCenterExtCategory.TV,
                StreamCenterExtCategory.ANIME,
            ),
            reliabilityWeight = 3,
        ),
        StreamCenterExtReleaseSource(
            id = 5,
            title = "IlCorsaroNero",
            categories = setOf(StreamCenterExtCategory.MOVIES, StreamCenterExtCategory.TV),
            reliabilityWeight = 6,
        ),
        StreamCenterExtReleaseSource(
            id = 7,
            title = "RARBG",
            categories = setOf(StreamCenterExtCategory.MOVIES, StreamCenterExtCategory.TV),
            reliabilityWeight = 4,
        ),
        StreamCenterExtReleaseSource(
            id = 8,
            title = "EXT Torrents",
            categories = setOf(
                StreamCenterExtCategory.MOVIES,
                StreamCenterExtCategory.TV,
                StreamCenterExtCategory.ANIME,
            ),
            reliabilityWeight = 5,
        ),
        StreamCenterExtReleaseSource(
            id = 9,
            title = "Nyaa",
            categories = setOf(StreamCenterExtCategory.ANIME),
            reliabilityWeight = 8,
        ),
        StreamCenterExtReleaseSource(
            id = 10,
            title = "OxTorrent",
            categories = setOf(StreamCenterExtCategory.MOVIES, StreamCenterExtCategory.TV),
            reliabilityWeight = 5,
        ),
        StreamCenterExtReleaseSource(
            id = 11,
            title = "DonTorrent",
            categories = setOf(StreamCenterExtCategory.MOVIES, StreamCenterExtCategory.TV),
            reliabilityWeight = 4,
        ),
        StreamCenterExtReleaseSource(
            id = 12,
            title = "SkTorrent",
            categories = setOf(StreamCenterExtCategory.MOVIES, StreamCenterExtCategory.TV),
            reliabilityWeight = 4,
        ),
        StreamCenterExtReleaseSource(
            id = 13,
            title = "Polskie Torrenty",
            categories = setOf(StreamCenterExtCategory.MOVIES, StreamCenterExtCategory.TV),
            reliabilityWeight = 3,
        ),
        StreamCenterExtReleaseSource(
            id = 14,
            title = "YggTorrent",
            categories = setOf(StreamCenterExtCategory.MOVIES, StreamCenterExtCategory.TV),
            reliabilityWeight = 5,
        ),
        StreamCenterExtReleaseSource(
            id = 16,
            title = "RuTracker",
            categories = setOf(
                StreamCenterExtCategory.MOVIES,
                StreamCenterExtCategory.TV,
                StreamCenterExtCategory.ANIME,
            ),
            reliabilityWeight = 5,
        ),
    )

    fun forCategory(category: StreamCenterExtCategory): List<StreamCenterExtReleaseSource> =
        all.filter { source -> category in source.categories }

    fun byId(id: Int?): StreamCenterExtReleaseSource? =
        id?.let { sourceId -> all.firstOrNull { source -> source.id == sourceId } }
}

internal data class StreamCenterTorrentCandidate(
    val title: String,
    val infoHash: String? = null,
    val magnetUrl: String? = null,
    val size: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val extReleaseSourceId: Int? = null,
    val fileIndex: Int? = null,
    val selectedFileName: String? = null,
    val availableFiles: List<StreamCenterTorrentFile>? = null,
)

internal data class StreamCenterTorrentFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long? = null,
)
