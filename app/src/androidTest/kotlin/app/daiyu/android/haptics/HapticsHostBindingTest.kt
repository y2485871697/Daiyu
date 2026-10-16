package app.daiyu.android.haptics

import android.content.Context
import android.test.InstrumentationTestCase
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.domain.haptics.HapticSettings

/** Platform test source only; no instrumentation execution is claimed. */
@Suppress("DEPRECATION")
class HapticsHostBindingTest : InstrumentationTestCase() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun withHost(block: (Owner, HapticsHostBinding) -> Unit) {
        val context = instrumentation.targetContext
        val name = "p31-host-${System.nanoTime()}"
        try {
            instrumentation.runOnMainSync {
                val store = LocalHapticSettingsStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
                // These tests exercise real lifecycle ownership without operating a physical motor.
                assertTrue(store.write(HapticSettings(touch = false)))
                val runtime = HapticsRuntime(store)
                val owner = Owner()
                owner.registry.currentState = Lifecycle.State.RESUMED
                val host = HapticsHostBinding(runtime, View(context), owner, "conversation")
                try { block(owner, host) } finally { host.close(); runtime.close() }
            }
        } finally { context.deleteSharedPreferences(name) }
    }

    fun testPauseKeepsRunButDestroyRevokesIt() = withHost { owner, host ->
        val run = host.beginRun("run", "message")
        owner.registry.currentState = Lifecycle.State.CREATED
        assertFalse(run.ended)
        run.output(2, reasoning = true)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertTrue(run.ended)
        run.output(2)
        run.toolAppeared("late")
        assertFalse(host.acceptsRuns)
    }

    fun testReplacementIsFrozenAndStaleStopCannotAffectNewRun() = withHost { _, host ->
        val first = host.beginRun("first", "message-1")
        assertSame(first, host.beginRun("first", "message-1"))
        val next = host.beginRun("next", "message-2")
        assertTrue(first.ended)
        first.stop()
        first.output(36)
        assertFalse(next.ended)
        assertSame(next, host.beginRun("next", "message-2"))
        assertEquals("next", next.runId)
        assertEquals("message-2", next.messageId)
    }

    fun testOwnerRevocationNeverResurrectsOldRun() = withHost { _, host ->
        val first = host.beginRun("run", "message")
        host.setEnabled(false)
        assertTrue(first.ended)
        host.setEnabled(true)
        val fresh = host.beginRun("run", "message")
        assertNotSame(first, fresh)
        first.reasoningCompleted("late")
        assertFalse(fresh.ended)
    }
}
