package it.dogior.hadEnough

internal object AnimeUnitySections {
    const val ADVANCED = "advanced"
    const val LATEST = "latest"
    const val CALENDAR = "calendar"
    const val ONGOING = "ongoing"
    const val POPULAR = "popular"
    const val BEST = "best"
    const val UPCOMING = "upcoming"
    const val RANDOM = "random"

    const val DEFAULT_ORDER = "latest,calendar,random,ongoing,popular,best,upcoming"

    val defaultOrderKeys = listOf(
        LATEST,
        CALENDAR,
        RANDOM,
        ONGOING,
        POPULAR,
        BEST,
        UPCOMING,
    )

    val validKeys = defaultOrderKeys
}
