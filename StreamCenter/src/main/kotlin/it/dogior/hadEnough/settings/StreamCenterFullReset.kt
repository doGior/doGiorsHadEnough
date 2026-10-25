package it.dogior.hadEnough.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import it.dogior.hadEnough.StreamCenter
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.cache.StreamCenterHomeCalendarCache
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncAutoConfig
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncAutoRunner
import it.dogior.hadEnough.remote.StreamCenterRemote
import it.dogior.hadEnough.torrent.StreamCenterExtCloudflareSession
import it.dogior.hadEnough.util.StreamCenterLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

internal object StreamCenterFullReset {
    internal val preferenceNames = setOf(
        StreamCenterPlugin.PREFS_NAME, "StreamCenterBackupStorage", "StreamCenterTrackingIds",
        "streamcenter_local_sync_auto", "streamcenter_local_sync_trust",
        "streamcenter_local_sync_versions", "streamcenter_ext_cloudflare_session",
    )

    suspend fun reset(context: Context, preferences: SharedPreferences?) {
        val app = context.applicationContext
        StreamCenterPlugin.completeResetInProgress = true
        StreamCenterLogger.stopForCompleteReset()
        StreamCenterLocalSyncAutoConfig.reset(app)
        withTimeout(15_000L) {
            StreamCenterLocalSyncAutoRunner.stopForCompleteReset()
            StreamCenterRemote.stopForCompleteReset()
            StreamCenter.prepareForCompleteReset()
        }
        StreamCenterHomeCalendarCache.stopForCompleteReset()
        StreamCenterExtCloudflareSession.clearForCompleteReset()
        withContext(Dispatchers.IO) {
            val failures = mutableListOf<Throwable>()
            fun attempt(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(failures::add)
            }
            attempt { StreamCenterBackupManager.resetDirectory(app) }
            val names = preferenceNames + File(app.applicationInfo.dataDir, "shared_prefs")
                .listFiles().orEmpty().mapNotNull { file ->
                    file.name.removeSuffix(".bak").takeIf {
                        it.startsWith("streamcenter", ignoreCase = true) && it.endsWith(".xml")
                    }
                        ?.removeSuffix(".xml")
                }
            val stores = names.map { app.getSharedPreferences(it, Context.MODE_PRIVATE) } +
                listOfNotNull(preferences, StreamCenterPlugin.activeSharedPref)
            stores.distinct().forEach { store -> attempt { clearPreferences(store) } }
            attempt { deleteOwnedFiles(app.filesDir) }
            attempt { deleteOwnedFiles(app.cacheDir) }
            attempt { deleteChild(File(app.filesDir, "documents"), "StreamCenter") }
            app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { documents ->
                attempt { deleteChild(documents, "StreamCenter") }
            }
            if (failures.isNotEmpty()) {
                throw IllegalStateException("Reset completo non riuscito. Riavvia CloudStream e riprova.", failures.first())
                    .apply { failures.drop(1).forEach(::addSuppressed) }
            }
        }
    }

    internal fun clearPreferences(preferences: SharedPreferences) {
        check(preferences.edit().clear().commit()) { "Impossibile cancellare i dati di StreamCenter." }
    }

    internal fun deleteOwnedFiles(root: File) {
        if (!root.exists()) return
        val children = checkNotNull(root.listFiles()) { "Impossibile leggere ${root.name}." }
        children.filter {
            it.name.removePrefix(".").startsWith("streamcenter_", ignoreCase = true) ||
                it.name.removePrefix(".").startsWith("streamcenter-", ignoreCase = true)
        }.forEach { deleteChild(root, it.name) }
    }

    internal fun deleteChild(root: File, name: String) {
        check(name == File(name).name && name != "." && name != "..") { "Percorso di ripristino non valido." }
        val target = File(root, name)
        if (!target.exists()) return
        val boundary = root.canonicalFile.path + File.separator
        check(target.canonicalFile.path.startsWith(boundary)) { "Percorso di ripristino non valido." }
        fun remove(file: File, scope: String) {
            val path = file.canonicalFile.path
            check(path == scope || path.startsWith(scope + File.separator)) { "Percorso di ripristino non valido." }
            check(file.canonicalFile == File(checkNotNull(file.parentFile).canonicalFile, file.name)) {
                "Collegamento non valido nel percorso di ripristino."
            }
            if (file.isDirectory) {
                checkNotNull(file.listFiles()) { "Impossibile leggere ${file.name}." }.forEach { remove(it, scope) }
            }
            check(file.delete()) { "Impossibile cancellare ${file.name}." }
        }
        remove(target, target.canonicalFile.path)
    }
}
