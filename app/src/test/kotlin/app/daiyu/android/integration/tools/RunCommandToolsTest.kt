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
    private class Fixture(private val automaticConsent: Boolean = false, linuxState: app.daiyu.android.terminal.TerminalLinuxState? = null) {
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
        var ownerCurrent = true
        var duringConsent: () -> Unit = {}
        val effects = mutableListOf<WriteEffect>()
        val executedCommands = mutableListOf<String>()
        private val gate = CommandApprovalGate({ fail("automatic consent must not post UI") }, { ownerCurrent && it === owner })
        val leaf = RunCommandTools(TerminalConfiguration(workingDirectory = File("/fixed")), owner, task, null,
            approve = { context, effect, disclosure ->
                assertSame(task, context.authorization)
                effects.add(effect)
                duringConsent()
                if (automaticConsent) gate.await(CommandApprovalRequest(owner, context.invocation.id.value,
                    effect, disclosure), context.cancelled)
                else { prompts++; approved }
            }, linuxState = linuxState, create = { _, _ ->
                created++; onCreate()
                object : CommandTransport {
                    override fun execute(name: String, arguments: JSONObject): JSONObject {
                        if (name == "terminal_open") {
                            onOpen()
                            return JSONObject().put("ok", true).put("session_id", "session")
                        }
                        executions++
                        if (failExecute) throw java.io.IOException("sent but receipt lost")
                        executedCommands.add(arguments.getString("command"))
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
        fun dispatch(id: String = "call", environment: String = "android", timeout: Long = 30000,
            command: String = "echo exact", dispatchOwner: RunExecutionOwner = owner) = dispatcher.dispatchOwned(task,
            listOf(ToolInvocation(ToolCallId(id), "terminal_exec", JSONObject().put("command", command)
                .put("environment", environment).put("timeout_ms", timeout).toString())), dispatchOwner) { cancelled }.single().outcome
    }
    @Test fun automaticDefaultExecutesDifferentCommandsAndTimeoutsWithSeparateExactGrants() {
        val fixture = Fixture(automaticConsent = true)
        val commands = listOf("echo first", "echo second", "echo second ")
        commands.forEachIndexed { index, command ->
            assertTrue(fixture.dispatch("call-$index", command = command) is DispatchOutcome.Confirmed)
        }
        assertTrue(fixture.dispatch("timeout", timeout = 60000, command = commands.last()) is DispatchOutcome.Confirmed)
        assertEquals(commands + commands.last(), fixture.executedCommands)
        assertEquals(4, fixture.effects.toSet().size)
        assertEquals(0, fixture.prompts)
        assertTrue(fixture.task.allowedWrites.isEmpty())
        assertTrue((fixture.dispatch("replay", command = commands.first()) as DispatchOutcome.Confirmed).replayed)
        assertEquals(4, fixture.executions)
        fixture.leaf.close()
    }
    @Test fun automaticDefaultStillRejectsStopExpiredOwnerAndForeignOwner() {
        val stopped = Fixture(automaticConsent = true)
        stopped.duringConsent = { stopped.cancelled = true }
        assertTrue(stopped.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, stopped.created)
        val expired = Fixture(automaticConsent = true)
        expired.ownerCurrent = false
        assertTrue(expired.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, expired.created)
        val foreign = Fixture(automaticConsent = true)
        val other = runnerOwner(Message(MessageId("other"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        assertTrue(foreign.dispatch(dispatchOwner = other) is DispatchOutcome.NotExecuted)
        assertTrue(foreign.effects.isEmpty())
        assertEquals(0, foreign.created)
    }
    @Test fun automaticUnknownNeverReplaysOrPermitsADifferentCommand() {
        val fixture = Fixture(automaticConsent = true)
        fixture.execution = "unknown"
        assertTrue(fixture.dispatch() is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("new-id") is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("different", command = "echo other") is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.executions)
        assertEquals(1, fixture.effects.size)
        assertEquals(0, fixture.prompts)
        fixture.leaf.close()
    }
    @Test fun consentExecutesExactBytesOnceAndConfirmedReplayDoesNotPrompt() {
        val fixture = Fixture()
        assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
        assertTrue((fixture.dispatch("new-id") as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, fixture.prompts); assertEquals(1, fixture.executions)
        assertTrue(fixture.task.allowedWrites.isEmpty())
    }
    @Test fun failedFreezeReportsActualTargetAndNeverRetriesAnotherEnvironment() {
        val selected = app.daiyu.android.terminal.LinuxGenerationRef("selected", "b")
        val workspace = app.daiyu.android.terminal.LinuxGenerationRef("workspace", "a")
        var freezes = 0
        val binding = CommandLinuxBinding.freeze(app.daiyu.android.terminal.TerminalLinuxState("FILES_READY", selected), workspace) {
            freezes++; error("linux_runner_generation_unknown")
        }
        assertEquals(1, freezes); assertNull(binding.launch)
        assertEquals(workspace, binding.state?.selected)
        assertEquals("RUNNER_UNKNOWN", binding.state?.state)
        val fixture = Fixture(linuxState = binding.state)
        assertEquals(DispatchOutcome.NotExecuted("linux_runner_generation_unknown"), fixture.dispatch(environment = "linux"))
        assertTrue(fixture.dispatch("android") is DispatchOutcome.Confirmed)
        fixture.leaf.close()
    }

    @Test fun missingWorkspaceBindingDoesNotInheritAnotherSelectionsState() {
        val selected = app.daiyu.android.terminal.LinuxGenerationRef("selected", "b")
        val workspace = app.daiyu.android.terminal.LinuxGenerationRef("workspace", "a")
        val binding = CommandLinuxBinding.freeze(app.daiyu.android.terminal.TerminalLinuxState("RUNNER_UNKNOWN", selected), workspace) { null }
        assertEquals(workspace, binding.state?.selected)
        assertEquals("UNAVAILABLE", binding.state?.state)
        assertEquals("linux_workspace_generation_unavailable", binding.state?.error)
    }

    @Test fun rootModeRejectionAndCancellationKeepTheirMeaning() {
        val rejected = CommandLinuxBinding.freeze(null, null) { error("linux_root_mode_required") }
        assertEquals("linux_root_mode_required", rejected.state?.error)
        assertNull(rejected.launch)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            CommandLinuxBinding.freeze(null, null) { throw java.util.concurrent.CancellationException("cancelled") }
        }
    }

    @Test fun unknownLinuxIsReportedAsFencedNotUnconfiguredAndNeverPromptsOrLaunches() {
        val fixture = Fixture(linuxState = app.daiyu.android.terminal.TerminalLinuxState("RUNNER_UNKNOWN",
            error = "linux_runner_generation_unknown"))
        val description = fixture.leaf.tools().first().definition.description
        assertTrue(description.contains("RUNNER_UNKNOWN"))
        assertTrue(description.contains("linux_runner_generation_unknown"))
        assertFalse(description.contains("未配置"))
        assertEquals(DispatchOutcome.NotExecuted("linux_runner_generation_unknown"), fixture.dispatch(environment = "linux"))
        assertEquals(0, fixture.prompts); assertEquals(0, fixture.created)
        assertTrue(fixture.dispatch("android", environment = "android") is DispatchOutcome.Confirmed)
        assertEquals(1, fixture.created)
        fixture.leaf.close()
    }

    @Test fun unavailableLinuxStatesHaveDistinctDescriptionsAndReceipts() {
        val states = mapOf("NOT_SELECTED" to "linux_environment_not_selected",
            "BROKEN" to "linux_environment_unavailable", "FILES_READY" to "linux_environment_not_bound_to_run")
        states.forEach { (state, reason) ->
            val fixture = Fixture(linuxState = app.daiyu.android.terminal.TerminalLinuxState(state))
            assertTrue(fixture.leaf.tools().first().definition.description.contains(state))
            assertEquals(DispatchOutcome.NotExecuted(reason), fixture.dispatch(environment = "linux"))
            assertEquals(0, fixture.prompts); assertEquals(0, fixture.created)
            fixture.leaf.close()
        }
    }

    @Test fun missingLinuxSnapshotDoesNotClaimThatNoInstallationExists() {
        val fixture = Fixture()
        val description = fixture.leaf.tools().first().definition.description
        assertTrue(description.contains("不能据此推断安装状态"))
        assertFalse(description.contains("未配置"))
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
