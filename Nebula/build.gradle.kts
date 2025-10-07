// use an integer for version numbers
version = 1


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Plugin to watch the free videos and ONLY THE FREE VIDEOS from nebula.tv"
    authors = listOf("doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Others")

    requiresResources = false
    language = "en"

    iconUrl = "https://nebula.tv/apple-touch-icon.png"
}
