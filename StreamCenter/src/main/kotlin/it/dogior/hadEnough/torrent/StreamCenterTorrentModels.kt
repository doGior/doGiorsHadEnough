package it.dogior.hadEnough.torrent

internal const val TORRENT_SOURCE_TIMEOUT_MS = 15_000L
internal const val TORRENT_SOURCE_CONCURRENCY = 2
internal const val TORRENT_TOTAL_TIMEOUT_MS = 25_000L
internal const val TORRENT_PERFORMANCE_TOTAL_TIMEOUT_MS = 15_000L

internal data class StreamCenterTorrentPlaybackContext(
    val titles: List<String>,
    val englishTitle: String? = null,
    val japaneseTitle: String? = null,
    val year: Int? = null,
    val isAnime: Boolean = false,
    val isMovie: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    val episodeNumberAliases: Map<Int, List<Int>>? = null,
)

internal fun StreamCenterTorrentPlaybackContext.episodeNumbersForSearch(): List<Int> {
    val localEpisode = episode?.takeIf { it > 0 } ?: return emptyList()
    return (listOf(localEpisode) + episodeNumberAliases.orEmpty()[localEpisode].orEmpty())
        .filter { it > 0 }
        .distinct()
}

internal enum class StreamCenterTorrentSourceScope {
    ANIME,
    GENERAL,
    ALL,
}

internal data class StreamCenterTorrentSourceDefinition(
    val key: String,
    val urlPrefKey: String,
    val title: String,
    val defaultUrl: String,
    val scope: StreamCenterTorrentSourceScope,
    val defaultEnabled: Boolean = true,
    val nyaaCategory: String? = null,
) {
    val displayUrl: String
        get() = nyaaCategory
            ?.let { category -> "${defaultUrl.trimEnd('/')}/?c=$category" }
            ?: "${defaultUrl.trimEnd('/')}/"

    fun supports(context: StreamCenterTorrentPlaybackContext): Boolean = when (scope) {
        StreamCenterTorrentSourceScope.ANIME -> context.isAnime
        StreamCenterTorrentSourceScope.GENERAL -> !context.isAnime
        StreamCenterTorrentSourceScope.ALL -> true
    }
}

internal object StreamCenterTorrentSources {
    const val NYAA_KEY = "sourceTorrentNyaa"
    const val SUKEBEI_NYAA_KEY = "sourceTorrentSukebeiNyaa"
    const val TORRENT_GALAXY_KEY = "sourceTorrentGalaxy"
    const val APIBAY_KEY = "sourceTorrentApiBay"
    const val EXT_KEY = "sourceTorrentExt"

    const val NYAA_URL_KEY = "urlTorrentNyaa"
    const val SUKEBEI_NYAA_URL_KEY = "urlTorrentSukebeiNyaa"
    const val TORRENT_GALAXY_URL_KEY = "urlTorrentGalaxy"
    const val APIBAY_URL_KEY = "urlTorrentApiBay"
    const val EXT_URL_KEY = "urlTorrentExt"

    val definitions: List<StreamCenterTorrentSourceDefinition> = listOf(
        StreamCenterTorrentSourceDefinition(
            key = NYAA_KEY,
            urlPrefKey = NYAA_URL_KEY,
            title = "Nyaa",
            defaultUrl = "https://nyaa.si",
            scope = StreamCenterTorrentSourceScope.ANIME,
            nyaaCategory = "1_0",
        ),
        StreamCenterTorrentSourceDefinition(
            key = SUKEBEI_NYAA_KEY,
            urlPrefKey = SUKEBEI_NYAA_URL_KEY,
            title = "Sukebei Nyaa",
            defaultUrl = "https://sukebei.nyaa.si",
            scope = StreamCenterTorrentSourceScope.ANIME,
            nyaaCategory = "1_1",
        ),
        StreamCenterTorrentSourceDefinition(
            key = TORRENT_GALAXY_KEY,
            urlPrefKey = TORRENT_GALAXY_URL_KEY,
            title = "Torrent Galaxy",
            defaultUrl = "https://torrentgalaxy.one",
            scope = StreamCenterTorrentSourceScope.GENERAL,
        ),
        StreamCenterTorrentSourceDefinition(
            key = APIBAY_KEY,
            urlPrefKey = APIBAY_URL_KEY,
            title = "ApiBay",
            defaultUrl = "https://apibay.org",
            scope = StreamCenterTorrentSourceScope.ALL,
        ),
        StreamCenterTorrentSourceDefinition(
            key = EXT_KEY,
            urlPrefKey = EXT_URL_KEY,
            title = "EXT",
            defaultUrl = "https://ext.to",
            scope = StreamCenterTorrentSourceScope.ALL,
        ),
    )

    val preferenceKeys: Set<String> = definitions
        .flatMapTo(linkedSetOf()) { source -> listOf(source.key, source.urlPrefKey) }

    fun isSourcePreferenceKey(key: String): Boolean =
        key.startsWith(SOURCE_PREFERENCE_PREFIX) || key.startsWith(URL_PREFERENCE_PREFIX)

    private const val SOURCE_PREFERENCE_PREFIX = "sourceTorrent"
    private const val URL_PREFERENCE_PREFIX = "urlTorrent"
}

internal data class StreamCenterTorrentCandidate(
    val title: String,
    val infoHash: String? = null,
    val magnetUrl: String? = null,
    val size: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val fileIndex: Int? = null,
    val selectedFileName: String? = null,
    val fileMetadataRequest: StreamCenterTorrentFileMetadataRequest? = null,
    val availableFiles: List<StreamCenterTorrentFile>? = null,
)

internal enum class StreamCenterTorrentFileMetadataFormat {
    TORRENT,
    APIBAY,
}

internal data class StreamCenterTorrentFileMetadataRequest(
    val url: String,
    val format: StreamCenterTorrentFileMetadataFormat,
    val expectedInfoHash: String? = null,
)

internal data class StreamCenterTorrentFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long? = null,
)

internal interface StreamCenterTorrentSourceClient {
    suspend fun search(
        definition: StreamCenterTorrentSourceDefinition,
        sourceUrl: String,
        context: StreamCenterTorrentPlaybackContext,
        filters: StreamCenterTorrentFilterSettings,
    ): List<StreamCenterTorrentCandidate>
}
