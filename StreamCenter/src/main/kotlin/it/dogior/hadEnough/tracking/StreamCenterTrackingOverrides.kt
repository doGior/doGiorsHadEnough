package it.dogior.hadEnough.tracking

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.util.StreamCenterLogger
import org.json.JSONArray
import org.json.JSONObject

internal enum class ManualTrackingService(
    val key: String,
    val label: String,
    val shortLabel: String,
    val fallbackIcon: String,
    val websiteUrl: String,
    val trackingService: TrackingIdService,
    val anime: Boolean,
) {
    MAL("mal", "MyAnimeList", "MAL", "🌸", "https://myanimelist.net", TrackingIdService.MAL, true),
    ANILIST("anilist", "AniList", "AniList", "📊", "https://anilist.co", TrackingIdService.ANILIST, true),
    KITSU("kitsu", "Kitsu", "Kitsu", "🦊", "https://kitsu.app", TrackingIdService.KITSU, true),
    SIMKL("simkl", "Simkl", "Simkl", "📺", "https://simkl.com", TrackingIdService.SIMKL, false),
    TMDB("tmdb", "TMDB", "TMDB", "🎥", "https://www.themoviedb.org", TrackingIdService.TMDB, false),
    IMDB("imdb", "IMDb", "IMDb", "🎬", "https://www.imdb.com", TrackingIdService.IMDB, false);

    val placeholder: String
        get() = if (this == IMDB) "tt0000000 o link" else "ID numerico o link"

    fun normalize(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (value == StreamCenterTrackingOverrides.DISABLED) return value
        if (this == IMDB) {
            return Regex("tt\\d{5,}", RegexOption.IGNORE_CASE).find(value)?.value?.lowercase()
        }
        if (value.all(Char::isDigit)) return value.trimStart('0').takeIf { it.isNotEmpty() }
        val fromPath = Regex(
            "/(?:anime|manga|tv|movie|movies|shows|series)/(\\d+)",
            RegexOption.IGNORE_CASE,
        ).find(value)?.groupValues?.getOrNull(1)
        return fromPath?.trimStart('0')?.takeIf { it.isNotEmpty() }
    }

    fun pageUrl(id: String, type: TvType?): String? {
        if (id.isBlank() || id == StreamCenterTrackingOverrides.DISABLED) return null
        val anime = type == null || type == TvType.Anime || type == TvType.AnimeMovie || type == TvType.OVA
        val movie = type == TvType.Movie || type == TvType.AnimeMovie
        return when (this) {
            MAL -> "https://myanimelist.net/anime/$id"
            ANILIST -> "https://anilist.co/anime/$id"
            KITSU -> "https://kitsu.app/anime/$id"
            SIMKL -> "https://simkl.com/${if (anime) "anime" else if (movie) "movies" else "tv"}/$id"
            TMDB -> "https://www.themoviedb.org/${if (movie) "movie" else "tv"}/$id"
            IMDB -> "https://www.imdb.com/title/$id/"
        }
    }

    companion object {
        fun fromKey(key: String): ManualTrackingService? = entries.firstOrNull { it.key == key }

        fun fromSyncIdName(name: SyncIdName): ManualTrackingService? = when (name) {
            SyncIdName.MyAnimeList -> MAL
            SyncIdName.Anilist -> ANILIST
            SyncIdName.Kitsu -> KITSU
            SyncIdName.Simkl -> SIMKL
            SyncIdName.Imdb -> IMDB
            else -> null
        }
    }
}

internal data class TrackingIdSeenEntry(
    val key: String,
    val apiName: String,
    val title: String,
    val posterUrl: String?,
    val type: TvType?,
    val year: Int?,
    val detected: Map<ManualTrackingService, String>,
    val groupedSeasons: Boolean,
    val seenAt: Long,
)

internal data class TrackingIdOverride(
    val key: String,
    val apiName: String,
    val title: String,
    val posterUrl: String?,
    val type: TvType?,
    val year: Int?,
    val values: Map<ManualTrackingService, String>,
    val updatedAt: Long,
)

