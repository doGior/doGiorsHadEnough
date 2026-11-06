package it.dogior.hadEnough.catalog

import com.lagradost.cloudstream3.Score
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.cache.AtomicTextFile
import it.dogior.hadEnough.util.runCatchingCancellable
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

internal object StreamCenterTmdbScores {
    private class Entry(val percent: Int, val savedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val queued: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetches = Semaphore(PARALLEL_FETCHES)
    private val loaded = AtomicBoolean(false)
    private val saveScheduled = AtomicBoolean(false)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val updates: SharedFlow<Unit> = changes

    fun cached(url: String): Score? {
        val key = keyFor(url) ?: return null
        ensureLoaded()
        val entry = entries[key]?.takeIf { System.currentTimeMillis() - it.savedAt < TTL_MS } ?: return null
        return Score.from(entry.percent, 100)
    }

    fun remember(url: String, score: Score) {
        val key = keyFor(url) ?: return
        ensureLoaded()
        entries[key] = Entry(score.toInt(100), System.currentTimeMillis())
        scheduleSave()
    }

    fun fetchLater(url: String, fetch: suspend () -> Score?) {
        val key = keyFor(url) ?: return
        if (!queued.add(key)) return
        scope.launch {
            try {
                val score = fetches.withPermit { runCatchingCancellable { fetch() }.getOrNull() } ?: return@launch
                remember(url, score)
                changes.tryEmit(Unit)
            } finally {
                queued.remove(key)
            }
        }
    }

    fun clear() {
        entries.clear()
        file(enabledOnly = false)?.let { file ->
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
            file.delete()
        }
    }

    private fun keyFor(url: String): String? = MEDIA_KEY_REGEX.find(url)
        ?.let { "${it.groupValues[1].lowercase(Locale.ROOT)}:${it.groupValues[2]}" }

    private fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        val file = file()?.takeIf(File::exists) ?: return
        val now = System.currentTimeMillis()
        runCatching {
            val json = JSONObject(AtomicTextFile.read(file))
            json.keys().forEach { key ->
                val value = json.optJSONArray(key) ?: return@forEach
                val entry = Entry(value.optInt(0), value.optLong(1))
                if (now - entry.savedAt < TTL_MS) entries.putIfAbsent(key, entry)
            }
        }
    }

    private fun scheduleSave() {
        if (!saveScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(SAVE_DELAY_MS)
            saveScheduled.set(false)
            val file = file() ?: return@launch
            val now = System.currentTimeMillis()
            val json = JSONObject()
            entries.forEach { (key, entry) ->
                if (now - entry.savedAt < TTL_MS) json.put(key, JSONArray().put(entry.percent).put(entry.savedAt))
            }
            runCatching { AtomicTextFile.write(file, json.toString()) }
        }
    }

    private fun file(enabledOnly: Boolean = true): File? {
        if (enabledOnly && !StreamCenterPlugin.isTmdbScoreCacheEnabled(StreamCenterPlugin.activeSharedPref)) return null
        return StreamCenterPlugin.activeContext?.cacheDir?.let { File(it, FILE_NAME) }
    }

    private const val FILE_NAME = "streamcenter_tmdb_scores.json"
    private const val TTL_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val PARALLEL_FETCHES = 2
    private const val SAVE_DELAY_MS = 2_000L
    private val MEDIA_KEY_REGEX = Regex("themoviedb\\.org/(movie|tv)/(\\d+)", RegexOption.IGNORE_CASE)
}
