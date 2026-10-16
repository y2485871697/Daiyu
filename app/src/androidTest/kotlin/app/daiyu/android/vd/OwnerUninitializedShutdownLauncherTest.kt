package app.daiyu.android.vd

import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import java.util.UUID

/**
 * Opt-in root-device launcher coverage. Run only with -e r08RootShutdown true on an isolated
 * disposable rooted device. No display is initialized, no task is launched, no kill is sent.
 * Default instrumentation does NOT execute this root integration scenario.
 */
class OwnerUninitializedShutdownLauncherTest : InstrumentationTestCase() {
    fun testExplicitLookupOnlyOwnerShutdownAndVerifiedExit() {
        val runner = instrumentation as? InstrumentationTestRunner ?: return
        if (runner.arguments.getString("r08RootShutdown") != "true") return

        val host = AndroidVirtualDisplayOwnerHost(instrumentation.targetContext)
        val generation = "r08_" + UUID.randomUUID().toString().replace("-", "")
        val binding = OwnerRunBinding("r08-device", "lookup-only", "test-conversation",
            generation, "test-message", "test-owner")
        assertEquals(AndroidVirtualDisplayOwnerHost.Start.READY, host.start(generation, binding))
        val client = host.client ?: throw AssertionError("authenticated client missing")
        try {
            val before = host.observe()
            assertNotNull(before.ownerPid)
            assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, before.lifecycle)
            val receipt = client.lookupGeneration()
            assertEquals(OwnerReply.Outcome.REPLIED, receipt.outcome)
            assertEquals(false, receipt.payloadCopy()!!.get("found"))
            assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, host.observe().lifecycle)

            // Exactly one explicit send. Even a lost response is not permission to retry.
            val shutdown = client.shutdownUninitialized()
            if (shutdown.outcome == OwnerReply.Outcome.REPLIED) {
                assertEquals(true, shutdown.payloadCopy()!!.get("ok"))
                assertEquals(false, shutdown.payloadCopy()!!.get("initialized"))
                assertEquals(true, shutdown.payloadCopy()!!.get("receiptsClosed"))
                assertFalse(shutdown.payloadCopy()!!.has("released"))
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            var observed = host.observe()
            while (observed.lifecycle != AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED && System.nanoTime() < deadline) {
                Thread.sleep(25)
                observed = host.observe()
            }
            assertEquals(before.ownerPid, observed.ownerPid)
            assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, observed.lifecycle)
            assertTrue(observed.stopped)
            assertEquals(0, observed.launcherExit ?: -1)
            assertTrue(observed.ownerExitObserved)
        } finally {
            // Never initialize/release/kill/retry to make a failed test look drained.
            client.disconnectTransport()
        }
    }
}
