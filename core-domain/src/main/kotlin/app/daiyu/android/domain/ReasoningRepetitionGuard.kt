package app.daiyu.android.domain

/**
 * Per-request, bounded detector for degenerate repeated text.
 * Callers feed reasoning and visible answers; tool arguments stay out.
 * Chunk boundaries and block IDs are deliberately irrelevant. Nothing retained here
 * is logged. The caller must abort the transport and must not replay a rejected request.
 */
internal class ReasoningRepetitionGuard {
    private val window = StringBuilder(WINDOW_CHARS)
    private var untilCheck = CHECK_STRIDE
    private var rejected = false

    fun append(delta: String): Boolean {
        if (rejected) return true
        var offset = 0
        while (offset < delta.length) {
            val count = minOf(untilCheck, delta.length - offset)
            if (window.length + count > WINDOW_CHARS) {
                window.delete(0, window.length + count - WINDOW_CHARS)
            }
            window.append(delta, offset, offset + count)
            offset += count
            untilCheck -= count
            if (untilCheck == 0) {
                untilCheck = CHECK_STRIDE
                if (window.length >= MIN_CHARS && (periodicTail() || repeatedShortLines())) {
                    rejected = true
                    return true
                }
            }
        }
        return false
    }

    fun reset() {
        window.setLength(0)
        untilCheck = CHECK_STRIDE
        rejected = false
    }

    private fun periodicTail(): Boolean {
        val start = window.length - MIN_CHARS
        // At least sixteen copies, even for the longest period. No quadratic growth in output.
        for (period in 1..MAX_PERIOD) {
            var index = start + period
            while (index < window.length && window[index] == window[index - period]) index++
            if (index == window.length && window.substring(start, start + period).isNotBlank()) return true
        }
        return false
    }

    private fun repeatedShortLines(): Boolean {
        // Drop both cut boundary lines; snapshots and delimiters aren't counted as content.
        val candidates = window.toString().split('\n').drop(1).dropLast(1)
            .map { it.trim() }.filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var repeatedChars = 0
        for (line in candidates.asReversed()) {
            if (line.length > MAX_LINE_CHARS) return false
            lines.add(line)
            repeatedChars += line.length + 1
            if (repeatedChars >= MIN_CHARS) break
        }
        // Normal earlier text must not lend its length to a tiny repetitive suffix.
        if (repeatedChars < MIN_CHARS || lines.size < LINE_COUNT) return false
        val counts = lines.groupingBy { it }.eachCount().values.sortedDescending()
        return counts.first() >= 32 && counts.take(8).sum() * 100 >= lines.size * 97
    }

    companion object {
        private const val WINDOW_CHARS = 8192
        private const val MIN_CHARS = 4096
        private const val CHECK_STRIDE = 512
        private const val MAX_PERIOD = 256
        private const val LINE_COUNT = 128
        private const val MAX_LINE_CHARS = 160
    }
}
