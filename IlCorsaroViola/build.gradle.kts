import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 1


cloudstream {
    // All of these properties are optional, you can safely remove them
    description =
        "Torrents from Il Corsaro Viola"
    authors = listOf("doGior")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Torrent", "Documentary")

    requiresResources = true
    language = "it"

    iconUrl = "https://i.imgur.com/OPM9e9p.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API")}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${properties.getProperty("SIMKL_CLIENT_ID")}\"")
        buildConfigField("String", "IlCorsaroViolaVercel", "\"${properties.getProperty("IlCorsaroViolaVercel")}\"")

    }
}
