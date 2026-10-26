package app.daiyu.android.haptics

import android.content.Context
import android.test.InstrumentationTestCase
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticPort
import app.daiyu.android.domain.haptics.HapticRequest
import app.daiyu.android.domain.haptics.HapticSettings

/** Source-only instrumentation regressions. Ports record calls; no real vibrator or model is used. */
@Suppress("DEPRECATION")
class HapticsActivityHostTest : InstrumentationTestCase() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Port : HapticPort {
        var plays = 0
        var cancels = 0
        override fun play(request: HapticRequest): HapticDelivery { plays++; return HapticDelivery.PLAYED }
        override fun cancel() { cancels++ }
    }
    private inner class Fixture(context: Context, name: String) {
        val store = LocalHapticSettingsStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        val ports = mutableListOf<Port>()
        val runtime = HapticsRuntime(store) { Port().also { ports += it } }
        val lifecycle = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }
        val bindings = mutableListOf<HapticsHostBinding>()
        var removed = 0
        var afterDrain: (() -> Unit)? = null
        val host = HapticsActivityHost(runtime, View(context), lifecycle) { binding ->
            bindings += binding
            AutoCloseable { removed++ }
        }
        fun sync(id: String? = "c", visible: Boolean = true, settings: Boolean = false) =
            host.sync(id, visible, settings)
        fun close() { host.close(); runtime.close() }
    }
    private fun withHost(block: (Fixture) -> Unit) {
        val context = instrumentation.targetContext
        val name = "p31-activity-${System.nanoTime()}"
        var failure: Throwable? = null
        var fixture: Fixture? = null
        try {
            instrumentation.runOnMainSync {
                val f = Fixture(context, name).also { fixture = it }
                try { block(f) } catch (t: Throwable) { failure = t }
            }
            if (failure == null && fixture?.afterDrain != null) {
                // Wait off the main thread so queued runtime pulses have a chance to drain.
                Thread.sleep(150)
                instrumentation.runOnMainSync {
                    try { fixture?.afterDrain?.invoke() } catch (t: Throwable) { failure = t }
                }
            }
            failure?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { fixture?.close() }
            context.deleteSharedPreferences(name)
        }
    }

    fun testPauseAndSettingsDoNotCompleteRunAndUseSameRuntimeStore() = withHost { f ->
        f.sync()
        val run = f.bindings.single().beginRun("r", "m")
        f.lifecycle.registry.currentState = Lifecycle.State.CREATED
        assertFalse(run.ended)
        assertEquals(0, f.removed)
        f.lifecycle.registry.currentState = Lifecycle.State.RESUMED
        f.sync(visible = false, settings = true)
        val settings = checkNotNull(f.host.settingsOwner)
        assertTrue(f.host.isCurrentSettings(settings))
        assertSame(f.runtime, f.host.runtime)
        assertEquals(HapticDelivery.PLAYED, settings.feedback.touch())
        assertTrue(f.store.write(HapticSettings(touch = false)))
        assertNull(settings.feedback.touch()) // live setting, not a frozen owner snapshot
        f.sync()
        assertFalse(f.host.isCurrentSettings(settings))
        assertNull(settings.feedback.preview(f.store.read().intensity))
        assertFalse(run.ended)
        assertEquals(1, f.bindings.size)
        assertEquals(0, f.removed)
    }

    fun testConversationReplacementAndDestroyRevokeObserversBeforeLateCallbacks() = withHost { f ->
        f.sync()
        val old = f.bindings.single().beginRun("old", "m")
        f.sync("other")
        assertTrue(old.ended)
        assertEquals(1, f.removed)
        val next = f.bindings.last().beginRun("new", "m2")
        old.stop(); old.output(36); old.reasoningCompleted("late")
        assertFalse(next.ended)
        f.lifecycle.registry.currentState = Lifecycle.State.DESTROYED
        assertTrue(next.ended)
        assertEquals(2, f.removed)
        f.host.close()
        assertEquals(2, f.removed)
        f.sync("c") // no remount after destroy
        assertEquals(2, f.bindings.size)
    }

    fun testSettingsReentryHasFreshCapabilityEvenForSameConversation() = withHost { f ->
        f.sync(settings = true)
        val first = checkNotNull(f.host.settingsOwner)
        f.sync(settings = false)
        f.sync(settings = true)
        val next = checkNotNull(f.host.settingsOwner)
        assertNotSame(first, next)
        assertFalse(f.host.isCurrentSettings(first))
        assertNull(first.feedback.touch())
        assertEquals(HapticDelivery.PLAYED, next.feedback.touch())
    }

    fun testRendererCapabilityUsesHostFeedbackAfterRunSessionEnds() = withHost { f ->
        f.sync()
        val run = f.bindings.single().beginRun("r", "m")
        val reveal = f.host.revealAdvance("c")
        run.stop()
        val sessions = f.ports.size
        reveal()
        assertTrue(run.ended)
        assertEquals(sessions, f.ports.size)
        assertEquals(1, f.ports.first().plays)
        assertEquals(0, f.ports.last().plays)
        assertEquals(1, f.ports.sumOf { it.plays })
        f.afterDrain = {
            assertTrue(f.store.write(HapticSettings(generation = false)))
            reveal()
            assertEquals(1, f.ports.sumOf { it.plays })
        }
    }

    fun testRendererCapabilityRejectsWrongConversationReentryAndDestroy() = withHost { f ->
        f.sync()
        val stale = f.host.revealAdvance("c")
        val wrong = f.host.revealAdvance("other")
        wrong()
        f.sync("other"); f.sync("c")
        stale()
        assertEquals(0, f.ports.sumOf { it.plays })
        val current = f.host.revealAdvance("c")
        f.host.close(); current()
        assertEquals(0, f.ports.sumOf { it.plays })
    }

    fun testVisibleSurfaceAndSyncNeverInventRevealTicks() = withHost { f ->
        f.sync()
        val run = f.bindings.single().beginRun("r", "m")
        repeat(5) { f.sync(); run.output(1) }
        // Without an actual renderer frame, snapshots only enter the background path, which
        // the visible surface suppresses. Neither mounting nor resuming emits a reveal tick.
        f.lifecycle.registry.currentState = Lifecycle.State.STARTED
        f.lifecycle.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(0, f.ports.sumOf { it.plays })
    }

    fun testReasoningOldIdentityAndClosedForwarderCannotPolluteNewRun() = withHost { f ->
        f.sync(visible = false)
        val binding = f.bindings.single()
        var current = binding.beginRun("r", "m")
        val forwarder = HapticsReasoningForwarder { current }
        val oldMessage = Message(MessageId("m"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        forwarder.accept(oldMessage, ModelEvent.ReasoningDelta("reason", "hello"))
        current = binding.beginRun("r2", "m2")
        forwarder.accept(oldMessage, ModelEvent.ReasoningFinal("reason", "late"))
        forwarder.close()
        val nextMessage = oldMessage.copy(id = MessageId("m2"), runId = RunId("r2"))
        forwarder.accept(nextMessage, ModelEvent.ReasoningFinal("reason", "late"))
        assertFalse(current.ended)
        f.afterDrain = { assertEquals(0, f.ports.sumOf { it.plays }) }
    }

    fun testAcceptedReasoningFinalHasRealCompletionPulseWithoutVisibleAdvance() = withHost { f ->
        f.sync(visible = false)
        val current = f.bindings.single().beginRun("r", "m")
        val forwarder = HapticsReasoningForwarder { current }
        val message = Message(MessageId("m"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        // An empty accepted final still signals completion, never a fabricated text delta.
        forwarder.accept(message, ModelEvent.ReasoningFinal("reason", ""))
        forwarder.accept(message, ModelEvent.ReasoningFinal("reason", ""))
        f.afterDrain = {
            assertEquals(1, f.ports.sumOf { it.plays })
            forwarder.close()
        }
    }
}
