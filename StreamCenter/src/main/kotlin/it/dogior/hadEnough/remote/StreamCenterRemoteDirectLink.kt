package it.dogior.hadEnough.remote

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.objects
import it.dogior.hadEnough.util.optNullableString
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAXIMUM_URL_LENGTH = 4_096
private const val MAXIMUM_HEADERS = 24
private const val MAXIMUM_SUBTITLES = 40

internal data class RemoteLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String,
    val quality: Int,
    val type: ExtractorLinkType,
    val headers: Map<String, String>,
    val extractorData: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source)
        .put("name", name)
        .put("url", url)
        .put("referer", referer)
        .put("quality", quality)
        .put("type", type.name)
        .put("headers", headers.toJson())
        .putOpt("extractorData", extractorData)

    companion object {
        fun of(link: ExtractorLink?): RemoteLink? {
            if (link == null || link is DrmExtractorLink || link is ExtractorLinkPlayList) return null
            if (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT ||
                link.url.toHttpUrlOrNull() == null || link.url.length > MAXIMUM_URL_LENGTH) return null
            return RemoteLink(
                source = link.source,
                name = link.name,
                url = link.url,
                referer = link.referer,
                quality = link.quality,
                type = link.type,
                headers = link.headers.entries.asSequence()
                    .filter { it.key.length <= 200 && it.value.length <= MAXIMUM_URL_LENGTH }
                    .take(MAXIMUM_HEADERS).associate { it.key to it.value },
                extractorData = link.extractorData?.takeIf { it.length <= MAXIMUM_URL_LENGTH },
            )
        }

        fun fromJson(json: JSONObject?): RemoteLink? = json?.let {
            runCatching {
                RemoteLink(
                    source = it.getString("source").take(200),
                    name = it.getString("name").take(200),
                    url = it.getString("url").take(MAXIMUM_URL_LENGTH),
                    referer = it.optString("referer").take(MAXIMUM_URL_LENGTH),
                    quality = it.optInt("quality"),
                    type = ExtractorLinkType.valueOf(it.getString("type")),
                    headers = it.optJSONObject("headers").toStringMap(),
                    extractorData = it.optNullableString("extractorData")?.take(MAXIMUM_URL_LENGTH),
                )
            }.getOrNull()?.takeIf { link -> link.url.isNotBlank() }
        }
    }
}

internal data class RemoteSubtitleFile(val lang: String, val url: String, val headers: Map<String, String>) {
    fun toJson(): JSONObject = JSONObject().put("lang", lang).put("url", url).put("headers", headers.toJson())

    companion object {
        fun of(subtitle: SubtitleData): RemoteSubtitleFile? = subtitle
            .takeIf { it.origin == SubtitleOrigin.URL && it.url.startsWith("http") && it.url.length <= MAXIMUM_URL_LENGTH }
            ?.let { subtitleFile ->
                RemoteSubtitleFile(
                    subtitleFile.name, subtitleFile.url,
                    subtitleFile.headers.entries.asSequence()
                        .filter { it.key.length <= 200 && it.value.length <= MAXIMUM_URL_LENGTH }
                        .take(MAXIMUM_HEADERS).associate { h -> h.key to h.value },
                )
            }

        fun listOf(subtitles: List<SubtitleData>): List<RemoteSubtitleFile> =
            subtitles.mapNotNull(::of).distinctBy { it.url }.take(MAXIMUM_SUBTITLES)

        fun listFromJson(array: JSONArray?): List<RemoteSubtitleFile> = array.objects().mapNotNull { json ->
            val url = json.optString("url").take(MAXIMUM_URL_LENGTH).takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            RemoteSubtitleFile(json.optString("lang").take(120), url, json.optJSONObject("headers").toStringMap())
        }.take(MAXIMUM_SUBTITLES)
    }
}

internal object StreamCenterRemoteDirectLink {
    private const val PENDING_TTL_MS = 2 * 60_000L

    private var pending: Pair<RemoteHandoff, Long>? = null

    @Synchronized
    fun put(handoff: RemoteHandoff) {
        pending = handoff.takeIf { it.link != null }?.let { it to System.currentTimeMillis() + PENDING_TTL_MS }
    }

    @Synchronized
    fun take(apiName: String, data: String): RemoteHandoff? {
        val (handoff, until) = pending ?: return null
        if (System.currentTimeMillis() > until) {
            pending = null
            return null
        }
        if (handoff.apiName != apiName || handoff.episodeData != data) return null
        pending = null
        return handoff
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    suspend fun emit(
        handoff: RemoteHandoff,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = handoff.link ?: return false
        if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET ||
            link.url.toHttpUrlOrNull() == null
        ) return false
        handoff.subtitles.forEach { subtitle ->
            subtitleCallback(newSubtitleFile(subtitle.lang, subtitle.url) { headers = subtitle.headers })
        }
        callback(newExtractorLink(link.source, link.name, link.url, link.type) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
        })
        StreamCenterLogger.logMenu(
            action = "Telecomando: fonte ricevuta avviata direttamente",
            metadata = mapOf("titolo" to handoff.name, "fonte" to link.name, "sottotitoli" to handoff.subtitles.size),
        )
        return true
    }
}

private fun Map<String, String>.toJson(): JSONObject = JSONObject().also { json ->
    forEach { (key, value) -> json.put(key, value) }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    val json = this ?: return emptyMap()
    return json.keys().asSequence().take(MAXIMUM_HEADERS)
        .mapNotNull { key ->
            json.optString(key).takeIf { key.length <= 200 && it.length <= MAXIMUM_URL_LENGTH }
                ?.let { value -> key to value }
        }.toMap()
}
