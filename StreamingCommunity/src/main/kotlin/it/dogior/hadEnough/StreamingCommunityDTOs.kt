package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody


data class SliderFetchRequestSlider(
    val name: String,
    val genre: String?
)

data class SliderFetchRequestBody(
    val sliders: List<SliderFetchRequestSlider>
) {
    fun toRequestBody(): RequestBody {
        return this.toJson().toRequestBody("application/json;charset=utf-8".toMediaType())
    }
}

data class LoadData(
    val url: String,
    val type: String,
    val tmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class Section(
    @JsonProperty("name") val name: String,
    @JsonProperty("label") val label: String,
    @JsonProperty("titles") val titles: List<Title>,
)

data class SearchResponse(
    @JsonProperty("current_page") val currentPage: Int,
    @JsonProperty("data") val data: List<Title>,
    @JsonProperty("last_page") val lastPage: Int
)

data class Title(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("images") val images: List<PosterImage>,
) {
    fun getPoster(): String? {
        this.images.forEach {
            if (it.type == "poster") {
                return it.filename
            }
        }
        return null
    }
}


data class PosterImage(
    @JsonProperty("filename") val filename: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("imageable_type") val imageableType: String,
    @JsonProperty("imageable_id") val imageableId: Int,
)

data class Genre(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("type") val type: String,
)


data class InertiaResponse(
    @JsonProperty("props") val props: Props,
    @JsonProperty("url") val url: String,
    @JsonProperty("version") val version: String
)

data class Props(
    @JsonProperty("scws_url") val scwsUrl: String,
    @JsonProperty("cdn_url") val cdnUrl: String,
    @JsonProperty("title") val title: TitleProp?,
    @JsonProperty("loadedSeason") val loadedSeason: Season?,
    @JsonProperty("sliders") val sliders: List<Slider>?,
    @JsonProperty("genres") val genres: List<Genre>?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("browseMoreApiRoute") val browseMoreApiRoute: String?,
    @JsonProperty("titles") val titles: List<Title>? //Only used in searches
)

data class Season(
    @JsonProperty("id") val id: Int,
    @JsonProperty("number") val number: Int,
    @JsonProperty("name") val name: String?,
    @JsonProperty("plot") val plot: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("title_id") val titleId: Int,
    @JsonProperty("episodes") val episodes: List<Episode>?
)

data class Episode(
    @JsonProperty("id") val id: Int,
    @JsonProperty("number") val number: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("plot") val plot: String?,
    @JsonProperty("duration") val duration: Int?,
    @JsonProperty("scws_id") val scwsId: Int,
    @JsonProperty("season_id") val seasonId: Int,
    @JsonProperty("images") val images: List<PosterImage>
){
    fun getCover(): String? {
        this.images.forEach {
            if (it.type == "cover") {
                return it.filename
            }
        }
        return null
    }
}

data class Slider(
    @JsonProperty("name") val name: String,
    @JsonProperty("label") val label: String,
    @JsonProperty("titles") val titles: List<Title>,
)

data class MainActor(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String,
)

data class TitleProp(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("plot") val plot: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("score") val score: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("age") val age: Int?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("tmdb_id") val tmdbId: Int?,
    @JsonProperty("imdb_id") val imdbId: String?,
    @JsonProperty("seasons_count") val seasonsCount: Int?,
    @JsonProperty("scws_id") val scwsId: Int?,
    @JsonProperty("trailers") val trailers: List<Trailer>?,
    @JsonProperty("seasons") val seasons: List<Season>?,
    @JsonProperty("images") val images: List<PosterImage>,
    @JsonProperty("genres") val genres: List<Genre>,
    @JsonProperty("main_actors") val mainActors: List<MainActor>?
){
    fun getBackgroundImageId(): String? {
        this.images.forEach {
            if (it.type == "background") {
                return it.filename
            }
        }
        return null
    }
    fun getPosterImageId(): String? {
        this.images.forEach {
            if (it.type == "poster") {
                return it.filename
            }
        }
        return null
    }
}


data class Trailer(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String?,
    @JsonProperty("youtube_id") val youtubeId: String?,
    @JsonProperty("title_id") val titleId: Int?,
){
    fun getYoutubeUrl(): String? {
        if(this.youtubeId == null) return null
        return "https://www.youtube.com/watch?v=${this.youtubeId}"
    }
}