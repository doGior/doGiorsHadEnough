package it.dogior.hadEnough.stremio

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class StreamCenterStremioResource(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)

internal data class StreamCenterStremioAddon(
    val key: String,
    val manifestUrl: String,
    val id: String,
    val name: String,
    val version: String?,
    val logoUrl: String? = null,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val resources: List<StreamCenterStremioResource> = emptyList(),
)

internal data class StreamCenterStremioPlaybackContext(
    val contentTypes: List<String>,
    val stremioId: String? = null,
    val stremioVideoId: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: Int? = null,
    val simklId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

internal object StreamCenterStremioAddonClient {
    private const val LOG_TAG = "StreamCenterStremio"
    private const val MANIFEST_MAX_SIZE = 512_000
    private const val RESPONSE_MAX_SIZE = 2_000_000
    private const val RESOURCE_HTTP_TIMEOUT_SECONDS = 10L
    private const val RESOURCE_REQUEST_TIMEOUT_MILLIS = 12_000L
    private const val EXTRACTOR_TIMEOUT_MILLIS = 15_000L
    private const val SUBTITLE_REQUEST_BUDGET_MILLIS = 36_000L
    private const val MAX_CONCURRENT_STREAM_REQUESTS = 8
    private const val MAX_CONCURRENT_SUBTITLE_REQUESTS = 8
    private const val MAX_CONCURRENT_EXTERNAL_EXTRACTORS = 4
    private const val RESOURCE_WAVE_SIZE = 8
    private const val PERFORMANCE_RESOURCE_WAVE_SIZE = 4
    private const val MAX_REQUESTS_PER_TYPE = 12
    private const val MAX_REQUESTS_PER_RESOURCE = 20
    private const val MAX_TOTAL_RESOURCE_REQUESTS = 20
    private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"

    suspend fun resolveImdbId(
        contentType: String,
        titleCandidates: List<String>,
        year: Int?,
    ): String? {
        val type = contentType.lowercase(Locale.ROOT).takeIf { it == "movie" || it == "series" }
            ?: return null
        val candidates = titleCandidates
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizedTitle)
            .take(3)
        candidates.forEach { title ->
            val response = runCatching {
                app.get(
                    "$CINEMETA_BASE_URL/catalog/$type/top/search=${encodePathSegment(title)}.json",
                    headers = mapOf("Accept" to "application/json"),
                    timeout = 12L,
                )
            }.getOrNull() ?: return@forEach
            if (response.code !in 200..299 || response.text.length > RESPONSE_MAX_SIZE) return@forEach
            val metas = runCatching { JSONObject(response.text).optJSONArray("metas") }.getOrNull()
                ?: return@forEach
            val normalizedCandidate = normalizedTitle(title)
            val matches = buildList {
                for (index in 0 until metas.length()) {
                    val meta = metas.optJSONObject(index) ?: continue
                    val id = meta.optString("id").trim().takeIf(IMDB_ID::matches) ?: continue
                    if (!meta.optString("type").equals(type, ignoreCase = true)) continue
                    if (normalizedTitle(meta.optString("name")) != normalizedCandidate) continue
                    add(id to meta.optString("releaseInfo").trim())
                }
            }
            matches.firstOrNull { (_, releaseInfo) -> year != null && releaseInfo.startsWith(year.toString()) }
                ?.first
                ?.let { return it }
            if (year == null || matches.size == 1) matches.firstOrNull()?.first?.let { return it }
        }
        return null
    }

    suspend fun readManifest(input: String): StreamCenterStremioAddon {
        val manifestUrl = normalizeManifestUrl(input)
        val response = app.get(
            manifestUrl,
            headers = mapOf("Accept" to "application/json"),
            timeout = 20L,
        )
        check(response.code in 200..299) { "Manifest non raggiungibile (${response.code})" }
        check(response.text.length <= MANIFEST_MAX_SIZE) { "Manifest troppo grande" }
        val responseUrl = response.url.ifBlank { manifestUrl }
        check(isHttpsUrl(responseUrl)) { "Il manifest ha reindirizzato a un URL non sicuro" }
        val configuredQuery = manifestUrl.substringAfter('?', "").takeIf(String::isNotBlank)
        val resolvedManifestUrl = if (
            configuredQuery != null &&
            '?' !in responseUrl &&
            hasSameOrigin(manifestUrl, responseUrl)
        ) {
            "$responseUrl?$configuredQuery"
        } else {
            responseUrl
        }
        return parseManifest(resolvedManifestUrl, response.text)
    }

    suspend fun load(
        addon: StreamCenterStremioAddon,
        context: StreamCenterStremioPlaybackContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        stopAfterFirstResult: Boolean = false,
    ): Boolean = supervisorScope {
        val streamRequests = resolveResourceRequests(addon, "stream", context)
        val subtitleRequests = resolveResourceRequests(addon, "subtitles", context)
        val streamSemaphore = Semaphore(MAX_CONCURRENT_STREAM_REQUESTS)
        val subtitleSemaphore = Semaphore(MAX_CONCURRENT_SUBTITLE_REQUESTS)
        val extractorSemaphore = Semaphore(MAX_CONCURRENT_EXTERNAL_EXTRACTORS)
        val emittedStreamKeys = ConcurrentHashMap.newKeySet<String>()
        val emittedSubtitleKeys = ConcurrentHashMap.newKeySet<String>()
        val uniqueSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            if (emittedSubtitleKeys.add(subtitleDedupKey(subtitle.url))) {
                subtitleCallback(subtitle)
            }
        }
        val uniqueStreamCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedStreamKeys.add(streamDedupKey(link))) {
                callback(link)
            }
        }
        val subtitleJob = async {
            withTimeoutOrNull(SUBTITLE_REQUEST_BUDGET_MILLIS) {
                getResourcesInWaves(
                    addon = addon,
                    requests = subtitleRequests,
                    semaphore = subtitleSemaphore,
                    stopAfterFirstResult = stopAfterFirstResult,
                ) { response ->
                    emitSubtitles(response, uniqueSubtitleCallback)
                }
            }
        }

        var emittedStream = false
        getResourcesInWaves(
            addon = addon,
            requests = streamRequests,
            semaphore = streamSemaphore,
            stopAfterFirstResult = stopAfterFirstResult,
        ) { response ->
            val responseEmitted = emitStreams(
                addon = addon,
                root = response,
                subtitleCallback = uniqueSubtitleCallback,
                callback = uniqueStreamCallback,
                extractorSemaphore = extractorSemaphore,
            )
            emittedStream = responseEmitted || emittedStream
            responseEmitted
        }
        subtitleJob.await()
        if (!emittedStream && streamRequests.isNotEmpty()) {
            Log.d(
                LOG_TAG,
                "${safeLogValue(addon.name)}: nessuno stream " +
                    "(${streamRequests.size} richieste compatibili)",
            )
        }
        emittedStream
    }

    private fun parseManifest(url: String, text: String): StreamCenterStremioAddon {
        val manifestUrl = normalizeManifestUrl(url)
        val root = JSONObject(text)
        val id = root.optNonBlank("id")
        val name = root.optNonBlank("name")
        check(id != null) { "Il manifest non contiene un ID" }
        check(name != null) { "Il manifest non contiene un nome" }
        val types = root.stringList("types")
        val idPrefixes = root.stringList("idPrefixes")
        val resources = root.optJSONArray("resources")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    when (val entry = array.opt(index)) {
                        is String -> entry.trim()
                            .takeIf(String::isNotBlank)
                            ?.let { add(StreamCenterStremioResource(it)) }
                        is JSONObject -> entry.optNonBlank("name")?.let { resourceName ->
                            add(
                                StreamCenterStremioResource(
                                    name = resourceName,
                                    types = entry.stringList("types"),
                                    idPrefixes = entry.stringList("idPrefixes"),
                                ),
                            )
                        }
                    }
                }
            }
        }.orEmpty()
        check(
            resources.any { resource ->
                resource.name.equals("stream", ignoreCase = true) ||
                    resource.name.equals("subtitles", ignoreCase = true)
            },
        ) {
            "L'add-on non offre stream o sottotitoli"
        }
        return StreamCenterStremioAddon(
            key = addonKey(id, manifestUrl),
            manifestUrl = manifestUrl,
            id = id,
            name = name,
            version = root.optNonBlank("version"),
            logoUrl = root.optNonBlank("logo")?.let { resolveManifestAssetUrl(manifestUrl, it) },
            types = types,
            idPrefixes = idPrefixes,
            resources = resources,
        )
    }

    private suspend fun getResource(
        addon: StreamCenterStremioAddon,
        request: ResourceRequest,
    ): JSONObject? {
        val resourceUrl = buildString {
            append(addonBaseUrl(addon.manifestUrl))
            append('/')
            append(encodePathSegment(request.name))
            append('/')
            append(encodePathSegment(request.type))
            append('/')
            append(encodePathSegment(request.id))
            append(".json")
        }
        val url = configuredResourceUrl(resourceUrl, addon.manifestUrl)
        val response = app.get(
            url,
            headers = mapOf("Accept" to "application/json"),
            timeout = RESOURCE_HTTP_TIMEOUT_SECONDS,
        )
        val responseUrl = response.url.ifBlank { url }
        when {
            response.code !in 200..299 -> {
                val detail = "HTTP ${response.code}"
                if (response.code == 404) {
                    Log.d(
                        LOG_TAG,
                        "${safeLogValue(addon.name)}: ${request.name}/${request.type} $detail",
                    )
                } else {
                    logResourceFailure(addon, request, detail)
                }
                return null
            }
            response.text.length > RESPONSE_MAX_SIZE -> {
                logResourceFailure(addon, request, "risposta troppo grande")
                return null
            }
            !isHttpsUrl(responseUrl) -> {
                logResourceFailure(addon, request, "redirect non sicuro")
                return null
            }
        }
        return runCatching { JSONObject(response.text) }
            .getOrElse {
                logResourceFailure(addon, request, "JSON non valido")
                null
            }
    }

    private suspend fun getResourceSafely(
        addon: StreamCenterStremioAddon,
        request: ResourceRequest,
    ): JSONObject? {
        return try {
            getResource(addon, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logResourceFailure(
                addon,
                request,
                error.javaClass.simpleName.takeIf(String::isNotBlank) ?: "errore di rete",
            )
            null
        }
    }

    private suspend fun getResourcesInWaves(
        addon: StreamCenterStremioAddon,
        requests: List<ResourceRequest>,
        semaphore: Semaphore,
        stopAfterFirstResult: Boolean = false,
        handleResponse: suspend (JSONObject) -> Boolean = { false },
    ) {
        if (requests.isEmpty()) return
        val waveSize = if (stopAfterFirstResult) PERFORMANCE_RESOURCE_WAVE_SIZE else RESOURCE_WAVE_SIZE
        for (wave in requests.chunked(waveSize)) {
            val waveResults = getResourceWave(addon, wave, semaphore)
            var handled = false
            for (response in waveResults) {
                handled = handleResponse(response) || handled
                if (stopAfterFirstResult && handled) break
            }
            if (stopAfterFirstResult && handled) break
        }
    }

    private suspend fun getResourceWave(
        addon: StreamCenterStremioAddon,
        requests: List<ResourceRequest>,
        semaphore: Semaphore,
    ): List<JSONObject> = supervisorScope {
        requests.map { request ->
            async {
                semaphore.withPermit {
                    withTimeoutOrNull(RESOURCE_REQUEST_TIMEOUT_MILLIS) {
                        getResourceSafely(addon, request)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun logResourceFailure(
        addon: StreamCenterStremioAddon,
        request: ResourceRequest,
        detail: String,
    ) {
        Log.w(
            LOG_TAG,
            "${safeLogValue(addon.name)}: " +
                "${safeLogValue(request.name)}/${safeLogValue(request.type)} fallita " +
                "(${safeLogValue(detail)})",
        )
    }

    private fun resolveResourceRequests(
        addon: StreamCenterStremioAddon,
        resourceName: String,
        context: StreamCenterStremioPlaybackContext,
    ): List<ResourceRequest> {
        val requestedTypes = context.contentTypes
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (requestedTypes.isEmpty()) return emptyList()
        val requests = mutableListOf<ResourceRequest>()
        addon.resources
            .filter { it.name.equals(resourceName, ignoreCase = true) }
            .forEach { resource ->
                val requestsByType = requestedTypes.mapNotNull { type ->
                    if (!resource.supportsType(addon, type)) {
                        null
                    } else {
                        idCandidates(context, type)
                            .filter { candidate -> resource.supportsId(addon, candidate) }
                            .take(MAX_REQUESTS_PER_TYPE)
                            .map { candidate ->
                                ResourceRequest(
                                    name = resourceName,
                                    type = type.lowercase(Locale.ROOT),
                                    id = candidate,
                                )
                            }
                    }
                }
                requests += interleave(requestsByType).take(MAX_REQUESTS_PER_RESOURCE)
            }
        return requests
            .distinctBy { request ->
                Triple(
                    request.name.lowercase(Locale.ROOT),
                    request.type.lowercase(Locale.ROOT),
                    request.id,
                )
            }
            .take(MAX_TOTAL_RESOURCE_REQUESTS)
    }

    private fun idCandidates(
        context: StreamCenterStremioPlaybackContext,
        requestedType: String,
    ): List<String> = buildList {
        context.stremioVideoId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)

        context.stremioId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { customId ->
                addVideoIdVariants(
                    target = this,
                    baseId = customId,
                    style = VideoIdStyle.CUSTOM,
                    requestedType = requestedType,
                    context = context,
                )
            }

        val imdbIds = normalizedImdbId(context.imdbId)
            ?.let { imdbId -> listOf(imdbId, "imdb:$imdbId") }
            .orEmpty()
        val tmdbIds = normalizedTmdbId(context.tmdbId)?.let { tmdbId ->
            when (requestedType.lowercase(Locale.ROOT)) {
                "movie" -> listOf("tmdb:$tmdbId", "tmdb:movie:$tmdbId")
                "anime" -> listOf(
                    "tmdb:$tmdbId",
                    "tmdb:tv:$tmdbId",
                    "tmdb:anime:$tmdbId",
                )
                else -> listOf(
                    "tmdb:$tmdbId",
                    "tmdb:tv:$tmdbId",
                    "tmdb:series:$tmdbId",
                )
            }
        }.orEmpty()
        val kitsuIds = context.kitsuId
            ?.takeIf { it > 0 }
            ?.let { listOf("kitsu:$it") }
            .orEmpty()
        val anilistIds = context.anilistId
            ?.takeIf { it > 0 }
            ?.let { listOf("anilist:$it") }
            .orEmpty()
        val malIds = context.malId
            ?.takeIf { it > 0 }
            ?.let { listOf("mal:$it") }
            .orEmpty()
        val simklIds = context.simklId
            ?.takeIf { it > 0 }
            ?.let { listOf("simkl:$it") }
            .orEmpty()

        val ordered = if (requestedType.equals("anime", ignoreCase = true)) {
            listOf(
                kitsuIds to VideoIdStyle.EPISODE_ONLY,
                anilistIds to VideoIdStyle.EPISODE_ONLY,
                malIds to VideoIdStyle.EPISODE_ONLY,
                simklIds to VideoIdStyle.SEASON_EPISODE,
                imdbIds to VideoIdStyle.SEASON_EPISODE,
                tmdbIds to VideoIdStyle.SEASON_EPISODE,
            )
        } else {
            listOf(
                imdbIds to VideoIdStyle.SEASON_EPISODE,
                tmdbIds to VideoIdStyle.SEASON_EPISODE,
                kitsuIds to VideoIdStyle.EPISODE_ONLY,
                anilistIds to VideoIdStyle.EPISODE_ONLY,
                malIds to VideoIdStyle.EPISODE_ONLY,
                simklIds to VideoIdStyle.SEASON_EPISODE,
            )
        }
        ordered.forEach { (baseIds, style) ->
            baseIds.forEach { baseId ->
                addVideoIdVariants(
                    target = this,
                    baseId = baseId,
                    style = style,
                    requestedType = requestedType,
                    context = context,
                )
            }
        }
    }.distinct()

    private fun addVideoIdVariants(
        target: MutableList<String>,
        baseId: String,
        style: VideoIdStyle,
        requestedType: String,
        context: StreamCenterStremioPlaybackContext,
    ) {
        val episode = context.episode
        if (
            episode == null ||
            requestedType.equals("movie", ignoreCase = true) ||
            hasEpisodeSuffix(baseId)
        ) {
            target += baseId
            return
        }
        val season = context.season ?: 1
        when (style) {
            VideoIdStyle.EPISODE_ONLY -> {
                target += "$baseId:$episode"
                target += "$baseId:$season:$episode"
            }
            VideoIdStyle.SEASON_EPISODE -> target += "$baseId:$season:$episode"
            VideoIdStyle.CUSTOM -> {
                target += "$baseId:$season:$episode"
                if (requestedType.equals("anime", ignoreCase = true)) {
                    target += "$baseId:$episode"
                }
            }
        }
    }

    private fun hasEpisodeSuffix(id: String): Boolean =
        SEASON_EPISODE_SUFFIX.containsMatchIn(id) || ANIME_EPISODE_ID.matches(id)

    private fun normalizedImdbId(value: String?): String? {
        val candidate = value?.trim() ?: return null
        return candidate.takeIf(IMDB_ID::matches)?.lowercase(Locale.ROOT)
    }

    private fun normalizedTmdbId(value: String?): String? {
        val candidate = value?.trim() ?: return null
        return TMDB_ID.matchEntire(candidate)?.groupValues?.getOrNull(1)
    }

    private fun normalizedTitle(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun StreamCenterStremioResource.supportsType(
        addon: StreamCenterStremioAddon,
        requestedType: String,
    ): Boolean {
        val supportedTypes = types.ifEmpty { addon.types }
        return supportedTypes.isEmpty() ||
            supportedTypes.any { it.equals(requestedType, ignoreCase = true) }
    }

    private fun StreamCenterStremioResource.supportsId(
        addon: StreamCenterStremioAddon,
        requestedId: String,
    ): Boolean {
        val supportedPrefixes = idPrefixes.ifEmpty { addon.idPrefixes }
        return supportedPrefixes.isEmpty() ||
            supportedPrefixes.any { prefix -> requestedId.startsWith(prefix, ignoreCase = true) }
    }

    private fun JSONObject.optNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val raw = opt(key)
            if (raw == null || raw === JSONObject.NULL) continue
            raw.toString()
                .trim()
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        return when (val value = opt(key)) {
            is Number -> value.toInt().takeIf { it >= 0 }
            null, JSONObject.NULL -> null
            else -> value.toString().toIntOrNull()?.takeIf { it >= 0 }
        }
    }

    private suspend fun emitStreams(
        addon: StreamCenterStremioAddon,
        root: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        extractorSemaphore: Semaphore,
    ): Boolean = supervisorScope {
        val streams = root.optJSONArray("streams") ?: return@supervisorScope false
        var emitted = false
        val externalTasks = mutableListOf<suspend () -> Boolean>()
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val label = stream.optNonBlank("description", "title", "name")
                ?.replace(Regex("\\s+"), " ")
                ?.take(240)
                ?: "Stream ${index + 1}"
            val headers = streamHeaders(stream)
            emitSubtitles(stream, subtitleCallback)
            val url = stream.optNonBlank("url")
            val infoHash = stream.optNonBlank("infoHash")
            val ytId = stream.optNonBlank("ytId")
            val externalUrl = stream.optNonBlank("externalUrl")
            when {
                url != null && isHttpUrl(url) -> {
                    callback(
                        newExtractorLink(
                            source = addon.name,
                            name = "[${addon.name}] $label",
                            url = url,
                            type = streamType(url),
                        ) {
                            quality = qualityFrom(label)
                            this.headers = headers
                            headerValue(headers, "Referer")?.let { this.referer = it }
                        },
                    )
                    emitted = true
                }
                url != null && url.startsWith("magnet:?", ignoreCase = true) -> {
                    callback(
                        newExtractorLink(
                            source = addon.name,
                            name = "[${addon.name}] $label",
                            url = url,
                            type = ExtractorLinkType.MAGNET,
                        ) {
                            quality = qualityFrom(label)
                        },
                    )
                    emitted = true
                }
                infoHash != null && INFO_HASH.matches(infoHash) -> {
                    callback(
                        newExtractorLink(
                            source = addon.name,
                            name = "[${addon.name}] $label",
                            url = stremioMagnetUrl(stream, infoHash),
                            type = ExtractorLinkType.MAGNET,
                        ) {
                            quality = qualityFrom(label)
                        },
                    )
                    emitted = true
                }
                ytId != null -> {
                    val youtubeUrl =
                        "https://www.youtube.com/watch?v=${encodeQueryValue(ytId)}"
                    externalTasks += suspend {
                        loadExternalStream(
                            addon = addon,
                            targetUrl = youtubeUrl,
                            referer = "https://www.youtube.com/",
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                            kind = "YouTube",
                        )
                    }
                }
                externalUrl != null && isHttpUrl(externalUrl) -> {
                    externalTasks += suspend {
                        loadExternalStream(
                            addon = addon,
                            targetUrl = externalUrl,
                            referer = headerValue(headers, "Referer")
                                ?: addonBaseUrl(addon.manifestUrl),
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                            kind = "esterno",
                        )
                    }
                }
            }
        }
        val externalEmitted = externalTasks.map { task ->
            async {
                extractorSemaphore.withPermit { task() }
            }
        }.awaitAll().any { it }
        emitted || externalEmitted
    }

    private suspend fun loadExternalStream(
        addon: StreamCenterStremioAddon,
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        kind: String,
    ): Boolean {
        return withTimeoutOrNull(EXTRACTOR_TIMEOUT_MILLIS) {
            try {
                loadExtractor(
                    targetUrl,
                    referer,
                    subtitleCallback,
                    callback,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(
                    LOG_TAG,
                    "${safeLogValue(addon.name)}: resolver $kind fallito " +
                        "(${safeLogValue(error.javaClass.simpleName)})",
                )
                false
            }
        } ?: false
    }

    private fun stremioMagnetUrl(stream: JSONObject, hash: String): String {
        val parameters = mutableListOf("xt=urn:btih:$hash")
        stream.optNullableInt("fileIdx")?.let { fileIndex ->
            parameters += "so=$fileIndex"
        }
        val filename = stream.optJSONObject("behaviorHints")
            ?.optNonBlank("filename")
        filename?.let { parameters += "dn=${encodeQueryValue(it)}" }
        stream.optJSONArray("sources")?.let { sources ->
            buildList {
                for (index in 0 until sources.length()) {
                    val source = sources.optString(index).trim()
                    if (source.startsWith("tracker:", ignoreCase = true)) {
                        source.substringAfter(':')
                            .takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                }
            }.distinct().take(20).forEach { tracker ->
                parameters += "tr=${encodeQueryValue(tracker)}"
            }
        }
        return "magnet:?${parameters.joinToString("&")}"
    }

    private suspend fun emitSubtitles(
        root: JSONObject,
        callback: (SubtitleFile) -> Unit,
    ): Boolean {
        val subtitles = root.optJSONArray("subtitles") ?: return false
        var emitted = false
        for (index in 0 until subtitles.length()) {
            val subtitle = subtitles.optJSONObject(index) ?: continue
            val url = subtitle.optNonBlank("url") ?: continue
            if (!isHttpUrl(url)) continue
            val language = subtitle.optNonBlank("lang", "lang_code", "name")
                ?: "Sconosciuto"
            callback(newSubtitleFile(language, url))
            emitted = true
        }
        return emitted
    }

    private fun streamHeaders(stream: JSONObject): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        mergeHeaders(headers, stream.optJSONObject("headers"))
        val hints = stream.optJSONObject("behaviorHints")
        mergeHeaders(headers, hints?.optJSONObject("headers"))
        mergeHeaders(
            headers,
            hints?.optJSONObject("proxyHeaders")?.optJSONObject("request"),
        )
        return headers
    }

    private fun mergeHeaders(
        target: LinkedHashMap<String, String>,
        source: JSONObject?,
    ) {
        source ?: return
        source.keys().forEach { rawKey ->
            val rawValue = source.opt(rawKey)
            if (rawValue == null || rawValue === JSONObject.NULL) return@forEach
            val value = when (rawValue) {
                is String, is Number, is Boolean -> rawValue.toString().trim()
                else -> ""
            }
            if (
                value.isBlank() ||
                value.equals("null", ignoreCase = true) ||
                value.length > MAX_HEADER_VALUE_LENGTH ||
                value.any { character ->
                    character == '\r' ||
                        character == '\n' ||
                        character.code == 0x7f ||
                        (character.code < 0x20 && character != '\t')
                }
            ) return@forEach
            putHeader(target, rawKey, value)
        }
    }

    private fun putHeader(
        target: LinkedHashMap<String, String>,
        rawName: String,
        value: String,
    ) {
        val name = canonicalHeaderName(rawName) ?: return
        target.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(target::remove)
        target[name] = value
    }

    private fun canonicalHeaderName(rawName: String): String? {
        val trimmed = rawName.trim()
        if (!HTTP_HEADER_NAME.matches(trimmed)) return null
        return when (trimmed.lowercase(Locale.ROOT).replace("_", "").replace("-", "")) {
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            "useragent" -> "User-Agent"
            "authorization" -> "Authorization"
            "cookie" -> "Cookie"
            "range" -> "Range"
            else -> trimmed
        }
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun streamType(url: String): ExtractorLinkType? {
        val normalized = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            normalized.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            normalized.endsWith(".mpd") -> ExtractorLinkType.DASH
            normalized.endsWith(".mp4") ||
                normalized.endsWith(".mkv") ||
                normalized.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }
    }

    private fun qualityFrom(label: String): Int {
        val text = label.lowercase(Locale.ROOT)
        if ("8k" in text) return 4320
        if ("4k" in text) return 2160
        return RESOLUTION_REGEX.findAll(text)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it in 144..4320 }
            ?: Qualities.Unknown.value
    }

    private fun normalizeManifestUrl(input: String): String {
        val candidate = input.trim().let { value ->
            when {
                value.startsWith("stremio://", ignoreCase = true) ->
                    "https://" + value.substringAfter("://")
                "://" in value -> value
                else -> "https://$value"
            }
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: error("URL del manifest non valido")
        check(uri.scheme.equals("https", ignoreCase = true)) {
            "Sono ammessi solo manifest HTTPS"
        }
        check(!uri.host.isNullOrBlank()) { "URL del manifest non valido" }
        val configuredQuery = uri.rawQuery?.takeIf(String::isNotBlank)
        val withoutQuery = candidate
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
        val manifestPath = if (
            withoutQuery.endsWith("/manifest.json", ignoreCase = true)
        ) {
            withoutQuery
        } else {
            "$withoutQuery/manifest.json"
        }
        return manifestPath + configuredQuery?.let { "?$it" }.orEmpty()
    }

    private fun addonBaseUrl(manifestUrl: String): String {
        val withoutQuery = manifestUrl.substringBefore('?').trimEnd('/')
        return withoutQuery
            .replace(MANIFEST_SUFFIX_REGEX, "")
            .trimEnd('/')
    }

    private fun configuredResourceUrl(resourceUrl: String, manifestUrl: String): String {
        val configuredQuery = manifestUrl.substringAfter('?', "").takeIf(String::isNotBlank)
        return resourceUrl + configuredQuery?.let { "?$it" }.orEmpty()
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val scheme = URI(value).scheme
        scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)
    }.getOrDefault(false)

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        URI(value).scheme.equals("https", ignoreCase = true)
    }.getOrDefault(false)

    private fun resolveManifestAssetUrl(manifestUrl: String, value: String): String? = runCatching {
        URI(manifestUrl).resolve(value.trim()).normalize()
    }.getOrNull()?.takeIf { resolved ->
        resolved.scheme.equals("https", ignoreCase = true) && !resolved.host.isNullOrBlank()
    }?.toASCIIString()

    private fun hasSameOrigin(first: String, second: String): Boolean = runCatching {
        val firstUri = URI(first)
        val secondUri = URI(second)
        firstUri.scheme.equals(secondUri.scheme, ignoreCase = true) &&
            firstUri.host.equals(secondUri.host, ignoreCase = true) &&
            effectivePort(firstUri) == effectivePort(secondUri)
    }.getOrDefault(false)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private fun addonKey(id: String, manifestUrl: String): String {
        val stem = id.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "addon" }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(manifestUrl.toByteArray(StandardCharsets.UTF_8))
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "stremio_${stem}_$fingerprint"
    }

    private fun streamDedupKey(link: ExtractorLink): String = buildString {
        append(link.url.trim().substringBefore('#'))
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

    private fun subtitleDedupKey(url: String): String =
        url.trim().substringBefore('#')

    private fun safeLogValue(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(100)

    private fun JSONObject.stringList(key: String): List<String> =
        optJSONArray(key)?.stringList().orEmpty()

    private fun JSONArray.stringList(): List<String> = buildList {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value == null || value === JSONObject.NULL) continue
            value.toString()
                .trim()
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?.let(::add)
        }
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> = buildList {
        var index = 0
        while (true) {
            var added = false
            lists.forEach { values ->
                values.getOrNull(index)?.let { value ->
                    add(value)
                    added = true
                }
            }
            if (!added) break
            index += 1
        }
    }

    private data class ResourceRequest(
        val name: String,
        val type: String,
        val id: String,
    )

    private enum class VideoIdStyle {
        CUSTOM,
        SEASON_EPISODE,
        EPISODE_ONLY,
    }

    private val IMDB_ID = Regex("^tt\\d{5,}$", RegexOption.IGNORE_CASE)
    private val TMDB_ID = Regex(
        "^(?:tmdb:(?:(?:movie|tv|series|anime):)?)?(\\d+)$",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_EPISODE_SUFFIX = Regex(":\\d+:\\d+$")
    private val ANIME_EPISODE_ID = Regex(
        "^(?:kitsu|anilist|mal):[^:]+:\\d+$",
        RegexOption.IGNORE_CASE,
    )
    private val INFO_HASH = Regex(
        "^(?:[A-Fa-f0-9]{40}|[A-Za-z2-7]{32})$",
    )
    private val RESOLUTION_REGEX = Regex("(?<!\\d)(\\d{3,4})p?(?!\\d)")
    private val MANIFEST_SUFFIX_REGEX = Regex(
        "/manifest\\.json$",
        RegexOption.IGNORE_CASE,
    )
    private val HTTP_HEADER_NAME = Regex("^[A-Za-z0-9!#%&'*+.^_`|~-]{1,100}$")
    private const val MAX_HEADER_VALUE_LENGTH = 8_192
}
