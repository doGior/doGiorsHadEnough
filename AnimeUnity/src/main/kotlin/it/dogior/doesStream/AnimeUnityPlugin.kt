package it.dogior.doesStream

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeUnityPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeUnity())
    }
}
