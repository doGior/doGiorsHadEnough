package it.dogior.hadEnough

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ShowStatus
import it.dogior.hadEnough.StreamCenter.Companion.IMDB_ID_REGEX
import it.dogior.hadEnough.StreamCenter.Companion.NETWORK_ALT_REGEX
import it.dogior.hadEnough.StreamCenter.Companion.PLACEHOLDER_PROFILE_IMAGE
import it.dogior.hadEnough.StreamCenter.Companion.TMDB_IMDB_JSON_REGEX
import it.dogior.hadEnough.StreamCenter.Companion.TMDB_MEDIA_URL_REGEX
import it.dogior.hadEnough.StreamCenter.Companion.WATCH_PROVIDER_REGEX
import it.dogior.hadEnough.anime.metadata.TmdbLogoClient
import it.dogior.hadEnough.cache.CachedMediaEntry
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.tmdb.StreamCenterTmdbApiClient
import it.dogior.hadEnough.tmdb.TmdbApiMapping
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.normalizeTrailerUrl
import it.dogior.hadEnough.util.runCatchingCancellable
import it.dogior.hadEnough.util.youtubeTrailerUrl
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal fun StreamCenter.extractTmdbImdbId(document: Document): String? {
    val externalLink = document.selectFirst("a[href*='imdb.com/title/tt']")
        ?.attr("href")
        ?.let { href -> IMDB_ID_REGEX.find(href)?.value }
    if (externalLink != null) return externalLink
    return TMDB_IMDB_JSON_REGEX.find(document.html())?.groupValues?.getOrNull(1)
}

internal fun StreamCenter.extractTmdbLogo(document: Document): String? {
    return document.selectFirst(
        "section.header.poster img.logo, section.header [class*=logo] img, " +
            "section[class*=header] img[class*=logo]",
    )?.extractImageUrl()
}

internal suspend fun StreamCenter.resolveTmdbLogo(
    isMovie: Boolean,
    tmdbId: Int?,
    isAnime: Boolean = false,
): String? {
    if (performanceMode) return null
    val id = tmdbId?.takeIf { it > 0 } ?: return null
    val kind = if (isMovie) "movie" else "tv"
    if (StreamCenterTmdbApiClient.isActive()) {
        StreamCenterTmdbApiClient.get(
            "$kind/$id/images",
            mapOf("include_image_language" to (tmdbLogoLanguages(isAnime) + "null").joinToString(",")),
        )?.let { return TmdbApiMapping.logo(it, tmdbLogoLanguages(isAnime)) }
    }
    val baseUrl = "https://www.themoviedb.org/$kind/$id/images/logos"
    return TmdbLogoClient { getTmdbDocument(it) }.resolve(baseUrl, isAnime)
}

internal fun StreamCenter.normalizeTmdbUrl(url: String, page: Int? = null): String {
    val absoluteUrl = if (url.startsWith("http")) {
        url
    } else {
        mainUrl + if (url.startsWith("/")) url else "/$url"
    }

    val params = buildMap {
        put("language", tmdbLanguage)
        page?.let { put("page", it.toString()) }
    }

    val existingQuery = absoluteUrl.substringAfter("?", "")
    val existingKeys = existingQuery.split('&').map { it.substringBefore('=') }.toSet()
    val paramsToAppend = params
        .filterKeys { it !in existingKeys }
        .map { "${it.key}=${it.value}" }

    if (paramsToAppend.isEmpty()) return absoluteUrl

    val separator = if ("?" in absoluteUrl) "&" else "?"
    return absoluteUrl + separator + paramsToAppend.joinToString("&")
}

internal fun StreamCenter.stripStreamCenterParams(url: String): String {
    val baseUrl = url.substringBefore("?")
    val query = url.substringAfter("?", "")
    if (query.isBlank()) return url

    val params = query.split("&").filterNot { parameter -> parameter.startsWith("streamcenter_") }
    return if (params.isEmpty()) baseUrl else "$baseUrl?${params.joinToString("&")}"
}

