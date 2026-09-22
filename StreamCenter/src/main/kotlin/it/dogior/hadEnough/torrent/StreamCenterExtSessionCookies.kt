package it.dogior.hadEnough.torrent

internal object StreamCenterExtSessionCookies {
    private const val CLEARANCE_COOKIE = "cf_clearance"

    fun parse(value: String?): Map<String, String> = value
        ?.split(';')
        ?.mapNotNull { entry ->
            val name = entry.substringBefore('=', "").trim()
            val content = entry.substringAfter('=', "").trim()
            name.takeIf(String::isNotBlank)?.let { it to content }
        }
        ?.toMap()
        .orEmpty()

    fun clearance(value: String?): String? = parse(value).entries
        .firstOrNull { (name, _) -> isClearance(name) }
        ?.value
        ?.takeIf(String::isNotBlank)

    fun merge(browserCookies: String?, responseCookies: Map<String, String>): Map<String, String> =
        parse(browserCookies).filterValues(String::isNotBlank).toMutableMap().apply {
            responseCookies.forEach { (name, value) ->
                if (!isCloudflareCookie(name)) {
                    if (value.isBlank()) remove(name) else put(name, value)
                }
            }
        }

    private fun isClearance(name: String): Boolean = name.equals(CLEARANCE_COOKIE, ignoreCase = true)

    fun isCloudflareCookie(name: String): Boolean = isClearance(name) ||
        name.startsWith("__cf", ignoreCase = true) || name.startsWith("cf_chl_", ignoreCase = true)
}
