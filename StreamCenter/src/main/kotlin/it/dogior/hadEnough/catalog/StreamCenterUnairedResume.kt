package it.dogior.hadEnough.catalog

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.RESULT_RESUME_WATCHING
import java.util.concurrent.ConcurrentHashMap

internal object StreamCenterUnairedResume : SharedPreferences.OnSharedPreferenceChangeListener {
    private val upcoming = ConcurrentHashMap<Int, Map<Int, Long>>()
    @Volatile private var prefs: SharedPreferences? = null

    fun install(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPrefs().also { it.registerOnSharedPreferenceChangeListener(this) }
    }

    fun remember(response: LoadResponse) {
        if (response !is TvSeriesLoadResponse && response !is AnimeLoadResponse) return
        val parentId = response.getId()
        val now = System.currentTimeMillis()
        val dubs = (response as? AnimeLoadResponse)?.episodes?.keys?.toList() ?: listOf(null)
        val future = dubs.flatMap { catchUpEpisodes(response, parentId, it) }
            .mapNotNull { episode -> episode.dateMillis?.takeIf { it > now }?.let { episode.id to it } }
            .toMap()
        if (future.isEmpty()) upcoming.remove(parentId) else upcoming[parentId] = future
        clear(parentId)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key?.contains("/$RESULT_RESUME_WATCHING/") != true) return
        key.substringAfterLast('/').toIntOrNull()?.let(::clear)
    }

    private fun clear(parentId: Int) {
        val future = upcoming[parentId] ?: return
        val resume = DataStoreHelper.getLastWatched(parentId) ?: return
        val airsAt = resume.episodeId?.let(future::get) ?: return
        if (airsAt > System.currentTimeMillis()) DataStoreHelper.removeLastWatched(parentId)
    }
}
