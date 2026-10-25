package it.dogior.hadEnough

import android.util.Base64
import android.widget.Toast
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.StreamCenter.Companion.MAGNET_FILE_INDEX_REGEX
import it.dogior.hadEnough.StreamCenter.Companion.MAGNET_INFO_HASH_REGEX
import it.dogior.hadEnough.StreamCenter.Companion.STREMIO_ADDON_CONCURRENCY
import it.dogior.hadEnough.StreamCenter.Companion.STREMIO_ADDON_TIMEOUT_MS
import it.dogior.hadEnough.StreamCenter.Companion.checkedSourceDomains
import it.dogior.hadEnough.anime.metadata.selectAnimeSeasonEpisode
import it.dogior.hadEnough.anime.source.absoluteProviderUrl
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.playback.StreamingPlaybackLinks
import it.dogior.hadEnough.playback.PlaybackSource
import it.dogior.hadEnough.playback.PlaybackSourceGroup
import it.dogior.hadEnough.playback.StreamCenterPlaybackInsights
import it.dogior.hadEnough.playback.StreamCenterSourceOrder
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.runCatchingCancellable
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup

internal fun StreamCenter.activeStremioResolverAddons(): List<StreamCenterStremioAddon> {
    val addonsByKey = StreamCenterPlugin.getStremioAddons(sharedPref)
        .associateBy(StreamCenterStremioAddon::key)
    return StreamCenterPlugin.getSourcePriorityOrder(sharedPref).asSequence()
        .distinct()
        .mapNotNull(addonsByKey::get)
        .filter { addon -> StreamCenterPlugin.isStremioAddonEnabled(sharedPref, addon.key) }
        .filter { addon ->
            addon.resources.any { resource ->
                resource.name.equals("stream", ignoreCase = true) ||
                    resource.name.equals("subtitles", ignoreCase = true)
            }
        }
        .toList()
}

internal fun StreamCenter.activeStremioResolversNeedKitsuId(): Boolean {
    return activeStremioResolverAddons().any { addon ->
        addon.resources.any { resource ->
            val isPlaybackResource =
                resource.name.equals("stream", ignoreCase = true) ||
                    resource.name.equals("subtitles", ignoreCase = true)
            val supportedTypes = resource.types.ifEmpty { addon.types }
            val supportsAnime = supportedTypes.isEmpty() || supportedTypes.any { type ->
                type.equals("anime", ignoreCase = true) ||
                    type.equals("series", ignoreCase = true)
            }
            val prefixes = resource.idPrefixes.ifEmpty { addon.idPrefixes }
            val needsKitsu = prefixes.isNotEmpty() &&
                prefixes.any { prefix -> "kitsu:1".startsWith(prefix, ignoreCase = true) } &&
                prefixes.none { prefix -> "anilist:1".startsWith(prefix, ignoreCase = true) }
            isPlaybackResource && supportsAnime && needsKitsu
        }
    }
}

internal fun StreamCenter.activeStremioResolversNeedImdbId(contentType: String): Boolean {
    return activeStremioResolverAddons().any { addon ->
        addon.resources.any { resource ->
            val isPlaybackResource =
                resource.name.equals("stream", ignoreCase = true) ||
                    resource.name.equals("subtitles", ignoreCase = true)
            val supportedTypes = resource.types.ifEmpty { addon.types }
            val supportsContentType = supportedTypes.isEmpty() ||
                supportedTypes.any { it.equals(contentType, ignoreCase = true) }
            val prefixes = resource.idPrefixes.ifEmpty { addon.idPrefixes }
            val supportsImdb = prefixes.any { prefix ->
                "tt0000000".startsWith(prefix, ignoreCase = true) ||
                    "imdb:tt0000000".startsWith(prefix, ignoreCase = true)
            }
            val supportsTmdb = prefixes.any { prefix -> "tmdb:1".startsWith(prefix, ignoreCase = true) }
            isPlaybackResource && supportsContentType && prefixes.isNotEmpty() && supportsImdb && !supportsTmdb
        }
    }
}

