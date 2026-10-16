package app.daiyu.android.pet

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.domain.*
import app.daiyu.android.domain.pet.*
import app.daiyu.android.storage.ModelSettings
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No real WindowManager, permission launch, model network or service is used. */
@Suppress("DEPRECATION")
class PetApplicationHostTest : InstrumentationTestCase() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
    }
    private class Model : PetModelPort, AutoCloseable {
        val requests = mutableListOf<PetModelRequest>()
        val callbacks = mutableListOf<(PetModelResult) -> Unit>()
        var cancellations = 0
        var closes = 0
        override fun complete(request: PetModelRequest, completion: (PetModelResult) -> Unit): PetCancel {
            requests += request; callbacks += completion
            return PetCancel { cancellations++ }
        }
        override fun close() { closes++ }
    }
    private class Windows : PetOverlayWindowPort<View> {
        val active = linkedSetOf<View>()
        val removed = mutableListOf<View>()
        var adds = 0
        var unknown = false
        override fun add(window: View) { adds++; active += window }
        override fun update(window: View) { check(window in active) }
        override fun remove(window: View): PetOverlayRemoval {
            check((window as FrameLayout).childCount == 0)
            removed += window
            if (unknown) return PetOverlayRemoval.UNKNOWN
            active -= window
            return PetOverlayRemoval.DETACHED
        }
    }
    private class Source : PetAssistantTransitionSource {
        lateinit var listener: (PetAssistantTransition) -> Unit
        var cancellations = 0
        override fun subscribe(listener: (PetAssistantTransition) -> Unit): PetCancel {
            this.listener = listener
            return PetCancel { cancellations++ }
        }
        // Intentionally deliver even after cancellation to emulate a racing/late producer.
        fun emit(event: PetAssistantTransition) = listener(event)
    }
    private fun context(): Context {
        val base = instrumentation.targetContext
        val prefix = "pet-application-${UUID.randomUUID()}-"
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
    }
    private inner class Fixture : AutoCloseable {
        val context = context()
        val model = Model()
        val windows = Windows()
        var permission = true
        val app = PetApplicationHost(context, model, null, {}, windows, { permission })
        val owner = Owner()
        val mount = FrameLayout(context)
        init {
            app.updateSettings(PetSettings(enabled = true, globalVisible = true,
                model = PetModelSelection("pet-assistant", "pet-provider", "pet-model")))
        }
        override fun close() {
            windows.unknown = false
            app.close()
            context.getSharedPreferences("daiyu_pet_v1", 0).edit().clear().commit()
        }
    }
    private fun transition(id: String): PetAssistantTransition {
        val before = Message(MessageId("message-$id"), ConversationId("conversation"), RunId(id),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        return PetAssistantTransition(before, before.copy(status = RunStatus.COMPLETED), "Task $id")
    }
    private fun reply() = PetModelResult.Completed("{\"mood\":\"happy\",\"speech\":\"独立反应\"}", 11, 7)

    fun testExplicitGateRequiresSystemPermissionAndRevokesWhileDetached() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                assertEquals(0, f.windows.adds)
                f.app.attach(f.mount, f.owner)
                assertEquals(PetOverlayState.PERMISSION_REQUIRED, f.app.host.snapshot().overlayState)
                f.app.refreshOverlayPermission()
                assertEquals(0, f.windows.adds) // Refresh is not consent.
                f.permission = false
                f.app.setOverlayAllowed(true)
                assertEquals(0, f.windows.adds) // Consent is not an Android permission grant.
                f.permission = true
                f.app.refreshOverlayPermission()
                assertEquals(1, f.windows.adds)
                assertEquals(PetOverlayState.VISIBLE, f.app.host.snapshot().overlayState)
                f.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                f.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                assertEquals(0, f.mount.childCount)
                assertFalse(f.app.host.snapshot().closed)
                assertEquals(1, f.windows.active.size)
                f.permission = false
                f.app.refreshOverlayPermission() // Still watches permission after Activity destruction.
                assertTrue(f.windows.active.isEmpty())
                f.permission = true
                f.app.refreshOverlayPermission()
                assertEquals(2, f.windows.adds)
                f.app.setOverlayAllowed(false)
                assertTrue(f.windows.active.isEmpty())
                assertFalse(f.app.host.snapshot().canDrawOverlays)
            } finally { f.close() }
        }
    }

    fun testActivityRecreationAndLateLeaseDoNotCloseApplicationRuntime() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                val oldLease = f.app.attach(f.mount, f.owner)
                val retained = f.app.host
                f.app.host.feed(PetFood.BOWL)
                assertEquals(1, f.model.requests.size)
                f.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                f.owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                assertEquals(0, f.mount.childCount)
                val newMount = FrameLayout(f.context)
                val newOwner = Owner()
                val lease = f.app.attach(newMount, newOwner)
                oldLease.cancel(); oldLease.cancel()
                assertSame(retained, f.app.host)
                assertEquals(1, newMount.childCount)
                assertEquals(0, f.model.closes)
                assertEquals(0, f.model.cancellations)
                assertTrue(f.app.host.snapshot().pet.state.thinking)
                lease.cancel()
                assertEquals(0, newMount.childCount)
                assertFalse(f.app.closed)
                assertFalse(f.app.host.snapshot().closed)
            } finally { f.close() }
        }
    }

    fun testUnknownCloseRetainsSameRootAndDoesNotReclosePortsOrPermitRestart() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                val lease = f.app.attach(f.mount, f.owner)
                f.app.setOverlayAllowed(true)
                f.app.host.feed(PetFood.BOWL)
                val root = f.windows.active.single()
                f.windows.unknown = true
                f.app.host.observe { if (it.closed) f.app.close() } // Reentrant close is fenced.
                f.app.close()
                assertTrue(f.app.closed)
                assertFalse(f.app.cleanupComplete)
                assertSame(root, f.windows.active.single())
                assertEquals(0, (root as FrameLayout).childCount)
                assertEquals(1, f.windows.removed.size)
                assertEquals(1, f.model.closes)
                assertEquals(1, f.model.cancellations)
                assertTrue(runCatching { f.app.attach(FrameLayout(f.context), Owner()) }.isFailure)
                lease.cancel()
                f.app.setOverlayAllowed(true)
                f.app.refreshOverlayPermission()
                f.model.callbacks.single()(reply())
                assertFalse(f.app.host.snapshot().pet.state.speechVisible)
                assertEquals(1, f.windows.adds)
                f.windows.unknown = false
                f.app.close()
                assertTrue(f.app.cleanupComplete)
                assertEquals(listOf(root, root), f.windows.removed)
                assertEquals(1, f.model.closes)
                assertEquals(1, f.model.cancellations)
                f.app.close()
                assertEquals(2, f.windows.removed.size)
            } finally { f.close() }
        }
    }

    fun testOnlyLiveCommittedSameOwnerSuccessReachesPetAndOldSourceIsFenced() {
        lateinit var f: Fixture
        val first = Source(); val second = Source()
        lateinit var oldLease: PetCancel
        lateinit var newLease: PetCancel
        instrumentation.runOnMainSync { f = Fixture() }
        try {
            instrumentation.runOnMainSync {
                oldLease = f.app.connectAssistantTransitions(first)
                first.emit(transition("queued-old"))
                newLease = f.app.connectAssistantTransitions(second)
                oldLease.cancel() // Must neither unsubscribe second nor accept first's queued event.
                first.emit(transition("late-old"))
                val valid = transition("live")
                second.emit(valid.copy(before = valid.after)) // Loaded completed row is not a transition.
                second.emit(valid.copy(after = valid.after.copy(status = RunStatus.STOPPED)))
                second.emit(valid.copy(after = valid.after.copy(status = RunStatus.FAILED)))
                second.emit(valid.copy(after = valid.after.copy(error = "failed")))
                second.emit(valid.copy(after = valid.after.copy(id = MessageId("foreign"))))
                second.emit(valid.copy(after = valid.after.copy(runId = RunId("foreign"))))
                second.emit(valid.copy(after = valid.after.copy(conversationId = ConversationId("foreign"))))
                second.emit(valid.copy(after = valid.after.copy(role = Message.USER_ROLE)))
            }
            instrumentation.runOnMainSync {
                assertEquals(0, f.model.requests.size)
                assertEquals(1, first.cancellations)
                assertEquals(0, second.cancellations)
                second.emit(transition("live"))
                second.emit(transition("live")) // Persisted run dedup.
            }
            instrumentation.runOnMainSync {
                assertEquals(1, f.model.requests.size)
                assertEquals(PetEvent.WORK_DONE, f.model.requests.single().event)
                assertEquals(listOf(PetCompletedRun("conversation", "live")), f.app.host.snapshot().pet.state.completedRuns)
                second.emit(transition("cancelled-queued"))
                newLease.cancel()
                second.emit(transition("cancelled-late"))
            }
            instrumentation.runOnMainSync {
                assertEquals(1, second.cancellations)
                assertEquals(1, f.model.requests.size)
                assertEquals(1, f.app.host.snapshot().pet.state.completedRuns.size)
            }
        } finally { instrumentation.runOnMainSync { f.close() } }
    }

    fun testWorkerCancellationCannotDetachOrDeliverIntoReplacementAndCloseDropsQueuedEvents() {
        lateinit var f: Fixture
        lateinit var mount: FrameLayout
        val oldSource = Source(); val currentSource = Source()
        instrumentation.runOnMainSync { f = Fixture() }
        try {
            instrumentation.runOnMainSync {
                val oldMount = f.app.attach(f.mount, f.owner)
                val oldEvents = f.app.connectAssistantTransitions(oldSource)
                val worker = Thread {
                    oldSource.emit(transition("old-worker"))
                    oldEvents.cancel()
                    oldMount.cancel()
                }
                worker.start(); worker.join() // Keep main queued work pending until replacement.
                mount = FrameLayout(f.context)
                f.app.attach(mount, Owner())
                f.app.connectAssistantTransitions(currentSource)
            }
            instrumentation.runOnMainSync {
                assertEquals(1, mount.childCount)
                assertEquals(0, f.model.requests.size)
                assertEquals(1, oldSource.cancellations)
                assertEquals(0, currentSource.cancellations)
                currentSource.emit(transition("live-worker"))
            }
            instrumentation.runOnMainSync {
                assertEquals(1, f.model.requests.size)
                currentSource.emit(transition("close-queued"))
                f.app.close()
                currentSource.emit(transition("close-late"))
            }
            instrumentation.runOnMainSync {
                assertEquals(1, f.model.requests.size)
                assertEquals(1, f.app.host.snapshot().pet.state.completedRuns.size)
                assertEquals(1, currentSource.cancellations)
                assertEquals(1, f.model.closes)
            }
        } finally { instrumentation.runOnMainSync { f.close() } }
    }

    fun testSettingsAndSameIdRegistryChangesCancelOldReactionNotNewOne() {
        instrumentation.runOnMainSync {
            val f = Fixture()
            try {
                f.app.host.feed(PetFood.BOWL)
                val original = f.model.requests.single().selection
                f.app.onModelConfigurationChanged()
                assertEquals(1, f.model.cancellations)
                f.app.host.feed(PetFood.BOWL)
                assertEquals(original, f.model.requests[1].selection)
                f.model.callbacks[0](reply())
                assertTrue(f.app.host.snapshot().pet.state.thinking)
                assertFalse(f.app.host.snapshot().pet.state.speechVisible)
                val changed = original.copy(assistantId = "other-assistant", providerId = "other-provider", modelId = "other-model")
                f.app.updateSettings(f.app.host.snapshot().pet.state.settings.copy(model = changed))
                assertEquals(2, f.model.cancellations)
                f.app.host.feed(PetFood.BOWL)
                assertEquals(changed, f.model.requests[2].selection)
                f.model.callbacks[1](reply())
                assertTrue(f.app.host.snapshot().pet.state.thinking)
                f.model.callbacks[2](reply())
                assertFalse(f.app.host.snapshot().pet.state.thinking)
                assertTrue(f.app.host.snapshot().pet.state.speechVisible)
                assertEquals(1, f.app.host.snapshot().pet.state.memories.size)
            } finally { f.close() }
        }
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try { check(latch.await(10, TimeUnit.SECONDS)); return }
            catch (_: InterruptedException) { /* Emulate native IO that cannot be interrupted. */ }
        }
    }

    fun testPausedMainIoRevocationFencesOldOpenReceiptAndEffectsWithoutCancellingNewOwner() {
        val context = context()
        val oldTerminal = CountDownLatch(1); val releaseOld = CountDownLatch(1)
        val newEntered = CountDownLatch(1); val releaseNew = CountDownLatch(1)
        val revoked = CountDownLatch(1); val newVoice = CountDownLatch(1)
        val opens = AtomicInteger(); val oldQueuedCallbacks = AtomicInteger(); val voices = AtomicInteger()
        val adapter = AndroidPetModelPort(PetModelResolver {
            ResolvedPetModel(it, ModelSettings("https://pet.invalid", "pet-api", "test-secret", false))
        }) { _, _ ->
            ModelPort {
                val first = opens.incrementAndGet() == 1
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (!first) { newEntered.countDown(); awaitIgnoringInterrupt(releaseNew) }
                        sink(ModelEvent.TextFinal("t", "{\"mood\":\"happy\",\"speech\":\"独立反应\"}"))
                        sink(ModelEvent.Usage(if (first) 91L else 11L, if (first) 33L else 7L))
                        // All old text/usage has already been parsed; return a genuine terminal
                        // AFTER revocation, without another event that could supply the fence.
                        if (first) { oldTerminal.countDown(); awaitIgnoringInterrupt(releaseOld) }
                    }
                    override fun cancel() {} // A late terminal receipt is intentionally still possible.
                }
            }
        }
        lateinit var app: PetApplicationHost
        var satietyAfterNewFeed = -1
        val ioFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val io = Thread {
            try { app.onModelConfigurationChanged() }
            catch (failure: Throwable) { ioFailure.set(failure) }
            finally { revoked.countDown() }
        }
        instrumentation.runOnMainSync {
            app = PetApplicationHost(context, adapter, PetVoicePort { _, _ ->
                voices.incrementAndGet(); newVoice.countDown(); PetCancel {}
            }, {}, Windows(), { false })
            app.updateSettings(PetSettings(enabled = true, audioEnabled = true,
                model = PetModelSelection("pet-assistant", "pet-provider", "pet-model")))
            app.host.feed(PetFood.BOWL)
        }
        try {
            assertTrue(oldTerminal.await(3, TimeUnit.SECONDS))
            val selection = app.host.snapshot().pet.state.settings.model
            adapter.complete(PetModelRequest("queued-old", selection, PetEvent.FEED, "old", "pet:queued-old")) {
                oldQueuedCallbacks.incrementAndGet()
            }
            instrumentation.runOnMainSync {
                // Deliberately freeze main while IO revokes. No queued UI runnable may be needed
                // to fence the old adapter or retire its controller owner.
                io.start()
                assertTrue(revoked.await(3, TimeUnit.SECONDS))
                assertNull(ioFailure.get())
                val retired = app.host.snapshot().pet.state
                assertFalse(retired.thinking)
                assertFalse(retired.speechVisible)
                assertTrue(retired.memories.isEmpty())
                assertEquals(PetRequestStatus.CANCELLED, retired.usage.single().status)
                assertNull(retired.usage.single().inputTokens)
                assertNull(retired.usage.single().outputTokens)
                assertEquals(0, voices.get())
                // New owner starts BEFORE the old revocation's queued UI synchronization runs.
                app.host.feed(PetFood.BOWL)
                assertTrue(app.host.snapshot().pet.state.thinking)
                satietyAfterNewFeed = app.host.snapshot().pet.state.satiety
            }
            instrumentation.runOnMainSync {
                assertTrue(app.host.snapshot().pet.state.thinking) // Old UI work did not cancel new owner.
                releaseOld.countDown()
            }
            assertTrue(newEntered.await(3, TimeUnit.SECONDS)) // Serial executor has drained both old jobs.
            val afterOldTerminal = app.host.snapshot().pet.state
            assertTrue(afterOldTerminal.thinking)
            assertTrue(afterOldTerminal.memories.isEmpty())
            assertFalse(afterOldTerminal.speechVisible)
            assertEquals(satietyAfterNewFeed, afterOldTerminal.satiety)
            assertEquals(0, voices.get()); assertEquals(0, oldQueuedCallbacks.get())
            assertEquals(2, opens.get()) // Only the old already-open session and the new owner.
            assertEquals(PetRequestStatus.CANCELLED, afterOldTerminal.usage.first().status)
            assertNull(afterOldTerminal.usage.first().inputTokens) // No late receipt write either.
            assertNull(afterOldTerminal.usage.first().outputTokens)
            releaseNew.countDown()
            assertTrue(newVoice.await(3, TimeUnit.SECONDS))
            val completed = app.host.snapshot().pet.state
            assertEquals(1, completed.memories.size)
            assertEquals(1, voices.get())
            assertEquals(PetRequestStatus.COMPLETED, completed.usage.last().status)
            assertEquals(11L, completed.usage.last().inputTokens)
            assertNull(completed.usage.first().inputTokens)
        } finally {
            releaseOld.countDown(); releaseNew.countDown()
            io.join(3000)
            instrumentation.runOnMainSync { app.close() }
            context.getSharedPreferences("daiyu_pet_v1", 0).edit().clear().commit()
        }
    }

    fun testProductionConstructorPassesExactThreeIdsAndNeverOpensFallbackTransport() {
        instrumentation.runOnMainSync {
            val context = context()
            val lookups = mutableListOf<PetModelSelection>()
            // Unavailable independent registry: returning null must fail closed, not use main config.
            val app = PetApplicationHost(context, PetModelResolver { lookups += it; null }, onOpenSettings = {})
            try {
                val selected = PetModelSelection("assistant-record", "provider-record", "model-record")
                app.updateSettings(PetSettings(enabled = true, model = selected))
                app.host.feed(PetFood.BOWL)
                assertEquals(listOf(selected), lookups)
                assertEquals(selected.assistantId, app.host.snapshot().pet.state.usage.single().assistantId)
                assertEquals(selected.providerId, app.host.snapshot().pet.state.usage.single().providerId)
                assertEquals(selected.modelId, app.host.snapshot().pet.state.usage.single().modelId)
                assertFalse(app.overlayAllowed)
            } finally {
                app.close()
                context.getSharedPreferences("daiyu_pet_v1", 0).edit().clear().commit()
            }
        }
    }
}
