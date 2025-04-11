// use an integer for version numbers
version = 3

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Live streams from the Free TV github repository. Forked from the Free-TV plugin in the ItalianProvider repo"
    authors = listOf("Gian-Fr","Adippe","doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Live")

    requiresResources = true

    iconUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/TV/television.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}
