package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NebulaPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(Nebula())
    }
}