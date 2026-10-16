package app.daiyu.android.integration.tools

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.*
import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.host.*
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.openminis.BrowserRunConstructionException
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real preparation, token, session and central dispatcher; only native host lifetime is fake. */
class CatalogBrowserBindingTest {
    private val identity = BrowserRunIdentity(ConversationId("conversation"), RunId("run"),
        "assistant-profile", MessageId("assistant-message"))
    private val profile = AssistantProfile(identity.assistantId, "Test", "")
    private val grant = BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), BrowserAccess.FULL)
    private val newTab = """{"action":"new_tab"}"""

    private fun preparation(actions: Set<String> = setOf("new_tab", "list_tabs"),
                            writes: List<String> = listOf(newTab)) =
        BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.FULL), grant, actions, writes)

    private class NativeHost(grant: BrowserGrant) : CatalogBrowserBinding.Host {
        var allocations = 0
        var executions = 0
        var requests = 0
        var observations = 0
        var beforeClose: () -> Unit = {}
        var receipt = BrowserCloseResult(false)
        override val session = BrowserToolSession(grant, BrowserSessionRegistry(), object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) { allocations++ }
            override fun close(lease: BrowserTabLease) {}
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                executions++
                return BrowserExecutionResult("ok")
            }
        })
        override fun closeWithResult(): BrowserCloseResult {
            requests++
            beforeClose()
            session.close()
            return receipt
        }
        override fun closeStatus(): BrowserCloseResult { observations++; return receipt }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) {}
    }

    @Test fun firstWritePreviewRequiresNoSessionAndUsesTheSameProductionEncoder() {
        val navigate = """{"action":"navigate","tab_id":0,"url":"https://example.invalid/","timeout_ms":500}"""
        val preparation = preparation(setOf("navigate"), listOf(navigate))
        assertEquals(RunBrowserTools.writeEffect(grant, navigate), preparation.preview.writes.single().effect)
        val confirmed = preparation.confirm(preparation.preview)
        // A session is constructed only AFTER the immutable preview was confirmed.
        val host = NativeHost(grant)
        val binding = CatalogBrowserBinding.create(profile, confirmed) { host }
        try {
            assertSame(host.session, binding.session)
            assertNull(host.session.currentTab)
            assertEquals(0, host.allocations)
            assertEquals(0, host.executions)
            assertEquals(RunBrowserTools.writeEffect(host.session, navigate), binding.requiredWrites.single())
            rejected { RunBrowserTools.writeEffect(grant, navigate.replace("https://example.invalid/", "javascript:alert(1)")) }
            rejected { RunBrowserTools.writeEffect(grant.copy(access = BrowserAccess.READ_ONLY), navigate) }
        } finally { binding.close() }
    }

    @Test fun bindsOriginalOwnerAndReturnsTheExactMixedTaskWithoutRecreatingSessionOrDispatcher() {
        val preparation = preparation()
        val confirmed = preparation.confirm(preparation.preview)
        val host = NativeHost(grant)
        val binding = CatalogBrowserBinding.create(profile, confirmed) { host }
        try {
            val originalOwner = owner()
            val extra = WriteEffect("another-leaf", "write", "target", "exact")
            val task = TaskAuthorization("run", "run", binding.requiredWrites + extra)
            val bound = binding.bindOwner(originalOwner, task)
            assertSame(task, bound.taskAuthorization)
            assertSame(bound, binding.bindOwner(originalOwner, task))
            assertSame(task, confirmed.taskAuthorization(originalOwner, profile.id, host.session))
            assertSame(host.session, binding.session)
            assertEquals(0, host.allocations)
            rejected { binding.bindOwner(originalOwner, TaskAuthorization("run", "run", task.allowedWrites)) }
            try { binding.bindOwner(owner(), task); fail("replacement owner") } catch (_: IllegalStateException) {}
            val registry = ToolRegistry().apply { bound.tools.forEach { register(it) } }
            val dispatcher = ToolDispatcher(registry.snapshot(), SessionCapabilities("run",
                setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY)), ToolLedger(InMemoryToolLedgerStore()))
            fun invoke(id: String) = dispatcher.dispatchOwned(task,
                listOf(ToolInvocation(ToolCallId(id), RunBrowserTools.WRITE_TOOL_NAME, newTab)), originalOwner) { false }.single().outcome
            assertTrue(invoke("first") is DispatchOutcome.Confirmed)
            assertEquals(1, host.allocations)
            assertNotNull(binding.session.currentTab)
            assertTrue((invoke("replay") as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, host.allocations)
            binding.revoke()
            assertTrue(invoke("after-revoke") is DispatchOutcome.NotExecuted)
            assertEquals(1, host.allocations)
        } finally { binding.close() }
    }

    @Test fun bindingPreservesTheOriginalSessionsAlreadyOwnedTab() {
        val preparation = preparation(setOf("list_tabs"), emptyList())
        val host = NativeHost(grant)
        assertTrue(host.session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed)
        val originalTab = host.session.currentTab
        val binding = CatalogBrowserBinding.create(profile, preparation.confirm(preparation.preview)) { host }
        try {
            val bound = binding.bindOwner(owner())
            assertTrue(bound.taskAuthorization.allowedWrites.isEmpty())
            assertSame(host.session, binding.session)
            assertEquals(originalTab, binding.session.currentTab)
            assertEquals(1, host.allocations)
            assertEquals(0, host.executions)
        } finally { binding.close() }
    }

    @Test fun failedCloseRequestCannotBeMistakenForSuccessfulNativeSnapshot() {
        val preparation = preparation()
        val host = NativeHost(grant)
        val binding = CatalogBrowserBinding.create(profile, preparation.confirm(preparation.preview)) { host }
        host.receipt = BrowserCloseResult(true)
        host.beforeClose = { error("request failed") }
        binding.close()
        assertFalse(binding.closeStatus().successful)
        assertTrue(binding.closeResult() is RunCatalogCloseResult.Unknown)
        host.beforeClose = {}
        binding.close() // Resource capability was retained even after the first request failed.
        assertEquals(2, host.requests)
        assertTrue(binding.closeResult() is RunCatalogCloseResult.Unknown)
    }

    @Test fun sharedTaskMustContainEveryConfirmedEffectAndStillCannotAuthorizeOtherBrowserActions() {
        val preparation = preparation(setOf("new_tab"), listOf(newTab))
        val confirmed = preparation.confirm(preparation.preview)
        val host = NativeHost(grant)
        val binding = CatalogBrowserBinding.create(profile, confirmed) { host }
        try {
            val owner = owner()
            rejected { binding.bindOwner(owner, TaskAuthorization("run", "run")) }
            rejected { binding.bindOwner(owner, TaskAuthorization("other", "run", binding.requiredWrites)) }
            val other = """{"action":"close_tab","tab_id":0}"""
            val task = TaskAuthorization("run", "run", binding.requiredWrites + RunBrowserTools.writeEffect(grant, other))
            val bound = binding.bindOwner(owner, task)
            val tool = bound.tools.single { it.definition.name == RunBrowserTools.WRITE_TOOL_NAME }
            rejected { tool.prepare(tool.definition.schema.parse(other)) }
            assertEquals(0, host.allocations)
        } finally { binding.close() }
    }

    @Test fun revocationPrecedesCloseAndOnlyLiveSuccessfulStatusCountsAsDrained() {
        val preparation = preparation()
        val token = preparation.confirm(preparation.preview)
        val host = NativeHost(grant)
        val binding = CatalogBrowserBinding.create(profile, token) { host }
        val owner = owner()
        val bound = binding.bindOwner(owner)
        val tool = bound.tools.single { it.definition.name == RunBrowserTools.WRITE_TOOL_NAME }
        host.beforeClose = { rejected { tool.prepare(tool.definition.schema.parse(newTab)) } }
        host.receipt = BrowserCloseResult(true, pending = setOf("RAW_IO"))
        assertNull(binding.closeResult())
        binding.close()
        assertEquals(1, host.requests)
        assertNull(binding.closeResult())
        host.receipt = BrowserCloseResult(true, failures = setOf("DESTROY_FAILED"))
        assertTrue(binding.closeStatus().drained) // Native drained != successful.
        assertTrue(binding.closeResult() is RunCatalogCloseResult.Unknown)
        host.receipt = BrowserCloseResult(true)
        assertSame(RunCatalogCloseResult.Drained, binding.closeResult())
        assertEquals(1, host.requests) // Polling is observation, not a hidden retry.
    }

    @Test fun closeNeverHoldsBindingLockAcrossNativeRequestAndCannotDrainAnInFlightRequest() {
        val preparation = preparation()
        val host = NativeHost(grant)
        val binding = CatalogBrowserBinding.create(profile, preparation.confirm(preparation.preview)) { host }
        val bound = binding.bindOwner(owner())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        host.receipt = BrowserCloseResult(true)
        host.beforeClose = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val closer = Thread { binding.close() }
        closer.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(binding.closeResult())
            val tool = bound.tools.single { it.definition.name == RunBrowserTools.WRITE_TOOL_NAME }
            rejected { tool.prepare(tool.definition.schema.parse(newTab)) }
        } finally {
            release.countDown()
            closer.join(5000)
        }
        assertFalse(closer.isAlive)
        assertSame(RunCatalogCloseResult.Drained, binding.closeResult())
    }

    @Test fun failedHostConstructionRethrowsTheCleanupCapabilityAndRevokesTheToken() {
        val preparation = preparation()
        val token = preparation.confirm(preparation.preview)
        var cleanupCalls = 0
        val construction = BrowserRunConstructionException(IllegalStateException("native construction")) {
            cleanupCalls++
            BrowserCloseResult(true, pending = setOf("RAW_IO"), failures = setOf("BROWSER_RUN_CONSTRUCTION"))
        }
        try {
            CatalogBrowserBinding.create(profile, token) { throw construction }
            fail("construction should throw")
        } catch (failure: BrowserRunConstructionException) {
            assertSame(construction, failure)
            assertFalse(failure.closeWithResult().successful)
            assertEquals(1, cleanupCalls)
        }
        val host = NativeHost(grant)
        try { rejected { token.taskAuthorization(owner(), profile.id, host.session) } }
        finally { host.session.close() }
    }

    /** The real runner is the only producer of the opaque owner; never forge an equal-ID token. */
    private fun owner(): RunExecutionOwner {
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
        runner.run(Message(identity.assistantMessageId, identity.conversationId, identity.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(captured)
    }
}
