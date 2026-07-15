// use an integer for version numbers
version = 4


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Magazine shows, concerts, documentaries, and more: the European culture channel's programmes available to stream free of charge on arte.tv"
    authors = listOf("doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Documentary")

    requiresResources = true
    language = "en"

    iconUrl = "https://static-cdn.arte.tv/replay/favicons/favicon-194x194.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}