internal fun StreamCenter.streamCenterUrlParameter(url: String, name: String): String? {
    val encodedValue = url.substringAfter("?", "")
        .split("&")
        .firstOrNull { parameter -> parameter.substringBefore("=") == "streamcenter_$name" }
        ?.substringAfter("=", "")
        ?.takeIf(String::isNotBlank)
        ?: return null
    return runCatching { URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name()) }
        .getOrNull()
        ?.let(::cleanText)
}

internal fun Int.toDecimalScore(): String {
    return String.format(Locale.US, "%.1f", this / 10.0)
}

internal fun StreamCenter.extractTmdbScore(element: Element): String? {
    extractTmdbScorePercent(element)?.let { return it }
    return extractTmdbJsonLdScore(element)
}

internal fun StreamCenter.extractTmdbScorePercent(element: Element): String? {
    return element.selectFirst(".user_score_chart[data-percent]")
        ?.attr("data-percent")
        ?.toIntOrNull()
        ?.toDecimalScore()
}

internal fun StreamCenter.extractTmdbJsonLdScore(element: Element): String? {
    return element.select("script[type=application/ld+json]").asSequence()
        .mapNotNull { script ->
            runCatching {
                JSONObject(script.data().trim())
                    .optJSONObject("aggregateRating")
                    ?.opt("ratingValue")
                    ?.toString()
                    ?.toDecimalScore()
            }.getOrNull()
        }
        .firstOrNull()
}

internal fun String.toDecimalScore(): String? {
    val value = replace(',', '.').toDoubleOrNull() ?: return null
    val normalized = if (value > 10.0) value / 10.0 else value
    return String.format(Locale.US, "%.1f", normalized)
}

internal fun Element.extractImageUrl(): String? {
    val srcset = attr("srcset")
        .split(",")
        .lastOrNull()
        ?.trim()
        ?.substringBefore(" ")
        ?.takeIf { it.startsWith("http") }

    return srcset ?: attr("src").takeIf { it.startsWith("http") }
}

internal fun StreamCenter.getLocalizedTitle(doc: Document): String {
    return extractTmdbPageTitle(doc)
}

internal fun StreamCenter.parseYear(doc: Document): Int? {
    val titleYear = doc.selectFirst("section.header.poster h2 span.release_date")
        ?.text()
        ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
    if (titleYear != null) return titleYear

    return doc.selectFirst("span.release")
        ?.text()
        ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
}

internal fun StreamCenter.extractAnyFact(doc: Document, vararg labels: String): String? {
    return labels.asSequence().mapNotNull { extractFact(doc, it) }.firstOrNull()
}

internal fun StreamCenter.extractFact(doc: Document, label: String): String? {
    val expectedLabel = normalizeFactLabel(label)
    for (fact in doc.select("section.facts.left_column p")) {
        val strong = fact.selectFirst("strong") ?: continue
        val key = strong.text().trim()
        if (normalizeFactLabel(key) != expectedLabel) continue

        val ownText = fact.ownText().trim()
        val allText = fact.text().trim()
        val fallbackText = if (allText.startsWith(key)) allText.substring(key.length).trim() else allText
        return cleanText(ownText.ifBlank { fallbackText })
    }
    return null
}

internal fun StreamCenter.normalizeFactLabel(value: String): String {
    return value.trim().trimEnd(':').lowercase(Locale.ROOT)
}

internal fun StreamCenter.extractTmdbNetworks(doc: Document): String? {
    return doc.select("ul.networks li a img[alt]")
        .mapNotNull { image ->
            val alt = cleanText(image.attr("alt")) ?: return@mapNotNull null
            NETWORK_ALT_REGEX.find(alt)?.groupValues?.getOrNull(1)?.let(::cleanText)
        }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .joinToString(", ")
        .takeIf(String::isNotBlank)
}

