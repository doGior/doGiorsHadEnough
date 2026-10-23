package it.dogior.hadEnough.subtitles

internal class AssDialogue(val durationMs: Long, val offset: Int) {
    companion object {
        private const val COMMA = ','.code.toByte()

        fun parse(sample: ByteArray): AssDialogue? {
            val first = sample.indexOf(COMMA)
            if (first < 0) return null
            var second = -1
            for (index in first + 1 until sample.size) {
                if (sample[index] == COMMA) {
                    second = index
                    break
                }
            }
            if (second < 0 || second + 1 >= sample.size) return null
            val duration = parseTimecode(String(sample, first + 1, second - first - 1, Charsets.US_ASCII)) ?: return null
            return AssDialogue(duration, second + 1)
        }

        private fun parseTimecode(value: String): Long? {
            val parts = value.trim().split(':', '.')
            if (parts.size != 4) return null
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toLongOrNull() ?: return null
            val centiseconds = parts[3].toLongOrNull() ?: return null
            return ((hours * 60 + minutes) * 60 + seconds) * 1000 + centiseconds * 10
        }
    }
}
