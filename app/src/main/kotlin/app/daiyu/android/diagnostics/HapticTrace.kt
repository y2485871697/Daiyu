package app.daiyu.android.diagnostics

import java.util.concurrent.atomic.AtomicLongArray

/** Per-code sampling keeps a silent interval visible without tracing every frame. */
internal object HapticTrace {
    private val next = AtomicLongArray(RuntimeDiagnostics.Code.entries.size)
    fun mark(code: RuntimeDiagnostics.Code, amount: Int = -1) {
        if (!RuntimeDiagnostics.enabled) return
        val index = code.ordinal
        val old = next.get(index)
        val now = System.nanoTime()
        if (old != 0L && now - old < 0L || !next.compareAndSet(index, old, now + 250_000_000L)) return
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.REPLY_HAPTIC, code, count = amount.toLong())
    }
}