internal object StreamCenterTrackingOverrides {
    const val PREF_OVERRIDES = "trackingIdOverrides"
    const val DISABLED = "0"
    private const val SEEN_PREFS_NAME = "StreamCenterTrackingIds"
    private const val PREF_SEEN = "seen"
    private const val MAX_SEEN = 250
    private val idTagRegex = Regex("^(MAL|AniList|Kitsu|Simkl|IMDb|TMDB) ")
    private val lock = Any()


    fun detectedIds(response: LoadResponse): Map<ManualTrackingService, String> = runCatching {
        collectIds(
            simkl = simklIds(response.syncData),
            mal = response.getMalId(),
            aniList = response.getAniListId(),
            kitsu = response.getKitsuId(),
            tmdb = response.getTMDbId(),
            imdb = response.getImdbId(),
        )
    }.getOrDefault(emptyMap())

    fun detectedIds(syncData: Map<String, String>?): Map<ManualTrackingService, String> = runCatching {
        val data = syncData.orEmpty()
        collectIds(
            simkl = simklIds(data),
            mal = data[LoadResponse.malIdPrefix],
            aniList = data[LoadResponse.aniListIdPrefix],
            kitsu = data[LoadResponse.kitsuIdPrefix],
            tmdb = null,
            imdb = null,
        )
    }.getOrDefault(emptyMap())

    private fun simklIds(syncData: Map<String, String>): Map<SimklSyncServices, String> =
        runCatching { LoadResponse.readIdFromString(syncData[LoadResponse.simklIdPrefix]) }.getOrNull().orEmpty()

