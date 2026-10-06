package it.dogior.hadEnough.tracking

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import it.dogior.hadEnough.StreamCenterTrackingMediaCategory
import it.dogior.hadEnough.cache.ExpiringCache
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal object StreamCenterSimklTracking {
    private data class MediaCategory(
        val category: StreamCenterTrackingMediaCategory,
        val type: TvType,
    )

    private val categoriesCache = ExpiringCache<String, Map<String, MediaCategory>>(
        maxEntries = 4,
        ttlMillis = 30_000L,
    )
    private val categoriesMutex = Mutex()

    suspend fun libraryItems(
        account: AuthData,
        items: List<SyncAPI.LibraryItem>,
        category: StreamCenterTrackingMediaCategory,
    ): List<SyncAPI.LibraryItem> {
        if (items.isEmpty()) return emptyList()
        val cacheKey = "${account.user.id}|${account.token.accessToken}"
        val categories = categoriesMutex.withLock {
            categoriesCache[cacheKey] ?: run {
                val response = app.get(
                    "https://api.simkl.com/sync/all-items/",
                    headers = SimklApi.getHeaders(account.token),
                    cacheTime = 0,
                )
                check(response.isSuccessful) { "Impossibile leggere le categorie della lista SIMKL" }
                parseCategories(JSONObject(response.text)).also { categoriesCache.put(cacheKey, it) }
            }
        }
        return filterItems(items, categories, category)
    }

    private fun parseCategories(root: JSONObject): Map<String, MediaCategory> {
        check(listOf("shows", "movies", "anime").any { root.optJSONArray(it) != null }) {
            "Categorie della lista SIMKL non disponibili"
        }
        return buildMap {
            listOf(
                "shows" to StreamCenterTrackingMediaCategory.TV,
                "movies" to StreamCenterTrackingMediaCategory.MOVIES,
                "anime" to StreamCenterTrackingMediaCategory.ANIME,
            ).forEach { (arrayKey, category) ->
                val entries = root.optJSONArray(arrayKey) ?: return@forEach
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val media = entry.optJSONObject(if (arrayKey == "movies") "movie" else "show") ?: continue
                    val id = media.optJSONObject("ids")?.optNullableString("simkl") ?: continue
                    val type = when (category) {
                        StreamCenterTrackingMediaCategory.TV -> TvType.TvSeries
                        StreamCenterTrackingMediaCategory.MOVIES -> TvType.Movie
                        else -> when (media.optNullableString("anime_type")) {
                            "movie" -> TvType.AnimeMovie
                            "ova", "ona", "special", "music" -> TvType.OVA
                            else -> TvType.Anime
                        }
                    }
                    put(id, MediaCategory(category, type))
                }
            }
        }
    }

    private fun filterItems(
        items: List<SyncAPI.LibraryItem>,
        categories: Map<String, MediaCategory>,
        requestedCategory: StreamCenterTrackingMediaCategory,
    ): List<SyncAPI.LibraryItem> = items.mapNotNull { item ->
        val media = categories[item.syncId]
        if (requestedCategory != StreamCenterTrackingMediaCategory.ALL && media?.category != requestedCategory) {
            return@mapNotNull null
        }
        if (media == null) item else item.copy(
            type = media.type,
            url = "https://simkl.com/${media.category.key}/${item.syncId}",
        )
    }
}
