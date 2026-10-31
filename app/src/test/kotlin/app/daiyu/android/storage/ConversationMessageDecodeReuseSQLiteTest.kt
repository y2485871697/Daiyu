package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real native SQLite: these are transaction/trigger tests, not mocked cursor tests. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationMessageDecodeReuseSQLiteTest {
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    private lateinit var user: Message
    private lateinit var assistant: Message
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database)
        runtime.recoverPending()
        val conversation = database.create("default").id
        user = Message(MessageId("user"), conversation, RunId("run"), "user",
            listOf(MessageBlock.Text("question", "question")), RunStatus.COMPLETED, createdAt = 12L)
        assistant = user.copy(id = MessageId("assistant"), role = "assistant", blocks = emptyList(),
            status = RunStatus.RUNNING)
        database.send(user, assistant)
    }
    @After fun tearDown() {
        if (::runtime.isInitialized) runtime.close()
        if (::database.isInitialized) database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
    }
    private fun transaction(work: (SQLiteDatabase, CommitMessageDecodeReuse) -> Unit) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            CommitMessageDecodeReuse().use { work(db, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun rejected(work: () -> Unit) {
        assertNotNull("integrity failure must reject", runCatching(work).exceptionOrNull())
    }
    private fun rows() = database.messages(user.conversationId).map { it.second }
    private fun checkpoint(message: Message = assistant, revision: Long = 1) =
        RunCheckpoint(message, listOf(user), ExecutionPhase.BOUNDARY, owner = "decode-owner", revision = revision)

    @Test fun allThreeValidationPointsReadSqlButUnchangedRowsDecodeOnlyOnce() {
        transaction { db, reuse ->
            assertEquals(assistant, database.runtimeMessage(db, assistant, reuse)) // first proof
            database.writeRuntimeMessage(db, assistant, reuse) // second + third proofs
            assertEquals(2, reuse.decodeCount) // assistant once + standalone user once
            assertEquals(2, reuse.retainedEntries)
            // A same-metadata, same-length different body is NOT a hit.
            val changed = assistant.copy(blocks = listOf(MessageBlock.Text("body", "数🙂")))
            database.writeRuntimeMessage(db, changed, reuse)
            assertEquals(3, reuse.decodeCount)
            assertEquals(changed, database.runtimeMessage(db, assistant, reuse))
            db.execSQL("UPDATE messages SET blocks=replace(blocks,'数🙂','字🙃') WHERE id='assistant'")
            assertEquals("字🙃", (database.runtimeMessage(db, assistant, reuse).blocks.single() as MessageBlock.Text).text)
            assertEquals(4, reuse.decodeCount)
        }
    }

    @Test fun rawColumnsNullAndAlternateJsonSpellingAreExactNotSemanticKeys() {
        transaction { db, reuse ->
            fun read() = database.storedMessage(db, assistant.conversationId, assistant.id.value, decodeReuse = reuse)
            read(); read()
            assertEquals(1, reuse.decodeCount)
            val changes = listOf<Pair<String, Any?>>("error" to "", "error" to null, "protocol" to "[ ]",
                "history_segments" to "[ ]", "blocks" to "[ ]", "created_at" to null,
                "created_at" to 13L, "status" to "COMPLETED", "run_id" to "another-run", "role" to "user")
            changes.forEachIndexed { index, (column, value) ->
                val values = ContentValues().apply {
                    when (value) { null -> putNull(column); is Long -> put(column, value); else -> put(column, value as String) }
                }
                assertEquals(1, db.update("messages", values, "id='assistant'", null))
                if (column == "run_id") {
                    // A legitimate identity change also needs its independent manifest updated.
                    db.execSQL("DELETE FROM message_video_witnesses WHERE message_id='assistant'")
                    app.daiyu.android.ControllerVideoManifestWitness.insert(db,
                        assistant.copy(runId = RunId(value as String)), emptyList())
                }
                read()
                // Returning to error=NULL can reuse the original exact key; all other changes are new.
                assertEquals(if (index == 0) 2 else index + 1, reuse.decodeCount)
            }
        }
    }

    @Test fun largeUtf8ScreenToolAndImageSurviveChunkedReadsAndReusableHistory() {
        val result = "a".repeat(65535) + "数🙂".repeat(310000)
        assertTrue(result.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
        val image = MessageBlock.Image(MessageImageReference(
            MessageImageOwner(assistant.conversationId.value, assistant.id.value),
            "11111111-1111-1111-1111-111111111111", "image/png", 500, "a".repeat(64), 100, 100))
        val blocks = listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("screen"), "screen", "{}"), result), image)
        val large = assistant.copy(blocks = blocks, historySegments = listOf(
            MessageHistorySegment(assistant.id, "assistant", blocks, createdAt = assistant.createdAt)))
        database.save(large)
        transaction { db, reuse ->
            assertEquals(large, database.runtimeMessage(db, large, reuse))
            database.writeRuntimeMessage(db, large, reuse)
            assertEquals(2, reuse.decodeCount)
            assertEquals(large, database.runtimeMessage(db, large, reuse))
        }
        assertEquals(large, rows().last())
    }

    @Test fun firstReadRejectsCorruptOldMessageBeforeWritingCheckpoint() {
        database.writableDatabase.execSQL("UPDATE messages SET history_segments='[{}]' WHERE id='assistant'")
        rejected { runtime.commitCheckpoint(checkpoint()) }
        assertNull(SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value))
    }

    @Test fun checkpointInsertTriggerChangingOldHistoryIsCaughtBySecondFullReadAndRollsBack() {
        val db = database.writableDatabase
        db.execSQL("CREATE TRIGGER change_old_message AFTER INSERT ON runtime_checkpoints BEGIN " +
            "UPDATE messages SET history_segments='[{}]' WHERE id=NEW.message_id; END")
        rejected { runtime.commitCheckpoint(checkpoint()) }
        assertEquals(listOf(user, assistant), rows())
        assertNull(SQLiteRuntimeStore.readCheckpointSnapshot(db, assistant.id.value))
        db.execSQL("DROP TRIGGER change_old_message")
        runtime.commitCheckpoint(checkpoint())
        assertEquals(checkpoint(), runtime.readCommittedCheckpoint(checkpoint()))
    }

    @Test fun checkpointUpdateTriggerChangingOldHistoryAlsoRollsBackAndKeepsDurableAck() {
        val first = checkpoint()
        runtime.commitCheckpoint(first)
        val db = database.writableDatabase
        db.execSQL("CREATE TRIGGER change_old_message AFTER UPDATE ON runtime_checkpoints BEGIN " +
            "UPDATE messages SET history_segments='[{}]' WHERE id=NEW.message_id; END")
        val next = checkpoint(assistant.copy(blocks = listOf(MessageBlock.Text("body", "next"))), 2)
        rejected { runtime.commitCheckpoint(next) }
        assertEquals(first, runtime.readCommittedCheckpoint(first))
        assertEquals(first, SQLiteRuntimeStore.readCheckpointSnapshot(db, assistant.id.value))
        assertEquals(listOf(user, assistant), rows())
        db.execSQL("DROP TRIGGER change_old_message")
        runtime.commitCheckpoint(next)
        assertEquals(next, runtime.readCommittedCheckpoint(next))
    }

    @Test fun messageUpdateTriggerCorruptingNewHistoryIsCaughtByThirdReadAndRollsBack() {
        val db = database.writableDatabase
        db.execSQL("CREATE TRIGGER break_new_history AFTER UPDATE ON messages WHEN NEW.id='assistant' AND NEW.history_segments!='[{}]' BEGIN " +
            "UPDATE messages SET history_segments='[{}]' WHERE id=NEW.id; END")
        rejected { runtime.commitCheckpoint(checkpoint(assistant.copy(blocks = listOf(MessageBlock.Text("body", "new"))))) }
        assertEquals(listOf(user, assistant), rows())
        assertNull(SQLiteRuntimeStore.readCheckpointSnapshot(db, assistant.id.value))
    }

    @Test fun messageTimeChangeBetweenFirstAndSecondReadIsNotHiddenByCache() {
        val db = database.writableDatabase
        db.execSQL("CREATE TRIGGER change_old_time AFTER INSERT ON runtime_checkpoints BEGIN " +
            "UPDATE messages SET created_at=13 WHERE id=NEW.message_id; END")
        rejected { runtime.commitCheckpoint(checkpoint()) }
        assertEquals(listOf(user, assistant), rows())
        assertNull(SQLiteRuntimeStore.readCheckpointSnapshot(db, assistant.id.value))
    }

    @Test fun cacheHitStillChecksChangedVideoWitnessAndSideTable() {
        transaction { db, reuse ->
            database.runtimeMessage(db, assistant, reuse)
            db.execSQL("UPDATE message_video_witnesses SET manifest_sha256=? WHERE message_id='assistant'", arrayOf("0".repeat(64)))
            rejected { database.runtimeMessage(db, assistant, reuse) }
            assertEquals(1, reuse.decodeCount) // hit revalidated witness, did not decode again
            assertEquals(0, reuse.retainedEntries)
        }
        // Restore the text-only witness using the accepted original manifest, not a cache.
        transaction { db, _ ->
            db.execSQL("DELETE FROM message_video_witnesses WHERE message_id='assistant'")
            app.daiyu.android.ControllerVideoManifestWitness.insert(db, assistant, emptyList())
        }
        transaction { db, reuse ->
            database.runtimeMessage(db, assistant, reuse)
            db.execSQL("INSERT INTO message_video_inputs(message_id,payload) VALUES('assistant','{}')")
            rejected { database.runtimeMessage(db, assistant, reuse) }
            assertEquals(1, reuse.decodeCount)
        }
    }

    @Test fun historyRescansFreshIdsAndRejectsCrossMessageMismatchEvenWithCachedAssistant() {
        val extra = user.copy(id = MessageId("extra"), blocks = listOf(MessageBlock.Text("extra", "accepted")))
        val withHistory = assistant.copy(historySegments = listOf(
            MessageHistorySegment(assistant.id, "assistant", emptyList()),
            MessageHistorySegment(extra.id, "user", extra.blocks)))
        database.save(withHistory)
        rejected {
            transaction { db, reuse ->
                database.runtimeMessage(db, withHistory, reuse)
                database.insertRuntimeSupplement(db, withHistory, extra.copy(blocks = listOf(MessageBlock.Text("extra", "changed"))))
                database.writeRuntimeMessage(db, withHistory, reuse)
            }
        }
        assertEquals(listOf(user, withHistory), rows()) // inserted ID and writes rolled back
    }

    @Test fun supplementalMessageAndCheckpointAckRemainAtomicAndDurable() {
        val first = checkpoint()
        runtime.commitCheckpoint(first)
        val extra = user.copy(id = MessageId("extra"), blocks = listOf(MessageBlock.Text("extra", "追加🙂")), createdAt = null)
        val next = first.copy(revision = 2, supplements = listOf(extra))
        runtime.commitSupplement(next, extra)
        assertEquals(next, runtime.readCommittedCheckpoint(next))
        assertEquals(listOf(user, next.message, extra), rows())
        rejected { runtime.commitSupplement(next.copy(revision = 3), extra) }
        assertEquals(next, runtime.readCommittedCheckpoint(next))
        runtime.close(); database.close()
        database = ConversationDatabase(RuntimeEnvironment.getApplication())
        assertEquals(next, SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value))
        assertEquals(listOf(user, next.message, extra), rows())
    }
}