    private fun collectIds(
        simkl: Map<SimklSyncServices, String>,
        mal: String?,
        aniList: String?,
        kitsu: String?,
        tmdb: String?,
        imdb: String?,
    ): Map<ManualTrackingService, String> {
        val result = linkedMapOf<ManualTrackingService, String>()
        fun add(service: ManualTrackingService, value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() && it != "null" && it != DISABLED }?.let { result[service] = it }
        }
        add(ManualTrackingService.MAL, mal ?: simkl[SimklSyncServices.Mal])
        add(ManualTrackingService.ANILIST, aniList ?: simkl[SimklSyncServices.AniList])
        add(ManualTrackingService.KITSU, kitsu)
        add(ManualTrackingService.SIMKL, simkl[SimklSyncServices.Simkl])
        add(ManualTrackingService.TMDB, tmdb ?: simkl[SimklSyncServices.Tmdb])
        add(ManualTrackingService.IMDB, imdb ?: simkl[SimklSyncServices.Imdb])
        return result
    }

    fun applyOverride(
        response: LoadResponse,
        override: TrackingIdOverride?,
        showAsTags: Boolean,
        visibleServices: Set<TrackingIdService>,
    ): Boolean {
        val values = override?.values?.takeIf { it.isNotEmpty() } ?: return false
        val hadIdTags = response.tags.orEmpty().any { idTagRegex.containsMatchIn(it) }
        values.forEach { (service, value) ->
            runCatching {
                if (value == DISABLED) response.removeTrackingId(service) else response.setTrackingId(service, value)
            }.onFailure { error ->
                StreamCenterLogger.logTabError(
                    tabName = response.name,
                    action = "ID di tracciamento manuale non applicato",
                    throwable = error,
                    metadata = mapOf("servizio" to service.label, "id" to value),
                )
            }
        }
        if (showAsTags && hadIdTags) {
            val detected = detectedIds(response)
            val ids = StreamCenterTrackingIds(
                anilist = detected[ManualTrackingService.ANILIST]?.toIntOrNull(),
                mal = detected[ManualTrackingService.MAL]?.toIntOrNull(),
                kitsu = detected[ManualTrackingService.KITSU]?.toIntOrNull(),
                simkl = detected[ManualTrackingService.SIMKL]?.toIntOrNull(),
                imdb = detected[ManualTrackingService.IMDB],
                tmdb = detected[ManualTrackingService.TMDB],
            )
            response.tags = response.tags.orEmpty().filterNot { idTagRegex.containsMatchIn(it) }
            response.addStreamCenterTrackingIdTags(ids, visibleServices)
        }
        StreamCenterLogger.logTab(
            tabName = response.name,
            action = "ID di tracciamento manuali applicati",
            metadata = values.entries.associate { (service, value) ->
                "id_${service.key}" to if (value == DISABLED) "Disattivato" else value
            },
        )
        return true
    }

    private fun LoadResponse.setTrackingId(service: ManualTrackingService, value: String) {
        when (service) {
            ManualTrackingService.MAL -> addMalId(value.toInt())
            ManualTrackingService.ANILIST -> addAniListId(value.toInt())
            ManualTrackingService.KITSU -> addKitsuId(value.toInt())
            ManualTrackingService.SIMKL -> addSimklId(value.toInt())
            ManualTrackingService.TMDB -> addTMDbId(value)
            ManualTrackingService.IMDB -> addImdbId(value)
        }
    }

    private fun LoadResponse.removeTrackingId(service: ManualTrackingService) {
        when (service) {
            ManualTrackingService.MAL -> syncData.remove(LoadResponse.malIdPrefix)
            ManualTrackingService.ANILIST -> syncData.remove(LoadResponse.aniListIdPrefix)
            ManualTrackingService.KITSU -> syncData.remove(LoadResponse.kitsuIdPrefix)
            else -> Unit
        }
        val simklService = when (service) {
            ManualTrackingService.MAL -> SimklSyncServices.Mal
            ManualTrackingService.ANILIST -> SimklSyncServices.AniList
            ManualTrackingService.SIMKL -> SimklSyncServices.Simkl
            ManualTrackingService.TMDB -> SimklSyncServices.Tmdb
            ManualTrackingService.IMDB -> SimklSyncServices.Imdb
            ManualTrackingService.KITSU -> null
        } ?: return
        val current = LoadResponse.readIdFromString(syncData[LoadResponse.simklIdPrefix])
        if (simklService !in current) return
        val updated = current - simklService
        if (updated.isEmpty()) syncData.remove(LoadResponse.simklIdPrefix)
        else syncData[LoadResponse.simklIdPrefix] = updated.toJson()
    }

    fun overrides(prefs: SharedPreferences?): List<TrackingIdOverride> {
        val raw = prefs?.getString(PREF_OVERRIDES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val key = json.optString("key").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val values = json.optJSONObject("ids")?.let { ids ->
                ids.keys().asSequence().mapNotNull { serviceKey ->
                    val service = ManualTrackingService.fromKey(serviceKey) ?: return@mapNotNull null
                    val value = ids.optString(serviceKey).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                    service to value
                }.toMap()
            }.orEmpty()
            if (values.isEmpty()) return@mapNotNull null
            TrackingIdOverride(
                key = key,
                apiName = json.optString("api"),
                title = json.optString("title").ifBlank { key },
                posterUrl = json.optString("poster").takeIf(String::isNotBlank),
                type = json.optString("type").toTvType(),
                year = json.optInt("year", 0).takeIf { it > 0 },
                values = values,
                updatedAt = json.optLong("updatedAt"),
            )
        }.distinctBy { it.key }
    }

    fun override(prefs: SharedPreferences?, key: String): TrackingIdOverride? =
        overrides(prefs).firstOrNull { it.key == key }

    fun saveOverride(prefs: SharedPreferences?, override: TrackingIdOverride) {
        prefs ?: return
        synchronized(lock) {
            val updated = overrides(prefs).filterNot { it.key == override.key } +
                listOfNotNull(override.takeIf { it.values.isNotEmpty() })
            writeOverrides(prefs, updated)
        }
        StreamCenterLogger.logMenu(
            action = "ID di tracciamento manuali salvati",
            metadata = mapOf("scheda" to override.title, "servizi" to override.values.keys.joinToString { it.label }),
        )
    }

    fun removeOverride(prefs: SharedPreferences?, key: String) {
        prefs ?: return
        synchronized(lock) { writeOverrides(prefs, overrides(prefs).filterNot { it.key == key }) }
    }

    fun urlForManualId(prefs: SharedPreferences?, apiName: String, name: SyncIdName, id: String): String? {
        val service = ManualTrackingService.fromSyncIdName(name) ?: return null
        val normalized = service.normalize(id)?.takeIf { it != DISABLED } ?: return null
        return overrides(prefs).firstOrNull { override ->
            override.apiName == apiName && override.values[service] == normalized
        }?.key
    }

    private fun writeOverrides(prefs: SharedPreferences, overrides: List<TrackingIdOverride>) {
        prefs.edit().apply {
            if (overrides.isEmpty()) {
                remove(PREF_OVERRIDES)
            } else {
                putString(
                    PREF_OVERRIDES,
                    JSONArray().apply {
                        overrides.forEach { override ->
                            put(
                                JSONObject().apply {
                                    put("key", override.key)
                                    put("api", override.apiName)
                                    put("title", override.title)
                                    override.posterUrl?.let { put("poster", it) }
                                    override.type?.let { put("type", it.name) }
                                    override.year?.let { put("year", it) }
                                    put("updatedAt", override.updatedAt)
                                    put(
                                        "ids",
                                        JSONObject().apply {
                                            override.values.forEach { (service, value) -> put(service.key, value) }
                                        },
                                    )
                                },
                            )
                        }
                    }.toString(),
                )
            }
        }.apply()
    }

    private fun seenPrefs(): SharedPreferences? =
        StreamCenterPlugin.activeContext?.getSharedPreferences(SEEN_PREFS_NAME, Context.MODE_PRIVATE)

    fun seen(): List<TrackingIdSeenEntry> {
        val raw = runCatching { seenPrefs()?.getString(PREF_SEEN, null) }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val key = json.optString("key").takeIf(String::isNotBlank) ?: return@mapNotNull null
            TrackingIdSeenEntry(
                key = key,
                apiName = json.optString("api"),
                title = json.optString("title").ifBlank { key },
                posterUrl = json.optString("poster").takeIf(String::isNotBlank),
                type = json.optString("type").toTvType(),
                year = json.optInt("year", 0).takeIf { it > 0 },
                detected = json.optJSONObject("ids")?.let { ids ->
                    ids.keys().asSequence().mapNotNull { serviceKey ->
                        val service = ManualTrackingService.fromKey(serviceKey) ?: return@mapNotNull null
                        ids.optString(serviceKey).trim().takeIf(String::isNotBlank)?.let { service to it }
                    }.toMap()
                }.orEmpty(),
                groupedSeasons = json.optBoolean("grouped"),
                seenAt = json.optLong("seenAt"),
            )
        }.distinctBy { it.key }
    }

    fun seen(key: String): TrackingIdSeenEntry? = seen().firstOrNull { it.key == key }

    fun remember(
        key: String,
        apiName: String,
        response: LoadResponse,
        detected: Map<ManualTrackingService, String>,
        groupedSeasons: Boolean,
    ) {
        if (response.type == TvType.Live || key.isBlank()) return
        val prefs = seenPrefs() ?: return
        val entry = TrackingIdSeenEntry(
            key = key,
            apiName = apiName,
            title = response.name,
            posterUrl = response.posterUrl,
            type = response.type,
            year = response.year,
            detected = detected,
            groupedSeasons = groupedSeasons,
            seenAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            val updated = (listOf(entry) + seen().filterNot { it.key == key }).take(MAX_SEEN)
            prefs.edit().putString(PREF_SEEN, updated.toSeenJson()).apply()
        }
    }

    fun removeSeen(key: String) {
        val prefs = seenPrefs() ?: return
        synchronized(lock) {
            prefs.edit().putString(PREF_SEEN, seen().filterNot { it.key == key }.toSeenJson()).apply()
        }
    }

    fun clearSeen() {
        synchronized(lock) { seenPrefs()?.edit()?.remove(PREF_SEEN)?.apply() }
    }

    private fun List<TrackingIdSeenEntry>.toSeenJson(): String = JSONArray().apply {
        forEach { entry ->
            put(
                JSONObject().apply {
                    put("key", entry.key)
                    put("api", entry.apiName)
                    put("title", entry.title)
                    entry.posterUrl?.let { put("poster", it) }
                    entry.type?.let { put("type", it.name) }
                    entry.year?.let { put("year", it) }
                    put("grouped", entry.groupedSeasons)
                    put("seenAt", entry.seenAt)
                    put(
                        "ids",
                        JSONObject().apply { entry.detected.forEach { (service, value) -> put(service.key, value) } },
                    )
                },
            )
        }
    }.toString()

    private fun String.toTvType(): TvType? = takeIf(String::isNotBlank)?.let { name ->
        runCatching { TvType.valueOf(name) }.getOrNull()
    }
}
