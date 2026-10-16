package app.daiyu.android.haptics

import android.content.Context
import android.test.InstrumentationTestCase
import android.view.View
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticKind
import app.daiyu.android.domain.haptics.HapticPort
import app.daiyu.android.domain.haptics.HapticRequest
import app.daiyu.android.domain.haptics.HapticSettings

/** Runtime regression source only; no instrumentation execution is claimed. */
@Suppress("DEPRECATION")
class HapticsRuntimeTest : InstrumentationTestCase() {
    private class Port(var delivery: HapticDelivery = HapticDelivery.PLAYED) : HapticPort {
        var plays = 0
        var cancels = 0
        override fun play(request: HapticRequest): HapticDelivery { plays++; return delivery }
        override fun cancel() { cancels++ }
    }

    private inner class Fixture(context: Context, name: String) {
        val store = LocalHapticSettingsStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        // Only the View gates are synthetic. All runtime/engine lifecycle and ownership paths are real;
        // injected ports record every downstream cancel without touching a physical motor.
        val view = object : View(context) {
            override fun isAttachedToWindow() = true
            override fun getWindowVisibility() = VISIBLE
        }
        val ports = mutableListOf<Port>()
        var nextDelivery = HapticDelivery.PLAYED
        val runtime = HapticsRuntime(store) { Port(nextDelivery).also { ports.add(it) } }

        fun ui(delivery: HapticDelivery = HapticDelivery.PLAYED): Port {
            nextDelivery = delivery
            val count = ports.size
            assertEquals(delivery, runtime.uiTouch(view, HapticKind.CLICK))
            assertEquals(count + 1, ports.size)
            return ports.last()
        }
    }

