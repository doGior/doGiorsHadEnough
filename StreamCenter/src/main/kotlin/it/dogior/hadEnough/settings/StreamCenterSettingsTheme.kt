package it.dogior.hadEnough.settings

import android.content.SharedPreferences
import kotlin.random.Random

internal data class StreamCenterPalette(
    val key: String,
    val label: String,
    val description: String,
    val background: String,
    val group: String,
    val surface: String,
    val surfaceSunken: String,
    val text: String,
    val muted: String,
    val stroke: String,
    val accent: String,
    val accentTile: String,
    val success: String,
    val danger: String,
    val thumbOff: String,
    val trackOff: String,
)

internal object StreamCenterThemes {
    const val PREF_THEME = "settingsTheme"
    const val ROTATION_KEY = "rotation"

    val ocean = StreamCenterPalette(
        key = "ocean",
        label = "Acqua notturna",
        description = "Petrolio profondo e acquamarina",
        background = "#0D1517",
        group = "#111D20",
        surface = "#172729",
        surfaceSunken = "#0F1A1C",
        text = "#E6F0EF",
        muted = "#88A3A1",
        stroke = "#22373A",
        accent = "#5FD4C0",
        accentTile = "#1B3A39",
        success = "#7FD6A0",
        danger = "#F07C6C",
        thumbOff = "#6E8584",
        trackOff = "#263A3C",
    )

    val guide = StreamCenterPalette(
        key = "guide",
        label = "Guida TV",
        description = "Grafite blu e blu segnale",
        background = "#11141A",
        group = "#151920",
        surface = "#1C212B",
        surfaceSunken = "#12151B",
        text = "#ECEFF4",
        muted = "#8D96A8",
        stroke = "#2A303C",
        accent = "#7FA6FF",
        accentTile = "#243050",
        success = "#6FD39B",
        danger = "#FF7A6B",
        thumbOff = "#737B8C",
        trackOff = "#2C3240",
    )

    val remote = StreamCenterPalette(
        key = "remote",
        label = "Telecomando",
        description = "Antracite e rosso play",
        background = "#15161A",
        group = "#1A1B20",
        surface = "#212227",
        surfaceSunken = "#131418",
        text = "#F1F0EE",
        muted = "#9A9AA2",
        stroke = "#2F3036",
        accent = "#FF6B5E",
        accentTile = "#3A2326",
        success = "#7CD39A",
        danger = "#F5A524",
        thumbOff = "#7A7A82",
        trackOff = "#34353C",
    )

    val phosphor = StreamCenterPalette(
        key = "phosphor",
        label = "Fosfori",
        description = "Bruno caldo e ambra dei vecchi schermi",
        background = "#16120C",
        group = "#1B1610",
        surface = "#241D15",
        surfaceSunken = "#130F0A",
        text = "#F3EBDD",
        muted = "#A89A84",
        stroke = "#33291D",
        accent = "#F2B45A",
        accentTile = "#3D2E18",
        success = "#8FD18B",
        danger = "#F07868",
        thumbOff = "#857A69",
        trackOff = "#382E22",
    )

    val primetime = StreamCenterPalette(
        key = "primetime",
        label = "Prima serata",
        description = "Indaco notturno e lilla",
        background = "#13121B",
        group = "#181622",
        surface = "#201E2C",
        surfaceSunken = "#110F18",
        text = "#EEEBF6",
        muted = "#9C96B0",
        stroke = "#2C2939",
        accent = "#B49BFF",
        accentTile = "#2F2850",
        success = "#79D3A2",
        danger = "#FF7D86",
        thumbOff = "#7D7890",
        trackOff = "#2E2B3D",
    )

    val colorBars = StreamCenterPalette(
        key = "colorbars",
        label = "Barre colore",
        description = "Prugna scura e magenta del monoscopio",
        background = "#17121A",
        group = "#1C1620",
        surface = "#251D29",
        surfaceSunken = "#120E15",
        text = "#F4ECF3",
        muted = "#A893A6",
        stroke = "#352A38",
        accent = "#F27BC3",
        accentTile = "#43223A",
        success = "#7DD3A0",
        danger = "#FFA14F",
        thumbOff = "#8A7889",
        trackOff = "#3A2E3C",
    )

    val forest = ocean.copy(
        key = "forest", label = "Sottobosco", description = "Verde bosco e salvia",
        background = "#101711", group = "#162019", surface = "#1E2C22",
        surfaceSunken = "#0C130E", text = "#E9F3E7", muted = "#9BB39E",
        stroke = "#304735", accent = "#A4D789", accentTile = "#2A4326",
        thumbOff = "#7A927E", trackOff = "#2C4030",
    )

