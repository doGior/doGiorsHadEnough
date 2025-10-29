package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity

@CloudstreamPlugin
class TVPlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("TV", Context.MODE_PRIVATE)
    private val playlistsToLang = mapOf(
        "playlist_albania.m3u8" to "al",
        "playlist_andorra.m3u8" to "ad",
        "playlist_argentina.m3u8" to "ar",
        "playlist_armenia.m3u8" to "am",
        "playlist_australia.m3u8" to "au",
        "playlist_austria.m3u8" to "at",
        "playlist_azerbaijan.m3u8" to "az",
        "playlist_belarus.m3u8" to "by",
        "playlist_belgium.m3u8" to "be",
        "playlist_bosnia_and_herzegovina.m3u8" to "ba",
        "playlist_brazil.m3u8" to "br",
        "playlist_bulgaria.m3u8" to "bg",
        "playlist_canada.m3u8" to "ca",
        "playlist_chad.m3u8" to "td",
        "playlist_chile.m3u8" to "cl",
        "playlist_china.m3u8" to "cn",
        "playlist_costa_rica.m3u8" to "cr",
        "playlist_croatia.m3u8" to "he",
        "playlist_cyprus.m3u8" to "cy",
        "playlist_czech_republic.m3u8" to "cz",
        "playlist_denmark.m3u8" to "dk",
        "playlist_dominican_republic.m3u8" to "do",
        "playlist_estonia.m3u8" to "ee",
        "playlist_faroe_islands.m3u8" to "fo",
        "playlist_finland.m3u8" to "fi",
        "playlist_france.m3u8" to "fr",
        "playlist_georgia.m3u8" to "ge",
        "playlist_germany.m3u8" to "de",
        "playlist_greece.m3u8" to "gr",
        "playlist_greenland.m3u8" to "gl",
        "playlist_hong_kong.m3u8" to "hk",
        "playlist_hongkong.m3u8" to "hk",
        "playlist_hungary.m3u8" to "hu",
        "playlist_iceland.m3u8" to "is",
        "playlist_india.m3u8" to "in",
        "playlist_iran.m3u8" to "ir",
        "playlist_iraq.m3u8" to "iq",
        "playlist_ireland.m3u8" to "ie",
        "playlist_israel.m3u8" to "il",
        "playlist_italy.m3u8" to "it",
        "playlist_japan.m3u8" to "jp",
        "playlist_korea.m3u8" to "kr",
        "playlist_kosovo.m3u8" to "xk",
        "playlist_latvia.m3u8" to "lv",
        "playlist_lithuania.m3u8" to "lt",
        "playlist_luxembourg.m3u8" to "lu",
        "playlist_macau.m3u8" to "mo",
        "playlist_malta.m3u8" to "mt",
        "playlist_mexico.m3u8" to "mx",
        "playlist_moldova.m3u8" to "md",
        "playlist_monaco.m3u8" to "mc",
        "playlist_montenegro.m3u8" to "me",
        "playlist_netherlands.m3u8" to "nl",
        "playlist_north_korea.m3u8" to "kp",
        "playlist_north_macedonia.m3u8" to "mk",
        "playlist_norway.m3u8" to "no",
        "playlist_paraguay.m3u8" to "py",
        "playlist_peru.m3u8" to "pe",
        "playlist_poland.m3u8" to "pl",
        "playlist_portugal.m3u8" to "pt",
        "playlist_qatar.m3u8" to "qa",
        "playlist_romania.m3u8" to "ro",
        "playlist_russia.m3u8" to "ru",
        "playlist_san_marino.m3u8" to "sm",
        "playlist_saudi_arabia.m3u8" to "sa",
        "playlist_serbia.m3u8" to "rs",
        "playlist_slovakia.m3u8" to "sk",
        "playlist_slovenia.m3u8" to "si",
        "playlist_somalia.m3u8" to "so",
        "playlist_spain.m3u8" to "es",
        "playlist_spain_vod.m3u8" to "es",
        "playlist_sweden.m3u8" to "se",
        "playlist_switzerland.m3u8" to "ch",
        "playlist_taiwan.m3u8" to "tw",
        "playlist_trinidad.m3u8" to "tt",
        "playlist_turkey.m3u8" to "tr",
        "playlist_uk.m3u8" to "en",
        "playlist_ukraine.m3u8" to "ua",
        "playlist_united_arab_emirates.m3u8" to "ar",
        "playlist_usa.m3u8" to "us",
        "playlist_usa_vod.m3u8" to "us",
        "playlist_venezuela.m3u8" to "ve",
        "playlist_zz_documentaries_ar.m3u8" to "ar",
        "playlist_zz_documentaries_en.m3u8" to "en",
        "playlist_zz_movies.m3u8" to "un",
        "playlist_zz_news_ar.m3u8" to "ar",
        "playlist_zz_news_en.m3u8" to "en",
        "playlist_zz_news_es.m3u8" to "es",
        "playlist_zz_vod_it.m3u8" to "it"
    )

    override fun load(context: Context) {
        val playlistSettings = playlistsToLang.keys.associateWith {
            sharedPref?.getBoolean(it, false) ?: false
        }
        val selectedPlaylists = playlistSettings.filter { it.value }.keys
        val selectedLanguages = selectedPlaylists.map { playlistsToLang[it] }
        val lang = if(selectedLanguages.isNotEmpty()){
            if(selectedLanguages.all { it == selectedLanguages.first() && it != null }){
                selectedLanguages.first()!! } else{ "un" }
        } else{ "un" }

        registerMainAPI(TV(selectedPlaylists.toList(), lang, sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = Settings(this, sharedPref, playlistsToLang.keys.toList())
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}