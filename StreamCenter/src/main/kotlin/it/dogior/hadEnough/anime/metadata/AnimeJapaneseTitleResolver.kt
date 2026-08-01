package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.model.AniZipEpisodeCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class AnimeJapaneseTitleHints(
    val kitsu: List<String> = emptyList(),
    val aniList: List<String> = emptyList(),
    val myAnimeList: List<String> = emptyList(),
)

internal enum class AnimeJapaneseTitleSource(
    val logName: String,
) {
    ANIZIP("AniZip"),
    KITSU("Kitsu"),
    ANILIST("AniList"),
    MY_ANIME_LIST("MyAnimeList"),
}

internal enum class AnimeJapaneseTitleAttemptStatus {
    FOUND,
    NOT_FOUND,
    SKIPPED_NO_ID,
    TIMED_OUT,
    FAILED,
    TOTAL_TIMEOUT,
}

internal data class AnimeJapaneseTitleAttempt(
    val source: AnimeJapaneseTitleSource,
    val status: AnimeJapaneseTitleAttemptStatus,
    val origin: String,
    val identifier: Int? = null,
    val durationMs: Long = 0L,
    val errorType: String? = null,
)

internal data class AnimeJapaneseTitleResolution(
    val title: String?,
    val source: AnimeJapaneseTitleSource?,
    val attempts: List<AnimeJapaneseTitleAttempt>,
    val cacheHit: Boolean,
)

