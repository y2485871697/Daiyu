package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.ToolDefinition
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ChildModelExecutionToolsTest {
    private class Parent {
        val owner = RunExecutionOwner.issue(Message(MessageId("parent"), ConversationId("conversation"), RunId("parent-run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val session = SessionCapabilities("session", setOf("fixture.read"))
        val authorization = TaskAuthorization(session.sessionId, owner.runId.value)
        fun context() = ToolExecutionContext(owner, ToolInvocation(ToolCallId("call"), "run_child_models", "{}"), authorization) { false }
        fun bind(batch: ChildModelBatch) = batch.apply { bind(owner, session, authorization) }
        fun record() = ChildTaskRecord(ChildRunOwner("conversation", "parent-run", "group", 1, "child", "child-message"),
            owner.assistantMessageId.value, owner.ownerId, 0, "one", "read once", access = ChildToolAccess.DEFAULT)
    }
    private class Assembly : ChildToolAssembly {
        override lateinit var scope: ChildRunScope
        val prepares = AtomicInteger()
        val executions = AtomicInteger()
        val observed = CountDownLatch(1)
        var automaticDrain = true
        @Volatile var observer: ((ChildToolDrain) -> Unit)? = null
        var beforeReceipt: () -> Unit = {}
        override val tools = object : CatalogTool {
            override val definitions = listOf(ToolDefinition("fixture", "read fixture", "{}"))
            override fun execute(invocation: ToolInvocation): String = error("receipt path required")
        }
        override val receipts = ReceiptBatchToolExecutor { _, calls, _ ->
            executions.incrementAndGet(); beforeReceipt()
            calls.map { ToolDispatchResult(it.id, DispatchOutcome.Confirmed("read-once", false)) }
        }
        override fun prepareOnWorker(): Boolean { prepares.incrementAndGet(); return true }
        override fun close() {}
        override fun observeClose(observer: (ChildToolDrain) -> Unit) {
            this.observer = observer; observed.countDown()
            if (automaticDrain) observer(ChildToolDrain.Drained)
        }
    }
    private class Port(private val toolsExpected: Boolean = true, private val useTool: Boolean = true, private val toolRounds: Int = 1) : CatalogFixturePort {
        val opens = AtomicInteger()
        val requests = AtomicInteger()
        val cancelled = CountDownLatch(1)
        var failCancel = false
        var beforeTool: () -> Unit = {}
        var onOpen: (List<Message>) -> Unit = {}
        var afterCancel: () -> Unit = {}
        override fun open(history: List<Message>): ModelSession = error("explicit schemas required")
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            assertEquals(toolsExpected, definitions.isNotEmpty()); opens.incrementAndGet()
            onOpen(history)
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    val request = requests.incrementAndGet()
                    if (request <= toolRounds && useTool) {
                        if (request > 1) assertEquals(listOf("read-once"), outputs.map { it.output })
                        beforeTool()
                        val id = if (toolRounds == 1) "read" else "read-$request"
                        sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId(id), "fixture", "{}")))
                    } else {
                        if (useTool) assertEquals(1, outputs.size)
                        sink(ModelEvent.TextFinal("answer", "continued"))
                    }
                }
                override fun cancel() { cancelled.countDown(); check(!failCancel) { "transport_cleanup_failed" }; afterCancel() }
            }
        }
    }
    private fun factory(assembly: Assembly) = ChildToolAssemblyFactory { owner, session, authorization, _ ->
        assembly.apply { scope = ChildRunScope.capture(owner, session, authorization, setOf("fixture.read")) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
    @Test fun executionUsesRealReceiptAndContinuesSameSession() {
        val parent = Parent(); val assembly = Assembly(); val port = Port()
        val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { port }),
            toolAssembly = factory(assembly)))
        try {
            val result = batch.execute(parent.context(), listOf(ChildModelTask("one", "read once", ChildToolAccess.DEFAULT)))
            assertTrue(result is ExecutionReceipt.Confirmed)
            assertEquals(1, assembly.prepares.get()); assertEquals(1, assembly.executions.get())
            assertEquals(1, port.opens.get()); assertEquals(2, port.requests.get())
        } finally { batch.close() }
    }
    @Test fun executionContinuesBeyondFormerRoundLimitsInSameSession() {
        val parent = Parent(); val assembly = Assembly(); val port = Port(toolRounds = 128)
        val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { port }),
            toolAssembly = factory(assembly)))
        try {
            val result = batch.execute(parent.context(), listOf(ChildModelTask("one", "continue tools", ChildToolAccess.DEFAULT)))
            assertTrue(result is ExecutionReceipt.Confirmed)
            assertTrue((result as ExecutionReceipt.Confirmed).payload.contains("continued"))
            assertEquals(1, assembly.prepares.get()); assertEquals(128, assembly.executions.get())
            assertEquals(1, port.opens.get()); assertEquals(129, port.requests.get())
        } finally { batch.close() }
    }
    @Test fun reviewCannotAcquireToolsEvenWhenRequested() {
        val parent = Parent(); val creates = AtomicInteger(); val port = Port(toolsExpected = false, useTool = false)
        val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { port }),
            toolAssembly = ChildToolAssemblyFactory { _, _, _, _ -> creates.incrementAndGet(); error("review must not create tools") }))
        try {
            assertTrue(batch.execute(parent.context(), listOf(ChildModelTask("one", "review", ChildToolAccess.DEFAULT))) is ExecutionReceipt.Confirmed)
            assertEquals(0, creates.get()); assertEquals(1, port.requests.get())
        } finally { batch.close() }
    }
    @Test fun pauseDuringToolWaitsBeforeNextRequestWithoutReplayingReceipt() {
        val parent = Parent(); val assembly = Assembly(); val port = Port()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        assembly.beforeReceipt = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val pause = ChildTaskPauseControl {}
        val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { port }),
            toolAssembly = factory(assembly)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<ExecutionReceipt> { batch.executeQueued(parent.context(), listOf(parent.record()), pause) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); pause.pause(); release.countDown()
            await { pause.snapshot() == ChildTaskPauseState.PAUSED }
            assertEquals(1, port.requests.get()); assertEquals(1, assembly.executions.get())
            pause.resume()
            assertTrue(result.get(5, TimeUnit.SECONDS) is ExecutionReceipt.Confirmed)
            assertEquals(2, port.requests.get()); assertEquals(1, port.opens.get()); assertEquals(1, assembly.executions.get())
        } finally { release.countDown(); pause.resume(); batch.close(); executor.shutdownNow() }
    }
    @Test fun configurationPauseAfterResponseBlocksToolWithoutCancellingOrReplaying() {
        val parent = Parent(); val assembly = Assembly(); val port = Port()
        val pause = ChildTaskPauseControl {}
        port.beforeTool = { pause.pauseForConfiguration() }
        val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { port }),
            toolAssembly = factory(assembly)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<ExecutionReceipt> { batch.executeQueued(parent.context(), listOf(parent.record()), pause) }
            await { pause.snapshot() == ChildTaskPauseState.PAUSED }
            assertFalse(result.isDone); assertEquals(1, port.opens.get()); assertEquals(1, port.requests.get())
            assertEquals(0, assembly.executions.get()); assertEquals(1L, port.cancelled.count)
            assertEquals(ChildTaskControlReceipt.CONFIGURATION_REQUIRED, pause.resume())
            assertEquals(ChildTaskControlReceipt.RESUMED, pause.resumeWithAssignment())
            assertTrue(result.get(5, TimeUnit.SECONDS) is ExecutionReceipt.Confirmed)
            assertEquals(1, assembly.executions.get()); assertEquals(1, port.opens.get()); assertEquals(2, port.requests.get())
        } finally { pause.terminate(); batch.close(); executor.shutdownNow() }
    }
    @Test fun duplicateDrainDoesNotUnderflowAndUnknownRemainsPinned() {
        for (unknown in listOf(false, true)) {
            val parent = Parent(); val assembly = Assembly().apply { automaticDrain = false }; val port = Port(useTool = false)
            val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { port }),
                toolAssembly = factory(assembly)))
            val idle = CountDownLatch(1)
            try {
                assertTrue(batch.execute(parent.context(), listOf(ChildModelTask("one", "answer", ChildToolAccess.DEFAULT))) is ExecutionReceipt.Confirmed)
                assertTrue(assembly.observed.await(5, TimeUnit.SECONDS)); assertTrue(port.cancelled.await(5, TimeUnit.SECONDS))
                val observer = checkNotNull(assembly.observer)
                if (unknown) observer(ChildToolDrain.Unknown("native_close_unknown"))
                observer(ChildToolDrain.Drained); observer(ChildToolDrain.Drained)
                batch.closeWhenIdle { idle.countDown() }
                assertEquals(!unknown, idle.await(if (unknown) 100 else 5000, TimeUnit.MILLISECONDS))
            } finally { batch.close() }
        }
    }
    @Test fun completedToolRoundRebindsSameTaskOnlyAfterOldCancelReturns() {
        val parent = Parent(); val assembly = Assembly(); val old = Port(); val next = Port(useTool = false)
        val enqueues = AtomicInteger(); val events = CopyOnWriteArrayList<ChildTaskDiagnosticEvent>()
        val releaseCancel = CountDownLatch(1); val cancelConfirmed = AtomicBoolean(false)
        val journal = object : ChildTaskStore {
            override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) { enqueues.incrementAndGet() }
            override fun started(task: ChildTaskRecord) {}
            override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {}
            override fun list(conversationId: String, limit: Int) = emptyList<ChildTaskRecord>()
        }
        val supervisor = RetainedChildTasks(journal, diagnostics = ChildTaskDiagnostics { events += it })
        fun definition(selection: String, modelId: String, port: ModelPort) =
            ChildModelDefinition("pool", 1, null, latest = null, selection = selection, role = ChildModelRole.IMPLEMENTATION) { port }
                .routed("fixture-provider", modelId)
        val chosen = AtomicReference(definition("old", "old-model", old))
        val dynamic = ChildModelDefinition("pool", 1, null, latest = { chosen.get() }, selection = "old",
            role = ChildModelRole.IMPLEMENTATION) { old }
        val binding = supervisor.bind(parent.owner, parent.session, parent.authorization,
            mapOf("one" to dynamic), factory(assembly))
        assembly.beforeReceipt = { supervisor.pauseGroup(binding) }
        old.afterCancel = { check(releaseCancel.await(5, TimeUnit.SECONDS)); cancelConfirmed.set(true) }
        try {
            val task = (supervisor.submit(binding, parent.context(),
                listOf(ChildModelTask("one", "read once", ChildToolAccess.DEFAULT))) as ChildTaskAdmission.Accepted).tasks.single()
            await { supervisor.snapshots(binding).single().pauseState == ChildTaskPauseState.PAUSED }
            assertEquals(1, old.requests.get()); assertEquals(1, assembly.executions.get())
            assertEquals(1L, old.cancelled.count); assertEquals(0, next.opens.get())
            next.onOpen = { history ->
                assertTrue("old cancel must finish before replacement open", cancelConfirmed.get())
                val expanded = MessageHistory.expand(history)
                assertEquals(listOf("read-once"), expanded.flatMap { it.blocks }.filterIsInstance<MessageBlock.Tool>().map { it.result })
                assertTrue(expanded.any { it.runId.value == task.owner.childRunId && it.id.value == task.owner.assistantMessageId })
            }
            chosen.set(definition("new", "new-model", next))
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, task.owner))
            assertTrue(old.cancelled.await(5, TimeUnit.SECONDS))
            assertFalse(cancelConfirmed.get()); assertEquals(0, next.opens.get()); assertEquals(0, next.requests.get())
            releaseCancel.countDown()
            await { supervisor.snapshots(binding).single().settled }
            val result = supervisor.snapshots(binding).single().record
            assertEquals(task.owner, result.owner); assertEquals(ChildTaskStatus.COMPLETED, result.status)
            assertEquals("continued", result.text); assertEquals(1, enqueues.get())
            assertEquals(1, assembly.prepares.get()); assertEquals(1, assembly.executions.get())
            assertEquals(1, old.opens.get()); assertEquals(1, old.requests.get())
            assertEquals(1, next.opens.get()); assertEquals(1, next.requests.get())
            val requests = events.filter { it.phase == ChildTaskDiagnosticPhase.REQUEST_STARTED }
            assertEquals(listOf(ChildRequestModel("fixture-provider", "old-model"),
                ChildRequestModel("fixture-provider", "new-model")), requests.map { it.model })
            assertTrue(requests.all { it.owner == task.owner })
        } finally {
            releaseCancel.countDown()
            val idle = CountDownLatch(1); supervisor.closeWhenIdle { idle.countDown() }
            assertTrue(idle.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun failedTransportCancelKeepsExecutionPinnedAfterToolDrain() {
        val parent = Parent(); val assembly = Assembly(); val port = Port(useTool = false).apply { failCancel = true }
        val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { port }),
            toolAssembly = factory(assembly)))
        val idle = CountDownLatch(1)
        try {
            assertTrue(batch.execute(parent.context(), listOf(ChildModelTask("one", "answer", ChildToolAccess.DEFAULT))) is ExecutionReceipt.Confirmed)
            assertTrue(port.cancelled.await(5, TimeUnit.SECONDS)); assertTrue(assembly.observed.await(5, TimeUnit.SECONDS))
            batch.closeWhenIdle { idle.countDown() }
            assertFalse(idle.await(100, TimeUnit.MILLISECONDS))
        } finally { batch.close() }
    }
    @Test fun roleChangeAfterOpenPausesBeforeToolDispatchOrNextRequest() {
        val parent = Parent(); val assembly = Assembly(); val port = Port()
        var current = ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { port }
        val original = ChildModelDefinition("pool", 1, null, latest = { current }, selection = "original",
            role = ChildModelRole.IMPLEMENTATION) { port }
        port.beforeTool = { current = ChildModelDefinition("pool", 1) { port } }
        val batch = parent.bind(ChildModelBatch(mapOf("one" to original), toolAssembly = factory(assembly)))
        val done = CountDownLatch(1)
        val worker = Thread {
            try { batch.execute(parent.context(), listOf(ChildModelTask("one", "read", ChildToolAccess.DEFAULT))) }
            finally { done.countDown() }
        }
        try {
            worker.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (batch.snapshots().singleOrNull()?.pauseState != ChildTaskPauseState.PAUSED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(ChildTaskPauseState.PAUSED, batch.snapshots().single().pauseState)
            assertTrue(batch.snapshots().single().configurationPaused)
            assertEquals(0, assembly.executions.get()); assertEquals(1, port.opens.get()); assertEquals(1, port.requests.get())
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        } finally { batch.close(); assertTrue(done.await(5, TimeUnit.SECONDS)); worker.join(1000) }
    }
    @Test fun secondRetainFailureRollsBackEntireReservationBeforeEnqueue() {
        val parent = Parent(); val retains = AtomicInteger(); val releases = AtomicInteger(); val enqueues = AtomicInteger()
        val journal = object : ChildTaskStore {
            override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) { enqueues.incrementAndGet() }
            override fun started(task: ChildTaskRecord) {}
            override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {}
            override fun list(conversationId: String, limit: Int) = emptyList<ChildTaskRecord>()
        }
        val factory = object : ChildToolAssemblyFactory {
            override fun retain(): AutoCloseable {
                check(retains.incrementAndGet() != 2) { "workspace_closed" }
                return AutoCloseable { releases.incrementAndGet() }
            }
            override fun create(owner: ChildRunOwner, parentSession: SessionCapabilities, parentAuthorization: TaskAuthorization,
                access: ChildToolAccess): ChildToolAssembly = error("not admitted")
        }
        val supervisor = RetainedChildTasks(journal)
        try {
            val models = mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { error("not admitted") })
            val binding = supervisor.bind(parent.owner, parent.session, parent.authorization, models, factory)
            val result = supervisor.submit(binding, parent.context(), List(2) { ChildModelTask("one", "read", ChildToolAccess.DEFAULT) })
            assertEquals(ChildTaskAdmission.Rejected("child_tool_admission_unavailable"), result)
            assertEquals(2, retains.get()); assertEquals(1, releases.get()); assertEquals(0, enqueues.get())
            val idle = CountDownLatch(1); supervisor.closeWhenIdle { idle.countDown() }
            assertTrue(idle.await(5, TimeUnit.SECONDS))
        } finally { supervisor.close() }
    }

    @Test fun providerFailureAfterExecutingChildToolDoesNotClearUnknownBoundary() {
        val parent = Parent(); val assembly = Assembly(); val requests = AtomicInteger()
        val model = CatalogFixturePort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (requests.incrementAndGet() == 1)
                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("read"), "fixture", "{}")))
                else throw ModelRequestException(ModelFailure.RATE_LIMIT)
            }
            override fun cancel() {}
        } }
        val batch = parent.bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1, ChildModelRole.IMPLEMENTATION) { model }),
            toolAssembly = factory(assembly)))
        try {
            assertTrue(batch.execute(parent.context(), listOf(ChildModelTask("one", "read once", ChildToolAccess.DEFAULT))) is ExecutionReceipt.Unknown)
            assertEquals(1, assembly.executions.get()); assertEquals(2, requests.get())
        } finally { batch.close() }
    }

}
