package it.dogior.hadEnough.anime.source

import it.dogior.hadEnough.model.AnimeSyncIds
import it.dogior.hadEnough.model.AnimeUnityAnime
import java.util.Locale

internal val AnimeUnityAnime.isDub: Boolean
    get() = dub == 1 || title.orEmpty().contains("(ITA)") ||
        titleEng.orEmpty().contains("(ITA)") ||
        titleIt.orEmpty().contains("(ITA)")

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
    return titleIt ?: titleEng ?: title ?: slug
}

internal fun AnimeUnityAnime.titleKeys(): Set<String> {
    return listOfNotNull(title, titleEng, titleIt, slug.replace('-', ' '))
        .map { sourceTitleDedupKey(cleanAnimeUnityTitle(it)) }
        .filter(String::isNotBlank)
        .toSet()
}

internal fun cleanAnimeUnityTitle(title: String): String {
    return title.replace(" (ITA)", "").trim()
}
