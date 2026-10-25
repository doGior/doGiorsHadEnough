import java.security.MessageDigest
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

val nativeNdkVersion = "28.2.13676358"
val nativeCmakeVersion = "3.31.6"
val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val nativeCmakeArguments = listOf("-DANDROID_STL=c++_static")
val nativeLibraryName = "libstreamcenter_ass.so"
val nativeSourceDir = file("src/main/cpp")
val nativeCacheRoot = rootProject.file(".gradle/streamcenter-native")

fun resolveNativeCacheKey(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(nativeNdkVersion, nativeCmakeVersion, nativeAbis.joinToString(","), nativeCmakeArguments.joinToString(","))
        .forEach { digest.update(it.toByteArray()) }
    nativeSourceDir.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.relativeTo(nativeSourceDir).invariantSeparatorsPath }
        .forEach { source ->
            digest.update(source.relativeTo(nativeSourceDir).invariantSeparatorsPath.toByteArray())
            digest.update(source.readBytes())
        }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}

val nativeLibraryCache = nativeCacheRoot.resolve("libass/${resolveNativeCacheKey()}")
val nativeLibraryCached = nativeAbis.all { nativeLibraryCache.resolve("$it/$nativeLibraryName").isFile }

version = 13
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
    ndkVersion = nativeNdkVersion

    buildFeatures {
        buildConfig = true
    }

    if (!nativeLibraryCached) {
        externalNativeBuild {
            cmake {
                path = nativeSourceDir.resolve("CMakeLists.txt")
                version = nativeCmakeVersion
            }
        }
    }

    defaultConfig {
        ndk {
            abiFilters += nativeAbis
        }
        if (!nativeLibraryCached) {
            externalNativeBuild {
                cmake {
                    arguments += nativeCmakeArguments + listOf(
                        "-DSTREAMCENTER_DEPS_DIR=${nativeCacheRoot.resolve("libass-sources").invariantSeparatorsPath}",
                    )
                }
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
    compileOnly("io.coil-kt.coil3:coil:3.3.0")
    compileOnly("androidx.media3:media3-exoplayer:1.9.3")
    compileOnly("androidx.media3:media3-extractor:1.9.3")
    compileOnly("androidx.media3:media3-ui:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
}

val cacheNativeLibraries = tasks.register<Copy>("cacheNativeLibraries") {
    enabled = !nativeLibraryCached
    from(tasks.named("stripDebugDebugSymbols")) {
        include("**/$nativeLibraryName")
        eachFile { path = relativePath.segments.takeLast(2).joinToString("/") }
        includeEmptyDirs = false
    }
    into(nativeLibraryCache)
}

tasks.withType<Zip>().matching { it.name == "make" }.configureEach {
    if (nativeLibraryCached) {
        from(nativeLibraryCache) {
            include("*/$nativeLibraryName")
            into("libass")
        }
    } else {
        dependsOn(cacheNativeLibraries)
        from(tasks.named("stripDebugDebugSymbols")) {
            include("**/$nativeLibraryName")
            eachFile { path = "libass/" + relativePath.segments.takeLast(2).joinToString("/") }
            includeEmptyDirs = false
        }
    }
    from("logos") {
        include("*.png")
        into("logos")
    }
}
