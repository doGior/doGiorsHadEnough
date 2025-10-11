// use an integer for version numbers
version = 13


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

    iconUrl = "https://cb01.uno/apple-icon-180x180px.png"
}
dependencies{
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}