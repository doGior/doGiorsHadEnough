// use an integer for version numbers
version = 2


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    authors = listOf("doGior")
    description =
        "No Streaming. This is just to open the info page of a show from the simkl library"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
        "TvSeries",
        "Movie",
        "Documentary",
        "Cartoon"
    )

    iconUrl = "https://eu.simkl.in/img_favicon/v2/favicon-192x192.png"
}