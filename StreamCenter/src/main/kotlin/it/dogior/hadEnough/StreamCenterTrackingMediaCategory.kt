package it.dogior.hadEnough

enum class StreamCenterTrackingMediaCategory(val key: String, val title: String) {
    ALL("all", "Tutti"),
    TV("tv", "Serie TV"),
    MOVIES("movies", "Film"),
    ANIME("anime", "Anime");

    fun supportsStatus(statusKey: String): Boolean =
        this != MOVIES || statusKey in setOf("plan_to_watch", "completed", "dropped")

    fun statusTitle(statusKey: String, defaultTitle: String): String =
        if (this == MOVIES && statusKey == "completed") "Visti" else defaultTitle

    companion object {
        fun fromKey(key: String?): StreamCenterTrackingMediaCategory =
            entries.firstOrNull { it.key == key } ?: ALL
    }
}
