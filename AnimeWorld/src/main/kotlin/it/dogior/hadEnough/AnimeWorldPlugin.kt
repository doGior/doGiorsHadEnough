package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity

@CloudstreamPlugin
class AnimeWorldPlugin : Plugin() {
    val sharedPref = activity?.getSharedPreferences("AnimeWorldIT", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        val isSplit = sharedPref?.getBoolean("isSplit", false) ?: false
        val dubEnabled = sharedPref?.getBoolean("dubEnabled", false) ?: false
        val subEnabled = sharedPref?.getBoolean("subEnabled", false) ?: false
        // All providers should be added in this manner. Please don't edit the providers list directly.
        if (isSplit) {
            if (dubEnabled) {
                registerMainAPI(AnimeWorldDub(isSplit))
            }
            if (subEnabled) {
                registerMainAPI(AnimeWorldSub(isSplit))
            }
        } else {
            registerMainAPI(AnimeWorldCore(isSplit))
        }

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}