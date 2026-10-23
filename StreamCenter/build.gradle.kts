import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun escapeBuildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun resolveBuildCommitSha(): String {
    val githubSha = providers.environmentVariable("GITHUB_SHA").orNull?.trim()
    if (!githubSha.isNullOrEmpty()) return githubSha

    return runCatching {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("unknown")
}

fun resolveBuildCompletedAtRome(): String {
    val envValue = providers.environmentVariable("BUILD_COMPLETED_AT_ROME").orNull?.trim()
    if (!envValue.isNullOrEmpty()) return envValue

    return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).apply {
        timeZone = TimeZone.getTimeZone("Europe/Rome")
    }.format(Date())
}

val buildCommitSha = resolveBuildCommitSha()
val buildCompletedAtRome = resolveBuildCompletedAtRome()

version = 12
val pluginVersion = version.toString()

cloudstream {
    description = "Film, Serie TV, Anime e TV in un unico posto."
    authors = listOf("Italiani")

    status = 1

    tvTypes = listOf(
        "TvSeries", 
        "Movie", 
        "AsianDrama", 
        "Anime", 
        "Torrent",
        "Live",
        "Others"
    )

    requiresResources = true
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/StreamCenter/icon.png"
}

android {
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DSTREAMCENTER_DEPS_DIR=${layout.buildDirectory.dir("libass-sources").get().asFile.invariantSeparatorsPath}",
                )
            }
        }
        buildConfigField("String", "PLUGIN_VERSION", escapeBuildConfigString(pluginVersion))
        buildConfigField("String", "BUILD_COMMIT_SHA", escapeBuildConfigString(buildCommitSha))
        buildConfigField(
            "String",
            "BUILD_COMPLETED_AT_ROME",
            escapeBuildConfigString(buildCompletedAtRome)
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    compileOnly("io.coil-kt.coil3:coil:3.3.0")
    compileOnly("androidx.media3:media3-exoplayer:1.9.3")
    compileOnly("androidx.media3:media3-extractor:1.9.3")
    compileOnly("androidx.media3:media3-ui:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
}

tasks.withType<Zip>().matching { it.name == "make" }.configureEach {
    from(tasks.named("stripDebugDebugSymbols")) {
        include("**/libstreamcenter_ass.so")
        eachFile { path = "libass/" + relativePath.segments.takeLast(2).joinToString("/") }
        includeEmptyDirs = false
    }
    from("logos") {
        include("*.png")
        into("logos")
    }
}
