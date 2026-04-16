package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// Sealed class to represent either Boolean or String
sealed class BooleanOrString {
    data class AsBoolean(val value: Boolean) : BooleanOrString(){
        override fun toString(): String {
            return value.toString()
        }
    }
    data class AsString(val value: String) : BooleanOrString()

    fun getValue(): Any {
        return when (this) {
            is AsBoolean -> this.value // Access boolean value
            is AsString -> this.value  // Access string value
        }
    }
}


data class RequestData(
    val title: String = "",
    val type: BooleanOrString = BooleanOrString.AsBoolean(false),
    val year: BooleanOrString = BooleanOrString.AsBoolean(false),
    val orderBy: BooleanOrString = BooleanOrString.AsBoolean(false),
    val status: BooleanOrString = BooleanOrString.AsBoolean(false),
    val genres: BooleanOrString = BooleanOrString.AsBoolean(false),
    /*** Inverno, Primavera, Estate, Autunno ***/
    val season: BooleanOrString = BooleanOrString.AsBoolean(false),
    var offset: Int? = 0,
    val dubbed: Int = 1,
){
    private fun toJson(): JSONObject {
        val m = mapOf(
            "title" to title,
            "type" to type.getValue(),
            "year" to year.getValue(),
            "order" to orderBy.getValue(),
            "status" to status.getValue(),
            "genres" to genres.getValue(),
            "season" to season.getValue(),
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

data class AnilistResponse(
    @JsonProperty("data") val data: AnilistData
)

data class AnilistData(
    @JsonProperty("Media") val media: AnilistMedia
)

data class AnilistMedia(
    @JsonProperty("id") val id: Int,
    @JsonProperty("coverImage") val coverImage: AnilistCoverImage?,
    @JsonProperty("duration") val duration: Int?
)

data class AnilistCoverImage(
    @JsonProperty("medium") val medium: String?,
    @JsonProperty("large") val large: String?,
    @JsonProperty("extraLarge") val extraLarge: String?
)