internal suspend fun StreamCenter.resolveStreamCenterLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    if (currentCoroutineContext()[ExtensionCall.Key] != null) return false
    StreamCenterVpnGuard.requireInternetAccess(sharedPref)
    val iptv = runCatching { JSONObject(data) }.getOrNull()
        ?.takeIf { it.optBoolean("streamcenterIptv") }
    if (iptv != null) {
        val channelName = iptv.optString("name", "TV")
        val streamUrl = iptv.optString("url").takeIf(String::isNotBlank) ?: run {
            StreamCenterLogger.logTab(
                tabName = channelName,
                action = "Risoluzione stream IPTV non riuscita",
                metadata = mapOf("motivo" to "url_mancante", "casting" to isCasting),
                level = StreamCenterLogger.Level.WARNING,
            )
            return false
        }
        StreamCenterLogger.logTab(
            tabName = channelName,
            action = "Risoluzione stream IPTV avviata",
            metadata = mapOf(
                "casting" to isCasting,
                "header_personalizzati" to (iptv.has("userAgent") || iptv.has("referer")),
            ),
        )
        val linkHeaders = buildMap {
            iptv.optString("userAgent").takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
            iptv.optString("referer").takeIf(String::isNotBlank)?.let { put("Referer", it) }
        }
        val resolved = resolveIptvStream(streamUrl, linkHeaders)
        callback(newExtractorLink(name, channelName, resolved.url, resolved.type) {
            quality = Qualities.Unknown.value
            headers = linkHeaders
        })
        StreamCenterLogger.logTab(
            tabName = channelName,
            action = "Stream IPTV risolto",
            metadata = mapOf("tipo_stream" to resolved.type.toString(), "header_personalizzati" to linkHeaders.isNotEmpty()),
        )
        return true
    }
    val playbackData = runCatching { parseJson<StreamCenterPlaybackData>(data) }.getOrNull()
    playbackData?.animeSeason?.let { target ->
        require(target.anilistId > 0 && target.episode in 1..3000) { "Episodio anime non valido" }
        val release = loadAnilistMedia(target.anilistId, target.malId, cacheable = true) as? AnimeLoadResponse
            ?: return false
        if (InstalledExtensionSources.enabledKeys(sharedPref).isNotEmpty()) release.attachExtensionPlayback()
        val episode = selectAnimeSeasonEpisode(release, target) ?: return false
        return loadLinks(episode.data, isCasting, subtitleCallback, callback)
    }
    val playbackLogTab = playbackData?.stremio?.anilistId?.let { "Anime AniList $it" }
        ?: playbackData?.stremio?.tmdbId?.let { "Contenuto TMDB $it" }
        ?: playbackData?.streamingCommunity?.tmdbId?.let { "Contenuto TMDB $it" }
        ?: "Riproduzione"
    StreamCenterLogger.logTab(
        tabName = playbackLogTab,
        action = "Risoluzione fonti di riproduzione avviata",
        metadata = mapOf(
            "casting" to isCasting,
            "modalita_prestazioni" to performanceMode,
            "payload_valido" to (playbackData != null),
            "animeunity_disponibile" to (playbackData?.animeUnity != null),
            "animeworld_disponibili" to playbackData?.animeWorld.orEmpty().size,
            "animesaturn_disponibili" to playbackData?.animeSaturn.orEmpty().size,
            "streamingcommunity_disponibile" to (playbackData?.streamingCommunity != null),
            "stremio_disponibile" to (playbackData?.stremio != null),
            "torrent_disponibile" to (playbackData?.torrent != null),
            "torrent_abilitati" to StreamCenterPlugin.isTorrentEnabled(sharedPref),
            "link_p2p_esclusi_per_casting" to isCasting,
        ),
        level = if (playbackData == null) StreamCenterLogger.Level.WARNING else StreamCenterLogger.Level.INFO,
    )
    val sourceOrder = StreamCenterSourceOrder()
    val playbackLinks = StreamingPlaybackLinks(this@resolveStreamCenterLinks::sourceLinkDedupKey) { source, original ->
        val origin = StreamCenterPlaybackInsights.originFor(source, original)
        val link = sourceOrder.prepare(
            source.group, original,
            playbackProvider = name.takeIf { StreamCenterVidxGoExtractor.ownsLink(original) },
            tier = StreamCenterPlaybackInsights.tierFor(origin),
        )
        if (source.group == PlaybackSourceGroup.EXTENSION) InstalledExtensionResolver.retainLinkOwner(original, link)
        StreamCenterPlaybackInsights.remember(link, origin)
        callback(link)
    }
    val emittedSubtitleKeys = ConcurrentHashMap.newKeySet<String>()
    val resultCallbackLock = Any()
    fun collectLink(source: PlaybackSource): (ExtractorLink) -> Unit = linkCallback@{ link ->
        val isPeerToPeer =
            link.type == ExtractorLinkType.MAGNET ||
                link.type == ExtractorLinkType.TORRENT ||
                link.url.startsWith("magnet:", ignoreCase = true)
        if (isCasting && isPeerToPeer) return@linkCallback
        playbackLinks.add(source, link)
    }
    fun sourceTitle(sourceKey: String): String = StreamCenterPlugin.streamingSources
        .firstOrNull { source -> source.key == sourceKey }
        ?.title
        ?: sourceKey
    fun siteCallback(sourceKey: String) =
        collectLink(PlaybackSource(PlaybackSourceGroup.HTTPS, sourceKey, sourceTitle(sourceKey)))
    val uniqueSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
        synchronized(resultCallbackLock) {
            if (emittedSubtitleKeys.add(subtitle.url.trim().substringBefore('#'))) {
                subtitleCallback(subtitle)
                StreamCenterLogger.logTab(
                    tabName = playbackLogTab,
                    action = "Sottotitolo emesso",
                    metadata = mapOf("sottotitoli_univoci_emessi" to emittedSubtitleKeys.size),
                )
            }
        }
    }
    val tasksBySource = linkedMapOf<String, MutableList<suspend () -> Boolean>>()
    val torrentTasks = linkedMapOf<String, suspend () -> Boolean>()
    val stremioTasks = linkedMapOf<String, suspend () -> Boolean>()
    val extensionTasks = linkedMapOf<String, suspend () -> Boolean>()
    fun loggedSourceTask(
        sourceName: String,
        warnWhenEmpty: Boolean = true,
        task: suspend () -> Boolean,
    ): suspend () -> Boolean = suspend {
        StreamCenterLogger.logTab(
            tabName = playbackLogTab,
            action = "Tentativo fonte riproduzione avviato",
            metadata = mapOf("fonte" to sourceName),
        )
        try {
            val result = task()
            StreamCenterLogger.logTab(
                tabName = playbackLogTab,
                action = "Tentativo fonte riproduzione completato",
                metadata = mapOf("fonte" to sourceName, "link_trovato" to result),
                level = if (result || !warnWhenEmpty) {
                    StreamCenterLogger.Level.INFO
                } else {
                    StreamCenterLogger.Level.WARNING
                },
            )
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StreamCenterLogger.logTabError(
                tabName = playbackLogTab,
                action = "Tentativo fonte riproduzione non riuscito",
                throwable = error,
                metadata = mapOf("fonte" to sourceName),
            )
            throw error
        }
    }
    fun addTask(sourceKey: String, task: suspend () -> Boolean) {
        tasksBySource.getOrPut(sourceKey) { mutableListOf() } +=
            loggedSourceTask(sourceName = sourceTitle(sourceKey), task = task)
    }

    playbackData?.animeUnity
        ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) }
        ?.let { animeUnityPlayback ->
            addTask(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY) {
                loadAnimeUnityLinks(
                    playbackData = animeUnityPlayback,
                    subtitleCallback = uniqueSubtitleCallback,
                    callback = siteCallback(StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY),
                )
            }
        }

    if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD)) {
        playbackData?.animeWorld.orEmpty().forEach { animeWorldPlayback ->
            addTask(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD) {
                loadAnimeWorldLink(
                    playbackData = animeWorldPlayback,
                    subtitleCallback = uniqueSubtitleCallback,
                    callback = siteCallback(StreamCenterPlugin.PREF_SOURCE_ANIMEWORLD),
                )
            }
        }
    }

    if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN)) {
        playbackData?.animeSaturn.orEmpty().forEach { animeSaturnPlayback ->
            addTask(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN) {
                loadAnimeSaturnLink(
                    playbackData = animeSaturnPlayback,
                    subtitleCallback = uniqueSubtitleCallback,
                    callback = siteCallback(StreamCenterPlugin.PREF_SOURCE_ANIMESATURN),
                )
            }
        }
    }

    playbackData?.streamingCommunity
        ?.takeIf { isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_VIXCLOUD) }
        ?.let { streamingCommunityPlayback ->
            addTask(StreamCenterPlugin.PREF_SOURCE_VIXCLOUD) {
                loadVixCloudLinks(
                    playbackData = streamingCommunityPlayback,
                    subtitleCallback = uniqueSubtitleCallback,
                    callback = siteCallback(StreamCenterPlugin.PREF_SOURCE_VIXCLOUD),
                )
            }
        }

    playbackData?.let { directPlaybackTarget(it) }?.let { target ->
        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_VIXSRC)) {
            addTask(StreamCenterPlugin.PREF_SOURCE_VIXSRC) {
                loadVixSrcLinks(
                    target = target,
                    subtitleCallback = uniqueSubtitleCallback,
                    callback = siteCallback(StreamCenterPlugin.PREF_SOURCE_VIXSRC),
                )
            }
        }
        if (isSourceEnabled(StreamCenterPlugin.PREF_SOURCE_VIDXGO)) {
            addTask(StreamCenterPlugin.PREF_SOURCE_VIDXGO) {
                loadVidxGoLinks(
                    target = target,
                    subtitleCallback = uniqueSubtitleCallback,
                    callback = siteCallback(StreamCenterPlugin.PREF_SOURCE_VIDXGO),
                )
            }
        }
    }

    if (StreamCenterPlugin.isTorrentEnabled(sharedPref) && !isCasting) {
        playbackData?.torrent?.let { torrentContext ->
            val torrentFilters = StreamCenterTorrentPreferences.read(sharedPref)
            val domains = StreamCenterTorrentPreferences.enabledDomains(sharedPref)
            torrentTasks["torrent-ext"] = loggedSourceTask(
                sourceName = "EXT",
                warnWhenEmpty = false,
            ) {
                StreamCenterTorrentResolver.load(
                    domains = domains,
                    context = torrentContext,
                    filters = torrentFilters,
                    callback = collectLink(PlaybackSource(PlaybackSourceGroup.TORRENT, "torrent-ext", "EXT")),
                    performanceMode = performanceMode,
                    logTabName = playbackLogTab,
                )
            }
        }
    }

    playbackData?.stremio?.let { stremioContext ->
        val catalogAddon = stremioContext.catalogAddonKey?.let { addonKey ->
            catalogDefinition?.stremioAddon?.takeIf { addon -> addon.key == addonKey }
                ?: StreamCenterCatalogs.stremioCatalogAddon(sharedPref, addonKey)
        }
        (listOfNotNull(catalogAddon) + activeStremioResolverAddons())
            .distinctBy(StreamCenterStremioAddon::key)
            .forEach { addon ->
                stremioTasks[addon.key] = suspend {
                    StreamCenterLogger.logTab(
                        tabName = playbackLogTab,
                        action = "Tentativo add-on Stremio avviato",
                        metadata = mapOf("add_on" to addon.name, "modalita_prestazioni" to performanceMode),
                    )
                    try {
                        val result = withTimeoutOrNull(STREMIO_ADDON_TIMEOUT_MS) {
                            StreamCenterStremioAddonClient.load(
                                addon = addon,
                                context = stremioContext,
                                subtitleCallback = uniqueSubtitleCallback,
                                callback = collectLink(PlaybackSource(PlaybackSourceGroup.EXTENSION, addon.key, addon.name)),
                                stopAfterFirstResult = performanceMode,
                            )
                        } ?: false
                        StreamCenterLogger.logTab(
                            tabName = playbackLogTab,
                            action = "Tentativo add-on Stremio completato",
                            metadata = mapOf("add_on" to addon.name, "link_trovato" to result),
                            level = if (result) StreamCenterLogger.Level.INFO else StreamCenterLogger.Level.WARNING,
                        )
                        result
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        StreamCenterLogger.logTabError(
                            tabName = playbackLogTab,
                            action = "Tentativo add-on Stremio non riuscito",
                            throwable = error,
                            metadata = mapOf("add_on" to addon.name),
                        )
                        throw error
                    }
                }
            }
    }

    playbackData?.extensions?.let { extensionContext ->
        InstalledExtensionSources.enabled(sharedPref).forEach { source ->
            extensionTasks[source.key] = loggedSourceTask(source.name) {
                InstalledExtensionResolver.resolve(
                    extension = source,
                    context = extensionContext,
                    isCasting = isCasting,
                    subtitleCallback = uniqueSubtitleCallback,
                    callback = collectLink(PlaybackSource(PlaybackSourceGroup.EXTENSION, source.key, source.name)),
                    performanceMode = performanceMode,
                    onError = { stage, error ->
                        StreamCenterLogger.logTabError(
                            tabName = playbackLogTab,
                            action = "Estensione installata: $stage non riuscita",
                            throwable = error,
                            metadata = mapOf("fonte" to source.name),
                        )
                    },
                )
            }
        }
    }

    val priorityOrder = StreamCenterPlugin.getSourcePriorityOrder(sharedPref)
    val orderedKeys = tasksBySource.keys.sortedBy { key ->
        priorityOrder.indexOf(key).takeIf { it >= 0 } ?: priorityOrder.size
    }
    StreamCenterLogger.logTab(
        tabName = playbackLogTab,
        action = "Piano risoluzione fonti preparato",
        metadata = mapOf(
            "fonti_native" to orderedKeys.size,
            "fonti_torrent" to torrentTasks.size,
            "add_on_stremio" to stremioTasks.size,
            "estensioni_installate" to extensionTasks.size,
            "ordine_priorita" to priorityOrder.joinToString(", "),
            "timeout_fonte_ms" to sourceGroupTimeoutMs,
            "timeout_torrent_ms" to TORRENT_SOURCE_TIMEOUT_MS,
            "timeout_torrent_totale_ms" to TORRENT_TOTAL_TIMEOUT_MS,
        ),
    )
    if (playbackData != null && listOf(tasksBySource, torrentTasks, stremioTasks, extensionTasks).all { it.isEmpty() }) {
        val torrentOnly = isCasting && playbackData.torrent != null && StreamCenterPlugin.isTorrentEnabled(sharedPref)
        CommonActivity.showToast(
            if (torrentOnly) {
                "I torrent non si possono trasmettere: attiva un add-on Stremio o un'estensione in Impostazioni › Contenuti › Fonti"
            } else {
                "Nessuna fonte attiva: abilitane una in Impostazioni › Contenuti › Fonti"
            },
            Toast.LENGTH_LONG,
        )
        return false
    }
    if (performanceMode) {
        val addonKeys = stremioTasks.keys.sortedBy { key ->
            priorityOrder.indexOf(key).takeIf { it >= 0 } ?: priorityOrder.size
        }
        val performanceKeys = (torrentTasks.keys + orderedKeys + addonKeys + extensionTasks.keys).distinct()
        var torrentDeadlineNanos: Long? = null
        for (sourceKey in performanceKeys) {
            when {
                extensionTasks[sourceKey] != null -> extensionTasks.getValue(sourceKey).invoke()
                stremioTasks[sourceKey] != null -> stremioTasks.getValue(sourceKey).invoke()
                torrentTasks[sourceKey] != null -> {
                    val deadline = torrentDeadlineNanos
                        ?: (
                            System.nanoTime() +
                                TORRENT_PERFORMANCE_TOTAL_TIMEOUT_MS * 1_000_000L
                            ).also { torrentDeadlineNanos = it }
                    val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L)
                        .coerceAtLeast(0L)
                    if (remainingMs > 0L) {
                        withTimeoutOrNull(
                            minOf(TORRENT_SOURCE_TIMEOUT_MS, remainingMs),
                        ) {
                            torrentTasks.getValue(sourceKey).invoke()
                        } ?: false
                    } else {
                        false
                    }
                }
                else -> withTimeoutOrNull(sourceGroupTimeoutMs) {
                    runParallelSourceTasks(tasksBySource[sourceKey].orEmpty())
                }
                    ?: false
            }
            if (playbackLinks.size > 0) {
                StreamCenterLogger.logTab(
                    tabName = playbackLogTab,
                    action = "Risoluzione fonti di riproduzione completata",
                    metadata = mapOf(
                        "esito" to "link_trovato",
                        "fonte_risolutiva" to sourceKey,
                        "link_univoci_emessi" to playbackLinks.size,
                        "sottotitoli_univoci_emessi" to emittedSubtitleKeys.size,
                    ),
                )
                return true
            }
        }
        val result = playbackLinks.size > 0
        StreamCenterLogger.logTab(
            tabName = playbackLogTab,
            action = "Risoluzione fonti di riproduzione completata",
            metadata = mapOf(
                "esito" to if (result) "link_trovato" else "nessun_link",
                "link_univoci_emessi" to playbackLinks.size,
                "sottotitoli_univoci_emessi" to emittedSubtitleKeys.size,
            ),
            level = if (result) StreamCenterLogger.Level.INFO else StreamCenterLogger.Level.WARNING,
        )
        return result
    }
    val result = supervisorScope {
        val extensionDeferred = extensionTasks.values.toList().takeIf { it.isNotEmpty() }?.let { tasks ->
            async(Dispatchers.IO) {
                runParallelSourceTasks(tasks, InstalledExtensionResolver.CONCURRENCY)
            }
        }
        val stremioDeferred = stremioTasks.values.toList().takeIf { it.isNotEmpty() }?.let { tasks ->
            async(Dispatchers.IO) {
                runParallelSourceTasks(tasks, STREMIO_ADDON_CONCURRENCY)
            }
        }
        val torrentDeferred = torrentTasks.values.toList().takeIf { it.isNotEmpty() }?.let { tasks ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(TORRENT_TOTAL_TIMEOUT_MS) {
                    runParallelSourceTasks(tasks)
                } ?: false
            }
        }
        val nativeDeferred = orderedKeys.takeIf { it.isNotEmpty() }?.let { sourceKeys ->
            async(Dispatchers.IO) {
                sourceKeys.fold(false) { anySourceLoaded, sourceKey ->
                    val sourceLoaded = withTimeoutOrNull(sourceGroupTimeoutMs) {
                        runParallelSourceTasks(tasksBySource[sourceKey].orEmpty())
                    } ?: false
                    anySourceLoaded || sourceLoaded
                }
            }
        }
        nativeDeferred?.await()
        torrentDeferred?.await()
        stremioDeferred?.await()
        extensionDeferred?.await()
        playbackLinks.size > 0
    }
    StreamCenterLogger.logTab(
        tabName = playbackLogTab,
        action = "Risoluzione fonti di riproduzione completata",
        metadata = mapOf(
            "esito" to if (result) "link_trovato" else "nessun_link",
            "link_univoci_emessi" to playbackLinks.size,
            "sottotitoli_univoci_emessi" to emittedSubtitleKeys.size,
        ),
        level = if (result) StreamCenterLogger.Level.INFO else StreamCenterLogger.Level.WARNING,
    )
    return result
}

