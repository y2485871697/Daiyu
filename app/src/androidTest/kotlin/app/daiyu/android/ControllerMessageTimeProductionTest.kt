package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.SupplementAdmission
import app.daiyu.android.storage.ConversationDatabase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Controller/SQLite/Runner lane; synthetic local model only, not a UI acceptance test. */
@Suppress("DEPRECATION")
class ControllerMessageTimeProductionTest : InstrumentationTestCase() {
    private val fixtures = mutableListOf<RunControlFixture>()
    private fun fresh() = RunControlFixture(instrumentation.targetContext).also {
        fixtures.add(it)
        awaitControl("controller ready") { it.controller.ready }
    }
    override fun tearDown() {
        try { fixtures.forEach { it.close() } } finally { super.tearDown() }
    }

    fun testAcceptedPairSharesTimeWithRunnerAndCompletionDoesNotStampLegacyHistory() {
        val f = fresh()
        val conversation = f.database.create("default")
        val oldUser = Message(MessageId("legacy-user"), conversation.id, RunId("legacy-run"),
            Message.USER_ROLE, listOf(MessageBlock.Text("old", "old")), RunStatus.COMPLETED)
        val oldAssistant = oldUser.copy(id = MessageId("legacy-assistant"), role = Message.ASSISTANT_ROLE,
            status = RunStatus.RUNNING)
        f.database.send(oldUser, oldAssistant)
        f.database.save(oldAssistant.copy(status = RunStatus.COMPLETED))
        val session = f.session("completed")
        val before = System.currentTimeMillis()
        assertNotNull(f.controller.send(conversation, "accepted"))
        val after = System.currentTimeMillis()
        val active = checkNotNull(f.controller.active)
        assertNotNull(active.createdAt)
        assertTrue(checkNotNull(active.createdAt) in before..after)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val rows = f.database.messages(conversation.id).map { it.second }
        val user = rows.single { it.runId == active.runId && it.role == Message.USER_ROLE }
        assertEquals(active.createdAt, user.createdAt)
        assertEquals(active, rows.single { it.id == active.id })
        assertEquals(user, f.histories.single().single { it.id == user.id })
        assertTrue(f.histories.single().filter { it.runId == oldUser.runId }.all { it.createdAt == null })
        session.release.countDown()
        awaitControl("completed") { f.controller.active == null }
        val completed = f.database.messages(conversation.id).single { it.second.id == active.id }.second
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertEquals(active.createdAt, completed.createdAt)
        assertTrue(completed.historySegments.isNotEmpty())
        assertTrue(completed.historySegments.all { it.createdAt == active.createdAt })
        val reopened = ConversationDatabase(f.application)
        try {
            val restored = reopened.messages(conversation.id).map { it.second }
            assertEquals(listOf(oldUser, oldAssistant.copy(status = RunStatus.COMPLETED)), restored.take(2))
            assertEquals(completed, restored.single { it.id == active.id })
        } finally { reopened.close() }
    }

    fun testPausedSupplementGetsItsOwnAcceptedTimeAndRetainsItAcrossResume() {
        val f = fresh()
        val first = f.session()
        val second = f.session("after supplement")
        assertNotNull(f.controller.send(null, "initial"))
        val owner = checkNotNull(f.controller.active)
        assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        controlMain { assertTrue(f.controller.pause(owner)) }
        awaitControl("paused") { f.controller.runState(owner)?.phase == ExecutionPhase.PAUSED }
        val result = AtomicReference<SupplementAdmission?>()
        val id = MessageId("timed-supplement")
        val before = System.currentTimeMillis()
        controlMain { assertTrue(f.controller.admitSupplement(owner, id, "added") { result.set(it) }) }
        val after = System.currentTimeMillis()
        awaitControl("supplement receipt") { result.get() != null }
        assertEquals(SupplementAdmission.Accepted(id), result.get())
        val supplement = f.database.messages(owner.conversationId).single { it.second.id == id }.second
        assertTrue(checkNotNull(supplement.createdAt) in before..after)
        controlMain {
            assertEquals(owner.createdAt, f.controller.active!!.createdAt)
        }
        assertTrue(second.entered.await(5, TimeUnit.SECONDS))
        assertEquals(supplement.createdAt, MessageHistory.expand(f.histories.last()).single { it.id == id }.createdAt)
        second.release.countDown()
        awaitControl("resumed completion") { f.controller.active == null }
        val rows = f.database.messages(owner.conversationId).map { it.second }
        val completed = rows.single { it.id == owner.id }
        assertEquals(owner.createdAt, completed.createdAt)
        assertEquals(supplement.createdAt, completed.historySegments.single { it.messageId == id }.createdAt)
        assertEquals(supplement.createdAt, MessageHistory.expand(rows).single { it.id == id }.createdAt)
    }
}
