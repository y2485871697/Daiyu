package app.daiyu.android.storage

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.testContext
import java.util.UUID

/** Device counterparts of the native-SQLite JVM regressions; no APK execution is required locally. */
@Suppress("DEPRECATION")
class CommitMessageDecodeDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    private lateinit var user: Message
    private lateinit var assistant: Message
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "decode-reuse-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        user = Message(MessageId("user"), database.create("default").id, RunId("run"), "user",
            listOf(MessageBlock.Text("question", "question")), RunStatus.COMPLETED, createdAt = 123L)
        assistant = user.copy(id = MessageId("assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
        database.send(user, assistant)
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db") }
        finally { super.tearDown() }
    }
    private fun checkpoint(revision: Long = 1) = RunCheckpoint(assistant, listOf(user),
        ExecutionPhase.BOUNDARY, owner = "owner", revision = revision)
    private fun rejected(work: () -> Unit) {
        assertNotNull("integrity failure must reject", runCatching(work).exceptionOrNull())
    }
    private fun rows() = database.messages(user.conversationId).map { it.second }

    fun testThreeProofsReuseDecodeButSameMetadataDifferentContentMisses() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            CommitMessageDecodeReuse().use { reuse ->
                assertEquals(assistant, database.runtimeMessage(db, assistant, reuse))
                database.writeRuntimeMessage(db, assistant, reuse)
                assertEquals(2, reuse.decodeCount)
                val changed = assistant.copy(blocks = listOf(MessageBlock.Text("body", "数🙂")))
                database.writeRuntimeMessage(db, changed, reuse)
                assertEquals(3, reuse.decodeCount)
                db.execSQL("UPDATE messages SET blocks=replace(blocks,'数🙂','字🙃') WHERE id='assistant'")
                assertEquals("字🙃", (database.runtimeMessage(db, assistant, reuse).blocks.single() as MessageBlock.Text).text)
                assertEquals(4, reuse.decodeCount)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun testCheckpointTriggersAndPostUpdateHistoryCannotSkipSecondOrThirdProof() {
        val db = database.writableDatabase
        val triggers = listOf(
            "AFTER INSERT ON runtime_checkpoints BEGIN UPDATE messages SET history_segments='[{}]' WHERE id=NEW.message_id; END",
            "AFTER UPDATE ON messages WHEN NEW.id='assistant' AND NEW.history_segments!='[{}]' BEGIN UPDATE messages SET history_segments='[{}]' WHERE id=NEW.id; END")
        triggers.forEach { trigger ->
            db.execSQL("CREATE TRIGGER corrupt_history $trigger")
            try {
                rejected { runtime.commitCheckpoint(checkpoint()) }
                assertEquals(listOf(user, assistant), rows())
                assertNull(SQLiteRuntimeStore.readCheckpointSnapshot(db, assistant.id.value))
            } finally { db.execSQL("DROP TRIGGER corrupt_history") }
        }
        runtime.commitCheckpoint(checkpoint())
        assertEquals(checkpoint(), runtime.readCommittedCheckpoint(checkpoint()))
        db.execSQL("CREATE TRIGGER corrupt_history AFTER UPDATE ON runtime_checkpoints BEGIN " +
            "UPDATE messages SET history_segments='[{}]' WHERE id=NEW.message_id; END")
        try { rejected { runtime.commitCheckpoint(checkpoint(2)) } }
        finally { db.execSQL("DROP TRIGGER corrupt_history") }
        assertEquals(checkpoint(), runtime.readCommittedCheckpoint(checkpoint()))
    }

    fun testCacheHitRevalidatesVideoWitnessAndSideTable() {
        val db = database.writableDatabase
        for (change in listOf(
            "DELETE FROM message_video_witnesses WHERE message_id='assistant'",
            "INSERT INTO message_video_inputs(message_id,payload) VALUES('assistant','{}')")) {
            db.beginTransaction()
            try {
                CommitMessageDecodeReuse().use { reuse ->
                    database.runtimeMessage(db, assistant, reuse)
                    db.execSQL(change)
                    rejected { database.runtimeMessage(db, assistant, reuse) }
                    assertEquals(1, reuse.decodeCount)
                }
            } finally { db.endTransaction() } // deliberately rollback each injected fault
        }
        assertEquals(listOf(user, assistant), rows())
    }

    fun testSupplementAndDurableAckSurviveReopenWithoutReusingCommitContext() {
        runtime.commitCheckpoint(checkpoint())
        val extra = user.copy(id = MessageId("extra"), blocks = listOf(MessageBlock.Text("extra", "追加🙂")), createdAt = null)
        val next = checkpoint(2).copy(supplements = listOf(extra))
        runtime.commitSupplement(next, extra)
        assertEquals(next, runtime.readCommittedCheckpoint(next))
        rejected { runtime.commitSupplement(next.copy(revision = 3), extra) }
        assertEquals(next, runtime.readCommittedCheckpoint(next))
        runtime.close(); database.close()
        assertEquals(next, SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value))
        assertEquals(listOf(user, assistant, extra), rows())
    }
}
