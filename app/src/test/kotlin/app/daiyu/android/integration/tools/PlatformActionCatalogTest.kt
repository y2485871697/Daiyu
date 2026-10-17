package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.actions.*
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real central catalog/dispatcher/ledger and Runner-issued owner; only Android transport is fake. */
class PlatformActionCatalogTest {
    private class Backend(var reply: ExecutionReceipt = ExecutionReceipt.Confirmed("{\"read_back\":true}")) : PlatformActionBackend {
        var calls = 0
        var unavailable: String? = null
        var work: (() -> Unit)? = null
        override fun unavailable(kind: PlatformActionKind) = unavailable
        override fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
            calls++; work?.invoke(); return reply
        }
    }
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun action() = PlatformAction.parse(PlatformActionKind.VOLUME, """{"stream":"music","index":3}""")
    private fun call(id: String = "call", index: Int = 3) = ToolInvocation(ToolCallId(id),
        PlatformActionKind.VOLUME.toolName, """{"index":$index,"stream":"music"}""")
    private fun create(backend: Backend, ledger: ToolLedger = ToolLedger(InMemoryToolLedgerStore())): Pair<RunCatalogTools, PlatformActionScope> {
        val action = action()
        val task = TaskAuthorization("run", "run", setOf(action.effect))
        val scope = PlatformActionScope(message.conversationId, message.runId, action, task, backend)
        val tools = RunCatalogTools(ToolRegistry(), SessionCapabilities("run", setOf(action.kind.capability)), ledger,
            message.conversationId, message.runId, authorization = task, platformAction = scope) { caps, auth ->
            assertSame(task, auth)
            SkillRunSession.create(auth, caps.enabled, emptyList())
        }
        tools.bindOwner(runnerOwner(message)); tools.definitions
        return tools to scope
    }
    @Test fun exactApprovalOwnerAndOriginalLedgerConfirmedReplay() {
        val backend = Backend()
        val store = InMemoryToolLedgerStore()
        val (tools, _) = create(backend, ToolLedger(store))
        assertEquals(listOf(PlatformActionKind.VOLUME.toolName), tools.definitions.map { it.name })
        assertEquals(0, backend.calls)
        assertEquals(DispatchOutcome.NotExecuted("preparation_failed"),
            tools.execute(message, listOf(call(index = 4))) { false }.single().outcome)
        assertTrue(store.transaction { it.isEmpty() })
        val receipt = (tools.start(message, listOf(call())) { false } as ReceiptBatchStart.Completed).receipts.single().outcome
        assertTrue(receipt is DispatchOutcome.Confirmed)
        val replay = tools.execute(message, listOf(call())) { false }.single().outcome as DispatchOutcome.Confirmed
        assertTrue(replay.replayed)
        assertEquals(1, backend.calls)
        assertEquals(LedgerState.CONFIRMED, store.transaction { it.single().state })
        val result = AtomicReference<RunCatalogCloseResult>()
        tools.closeWithResult(result::set)
        assertEquals(RunCatalogCloseResult.Drained, result.get())
        assertEquals(DispatchOutcome.NotExecuted("run_closed"),
            tools.execute(message, listOf(call("after-close"))) { false }.single().outcome)
    }
    @Test fun capabilityAloneCannotAdvertiseOrIssueWriteAndCopiedTaskCannotBind() {
        val action = action()
        val caps = SessionCapabilities("run", setOf(action.kind.capability))
        val empty = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()), message.conversationId,
            message.runId) { c, a -> SkillRunSession.create(a, c.enabled, emptyList()) }
        empty.bindOwner(runnerOwner(message))
        assertFalse(empty.definitions.any { it.name == action.kind.toolName })
        assertEquals(DispatchOutcome.NotExecuted("unknown_tool"), empty.execute(message, listOf(call())) { false }.single().outcome)
        empty.close()
        try {
            PlatformActionScope(message.conversationId, message.runId, action, TaskAuthorization("run", "run"), Backend())
            fail("empty grants must fail")
        } catch (_: IllegalArgumentException) {}
        val task = TaskAuthorization("run", "run", setOf(action.effect))
        val scope = PlatformActionScope(message.conversationId, message.runId, action, task, Backend())
        try {
            scope.bind(runnerOwner(message), caps, TaskAuthorization("run", "run", task.allowedWrites))
            fail("copied grant is not the approved task")
        } catch (_: IllegalArgumentException) {}
        scope.close()
    }
    @Test fun unknownCannotUseFreshCallIdAndNeverDrains() {
        val backend = Backend(ExecutionReceipt.Unknown("key_submission_unverified"))
        val (tools, _) = create(backend)
        assertTrue(tools.execute(message, listOf(call())) { false }.single().outcome is DispatchOutcome.Unknown)
        assertEquals(DispatchOutcome.NotExecuted("platform_outcome_unknown_no_retry"),
            tools.execute(message, listOf(call("new-call-id"))) { false }.single().outcome)
        assertEquals(1, backend.calls)
        var drained = false
        val result = AtomicReference<RunCatalogCloseResult>()
        tools.closeWhenIdle { drained = true }
        tools.closeWithResult(result::set)
        assertFalse(drained)
        assertTrue(result.get() is RunCatalogCloseResult.Unknown)
    }
    @Test fun receiptPersistenceFailureAfterConfirmedReadbackFencesScope() {
        val backend = Backend()
        val memory = InMemoryToolLedgerStore()
        var transactions = 0
        val store = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                if (++transactions == 2) error("receipt write failed")
                return memory.transaction(block)
            }
        }
        val (tools, _) = create(backend, ToolLedger(store))
        assertEquals(DispatchOutcome.Unknown("receipt_persistence_failed"),
            tools.execute(message, listOf(call())) { false }.single().outcome)
        assertTrue(tools.execute(message, listOf(call("different-id"))) { false }.single().outcome is DispatchOutcome.NotExecuted)
        val result = AtomicReference<RunCatalogCloseResult>()
        tools.closeWithResult(result::set)
        assertTrue(result.get() is RunCatalogCloseResult.Unknown)
        assertEquals(1, backend.calls)
    }
    @Test fun unavailableBackendDoesNotAdvertiseAndCancelBeforeDispatchDoesNotExecute() {
        val unavailable = Backend().apply { unavailable = "not_supported" }
        val (disabled, _) = create(unavailable)
        assertTrue(disabled.definitions.isEmpty())
        assertEquals(DispatchOutcome.NotExecuted("unknown_tool"), disabled.execute(message, listOf(call())) { false }.single().outcome)
        assertEquals(0, unavailable.calls); disabled.close()
        val backend = Backend()
        val (tools, _) = create(backend)
        assertTrue(tools.execute(message, listOf(call())) { true }.single().outcome is DispatchOutcome.NotExecuted)
        assertEquals(0, backend.calls); tools.close()
    }
    @Test fun closeWaitsForActiveSubmissionAndUnknownRemainsPinned() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val backend = Backend(ExecutionReceipt.Unknown("in_flight_unconfirmed")).apply {
            work = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val (tools, _) = create(backend)
        val worker = Thread { tools.execute(message, listOf(call())) { false } }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val result = AtomicReference<RunCatalogCloseResult>()
        tools.closeWithResult(result::set)
        assertNull(result.get())
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(result.get() is RunCatalogCloseResult.Unknown)
        assertEquals(1, backend.calls)
    }
    @Test fun fiveSchemasAreStrictAndSemanticIdentitiesIgnoreJsonSpelling() {
        val samples = mapOf(
            PlatformActionKind.ALARM to """{"hour":7,"minute":30,"label":"wake"}""",
            PlatformActionKind.TIMER to """{"seconds":120,"label":"tea"}""",
            PlatformActionKind.VOLUME to """{"stream":"music","index":3}""",
            PlatformActionKind.MEDIA to """{"key":"pause"}""",
            PlatformActionKind.WIFI to """{"enabled":false}""")
        assertEquals(5, samples.map { (kind, json) -> PlatformAction.parse(kind, json).effect }.toSet().size)
        assertEquals(action().effect, PlatformAction.parse(PlatformActionKind.VOLUME, """{ "index":3, "stream":"music" }""").effect)
        for (invalid in listOf("""{"stream":"music","index":3,"shell":"x"}""", """{"stream":"ring","index":3}""", """{"stream":"music","index":-1}""")) {
            try { PlatformAction.parse(PlatformActionKind.VOLUME, invalid); fail("strict schema") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
