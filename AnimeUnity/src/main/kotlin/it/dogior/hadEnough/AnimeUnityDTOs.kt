package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class BooleanOrString {
    data class AsBoolean(val value: Boolean) : BooleanOrString() {
        override fun toString(): String {
            return value.toString()
        }
    }

    data class AsString(val value: String) : BooleanOrString()

    fun toJsonValue(): Any {
        return when (this) {
            is AsBoolean -> value
            is AsString -> value
        }
    }
}


data class RequestData(
    val title: String = "",
    val type: BooleanOrString = BooleanOrString.AsBoolean(false),
    val year: BooleanOrString = BooleanOrString.AsBoolean(false),
    val orderBy: BooleanOrString = BooleanOrString.AsBoolean(false),
    val status: BooleanOrString = BooleanOrString.AsBoolean(false),
    val genres: Any = BooleanOrString.AsBoolean(false),
    /*** Inverno, Primavera, Estate, Autunno ***/
    val season: BooleanOrString = BooleanOrString.AsBoolean(false),
    val offset: Int? = 0,
    val dubbed: Int = 1,
) {
    private fun serializeValue(value: Any): Any {
        return when (value) {
            is BooleanOrString -> value.toJsonValue()
            is List<*> -> JSONArray().apply {
                value.filterIsInstance<ArchiveGenreOption>().forEach { genre ->
                    put(
                        JSONObject().apply {
                            put("id", genre.id)
                            put("name", genre.name)
                        }
                    )
                }
            }
            else -> value
        }
    }

    private fun toJson(): JSONObject {
        val m = mapOf(
            "title" to title,
            "type" to serializeValue(type),
            "year" to serializeValue(year),
            "order" to serializeValue(orderBy),
            "status" to serializeValue(status),
            "genres" to serializeValue(genres),
            "season" to serializeValue(season),
            "dubbed" to dubbed,
            "offset" to offset
        )

        return JSONObject().apply {
            m.forEach { (key, value) ->
                put(key, value)
            }
        }
    }

    fun toRequestBody(): RequestBody {
        return this.toJson().toString()
            .toRequestBody("application/json;charset=utf-8".toMediaType())
    }
}


data class ApiResponse(
    @JsonProperty("records") val titles: List<Anime>?,
    @JsonProperty("tot") val total: Int
)

data class Anime(
    @JsonProperty("id") val id: Int,
//    @JsonProperty("user_id") val userId: Int,
    @JsonProperty("title") val title: String?,
    @JsonProperty("imageurl") val imageUrl: String,
    @JsonProperty("plot") val plot: String,
    @JsonProperty("date") val date: String,
    @JsonProperty("episodes_count") val episodesCount: Int,
    @JsonProperty("real_episodes_count") val realEpisodesCount: Int?,
    @JsonProperty("episodes_length") val episodesLength: Int,
    @JsonProperty("status") val status: String,
//    @JsonProperty("imageurl_cover") val imageUrlCover: String?,
    @JsonProperty("type") val type: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title_eng") val titleEng: String?,
    @JsonProperty("day") val day: String?,
    @JsonProperty("score") val score: String?,
//    @JsonProperty("studio") val studio: String,
    @JsonProperty("dub") val dub: Int,
//    @JsonProperty("always_home") val alwaysHome: Int,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("anilist_id") val anilistId: Int?,
//    @JsonProperty("season") val season: String,
    @JsonProperty("title_it") val titleIt: String?,
    @JsonProperty("mal_id") val malId: Int?,
    @JsonProperty("episodes") val episodes: List<Episode>?,
    @JsonProperty("genres") val genres: List<Genre>
)

data class Episode(
    @JsonProperty("id") val id: Int,
    @JsonProperty("anime_id") val animeId: Int,
    @JsonProperty("user_id") val userId: Int?,
    @JsonProperty("number") val number: String,
    @JsonProperty("link") val link: String,
    @JsonProperty("visite") val visite: Int,
    @JsonProperty("hidden") val hidden: Int,
    @JsonProperty("public") val isPublic: Int,
    @JsonProperty("scws_id") val scwsId: Int,
    @JsonProperty("file_name") val fileName: String?
)

