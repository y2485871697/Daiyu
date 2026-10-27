package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.CatalogFixturePort

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Resource-owner tests, not an Android/native-host substitute. Production Runner dispatch is
 * exercised separately by BrowserChildProductionTest and the Controller instrumentation source.
 */
class ToolEnabledChildRunTest {
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))
    private class Assembly : ChildToolAssembly {
        override lateinit var scope: ChildRunScope
        val prepares = AtomicInteger()
        val observed = CountDownLatch(1)
        val preparing = CountDownLatch(1)
        val finishPrepare = CountDownLatch(1)
        var holdPrepare = false
        @Volatile var observer: ((ChildToolDrain) -> Unit)? = null
        override val tools = object : CatalogTool {
            override val definitions = listOf(ToolDefinition("fixture", "fixture", "{}"))
            override fun execute(invocation: ToolInvocation): String = error("unused")
        }
        override val receipts = ReceiptBatchToolExecutor { _, _, _ -> error("unused") }
        override fun prepareOnWorker(): Boolean {
            prepares.incrementAndGet(); preparing.countDown()
            if (holdPrepare) check(finishPrepare.await(5, TimeUnit.SECONDS))
            return true
        }
        override fun close() {}
        override fun observeClose(observer: (ChildToolDrain) -> Unit) { this.observer = observer; observed.countDown() }
        fun report(result: ChildToolDrain) = checkNotNull(observer)(result)
    }
    private class Journal : ChildTaskStore {
        val finished = CountDownLatch(1)
        val enqueues = AtomicInteger()
        override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) { enqueues.incrementAndGet() }
        override fun started(task: ChildTaskRecord) {}
        override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) { finished.countDown() }
        override fun list(conversationId: String, limit: Int) = emptyList<ChildTaskRecord>()
    }
    private class Transport(private val failCancel: Boolean = false) : ModelSession {
        val cancelEntered = CountDownLatch(1)
        val returnCancel = CountDownLatch(1)
        val cancelExited = CountDownLatch(1)
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("text", "done")) }
        override fun cancel() {
            cancelEntered.countDown()
            try { check(returnCancel.await(5, TimeUnit.SECONDS)); check(!failCancel) }
            finally { cancelExited.countDown() }
        }
    }
    private class Fixture(val assembly: Assembly = Assembly(), val transport: Transport = Transport(),
        val capacity: Semaphore = Semaphore(1),
        val contextRuntime: RunContextRuntime? = null, val runtimeStore: RuntimeStore? = null) {
        val parent = RunExecutionOwner.issue(Message(MessageId("parent-message"), ConversationId("conversation"), RunId("parent"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val scope = ChildRunScope.capture(ChildRunOwner("conversation", "parent", "group", 7, "child", "child-message"),
            SessionCapabilities("session", emptySet()), TaskAuthorization("session", "parent"), emptySet())
        val journal = Journal()
        val factories = AtomicInteger()
        val run = ToolEnabledChildRun(scope, ChildTaskRecord(scope.owner, parent.assistantMessageId.value, parent.ownerId,
            0, "profile", "explicit child"), parent, assembly.apply { this.scope = this@Fixture.scope }, {
            factories.incrementAndGet()
            object : CatalogFixturePort {
                override fun open(history: List<Message>): ModelSession = error("schema required")
                override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                    check(definitions.isNotEmpty()); return transport
                }
            }
        }, journal, capacity, runtimeStore = runtimeStore, contextRuntime = contextRuntime)
        fun waitDrained() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!run.drained && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(run.drained)
        }
    }

    @Test fun capacityPauseIsVisibleAndResumeCompressesTheSameChildWithoutRecreation() {
        val paused = CountDownLatch(1)
        val summaries = AtomicInteger()
        val runtime = object : RunContextRuntime {
            override val automatic = false
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) =
                if (summaries.get() == 0) 1100L else 100L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                admit(); summaries.incrementAndGet(); return history
            }
        }
        val f = Fixture(contextRuntime = runtime, runtimeStore = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                if (checkpoint.context?.capacity != null) paused.countDown()
            }
        })
        try {
            assertTrue(f.run.start()); await(paused)
            assertTrue(f.run.contextLimited)
            assertTrue(f.run.snapshot.contextLimited)
            assertEquals(ChildTaskPauseState.PAUSED, f.run.pauseState)
            assertEquals(0, f.factories.get())
            assertEquals(ChildTaskControlReceipt.PENDING, f.run.resume())
            await(f.journal.finished); await(f.assembly.observed)
            f.transport.returnCancel.countDown(); f.assembly.report(ChildToolDrain.Drained)
            f.waitDrained()
            assertEquals(1, summaries.get()); assertEquals(1, f.factories.get()); assertEquals(1, f.assembly.prepares.get())
            assertFalse(f.run.contextLimited)
        } finally { f.transport.returnCancel.countDown(); f.run.close() }
    }

    @Test fun pauseBeforeStartPreservesOriginalBrowserChildAndNeverConstructsUntilResume() {
        val f = Fixture()
        try {
            assertEquals(ChildTaskControlReceipt.PENDING, f.run.pause())
            assertTrue(f.run.start())
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (f.run.pauseState != ChildTaskPauseState.PAUSED && System.nanoTime() < end) Thread.sleep(5)
            assertEquals(ChildTaskPauseState.PAUSED, f.run.pauseState)
            assertEquals(0, f.assembly.prepares.get()); assertEquals(0, f.factories.get())
            assertEquals(ChildTaskControlReceipt.RESUMED, f.run.resume())
            await(f.journal.finished); await(f.assembly.observed)
            f.transport.returnCancel.countDown(); f.assembly.report(ChildToolDrain.Drained)
            f.waitDrained(); assertEquals(1, f.journal.enqueues.get()); assertEquals(1, f.factories.get())
        } finally { f.transport.returnCancel.countDown(); f.run.close() }
    }

    @Test fun workerExitAndBrowserDrainDoNotReleaseWhileTransportCancelIsBlocked() {
        val f = Fixture()
        try {
            assertTrue(f.run.start())
            await(f.journal.finished); await(f.assembly.observed); await(f.transport.cancelEntered)
            assertEquals(0, f.capacity.availablePermits())
            f.assembly.report(ChildToolDrain.Drained)
            assertFalse(f.run.drained)
            assertEquals(0, f.capacity.availablePermits())
            f.transport.returnCancel.countDown(); await(f.transport.cancelExited)
            f.waitDrained()
            repeat(3) { f.run.close(); f.assembly.report(ChildToolDrain.Drained) }
            assertEquals(1, f.capacity.availablePermits())
            assertEquals(1, f.factories.get())
            assertFalse(f.run.start())
        } finally { f.transport.returnCancel.countDown(); f.run.close() }
    }

    @Test fun transportDrainAndWorkerExitDoNotReleaseWhileBrowserCleanupIsPending() {
        val f = Fixture()
        try {
            assertTrue(f.run.start())
            await(f.journal.finished); await(f.assembly.observed); await(f.transport.cancelEntered)
            f.transport.returnCancel.countDown(); await(f.transport.cancelExited)
            assertEquals(0, f.capacity.availablePermits())
            assertFalse(f.run.drained)
            f.assembly.report(ChildToolDrain.Drained)
            f.waitDrained()
            assertEquals(1, f.capacity.availablePermits())
        } finally { f.transport.returnCancel.countDown(); f.run.close() }
    }

    @Test fun unknownCleanupIsStickyEvenAfterLaterDrainedObservationAndCannotRestart() {
        val f = Fixture()
        try {
            assertTrue(f.run.start())
            await(f.journal.finished); await(f.assembly.observed)
            f.assembly.report(ChildToolDrain.Unknown("native_destroy_failed"))
            f.transport.returnCancel.countDown(); await(f.transport.cancelExited)
            f.assembly.report(ChildToolDrain.Drained)
            assertTrue(f.run.cleanupUnknown)
            assertFalse(f.run.drained)
            assertEquals(0, f.capacity.availablePermits())
            assertFalse(f.run.start())
            assertEquals(1, f.assembly.prepares.get())
        } finally { f.transport.returnCancel.countDown(); f.run.close() }
    }

    @Test fun failedTransportCleanupAlsoPinsPermitAfterBrowserDrained() {
        val f = Fixture(transport = Transport(failCancel = true))
        try {
            assertTrue(f.run.start())
            await(f.assembly.observed); await(f.transport.cancelEntered)
            f.assembly.report(ChildToolDrain.Drained)
            f.transport.returnCancel.countDown(); await(f.transport.cancelExited)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!f.run.cleanupUnknown && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(f.run.cleanupUnknown)
            assertFalse(f.run.drained)
            assertEquals(0, f.capacity.availablePermits())
        } finally { f.transport.returnCancel.countDown(); f.run.close() }
    }

    @Test fun stopBeforePermitNeverConstructsHostOrCreatesModel() {
        val f = Fixture(capacity = Semaphore(0))
        assertTrue(f.run.start())
        f.run.close(); await(f.assembly.observed)
        f.assembly.report(ChildToolDrain.Drained)
        f.waitDrained()
        assertEquals(0, f.assembly.prepares.get())
        assertEquals(0, f.factories.get())
        assertEquals(0, f.capacity.availablePermits())
    }

    @Test fun stopDuringConstructionCannotReleaseBeforeLateWorkerPublication() {
        val assembly = Assembly().apply { holdPrepare = true }
        val f = Fixture(assembly)
        try {
            assertTrue(f.run.start()); await(assembly.preparing)
            f.run.close(); await(assembly.observed)
            assembly.report(ChildToolDrain.Drained)
            assertFalse(f.run.drained)
            assertEquals(0, f.capacity.availablePermits())
            assembly.finishPrepare.countDown()
            f.waitDrained()
            assertEquals(0, f.factories.get())
            assertEquals(1, f.capacity.availablePermits())
        } finally { assembly.finishPrepare.countDown(); f.run.close() }
    }
}
