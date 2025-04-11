// use an integer for version numbers
version = 4

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Movies, TV Shows and Anime from ToonItalia.green"
    authors = listOf("doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Cartoon","TvSeries", "Movie", "Anime")

    language = "it"

    iconUrl = "https://toonitalia.green/wp-content/uploads/2023/08/favicon.ico"
}