data class LatestEpisodesPage(
    @JsonProperty("current_page") val currentPage: Int,
    @JsonProperty("data") val episodes: List<LatestEpisodeItem>
)

data class LatestEpisodeItem(
    @JsonProperty("id") val id: Int,
    @JsonProperty("anime_id") val animeId: Int,
    @JsonProperty("user_id") val userId: Int?,
    @JsonProperty("number") val number: String,
    @JsonProperty("anime") val anime: LatestEpisodeAnime
)

data class LatestEpisodeAnime(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String?,
    @JsonProperty("imageurl") val imageUrl: String?,
    @JsonProperty("episodes_count") val episodesCount: Int,
    @JsonProperty("real_episodes_count") val realEpisodesCount: Int?,
    @JsonProperty("type") val type: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title_eng") val titleEng: String?,
    @JsonProperty("score") val score: String?,
    @JsonProperty("dub") val dub: Int,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("anilist_id") val anilistId: Int?,
    @JsonProperty("title_it") val titleIt: String?
)

data class AnimeInfo(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String?,
//    @JsonProperty("slug") val slug: String,
    @JsonProperty("episodes_count") val episodesCount: Int,
    @JsonProperty("current_episode") val currentEpisode: Int,
    @JsonProperty("episodes") val episodes: List<Episode>
)

data class Genre(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String
)

data class ArchiveGenreOption(
    val id: Int,
    val name: String,
)

data class AdvancedSearchConfig(
    val enabled: Boolean,
    val title: String,
    val genre: ArchiveGenreOption?,
    val year: String?,
    val order: String?,
    val status: String?,
    val type: String?,
    val season: String?,
)

data class AnilistResponse(
    @JsonProperty("data") val data: AnilistData
)

data class AnilistData(
    @JsonProperty("Media") val media: AnilistMedia
)

data class AnilistMedia(
    @JsonProperty("id") val id: Int,
    @JsonProperty("coverImage") val coverImage: AnilistCoverImage?,
    @JsonProperty("duration") val duration: Int?,
    @JsonProperty("trailer") val trailer: AnilistTrailer?
)

data class AnilistCoverImage(
    @JsonProperty("medium") val medium: String?,
    @JsonProperty("large") val large: String?,
    @JsonProperty("extraLarge") val extraLarge: String?
)

data class AnilistTrailer(
    @JsonProperty("id") val id: String?,
    @JsonProperty("site") val site: String?,
    @JsonProperty("thumbnail") val thumbnail: String?
)

data class JikanFullResponse(
    @JsonProperty("data") val data: JikanAnimeData
)

data class JikanAnimeData(
    @JsonProperty("trailer") val trailer: JikanTrailer?
)

data class JikanSearchResponse(
    @JsonProperty("data") val data: List<JikanSearchAnime>
)

data class JikanSearchAnime(
    @JsonProperty("title") val title: String,
    @JsonProperty("title_english") val titleEnglish: String?,
    @JsonProperty("title_japanese") val titleJapanese: String?,
    @JsonProperty("title_synonyms") val titleSynonyms: List<String>?,
    @JsonProperty("trailer") val trailer: JikanTrailer?
)

data class JikanTrailer(
    @JsonProperty("youtube_id") val youtubeId: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("embed_url") val embedUrl: String?
)

data class AniZipMetadata(
    @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>?,
    @JsonProperty("mappings") val mappings: AniZipMappings?
)

data class AniZipMappings(
    @JsonProperty("mal_id") val malId: Int?,
    @JsonProperty("anilist_id") val anilistId: Int?,
    @JsonProperty("kitsu_id") val kitsuId: Int?
)

data class AniZipEpisode(
    @JsonProperty("episode") val episode: String?,
    @JsonProperty("airDate") val airDate: String?,
    @JsonProperty("airDateUtc") val airDateUtc: String?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("image") val image: String?,
    @JsonProperty("title") val title: Map<String, String?>?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("summary") val summary: String?,
    @JsonProperty("rating") val rating: String?
)
