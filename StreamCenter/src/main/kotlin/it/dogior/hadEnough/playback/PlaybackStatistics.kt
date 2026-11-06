package it.dogior.hadEnough.playback

internal fun medianLong(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}
