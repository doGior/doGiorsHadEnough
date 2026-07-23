package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.model.MalEpisodeExtra
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class JikanMetadataClient(
    private val httpClient: AnimeMetadataHttpClient,
) {
    suspend fun fetchEpisodeExtras(
        malId: Int,
        targetEpisodeCount: Int? = null,
    ): Map<Int, MalEpisodeExtra> {
        val result = linkedMapOf<Int, MalEpisodeExtra>()
        var page = 1
        val maxPages = if (targetEpisodeCount == null) DEFAULT_MAX_PAGES else EXTENDED_MAX_PAGES
        while (page <= maxPages) {
            val text = httpClient.getText(
                url = "$API_URL/anime/$malId/episodes?page=$page",
                accept = "application/json",
                beforeRequest = ::throttle,
            ) ?: break
            val json = runCatching { JSONObject(text) }.getOrNull() ?: break
            val data = json.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (index in 0 until data.length()) {
                val entry = data.optJSONObject(index) ?: continue
                val number = entry.optNullableInt("mal_id") ?: continue
                if (result.containsKey(number)) continue
                result[number] = MalEpisodeExtra(
                    title = entry.optNullableString("title"),
                    score = entry.optDouble("score", 0.0).takeIf { it > 0.0 },
                    airedDate = entry.optNullableString("aired")?.substringBefore("T"),
                    filler = entry.optBoolean("filler", false),
                    recap = entry.optBoolean("recap", false),
                )
            }
            if (targetEpisodeCount != null && (result.keys.maxOrNull() ?: 0) >= targetEpisodeCount) break
            if (json.optJSONObject("pagination")?.optBoolean("has_next_page", false) != true) break
            page++
        }
        return result
    }

    private suspend fun throttle() {
        requestMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequestAtMs)
            if (wait > 0) delay(wait)
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    private companion object {
        const val API_URL = "https://api.jikan.moe/v4"
        const val DEFAULT_MAX_PAGES = 5
        const val EXTENDED_MAX_PAGES = 30
        const val MIN_INTERVAL_MS = 400L
        val requestMutex = Mutex()
        var lastRequestAtMs = 0L
    }
}