internal class AnimeJapaneseTitleResolver(
    private val kitsuMetadataClient: KitsuMetadataClient,
    private val aniListMetadataClient: AniListMetadataClient,
    private val jikanMetadataClient: JikanMetadataClient,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val positiveCacheTtlMs: Long = DEFAULT_POSITIVE_CACHE_TTL_MS,
    private val negativeCacheTtlMs: Long = DEFAULT_NEGATIVE_CACHE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    init {
        require(providerTimeoutMs > 0L) { "providerTimeoutMs must be positive" }
        require(totalTimeoutMs > 0L) { "totalTimeoutMs must be positive" }
        require(positiveCacheTtlMs > 0L) { "positiveCacheTtlMs must be positive" }
        require(negativeCacheTtlMs > 0L) { "negativeCacheTtlMs must be positive" }
    }

    suspend fun resolve(
        aniZipCatalog: AniZipEpisodeCatalog? = null,
        kitsuId: Int? = null,
        anilistId: Int? = null,
        malId: Int? = null,
        hints: AnimeJapaneseTitleHints = AnimeJapaneseTitleHints(),
    ): AnimeJapaneseTitleResolution {
        val ids = ResolvedIds(
            kitsuId = kitsuId.validId() ?: aniZipCatalog?.kitsuId.validId(),
            anilistId = anilistId.validId() ?: aniZipCatalog?.anilistId.validId(),
            malId = malId.validId() ?: aniZipCatalog?.malId.validId(),
        )
        val cacheKey = cacheKey(aniZipCatalog, ids, hints)
        val now = clock()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return cached.resolution.copy(cacheHit = true)
            }
            cache.remove(cacheKey, cached)
        }

        val attempts = mutableListOf<AnimeJapaneseTitleAttempt>()
        var activeSource = AnimeJapaneseTitleSource.ANIZIP
        val resolution = withTimeoutOrNull(totalTimeoutMs) {
            resolveUncached(
                aniZipCatalog = aniZipCatalog,
                ids = ids,
                hints = hints,
                attempts = attempts,
                onSourceStarted = { activeSource = it },
            )
        } ?: AnimeJapaneseTitleResolution(
            title = null,
            source = null,
            attempts = attempts + AnimeJapaneseTitleAttempt(
                source = activeSource,
                status = AnimeJapaneseTitleAttemptStatus.TOTAL_TIMEOUT,
                origin = ORIGIN_RESOLVER,
                identifier = ids.identifierFor(activeSource),
                durationMs = totalTimeoutMs,
            ),
            cacheHit = false,
        )

        if (resolution.isCacheable()) {
            putCache(cacheKey, resolution, clock())
        }
        return resolution
    }

    private suspend fun resolveUncached(
        aniZipCatalog: AniZipEpisodeCatalog?,
        ids: ResolvedIds,
        hints: AnimeJapaneseTitleHints,
        attempts: MutableList<AnimeJapaneseTitleAttempt>,
        onSourceStarted: (AnimeJapaneseTitleSource) -> Unit,
    ): AnimeJapaneseTitleResolution {
        onSourceStarted(AnimeJapaneseTitleSource.ANIZIP)
        val aniZipStartedAt = clock()
        val aniZipTitle = aniZipCatalog?.titles?.let(::selectAniZipTitle)
        attempts += AnimeJapaneseTitleAttempt(
            source = AnimeJapaneseTitleSource.ANIZIP,
            status = if (aniZipTitle == null) {
                AnimeJapaneseTitleAttemptStatus.NOT_FOUND
            } else {
                AnimeJapaneseTitleAttemptStatus.FOUND
            },
            origin = if (aniZipCatalog == null) ORIGIN_CATALOG_ABSENT else ORIGIN_CATALOG,
            durationMs = elapsedSince(aniZipStartedAt),
        )
        if (aniZipTitle != null) {
            return success(aniZipTitle, AnimeJapaneseTitleSource.ANIZIP, attempts)
        }

        onSourceStarted(AnimeJapaneseTitleSource.KITSU)
        resolveProvider(
            source = AnimeJapaneseTitleSource.KITSU,
            identifier = ids.kitsuId,
            knownHints = hints.kitsu,
            identifierResolver = if (ids.malId != null || ids.anilistId != null) {
                { kitsuMetadataClient.resolveAnimeId(ids.malId, ids.anilistId) }
            } else {
                null
            },
            fetch = { id -> listOfNotNull(kitsuMetadataClient.fetchNativeTitle(id)) },
        ).also { attempts += it.attempt }.title?.let { title ->
            return success(title, AnimeJapaneseTitleSource.KITSU, attempts)
        }

        onSourceStarted(AnimeJapaneseTitleSource.ANILIST)
        resolveProvider(
            source = AnimeJapaneseTitleSource.ANILIST,
            identifier = ids.anilistId,
            knownHints = hints.aniList,
            fetch = { id -> aniListMetadataClient.fetchTitleAliases(listOf(id))[id].orEmpty() },
        ).also { attempts += it.attempt }.title?.let { title ->
            return success(title, AnimeJapaneseTitleSource.ANILIST, attempts)
        }

        onSourceStarted(AnimeJapaneseTitleSource.MY_ANIME_LIST)
        resolveProvider(
            source = AnimeJapaneseTitleSource.MY_ANIME_LIST,
            identifier = ids.malId,
            knownHints = hints.myAnimeList,
            fetch = { id -> listOfNotNull(jikanMetadataClient.fetchNativeTitle(id)) },
        ).also { attempts += it.attempt }.title?.let { title ->
            return success(title, AnimeJapaneseTitleSource.MY_ANIME_LIST, attempts)
        }

        return AnimeJapaneseTitleResolution(
            title = null,
            source = null,
            attempts = attempts.toList(),
            cacheHit = false,
        )
    }

    private suspend fun resolveProvider(
        source: AnimeJapaneseTitleSource,
        identifier: Int?,
        knownHints: List<String>,
        identifierResolver: (suspend () -> Int?)? = null,
        fetch: suspend (Int) -> List<String>,
    ): ProviderResolution {
        val startedAt = clock()
        firstNativeTitle(knownHints)?.let { title ->
            return ProviderResolution(
                title = title,
                attempt = AnimeJapaneseTitleAttempt(
                    source = source,
                    status = AnimeJapaneseTitleAttemptStatus.FOUND,
                    origin = ORIGIN_KNOWN_HINT,
                    identifier = identifier,
                    durationMs = elapsedSince(startedAt),
                ),
            )
        }
        if (identifier == null && identifierResolver == null) {
            return ProviderResolution(
                title = null,
                attempt = AnimeJapaneseTitleAttempt(
                    source = source,
                    status = AnimeJapaneseTitleAttemptStatus.SKIPPED_NO_ID,
                    origin = providerOrigin(knownHints, identifierResolutionAttempted = false, includeApi = false),
                    durationMs = elapsedSince(startedAt),
                ),
            )
        }

        var resolvedIdentifier = identifier
        var identifierResolutionAttempted = false
        var apiAttempted = false
        return try {
            val completed = withTimeoutOrNull(providerTimeoutMs) {
                if (resolvedIdentifier == null) {
                    identifierResolutionAttempted = true
                    resolvedIdentifier = identifierResolver?.invoke().validId()
                }
                val providerId = resolvedIdentifier
                ProviderFetch(
                    title = providerId?.let {
                        apiAttempted = true
                        firstNativeTitle(fetch(it))
                    },
                    identifier = providerId,
                )
            }
            if (completed == null) {
                ProviderResolution(
                    title = null,
                    attempt = AnimeJapaneseTitleAttempt(
                        source = source,
                        status = AnimeJapaneseTitleAttemptStatus.TIMED_OUT,
                        origin = providerOrigin(knownHints, identifierResolutionAttempted, apiAttempted),
                        identifier = resolvedIdentifier,
                        durationMs = elapsedSince(startedAt),
                    ),
                )
            } else {
                ProviderResolution(
                    title = completed.title,
                    attempt = AnimeJapaneseTitleAttempt(
                        source = source,
                        status = if (completed.title == null) {
                            AnimeJapaneseTitleAttemptStatus.NOT_FOUND
                        } else {
                            AnimeJapaneseTitleAttemptStatus.FOUND
                        },
                        origin = providerOrigin(
                            knownHints = knownHints,
                            identifierResolutionAttempted = identifierResolutionAttempted,
                            includeApi = completed.identifier != null,
                        ),
                        identifier = completed.identifier,
                        durationMs = elapsedSince(startedAt),
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ProviderResolution(
                title = null,
                attempt = AnimeJapaneseTitleAttempt(
                    source = source,
                    status = AnimeJapaneseTitleAttemptStatus.FAILED,
                    origin = providerOrigin(knownHints, identifierResolutionAttempted, apiAttempted),
                    identifier = resolvedIdentifier,
                    durationMs = elapsedSince(startedAt),
                    errorType = error::class.java.simpleName.takeIf(String::isNotBlank),
                ),
            )
        }
    }

    private fun providerOrigin(
        knownHints: List<String>,
        identifierResolutionAttempted: Boolean,
        includeApi: Boolean,
    ): String = buildList {
        if (knownHints.isNotEmpty()) add(ORIGIN_KNOWN_HINT)
        if (identifierResolutionAttempted) add(ORIGIN_ID_MAPPING)
        if (includeApi) add(ORIGIN_API)
    }.ifEmpty { listOf(ORIGIN_NO_ID) }.joinToString("+")

    private fun selectAniZipTitle(titles: Map<String, String>): String? {
        return titles.entries
            .sortedWith(
                compareBy<Map.Entry<String, String>>(
                    { aniZipLanguagePriority(it.key) },
                    { it.key.lowercase(Locale.ROOT) },
                )
            )
            .asSequence()
            .mapNotNull { normalizeNativeTitle(it.value) }
            .firstOrNull()
    }

    private fun aniZipLanguagePriority(rawLanguage: String): Int {
        val language = rawLanguage.trim().lowercase(Locale.ROOT).replace('_', '-')
        return when {
            language in ANIZIP_JAPANESE_LANGUAGE_KEYS -> 0
            language.startsWith("ja-") -> 1
            language.contains("japanese") || language.contains("native") -> 2
            else -> 3
        }
    }

    private fun firstNativeTitle(values: Iterable<String>): String? {
        return values.asSequence().mapNotNull(::normalizeNativeTitle).firstOrNull()
    }

    private fun normalizeNativeTitle(rawTitle: String): String? {
        val normalized = Normalizer.normalize(rawTitle, Normalizer.Form.NFKC)
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .takeIf { it.length in 1..MAX_TITLE_LENGTH }
            ?: return null
        return normalized.takeIf(::containsJapaneseScript)
    }

    private fun containsJapaneseScript(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> return true

                else -> Unit
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private fun cacheKey(
        aniZipCatalog: AniZipEpisodeCatalog?,
        ids: ResolvedIds,
        hints: AnimeJapaneseTitleHints,
    ): CacheKey {
        val aniZipTitles = aniZipCatalog?.titles.orEmpty().entries
            .map { (language, title) ->
                language.trim().lowercase(Locale.ROOT) to title.trim()
            }
            .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
        return CacheKey(
            ids = ids,
            aniZipTitles = aniZipTitles,
            kitsuHints = hints.kitsu.normalizedCacheValues(),
            aniListHints = hints.aniList.normalizedCacheValues(),
            myAnimeListHints = hints.myAnimeList.normalizedCacheValues(),
        )
    }

    private fun List<String>.normalizedCacheValues(): List<String> {
        return asSequence()
            .map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
    }

    private fun putCache(
        key: CacheKey,
        resolution: AnimeJapaneseTitleResolution,
        now: Long,
    ) {
        pruneCache(now)
        if (cache.size >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
            cache.entries.minByOrNull { it.value.createdAtMs }?.let { oldest ->
                cache.remove(oldest.key, oldest.value)
            }
        }
        val ttl = if (resolution.title == null) negativeCacheTtlMs else positiveCacheTtlMs
        cache[key] = CacheEntry(
            resolution = resolution.copy(cacheHit = false),
            createdAtMs = now,
            expiresAtMs = now + ttl,
        )
    }

    private fun pruneCache(now: Long) {
        cache.entries.forEach { entry ->
            if (entry.value.expiresAtMs <= now) {
                cache.remove(entry.key, entry.value)
            }
        }
    }

    private fun AnimeJapaneseTitleResolution.isCacheable(): Boolean {
        if (title != null) return true
        return attempts.none { attempt ->
            attempt.status == AnimeJapaneseTitleAttemptStatus.TIMED_OUT ||
                attempt.status == AnimeJapaneseTitleAttemptStatus.FAILED ||
                attempt.status == AnimeJapaneseTitleAttemptStatus.TOTAL_TIMEOUT
        }
    }

    private fun success(
        title: String,
        source: AnimeJapaneseTitleSource,
        attempts: List<AnimeJapaneseTitleAttempt>,
    ): AnimeJapaneseTitleResolution = AnimeJapaneseTitleResolution(
        title = title,
        source = source,
        attempts = attempts.toList(),
        cacheHit = false,
    )

    private fun elapsedSince(startedAt: Long): Long = (clock() - startedAt).coerceAtLeast(0L)

    private fun Int?.validId(): Int? = this?.takeIf { it > 0 }

    private fun ResolvedIds.identifierFor(source: AnimeJapaneseTitleSource): Int? = when (source) {
        AnimeJapaneseTitleSource.ANIZIP -> null
        AnimeJapaneseTitleSource.KITSU -> kitsuId
        AnimeJapaneseTitleSource.ANILIST -> anilistId
        AnimeJapaneseTitleSource.MY_ANIME_LIST -> malId
    }

    private data class ResolvedIds(
        val kitsuId: Int?,
        val anilistId: Int?,
        val malId: Int?,
    )

    private data class CacheKey(
        val ids: ResolvedIds,
        val aniZipTitles: List<Pair<String, String>>,
        val kitsuHints: List<String>,
        val aniListHints: List<String>,
        val myAnimeListHints: List<String>,
    )

    private data class CacheEntry(
        val resolution: AnimeJapaneseTitleResolution,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    )

    private data class ProviderFetch(
        val title: String?,
        val identifier: Int?,
    )

    private data class ProviderResolution(
        val title: String?,
        val attempt: AnimeJapaneseTitleAttempt,
    )

    private companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 2_500L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 8_000L
        const val DEFAULT_POSITIVE_CACHE_TTL_MS = 12L * 60L * 60L * 1_000L
        const val DEFAULT_NEGATIVE_CACHE_TTL_MS = 10L * 60L * 1_000L
        const val MAX_CACHE_ENTRIES = 256
        const val MAX_TITLE_LENGTH = 240

        const val ORIGIN_CATALOG = "catalog"
        const val ORIGIN_CATALOG_ABSENT = "catalog_absent"
        const val ORIGIN_KNOWN_HINT = "known_hint"
        const val ORIGIN_ID_MAPPING = "id_mapping"
        const val ORIGIN_API = "api"
        const val ORIGIN_NO_ID = "no_id"
        const val ORIGIN_RESOLVER = "resolver"

        val WHITESPACE_REGEX = Regex("\\s+")
        val ANIZIP_JAPANESE_LANGUAGE_KEYS = setOf(
            "ja",
            "ja-jp",
            "jp",
            "jpn",
            "japanese",
            "native",
        )
    }
}