    private fun withRuntime(block: (Fixture) -> Unit) {
        val context = instrumentation.targetContext
        val name = "haptics-runtime-${System.nanoTime()}"
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                try {
                    val fixture = Fixture(context, name)
                    try { block(fixture) } finally { fixture.runtime.close() }
                } catch (t: Throwable) { failure = t }
            }
            failure?.let { throw it }
        } finally { context.deleteSharedPreferences(name) }
    }

    fun testUiSuccessSurvivesOldSessionCloseAndLatestOwnerCancelsOnce() = withRuntime { f ->
        val old = f.runtime.attach(f.view, "conversation", resumed = true)
        val oldPort = f.ports.last()
        assertEquals(HapticDelivery.PLAYED, old.touch())
        val ui = f.ui()
        assertEquals(0, oldPort.cancels) // replacement itself must not cancel the motor

        old.close()
        old.close()
        assertEquals(0, oldPort.cancels)
        assertEquals(0, ui.cancels)
        f.runtime.close()
        f.runtime.close()
        assertEquals(0, oldPort.cancels)
        assertEquals(1, ui.cancels)
    }

    fun testUiSuccessSurvivesOldSessionDisableAndConversationReplacement() = withRuntime { f ->
        val old = f.runtime.attach(f.view, "old", resumed = true)
        val oldPort = f.ports.last()
        assertEquals(HapticDelivery.PLAYED, old.touch())
        val ui = f.ui()
        old.lifecycle(resumed = false, visibleOutput = false, enabled = false)
        assertEquals(0, oldPort.cancels)
        assertEquals(0, ui.cancels)

        val next = f.runtime.attach(f.view, "next", resumed = true)
        val nextPort = f.ports.last()
        assertEquals(0, ui.cancels)
        assertEquals(HapticDelivery.PLAYED, next.touch())
        old.close()
        next.close()
        next.close()
        f.runtime.close()
        assertEquals(0, oldPort.cancels)
        assertEquals(0, ui.cancels)
        assertEquals(1, nextPort.cancels)
    }

    fun testEveryFailedUiDeliveryLeavesSessionAsLatestOwner() {
        for (delivery in HapticDelivery.values().filter { it != HapticDelivery.PLAYED }) {
            withRuntime { f ->
                val session = f.runtime.attach(f.view, "conversation", resumed = true)
                val port = f.ports.last()
                assertEquals(HapticDelivery.PLAYED, session.touch())
                val failed = f.ui(delivery)
                session.close()
                session.close()
                f.runtime.close()
                assertEquals(delivery.name, 1, port.cancels)
                assertEquals(delivery.name, 0, failed.cancels)
            }
        }
    }

    fun testEveryFailedSessionDeliveryLeavesUiAsLatestOwner() {
        for (delivery in HapticDelivery.values().filter { it != HapticDelivery.PLAYED }) {
            withRuntime { f ->
                val ui = f.ui()
                f.nextDelivery = delivery
                val session = f.runtime.attach(f.view, "conversation", resumed = true)
                val failed = f.ports.last()
                assertEquals(delivery, session.touch())
                session.close()
                assertEquals(0, ui.cancels)
                f.runtime.close()
                assertEquals(delivery.name, 1, ui.cancels)
                assertEquals(delivery.name, 0, failed.cancels)
            }
        }
    }

    fun testEveryFailedUiReplacementKeepsSuccessfulUiCancellable() {
        for (delivery in HapticDelivery.values().filter { it != HapticDelivery.PLAYED }) {
            withRuntime { f ->
                val ui = f.ui()
                val failed = f.ui(delivery)
                assertEquals(0, ui.cancels)
                f.runtime.close()
                f.runtime.close()
                assertEquals(delivery.name, 1, ui.cancels)
                assertEquals(delivery.name, 0, failed.cancels)
            }
        }
    }

    fun testFailedReplayCannotReclaimDisplacedSessionToken() = withRuntime { f ->
        val session = f.runtime.attach(f.view, "conversation", resumed = true)
        val port = f.ports.last()
        assertEquals(HapticDelivery.PLAYED, session.touch())
        val ui = f.ui()
        port.delivery = HapticDelivery.FAILED
        assertEquals(HapticDelivery.FAILED, session.touch())
        session.close()
        assertEquals(0, port.cancels)
        assertEquals(0, ui.cancels)
        f.runtime.close()
        assertEquals(1, ui.cancels)
    }

    fun testFailedReplayPreservesCurrentSessionToken() = withRuntime { f ->
        val session = f.runtime.attach(f.view, "conversation", resumed = true)
        val port = f.ports.last()
        assertEquals(HapticDelivery.PLAYED, session.touch())
        port.delivery = HapticDelivery.FAILED
        assertEquals(HapticDelivery.FAILED, session.touch())
        session.close()
        f.runtime.close()
        assertEquals(1, port.cancels)
    }

    fun testUiDoesNotClaimConversationAndSessionCanReclaimLatestPlayback() = withRuntime { f ->
        val session = f.runtime.attach(f.view, "conversation", resumed = true)
        val port = f.ports.last()
        assertEquals(HapticDelivery.PLAYED, session.touch())
        val ui = f.ui()
        // The same conversation remains eligible; a successful replay gets a fresh motor token.
        assertEquals(HapticDelivery.PLAYED, session.touch())
        assertEquals(2, port.plays)
        assertEquals(0, port.cancels)
        assertEquals(0, ui.cancels)
        session.close()
        f.runtime.close()
        assertEquals(1, port.cancels)
        assertEquals(0, ui.cancels)
    }

    fun testRepeatedUiReplacementAndGateCancellationOnlyReachLatestToken() = withRuntime { f ->
        val session = f.runtime.attach(f.view, "conversation", resumed = true)
        val port = f.ports.last()
        assertEquals(HapticDelivery.PLAYED, session.touch())
        val first = f.ui()
        val second = f.ui()
        val latest = f.ui()
        assertTrue(f.store.write(HapticSettings(touch = false)))
        assertTrue(f.store.write(HapticSettings(touch = false)))
        session.close()
        assertEquals(0, port.cancels)
        assertEquals(0, first.cancels)
        assertEquals(0, second.cancels)
        assertEquals(1, latest.cancels)

        assertTrue(f.store.write(HapticSettings()))
        val replacement = f.ui()
        f.runtime.close()
        f.runtime.close()
        assertEquals(1, latest.cancels)
        assertEquals(1, replacement.cancels)
    }

    fun testSessionReplacementAndRepeatedCancellationOnlyReachLatestPort() = withRuntime { f ->
        val first = f.runtime.attach(f.view, "conversation", resumed = true)
        val firstPort = f.ports.last()
        val latest = f.runtime.attach(f.view, "conversation", resumed = true)
        val latestPort = f.ports.last()
        assertEquals(HapticDelivery.PLAYED, first.touch())
        assertEquals(HapticDelivery.PLAYED, latest.touch())
        first.close()
        first.close()
        assertEquals(0, firstPort.cancels)
        assertEquals(0, latestPort.cancels)
        latest.lifecycle(resumed = false, visibleOutput = false, enabled = false)
        latest.lifecycle(resumed = false, visibleOutput = false, enabled = false)
        assertEquals(1, latestPort.cancels)
        latest.lifecycle(resumed = true, visibleOutput = true)
        assertEquals(HapticDelivery.PLAYED, latest.touch())
        latest.close()
        latest.close()
        f.runtime.close()
        assertEquals(0, firstPort.cancels)
        assertEquals(2, latestPort.cancels)
    }
}
