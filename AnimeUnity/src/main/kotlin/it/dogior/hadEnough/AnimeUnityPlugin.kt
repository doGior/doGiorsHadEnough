package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeUnityPlugin : Plugin() {
    companion object {
        const val PREFS_NAME = "AnimeUnity"
        const val PREF_SHOW_LATEST_EPISODES = "showLatestEpisodes"
        const val PREF_SHOW_CALENDAR = "showCalendar"
        const val PREF_SHOW_ONGOING = "showOngoing"
        const val PREF_SHOW_POPULAR = "showPopular"
        const val PREF_SHOW_BEST = "showBest"
        const val PREF_SHOW_UPCOMING = "showUpcoming"
        const val PREF_SHOW_SCORE = "showScore"
    }

    private var sharedPref: SharedPreferences? = null

    override fun load(context: Context) {
        sharedPref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        registerMainAPI(AnimeUnity(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            AnimeUnitySettings(this, sharedPref).show(activity.supportFragmentManager, "AnimeUnitySettings")
        }
    }
}
