package app.daiyu.android

import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Controller/SQLite/Runner/catalog path. Model requests NO tools: no shell or network. */
@Suppress("DEPRECATION")
class ControllerTerminalJobSendTest : InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    private val controller get() = fixture.controller
    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext) {}
        awaitControl("ready") { controller.ready }
    }
    override fun tearDown() { try { fixture.close() } finally { super.tearDown() } }
    private fun draft(): TerminalDraftSnapshot {
        fixture.database.draft("解释独立job的输出，不是命令")
        return TerminalDraftSnapshot(TerminalDraftOwner(null, 71, 82), fixture.database.draft())
    }
    fun testAsyncPrepareAndConfirmDoNotStartAndUseExactIndependentCommand() {
        val draft = draft()
        val p = checkNotNull(controller.prepareTerminalJobSend(null, draft, "printf 'exact job'", "async", null))
        assertEquals("printf 'exact job'", p.plan.command)
        assertEquals("async", p.plan.mode)
        assertFalse(p.plan.configuration.root)
        controlMain { assertTrue(controller.confirmTerminalJob(p, draft)) }
        assertEquals(0, fixture.opens.get())
        assertNull(controller.active)
        assertTrue(fixture.database.conversations().isEmpty())
        assertFalse(controller.sendText(null, 71, 82, draft.text) { fail("reserved job") })
        controlMain { controller.rejectTerminalJob(p) }
        awaitControl("job preview drained") { controller.terminalJobPreparation() == null }
    }
    fun testDefaultAuthorizedDaemonSendUsesSameDurableTextReceiptAndRetainedOriginalSessionWithoutStarting() {
        val draft = draft()
        val p = checkNotNull(controller.prepareTerminalJobSend(null, draft, "printf daemon", "daemon", null))
        val session = fixture.session(holdSeconds = 120)
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        val ticket = ChatController.TextSendTicket()
        controlMain {
            assertTrue(controller.sendTerminalJob(p, draft, ticket) { result.set(it); done.countDown() })
            assertFalse(controller.sendTerminalJob(p, draft, ChatController.TextSendTicket()) { fail("duplicate") })
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(session.entered.await(10, TimeUnit.SECONDS))
        val accepted = result.get() as ChatController.TextSendResult.Accepted
        assertEquals(p.identity.conversationId, accepted.conversation.id)
        val retained = controller.terminalJobRuns(accepted.conversation.id).single()
        assertSame(p.plan, retained.plan)
        assertNull(retained.jobId) // model did not invoke the start tool
        assertEquals("", fixture.database.draft())
        controlMain { controller.acknowledgeTextSend(ticket) }
        session.release.countDown()
        awaitControl("run terminal") { controller.active == null }
        assertSame(retained, controller.terminalJobRuns(accepted.conversation.id).single())
        assertEquals("terminal_job_not_dispatched_or_unconfirmed", controller.inspectTerminalJobOnWorker(accepted.conversation.id, retained).getString("error"))
    }
    fun testChangedDraftAndLinuxDaemonAreRejectedWithoutFallbackOrModelOpen() {
        val draft = draft()
        assertNull(controller.prepareTerminalJobSend(null, draft, "echo no", "daemon",
            app.daiyu.android.terminal.LinuxGenerationRef("not-installed", "generation")))
        val p = checkNotNull(controller.prepareTerminalJobSend(null, draft, "echo no", "async", null))
        controlMain { assertFalse(controller.confirmTerminalJob(p, draft.copy(text = "changed"))) }
        assertEquals(0, fixture.opens.get())
        assertEquals(draft.text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
    }
}
