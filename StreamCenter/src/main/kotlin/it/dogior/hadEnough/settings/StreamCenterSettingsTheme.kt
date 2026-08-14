package it.dogior.hadEnough.settings

import kotlin.math.abs
import kotlin.math.roundToInt

private fun hsl(hue: Float, saturation: Float, lightness: Float): String {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val r: Float
    val g: Float
    val b: Float
    when {
        h < 60f -> { r = c; g = x; b = 0f }
        h < 120f -> { r = x; g = c; b = 0f }
        h < 180f -> { r = 0f; g = c; b = x }
        h < 240f -> { r = 0f; g = x; b = c }
        h < 300f -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    val ri = ((r + m) * 255f).roundToInt().coerceIn(0, 255)
    val gi = ((g + m) * 255f).roundToInt().coerceIn(0, 255)
    val bi = ((b + m) * 255f).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(ri, gi, bi)
}

internal const val COLOR_BACKGROUND = "#11141A"
internal const val COLOR_CARD = "#1A2029"
internal const val COLOR_CARD_ALT = "#202733"
internal const val COLOR_CARD_DISABLED = "#151922"
internal const val COLOR_TEXT = "#F4F7FB"
internal const val COLOR_MUTED = "#96A2B5"
internal const val COLOR_STROKE = "#2E3746"
internal const val COLOR_ACCENT = "#4CC9F0"
internal const val COLOR_SUCCESS = "#7CFF9D"
internal const val COLOR_DANGER = "#FF7F7F"
internal const val COLOR_THUMB_OFF = "#8A94A6"
internal const val COLOR_TRACK_OFF = "#39424F"
internal const val COLOR_INPUT_FILL = "#10151D"
internal const val COLOR_VPN_ON = "#86A895"
internal const val COLOR_VPN_OFF = "#B78A90"

internal val COLOR_PERFORMANCE = hsl(88f, 0.68f, 0.56f)
internal val COLOR_DISPLAY = hsl(174f, 0.68f, 0.50f)
internal val COLOR_HOME = hsl(214f, 0.82f, 0.64f)
internal val COLOR_SOURCES = hsl(40f, 0.92f, 0.56f)
internal val COLOR_SUPPORT = hsl(350f, 0.82f, 0.68f)

internal val COLOR_VISUAL_EFFECTS = hsl(190f, 0.72f, 0.60f)
internal val COLOR_VISUAL_BLUR = hsl(163f, 0.52f, 0.66f)
internal val COLOR_VISUAL_HEADER = hsl(158f, 0.58f, 0.52f)
internal val COLOR_PARTICLES = hsl(197f, 0.78f, 0.62f)
internal val COLOR_PUBLIC_IP = hsl(176f, 0.60f, 0.72f)
internal val COLOR_VPN_GUARD = hsl(185f, 0.55f, 0.44f)
internal val COLOR_SCORE = hsl(150f, 0.55f, 0.62f)
internal val COLOR_ANIME_VARIANTS = hsl(202f, 0.55f, 0.52f)
internal val COLOR_EPISODES = hsl(168f, 0.72f, 0.40f)
internal val COLOR_TRACKING_IDS = hsl(155f, 0.46f, 0.72f)

internal val COLOR_HOME_ANIME = hsl(248f, 0.58f, 0.72f)
internal val COLOR_HOME_TV = hsl(200f, 0.78f, 0.56f)
internal val COLOR_HOME_MOVIE = hsl(224f, 0.68f, 0.50f)
internal val COLOR_HOME_TRACKING = hsl(238f, 0.62f, 0.68f)
internal val COLOR_HOME_CHANNELS = hsl(190f, 0.82f, 0.60f)
internal val COLOR_CATALOGS = hsl(230f, 0.66f, 0.60f)

internal val COLOR_SOURCE_UPDATE = hsl(50f, 0.85f, 0.52f)
internal val COLOR_SOURCE_ANIME = hsl(28f, 0.82f, 0.62f)
internal val COLOR_SOURCE_TV = hsl(54f, 0.72f, 0.58f)
internal val COLOR_TORRENT = hsl(18f, 0.85f, 0.60f)
internal val COLOR_API_CHECK = hsl(44f, 0.68f, 0.68f)

internal val COLOR_CLOUDSTREAM_SERVICES = hsl(338f, 0.66f, 0.62f)
internal val COLOR_FEEDBACK = hsl(356f, 0.72f, 0.72f)
internal val COLOR_RESET = hsl(4f, 0.80f, 0.62f)
internal val COLOR_BACKUP = hsl(332f, 0.52f, 0.60f)
internal val COLOR_LOG = hsl(346f, 0.82f, 0.54f)
internal val COLOR_LOCAL_SYNC = hsl(324f, 0.58f, 0.66f)

internal const val COLOR_STREMIO = "#D946EF"
internal const val COLOR_TELEGRAM = "#229ED9"

internal const val STREAMING_COMMUNITY_UPDATED_LINK_PAGE =
    "https://telegra.ph/Link-Aggiornato-StreamingCommunity-09-29"
internal const val TELEGRAM_ICON_URL = "https://telegram.org/img/t_logo.png"
internal const val STREMIO_WEBSITE_URL = "https://www.stremio.com"

internal fun tint(color: String, alphaHex: String): String =
    "#$alphaHex${color.removePrefix("#")}"
