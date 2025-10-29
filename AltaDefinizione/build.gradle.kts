// use an integer for version numbers
version = 4


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Movies and Tv Series from Altadefinizione"
    authors = listOf("doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Documentary")

    requiresResources = false
    language = "it"

    iconUrl = "https://altadefinizionegratis.group/templates/Dark/img/favicon.ico"
}
