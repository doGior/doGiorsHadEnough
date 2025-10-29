package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class ArtePlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("Arte", Context.MODE_PRIVATE)

        val language = sharedPref.getString("language", "Italian \uD83C\uDDEE\uD83C\uDDF9") ?: "Italian \uD83C\uDDEE\uD83C\uDDF9"
        val languageToCode = mapOf(
            "\uD83C\uDDEE\uD83C\uDDF9 Italian" to "it",
            "\uD83C\uDDEB\uD83C\uDDF7 Français" to "fr",
            "\uD83C\uDDE9\uD83C\uDDEA Deutsch" to "de",
            "\uD83C\uDDEC\uD83C\uDDE7 English" to "en",
            "\uD83C\uDDEA\uD83C\uDDF8 Español" to "es",
            "\uD83C\uDDF5\uD83C\uDDF1 Polski" to "pl",
            "\uD83C\uDDF7\uD83C\uDDF4 Română" to "ro",
        )
        // All providers should be added in this manner
        registerMainAPI(Arte(languageToCode[language] ?: "it"))

        // Enable settings
        openSettings = {ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}