    val sunset = phosphor.copy(
        key = "sunset", label = "Tramonto", description = "Terracotta e arancio dorato",
        background = "#1C1210", group = "#251915", surface = "#33231D",
        surfaceSunken = "#160E0C", text = "#FFF0E5", muted = "#C0A393",
        stroke = "#51372B", accent = "#FFAD78", accentTile = "#543322",
        thumbOff = "#A18778", trackOff = "#463127",
    )

    val glacier = guide.copy(
        key = "glacier", label = "Ghiacciaio", description = "Blu polare e azzurro ghiaccio",
        background = "#0B1520", group = "#111F2D", surface = "#192C3D",
        surfaceSunken = "#08111B", text = "#EAF7FF", muted = "#95B2C6",
        stroke = "#2C465C", accent = "#80D8FF", accentTile = "#1A4056",
        thumbOff = "#7896AB", trackOff = "#263F52",
    )

    val oled = remote.copy(
        key = "oled", label = "Nero OLED", description = "Nero assoluto e bianco luminoso",
        background = "#000000", group = "#080808", surface = "#141414",
        surfaceSunken = "#000000", text = "#FFFFFF", muted = "#ADADAD",
        stroke = "#353535", accent = "#E0E0E0", accentTile = "#303030",
        thumbOff = "#888888", trackOff = "#303030",
    )

    val all: List<StreamCenterPalette> = listOf(
        ocean, guide, remote, phosphor, primetime, colorBars, forest, sunset, glacier, oled,
    )

    fun available(prefs: SharedPreferences?): List<StreamCenterPalette> =
        all + StreamCenterCustomThemes.load(prefs)

    @Volatile
    var active: StreamCenterPalette = ocean
        private set

    fun selectedKey(prefs: SharedPreferences?): String {
        val stored = prefs?.getString(PREF_THEME, null)
        return stored?.takeIf { key -> key == ROTATION_KEY || available(prefs).any { it.key == key } } ?: ROTATION_KEY
    }

    fun selectedLabel(prefs: SharedPreferences?): String {
        val key = selectedKey(prefs)
        if (key == ROTATION_KEY) return "Rotazione"
        return available(prefs).first { it.key == key }.label
    }

    fun applyFor(prefs: SharedPreferences?) {
        val key = selectedKey(prefs)
        val palettes = available(prefs)
        active = if (key == ROTATION_KEY) {
            palettes.filter { it.key != active.key }.let { it[Random.nextInt(it.size)] }
        } else {
            palettes.first { it.key == key }
        }
    }
}

internal val COLOR_BACKGROUND get() = StreamCenterThemes.active.background
internal val COLOR_GROUP get() = StreamCenterThemes.active.group
internal val COLOR_CARD get() = StreamCenterThemes.active.surface
internal val COLOR_CARD_ALT get() = StreamCenterThemes.active.surface
internal val COLOR_CARD_DISABLED get() = StreamCenterThemes.active.surfaceSunken
internal val COLOR_INPUT_FILL get() = StreamCenterThemes.active.surfaceSunken
internal val COLOR_TEXT get() = StreamCenterThemes.active.text
internal val COLOR_MUTED get() = StreamCenterThemes.active.muted
internal val COLOR_STROKE get() = StreamCenterThemes.active.stroke
internal val COLOR_ACCENT get() = StreamCenterThemes.active.accent
internal val COLOR_ACCENT_TILE get() = StreamCenterThemes.active.accentTile
internal val COLOR_SUCCESS get() = StreamCenterThemes.active.success
internal val COLOR_DANGER get() = StreamCenterThemes.active.danger
internal val COLOR_THUMB_OFF get() = StreamCenterThemes.active.thumbOff
internal val COLOR_TRACK_OFF get() = StreamCenterThemes.active.trackOff

internal const val COLOR_TRANSPARENT = "#00000000"
internal const val COLOR_STREMIO = "#D946EF"
internal const val COLOR_TELEGRAM = "#229ED9"

internal const val STREAMING_COMMUNITY_UPDATED_LINK_PAGE =
    "https://telegra.ph/Link-Aggiornato-StreamingCommunity-09-29"
internal const val TELEGRAM_ICON_URL = "https://telegram.org/img/t_logo.png"
internal const val STREMIO_WEBSITE_URL = "https://www.stremio.com"

internal fun tint(color: String, alphaHex: String): String =
    "#$alphaHex${color.removePrefix("#").takeLast(6)}"
