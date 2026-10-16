package app.daiyu.android.browser

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.integration.tools.RunBrowserTools
import org.junit.Assert.*
import org.junit.Test

/** Real production effect validation with a fake lifecycle/port. No WebView or network. */
class BrowserRunPreparationTest {
    private val identity = BrowserRunIdentity(ConversationId("conversation"), RunId("run"),
        "assistant-profile", MessageId("assistant-message"))
    private val full = BrowserSettings(BrowserAccess.FULL)
    private val navigate = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/","timeout_ms":1000}"""

    private class Fixture(access: BrowserAccess = BrowserAccess.FULL) : AutoCloseable {
        var lifecycleCalls = 0
        var portCalls = 0
        val grant = BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), access)
        val session = BrowserToolSession(grant, BrowserSessionRegistry(), object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) { lifecycleCalls++ }
            override fun close(lease: BrowserTabLease) { lifecycleCalls++ }
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                portCalls++
                error("preparation must not execute")
            }
        })
        override fun close() = session.close()
    }

    private fun prepare(f: Fixture, actions: Set<String> = setOf("navigate"),
                        arguments: List<String> = listOf(navigate)) = BrowserRunPreparation.prepare(
        identity, full, f.grant, actions, arguments, f.session)

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun exactEffectAndCollectionsFreezeWithoutAnyExecution() {
        Fixture().use { f ->
            val actions = mutableSetOf("navigate", "get_text")
            val arguments = mutableListOf(navigate)
            val preparation = prepare(f, actions, arguments)
            actions.clear(); arguments[0] = navigate.replace("1000", "1001")
            val preview = preparation.preview
            assertEquals(setOf("navigate", "get_text"), preview.allowedActions)
            assertEquals(navigate, preview.writes.single().arguments)
            assertEquals(RunBrowserTools.writeEffect(f.session, navigate), preview.writes.single().effect)
            val confirmed = preparation.confirm(preview)
            val auth = confirmed.taskAuthorization(owner(), identity.assistantId, f.session)
            assertEquals("run", auth.sessionId)
            assertEquals("run", auth.taskId)
            assertEquals(setOf(preview.writes.single().effect), auth.allowedWrites)
            try { preparation.confirm(preview); fail("single use") } catch (_: IllegalStateException) { }
            assertNull(f.session.currentTab)
            assertEquals(0, f.lifecycleCalls)
            assertEquals(0, f.portCalls)
            try {
                (preview.allowedActions as MutableSet<String>).add("click")
                fail("immutable")
            } catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun readOnlyCanPrepareWithoutSessionButFullNeverGrantsAnImplicitWrite() {
        val grant = BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), BrowserAccess.READ_ONLY)
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
            grant, setOf("list_tabs", "get_text"))
        Fixture(BrowserAccess.READ_ONLY).use { f ->
            val confirmed = preparation.confirm(preparation.preview)
            val owner = owner()
            assertTrue(confirmed.taskAuthorization(owner, identity.assistantId, f.session).allowedWrites.isEmpty())
            confirmed.checkInvocation(owner, identity.assistantId, f.session, RunBrowserTools.READ_TOOL_NAME,
                """{"action":"get_text","tab_id":0,"selector":"main"}""")
            rejected { confirmed.checkInvocation(owner, identity.assistantId, f.session, RunBrowserTools.READ_TOOL_NAME,
                """{"action":"get_page_info","tab_id":0}""") }
            rejected { confirmed.checkInvocation(owner, identity.assistantId, f.session, RunBrowserTools.WRITE_TOOL_NAME,
                """{"action":"get_text","tab_id":0}""") }
            assertEquals(0, f.portCalls)
        }
        Fixture().use { f ->
            rejected { prepare(f, arguments = emptyList()) }
            val firstWrite = BrowserRunPreparation.prepare(identity, full, f.grant, setOf("navigate"), listOf(navigate))
            assertEquals(RunBrowserTools.writeEffect(f.grant, navigate), firstWrite.preview.writes.single().effect)
            rejected { BrowserRunPreparation.prepare(identity, BrowserSettings(), f.grant, setOf("list_tabs")) }
            rejected { BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
                f.grant, setOf("list_tabs")) }
        }
    }

    @Test fun tabChangesAreWritesEvenWithReadOnlyCeiling() {
        Fixture(BrowserAccess.READ_ONLY).use { f ->
            val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
                f.grant, setOf("new_tab"), listOf("""{"action":"new_tab"}"""), f.session)
            assertEquals("new_tab", preparation.preview.writes.single().effect.operation)
            rejected { BrowserRunPreparation.prepare(identity, full, f.grant, setOf("navigate"), listOf(navigate), f.session) }
            assertEquals(0, f.lifecycleCalls)
        }
    }

    @Test fun cookieFetchUnsupportedAndChildActionsAreNotOffered() {
        Fixture().use { f ->
            for (action in listOf("get_cookies", "set_cookies", "fetch", "download", "unknown")) {
                rejected { prepare(f, setOf(action), emptyList()) }
            }
            for (json in listOf(navigate.replace("\"tab_id\":0,", ""),
                navigate.replace("\"tab_id\":0", "\"tab_id\":3"),
                navigate.replace("https://example.invalid/", "javascript:alert(1)"),
                navigate.dropLast(1) + ",\"unconfirmed\":true}")) {
                rejected { prepare(f, arguments = listOf(json)) }
            }
            rejected { prepare(f, arguments = listOf(navigate, navigate)) }
            rejected { BrowserRunPreparation.prepare(identity, full,
                f.grant.copy(owner = BrowserOwner("run", BrowserOwnerKind.CHILD)), setOf("list_tabs")) }
            rejected { BrowserRunPreparation.prepare(identity, full,
                f.grant.copy(owner = BrowserOwner("another-run", BrowserOwnerKind.MAIN)), setOf("list_tabs")) }
        }
    }

    @Test fun confirmationAndAdmissionRejectChangedIdentityActionsAndEveryEffectParameter() {
        Fixture().use { f ->
            val preparation = prepare(f)
            rejected { preparation.confirm(prepare(f).preview) }
            val confirmed = preparation.confirm(preparation.preview)
            val owner = owner()
            confirmed.checkInvocation(owner, identity.assistantId, f.session, RunBrowserTools.WRITE_TOOL_NAME, navigate)
            // Equivalent JSON order is the same production effect, not raw-text identity.
            confirmed.checkInvocation(owner, identity.assistantId, f.session, RunBrowserTools.WRITE_TOOL_NAME,
                """{"timeout_ms":1000,"url":"https://example.invalid/","tab_id":0,"action":"navigate"}""")
            for (changed in listOf(navigate.replace("1000", "1001"), navigate.replace("tab_id\":0", "tab_id\":1"),
                navigate.replace("example.invalid/", "example.invalid/other"))) {
                rejected { confirmed.checkInvocation(owner, identity.assistantId, f.session, RunBrowserTools.WRITE_TOOL_NAME, changed) }
            }
            rejected { confirmed.checkInvocation(owner, identity.assistantId, f.session,
                RunBrowserTools.READ_TOOL_NAME, """{"action":"list_tabs"}""") }
            rejected { confirmed.checkInvocation(owner, identity.assistantId, f.session, RunBrowserTools.READ_TOOL_NAME, navigate) }
            rejected { confirmed.taskAuthorization(owner, "other-assistant-profile", f.session) }
            for (changedIdentity in listOf(identity.copy(conversationId = ConversationId("other")),
                identity.copy(runId = RunId("other")), identity.copy(assistantMessageId = MessageId("other")))) {
                rejected { confirmed.taskAuthorization(owner(changedIdentity), identity.assistantId, f.session) }
            }
            Fixture().use { other -> rejected { confirmed.taskAuthorization(owner, identity.assistantId, other.session) } }
            assertEquals(0, f.lifecycleCalls)
            assertEquals(0, f.portCalls)
        }
    }

    @Test fun readOnlyFirstBindingPinsActualOwnerAndSessionAndRevocationIsTerminal() {
        val grant = BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), BrowserAccess.READ_ONLY)
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.READ_ONLY),
            grant, setOf("list_tabs"))
        val confirmed = preparation.confirm(preparation.preview)
        Fixture(BrowserAccess.READ_ONLY).use { first ->
            Fixture(BrowserAccess.READ_ONLY).use { replacement ->
                val owner = owner()
                confirmed.taskAuthorization(owner, identity.assistantId, first.session)
                rejected { confirmed.taskAuthorization(owner, identity.assistantId, replacement.session) }
                rejected { confirmed.taskAuthorization(owner(), identity.assistantId, first.session) }
                confirmed.checkInvocation(owner, identity.assistantId, first.session,
                    RunBrowserTools.READ_TOOL_NAME, """{"action":"list_tabs"}""")
                confirmed.revoke()
                confirmed.revoke()
                rejected { confirmed.taskAuthorization(owner, identity.assistantId, first.session) }
                rejected { confirmed.checkInvocation(owner, identity.assistantId, first.session,
                    RunBrowserTools.READ_TOOL_NAME, """{"action":"list_tabs"}""") }
                assertEquals(0, first.lifecycleCalls)
                assertEquals(0, first.portCalls)
                assertEquals(0, replacement.lifecycleCalls)
                assertEquals(0, replacement.portCalls)
            }
        }
    }

    @Test fun cancelConsumesUnconfirmedPreparation() {
        Fixture().use { f ->
            val preparation = prepare(f)
            preparation.cancel()
            try { preparation.confirm(preparation.preview); fail("cancelled") } catch (_: IllegalStateException) { }
        }
    }

    /** Get the opaque owner via the real runner, not reflection or a forged constructor. */
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