internal fun StreamCenter.sourceLinkDedupKey(link: ExtractorLink): String {
    val normalizedUrl = link.url.trim().substringBefore('#')
    if (normalizedUrl.startsWith("magnet:?", ignoreCase = true)) {
        val infoHash = MAGNET_INFO_HASH_REGEX.find(normalizedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase(Locale.ROOT)
        if (infoHash != null) {
            val fileIndex = MAGNET_FILE_INDEX_REGEX.find(normalizedUrl)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            return "magnet|$infoHash|$fileIndex"
        }
    }
    return buildString {
        append(normalizedUrl)
        append('|')
        append(link.referer)
        append('|')
        link.headers.entries
            .sortedBy { entry -> entry.key.lowercase(Locale.ROOT) }
            .forEach { entry ->
                append(entry.key.lowercase(Locale.ROOT))
                append('=')
                append(entry.value)
                append(';')
            }
    }
}

internal suspend fun StreamCenter.runParallelSourceTasks(
    tasks: List<suspend () -> Boolean>,
    maxConcurrency: Int = tasks.size.coerceAtLeast(1),
): Boolean {
    if (tasks.isEmpty()) return false
    return supervisorScope {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        tasks.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        task()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
        }.awaitAll().any { it }
    }
}

internal fun StreamCenter.isSourceEnabled(prefKey: String): Boolean {
    return StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, prefKey)
}

