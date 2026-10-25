package it.dogior.hadEnough.remote

import it.dogior.hadEnough.util.objects
import it.dogior.hadEnough.util.optIntValue
import it.dogior.hadEnough.util.optNullableString
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteHandoff(
    val url: String,
    val apiName: String,
    val name: String,
    val episodeId: Int?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val linkName: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("apiName", apiName)
        .put("name", name)
        .putOpt("episodeId", episodeId)
        .putOpt("season", season)
        .putOpt("episode", episode)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .putOpt("linkName", linkName)

    companion object {
        fun fromJson(json: JSONObject): RemoteHandoff? = runCatching {
            RemoteHandoff(
                url = json.getString("url").take(2_048),
                apiName = json.getString("apiName").take(120),
                name = json.optString("name").take(200),
                episodeId = json.optIntValue("episodeId"),
                season = json.optIntValue("season"),
                episode = json.optIntValue("episode"),
                positionMs = json.optLong("positionMs").coerceAtLeast(0L),
                durationMs = json.optLong("durationMs").coerceAtLeast(0L),
                linkName = json.optNullableString("linkName")?.take(200),
            )
        }.getOrNull()
    }
}

internal data class RemoteSubtitle(val id: String, val label: String)

internal data class RemotePlayerState(
    val title: String,
    val poster: String?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val subtitles: List<RemoteSubtitle>,
    val selectedSubtitle: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .putOpt("poster", poster)
        .putOpt("season", season)
        .putOpt("episode", episode)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("playing", playing)
        .put("subtitles", JSONArray().apply {
            subtitles.forEach { put(JSONObject().put("id", it.id).put("label", it.label)) }
        })
        .putOpt("selectedSubtitle", selectedSubtitle)

    companion object {
        fun fromJson(json: JSONObject?): RemotePlayerState? = json?.takeIf { it.has("title") }?.let {
            RemotePlayerState(
                title = it.optString("title"),
                poster = it.optNullableString("poster"),
                season = it.optIntValue("season"),
                episode = it.optIntValue("episode"),
                positionMs = it.optLong("positionMs"),
                durationMs = it.optLong("durationMs"),
                playing = it.optBoolean("playing"),
                subtitles = it.optJSONArray("subtitles").objects().map { entry ->
                    RemoteSubtitle(entry.optString("id"), entry.optString("label"))
                },
                selectedSubtitle = it.optNullableString("selectedSubtitle"),
            )
        }
    }
}

internal data class RemoteQueueItem(
    val id: String,
    val url: String,
    val apiName: String,
    val name: String,
    val poster: String?,
    val addedAt: Long,
    val addedBy: String,
    val order: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("apiName", apiName)
        .put("name", name)
        .putOpt("poster", poster)
        .put("addedAt", addedAt)
        .put("addedBy", addedBy)
        .put("order", order)

    companion object {
        fun fromJson(json: JSONObject): RemoteQueueItem? = runCatching {
            RemoteQueueItem(
                id = json.getString("id"),
                url = json.getString("url").take(2_048),
                apiName = json.getString("apiName").take(120),
                name = json.optString("name").take(200),
                poster = json.optNullableString("poster")?.take(2_048),
                addedAt = json.optLong("addedAt"),
                addedBy = json.optString("addedBy").take(80),
                order = json.optLong("order", json.optLong("addedAt")),
            )
        }.getOrNull()
    }
}
