package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.TerminalConfiguration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RunCommandToolsTest {
    private class Fixture {
        val owner = runnerOwner(Message(MessageId("m"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val task = TaskAuthorization("s", "r")
        var approved = true
        var prompts = 0
        var created = 0
        var executions = 0
        var closes = 0
        var drains = 0
        var onCreate: () -> Unit = {}
        var terminated = true
        var failExecute = false
        var failClose = false
        var cancelled = false
        var onOpen: () -> Unit = {}
        var execution = "executed"
        val leaf = RunCommandTools(TerminalConfiguration(workingDirectory = File("/fixed")), owner, task, null,
            approve = { context, _, disclosure ->
                assertSame(task, context.authorization); assertTrue(disclosure.endsWith("echo exact"))
                prompts++; approved
            }, create = { _, _ ->
                created++; onCreate()
                object : CommandTransport {
                    override fun execute(name: String, arguments: JSONObject): JSONObject {
                        if (name == "terminal_open") {
                            onOpen()
                            return JSONObject().put("ok", true).put("session_id", "session")
                        }
                        executions++
                        if (failExecute) throw java.io.IOException("sent but receipt lost")
                        assertEquals("echo exact", arguments.getString("command"))
                        return JSONObject().put("ok", true).put("execution", execution)
                            .put("job_id", "00000000-0000-0000-0000-000000000001").put("stdout", "exact\n")
                    }
                    override fun read(id: String, offset: Long, limit: Int) = JSONObject().put("ok", true).put("execution", "executed")
                    override fun close() { closes++; if (failClose) throw java.io.IOException("close failed") }
                    override fun awaitTermination(timeoutMillis: Long): Boolean { drains++; return terminated }
                }
            })
        private val registry = ToolRegistry().also { registry -> leaf.tools().forEach { registry.register(it) } }
        private val dispatcher = ToolDispatcher(registry.snapshot(), SessionCapabilities("s", setOf(RunCommandTools.CAPABILITY)),
            ToolLedger(InMemoryToolLedgerStore()))
        fun dispatch(id: String = "call", environment: String = "android", timeout: Long = 30000) = dispatcher.dispatchOwned(task,
            listOf(ToolInvocation(ToolCallId(id), "terminal_exec", JSONObject().put("command", "echo exact")
                .put("environment", environment).put("timeout_ms", timeout).toString())), owner) { cancelled }.single().outcome
    }
    @Test fun consentExecutesExactBytesOnceAndConfirmedReplayDoesNotPrompt() {
        val fixture = Fixture()
        assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
        assertTrue((fixture.dispatch("new-id") as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, fixture.prompts); assertEquals(1, fixture.executions)
        assertTrue(fixture.task.allowedWrites.isEmpty())
    }
    @Test fun denialAndUnavailableLinuxNeverConstructBackend() {
        val denied = Fixture(); denied.approved = false
        assertTrue(denied.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, denied.created); assertEquals(0, denied.executions)
        val linux = Fixture()
        assertTrue(linux.dispatch(environment = "linux") is DispatchOutcome.NotExecuted)
        assertEquals(0, linux.prompts); assertEquals(0, linux.created)
    }
    @Test fun cancellationDuringOpenDoesNotSendCommand() {
        val fixture = Fixture(); fixture.onOpen = { fixture.cancelled = true }
        assertTrue(fixture.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.created); assertEquals(0, fixture.executions)
    }
    @Test fun unknownThenEquivalentNewCallRemainsUnknownWithoutNewConsentOrExecution() {
        val fixture = Fixture(); fixture.execution = "unknown"
        assertTrue(fixture.dispatch() is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("new-id") is DispatchOutcome.Unknown)
        assertEquals(1, fixture.prompts); assertEquals(1, fixture.executions)
    }
    @Test fun changedTimeoutNeedsItsOwnExactConsentAndCommand() {
        val fixture = Fixture()
        assertTrue(fixture.dispatch(timeout = 30000) is DispatchOutcome.Confirmed)
        val next = fixture.dispatch("longer", timeout = 60000) as DispatchOutcome.Confirmed
        assertFalse(next.replayed)
        assertEquals(2, fixture.prompts); assertEquals(2, fixture.executions)
    }
    @Test fun closeDuringOpenNeverSendsCommandAndDrainObservesBackend() {
        val fixture = Fixture(); fixture.onOpen = { fixture.leaf.close() }
        assertTrue(fixture.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, fixture.executions); assertEquals(1, fixture.closes)
        val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
        fixture.leaf.observeClose { result.complete(it) }
        assertEquals(RunCatalogCloseResult.Drained, result.get(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, fixture.drains)
    }
    @Test fun closingWhileFactoryIsBlockedNeverReportsDrainedAndLateHandleIsClosed() {
        val fixture = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fixture.onCreate = { entered.countDown(); check(release.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        val executing = java.util.concurrent.FutureTask { fixture.dispatch() }
        Thread(executing).start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); fixture.leaf.close()
            val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
            fixture.leaf.observeClose { result.complete(it) }
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is RunCatalogCloseResult.Unknown)
        } finally { release.countDown() }
        assertTrue(executing.get(2, java.util.concurrent.TimeUnit.SECONDS) is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.closes); assertEquals(0, fixture.executions)
    }
    @Test fun unconfirmedPhysicalTerminationNeverReportsDrained() {
        val fixture = Fixture(); fixture.terminated = false
        assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed); fixture.leaf.close()
        val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
        fixture.leaf.observeClose { result.complete(it) }
        assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is RunCatalogCloseResult.Unknown)
    }

    @Test fun thrownExecutionFencesDifferentEffectsBeforeOutcomeFeedback() {
        val fixture = Fixture(); fixture.failExecute = true
        assertTrue(fixture.dispatch() is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("different", timeout = 60000) is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.prompts); assertEquals(1, fixture.executions)
    }
    @Test fun closeFailureIsRetainedEvenWhenTerminationProbeWouldSucceed() {
        val fixture = Fixture(); assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
        fixture.failClose = true
        try { fixture.leaf.close(); fail("close failure disappeared") } catch (_: java.io.IOException) {}
        val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
        fixture.leaf.observeClose { result.complete(it) }
        assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is RunCatalogCloseResult.Unknown)
    }

}
