// use an integer for version numbers
version = 10


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Movies and Shows from CB01"
    authors = listOf("doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Cartoon")

    requiresResources = false
    language = "it"

    iconUrl = "https://cb01.uno/favicon-512x512.png"
}