internal suspend fun StreamCenter.extractTmdbWatchProviders(showUrl: String): String? =
    (apiWatchProviders(showUrl) ?: scrapedWatchProviders(showUrl))?.let { "Piattaforme (dati JustWatch): $it" }

private suspend fun StreamCenter.apiWatchProviders(showUrl: String): String? {
    if (!StreamCenterTmdbApiClient.isActive()) return null
    val path = Regex("/(movie|tv)/[0-9]+").find(showUrl)?.value?.trimStart('/') ?: return null
    return TmdbApiMapping.watchProviders(StreamCenterTmdbApiClient.get("$path/watch/providers"))
}

private suspend fun StreamCenter.scrapedWatchProviders(showUrl: String): String? {
    val doc = runCatching { getTmdbDocument("$showUrl/watch") }.getOrNull() ?: return null
    return doc.select("ul.providers li a[title]")
        .mapNotNull { anchor ->
            val title = cleanText(anchor.attr("title")) ?: return@mapNotNull null
            WATCH_PROVIDER_REGEX.find(title)?.groupValues?.getOrNull(1)?.let(::cleanText)
        }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .joinToString(", ")
        .takeIf(String::isNotBlank)
}

internal fun StreamCenter.extractContentRating(doc: Document): String? {
    return cleanText(doc.selectFirst("span.certification")?.text())
}

internal fun StreamCenter.extractTrailerUrl(doc: Document): String? {
    return doc.select(".play_trailer").firstNotNullOfOrNull { trailer ->
        val site = trailer.attr("data-site")
        val value = trailer.attr("data-id").ifBlank { trailer.attr("data-key") }
        when {
            site.equals("YouTube", ignoreCase = true) || site.isBlank() -> youtubeTrailerUrl(value)
            site.equals("Dailymotion", ignoreCase = true) -> normalizeTrailerUrl("https://www.dailymotion.com/video/$value")
            else -> null
        } ?: normalizeTrailerUrl(trailer.attr("href"))?.takeIf {
            it.startsWith("https://www.youtube.com/watch?v=") || it.startsWith("https://www.dailymotion.com/video/")
        }
    }
}

internal suspend fun StreamCenter.findTmdbTrailer(url: String, document: Document? = null): String? {
    document?.let(this@findTmdbTrailer::extractTrailerUrl)?.let { return it }
    val path = Regex("/(movie|tv)/[0-9]+").find(url)?.value ?: return null
    if (StreamCenterTmdbApiClient.isActive()) {
        StreamCenterTmdbApiClient.get("${path.trimStart('/')}/videos", mapOf("include_video_language" to "it,en,null"))
            ?.let { return TmdbApiMapping.trailer(it) }
    }
    return runCatchingCancellable {
        withTimeoutOrNull(3_000L) {
            if (document == null) {
                extractTrailerUrl(getTmdbDocument("https://www.themoviedb.org$path"))?.let { return@withTimeoutOrNull it }
            }
            extractTrailerUrl(getTmdbDocument("https://www.themoviedb.org$path?language=en-US"))
        }
    }.getOrNull()
}

internal suspend fun StreamCenter.cachedTrailer(entry: CachedMediaEntry): String? {
    val generation = StreamCenterMediaCache.generation
    val normalized = normalizeTrailerUrl(entry.trailerUrl)
    if (normalized != null) {
        if (normalized != entry.trailerUrl) {
            StreamCenterMediaCache.updateTrailer(entry.key, normalized, System.currentTimeMillis(), generation)
        }
        return normalized
    }
    val now = System.currentTimeMillis()
    if (performanceMode || !LoadResponse.isTrailersEnabled || currentCoroutineContext()[ExtensionCall.Key] != null ||
        now - entry.trailerCheckedAtMillis in 0 until 30 * 60_000L) return null
    val recovered = runCatchingCancellable {
        withTimeoutOrNull(4_000L) {
            if (entry.type == StreamCenterMediaCache.TYPE_ANIME) {
                val ids = entry.trackingIds
                if (ids?.anilist != null || ids?.mal != null) {
                    normalizeTrailerUrl(aniListMetadataClient.fetchMetadata(ids.anilist, ids.mal)?.trailerUrl)
                        ?.let { return@withTimeoutOrNull it }
                }
                val tmdbId = ids?.tmdb ?: return@withTimeoutOrNull null
                findTmdbTrailer("https://www.themoviedb.org/${if (entry.animeMovie) "movie" else "tv"}/$tmdbId")
            } else findTmdbTrailer(entry.url)
        }
    }.getOrNull()
    StreamCenterMediaCache.updateTrailer(entry.key, recovered, now, generation)
    return recovered
}

