package app.daiyu.android

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserRunAuthorization
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.openminis.BrowserRunConstructionException
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.integration.tools.CatalogBrowserBinding
import app.daiyu.android.integration.tools.RunCatalogCloseResult
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual preparation/token/binding; fake main queue and native lifetime, no WebView or network. */
class ControllerBrowserPreparationTest {
    private val conversation = Conversation(ConversationId("conversation"), "title", "draft", 0, assistantId = "profile")
    private val draft = BrowserDraftSnapshot(BrowserDraftOwner(conversation.id, 3, 7), "precise text")
    private val skills = mutableListOf("skill-one")
    private val assistant = AssistantProfile("profile", "Profile", "precise prompt", enabledSkillIds = skills)
    private val provider = ProviderSnapshot.capture(ModelSettings("https://example.invalid", "fixture-model", "synthetic-key", false))
    private val grant = BrowserGrant(BrowserOwner("run", BrowserOwnerKind.MAIN), BrowserAccess.FULL)
    private val raw = " {\"action\":\"new_tab\"} "
    private fun prepare(actions: Set<String> = setOf("new_tab", "list_tabs"), writes: List<String> = listOf(raw),
                        access: BrowserAccess = BrowserAccess.FULL) = ControllerBrowserPreparation.prepare(
        conversation, draft, assistant, provider, BrowserSettings(access), grant.copy(access = access), actions, writes,
        assistantMessageId = MessageId("reply"))

    private class MainQueue : BrowserMainDispatcher {
        private val main = Thread.currentThread()
        private val enqueued = CountDownLatch(1)
        private var block: (() -> Unit)? = null
        override fun isMainThread() = Thread.currentThread() === main
        @Synchronized override fun post(block: () -> Unit): Boolean {
            check(this.block == null)
            this.block = block
            enqueued.countDown()
            return true
        }
        fun awaitPost() { assertTrue(enqueued.await(5, TimeUnit.SECONDS)) }
        fun runNext() { check(isMainThread()); checkNotNull(block).invoke() }
    }

