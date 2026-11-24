package app.daiyu.android.diagnostics

/** Short, non-nested text measurements. The main thread writes; the frame worker reads by vsync. */
internal class ScrollTextTiming {
    enum class Part { PREPARE, CANDIDATE, NEXT_LINE, MEASURE }
    private val starts = LongArray(512)
    private val ends = LongArray(starts.size)
    private val kinds = IntArray(starts.size)
    private var next = 0
    private var size = 0

    @Synchronized fun add(part: Part, start: Long, end: Long) {
        starts[next] = start; ends[next] = end; kinds[next] = part.ordinal
        next = (next + 1) % starts.size
        if (size < starts.size) size++
    }
    // Only time intersecting this frame. A wrapped buffer may not cover its beginning.
    @Synchronized fun overlap(from: Long, until: Long): LongArray {
        val totals = LongArray(Part.entries.size + 1)
        if (size == starts.size && starts[next] > from) totals[totals.lastIndex] = 1
        for (i in 0 until size) {
            val index = (next - 1 - i + starts.size) % starts.size
            if (ends[index] <= from) break
            val amount = minOf(ends[index], until) - maxOf(starts[index], from)
            if (amount > 0) totals[kinds[index]] += amount
        }
        return totals
    }

    companion object {
        @Volatile var active: ScrollTextTiming? = null
        inline fun <T> timed(part: Part, work: () -> T): T {
            val capture = active ?: return work()
            val start = System.nanoTime()
            return try { work() } finally { capture.add(part, start, System.nanoTime()) }
        }
    }
}
