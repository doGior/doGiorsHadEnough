// use an integer for version numbers
version = 14


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "TV Shows and Movies from StreamingCommunity"
    authors = listOf("doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Cartoon"
    )


    requiresResources = false
    language = "it"

    iconUrl = "https://streamingcommunity.lu/apple-touch-icon.png?v=2"
}
