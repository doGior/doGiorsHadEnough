package it.dogior.hadEnough.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val youtubeId = Regex("[A-Za-z0-9_-]{11}")

internal fun youtubeTrailerUrl(value: String?): String? {
    val candidate = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (youtubeId.matches(candidate)) return "https://www.youtube.com/watch?v=$candidate"
    return normalizeTrailerUrl(candidate)?.takeIf { it.startsWith("https://www.youtube.com/watch?v=") }
}

internal fun normalizeTrailerUrl(value: String?): String? {
    val candidate = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val url = (if (candidate.startsWith("//")) "https:$candidate" else candidate).toHttpUrlOrNull()
        ?: return null
    val segments = url.pathSegments
    val id = when (url.host) {
        "youtu.be", "www.youtu.be" -> segments.firstOrNull()
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com" -> when (segments.firstOrNull()) {
            "watch" -> url.queryParameter("v")
            "embed", "shorts", "v", "live" -> segments.getOrNull(1)
            else -> null
        }
        "dailymotion.com", "www.dailymotion.com", "dai.ly" -> {
            val videoId = when {
                url.host == "dai.ly" -> segments.firstOrNull()
                segments.firstOrNull() == "video" -> segments.getOrNull(1)
                segments.take(2) == listOf("embed", "video") -> segments.getOrNull(2)
                else -> null
            }?.substringBefore('_')?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) && it != "null" }
            return videoId?.let { "https://www.dailymotion.com/video/$it" }
        }
        else -> return url.toString()
    }
    return id?.takeIf(youtubeId::matches)?.let { "https://www.youtube.com/watch?v=$it" }
}
