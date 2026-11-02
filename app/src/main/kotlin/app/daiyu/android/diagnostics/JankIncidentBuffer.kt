package app.daiyu.android.diagnostics

/** Frame-worker confined; bounded pre-roll and one pending incident per window. */
internal class JankIncidentBuffer(
    private val emit: (List<Pair<String, PerformanceFrame>>, Boolean) -> Unit,
    private val trigger: () -> Unit,
) {
    private val history = ArrayDeque<Pair<String, PerformanceFrame>>()
    private var pending: MutableList<Pair<String, PerformanceFrame>>? = null
    private var finishNs = 0L
    private var nextTriggerNs = 0L

    fun add(page: String, frame: PerformanceFrame) {
        val sample = page to frame
        pending?.let { rows ->
            if (rows.size < 960) rows.add(sample)
            if (frame.vsyncNs >= finishNs || rows.size >= 960) flush(frame.vsyncNs >= finishNs)
        }
        history.addLast(sample)
        while (history.size > 480 || (history.size > 1 && frame.vsyncNs - history.first().second.vsyncNs > 2_000_000_000L)) history.removeFirst()
        if (pending == null && !frame.firstDraw && frame.budgetNs > 0 &&
            frame.totalNs > maxOf(frame.budgetNs * 2, 16_666_667L) && frame.vsyncNs >= nextTriggerNs) {
            pending = history.toMutableList()
            finishNs = frame.vsyncNs + 2_000_000_000L
            nextTriggerNs = frame.vsyncNs + 15_000_000_000L
            trigger()
        }
    }

    // Persist an incomplete snapshot without discarding long frames whose callback is still pending.
    fun checkpoint() {
        pending?.let { emit(it.toList(), false) }
    }

    fun flush(complete: Boolean = false) {
        val rows = pending ?: return
        pending = null
        emit(rows, complete)
    }
}