internal fun StreamCenter.mapShowStatus(status: String?): ShowStatus? {
    val normalized = status?.lowercase(Locale.ROOT) ?: return null
    return when {
        normalized.contains("terminat") ||
            normalized.contains("conclus") ||
            normalized.contains("cancellat") ||
            normalized.contains("ended") ||
            normalized.contains("canceled") -> ShowStatus.Completed
        normalized.contains("in corso") ||
            normalized.contains("in onda") ||
            normalized.contains("produzione") ||
            normalized.contains("ripropost") ||
            normalized.contains("returning") -> ShowStatus.Ongoing
        else -> null
    }
}

internal fun StreamCenter.anilistShowStatus(status: String?): ShowStatus? = when (status?.uppercase(Locale.ROOT)) {
    "FINISHED", "CANCELLED" -> ShowStatus.Completed
    "RELEASING" -> ShowStatus.Ongoing
    else -> null
}

internal fun StreamCenter.isComingSoon(status: String?): Boolean {
    val normalized = status?.lowercase(Locale.ROOT) ?: return false
    return normalized.contains("prossimamente") ||
        normalized.contains("pianificat") ||
        normalized.contains("annunciat") ||
        normalized.contains("post produzione") ||
        normalized.contains("post-produzione") ||
        normalized.contains("in produzione") ||
        normalized.contains("rumored")
}

internal fun StreamCenter.parseAnimeSelection(url: String): StreamCenterAnimeSelection? {
    val params = parseQueryParams(url)
    val selection = StreamCenterAnimeSelection(
        anilistId = params[animeAnilistParam]?.toIntOrNull(),
        malId = params[animeMalParam]?.toIntOrNull(),
    )

    return selection.takeIf {
        it.anilistId != null || it.malId != null
    }
}

internal fun StreamCenter.parseQueryParams(url: String): Map<String, String> {
    val query = url.substringAfter("?", "").takeIf(String::isNotBlank) ?: return emptyMap()
    return query.split("&").mapNotNull { parameter ->
        val key = parameter.substringBefore("=").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val value = parameter.substringAfter("=", "")
        key to value
    }.toMap()
}

internal fun StreamCenter.parseActors(doc: Document): List<ActorData> {
    return doc.select("#cast_scroller li.card").mapNotNull { card ->
        val name = card.selectFirst("p a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val character = card.selectFirst("p.character")?.text()?.trim()
        val image = card.selectFirst("img.profile")?.extractImageUrl()
        ActorData(Actor(name, image), roleString = character)
    }
}

internal fun StreamCenter.parseCrew(doc: Document): List<ActorData> {
    return doc.select("ol.people.no_image li.profile").mapNotNull { person ->
        val name = cleanText(person.selectFirst("p a")?.text()) ?: return@mapNotNull null
        val role = cleanText(person.selectFirst("p.character")?.text())
        ActorData(Actor(name, PLACEHOLDER_PROFILE_IMAGE), roleString = role)
    }
}

internal fun StreamCenter.extractTmdbId(url: String): String? {
    return TMDB_MEDIA_URL_REGEX.find(url)
        ?.groupValues
        ?.getOrNull(2)
        ?.toIntOrNull()
        ?.toString()
}
