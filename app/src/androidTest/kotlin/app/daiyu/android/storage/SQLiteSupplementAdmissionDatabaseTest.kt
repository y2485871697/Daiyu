package app.daiyu.android.storage

import android.test.InstrumentationTestCase
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class SQLiteSupplementAdmissionDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    private lateinit var first: RunCheckpoint
    private lateinit var extra: Message
    private val next get() = first.copy(revision = 2, supplements = listOf(extra))
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "supplement-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        val c = database.create("default"); val r = RunId("run")
        val user = Message(MessageId("user"), c.id, r, Message.USER_ROLE, listOf(MessageBlock.Text("user", "initial")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("answer"), role = Message.ASSISTANT_ROLE, blocks = emptyList(), status = RunStatus.RUNNING)
        database.send(user, answer); database.draft(c.id, "draft remains")
        first = RunCheckpoint(answer, listOf(user), ExecutionPhase.PAUSED, owner = "owner", revision = 1)
        runtime.commitCheckpoint(first)
        extra = user.copy(id = MessageId("extra"), blocks = listOf(MessageBlock.Text("user", "追加😀")))
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db") } finally { super.tearDown() }
    }
    private fun saved() = DatabaseTransactions { database.readableDatabase }.run {
        SQLiteRuntimeStore.readCheckpointSnapshot(it, first.message.id.value)
    }
    private fun assertUnchanged() {
        assertEquals(first, saved()); assertEquals(2, database.messages(first.message.conversationId).size)
        assertEquals("draft remains", database.conversations().single().draft)
    }
    private fun rejected(checkpoint: RunCheckpoint = next, user: Message = extra) {
        try { runtime.commitSupplement(checkpoint, user); fail("admission accepted") } catch (_: Exception) {}
        assertUnchanged()
    }
    fun testAdmissionAtomicallyPersistsUserCheckpointAndLeavesDraft() {
        runtime.commitSupplement(next, extra)
        assertEquals(next, saved())
        assertEquals(extra, database.messages(extra.conversationId).single { it.second.id == extra.id }.second)
        assertEquals("draft remains", database.conversations().single().draft)
        assertEquals(3, database.messages(extra.conversationId).size)
    }
    fun testStaleOwnerRevisionAndWrongRunAreRejectedWithoutRows() {
        rejected(next.copy(owner = "other"))
        rejected(next.copy(revision = 3))
        val wrong = extra.copy(runId = RunId("other"))
        rejected(next.copy(supplements = listOf(wrong)), wrong)
        rejected(next.copy(phase = ExecutionPhase.STREAMING))
    }
    fun testDuplicateMessageIdIsNeverIgnoredOrReplaced() {
        extra = extra.copy(id = first.history.single().id)
        rejected()
        assertEquals("initial", (database.messages(extra.conversationId).first().second.blocks.single() as MessageBlock.Text).text)
    }
    fun testInsertCheckpointAndAssistantFailuresRollbackTheWholeAdmission() {
        for (target in listOf("user", "checkpoint", "assistant")) {
            val sql = when (target) {
                "user" -> "BEFORE INSERT ON messages WHEN new.id='extra'"
                "checkpoint" -> "BEFORE UPDATE ON runtime_checkpoints"
                else -> "BEFORE UPDATE ON messages WHEN old.id='answer'"
            }
            database.writableDatabase.execSQL("CREATE TRIGGER reject_admission $sql BEGIN SELECT RAISE(ABORT,'fixture'); END")
            try { rejected() } finally { database.writableDatabase.execSQL("DROP TRIGGER reject_admission") }
            database.close(); assertUnchanged()
        }
    }
    fun testMismatchedAndDuplicateCheckpointEntriesDoNotWrite() {
        rejected(next.copy(supplements = emptyList()))
        rejected(next.copy(supplements = listOf(extra, extra)))
        rejected(next.copy(supplements = listOf(extra.copy(blocks = listOf(MessageBlock.Text("user", "other"))))))
    }
    fun testReopenRecoveryRetainsAdmissionAndNeverContinuesRun() {
        runtime.commitSupplement(next, extra)
        runtime.close(); database.close()
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        val recovered = checkNotNull(saved())
        assertEquals(ExecutionPhase.TERMINAL, recovered.phase); assertEquals(RunStatus.INTERRUPTED, recovered.message.status)
        assertEquals(listOf(extra), recovered.supplements)
        assertEquals(extra, database.messages(extra.conversationId).single { it.second.id == extra.id }.second)
        assertEquals("draft remains", database.conversations().single().draft)
        val late = extra.copy(id = MessageId("late"))
        try { runtime.commitSupplement(recovered.copy(revision = recovered.revision + 1, phase = ExecutionPhase.PAUSED,
            message = recovered.message.copy(status = RunStatus.RUNNING), supplements = listOf(late)), late)
            fail("terminal accepted") } catch (_: Exception) {}
        assertEquals(recovered, saved())
        assertEquals(3, database.messages(extra.conversationId).size)
    }

    fun testAlreadyCommittedIdCannotBeReplayedAsAnotherAdmission() {
        runtime.commitSupplement(next, extra)
        try { runtime.commitSupplement(next.copy(revision = 3), extra); fail("duplicate replay accepted") } catch (_: Exception) {}
        assertEquals(next, saved()); assertEquals(3, database.messages(extra.conversationId).size)
        assertEquals(extra, database.messages(extra.conversationId).single { it.second.id == extra.id }.second)
    }
}
