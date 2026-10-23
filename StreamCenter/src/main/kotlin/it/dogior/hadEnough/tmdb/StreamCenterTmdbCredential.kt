package it.dogior.hadEnough.tmdb

import android.content.SharedPreferences
import androidx.core.content.edit
import it.dogior.hadEnough.cache.StreamCenterMediaCache

internal enum class TmdbCredentialKind(val label: String) { V3("v3"), V4("v4") }

internal data class TmdbCredential(val value: String, val kind: TmdbCredentialKind)

internal object StreamCenterTmdbCredential {
    const val PREF_KEY = "tmdbApiCredential"
    const val PREF_INVALID = "tmdbApiCredentialInvalid"

    private val V3_FORMAT = Regex("^[0-9a-fA-F]{32}$")
    private val V4_FORMAT = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    fun parse(raw: String?): TmdbCredential? {
        val value = raw?.trim()?.removePrefix("Bearer ")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return when {
            V3_FORMAT.matches(value) -> TmdbCredential(value.lowercase(), TmdbCredentialKind.V3)
            V4_FORMAT.matches(value) -> TmdbCredential(value, TmdbCredentialKind.V4)
            else -> null
        }
    }

    fun read(sharedPref: SharedPreferences?): TmdbCredential? = parse(sharedPref?.getString(PREF_KEY, null))

    fun isInvalid(sharedPref: SharedPreferences?): Boolean = sharedPref?.getBoolean(PREF_INVALID, false) ?: false

    fun isActive(sharedPref: SharedPreferences?): Boolean = read(sharedPref) != null && !isInvalid(sharedPref)

    fun isDeviceOnly(key: String): Boolean = key == PREF_KEY || key == PREF_INVALID

    fun save(sharedPref: SharedPreferences?, credential: TmdbCredential) {
        sharedPref?.edit {
            putString(PREF_KEY, credential.value)
            remove(PREF_INVALID)
        }
    }

    fun clear(sharedPref: SharedPreferences?) {
        sharedPref?.edit {
            remove(PREF_KEY)
            remove(PREF_INVALID)
        }
        StreamCenterMediaCache.purgeTmdbApiEntries()
    }

    fun markInvalid(sharedPref: SharedPreferences?): Boolean {
        if (sharedPref == null || isInvalid(sharedPref)) return false
        sharedPref.edit { putBoolean(PREF_INVALID, true) }
        StreamCenterMediaCache.purgeTmdbApiEntries()
        return true
    }
}
