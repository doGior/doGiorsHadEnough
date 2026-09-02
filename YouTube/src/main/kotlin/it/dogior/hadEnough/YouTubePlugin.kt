package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import it.dogior.hadEnough.settings.SettingsFragment
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

@CloudstreamPlugin
class YouTubePlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("Youtube", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        var language = sharedPref?.getString("language", "it")
        var country = sharedPref?.getString("country", "IT")

        if (language.isNullOrEmpty()) {language = "it"}
        if (country.isNullOrEmpty()) {country = "IT"}

        // All providers should be added in this manner
        registerMainAPI(YouTubeProvider(sharedPrefs = sharedPref))
        registerMainAPI(YouTubePlaylistsProvider(sharedPrefs = sharedPref))
        registerMainAPI(YouTubeChannelProvider(sharedPrefs = sharedPref))

        openSettings = {context ->
            val activity = context as AppCompatActivity
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
