package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamingCommunityPlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("StreamingCommunity", Context.MODE_PRIVATE)
    override fun load(context: Context) {
        val lang = sharedPref?.getString("lang", "it") ?: "it"
        registerMainAPI(StreamingCommunity(lang))
        registerExtractorAPI(VixCloudExtractor())
        registerExtractorAPI(VixSrcExtractor())


        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
