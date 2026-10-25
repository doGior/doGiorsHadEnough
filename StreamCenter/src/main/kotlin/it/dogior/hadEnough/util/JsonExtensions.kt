package it.dogior.hadEnough.util

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }?.takeIf { it > 0 }
}

internal fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotBlank() && it != "null" }
}

internal fun JSONObject.optIntValue(name: String): Int? =
    if (!has(name) || isNull(name)) null else (opt(name) as? Number)?.toInt()

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
