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

version = 5
val pluginVersion = version.toString()

cloudstream {
    description = "Film, Serie TV, Anime e TV in un unico posto."
    authors = listOf("Italiani")

    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Live")

    requiresResources = true
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/StreamCenter/icon.png"
}

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
}
