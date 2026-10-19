package app.daiyu.android

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.ModelPort
import app.daiyu.android.domain.ModelSession
import app.daiyu.android.domain.ToolOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-only transport: no provider request or fallback is available. */
internal class ControlledPerformanceModel : AutoCloseable {
    private val queued = java.util.ArrayDeque<Session>()
    private val sessions = ArrayList<Session>()
    val port = ModelPort { synchronized(queued) { checkNotNull(queued.pollFirst()) { "synthetic_session_queue_empty" } } }
    fun enqueue(): Session = synchronized(queued) {
        Session().also { sessions.add(it); queued.addLast(it) }
    }
    class Session : ModelSession {
        val entered = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val emitted = AtomicInteger()
        private val cancelled = CountDownLatch(1)
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            entered.countDown()
            try {
                while (cancelled.count != 0L) {
                    val index = emitted.incrementAndGet()
                    sink(ModelEvent.TextDelta("body", "synthetic-$index "))
                    if (cancelled.await(INTERVAL_MS, TimeUnit.MILLISECONDS)) break
                }
            } finally { drained.countDown() }
        }
        override fun cancel() { cancelled.countDown() }
    }
    override fun close() {
        synchronized(queued) { sessions.toList().also { queued.clear() } }.forEach { it.cancel() }
    }
    companion object { const val INTERVAL_MS = 50L }
}
