package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty

data class Results(
    @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    @JsonProperty("total_pages") val totalPages: Int,
)

data class Media(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
)

data class Data(
    val id: Int,
    val type: String? = null,
    val aniId: String? = null,
    val malId: Int? = null,
)

data class MediaDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Any? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
    @JsonProperty("videos") val videos: ResultsTrailer? = null,
    @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    @JsonProperty("credits") val credits: Credits? = null,
    @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
    @JsonProperty("last_episode_to_air") val lastEpisodeToAir: LastEpisodeToAir? = null,
    @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
)

data class ExternalIds(
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("tvdb_id") val tvdbId: Int? = null,
)

data class Episode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
)

data class MediaDetailEpisodes(
    @JsonProperty("episodes") val episodes: ArrayList<Episode>? = arrayListOf(),
)

data class LastEpisodeToAir(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
)

data class Seasons(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("air_date") val airDate: String? = null,
)

data class Credits(
    @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
)

data class Cast(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("known_for_department") val knownForDepartment: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

data class Genres(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class Trailers(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class ResultsTrailer(
    @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
)

data class ResultsRecommendations(
    @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
)

data class LinkData(
    val id: Int? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val epid: Int? = null,
    val aniId: String? = null,
    val animeId: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val orgTitle: String? = null,
    val isAnime: Boolean = false,
    val airedYear: Int? = null,
    val lastSeason: Int? = null,
    val epsTitle: String? = null,
    val date: String? = null,
    val airedDate: String? = null,
)

data class TorrentioResponse(
    @JsonProperty("streams") val streams: List<TorrentioStream> = emptyList()
)

data class TorrentioStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("infoHash") val infoHash: String? = null,
    @JsonProperty("fileIdx") val fileIdx: Int? = null
)