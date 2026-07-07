package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamingCommunityPlugin : Plugin() {
    companion object {
        const val PREF_LANG = "lang"
        const val PREF_LANG_POSITION = "langPosition"
        const val PREF_BASE_URL = "baseUrl"
        const val PREF_SHOW_UPCOMING = "showUpcoming"
    }

    private val sharedPref =
        activity?.getSharedPreferences("StreamingCommunity", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        val lang = sharedPref?.getString(PREF_LANG, "it") ?: "it"
        val baseUrl = sharedPref?.getString(PREF_BASE_URL, null)
        val showUpcoming = sharedPref?.getBoolean(PREF_SHOW_UPCOMING, true) ?: true

        registerMainAPI(
            StreamingCommunity(
                lang,
                customBaseUrl = baseUrl,
                showUpcoming = showUpcoming
            )
        )
        registerExtractorAPI(VixCloudExtractor())
        registerExtractorAPI(VixSrcExtractor())


        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
