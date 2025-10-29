package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

@CloudstreamPlugin
class HuhuPlugin : Plugin() {
    private var countries = mapOf(
        "Germany" to true,
        "Albania" to true,
        "France" to true,
        "Balkans" to true,
        "Turkey" to true,
        "Portugal" to true,
        "Poland" to true,
        "Italy" to true,
        "United Kingdom" to true,
        "Romania" to true,
        "Arabia" to true,
        "Russia" to true,
        "Spain" to true,
        "Bulgaria" to true,
        "Netherlands" to true,
    )
    private val countriesToLang = mapOf(
        "Germany" to "de",
        "Albania" to "al",
        "France" to "fr",
        "Balkans" to "i don't wanna upset anyone",
        "Turkey" to "tr",
        "Portugal" to "pt",
        "Poland" to "pl",
        "Italy" to "it",
        "United Kingdom" to "uk",
        "Romania" to "ro",
        "Arabia" to "sa",
        "Russia" to "ru",
        "Spain" to "es",
        "Bulgaria" to "bg",
        "Netherlands" to "nl",
    )

    override fun load(context: Context) {
        // Preferences
        val sharedPref = context.getSharedPreferences("Huhu", Context.MODE_PRIVATE)

        val savedContries = let {
            val c = sharedPref.getString("countries", "")
            if (!c.isNullOrEmpty()) {
                parseJson<Map<String, Boolean>>(c)
            } else {
                null
            }
        }
        val domain = sharedPref.getString("domain", "huhu.to") ?: "huhu.to"

        val lang = savedContries?.let {
            val enabledCountries = it.filter { pair -> pair.value }
            if (enabledCountries.size == 1) {
                countriesToLang[enabledCountries.keys.first()]
            } else {
                "un"
            }
        } ?: "un"

        // Register Plugin
        registerMainAPI(Huhu(domain, savedContries ?: countries, lang))

        // Enable settings
        openSettings = {ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref, countries)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}