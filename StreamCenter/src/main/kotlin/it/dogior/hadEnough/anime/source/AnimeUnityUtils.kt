package it.dogior.hadEnough.anime.source

import it.dogior.hadEnough.model.AnimeSyncIds
import it.dogior.hadEnough.model.AnimeUnityAnime
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Locale

internal const val ANIME_UNITY_DUB_LANGUAGE = "ita"

private val ANILIST_POSTER_ID = Regex("""/(?:bx|b)(\d+)-[^/]*$""")
private val ANILIST_BANNER_ID = Regex("""/(\d+)-[^/]*$""")

internal val AnimeUnityAnime.hasDub: Boolean
    get() = ANIME_UNITY_DUB_LANGUAGE in episodeCounts

internal val AnimeUnityAnime.hasSub: Boolean
    get() = episodeCounts.isEmpty() || episodeCounts.keys.any { it != ANIME_UNITY_DUB_LANGUAGE }

internal fun AnimeUnityAnime.audioVariants(): List<Boolean> =
    listOfNotNull(false.takeIf { hasSub }, true.takeIf { hasDub })

internal fun AnimeUnityAnime.episodeCount(dub: Boolean): Int? =
    episodeCounts.filterKeys { (it == ANIME_UNITY_DUB_LANGUAGE) == dub }.values.maxOrNull()

internal fun AnimeUnityAnime.matches(syncIds: AnimeSyncIds): Boolean {
    return (syncIds.anilistId != null && anilistId == syncIds.anilistId) ||
        (syncIds.malId != null && malId == syncIds.malId)
}

internal fun AnimeUnityAnime.contentKey(): String {
    return when {
        anilistId != null -> "anilist:$anilistId"
        malId != null -> "mal:$malId"
        else -> "title:${cleanAnimeUnityTitle(displayTitle()).lowercase(Locale.ROOT)}"
    }
}

internal fun AnimeUnityAnime.displayTitle(): String {
    return titleIt ?: title ?: titleJa ?: slug
}

internal fun AnimeUnityAnime.titleKeys(): Set<String> {
    return listOfNotNull(title, titleJa, titleIt, slug.replace('-', ' '))
        .map { sourceTitleDedupKey(cleanAnimeUnityTitle(it)) }
        .filter(String::isNotBlank)
        .toSet()
}

internal fun cleanAnimeUnityTitle(title: String): String {
    return title.replace(" (ITA)", "").trim()
}

internal fun JSONObject.toAnimeUnityAnime(): AnimeUnityAnime? {
    val id = optNullableInt("id") ?: return null
    val slug = optNullableString("slug") ?: return null
    val images = optJSONObject("images")
    return AnimeUnityAnime(
        id = id,
        slug = slug,
        title = optNullableString("title"),
        titleJa = optNullableString("title_ja"),
        titleIt = optNullableString("title_it"),
        episodeCounts = optJSONArray("variants").animeUnityEpisodeCounts(),
        anilistId = optNullableInt("anilist_id") ?: images.anilistIdFromImages(),
        malId = optNullableInt("mal_id"),
        plot = cleanText(optNullableString("plot")),
        imageUrl = images?.optNullableString("poster") ?: images?.optNullableString("cover"),
        score = optNullableString("score")?.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0.0 },
        type = optNullableString("type"),
        year = optNullableInt("year"),
    )
}

internal fun JSONArray?.toAnimeUnityAnimeList(): List<AnimeUnityAnime> {
    this ?: return emptyList()
    return (0 until length())
        .mapNotNull { index -> optJSONObject(index)?.toAnimeUnityAnime() }
        .distinctBy(AnimeUnityAnime::id)
}

internal fun animeUnityPageProps(html: String, url: String): JSONObject? {
    val page = Jsoup.parse(html, url).selectFirst("[data-page]")?.attr("data-page")
        ?.takeIf(String::isNotBlank)
        ?: return null
    return runCatching { JSONObject(page).optJSONObject("props") }.getOrNull()
}

private fun JSONArray?.animeUnityEpisodeCounts(): Map<String, Int> {
    this ?: return emptyMap()
    return buildMap {
        for (index in 0 until length()) {
            val variant = optJSONObject(index) ?: continue
            val language = variant.optNullableString("language")?.lowercase(Locale.ROOT) ?: continue
            put(language, variant.optNullableInt("episodes_count") ?: 0)
        }
    }
}

private fun JSONObject?.anilistIdFromImages(): Int? {
    this ?: return null
    return optNullableString("poster")?.let { ANILIST_POSTER_ID.find(it) }?.groupValues?.get(1)?.toIntOrNull()
        ?: optNullableString("banner")?.let { ANILIST_BANNER_ID.find(it) }?.groupValues?.get(1)?.toIntOrNull()
}
