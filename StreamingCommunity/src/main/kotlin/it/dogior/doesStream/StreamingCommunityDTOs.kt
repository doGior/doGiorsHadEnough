package it.dogior.doesStream

import com.fasterxml.jackson.annotation.JsonProperty

data class Section(
    @JsonProperty("name") val name: String,
    @JsonProperty("label") val label: String,
    @JsonProperty("titles") val titles: List<Title>,
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

data class TitlePreview(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("runtime") val runtime: Int,
    @JsonProperty("release_date") val releaseDate: String,
    @JsonProperty("quality") val quality: String,
    @JsonProperty("plot") val plot: String,
    @JsonProperty("seasons_count") val seasonsCount: Int,
    @JsonProperty("genres") val genres: List<Genre>,
    @JsonProperty("images") val images: List<PosterImage>
) {
    fun getBackgroundImage(): String? {
        this.images.forEach {
            if (it.type == "background") {
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

data class SearchData(
    @JsonProperty("data") val titles: List<Title>,
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
    @JsonProperty("plot") val plot: String,
    @JsonProperty("duration") val duration: Int,
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
    @JsonProperty("plot") val plot: String,
    @JsonProperty("quality") val quality: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("score") val score: String,
    @JsonProperty("release_date") val releaseDate: String,
    @JsonProperty("status") val status: String?,
    @JsonProperty("age") val age: Int,
    @JsonProperty("seasons_count") val seasonsCount: Int,
    @JsonProperty("scws_id") val scwsId: Int?,
    @JsonProperty("seasons") val seasons: List<Season>,
    @JsonProperty("images") val images: List<PosterImage>,
    @JsonProperty("genres") val genres: List<Genre>,
    @JsonProperty("main_actors") val mainActors: List<MainActor>
){
    fun getBackgroundImage(): String? {
        this.images.forEach {
            if (it.type == "background") {
                return it.filename
            }
        }
        return null
    }
}

data class Server(
    @JsonProperty("name") val name: String,
    @JsonProperty("active") val active: Boolean,
    @JsonProperty("url") val url: String
)

data class MasterPlaylist(
    @JsonProperty("params") val params: Params,
    @JsonProperty("url") val url: String
)

data class Params(
    @JsonProperty("token") val token: String,
    @JsonProperty("expires") val expires: String
)