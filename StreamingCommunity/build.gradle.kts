// use an integer for version numbers
version = 23


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "TV Shows and Movies from StreamingCommunity (now StreamingUnity)"
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
        "Documentary",
        "Cartoon"
    )

    requiresResources = false
    language = "it"

    iconUrl = "https://streamingunity.co/apple-touch-icon.png?v=2"
}
