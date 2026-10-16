package app.daiyu.android.pet

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.domain.pet.*
import java.util.UUID

/** Exercises the public host, not just the overlay's independently retryable close. */
@Suppress("DEPRECATION")
class PetHostCloseTest : InstrumentationTestCase() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Model : PetModelPort, AutoCloseable {
        var requests = 0
        var cancellations = 0
        var closes = 0
        override fun complete(request: PetModelRequest, completion: (PetModelResult) -> Unit): PetCancel {
            requests++
            return PetCancel { cancellations++ }
        }
        override fun close() { closes++ }
    }
    private class WindowPort : PetOverlayWindowPort<View> {
        val active = linkedSetOf<View>()
        val removed = mutableListOf<View>()
        var adds = 0
        var updates = 0
        var unknownRemoval = false
        override fun add(window: View) { adds++; active += window }
        override fun update(window: View) { updates++ }
        override fun remove(window: View): PetOverlayRemoval {
            removed += window
            check((window as FrameLayout).childCount == 0) { "Content must be retired before root cleanup" }
            if (unknownRemoval) return PetOverlayRemoval.UNKNOWN
            active -= window
            return PetOverlayRemoval.DETACHED
        }
    }
    private inner class Fixture : AutoCloseable {
        private val base = instrumentation.targetContext
        private val prefix = "pet-host-close-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val model = Model()
        val port = WindowPort()
        val owner = Owner()
        val mount = FrameLayout(context)
        val host = PetHost(context, model, voice = null, onOpenSettings = {},
            windowPort = port, canDrawOverlays = { true })
        init {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            host.updateSettings(PetSettings(enabled = true, globalVisible = true,
                model = PetModelSelection("a", "p", "m")))
            host.attach(mount, owner)
            host.feed(PetFood.BOWL)
        }
        override fun close() {
            port.unknownRemoval = false
            host.close()
            context.getSharedPreferences("daiyu_pet_v1", 0).edit().clear().commit()
        }
    }

    fun testPublicCloseRetriesUnknownRootWithoutReclosingModelOrReenteringCleanup() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                assertEquals(PetOverlayState.VISIBLE, f.host.snapshot().overlayState)
                assertEquals(1, f.model.requests)
                assertTrue(f.host.snapshot().pet.state.thinking)
                val root = f.port.active.single() as FrameLayout
                val updates = f.port.updates
                f.port.unknownRemoval = true
                val shutdownStates = mutableListOf<PetOverlayState>()
                f.host.observe { next ->
                    if (next.closed) {
                        shutdownStates += next.overlayState
                        // Both overlay-state and final host notifications are synchronous.
                        f.host.close()
                    }
                }

                f.host.close()
                assertTrue(f.host.snapshot().closed)
                assertTrue(f.host.snapshot().pet.closed)
                assertEquals(PetOverlayState.ERROR, f.host.snapshot().overlayState)
                assertTrue(f.host.snapshot().overlayError!!.contains("清理结果未知"))
                assertEquals(listOf(PetOverlayState.ERROR, PetOverlayState.ERROR), shutdownStates)
                assertEquals(listOf(root), f.port.removed)
                assertSame(root, f.port.active.single())
                assertEquals(0, root.childCount)
                assertEquals(0, f.mount.childCount)
                assertEquals(1, f.model.closes)
                assertEquals(1, f.model.cancellations)

                // Closed runtime entrypoints cannot retry or resurrect the retained root.
                f.host.refreshOverlayPermission()
                assertTrue(f.host.setGlobalVisible(true) is PetCommandResult.Rejected)
                assertEquals(listOf(root), f.port.removed)
                f.port.unknownRemoval = false
                f.host.close()
                assertEquals(listOf(root, root), f.port.removed)
                assertTrue(f.port.active.isEmpty())
                assertEquals(PetOverlayState.CLOSED, f.host.snapshot().overlayState)
                assertNull(f.host.snapshot().overlayError)
                assertEquals(1, f.model.closes)
                assertEquals(1, f.model.cancellations)

                // Completed cleanup is terminal; neither repeat close nor owner destroy does IO.
                f.host.close()
                f.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                assertEquals(2, f.port.removed.size)
                assertEquals(1, f.port.adds)
                assertEquals(updates, f.port.updates)
                assertEquals(1, f.model.closes)
                assertEquals(1, f.model.cancellations)
                assertEquals(2, shutdownStates.size) // Host subscriptions were released once.
            } finally { f.close() }
        }
    }

    fun testSynchronousSuccessfulCloseNotificationsDoNotRepeatExternalIo() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                var notifications = 0
                f.host.observe { next ->
                    if (next.closed) { notifications++; f.host.close() }
                }
                f.host.close()
                assertEquals(2, notifications)
                assertEquals(PetOverlayState.CLOSED, f.host.snapshot().overlayState)
                assertEquals(1, f.port.removed.size)
                assertTrue(f.port.active.isEmpty())
                assertEquals(1, f.model.closes)
                assertEquals(1, f.model.cancellations)
                f.host.close()
                assertEquals(1, f.port.removed.size)
                assertEquals(1, f.model.closes)
                assertEquals(1, f.model.cancellations)
            } finally { f.close() }
        }
    }
}
