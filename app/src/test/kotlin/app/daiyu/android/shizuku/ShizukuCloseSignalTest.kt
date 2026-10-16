package app.daiyu.android.shizuku

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ShizukuCloseSignalTest {
    @Test fun closeRequestAloneDoesNotPublishDrained() {
        val signal = ShizukuCloseSignal()
        val callbacks = AtomicInteger()
        signal.observe { callbacks.incrementAndGet() }
        assertEquals(0, callbacks.get())
        signal.complete(ShizukuCloseResult.Drained)
        assertEquals(1, callbacks.get())
    }

    @Test fun lateObserverGetsOriginalResultAndCannotUpgradeUnknown() {
        val signal = ShizukuCloseSignal()
        val unknown = ShizukuCloseResult.Unknown("lost")
        signal.complete(unknown)
        signal.complete(ShizukuCloseResult.Drained)
        var observed: ShizukuCloseResult? = null
        signal.observe { observed = it }
        assertSame(unknown, observed)
    }

    @Test fun observerFailureDoesNotSkipOtherObservers() {
        val signal = ShizukuCloseSignal()
        var observed: ShizukuCloseResult? = null
        signal.observe { error("listener failed") }
        signal.observe { observed = it }
        signal.complete(ShizukuCloseResult.Drained)
        assertSame(ShizukuCloseResult.Drained, observed)
    }
}
