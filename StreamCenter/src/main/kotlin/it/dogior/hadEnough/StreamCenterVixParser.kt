package it.dogior.hadEnough

import org.json.JSONObject

internal object StreamCenterVixParser {
    private val windowAssignment = Regex("""window\.(\w+)\s*=""")
    private val unquotedKey = Regex("""(\{|\[|,)\s*(\w+)\s*:""")
    private val trailingComma = Regex(""",(\s*[}\]])""")

    fun playlistUrl(script: JSONObject): String {
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val params = masterPlaylist.getJSONObject("params")
        val token = params.getString("token")
        val expires = params.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")
        val query = "token=$token&expires=$expires"
        val baseUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$query"
        } else {
            "$playlistUrl?$query"
        }
        return if (script.getBoolean("canPlayFHD")) "$baseUrl&h=1" else baseUrl
    }

    fun parseScript(script: String): JSONObject {
        val parts = windowAssignment.split(script).drop(1)
        val keys = windowAssignment.findAll(script).map { it.groupValues[1] }.toList()
        val objects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(unquotedKey, "$1 \"$2\":")
                .replace(trailingComma, "$1")
                .trim()
            "\"$key\": $cleaned"
        }
        return JSONObject("{\n${objects.joinToString(",\n")}\n}".replace("'", "\""))
    }
}
