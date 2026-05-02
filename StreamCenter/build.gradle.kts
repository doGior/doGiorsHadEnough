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

    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).apply {
        timeZone = TimeZone.getTimeZone("Europe/Rome")
    }.format(Date())
}

val buildCommitSha = resolveBuildCommitSha()
val buildCompletedAtRome = resolveBuildCompletedAtRome()

// use an integer for version numbers
version = 1

cloudstream {
    description = "Lavori in corso."
    authors = listOf("Italiani")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie")

    requiresResources = true
    language = "it"

    iconUrl = "https://www.themoviedb.org/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
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