internal fun StreamCenter.ensureUpdatedSourceDomain(prefKey: String) {
    if (!StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref)) return
    synchronized(checkedSourceDomains) {
        if (!checkedSourceDomains.add(prefKey)) return
    }
    backgroundScope.launch {
        val result = runCatching {
            val baseUrl = StreamCenterPlugin.getSourceBaseUrl(sharedPref, prefKey)
            if (baseUrl.isBlank()) return@runCatching false
            val response = app.get(baseUrl, headers = headers, timeout = 15L)
            val finalUrl = response.url
            val newHost = hostOf(finalUrl)
            val moved = response.code in 200..299 &&
                finalUrl.startsWith("http") &&
                newHost.isNotBlank() &&
                !newHost.equals(hostOf(baseUrl), ignoreCase = true)
            if (moved) {
                val scheme = finalUrl.substringBefore("://")
                StreamCenterPlugin.setSourceBaseUrl(sharedPref, prefKey, "$scheme://$newHost")
            }
            moved
        }
        when {
            result.isFailure -> synchronized(checkedSourceDomains) { checkedSourceDomains.remove(prefKey) }
            result.getOrDefault(false) -> resetSourceSession(prefKey)
        }
    }
}

internal fun StreamCenter.resetSourceSession(prefKey: String) {
    when (prefKey) {
        StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY -> {
            streamingCommunityClient.resetSession()
        }
        StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY -> {
                animeUnitySourceClient.resetSession()
        }
    }
}

