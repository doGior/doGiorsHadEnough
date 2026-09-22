package it.dogior.hadEnough.anime.metadata

import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

internal class TmdbLogoClient(private val document: suspend (String) -> Document) {
    suspend fun resolve(baseUrl: String, isAnime: Boolean): String? {
        val pages = mutableMapOf<String, Document?>()
        suspend fun page(url: String): Document? {
            if (pages.containsKey(url)) return pages[url]
            val result = try {
                document(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            pages[url] = result
            return result
        }

        val preferred = listOfNotNull("it", "en", "ja".takeIf { isAnime }, "xx")
        for (language in preferred) {
            page("$baseUrl?image_language=$language")?.let { doc ->
                TmdbArtwork.logo(doc, language)?.let { return it }
            }
        }
        val unfiltered = page(baseUrl)
        unfiltered?.let { TmdbArtwork.logo(it)?.let { logo -> return logo } }
        val languages = (listOfNotNull(unfiltered) + pages.values.filterNotNull())
            .flatMap(TmdbArtwork::languages)
            .distinct()
            .filterNot { it in preferred }
        for (language in languages) {
            page("$baseUrl?image_language=$language")?.let { doc ->
                TmdbArtwork.logo(doc, language)?.let { return it }
            }
        }
        return null
    }
}

internal object TmdbArtwork {
    private val imagePath = Regex("""/t/p/[^/]+/([^/?#]+)""")
    private val imageLanguage = Regex("""[?&]image_language=([a-zA-Z]{2,3})(?:[-_][a-zA-Z]{2})?(?=&|$)""")
    private val seasonPath = Regex("""/tv/(\d+)(?:-[^/]+)?/season/(\d+)(?:/|$)""")
    private val hosts = setOf("image.tmdb.org", "media.themoviedb.org", "www.themoviedb.org")

    fun imageUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val absolute = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/t/p/") -> "https://image.tmdb.org$raw"
            else -> raw
        }
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host?.lowercase(Locale.ROOT) !in hosts) return null
        val filename = imagePath.matchEntire(uri.path)?.groupValues?.get(1) ?: return null
        if (!filename.contains('.') || filename.contains('$')) return null
        return "https://image.tmdb.org/t/p/original/$filename"
    }

    fun image(element: Element): String? = sequenceOf(
        element.attr("href"), element.attr("data-src"), element.attr("src"),
        element.attr("srcset").substringBefore(',').trim().substringBefore(' '),
    ).firstNotNullOfOrNull(::imageUrl)

    fun logo(document: Document, language: String? = null): String? {
        return document.select(".images.logos li.card, .results li.card, .results div.image_content")
            .firstNotNullOfOrNull { item ->
                val card = item.closest("li.card") ?: item
                val actualLanguage = card.selectFirst("input[data-language]")?.attr("data-language")
                    ?.substringBefore('-')?.lowercase(Locale.ROOT)
                if (language != null && actualLanguage != null && actualLanguage != language) {
                    return@firstNotNullOfOrNull null
                }
                card.select("a.image[href], .image_content a[href], .image_content img, a.image img")
                    .firstNotNullOfOrNull(::image)
            }
    }

    fun languages(document: Document): List<String> = document.select("a[href*=image_language=]")
        .mapNotNull { imageLanguage.find(it.attr("href"))?.groupValues?.get(1)?.lowercase(Locale.ROOT) }
        .distinct()

    fun isSeason(document: Document, tmdbId: Int, season: Int): Boolean {
        val canonical = document.selectFirst("link[rel=canonical]")?.attr("href")
            ?: document.selectFirst("meta[property=og:url]")?.attr("content")
            ?: return false
        val path = runCatching { URI(canonical).path }.getOrNull() ?: return false
        val match = seasonPath.find(path) ?: return false
        return match.groupValues[1].toIntOrNull() == tmdbId && match.groupValues[2].toIntOrNull() == season
    }

    fun seasonPoster(document: Document, tmdbId: Int, season: Int): String? {
        if (!isSeason(document, tmdbId, season)) return null
        return document.select("meta[property=og:image]").firstNotNullOfOrNull { imageUrl(it.attr("content")) }
            ?: document.select("section.header img.poster").firstNotNullOfOrNull(::image)
    }

    fun seasonStill(document: Document, tmdbId: Int, season: Int): String? {
        if (!isSeason(document, tmdbId, season)) return null
        return document.select(".episode_list .card[data-url]")
            .filter { it.attr("data-url").substringBefore('?').startsWith("/tv/$tmdbId/season/$season/episode/") }
            .firstNotNullOfOrNull { card -> card.select("div.image img").firstNotNullOfOrNull(::image) }
    }
}
