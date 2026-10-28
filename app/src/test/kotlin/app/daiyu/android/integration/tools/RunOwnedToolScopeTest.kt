package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test

class RunOwnedToolScopeTest {
    private fun owner(): RunExecutionOwner {
        var captured: RunExecutionOwner? = null
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = Unit
            override fun cancel() = Unit
        } }, object : ConversationStore { override fun save(message: Message) = Unit }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = executor)) {}.run(Message(MessageId("a"), ConversationId("c"),
            RunId("r"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(captured)
    }
    private class Scope(owner: RunExecutionOwner, auth: TaskAuthorization) : RunOwnedToolScope(owner, auth) {
        var calls = 0
        var closes = 0
        val effect = WriteEffect("ordinary", "write", "owner", "exact")
        override val tools = listOf(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("ordinary_write", 1, "test", "ordinary", ToolEffect.WRITE, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = write(effect) { calls++; ExecutionReceipt.Confirmed("done") }
        }, object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("ordinary_read", 1, "test", "ordinary", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = diagnosticRead { ExecutionReceipt.Confirmed("diagnostic") }
        })
        fun creating() = beginCreation()
        fun created() = endCreation()
        fun hold() = retain(AutoCloseable { closes++ })
        fun hold(resource: AutoCloseable) = retain(resource)
    }
    private inner class Fixture {
        val owner = owner()
        val auth = TaskAuthorization("s", "r", emptySet())
        val scope = Scope(owner, auth)
        val dispatcher = ToolDispatcher(ToolRegistry().apply { scope.tools.forEach(::register) }.snapshot(),
            SessionCapabilities("s", setOf("ordinary")), ToolLedger(InMemoryToolLedgerStore()))
        fun call(id: String, write: Boolean = true, task: TaskAuthorization = auth, token: RunExecutionOwner = owner) =
            dispatcher.dispatchOwned(task, listOf(ToolInvocation(ToolCallId(id),
                if (write) "ordinary_write" else "ordinary_read", "{}")), token) { false }.single().outcome
    }
    @Test fun advertisingAndPrepareArePureAndExactWriteRunsInCentralDispatcher() {
        val f = Fixture()
        f.scope.tools.forEach { it.prepare(it.definition.schema.parse("{}")) }
        assertEquals(0, f.scope.calls)
        assertTrue(f.call("one") is DispatchOutcome.Confirmed)
        assertEquals(1, f.scope.calls)
        f.call("one")
        assertEquals(1, f.scope.calls)
        f.scope.close()
    }
    @Test fun equalFieldsDoNotReplaceOriginalOwnerOrAuthorization() {
        val f = Fixture()
        assertTrue(f.call("forged", task = TaskAuthorization("s", "r", emptySet())) is DispatchOutcome.NotExecuted)
        assertTrue(f.call("other", token = owner()) is DispatchOutcome.NotExecuted)
        assertEquals(0, f.scope.calls)
    }
    @Test fun unknownFencesNewWritesButKeepsOwnedDiagnostics() {
        val f = Fixture()
        f.scope.markUnknown()
        assertTrue(f.call("write") is DispatchOutcome.NotExecuted)
        assertTrue(f.call("read", write = false) is DispatchOutcome.Confirmed)
        assertEquals(0, f.scope.calls)
    }
    @Test fun cancelledOrClosedScopeDoesNotExecute() {
        val f = Fixture()
        f.scope.close()
        assertTrue(f.call("closed") is DispatchOutcome.NotExecuted)
        assertEquals(0, f.scope.calls)
    }
    @Test fun closeWaitsForLateCreationAndClosesItsRetainedHandle() {
        val f = Fixture()
        f.scope.creating()
        var drained: RunCatalogCloseResult? = null
        f.scope.observeClose { drained = it }
        assertNull(drained)
        f.scope.hold()
        assertEquals(1, f.scope.closes)
        f.scope.created()
        assertEquals(RunCatalogCloseResult.Drained, drained)
        f.scope.close()
        assertEquals(1, f.scope.closes)
    }
    @Test fun observeCloseWaitsForSynchronousCloseAndReportsItsFailure() {
        val f = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        f.scope.hold(AutoCloseable {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw IllegalStateException("close failed")
        })
        val stopping = Thread { f.scope.close() }.apply { start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val drained = java.util.concurrent.atomic.AtomicReference<RunCatalogCloseResult>()
        try {
            f.scope.observeClose { drained.set(it) }
            assertNull("a close request is not physical cleanup", drained.get())
        } finally {
            release.countDown()
            stopping.join(5000)
        }
        assertFalse(stopping.isAlive)
        assertTrue(drained.get() is RunCatalogCloseResult.Unknown)
    }

    @Test fun lateRetainedCloseFailureCannotReportDrained() {
        val f = Fixture()
        f.scope.creating()
        var drained: RunCatalogCloseResult? = null
        f.scope.observeClose { drained = it }
        try { f.scope.hold(AutoCloseable { throw IllegalStateException("late close failed") }) }
        finally { f.scope.created() }
        assertTrue(drained is RunCatalogCloseResult.Unknown)
    }

}