    private class NativeHost(grant: BrowserGrant) : CatalogBrowserBinding.Host {
        var tabs = 0
        var network = 0
        var closes = 0
        var status = BrowserCloseResult(true)
        override val session = BrowserToolSession(grant, BrowserSessionRegistry(), object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) { tabs++ }
            override fun close(lease: BrowserTabLease) {}
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                network++
                return BrowserExecutionResult("ok")
            }
        })
        override fun closeWithResult(): BrowserCloseResult { closes++; session.close(); return status }
        override fun closeStatus() = status
    }

    private fun open(preparation: ControllerBrowserPreparation, queue: MainQueue,
                     factory: (BrowserRunAuthorization) -> CatalogBrowserBinding): Pair<Thread, AtomicReference<CatalogBrowserBinding?>> {
        val result = AtomicReference<CatalogBrowserBinding?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { result.set(preparation.openOnWorker(queue, factory)) }
            catch (error: Throwable) { failure.set(error) }
        }
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
        worker.start()
        queue.awaitPost()
        queue.runNext()
        worker.join(5000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        return worker to result
    }

    @Test fun purePreviewFreezesCompleteInputsAndOnlyOriginalPreviewCanConfirm() {
        val actions = mutableSetOf("new_tab", "list_tabs")
        val writes = mutableListOf(raw)
        val p = prepare(actions, writes)
        val other = prepare()
        actions.clear(); writes.clear(); skills.clear()
        assertEquals(setOf("new_tab", "list_tabs"), p.preview.allowedActions)
        assertEquals(raw, p.preview.writes.single().arguments)
        assertEquals(listOf("skill-one"), p.assistant.enabledSkillIds)
        assertSame(provider, p.provider)
        assertEquals("reply", p.identity.assistantMessageId.value)
        assertEquals("run", p.identity.runId.value)
        assertFalse(p.confirm(other.preview, draft))
        assertFalse(p.confirm(p.preview, draft.copy(owner = draft.owner.copy(draftRevision = 8))))
        assertFalse(p.confirm(p.preview, draft.copy(owner = draft.owner.copy(editorGeneration = 4))))
        assertFalse(p.confirm(p.preview, draft.copy(text = "changed")))
        assertEquals(ControllerBrowserPreparation.State.PREVIEW, p.state)
        assertTrue(p.confirm(p.preview, draft))
        assertFalse(p.confirm(p.preview, draft))
        p.cancel()
        assertEquals(RunCatalogCloseResult.Drained, p.pollCleanup())
    }

    @Test fun noConfirmationMeansNoQueueOrFactoryAndMainCallerIsRejected() {
        val p = prepare()
        val queue = MainQueue()
        try { p.openOnWorker(queue) { error("must not construct") }; fail("main caller") }
        catch (_: IllegalStateException) {}
        val offMain = object : BrowserMainDispatcher {
            override fun isMainThread() = false
            override fun post(block: () -> Unit): Boolean = error("must not enqueue")
        }
        assertNull(p.openOnWorker(offMain) { error("must not construct") })
        p.cancel()
        assertFalse(p.confirm(p.preview, draft))
    }

    @Test fun readOnlyEmptySessionDoesNotCreateTabNavigateOrBindAnOwner() {
        val p = prepare(setOf("list_tabs", "get_text"), emptyList(), BrowserAccess.READ_ONLY)
        assertTrue(p.confirm(p.preview, draft))
        val queue = MainQueue()
        val host = NativeHost(p.preview.grant)
        val (_, opened) = open(p, queue) { token ->
            assertTrue(queue.isMainThread())
            CatalogBrowserBinding.create(p.assistant, token) { host }
        }
        assertNotNull(opened.get())
        assertNull(opened.get()!!.session.currentTab)
        assertEquals(0, host.tabs)
        assertEquals(0, host.network)
        assertTrue(opened.get()!!.requiredWrites.isEmpty())
        assertEquals(ControllerBrowserPreparation.State.READY, p.state)
        p.cancel()
        assertEquals(1, host.closes)
        assertSame(RunCatalogCloseResult.Drained, p.pollCleanup())
    }

    @Test fun cancelWhileNativeConstructorIsInFlightOwnsAndClosesTheLateBinding() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val host = NativeHost(grant)
        var late: CatalogBrowserBinding? = null
        val (_, opened) = open(p, MainQueue()) { token ->
            p.cancel() // Simulate stop racing after native acquisition, before publication.
            CatalogBrowserBinding.create(p.assistant, token) { host }.also { late = it }
        }
        assertNull(opened.get())
        assertEquals(1, host.closes)
        assertEquals(0, host.tabs)
        assertSame(RunCatalogCloseResult.Drained, p.pollCleanup())
        assertNull(p.accept(checkNotNull(late)) { error("cancelled owner cannot commit") })
    }

    @Test fun interruptedWorkerCannotReleaseQueuedOwnerOrLeakLateConstruction() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val queue = MainQueue()
        val result = AtomicReference<CatalogBrowserBinding?>()
        val worker = Thread { result.set(p.openOnWorker(queue) { error("cancelled queue must not construct") }) }
        worker.start(); queue.awaitPost()
        worker.interrupt(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertNull(result.get())
        assertEquals(ControllerBrowserPreparation.State.OPENING, p.state)
        assertNull(p.pollCleanup())
        queue.runNext()
        assertSame(RunCatalogCloseResult.Drained, p.pollCleanup())
    }

    @Test fun cancelBeforeMainRunsNeverInvokesConstructor() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val queue = MainQueue()
        val result = AtomicReference<CatalogBrowserBinding?>()
        val worker = Thread { result.set(p.openOnWorker(queue) { error("cancelled before start") }) }
        worker.start(); queue.awaitPost()
        p.cancel()
        assertNull(p.pollCleanup())
        assertEquals(ControllerBrowserPreparation.State.OPENING, p.state)
        queue.runNext(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertNull(result.get())
        assertSame(RunCatalogCloseResult.Drained, p.pollCleanup())
    }

    @Test fun partialConstructionRetainsOriginalCleanupAndUnknownNeverBecomesReleaseReceipt() {
        val p = prepare()
        p.confirm(p.preview, draft)
        var requests = 0
        var status = BrowserCloseResult(true, pending = setOf("RAW_IO"), failures = setOf("CONSTRUCTION"))
        val original = BrowserRunConstructionException(IllegalStateException("fixture failure")) {
            requests++
            status
        }
        val (_, opened) = open(p, MainQueue()) { throw original }
        assertNull(opened.get())
        assertEquals(1, requests)
        assertTrue(p.pollCleanup() is RunCatalogCloseResult.Unknown)
        assertEquals(1, requests) // Observation is not a hidden cleanup retry.
        status = BrowserCloseResult(true)
        p.retryCleanup()
        assertEquals(2, requests) // Still owns the actual exception capability after UNKNOWN.
        assertEquals(ControllerBrowserPreparation.State.UNKNOWN, p.state)
        assertTrue(p.pollCleanup() is RunCatalogCloseResult.Unknown)
    }

    @Test fun pendingCloseIsNotDrainedAndFailedCloseIsStickyEvenAfterNativeSuccess() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val host = NativeHost(grant)
        host.status = BrowserCloseResult(true, pending = setOf("RAW_IO"))
        open(p, MainQueue()) { token -> CatalogBrowserBinding.create(p.assistant, token) { host } }
        p.cancel()
        assertNull(p.pollCleanup())
        host.status = BrowserCloseResult(true, failures = setOf("DESTROY"))
        assertTrue(p.pollCleanup() is RunCatalogCloseResult.Unknown)
        host.status = BrowserCloseResult(true)
        p.retryCleanup()
        assertEquals(2, host.closes)
        assertTrue(p.pollCleanup() is RunCatalogCloseResult.Unknown)
    }

    @Test fun acceptanceTransfersSameBindingAndReceiptConsumesOnlyExactDraftVersionOnce() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val host = NativeHost(grant)
        val binding = open(p, MainQueue()) { token -> CatalogBrowserBinding.create(p.assistant, token) { host } }.second.get()!!
        val receipt = p.accept(binding) { conversation }!!
        assertEquals(ControllerBrowserPreparation.State.TRANSFERRED, p.state)
        p.cancel(); p.retryCleanup()
        assertEquals(0, host.closes) // Accepted runner is now solely responsible for teardown.
        assertNull(p.pollCleanup())
        assertFalse(receipt.consume(draft.copy(owner = draft.owner.copy(draftRevision = 8))))
        assertFalse(receipt.consume(draft.copy(owner = draft.owner.copy(editorGeneration = 4))))
        assertFalse(receipt.consume(draft.copy(text = "changed")))
        assertTrue(receipt.consume(draft))
        assertFalse(receipt.consume(draft))
        assertNull(p.accept(binding) { error("no second commit") })
        binding.close()
    }

    @Test fun ambiguousCommitCannotCreateReceiptOrReleaseEvenAfterSuccessfulClose() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val host = NativeHost(grant)
        val binding = open(p, MainQueue()) { token -> CatalogBrowserBinding.create(p.assistant, token) { host } }.second.get()!!
        assertNull(p.accept(binding) { throw IllegalStateException("ambiguous DB commit") })
        assertEquals(1, host.closes)
        assertTrue(p.pollCleanup() is RunCatalogCloseResult.Unknown)
        assertEquals(ControllerBrowserPreparation.State.UNKNOWN, p.state)
    }

    @Test fun reentrantCancellationDuringCommitCannotPublishAReceipt() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val host = NativeHost(grant)
        val binding = open(p, MainQueue()) { token -> CatalogBrowserBinding.create(p.assistant, token) { host } }.second.get()!!
        assertNull(p.accept(binding) { p.cancel(); conversation })
        assertTrue(host.closes >= 1)
        assertTrue(p.pollCleanup() is RunCatalogCloseResult.Unknown)
    }

    @Test fun rejectedMainQueueAndRejectedCommitAreDefiniteNoAcceptance() {
        val p = prepare()
        p.confirm(p.preview, draft)
        val rejected = object : BrowserMainDispatcher {
            override fun isMainThread() = false
            override fun post(block: () -> Unit) = false
        }
        assertNull(p.openOnWorker(rejected) { error("not accepted by main") })
        assertSame(RunCatalogCloseResult.Drained, p.pollCleanup())
        val second = prepare()
        second.confirm(second.preview, draft)
        val host = NativeHost(grant)
        val binding = open(second, MainQueue()) { token -> CatalogBrowserBinding.create(second.assistant, token) { host } }.second.get()!!
        assertNull(second.accept(binding) { null })
        assertEquals(1, host.closes)
        assertSame(RunCatalogCloseResult.Drained, second.pollCleanup())
    }

    @Test fun noForbiddenActionsArePromotedByFullPreference() {
        listOf("fetch", "get_cookies", "set_cookies", "history", "open_child").forEach { action ->
            try { prepare(setOf(action), emptyList()); fail(action) } catch (_: IllegalArgumentException) {}
        }
        try { prepare(setOf("navigate"), listOf("{\"action\":\"navigate\",\"url\":\"https://example.invalid\",\"tab_id\":0}"), BrowserAccess.READ_ONLY)
            fail("read-only navigation") } catch (_: IllegalArgumentException) {}
    }
}