internal fun StreamCenter.buildAnimeUnityPlayerSources(playbackData: AnimeUnityPlaybackData): List<AnimeUnityPlayerSource> {
    val sources = mutableListOf<AnimeUnityPlayerSource>()
    val seen = linkedSetOf<String>()

    fun add(url: String?, label: String) {
        val normalizedUrl = url?.takeIf(String::isNotBlank) ?: return
        if (seen.add(normalizedUrl)) {
            sources += AnimeUnityPlayerSource(label = label, url = normalizedUrl)
        }
    }

    when (playbackData.preferredUrl) {
        playbackData.dubUrl -> {
            add(playbackData.dubUrl, "[DUB]")
            add(playbackData.subUrl, "[SUB]")
        }
        playbackData.subUrl -> {
            add(playbackData.subUrl, "[SUB]")
            add(playbackData.dubUrl, "[DUB]")
        }
        else -> {
            add(playbackData.preferredUrl, "[SOURCE]")
            add(playbackData.subUrl, "[SUB]")
            add(playbackData.dubUrl, "[DUB]")
        }
    }

    return sources
}

internal fun StreamCenter.animeSourceDisplayName(provider: String, label: String): String {
    val audio = when {
        label.contains("DUB", ignoreCase = true) -> "\uD83C\uDDEE\uD83C\uDDF9 (DUB)"
        label.contains("SUB", ignoreCase = true) -> "\uD83C\uDDEF\uD83C\uDDF5 (SUB)"
        else -> "Fonte principale"
    }
    return "$provider - $audio"
}

