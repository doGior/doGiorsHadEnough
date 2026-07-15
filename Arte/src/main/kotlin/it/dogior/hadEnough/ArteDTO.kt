package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty

data class Value(
    @JsonProperty("data") val data: List<EpisodeData>,
    @JsonProperty("pagination") val pagination: Pagination?,
)

data class EpisodeData(
    @JsonProperty("mainImage") val image: ShowImage,
    @JsonProperty("shortDescription") val description: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("duration") val duration: Int, //In minutes
    @JsonProperty("url") val url: String,
    @JsonProperty("availability") val availability: Availability,
    @JsonProperty("episodeInfo") val epInfo: EpisodeInfo? = null,
)

data class EpisodeInfo(
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("episode") val episode: String? = null,
)

data class Availability(
    @JsonProperty("start") val start: String,
    @JsonProperty("label") val label: String,
)

data class ShowImage(
    @JsonProperty("url") val url: String,
)

data class Pagination(
    @JsonProperty("page") val currentPage: Int,
    @JsonProperty("pages") val totalPages: Int,
)

data class Page(
    @JsonProperty("zones") val zones: List<Zones>,
)

data class Zones(
    @JsonProperty("content") val content: SearchContent,
    @JsonProperty("displayOptions") val displayOptions: DisplayOptions,
    @JsonProperty("title") val title: String
)

data class SearchContent(
    @JsonProperty("data") val data: List<ApiData>,
)

data class DisplayOptions(
    @JsonProperty("template") val template: String
)

data class ApiData(
    @JsonProperty("mainImage") val image: ShowImage,
//        @JsonProperty("shortDescription") val description: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("subtitle") val subtitle: String?,
    @JsonProperty("programId") val programId: String?,
    @JsonProperty("url") val url: String,
    @JsonProperty("kind") val kind: Kind,
)

data class Kind(
    @JsonProperty("code") val code: String,
    @JsonProperty("label") val label: String,
    @JsonProperty("isCollection") val isCollection: Boolean,
)