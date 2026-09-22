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

internal const val COLOR_BACKGROUND = "#100C0B"
internal const val COLOR_CARD = "#1C1613"
internal const val COLOR_CARD_ALT = "#241B15"
internal const val COLOR_CARD_DISABLED = "#150F0C"
internal const val COLOR_TEXT = "#F3EADF"
internal const val COLOR_MUTED = "#AB9B89"
internal const val COLOR_STROKE = "#342820"
internal const val COLOR_ACCENT = "#F2C066"
internal const val COLOR_SUCCESS = "#A9CE8C"
internal const val COLOR_DANGER = "#E56B4E"
internal const val COLOR_THUMB_OFF = "#948270"
internal const val COLOR_TRACK_OFF = "#3B2F27"
internal const val COLOR_INPUT_FILL = "#140F0C"
internal const val COLOR_VPN_ON = "#93AE84"
internal const val COLOR_VPN_OFF = "#C08A80"

internal val COLOR_PERFORMANCE = hsl(48f, 0.90f, 0.62f)
internal val COLOR_DISPLAY = hsl(20f, 0.72f, 0.64f)
internal val COLOR_HOME = hsl(44f, 0.80f, 0.64f)
internal val COLOR_SOURCES = hsl(34f, 0.86f, 0.60f)
internal val COLOR_SUPPORT = hsl(351f, 0.52f, 0.70f)

internal val COLOR_VISUAL_EFFECTS = hsl(40f, 0.80f, 0.64f)
internal val COLOR_VISUAL_BLUR = hsl(30f, 0.54f, 0.66f)
internal val COLOR_VISUAL_HEADER = hsl(46f, 0.62f, 0.62f)
internal val COLOR_PARTICLES = hsl(44f, 0.78f, 0.66f)
internal val COLOR_PUBLIC_IP = hsl(42f, 0.72f, 0.74f)
internal val COLOR_VPN_GUARD = hsl(22f, 0.60f, 0.60f)
internal val COLOR_SCORE = hsl(46f, 0.70f, 0.64f)
internal val COLOR_ANIME_VARIANTS = hsl(16f, 0.62f, 0.64f)
internal val COLOR_EPISODES = hsl(36f, 0.74f, 0.58f)
internal val COLOR_TRACKING_IDS = hsl(50f, 0.55f, 0.72f)

internal val COLOR_HOME_ANIME = hsl(12f, 0.64f, 0.66f)
internal val COLOR_HOME_TV = hsl(40f, 0.80f, 0.62f)
internal val COLOR_HOME_MOVIE = hsl(28f, 0.72f, 0.60f)
internal val COLOR_HOME_TRACKING = hsl(50f, 0.66f, 0.68f)
internal val COLOR_HOME_CHANNELS = hsl(46f, 0.82f, 0.60f)
internal val COLOR_CATALOGS = hsl(24f, 0.66f, 0.62f)

internal val COLOR_SOURCE_UPDATE = hsl(50f, 0.85f, 0.58f)
internal val COLOR_SOURCE_ANIME = hsl(20f, 0.82f, 0.64f)
internal val COLOR_SOURCE_TV = hsl(44f, 0.78f, 0.60f)
internal val COLOR_TORRENT = hsl(14f, 0.82f, 0.62f)
internal val COLOR_API_CHECK = hsl(46f, 0.66f, 0.70f)

internal val COLOR_CLOUDSTREAM_SERVICES = hsl(352f, 0.60f, 0.66f)
internal val COLOR_FEEDBACK = hsl(356f, 0.66f, 0.72f)
internal val COLOR_RESET = hsl(8f, 0.78f, 0.62f)
internal val COLOR_BACKUP = hsl(36f, 0.55f, 0.64f)
internal val COLOR_LOG = hsl(344f, 0.60f, 0.62f)
internal val COLOR_LOCAL_SYNC = hsl(28f, 0.58f, 0.66f)
internal val COLOR_CACHE = hsl(48f, 0.60f, 0.62f)

internal const val COLOR_STREMIO = "#D946EF"
internal const val COLOR_TELEGRAM = "#229ED9"

internal const val STREAMING_COMMUNITY_UPDATED_LINK_PAGE =
    "https://telegra.ph/Link-Aggiornato-StreamingCommunity-09-29"
internal const val TELEGRAM_ICON_URL = "https://telegram.org/img/t_logo.png"
internal const val STREMIO_WEBSITE_URL = "https://www.stremio.com"

internal fun tint(color: String, alphaHex: String): String =
    "#$alphaHex${color.removePrefix("#")}"
