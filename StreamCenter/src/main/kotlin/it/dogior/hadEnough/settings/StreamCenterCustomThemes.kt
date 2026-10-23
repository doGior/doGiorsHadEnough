package it.dogior.hadEnough.settings

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal object StreamCenterCustomThemes {
    private const val PREF_CUSTOM_THEMES = "settingsCustomThemes"
    private const val FORMAT = "StreamCenterTheme"
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_NAME_LENGTH = 60
    val colorPattern = Regex("#[0-9a-fA-F]{6}")

    val colorLabels = linkedMapOf(
        "background" to "Sfondo", "group" to "Gruppi", "surface" to "Schede",
        "surfaceSunken" to "Campi e schede disattivate", "text" to "Testo principale",
        "muted" to "Testo secondario", "stroke" to "Bordi", "accent" to "Colore principale",
        "accentTile" to "Sfondo icone", "success" to "Conferme", "danger" to "Avvisi ed errori",
        "thumbOff" to "Interruttore spento", "trackOff" to "Sfondo interruttore spento",
    )

    fun colors(palette: StreamCenterPalette): Map<String, String> = linkedMapOf(
        "background" to palette.background, "group" to palette.group, "surface" to palette.surface,
        "surfaceSunken" to palette.surfaceSunken, "text" to palette.text, "muted" to palette.muted,
        "stroke" to palette.stroke, "accent" to palette.accent, "accentTile" to palette.accentTile,
        "success" to palette.success, "danger" to palette.danger,
        "thumbOff" to palette.thumbOff, "trackOff" to palette.trackOff,
    )

    fun create(name: String, colors: Map<String, String>, key: String = newKey()): StreamCenterPalette {
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= MAX_NAME_LENGTH) {
            "Il nome deve contenere da 1 a $MAX_NAME_LENGTH caratteri"
        }
        fun color(field: String): String {
            val value = colors[field]?.trim().orEmpty()
            require(colorPattern.matches(value)) { "Colore non valido: ${colorLabels[field]}. Usa #RRGGBB" }
            return value.uppercase(Locale.ROOT)
        }
        return StreamCenterPalette(
            key, label, "Tema personalizzato",
            color("background"), color("group"), color("surface"), color("surfaceSunken"),
            color("text"), color("muted"), color("stroke"), color("accent"), color("accentTile"),
            color("success"), color("danger"), color("thumbOff"), color("trackOff"),
        )
    }

    private fun newKey(): String = "custom_${UUID.randomUUID()}"

    private fun encode(palette: StreamCenterPalette): JSONObject = JSONObject()
        .put("name", palette.label)
        .put("colors", JSONObject(colors(palette)))

    private fun decode(json: JSONObject, key: String = newKey()): StreamCenterPalette {
        val colors = json.getJSONObject("colors")
        return create(json.getString("name"), colorLabels.keys.associateWith { colors.getString(it) }, key)
    }

    fun export(palette: StreamCenterPalette): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("theme", encode(palette))
        .toString(2)

    fun importTheme(content: String): StreamCenterPalette {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "File tema troppo grande" }
        val root = try { JSONObject(content.removePrefix("\uFEFF")) } catch (_: Exception) {
            throw IllegalArgumentException("Il file non contiene un tema JSON valido")
        }
        require(root.optString("format") == FORMAT) { "Il file non è un tema StreamCenter" }
        require(root.opt("version") == 1) { "Versione del tema non supportata" }
        return try { decode(root.getJSONObject("theme")) } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("Il tema è incompleto o contiene valori non validi")
        }
    }

    fun load(prefs: SharedPreferences?): List<StreamCenterPalette> {
        val raw = prefs?.getString(PREF_CUSTOM_THEMES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                val key = entry.getString("key")
                require(key.startsWith("custom_"))
                decode(entry, key)
            }.getOrNull()
        }.distinctBy { it.key }
    }

    fun save(prefs: SharedPreferences, palette: StreamCenterPalette) {
        require(palette.key.startsWith("custom_"))
        val validated = create(palette.label, colors(palette), palette.key)
        write(prefs, load(prefs).filterNot { it.key == palette.key } + validated)
    }

    fun delete(prefs: SharedPreferences, key: String) {
        val remaining = load(prefs).filterNot { it.key == key }
        val editor = prefs.edit().putString(PREF_CUSTOM_THEMES, encodeList(remaining))
        if (prefs.getString(StreamCenterThemes.PREF_THEME, null) == key) {
            editor.putString(StreamCenterThemes.PREF_THEME, StreamCenterThemes.ocean.key)
        }
        editor.apply()
    }

    private fun encodeList(palettes: List<StreamCenterPalette>): String = JSONArray().apply {
        palettes.forEach { put(encode(it).put("key", it.key)) }
    }.toString()

    private fun write(prefs: SharedPreferences, palettes: List<StreamCenterPalette>) {
        prefs.edit().putString(PREF_CUSTOM_THEMES, encodeList(palettes)).apply()
    }
}
