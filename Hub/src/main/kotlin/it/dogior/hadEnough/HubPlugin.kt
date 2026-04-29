package it.dogior.hadEnough

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File

@CloudstreamPlugin
class HubPlugin : Plugin() {
    override fun load(context: Context) {
        Hub.setCacheDirectory(File(context.cacheDir, "hub_tmdb_cache"))
        registerMainAPI(Hub())

        openSettings = { ctx ->
            AlertDialog.Builder(ctx)
                .setTitle("Hub")
                .setMessage(getBuildInfoText())
                .setPositiveButton("Chiudi", null)
                .show()
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
