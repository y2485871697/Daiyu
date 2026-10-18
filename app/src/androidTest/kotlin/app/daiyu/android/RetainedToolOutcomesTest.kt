package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.storage.*
import app.daiyu.android.ui.RetainedToolOutcomes
import app.daiyu.android.ui.screens.chat.conversation.*

/** The flag has already crossed Dispatcher -> Runner -> durable ToolDisposition; do not parse JSON. */
@Suppress("DEPRECATION")
class RetainedToolOutcomesTest : android.test.AndroidTestCase() {
    private lateinit var scoped: android.content.Context
    private lateinit var database: ConversationDatabase
    override fun setUp() {
        super.setUp(); scoped = testContext(context, "tool-outcome-projection-${java.util.UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
    }
    override fun tearDown() {
        try { database.close(); scoped.deleteDatabase("conversations.db") } finally { super.tearDown() }
    }
    private fun run(receipts: List<DispatchOutcome>, key: String): Message {
        val conversation = database.create("default")
        val user = Message(MessageId("user-$key"), conversation.id, RunId(key), Message.USER_ROLE,
            listOf(MessageBlock.Text("user", "read")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("assistant-$key"), role = Message.ASSISTANT_ROLE,
            status = RunStatus.RUNNING, blocks = emptyList())
        val calls = receipts.indices.map { ToolInvocation(ToolCallId("call-$it"), "read", "{}") }
        var round = 0
        SQLiteRuntimeStore.acquire(database).use { runtime ->
            runtime.recoverPending()
            database.send(user, assistant)
            val runner = ChatRunner(ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (round++ == 0) calls.forEach { sink(ModelEvent.ToolReady(it)) }
                    else sink(ModelEvent.TextFinal("answer", "done"))
                }
                override fun cancel() {}
            } }, database, BatteryTool { error("not a legacy call") }, RunnerOptions(
                runtimeStore = runtime, receiptBatchExecutor = ReceiptBatchToolExecutor { _, batch, _ ->
                    batch.mapIndexed { index, call -> ToolDispatchResult(call.id, receipts[index]) }
                })) {}
            return runner.run(assistant, listOf(user))
        }
    }
    private fun project(message: Message) = ChatTurnMapper.map(listOf(message),
        toolState = RetainedToolOutcomes.stateReader(database, listOf(message))).single() as ChatTurnUi.Assistant

    fun testStructuredConfirmedErrorUsesDurableFlagNotModelJsonAndSurvivesReopen() {
        val json = "{\"ok\":false,\"error\":\"permission_denied\"}"
        val done = run(listOf(DispatchOutcome.Confirmed(json, true), DispatchOutcome.Confirmed(json, false)), "confirmed")
        assertEquals(RunStatus.COMPLETED, done.status)
        database.close(); database = ConversationDatabase(scoped)
        val stored = database.messages(done.conversationId).last().second
        val ui = project(stored)
        assertEquals(listOf(WorkStepState.FAILED, WorkStepState.OK), ui.workProcess!!.segments.flatMap { it.steps }.map { it.state })
        assertEquals("失败", ui.workProcess.summary)
        val tool = stored.blocks.filterIsInstance<MessageBlock.Tool>().first()
        val reader = RetainedToolOutcomes.reader(database, listOf(stored))
        assertEquals(true, reader(stored, tool))
        assertNull(reader(stored.copy(role = Message.USER_ROLE), tool))
        assertNull(reader(stored.copy(conversationId = ConversationId("other-chat")), tool))
        assertNull(reader(stored, tool.copy(invocation = tool.invocation.copy(id = ToolCallId("other-call")))))
        assertNull(reader(stored, tool.copy(invocation = tool.invocation.copy(name = "other-tool"))))
        assertNull(reader(stored.copy(runId = RunId("other-run")), tool))
        assertNull(reader(stored, tool.copy(invocation = tool.invocation.copy(arguments = "{\"changed\":true}"))))
        assertNull(reader(stored, tool.copy(result = "changed result")))
        // A model/historical JSON string without matching trusted metadata stays unknown.
        val historicalId = MessageId("untrusted-history")
        val historical = stored.copy(id = historicalId,
            historySegments = stored.historySegments.map { segment ->
                if (segment.role == Message.ASSISTANT_ROLE) segment.copy(messageId = historicalId) else segment
            })
        assertEquals(WorkStepState.UNKNOWN, project(historical).workProcess!!.segments.first().steps.first().state)
    }

    fun testUnknownIsNotTurnedIntoConfirmedErrorOrSuccess() {
        val stopped = run(listOf(DispatchOutcome.Unknown("lost_receipt")), "unknown")
        val tool = stopped.blocks.filterIsInstance<MessageBlock.Tool>().single()
        assertNull(RetainedToolOutcomes.reader(database, listOf(stopped))(stopped, tool))
        val baseline = ChatTurnMapper.map(listOf(stopped)).single() as ChatTurnUi.Assistant
        assertEquals(baseline, project(stopped))
        assertEquals(WorkStepState.UNKNOWN, project(stopped).workProcess!!.segments.flatMap { it.steps }.single().state)
        database.close(); database = ConversationDatabase(scoped)
        assertEquals(WorkStepState.UNKNOWN, project(database.messages(stopped.conversationId).last().second)
            .workProcess!!.segments.flatMap { it.steps }.single().state)
        val snapshot = checkNotNull(SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, stopped.id.value))
        assertEquals(ToolDisposition.UNKNOWN, snapshot.tools.single().disposition)
    }
    fun testNotExecutedRemainsNotExecutedAfterReopen() {
        val done = run(listOf(DispatchOutcome.NotExecuted("cancelled_before_dispatch")), "not-executed")
        database.close(); database = ConversationDatabase(scoped)
        val stored = database.messages(done.conversationId).last().second
        val step = project(stored).workProcess!!.segments.flatMap { it.steps }.single()
        assertEquals(WorkStepState.NOT_EXECUTED, step.state)
        val tool = stored.blocks.filterIsInstance<MessageBlock.Tool>().single()
        assertNull(RetainedToolOutcomes.reader(database, listOf(stored))(stored, tool))
    }

}
