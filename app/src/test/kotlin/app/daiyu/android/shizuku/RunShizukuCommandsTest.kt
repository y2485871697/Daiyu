package app.daiyu.android.shizuku

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RunShizukuCommandsTest {
    private open class Backend : ShizukuBackend {
        override val identity = ShizukuIdentity(2000, "service", "permission")
        val commands = mutableListOf<ShizukuCommand>()
        var closeResult: ShizukuCloseResult = ShizukuCloseResult.Drained
        var closes = 0
        var output = "result".toByteArray()
        var exit = 0
        var unknown = false
        override fun execute(command: ShizukuCommand): ShizukuDispatch {
            commands.add(command)
            return if (unknown) ShizukuDispatch.Unknown(command.requestId, "lost_receipt")
            else ShizukuDispatch.Dispatched(ShizukuCommandHandle(command.requestId, identity))
        }
        override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int): ShizukuCommandPage {
            val end = minOf(output.size, offset + limit)
            return ShizukuCommandPage(ShizukuOutcome.Completed(exit), output.copyOfRange(offset, end),
                offset, end, output.size, false, true)
        }
        override fun cancel(handle: ShizukuCommandHandle) = ShizukuOutcome.Unknown("cancel_requested")
        override fun close() { closes++ }
        override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) { close(); onResult(closeResult) }
    }

    private fun owner(): RunExecutionOwner {
        var result: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        runner.run(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(result)
    }

    private class Fixture(val backend: Backend = Backend(), enabled: Boolean = true, approved: Boolean = true,
        bind: Boolean = true, val token: RunExecutionOwner) : AutoCloseable {
        val resources = RunShizukuCommands(backend)
        val capabilities = SessionCapabilities("session", if (enabled) setOf(RunShizukuCommands.CAPABILITY) else emptySet())
        val grant = RunShizukuCommands.writeEffect(2000, "printf result")
        val task = TaskAuthorization("session", "run", if (approved) setOf(grant) else emptySet())
        val store = InMemoryToolLedgerStore()
        val dispatcher = ToolDispatcher(ToolRegistry().register(resources.tool()).snapshot(), capabilities, ToolLedger(store))
        init { if (bind) resources.bind(token, capabilities, task) }
        fun call(id: String = "provider-call-1", script: String = "printf result", timeout: Long? = null) =
            ToolInvocation(ToolCallId(id), RunShizukuCommands.TOOL_NAME,
                JSONObject().put("script", script).also { if (timeout != null) it.put("timeout_ms", timeout) }.toString())
        fun dispatch(call: ToolInvocation = call(), auth: TaskAuthorization = task,
            owner: RunExecutionOwner = token, cancelled: () -> Boolean = { false }) =
            dispatcher.dispatchOwned(auth, listOf(call), owner, cancelled).single().outcome
        override fun close() = resources.close()
    }

    @Test fun disabledCapabilityAndMissingExactGrantNeverExecute() {
        Fixture(enabled = false, token = owner()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("capability_disabled"), f.dispatch())
            assertTrue(f.backend.commands.isEmpty())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
        Fixture(approved = false, token = owner()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("task_write_not_authorized"), f.dispatch())
            assertTrue(f.backend.commands.isEmpty())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun scriptTimeoutAndUidArePartOfExactEffect() {
        Fixture(token = owner()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("task_write_not_authorized"), f.dispatch(f.call(script = "printf different")))
            assertEquals(DispatchOutcome.NotExecuted("task_write_not_authorized"), f.dispatch(f.call(timeout = 30_001)))
            assertNotEquals(f.grant, RunShizukuCommands.writeEffect(0, "printf result"))
            assertTrue(f.backend.commands.isEmpty())
        }
    }

    @Test fun copiedAuthorizationAndOwnerAreRejected() {
        Fixture(token = owner()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"),
                f.dispatch(auth = TaskAuthorization("session", "run", setOf(f.grant))))
            assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"), f.dispatch(f.call("other-owner"), owner = owner()))
            assertTrue(f.backend.commands.isEmpty())
        }
    }

    @Test fun unboundRunAndUnownedDispatchNeverExecute() {
        Fixture(bind = false, token = owner()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("shizuku_run_not_bound"), f.dispatch())
            assertTrue(f.backend.commands.isEmpty())
        }
        Fixture(token = owner()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("tool_execution_context_required"),
                f.dispatcher.dispatch(f.task, listOf(f.call())).single().outcome)
        }
    }

    @Test fun successfulOutputAndLedgerReplayExecuteOnlyOnce() {
        Fixture(token = owner()).use { f ->
            val result = f.dispatch() as DispatchOutcome.Confirmed
            assertFalse(result.isError)
            assertEquals("result", JSONObject(result.payload).getString("output"))
            assertEquals(2000, JSONObject(result.payload).getInt("uid"))
            assertEquals("shizuku", JSONObject(result.payload).getString("backend"))
            assertTrue((f.dispatch() as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, f.backend.commands.size)
            assertTrue(f.backend.commands.single().requestId.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test fun nonzeroExitAndUtf8SplitAcrossPagesRemainExact() {
        val backend = Backend().apply { exit = 7; output = ("x".repeat(16383) + "鱼").toByteArray() }
        Fixture(backend, token = owner()).use { f ->
            val result = f.dispatch() as DispatchOutcome.Confirmed
            assertTrue(result.isError)
            val payload = JSONObject(result.payload)
            assertEquals(7, payload.getInt("exit_code"))
            assertEquals("x".repeat(16383) + "鱼", payload.getString("output"))
        }
    }

    @Test fun unknownIsNotReplayedAndClosesRunResources() {
        Fixture(Backend().apply { unknown = true }, token = owner()).use { f ->
            assertTrue(f.dispatch() is DispatchOutcome.Unknown)
            assertTrue(f.dispatch() is DispatchOutcome.Unknown)
            assertEquals(1, f.backend.commands.size)
            assertEquals(1, f.backend.closes)
        }
    }

    @Test fun closedRunRejectsNewCallWithoutBackendExecution() {
        Fixture(token = owner()).use { f ->
            f.resources.close()
            assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch())
            assertTrue(f.backend.commands.isEmpty())
        }
    }

    @Test fun entireBatchIsValidatedBeforeFirstEffect() {
        Fixture(token = owner()).use { f ->
            val bad = ToolInvocation(ToolCallId("bad"), RunShizukuCommands.TOOL_NAME, """{"script":"printf result","uid":0}""")
            val outcomes = f.dispatcher.dispatchOwned(f.task, listOf(f.call(), bad), f.token)
            assertTrue(outcomes.all { it.outcome == DispatchOutcome.NotExecuted("invalid_arguments") })
            assertTrue(f.backend.commands.isEmpty())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun cancellationBeforeDispatchDoesNotReserveOrExecute() {
        Fixture(token = owner()).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("cancelled_before_execution"), f.dispatch(cancelled = { true }))
            assertTrue(f.backend.commands.isEmpty())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun closeAcknowledgementWaitsForLocalCallAndRemoteResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = AtomicReference<((ShizukuCloseResult) -> Unit)?>()
        val backend = object : Backend() {
            override fun execute(command: ShizukuCommand): ShizukuDispatch {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.execute(command)
            }
            override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) { close(); complete.set(onResult) }
        }
        val f = Fixture(backend, token = owner())
        val acknowledgements = AtomicInteger()
        val outcome = AtomicReference<DispatchOutcome>()
        val worker = Thread { outcome.set(f.dispatch()) }
        try {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.resources.closeWithResult { assertSame(ShizukuCloseResult.Drained, it); acknowledgements.incrementAndGet() }
            checkNotNull(complete.get())(ShizukuCloseResult.Drained)
            assertEquals(0, acknowledgements.get())
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(outcome.get() is DispatchOutcome.Unknown)
            assertEquals(1, acknowledgements.get())
        } finally { release.countDown(); worker.join(5000); f.close() }
    }

    @Test fun unknownCleanupIsReportedInsteadOfFalseDrain() {
        val unknown = ShizukuCloseResult.Unknown("permission_revoked")
        Fixture(Backend().apply { closeResult = unknown }, token = owner()).use { f ->
            var result: ShizukuCloseResult? = null
            f.resources.closeWithResult { result = it }
            assertSame(unknown, result)
        }
    }
}
