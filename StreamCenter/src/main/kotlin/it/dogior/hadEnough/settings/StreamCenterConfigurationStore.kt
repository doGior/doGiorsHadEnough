package it.dogior.hadEnough.settings

import android.content.Context
import android.content.SharedPreferences
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.util.StreamCenterLogger

internal object StreamCenterConfigurationStore {
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(StreamCenterPlugin.PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(preferences: SharedPreferences): Map<String, Any> =
        portable(preferences.all).toSortedMap()

    fun portable(values: Map<String, *>): Map<String, Any> {
        return values.mapNotNull { (key, value) ->
            val portableValue = when (value) {
                is String, is Boolean, is Int, is Long, is Float -> value
                is Set<*> -> {
                    require(value.all { item -> item is String }) {
                        "La preferenza $key contiene un tipo non supportato."
                    }
                    value.filterIsInstance<String>().toSet()
                }
                null -> return@mapNotNull null
                else -> throw IllegalArgumentException("Tipo non supportato per la preferenza $key.")
            }
            val excluded = StreamCenterPlugin.isObsoleteTorrentPreference(key) ||
                StreamCenterPlugin.isDefaultTorrentPreference(key, portableValue) ||
                StreamCenterPlugin.isDefaultHomePreference(key, portableValue) ||
                StreamCenterPlugin.isDefaultVpnRequirementPreference(key, portableValue) ||
                StreamCenterLogger.isDefaultRetentionPreference(key, portableValue)
            if (excluded) null else key to portableValue
        }.toMap()
    }

    fun replace(preferences: SharedPreferences, values: Map<String, *>) {
        val expected = portable(values).toSortedMap()
        val previous = snapshot(preferences)
        runCatching {
            write(preferences, expected)
            check(snapshot(preferences) == expected) {
                "Ripristino della configurazione StreamCenter incompleto."
            }
        }.getOrElse { error ->
            runCatching { write(preferences, previous) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun write(preferences: SharedPreferences, values: Map<String, Any>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> throw IllegalArgumentException("Tipo non supportato per la preferenza $key.")
            }
        }
        check(editor.commit()) { "Impossibile salvare la configurazione StreamCenter." }
    }
}