internal suspend fun StreamCenter.loadAnimeUnityLinks(
    playbackData: AnimeUnityPlaybackData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val tasks = buildAnimeUnityPlayerSources(playbackData).map { source ->
        suspend {
            val embedUrl = getAnimeUnityEmbedUrl(source.url)
            if (embedUrl.isNullOrBlank()) {
                false
            } else {
                StreamCenterVixCloudExtractor(
                    sourceName = animeSourceDisplayName("VixCloud", source.label),
                    displayName = animeSourceDisplayName("AnimeUnity", source.label),
                ).getUrl(
                    url = embedUrl,
                    referer = animeUnityUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                true
            }
        }
    }
    return runParallelSourceTasks(tasks)
}

internal suspend fun StreamCenter.getAnimeUnityEmbedUrl(playerUrl: String): String? {
    val html = fetchText {
        app.get(
            playerUrl,
        ).text
    }

    return Jsoup.parse(html, playerUrl)
        .selectFirst("video-player")
        ?.attr("embed_url")
        ?.takeIf(String::isNotBlank)
}

internal suspend fun StreamCenter.loadAnimeWorldLink(
    playbackData: AnimeWorldPlaybackData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val infoUrl = "$animeWorldUrl/api/episode/info?id=${URLEncoder.encode(playbackData.episodeToken, "UTF-8")}"
    val text = runCatching {
        fetchText {
            app.get(
                infoUrl,
                headers = headers + mapOf("Referer" to playbackData.pageUrl),
            ).text
        }
    }.getOrNull() ?: return false

    val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
    val grabber = json.optNullableString("grabber") ?: return false
    val target = json.optNullableString("target").orEmpty()

    if (target.contains("listeamed.net", ignoreCase = true) ||
        grabber.contains("listeamed.net", ignoreCase = true)
    ) {
        return runCatching {
            loadExtractor(grabber, animeWorldUrl, subtitleCallback, callback)
        }.getOrDefault(false)
    }

    emitDirectVideoLink(
        source = "AnimeWorld",
        name = animeSourceDisplayName("AnimeWorld", playbackData.label),
        url = grabber,
        referer = animeWorldUrl,
        callback = callback,
    )
    return true
}

internal suspend fun StreamCenter.loadAnimeSaturnLink(
    playbackData: AnimeSaturnPlaybackData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val html = runCatching {
        fetchText {
            app.get(
                playbackData.watchUrl,
                headers = headers,
            ).text
        }
    }.getOrNull() ?: return false

    val doc = Jsoup.parse(html, playbackData.watchUrl)
    val embedUrl = doc.selectFirst("iframe#watch-iframe[src], iframe[src*='play.saturncdn.net'][src]")
        ?.attr("src")
        ?.takeIf(String::isNotBlank)
        ?.let { absoluteProviderUrl(animeSaturnUrl, it) }
        ?: Regex(""""initialVideoUrl"\s*:\s*"([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
        ?: return false

    val videoUrl = getAnimeSaturnVideoUrl(embedUrl) ?: return false
    if (videoUrl.contains("youtube.com/embed/", ignoreCase = true)) {
        return runCatching {
            loadExtractor(videoUrl, animeSaturnUrl, subtitleCallback, callback)
        }.getOrDefault(false)
    }

    emitDirectVideoLink(
        source = "AnimeSaturn",
        name = animeSourceDisplayName("AnimeSaturn", playbackData.label),
        url = videoUrl,
        referer = embedUrl,
        callback = callback,
    )
    return true
}

internal suspend fun StreamCenter.getAnimeSaturnVideoUrl(embedUrl: String): String? {
    val match = Regex("""/embed/(\d+)\?token=([^&]+)&expires=(\d+)""").find(embedUrl) ?: return null
    val id = match.groupValues.getOrNull(1) ?: return null
    val token = match.groupValues.getOrNull(2) ?: return null
    val expires = match.groupValues.getOrNull(3) ?: return null
    val baseUrl = embedUrl.substringBefore("/embed/")
    val playlistUrl = "$baseUrl/embed/$id/playlist?token=$token&expires=$expires"
    val text = runCatching {
        fetchText {
            app.get(
                playlistUrl,
                headers = headers + mapOf("Referer" to embedUrl),
            ).text
        }
    }.getOrNull() ?: return null
    val encoded = runCatching { JSONObject(text).optNullableString("d") }.getOrNull() ?: return null
    return decodeAnimeSaturnPayload(encoded, token)?.let { decoded ->
        if (decoded.startsWith("youtube/")) {
            "https://www.youtube.com/embed/${decoded.removePrefix("youtube/")}"
        } else {
            decoded
        }
    }?.takeIf(String::isNotBlank)
}

internal fun StreamCenter.decodeAnimeSaturnPayload(encoded: String, key: String): String? {
    if (key.isBlank()) return null
    val decodedBytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
    val keyBytes = key.toByteArray(Charsets.UTF_8)
    val output = ByteArray(decodedBytes.size)
    decodedBytes.forEachIndexed { index, byte ->
        output[index] = (byte.toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
    }
    return String(output, Charsets.UTF_8)
}

internal suspend fun StreamCenter.emitDirectVideoLink(
    source: String,
    name: String,
    url: String,
    referer: String,
    callback: (ExtractorLink) -> Unit,
) {
    callback(
        newExtractorLink(
            source = source,
            name = name,
            url = url,
            type = INFER_TYPE,
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
        }
    )
}

internal suspend fun StreamCenter.loadVixCloudLinks(
    playbackData: StreamingCommunityPlaybackData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val iframeSrc = runCatching {
        val html = fetchText {
            app.get(
                playbackData.iframeUrl,
            ).body.string()
        }
        Jsoup.parse(html, playbackData.iframeUrl)
            .selectFirst("iframe")
            ?.attr("src")
            ?.takeIf(String::isNotBlank)
    }.getOrNull()
    if (iframeSrc.isNullOrBlank()) return false

    return runCatching {
        StreamCenterVixCloudExtractor(
            sourceName = "VixCloud",
            displayName = "VixCloud",
        ).getUrl(
            url = iframeSrc,
            referer = streamingCommunityRootUrl,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
        true
    }.getOrDefault(false)
}

internal fun StreamCenter.directPlaybackTarget(playbackData: StreamCenterPlaybackData): DirectPlaybackTarget? {
    val streamingCommunity = playbackData.streamingCommunity
    val stremio = playbackData.stremio
        ?.takeUnless { context -> context.contentTypes.any { it.equals("anime", ignoreCase = true) } }
    val type = streamingCommunity?.type
        ?: stremio?.contentTypes?.firstNotNullOfOrNull { contentType ->
            when (contentType.lowercase(Locale.ROOT)) {
                "movie" -> "movie"
                "series" -> "tv"
                else -> null
            }
        }
        ?: return null
    return DirectPlaybackTarget(
        type = type,
        tmdbId = streamingCommunity?.tmdbId
            ?: playbackData.tmdbUrl?.let { extractTmdbId(it) }?.toIntOrNull()
            ?: stremio?.tmdbId?.let { Regex("""\d+""").find(it)?.value }?.toIntOrNull(),
        imdbId = streamingCommunity?.imdbId ?: stremio?.imdbId,
        seasonNumber = if (streamingCommunity != null) streamingCommunity.seasonNumber else stremio?.season,
        episodeNumber = if (streamingCommunity != null) streamingCommunity.episodeNumber else stremio?.episode,
    )
}

internal suspend fun StreamCenter.loadVixSrcLinks(
    target: DirectPlaybackTarget,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val vixSrcUrl = buildVixSrcUrl(target) ?: return false

    return runCatching {
        StreamCenterVixSrcExtractor().getUrl(
            url = vixSrcUrl,
            referer = "$vixSrcBaseUrl/",
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
        true
    }.getOrDefault(false)
}

internal suspend fun StreamCenter.loadVidxGoLinks(
    target: DirectPlaybackTarget,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val rawImdbId = target.imdbId ?: return false
    val imdbNumber = rawImdbId.removePrefix("tt")

    val targetUrl = if (target.type == "movie") {
        "$vidxGoUrl/$imdbNumber"
    } else {
        val season = target.seasonNumber ?: return false
        val episode = target.episodeNumber ?: return false
        "$vidxGoUrl/t/$imdbNumber/$season/$episode"
    }

    var emitted = false
    runCatchingCancellable {
        StreamCenterVidxGoExtractor().getUrl(
            url = targetUrl,
            referer = "$vidxGoUrl/",
            subtitleCallback = subtitleCallback,
            callback = { link ->
                emitted = true
                callback(link)
            },
        )
    }
    return emitted
}

internal fun StreamCenter.buildVixSrcUrl(target: DirectPlaybackTarget): String? {
    val tmdbId = target.tmdbId ?: return null
    return if (target.type == "movie") {
        "$vixSrcBaseUrl/movie/$tmdbId"
    } else {
        val seasonNumber = target.seasonNumber ?: return null
        val episodeNumber = target.episodeNumber ?: return null
        "$vixSrcBaseUrl/tv/$tmdbId/$seasonNumber/$episodeNumber"
    }
}
