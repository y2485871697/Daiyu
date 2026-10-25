package app.daiyu.android.integration.tools

import app.daiyu.android.CatalogFixturePort

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.domain.tools.ToolDefinition as RegisteredDefinition
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private fun deviceCall(id: String, name: String = ReadOnlyTool.BATTERY.toolName, arguments: String = "{}") =
    ToolInvocation(ToolCallId(id), name, arguments)

class RunDeviceResourcesTest {
    private class Fixture(bind: Boolean = true, read: (ToolInvocation) -> String = { """{"ok":true}""" }) : AutoCloseable {
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val owner = runnerOwner(message)
        val authorization = TaskAuthorization("run", "run")
        val capabilities = SessionCapabilities("run", setOf(AndroidToolRegistry.BATTERY_CAPABILITY, AndroidToolRegistry.DEVICE_STATUS_CAPABILITY))
        val reads = AtomicInteger()
        val resources = RunDeviceResources(BatteryTool { reads.incrementAndGet(); read(it) })
        val store = InMemoryToolLedgerStore()
        val dispatcher = ToolDispatcher(AndroidToolRegistry.create(resources).snapshot(), capabilities, ToolLedger(store))
        init { if (bind) resources.bind(owner, authorization, capabilities) }
        fun dispatch(call: ToolInvocation, task: TaskAuthorization = authorization,
            token: RunExecutionOwner = owner, cancelled: () -> Boolean = { false }) =
            dispatcher.dispatchOwned(task, listOf(call), token, cancelled).single().outcome
        override fun close() = resources.close()
    }
    @Test fun realRegistrationsPreserveInvocationAndReplayWithoutResampling() {
        val seen = mutableListOf<ToolInvocation>()
        Fixture(read = { seen.add(it); if (it.name == ReadOnlyTool.BATTERY.toolName) """{"ok":true,"percent":42}""" else """{"ok":false,"error":"fixture_unavailable"}""" }).use { f ->
            val battery = deviceCall("provider-battery-17", arguments = " \n{ }\t")
            val status = deviceCall("provider-status-23", ReadOnlyTool.DEVICE_STATUS.toolName)
            assertFalse((f.dispatch(battery) as DispatchOutcome.Confirmed).isError)
            assertTrue((f.dispatch(status) as DispatchOutcome.Confirmed).isError)
            assertSame(battery, seen[0]); assertSame(status, seen[1])
            assertTrue((f.dispatch(battery) as DispatchOutcome.Confirmed).replayed)
            assertTrue((f.dispatch(status) as DispatchOutcome.Confirmed).replayed)
            assertEquals(2, f.reads.get()); assertTrue(f.authorization.allowedWrites.isEmpty())
        }
    }
    @Test fun copiedAuthorizationAndSecondRunnerOwnerCannotRead() {
        Fixture().use { f ->
            assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"), f.dispatch(deviceCall("copied"), TaskAuthorization("run", "run")))
            assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"), f.dispatch(deviceCall("other"), token = runnerOwner(f.message)))
            assertEquals(0, f.reads.get()); assertTrue(f.dispatch(deviceCall("original")) is DispatchOutcome.Confirmed)
            assertEquals(1, f.reads.get())
        }
    }
    @Test fun unownedAndMalformedWholeBatchesNeverReserveOrRead() {
        Fixture().use { f ->
            assertEquals(DispatchOutcome.NotExecuted("tool_execution_context_required"), f.dispatcher.dispatch(f.authorization, listOf(deviceCall("unowned"))).single().outcome)
            val invalid = f.dispatcher.dispatchOwned(f.authorization, listOf(deviceCall("valid-first"), deviceCall("invalid", ReadOnlyTool.DEVICE_STATUS.toolName, """{"extra":true}""")), f.owner)
            assertTrue(invalid.all { it.outcome == DispatchOutcome.NotExecuted("invalid_arguments") })
            val unknown = f.dispatcher.dispatchOwned(f.authorization, listOf(deviceCall("valid-again"), deviceCall("not-opened", "browser")), f.owner)
            assertTrue(unknown.all { it.outcome == DispatchOutcome.NotExecuted("unknown_tool") })
            assertEquals(0, f.reads.get()); assertTrue(f.store.transaction { it.isEmpty() })
        }
    }
    @Test fun consumerChecksLiveCancellationAfterDispatcherAdmission() {
        Fixture().use { f ->
            val cancelled = AtomicBoolean(false)
            val registry = ToolRegistry().register(object : RegisteredTool {
                override val definition = RegisteredDefinition(ReadOnlyTool.BATTERY.toolName, 1, "Cancellation boundary", AndroidToolRegistry.BATTERY_CAPABILITY, ToolEffect.READ_ONLY, ToolSchema())
                override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                    cancelled.set(true); f.resources.execute(it, ReadOnlyTool.BATTERY)
                }
            })
            val result = ToolDispatcher(registry.snapshot(), f.capabilities, ToolLedger(f.store)).dispatchOwned(f.authorization, listOf(deviceCall("cancel")), f.owner) { cancelled.get() }.single().outcome
            assertEquals(DispatchOutcome.NotExecuted("cancelled_before_execution"), result)
            assertEquals(0, f.reads.get()); assertEquals(LedgerState.NOT_EXECUTED, f.store.transaction { it.single().state })
        }
    }
    @Test fun closeFencesWithoutWaitingAndAcknowledgesAfterReadReturns() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val acknowledged = AtomicInteger()
        val returned = CountDownLatch(1); val cancelled = AtomicBoolean(false)
        val outcome = AtomicReference<DispatchOutcome?>(); val failure = AtomicReference<Throwable?>()
        val f = Fixture(read = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); """{"ok":true,"percent":42}""" })
        val worker = Thread { try { outcome.set(f.dispatch(deviceCall("held"), cancelled = { cancelled.get() })) } catch (t: Throwable) { failure.set(t) } }.apply { isDaemon = true }
        val closer = Thread { try { f.resources.closeWhenIdle { acknowledged.incrementAndGet() } } catch (t: Throwable) { failure.set(t) } finally { returned.countDown() } }.apply { isDaemon = true }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); closer.start(); assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(0, acknowledged.get()); assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch(deviceCall("after-fence")))
            f.resources.close(); f.resources.close(); cancelled.set(true)
        } finally { release.countDown(); worker.join(5000); if (closer.state != Thread.State.NEW) closer.join(5000); f.close() }
        assertFalse(worker.isAlive); assertFalse(closer.isAlive); failure.get()?.let { throw it }
        assertTrue(outcome.get() is DispatchOutcome.Confirmed); assertEquals(1, f.reads.get()); assertEquals(1, acknowledged.get())
    }
    @Test fun backendUnknownCannotReplayAndDoesNotLeakReadLease() {
        Fixture(read = { error("service result unavailable") }).use { f ->
            val call = deviceCall("uncertain"); assertTrue(f.dispatch(call) is DispatchOutcome.Unknown)
            val acknowledged = AtomicInteger(); f.resources.closeWhenIdle { acknowledged.incrementAndGet() }
            assertEquals(1, acknowledged.get()); assertTrue(f.dispatch(call) is DispatchOutcome.Unknown); assertEquals(1, f.reads.get())
        }
    }
    @Test fun unboundOrClosedResourcesCannotBeOpenedByFirstInvocation() {
        Fixture(bind = false).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("device_run_not_bound"), f.dispatch(deviceCall("unbound")))
            f.resources.close()
            try { f.resources.bind(f.owner, f.authorization, f.capabilities); fail("closed resources rebound") } catch (_: IllegalStateException) {}
            assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch(deviceCall("closed"))); assertEquals(0, f.reads.get())
        }
    }
    @Test fun runnerCheckpointAndSameRunCatalogWireBothRealDeviceConsumers() {
        val initial = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val checkpoints = mutableListOf<RunCheckpoint>()
        val calls = listOf(deviceCall("provider-battery"), deviceCall("provider-status", ReadOnlyTool.DEVICE_STATUS.toolName))
        val seen = mutableListOf<ToolInvocation>()
        val resources = RunDeviceResources(BatteryTool { invocation ->
            assertTrue(checkpoints.isNotEmpty()); assertTrue(checkpoints.last().tools.any { it.invocation.id == invocation.id && it.disposition == ToolDisposition.IN_FLIGHT })
            assertSame(calls[seen.size], invocation); seen.add(invocation); """{"ok":true}"""
        })
        val capabilities = SessionCapabilities("run", setOf(AndroidToolRegistry.BATTERY_CAPABILITY, AndroidToolRegistry.DEVICE_STATUS_CAPABILITY, SkillRunSession.READ))
        lateinit var authorization: TaskAuthorization
        val tools = RunCatalogTools(AndroidToolRegistry.create(resources), capabilities, ToolLedger(InMemoryToolLedgerStore()), initial.conversationId, initial.runId, resources) { caps, task ->
            assertSame(capabilities, caps); assertTrue(task.allowedWrites.isEmpty()); authorization = task
            SkillRunSession.create(task, setOf(SkillRunSession.READ), emptyList())
        }
        var rounds = 0
        val model = object : CatalogFixturePort {
            override fun open(history: List<Message>): ModelSession = error("catalog required")
            override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
                assertTrue(checkpoints.isNotEmpty())
                assertEquals(setOf(ReadOnlyTool.BATTERY.toolName, ReadOnlyTool.DEVICE_STATUS.toolName), definitions.map { it.name }.toSet())
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { if (rounds++ == 0) calls.forEach { sink(ModelEvent.ToolReady(it)) } else assertEquals(calls.map { it.id }, outputs.map { it.callId }) }
                    override fun cancel() {}
                }
            }
        }
        val runtime = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) { checkpoints.add(checkpoint) }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("not recovering")
        }
        val runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} }, tools, RunnerOptions(runtimeStore = runtime, receiptBatchExecutor = tools)) {}
        try {
            assertEquals(RunStatus.COMPLETED, runner.run(initial, emptyList()).status); assertEquals(calls, seen)
            assertEquals("run", authorization.taskId); assertEquals(1, checkpoints.map { it.owner }.distinct().size)
            assertTrue(tools.execute(initial, calls) { false }.all { (it.outcome as DispatchOutcome.Confirmed).replayed }); assertEquals(2, seen.size)
        } finally { val acknowledged = AtomicInteger(); tools.closeWhenIdle { acknowledged.incrementAndGet() }; assertEquals(1, acknowledged.get()) }
    }
}
