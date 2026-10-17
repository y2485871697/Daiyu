package app.daiyu.android.gui

import android.test.InstrumentationTestCase
import app.daiyu.android.controlMain
import java.util.UUID

/** Real GuiRuntime capture bookkeeping and GuiRunSession.close main-loop drain. Resources
 * alone are fake: no accessibility service, system grant or screenshot backend is installed. */
@Suppress("DEPRECATION")
class GuiCaptureDisposalProductionTest : InstrumentationTestCase() {
    fun testEachFailedOnSuccessDisposalPinsOriginalSessionUntilAllResourceReceiptsExist() {
        for (failedResource in 0..2) controlMain {
            val id = UUID.randomUUID().toString()
            val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner(id, id)))
            val lease = session.lease
            @Suppress("UNCHECKED_CAST")
            val captures = GuiRuntime::class.java.getDeclaredField("captures").apply { isAccessible = true }
                .get(GuiRuntime) as MutableMap<GuiLeaseGate.Lease, Int>
            @Suppress("UNCHECKED_CAST")
            val closing = GuiRuntime::class.java.getDeclaredField("closingLeases").apply { isAccessible = true }
                .get(GuiRuntime) as MutableSet<GuiLeaseGate.Lease>
            val finished = GuiRuntime::class.java.getDeclaredMethod("captureFinished", GuiLeaseGate.Lease::class.java)
                .apply { isAccessible = true }
            var terminalReceipts = 0
            captures[lease] = 1
            val request = GuiCaptureRequest<String> { terminalReceipts++; finished.invoke(GuiRuntime, lease) }
            var fail = true
            val disposed = BooleanArray(3)
            val attempts = mutableListOf<Int>()
            fun dispose(index: Int) {
                attempts += index
                if (fail && index == failedResource) throw IllegalStateException("fixture disposal failure")
                disposed[index] = true
            }
            fun cleanup() = request.disposeSuccessResources({ dispose(0) }, { dispose(1) }, { dispose(2) })
            try {
                request.submit {}
                request.future.complete(GuiResult.Value("onSuccess image"))
                session.close()
                assertFalse(lease.valid) // revocation is synchronous, not a physical receipt
                assertFalse(session.releaseConfirmed)
                cleanup()
                assertEquals(listOf(0, 1, 2), attempts)
                assertFalse(disposed[failedResource])
                assertEquals(0, terminalReceipts)
                assertEquals(1, captures.getValue(lease))
                assertTrue(closing.contains(lease))
                session.close() // repeat close cannot turn the failed recycle/close into a receipt
                assertFalse(session.releaseConfirmed)
                assertEquals(0, terminalReceipts)
            } finally {
                // Test teardown actually disposes the remaining fake resource. This is NOT a
                // production retry, timeout release, or a direct resourcesDisposed injection.
                fail = false
                cleanup()
            }
            assertTrue(disposed.all { it })
            assertTrue(session.releaseConfirmed)
            assertFalse(captures.containsKey(lease))
            assertFalse(closing.contains(lease))
            assertEquals(1, terminalReceipts)
            cleanup()
            assertEquals(1, terminalReceipts)
        }
    }
}
