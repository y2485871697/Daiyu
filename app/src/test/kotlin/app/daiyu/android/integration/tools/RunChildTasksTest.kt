package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RunChildTasksTest {
    private class Journal : ChildTaskStore {
        val enqueues = AtomicInteger()
        val rows = linkedMapOf<String, ChildTaskRecord>()
        var failAdmission = false
        @Synchronized override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            enqueues.incrementAndGet(); tasks.forEach { rows[it.owner.childRunId] = it }; check(!failAdmission)
        }
        @Synchronized override fun started(task: ChildTaskRecord) { rows[task.owner.childRunId] = task.copy(status = ChildTaskStatus.RUNNING) }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            rows[task.owner.childRunId] = task.copy(status = status, text = text, detail = detail)
        }
        @Synchronized override fun list(conversationId: String, limit: Int) = rows.values.filter { it.owner.conversationId == conversationId }.take(limit)
    }
    private val message = Message(MessageId("parent"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun owner(): RunExecutionOwner {
        var result: RunExecutionOwner? = null
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> = error("no tools")
        }
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }
        ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tools") },
            RunnerOptions(receiptBatchExecutor = executor)) {}.run(message, emptyList())
        return checkNotNull(result)
    }
    private class Held : ModelPort {
        val opened = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        override fun open(history: List<Message>): ModelSession = error("empty tools required")
        override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
            assertTrue(definitions.isEmpty()); opened.incrementAndGet()
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("text", "retained result"))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
    }
    private fun call(id: String = "call", profile: String = "review") = ToolInvocation(ToolCallId(id), RunChildTasks.TOOL_NAME,
        """{"tasks":[{"profile_id":"$profile","prompt":"Review this task"}]}""")
    private fun close(supervisor: RetainedChildTasks, model: Held) {
        model.release.countDown(); val done = CountDownLatch(1); supervisor.closeWhenIdle { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test fun realDispatcherReturnsDurableAdmissionAndParentCloseDoesNotCancelChild() {
        val journal = Journal(); val model = Held(); val owner = owner()
        val caps = SessionCapabilities("session", setOf(RunChildTasks.CAPABILITY)); val auth = TaskAuthorization("session", "run")
        val supervisor = RetainedChildTasks(journal)
        val binding = supervisor.bind(owner, caps, auth, mapOf("review" to ChildModelDefinition("pool", 1) { model }))
        val adapter = RunChildTasks(supervisor, binding, setOf("review")); val ledger = InMemoryToolLedgerStore()
        val dispatcher = ToolDispatcher(ToolRegistry().register(adapter.tool()).snapshot(), caps, ToolLedger(ledger))
        try {
            val result = dispatcher.dispatchOwned(auth, listOf(call()), owner).single().outcome as DispatchOutcome.Confirmed
            val payload = JSONObject(result.payload)
            assertTrue(payload.getBoolean("accepted")); assertFalse(payload.getBoolean("completed"))
            assertEquals(1, payload.getJSONArray("tasks").length()); assertEquals(1, journal.enqueues.get())
            assertTrue(model.entered.await(3, TimeUnit.SECONDS)); adapter.close()
            assertEquals(1L, model.cancelled.count)
            val replay = dispatcher.dispatchOwned(auth, listOf(call()), owner).single().outcome as DispatchOutcome.Confirmed
            assertTrue(replay.replayed); assertEquals(result.payload, replay.payload)
            assertTrue(dispatcher.dispatchOwned(auth, listOf(call("new")), owner).single().outcome is DispatchOutcome.NotExecuted)
            assertEquals(1, model.opened.get()); assertEquals(1, journal.enqueues.get())
            model.release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (supervisor.snapshots(binding).any { !it.settled } && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(supervisor.snapshots(binding).all { it.settled })
        } finally { close(supervisor, model) }
        assertEquals(ChildTaskStatus.COMPLETED, journal.list("conversation").single().status)
        assertEquals("retained result", journal.list("conversation").single().text)
    }

    @Test fun wholeBatchValidationAndExplicitCapabilityPrecedeJournalAndNetwork() {
        val journal = Journal(); val model = Held(); val owner = owner()
        val caps = SessionCapabilities("session", setOf(RunChildTasks.CAPABILITY)); val auth = TaskAuthorization("session", "run")
        val supervisor = RetainedChildTasks(journal)
        val binding = supervisor.bind(owner, caps, auth, mapOf("review" to ChildModelDefinition("pool", 1) { model }))
        val registry = ToolRegistry().register(RunChildTasks(supervisor, binding, setOf("review")).tool()).snapshot()
        val ledger = InMemoryToolLedgerStore()
        try {
            val dispatcher = ToolDispatcher(registry, caps, ToolLedger(ledger))
            assertTrue(dispatcher.dispatchOwned(auth, listOf(call(), call("bad", "unknown")), owner).all { it.outcome is DispatchOutcome.NotExecuted })
            val disabled = ToolDispatcher(registry, SessionCapabilities("session", emptySet()), ToolLedger(ledger))
            assertTrue(disabled.dispatchOwned(auth, listOf(call()), owner).single().outcome is DispatchOutcome.NotExecuted)
            assertEquals(0, journal.enqueues.get()); assertEquals(0, model.opened.get()); assertTrue(ledger.transaction { it.toList() }.isEmpty())
        } finally { close(supervisor, model) }
    }

    @Test fun unknownAdmissionNeverCreatesModelOrReplaysLedgerEntry() {
        val journal = Journal().apply { failAdmission = true }; val model = Held(); val owner = owner()
        val caps = SessionCapabilities("session", setOf(RunChildTasks.CAPABILITY)); val auth = TaskAuthorization("session", "run")
        val supervisor = RetainedChildTasks(journal)
        val binding = supervisor.bind(owner, caps, auth, mapOf("review" to ChildModelDefinition("pool", 1) { model }))
        val dispatcher = ToolDispatcher(ToolRegistry().register(RunChildTasks(supervisor, binding, setOf("review")).tool()).snapshot(), caps, ToolLedger(InMemoryToolLedgerStore()))
        try {
            repeat(2) { assertTrue(dispatcher.dispatchOwned(auth, listOf(call()), owner).single().outcome is DispatchOutcome.Unknown) }
            assertEquals(1, journal.enqueues.get()); assertEquals(0, model.opened.get())
        } finally { close(supervisor, model) }
    }

    @Test fun schemaDoesNotExposeToolsWritesOrRecursiveDelegation() {
        val model = Held(); val owner = owner(); val caps = SessionCapabilities("session", setOf(RunChildTasks.CAPABILITY))
        val auth = TaskAuthorization("session", "run"); val supervisor = RetainedChildTasks(Journal())
        val binding = supervisor.bind(owner, caps, auth, mapOf("review" to ChildModelDefinition("pool", 1) { model }))
        try {
            val tool = RunChildTasks(supervisor, binding, setOf("review")).tool()
            assertEquals(ToolEffect.READ_ONLY, tool.definition.effect)
            assertEquals("delegation.text.background_review", tool.definition.capability)
            assertThrows(IllegalArgumentException::class.java) { tool.definition.schema.parse("""{"tasks":[],"tools":["write_file"]}""") }
        } finally { close(supervisor, model) }
    }
}
