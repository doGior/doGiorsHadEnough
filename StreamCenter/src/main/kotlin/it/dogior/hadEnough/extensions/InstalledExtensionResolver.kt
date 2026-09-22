package it.dogior.hadEnough.extensions

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import it.dogior.hadEnough.cache.ExpiringCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal object ExtensionCall : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ExtensionCall>
}

internal object InstalledExtensionResolver {
    const val CONCURRENCY = 3
    private const val PROVIDER_TIMEOUT_MS = 45_000L
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private val syncNames = mapOf(
        "anilist" to SyncIdName.Anilist,
        "mal" to SyncIdName.MyAnimeList,
        "kitsu" to SyncIdName.Kitsu,
        "imdb" to SyncIdName.Imdb,
    )
    private val linkOwners = ExpiringCache<List<Any>, Pair<MainAPI, ExtractorLink>>(512, 6 * 60 * 60_000L)
    private val verifierOwners = ExpiringCache<String, MainAPI>(512, 6 * 60 * 60_000L)

    private fun linkKey(link: ExtractorLink): List<Any> =
        listOf(link.source, link.name, link.url, link.referer, link.headers.toMap(), link.extractorData.orEmpty())

    fun videoInterceptor(link: ExtractorLink): Interceptor? {
        val (provider, originalLink) = linkOwners[linkKey(link)] ?: return null
        return provider.getVideoInterceptor(originalLink)
    }

    fun retainLinkOwner(original: ExtractorLink, playbackLink: ExtractorLink) {
        linkOwners[linkKey(original)]?.let { linkOwners.put(linkKey(playbackLink), it) }
    }

    suspend fun verify(extractorData: String?) {
        val provider = extractorData?.let { verifierOwners[it] } ?: return
        withContext(ExtensionCall) {
            providerCall("verifica", { _, _ -> }) { provider.extractorVerifierJob(extractorData) }
        }
    }

    suspend fun resolve(
        extension: InstalledExtension,
        context: ExtensionPlaybackContext,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        performanceMode: Boolean = false,
        onError: (String, Throwable) -> Unit = { _, _ -> },
    ): Boolean {
        val emitted = AtomicBoolean(false)
        withTimeoutOrNull(if (performanceMode) 15_000L else PROVIDER_TIMEOUT_MS) {
            for (source in extension.sources) {
                val found = resolve(source, context, isCasting, subtitleCallback, { link ->
                    emitted.set(true)
                    callback(link)
                }, performanceMode, onError)
                if (performanceMode && (found || emitted.get())) break
            }
        }
        return emitted.get()
    }

    suspend fun resolve(
        source: InstalledExtensionSource,
        context: ExtensionPlaybackContext,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        performanceMode: Boolean = false,
        onError: (String, Throwable) -> Unit = { _, _ -> },
    ): Boolean {
        if (currentCoroutineContext()[ExtensionCall.Key] != null) return false
        val api = source.api
        val movieTypes = setOf(TvType.Movie, TvType.AnimeMovie)
        val seriesTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.OVA)
        if (api.supportedTypes.none { it in if (context.isMovie) movieTypes else seriesTypes }) return false
        if (isCasting && !api.hasChromecastSupport) return false
        val emitted = AtomicBoolean(false)
        withTimeoutOrNull(if (performanceMode) 15_000L else PROVIDER_TIMEOUT_MS) {
            withContext(ExtensionCall) {
                val job = currentCoroutineContext()
                val safeSubtitle: (SubtitleFile) -> Unit = { subtitle ->
                    job.ensureActive()
                    subtitleCallback(subtitle)
                }
                val safeLink: (ExtractorLink) -> Unit = callback@{ link ->
                    job.ensureActive()
                    if (isCasting && (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT ||
                            link.url.startsWith("magnet:", ignoreCase = true))) return@callback
                    if (link.url.isBlank()) return@callback
                    linkOwners.put(linkKey(link), api to link)
                    link.extractorData?.takeIf(String::isNotBlank)?.let { verifierOwners.put(it, api) }
                    emitted.set(true)
                    callback(link)
                }
                val visited = mutableSetOf<String>()
                var loads = 0
                suspend fun tryMedia(url: String, idKind: String? = null): Boolean {
                    if (url.isBlank() || !visited.add(url) || loads >= 5) return false
                    loads++
                    val response = providerCall("scheda", onError) {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { api.load(url) }
                    } ?: return false
                    if (!ExtensionMediaMatcher.matches(context, response, idKind)) return false
                    val data = ExtensionMediaMatcher.playbackData(context, response)
                    for (payload in data) {
                        providerCall("video", onError) {
                            withTimeoutOrNull(25_000L) { api.loadLinks(payload, isCasting, safeSubtitle, safeLink) }
                        }
                        if (performanceMode && emitted.get()) break
                    }
                    return emitted.get()
                }

                for ((kind, syncName) in syncNames) {
                    val id = context.ids[kind] ?: continue
                    if (syncName !in api.supportedSyncNames) continue
                    val url = providerCall("identificativo", onError) {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { api.getLoadUrl(syncName, id) }
                    } ?: continue
                    if (tryMedia(url, kind)) return@withContext
                }
                val queries = context.titles.filter(String::isNotBlank)
                    .distinctBy(ExtensionMediaMatcher::normalizedTitle).take(3)
                for (query in queries) {
                    val results = providerCall("ricerca", onError) {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { api.search(query, 1)?.items }
                    }.orEmpty()
                    for (result in results) {
                        if (!ExtensionMediaMatcher.titleMatches(context, listOf(result.name))) continue
                        if (tryMedia(result.url)) return@withContext
                    }
                    if (loads >= 5) break
                }
            }
        }
        return emitted.get()
    }

    private suspend fun <T> providerCall(stage: String, onError: (String, Throwable) -> Unit, block: suspend () -> T): T? {
        return try {
            block()
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            onError(stage, error)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(stage, error)
            null
        } catch (error: NotImplementedError) {
            onError(stage, error)
            null
        } catch (error: LinkageError) {
            onError(stage, error)
            null
        }
    }
}
