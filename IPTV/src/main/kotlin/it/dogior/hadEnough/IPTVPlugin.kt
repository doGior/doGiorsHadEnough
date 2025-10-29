package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey

@CloudstreamPlugin
class IPTVPlugin : Plugin() {
    override fun load(context: Context) {
        reload()
    }

    init {
        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            try {
                val frag = IPTVSettingsFragment(this)
                frag.show(activity.supportFragmentManager, "IPTV")
            } catch (e: Exception) {
            }
        }
    }

    fun reload() {
        try {
            val savedLinks = getKey<Array<Link>>("iptv_links") ?: emptyArray()
            savedLinks.forEach { link ->
                val pluginData = PluginManager.getPluginsOnline().find { it.internalName.contains(link.name) }
                if (pluginData != null) {
                    PluginManager.unloadPlugin(pluginData.filePath)
                } else {
                    registerMainAPI(IPTVProvider(link.link, link.name))
                }
            }
            MainActivity.afterPluginsLoadedEvent.invoke(true)
        } catch (e: Exception) {
        }
    }
}