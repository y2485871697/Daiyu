package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.integration.tools.CatalogTerminalBinding
import app.daiyu.android.integration.tools.TerminalPtyStep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real parent entry, SQLite admission, Runner/catalog and retained text tickets.
 * The model fixture requests no tools: these tests never launch a shell or access the network. */
@Suppress("DEPRECATION")
class ControllerTerminalSendTest : InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    private val factories = AtomicInteger()
    private val sessionOwners = java.util.Collections.synchronizedList(mutableListOf<app.daiyu.android.domain.ConversationId>())
    private val releases = mutableListOf<CountDownLatch>()
    private val controller get() = fixture.controller

    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext, onSession = { _, _, context ->
            sessionOwners.add(context.conversationId); assertNull(context.project)
        }) { factories.incrementAndGet() }
        awaitControl("ready") { controller.ready }
    }
    override fun tearDown() {
        try { releases.forEach { it.countDown() }; fixture.close() } finally { super.tearDown() }
    }
    private fun draft(): TerminalDraftSnapshot {
        val text = "解释输出，不要把这段自然语言当 shell  \n"
        fixture.database.draft(text)
        return TerminalDraftSnapshot(TerminalDraftOwner(null, 31, 42), text)
    }
    private fun prepare(draft: TerminalDraftSnapshot): ControllerTerminalPreparation =
        checkNotNull(controller.prepareTerminalSend(null, draft, "printf 'exact input'\n", null))
    private fun confirm(p: ControllerTerminalPreparation) {
        controlMain { assertTrue(controller.confirmTerminal(p, p.preview, p.draft, p.assistant, p.provider)) }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(target)
    }
    private fun waitFor(latch: CountDownLatch) = assertTrue(latch.await(10, TimeUnit.SECONDS))
    private fun holdWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases.add(it) }
        (field(controller, "executor") as ExecutorService).execute { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
        waitFor(entered)
        return release
    }

    fun testProductionPrepareAndConfirmAreNonSendingAndUseOnlyIndependentExactBytes() {
        val draft = draft()
        val p = prepare(draft)
        assertEquals(draft, p.draft)
        assertEquals(2, p.preview.plan.steps.size)
        assertTrue("printf 'exact input'\n".toByteArray().contentEquals((p.preview.plan.steps[1] as TerminalPtyStep.Input).bytes()))
        confirm(p)
        assertEquals(0, factories.get())
        assertEquals(0, fixture.opens.get())
        assertNull(controller.active)
        assertNull(controller.textSendState())
        assertEquals(draft.text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertFalse(controller.sendText(null, 31, 42, draft.text) { fail("reserved") })
        controlMain { controller.rejectTerminal(p) }
        awaitControl("preview drained") { controller.terminalPreparation() == null }
    }

    fun testDefaultAuthorizedSendNeedsNoConfirmationAndKeepsOriginalPlan() {
        val draft = draft()
        val p = prepare(draft)
        assertEquals(ControllerTerminalPreparation.State.PREVIEW, p.state)
        assertNull(field(p, "token"))
        val session = fixture.session(holdSeconds = 120)
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        val ticket = ChatController.TextSendTicket()
        controlMain {
            assertTrue(controller.sendTerminal(p, p.preview, draft, p.assistant, p.provider, ticket) {
                result.set(it); done.countDown()
            })
            assertEquals(ControllerTerminalPreparation.State.TRANSFERRED, p.state)
        }
        waitFor(done); waitFor(session.entered)
        assertTrue(result.get() is ChatController.TextSendResult.Accepted)
        val catalog = checkNotNull(field(controller, "runTools"))
        val authorization = checkNotNull(field(p, "token"))
        assertSame(authorization, field(catalog, "initialAuthorization"))
        val leaf = checkNotNull(field(catalog, "terminalLeaf"))
        val binding = field(checkNotNull(field(leaf, "tools")), "binding") as CatalogTerminalBinding
        assertSame(p.preview.plan, binding.plan)
        assertSame(authorization, binding.authorization)
        controlMain { controller.acknowledgeTextSend(ticket) }
        session.release.countDown()
        awaitControl("default authorized run drained") { controller.active == null }
    }

    fun testExplicitSendUsesOriginalAuthorizationAndBindingAndPersistentReceiptIdentity() {
        val draft = draft()
        val p = prepare(draft)
        confirm(p)
        val authorization = field(p, "token")
        val binding = field(p, "binding") as CatalogTerminalBinding
        val gate = holdWorker()
        val ticket = ChatController.TextSendTicket()
        val result = AtomicReference<ChatController.TextSendResult>()
        val done = CountDownLatch(1)
        val session = fixture.session(holdSeconds = 120)
        controlMain {
            assertTrue(controller.sendTerminal(p, p.preview, draft, p.assistant, p.provider, ticket) {
                result.set(it); done.countDown()
            })
            assertEquals(ControllerTerminalPreparation.State.TRANSFERRED, p.state)
            assertEquals(draft.text, fixture.database.draft())
            assertNull(checkNotNull(controller.textSendState()).result)
            assertFalse(controller.sendTerminal(p, p.preview, draft, p.assistant, p.provider,
                ChatController.TextSendTicket()) { fail("must not duplicate") })
        }
        gate.countDown()
        waitFor(done); waitFor(session.entered)
        val accepted = result.get() as ChatController.TextSendResult.Accepted
        assertEquals(p.identity.conversationId, accepted.conversation.id)
        assertEquals(listOf(accepted.conversation.id), sessionOwners)
        val rows = fixture.database.messages(accepted.conversation.id).map { it.second }
        assertEquals(2, rows.size)
        assertEquals(p.identity.assistantMessageId, rows.single { it.role == "assistant" }.id)
        assertTrue(rows.all { it.runId == p.identity.runId })
        assertEquals(listOf(MessageBlock.Text("user", draft.text)), rows.single { it.role == "user" }.blocks)
        val catalog = checkNotNull(field(controller, "runTools"))
        assertSame(authorization, field(catalog, "initialAuthorization"))
        val leaf = checkNotNull(field(catalog, "terminalLeaf"))
        assertSame(binding, field(checkNotNull(field(leaf, "tools")), "binding"))
        assertSame(field(catalog, "owner"), field(binding, "owner"))
        assertSame(authorization, binding.authorization)
        assertSame(p.preview.plan, binding.plan)
        assertSame(accepted, checkNotNull(controller.textSendState()).result)
        assertEquals("", fixture.database.draft())
        controlMain { controller.acknowledgeTextSend(ticket) }
        session.release.countDown()
        awaitControl("catalog drained") { controller.active == null && field(controller, "resources") == null }
        assertTrue(binding.awaitTermination(100))
        assertEquals(1, fixture.opens.get())
    }

    fun testUnknownDatabaseReceiptKeepsDraftAndConsumedPlanCannotBeResent() {
        val draft = draft()
        val p = prepare(draft)
        confirm(p)
        fixture.database.writableDatabase.execSQL("CREATE TRIGGER terminal_fail BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'terminal receipt failure'); END")
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        val ticket = ChatController.TextSendTicket()
        try {
            controlMain { assertTrue(controller.sendTerminal(p, p.preview, draft, p.assistant, p.provider, ticket) {
                result.set(it); done.countDown()
            }) }
            waitFor(done)
            assertTrue(result.get() is ChatController.TextSendResult.Unknown)
            assertEquals(draft.text, fixture.database.draft())
            assertEquals(0, fixture.opens.get())
            assertSame(result.get(), checkNotNull(controller.textSendState()).result)
            assertFalse(controller.sendTerminal(p, p.preview, draft, p.assistant, p.provider,
                ChatController.TextSendTicket()) { fail("no retry") })
        } finally { fixture.database.writableDatabase.execSQL("DROP TRIGGER terminal_fail") }
        controlMain { controller.acknowledgeTextSend(ticket) }
    }

    fun testQueuedStopRejectsWithoutModelOpenAndRetainsTicketUntilAcknowledged() {
        val draft = draft()
        val p = prepare(draft)
        confirm(p)
        val gate = holdWorker()
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        val ticket = ChatController.TextSendTicket()
        controlMain {
            assertTrue(controller.sendTerminal(p, p.preview, draft, p.assistant, p.provider, ticket) {
                result.set(it); done.countDown()
            })
            controller.stop()
            assertSame(p, controller.terminalPreparation())
        }
        gate.countDown(); waitFor(done)
        assertTrue(result.get() is ChatController.TextSendResult.Rejected)
        awaitControl("unstarted binding drained") { controller.terminalPreparation() == null }
        assertEquals(0, fixture.opens.get())
        assertEquals(0, factories.get())
        assertEquals(draft.text, fixture.database.draft())
        assertSame(result.get(), checkNotNull(controller.textSendState()).result)
        controlMain { controller.acknowledgeTextSend(ticket) }
    }

    fun testPreparationRequiresWorkerAndUnavailableLinuxNeverFallsBackToAndroid() {
        val draft = draft()
        controlMain {
            try {
                controller.prepareTerminalSend(null, draft, "printf x\n", null)
                fail("main-thread preparation")
            } catch (_: IllegalStateException) { }
            assertNull(controller.terminalPreparation())
        }
        assertNull(controller.prepareTerminalSend(null, draft, "printf x\n",
            app.daiyu.android.terminal.LinuxGenerationRef("not-installed", "generation")))
        assertNull(controller.terminalPreparation())
        assertEquals(0, factories.get())
        assertEquals(0, fixture.opens.get())
        assertEquals(draft.text, fixture.database.draft())
    }

    fun testChangedPersistedDraftIsRejectedBeforeMessagePairAndNeverOpensModel() {
        val draft = draft()
        val p = prepare(draft)
        confirm(p)
        fixture.database.draft("new persisted text")
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        val ticket = ChatController.TextSendTicket()
        controlMain { assertTrue(controller.sendTerminal(p, p.preview, draft, p.assistant, p.provider, ticket) {
            result.set(it); done.countDown()
        }) }
        waitFor(done)
        assertTrue(result.get() is ChatController.TextSendResult.Rejected)
        assertEquals("new persisted text", fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
        controlMain { controller.acknowledgeTextSend(ticket) }
    }

    fun testOldDisposedCallbacksDoNotReleaseReplacementReservation() {
        val draft = draft()
        val old = prepare(draft)
        controlMain { controller.rejectTerminal(old) }
        awaitControl("old owner drained") { controller.terminalPreparation() == null }
        val current = prepare(draft)
        controlMain {
            controller.rejectTerminal(old)
            assertFalse(controller.confirmTerminal(old, old.preview, draft, old.assistant, old.provider))
            assertFalse(controller.sendTerminal(old, old.preview, draft, old.assistant, old.provider,
                ChatController.TextSendTicket()) { fail("disposed owner") })
            assertSame(current, controller.terminalPreparation())
            assertTrue(controller.confirmTerminal(current, current.preview, draft, current.assistant, current.provider))
            controller.rejectTerminal(current)
        }
        awaitControl("replacement drained") { controller.terminalPreparation() == null }
        assertEquals(0, fixture.opens.get())
    }

    fun testStaleDraftAssistantProviderAndDisposedPreviewCannotConfirmOrTransfer() {
        val draft = draft()
        val p = prepare(draft)
        assertFalse(controller.confirmTerminal(p, p.preview, draft.copy(owner = draft.owner.copy(draftRevision = 43)), p.assistant, p.provider))
        assertFalse(controller.confirmTerminal(p, p.preview, draft, p.assistant.copy(prompt = "different"), p.provider))
        // Configuration mismatch cancels the actual retained owner, not only a UI flag.
        awaitControl("invalid profile drained") { controller.terminalPreparation() == null }
        assertFalse(controller.confirmTerminal(p, p.preview, draft, p.assistant, p.provider))
        val next = prepare(draft)
        confirm(next)
        val changed = fixture.settings.load().copy(model = "changed")
        fixture.settings.save(changed)
        assertFalse(controller.sendTerminal(next, next.preview, draft, next.assistant, next.provider,
            ChatController.TextSendTicket()) { fail("stale provider") })
        awaitControl("invalid provider drained") { controller.terminalPreparation() == null }
        assertEquals(draft.text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }
}
