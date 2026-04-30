package it.dogior.hadEnough

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File

@CloudstreamPlugin
class HubPlugin : Plugin() {
    private val feedbackIssuesUrl = "https://github.com/doGior/doGiorsHadEnough/issues/new"

    override fun load(context: Context) {
        Hub.setCacheDirectory(File(context.cacheDir, "hub_tmdb_cache"))
        registerMainAPI(Hub())

        openSettings = { ctx ->
            showSettingsDialog(ctx)
        }
    }

    private fun showSettingsDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Hub")
            .setMessage(buildSettingsMessage())
            .setPositiveButton("Segnala un Problema") { _, _ ->
                openFeedbackPage(context, "Hub [problema]: ")
            }
            .setNegativeButton("Suggerisci un Miglioramento") { _, _ ->
                openFeedbackPage(context, "Hub [suggerimento]: ")
            }
            .setNeutralButton("Chiudi", null)
            .show()
    }

    private fun buildSettingsMessage(): String {
        return "${getBuildInfoText()}\n\nSegnalazioni e Suggerimenti\nVuoi segnalare un problema o suggerire un miglioramento?"
    }

    private fun openFeedbackPage(context: Context, titlePrefix: String) {
        val issuesUrl = "$feedbackIssuesUrl?title=${Uri.encode(titlePrefix)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issuesUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    context,
                    "Impossibile aprire GitHub in questo momento.",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    private fun getBuildInfoText(): String {
        val rawCommit = BuildConfig.BUILD_COMMIT_SHA.trim()
        val rawBuildCompletedAt = BuildConfig.BUILD_COMPLETED_AT_ROME.trim()
        val shortCommit = rawCommit.takeIf { it.isNotEmpty() && it != "unknown" }?.take(7)
        return if (shortCommit != null && rawBuildCompletedAt.isNotEmpty()) {
            "Commit $shortCommit | Build $rawBuildCompletedAt"
        } else if (shortCommit != null) {
            "Commit $shortCommit"
        } else {
            "Informazioni build non disponibili"
        }
    }
}
