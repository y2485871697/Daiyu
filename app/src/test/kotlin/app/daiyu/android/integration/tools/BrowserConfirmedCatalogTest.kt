package app.daiyu.android.integration.tools

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserRunIdentity
import app.daiyu.android.browser.BrowserRunPreparation
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test

/** Real confirmation, adapter, dispatcher, facade and ledger. Only host/port are fake; no network. */
class BrowserConfirmedCatalogTest {
    private val identity = BrowserRunIdentity(ConversationId("conversation"), RunId("run"),
        "assistant-profile", MessageId("assistant-message"))
    private val profile = AssistantProfile(identity.assistantId, "Test assistant", "")
    private val navigate = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/","timeout_ms":1000}"""
    private val tabs = """{"action":"list_tabs"}"""

    private class CountingStore : ToolLedgerStore {
        private val delegate = InMemoryToolLedgerStore()
        var accesses = 0
        override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
            accesses++
            return delegate.transaction(block)
        }
        fun rows() = delegate.transaction { it.toList() }
    }

    private inner class Fixture : AutoCloseable {
        var lifecycleCalls = 0
        var portCalls = 0
        var failPort = false
        val owner: RunExecutionOwner = owner()
        val session = BrowserToolSession(
            BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), BrowserAccess.FULL),
            BrowserSessionRegistry(), object : BrowserTabLifecycle {
                override fun create(lease: BrowserTabLease) { lifecycleCalls++ }
                override fun close(lease: BrowserTabLease) { lifecycleCalls++ }
            }, object : BrowserExecutionPort {
                override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                    portCalls++
                    if (failPort) error("lost response after entry")
                    return BrowserExecutionResult("confirmed browser result")
                }
            })
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.FULL),
            session.grant, setOf("list_tabs", "navigate"), listOf(navigate), session)
        val confirmed = preparation.confirm(preparation.preview)
        val binding = RunBrowserTools.createConfirmed(session, owner, profile, confirmed)
        val store = CountingStore()
        fun dispatcher(tools: List<RegisteredTool> = binding.tools) = ToolDispatcher(
            ToolRegistry().apply { tools.forEach { register(it) } }.snapshot(),
            SessionCapabilities("run", setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY)),
            ToolLedger(store))
        val dispatcher: ToolDispatcher = dispatcher()
        fun call(json: String = tabs, write: Boolean = false, id: String = "call") = ToolInvocation(
            ToolCallId(id), if (write) RunBrowserTools.WRITE_TOOL_NAME else RunBrowserTools.READ_TOOL_NAME, json)
        fun dispatch(json: String = tabs, write: Boolean = false, id: String = "call",
                     task: TaskAuthorization = binding.taskAuthorization,
                     token: RunExecutionOwner = owner, dispatcher: ToolDispatcher = this.dispatcher) =
            dispatcher.dispatchOwned(task, listOf(call(json, write, id)), token).single().outcome
        fun hostTab() { assertTrue(session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed) }
        override fun close() = session.close()
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun matchingSnapshotBindsWithoutExecutionAndRealDispatcherReplaysExactEffect() {
        Fixture().use { f ->
            assertEquals(0, f.lifecycleCalls)
            assertEquals(0, f.portCalls)
            assertEquals(0, f.store.accesses)
            assertNull(f.session.currentTab)
            assertEquals(setOf(RunBrowserTools.writeEffect(f.session, navigate)), f.binding.taskAuthorization.allowedWrites)
            assertTrue(f.dispatch(id = "read") is DispatchOutcome.Confirmed)
            assertEquals(0, f.lifecycleCalls) // list_tabs does not allocate a tab.
            f.hostTab() // Explicit test host setup, not catalog preparation.
            val first = f.dispatch(navigate, write = true) as DispatchOutcome.Confirmed
            assertFalse(first.replayed)
            val equivalent = """{"timeout_ms":1000,"url":"https://example.invalid/","tab_id":0,"action":"navigate"}"""
            val replay = f.dispatch(equivalent, write = true, id = "same-effect") as DispatchOutcome.Confirmed
            assertTrue(replay.replayed)
            assertEquals(first.payload, replay.payload)
            assertEquals(1, f.portCalls)
            assertEquals(1, f.lifecycleCalls)
        }
    }

    @Test fun wrongAssistantOwnerAndSessionCannotBindOrTouchLedger() {
        Fixture().use { f ->
            // Profile is trusted binding data, not a model argument or a field of ToolExecutionContext.
            // Reject a wrong profile at creation, before a dispatcher catalog can be constructed.
            rejected { RunBrowserTools.createConfirmed(f.session, f.owner, profile.copy(id = "other-profile"), f.confirmed) }
            for (changed in listOf(identity.copy(conversationId = ConversationId("other")),
                identity.copy(runId = RunId("other")), identity.copy(assistantMessageId = MessageId("other")), identity)) {
                rejected { RunBrowserTools.createConfirmed(f.session, owner(changed), profile, f.confirmed) }
            }
            Fixture().use { other ->
                rejected { RunBrowserTools.createConfirmed(other.session, f.owner, profile, f.confirmed) }
            }
            assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"), f.dispatch(token = owner()))
            val copiedTask = TaskAuthorization("run", "run", f.binding.taskAuthorization.allowedWrites)
            assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"), f.dispatch(task = copiedTask))
            assertEquals(0, f.store.accesses)
            assertTrue(f.store.rows().isEmpty())
            assertEquals(0, f.lifecycleCalls)
            assertEquals(0, f.portCalls)
        }
    }

    @Test fun unconfirmedActionAndEveryChangedEffectRejectBeforeAnyLedgerAccess() {
        Fixture().use { f ->
            assertEquals(DispatchOutcome.NotExecuted("preparation_failed"),
                f.dispatch("""{"action":"get_page_info","tab_id":0}"""))
            for (changed in listOf(navigate.replace("1000", "1001"),
                navigate.replace("tab_id\":0", "tab_id\":1"),
                navigate.replace("example.invalid/", "example.invalid/other"))) {
                assertEquals(DispatchOutcome.NotExecuted("preparation_failed"), f.dispatch(changed, write = true))
            }
            val batch = listOf(f.call(id = "valid"), f.call("""{"action":"get_text"}""", id = "not-confirmed"))
            assertTrue(f.dispatcher.dispatchOwned(f.binding.taskAuthorization, batch, f.owner)
                .all { it.outcome == DispatchOutcome.NotExecuted("preparation_failed") })
            assertEquals(0, f.store.accesses)
            assertTrue(f.store.rows().isEmpty())
            assertEquals(0, f.lifecycleCalls)
            assertEquals(0, f.portCalls)
        }
    }

    @Test fun revokedTokenRejectsConfirmedReadAndWriteReplayBeforeLedgerAndCannotBeRebound() {
        Fixture().use { f ->
            f.hostTab()
            assertTrue(f.dispatch(id = "read") is DispatchOutcome.Confirmed)
            assertTrue(f.dispatch(navigate, write = true, id = "write") is DispatchOutcome.Confirmed)
            val rows = f.store.rows()
            val accesses = f.store.accesses
            f.confirmed.revoke()
            f.confirmed.revoke() // Idempotent, never a toggle.
            assertEquals(DispatchOutcome.NotExecuted("preparation_failed"), f.dispatch(id = "read"))
            for (id in listOf("write", "same-effect-new-id")) {
                assertEquals(DispatchOutcome.NotExecuted("preparation_failed"), f.dispatch(navigate, true, id))
            }
            rejected { RunBrowserTools.createConfirmed(f.session, f.owner, profile, f.confirmed) }
            rejected { f.confirmed.taskAuthorization(f.owner, identity.assistantId, f.session) }
            assertEquals(accesses, f.store.accesses)
            assertEquals(rows, f.store.rows())
            assertEquals(1, f.portCalls)
            assertEquals(1, f.lifecycleCalls)
        }
    }

    @Test fun revocationBetweenRealPrepareAndAdmissionAlsoBlocksConfirmedReplayBeforeLedger() {
        for (write in listOf(false, true)) Fixture().use { f ->
            if (write) f.hostTab()
            val json = if (write) navigate else tabs
            assertTrue(f.dispatch(json, write) is DispatchOutcome.Confirmed)
            val accesses = f.store.accesses
            val rows = f.store.rows()
            // Test-only timing hook. Return the actual adapter's PreparedTool unchanged;
            // never synthesize a receipt or replace the execution/admission implementation.
            val hooked = f.binding.tools.map { actual -> object : RegisteredTool {
                override val definition = actual.definition
                override fun prepare(arguments: ToolArguments): PreparedTool = actual.prepare(arguments).also {
                    f.confirmed.revoke()
                }
            } }
            assertEquals(DispatchOutcome.NotExecuted("browser_confirmation_not_authorized"),
                f.dispatch(json, write, dispatcher = f.dispatcher(hooked)))
            assertEquals(accesses, f.store.accesses)
            assertEquals(rows, f.store.rows())
            assertEquals(if (write) 1 else 0, f.portCalls)
        }
    }

    @Test fun unknownWriteIsNeverResentUnderSameOrFreshCallId() {
        Fixture().use { f ->
            f.hostTab()
            f.failPort = true
            assertEquals(DispatchOutcome.Unknown("browser_execution_unconfirmed"), f.dispatch(navigate, true))
            for (id in listOf("call", "fresh-call")) {
                assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), f.dispatch(navigate, true, id))
            }
            assertEquals(1, f.portCalls)
            assertEquals(1, f.lifecycleCalls)
            assertEquals(LedgerState.UNKNOWN, f.store.rows().single().state)
        }
    }

    /** Obtain the real opaque token from ChatRunner; do not forge it or reflect its constructor. */
    private fun owner(id: BrowserRunIdentity = identity): RunExecutionOwner {
        var captured: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        runner.run(Message(id.assistantMessageId, id.conversationId, id.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(captured)
    }
}
