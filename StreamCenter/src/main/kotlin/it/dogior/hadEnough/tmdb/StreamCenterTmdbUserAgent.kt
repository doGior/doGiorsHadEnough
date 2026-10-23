package it.dogior.hadEnough.tmdb

import android.content.SharedPreferences
import it.dogior.hadEnough.StreamCenterPlugin

internal object StreamCenterTmdbUserAgent {
    const val PREF_KEY = "tmdbUserAgent"

    fun read(preferences: SharedPreferences? = StreamCenterPlugin.activeSharedPref): String? =
        preferences?.getString(PREF_KEY, null)?.trim()?.takeIf { it.isNotEmpty() && isValid(it) }

    fun isValid(value: String): Boolean = value.all { it.code in 32..126 }

    fun headers(
        defaults: Map<String, String>,
        preferences: SharedPreferences? = StreamCenterPlugin.activeSharedPref,
    ): Map<String, String> {
        val custom = read(preferences) ?: return defaults
        return defaults.filterKeys { !it.equals("User-Agent", ignoreCase = true) } + ("User-Agent" to custom)
    }

    fun webHeaders(
        defaults: Map<String, String>,
        preferences: SharedPreferences? = StreamCenterPlugin.activeSharedPref,
    ): Map<String, String> = StreamCenterTmdbChallenge.withPass(headers(defaults, preferences))
}
