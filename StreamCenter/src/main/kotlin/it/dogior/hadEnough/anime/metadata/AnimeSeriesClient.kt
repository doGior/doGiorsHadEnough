package it.dogior.hadEnough.anime.metadata

import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import it.dogior.hadEnough.util.runCatchingCancellable
import it.dogior.hadEnough.cache.StreamCenterMediaCache
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal data class AnimeSeriesEntry(
    val id: Int,
    val malId: Int?,
    val title: String,
    val format: String?,
    val status: String?,
    val episodeCount: Int?,
    val nextEpisode: Int?,
    val date: Int?,
    val poster: String?,
    val relations: List<Int>,
) {
    val isSeries: Boolean get() = format in setOf("TV", "TV_SHORT", "ONA", "OVA", "SPECIAL")
    val availableEpisodes: Int get() = when (status) {
        "FINISHED" -> episodeCount ?: 0
        "RELEASING" -> nextEpisode?.minus(1)?.coerceAtMost(episodeCount ?: Int.MAX_VALUE) ?: 0
        else -> 0
    }.coerceIn(0, 3000)
}

internal class AnimeSeriesClient(
    private val execute: suspend (String, JSONObject) -> JSONObject?,
    private val cacheEnabled: () -> Boolean = { StreamCenterMediaCache.isEnabled() },
) {
    suspend fun resolve(anilistId: Int?, malId: Int?): List<AnimeSeriesEntry> {
        if (cacheEnabled()) StreamCenterMediaCache.readAnimeRelations(anilistId, malId)?.let { return it }
        if (anilistId == null && malId == null) return emptyList()
        val entries = linkedMapOf<Int, AnimeSeriesEntry>()
        fun addNode(media: JSONObject?) {
            val entry = parse(media) ?: return
            entries[entry.id] = entry
            val edges = media?.optJSONObject("relations")?.optJSONArray("edges") ?: return
            for (index in 0 until edges.length()) {
                val edge = edges.optJSONObject(index) ?: continue
                if (edge.optString("relationType") !in setOf("PREQUEL", "SEQUEL")) continue
                val node = edge.optJSONObject("node") ?: continue
                if (node.has("title")) addNode(node)
            }
        }
        var complete = false
        withTimeoutOrNull(12_000) {
            val variables = JSONObject().apply {
                if (anilistId != null) put("id", anilistId) else put("idMal", malId)
            }
            val root = request(ROOT_QUERY, variables)?.optJSONObject("Media") ?: return@withTimeoutOrNull
            addNode(root)
            if (entries.isEmpty()) return@withTimeoutOrNull
            val visited = mutableSetOf<Int>()
            while (entries.size <= MAX_ENTRIES) {
                val pending = entries.values.flatMap { it.relations }
                    .filter { it !in entries && it !in visited }.distinct().take(20)
                if (pending.isEmpty()) { complete = true; break }
                visited += pending
                val media = request(BATCH_QUERY, JSONObject().put("ids", org.json.JSONArray(pending)))
                    ?.optJSONObject("Page")?.optJSONArray("media") ?: break
                for (index in 0 until media.length()) {
                    addNode(media.optJSONObject(index))
                }
                if (pending.any { it !in entries }) break
            }
        }
        val sorted = entries.values.sortedWith(compareBy<AnimeSeriesEntry> { it.date ?: Int.MAX_VALUE }.thenBy { it.id })
        if (complete && cacheEnabled()) StreamCenterMediaCache.rememberAnimeRelations(sorted)
        return if (complete) sorted else emptyList()
    }

    private suspend fun request(query: String, variables: JSONObject): JSONObject? =
        runCatchingCancellable { execute(query, variables) }.getOrNull()

    companion object {
        private const val MAX_ENTRIES = 60
        private val BASE_FIELDS = """
            id idMal type format status episodes
            title { romaji english native }
            startDate { year month day }
            coverImage { large }
            nextAiringEpisode { episode }
        """.trimIndent()
        private val NEIGHBOUR_FIELDS = "$BASE_FIELDS relations { edges { relationType node { id type } } }"
        private val FIELDS = "$BASE_FIELDS relations { edges { relationType node { $NEIGHBOUR_FIELDS } } }"
        private val ROOT_QUERY = "query(${'$'}id:Int,${'$'}idMal:Int){Media(id:${'$'}id,idMal:${'$'}idMal,type:ANIME){$FIELDS}}"
        private val BATCH_QUERY = "query(${'$'}ids:[Int]){Page(perPage:20){media(id_in:${'$'}ids,type:ANIME){$FIELDS}}}"

        internal fun parse(media: JSONObject?): AnimeSeriesEntry? {
            media ?: return null
            if (media.optString("type") != "ANIME") return null
            val id = media.optNullableInt("id")?.takeIf { it > 0 } ?: return null
            val titles = media.optJSONObject("title")
            val title = listOf("romaji", "english", "native").firstNotNullOfOrNull {
                titles?.optNullableString(it)
            } ?: return null
            val edges = media.optJSONObject("relations")?.optJSONArray("edges")
            val relations = buildList {
                for (index in 0 until (edges?.length() ?: 0)) {
                    val edge = edges?.optJSONObject(index) ?: continue
                    if (edge.optString("relationType") !in setOf("PREQUEL", "SEQUEL")) continue
                    val node = edge.optJSONObject("node") ?: continue
                    if (node.optString("type") != "ANIME") continue
                    node.optNullableInt("id")?.takeIf { it > 0 }?.let(::add)
                }
            }.distinct()
            val start = media.optJSONObject("startDate")
            val date = start?.optNullableInt("year")?.let {
                it * 10_000 + (start.optNullableInt("month") ?: 1) * 100 + (start.optNullableInt("day") ?: 1)
            }
            return AnimeSeriesEntry(id, media.optNullableInt("idMal"), title,
                media.optNullableString("format"), media.optNullableString("status"),
                media.optNullableInt("episodes"), media.optJSONObject("nextAiringEpisode")?.optNullableInt("episode"),
                date, media.optJSONObject("coverImage")?.optNullableString("large"), relations)
        }
    }
}
