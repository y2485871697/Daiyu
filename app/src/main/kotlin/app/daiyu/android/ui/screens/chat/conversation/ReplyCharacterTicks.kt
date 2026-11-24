package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.diagnostics.HapticTrace
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Code as HC


/** Main-thread only. One pending callback regardless of network or frame rate. */
internal class ReplyCharacterTicks(private val now: () -> Long,
    private val post: (Runnable, Long) -> Unit, private val remove: (Runnable) -> Unit,
    private val tick: () -> Unit) : Runnable {
    companion object { const val BURST = 3; const val PERIOD = 50L }
    private var credits = 0
    private var due = 0L
    private var offered = 0L
    private var scheduled = false
    private var closed = false

    fun offer(count: Int) {
        if (closed || count <= 0) return
        HapticTrace.mark(HC.TICK_OFFER, count)
        offered = now()
        credits = (credits + count.coerceAtMost(BURST)).coerceAtMost(BURST)
        schedule()
    }
    private fun schedule() {
        if (!scheduled && credits > 0) {
            scheduled = true
            post(this, maxOf(now(), due))
        }
    }
    override fun run() {
        scheduled = false
        if (closed || credits == 0) return
        val time = now()
        if (time - offered > BURST * PERIOD) { HapticTrace.mark(HC.TICK_EXPIRED, credits); credits = 0; return }
        credits--
        due = time + PERIOD
        HapticTrace.mark(HC.TICK_FIRE)
        tick()
        schedule()
    }
    fun close() { if (credits > 0) HapticTrace.mark(HC.TICK_DISPOSED, credits); closed = true; credits = 0; remove(this) }
}
