import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 26


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

    requiresResources = true
    language = "it"

    iconUrl = "https://streamingunity.tv/apple-touch-icon.png?v=2"
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
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${properties.getProperty("SIMKL_CLIENT_ID")}\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.room:room-compiler:2.8.4")
}
