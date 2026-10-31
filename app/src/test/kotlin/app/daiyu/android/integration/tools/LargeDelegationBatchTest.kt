package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LargeDelegationBatchTest {
    private val parent = Message(MessageId("parent"), ConversationId("conversation"), RunId("parent-run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val owner = runnerOwner(parent)
    private val session = SessionCapabilities("session", setOf(RunChildModels.CAPABILITY, RunChildTasks.CAPABILITY))
    private val authorization = TaskAuthorization(session.sessionId, parent.runId.value)
    private class Journal : ChildTaskStore {
        val enqueues = AtomicInteger()
        private val rows = linkedMapOf<String, ChildTaskRecord>()
        @Synchronized override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            enqueues.incrementAndGet(); tasks.forEach { rows[it.owner.childRunId] = it }
        }
        @Synchronized override fun started(task: ChildTaskRecord) { rows[task.owner.childRunId] = task.copy(status = ChildTaskStatus.RUNNING) }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            rows[task.owner.childRunId] = task.copy(status = status, text = text, detail = detail)
        }
        @Synchronized override fun list(conversationId: String, limit: Int) = rows.values.filter { it.owner.conversationId == conversationId }.take(limit)
    }
    private fun invocation(name: String, count: Int = 16, invalidLast: Boolean = false) = ToolInvocation(ToolCallId("call"), name,
        (0 until count).joinToString(",", "{\"tasks\":[", "]}") { index ->
            "{\"profile_id\":\"${if (invalidLast && index == count - 1) "missing" else "review"}\",\"prompt\":\"task $index\"}"
        })
    private fun dispatcher(tool: RegisteredTool, ledger: InMemoryToolLedgerStore) =
        ToolDispatcher(ToolRegistry().apply { register(tool) }.snapshot(), session, ToolLedger(ledger))
    private fun model(journal: Journal, opened: AtomicInteger, active: AtomicInteger, peak: AtomicInteger,
        entered: CountDownLatch, release: CountDownLatch) = object : ModelPort {
        override fun open(history: List<Message>): ModelSession = error("explicit child tools required")
        override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
            assertTrue(definitions.isEmpty()); assertEquals(1, journal.enqueues.get()); opened.incrementAndGet()
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    val n = active.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; entered.countDown()
                    try {
                        assertTrue("all configured slots enter", entered.await(5, TimeUnit.SECONDS))
                        assertTrue("release child", release.await(5, TimeUnit.SECONDS))
                        sink(ModelEvent.TextFinal("answer", "done"))
                    } finally { active.decrementAndGet() }
                }
                override fun cancel() {}
            }
        }
    }
    @Test fun sixteenSynchronousTasksUseOneCallAndRespectOnlyTheConfiguredPoolLimit() {
        for (limit in listOf(1, 2, 12, 0)) {
            val journal = Journal(); val ledger = InMemoryToolLedgerStore()
            val opened = AtomicInteger(); val active = AtomicInteger(); val peak = AtomicInteger()
            val expected = if (limit == 0) 16 else limit
            val entered = CountDownLatch(expected); val release = CountDownLatch(0)
            val port = model(journal, opened, active, peak, entered, release)
            val batch = ChildModelBatch(mapOf("review" to ChildModelDefinition("pool", limit) { port }), store = journal)
            val adapter = RunChildModels(batch, setOf("review")); adapter.bind(owner, session, authorization)
            val dispatch = dispatcher(adapter.tool(), ledger); val call = invocation(RunChildModels.TOOL_NAME)
            try {
                val first = dispatch.dispatchOwned(authorization, listOf(call), owner).single().outcome as DispatchOutcome.Confirmed
                assertEquals(16, JSONObject(first.payload).getJSONArray("results").length())
                assertEquals(16, opened.get()); assertEquals(expected, peak.get())
                assertEquals((0 until 16).toList(), journal.list(parent.conversationId.value, 100).map { it.index })
                val replay = dispatch.dispatchOwned(authorization, listOf(call), owner).single().outcome as DispatchOutcome.Confirmed
                assertTrue(replay.replayed); assertEquals(first.payload, replay.payload)
                assertEquals(16, opened.get()); assertEquals(1, journal.enqueues.get())
                assertEquals(1, ledger.transaction { it.toList() }.size)
            } finally { adapter.close() }
        }
    }
    @Test fun sixteenBackgroundTasksPersistTogetherAndZeroDoesNotHideAnEightWorkerCeiling() {
        for (limit in listOf(2, 12, 0)) {
            val journal = Journal(); val ledger = InMemoryToolLedgerStore(); val supervisor = RetainedChildTasks(journal)
            val opened = AtomicInteger(); val active = AtomicInteger(); val peak = AtomicInteger()
            val expected = if (limit == 0) 16 else limit
            val entered = CountDownLatch(expected); val release = CountDownLatch(1)
            val port = model(journal, opened, active, peak, entered, release)
            val binding = supervisor.bind(owner, session, authorization, mapOf("review" to ChildModelDefinition("pool", limit) { port }))
            val adapter = RunChildTasks(supervisor, binding, setOf("review"))
            val dispatch = dispatcher(adapter.tool(), ledger); val call = invocation(RunChildTasks.TOOL_NAME)
            val drained = CountDownLatch(1)
            try {
                val first = dispatch.dispatchOwned(authorization, listOf(call), owner).single().outcome as DispatchOutcome.Confirmed
                val payload = JSONObject(first.payload)
                assertTrue(payload.getBoolean("accepted")); assertFalse(payload.getBoolean("completed"))
                assertEquals(16, payload.getJSONArray("tasks").length()); assertEquals(1, journal.enqueues.get())
                assertTrue("configured concurrency > eight", entered.await(5, TimeUnit.SECONDS))
                assertEquals(expected, peak.get())
                val replay = dispatch.dispatchOwned(authorization, listOf(call), owner).single().outcome as DispatchOutcome.Confirmed
                assertTrue(replay.replayed); assertEquals(first.payload, replay.payload); assertEquals(1, journal.enqueues.get())
                adapter.close(); release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (supervisor.snapshots(binding).any { !it.settled } && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(supervisor.snapshots(binding).all { it.settled })
                supervisor.closeWhenIdle { drained.countDown() }
                assertTrue(drained.await(5, TimeUnit.SECONDS)); assertEquals(16, opened.get())
                assertTrue(journal.list(parent.conversationId.value, 100).all { it.status == ChildTaskStatus.COMPLETED })
            } finally { release.countDown(); adapter.close(); supervisor.closeWhenIdle { drained.countDown() }; drained.await(5, TimeUnit.SECONDS) }
        }
    }
    @Test fun schemasDoNotReplaceEightWithAnotherCountLimitAndLateInvalidTasksAreAtomic() {
        val journal = Journal(); val opened = AtomicInteger()
        val models = mapOf("review" to ChildModelDefinition("pool", 0) { opened.incrementAndGet(); error("must not create") })
        val batch = ChildModelBatch(models); val synchronous = RunChildModels(batch, models.keys)
        synchronous.bind(owner, session, authorization)
        val supervisor = RetainedChildTasks(journal); val binding = supervisor.bind(owner, session, authorization, models)
        val background = RunChildTasks(supervisor, binding, models.keys)
        try {
            for (tool in listOf(synchronous.tool(), background.tool())) {
                val array = JSONObject(tool.definition.schema.jsonSchema()).getJSONObject("properties").getJSONObject("tasks")
                assertFalse(array.has("maxItems")); assertEquals(1, array.getInt("minItems"))
                assertEquals(65, tool.definition.schema.parse(invocation(tool.definition.name, 65).arguments).array("tasks").size)
                val ledger = InMemoryToolLedgerStore(); val dispatch = dispatcher(tool, ledger)
                val result = dispatch.dispatchOwned(authorization, listOf(invocation(tool.definition.name, invalidLast = true)), owner)
                assertTrue(result.single().outcome is DispatchOutcome.NotExecuted)
                assertTrue(ledger.transaction { it.toList() }.isEmpty())
            }
            assertEquals(0, opened.get()); assertEquals(0, journal.enqueues.get())
        } finally { synchronous.close(); background.close(); supervisor.close() }
    }
}
