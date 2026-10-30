package app.daiyu.android.storage

import android.content.ContentValues
import android.database.CursorWrapper
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class SQLiteRuntimeSnapshotTest {
    private val call = ToolInvocation(ToolCallId("screen-call"), "screen", "{\"operation\":\"observe\"}")
    private fun checkpoint(revision: Long, text: String = "reply-$revision"): RunCheckpoint {
        val tool = MessageBlock.Tool(call, "native receipt")
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, listOf(tool, MessageBlock.Text("reply", text)), RunStatus.RUNNING)
        return RunCheckpoint(message, emptyList(), ExecutionPhase.STREAMING,
            tools = listOf(ToolRecord(call, ToolDisposition.SUCCEEDED, tool.result)), owner = "owner", revision = revision)
    }
    private fun write(db: SQLiteDatabase, checkpoint: RunCheckpoint) {
        db.insertWithOnConflict("runtime_checkpoints", null, ContentValues().apply {
            put("message_id", checkpoint.message.id.value); put("conversation_id", checkpoint.message.conversationId.value)
            put("run_id", checkpoint.message.runId.value); put("owner", checkpoint.owner)
            put("revision", checkpoint.revision); put("phase", checkpoint.phase.name); put("process_owner", "process")
            put("payload", RunCheckpointCodec.encode(checkpoint))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    private fun database(afterQuery: ((String) -> Unit)? = null, work: (SQLiteDatabase) -> Unit) {
        val file = File.createTempFile("runtime-snapshot-", ".db")
        val factory = SQLiteDatabase.CursorFactory { _, driver, table, query ->
            object : CursorWrapper(SQLiteCursor(driver, table, query)) {
                override fun close() { super.close(); afterQuery?.invoke(query.toString()) }
            }
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, factory).use { db ->
                db.enableWriteAheadLogging()
                db.execSQL("CREATE TABLE runtime_checkpoints(message_id TEXT PRIMARY KEY, conversation_id TEXT, run_id TEXT, owner TEXT, revision INTEGER, phase TEXT, process_owner TEXT, payload TEXT)")
                work(db)
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private fun concurrentRead(interruptAt: String) {
        var hook: ((String) -> Unit)? = null
        database(afterQuery = { hook?.invoke(it) }) { db ->
            val before = checkpoint(1, "数🙂".repeat(30000))
            val after = checkpoint(2, "新🙂".repeat(40000))
            write(db, before)
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            var writer: Thread? = null
            hook = { sql -> if (sql.contains(interruptAt)) {
                hook = null
                writer = Thread {
                    try { write(db, after) } catch (caught: Throwable) { failure.set(caught) }
                    finally { done.countDown() }
                }.apply { start() }
                // The old reader lets this commit between queries. A snapshot holds it until extraction ends.
                if (!db.inTransaction() || android.os.Build.VERSION.SDK_INT >= 35) {
                    assertTrue("concurrent writer did not commit", done.await(10, TimeUnit.SECONDS))
                }
            } }
            try {
                assertEquals(before, SQLiteRuntimeStore.readCheckpointSnapshot(db, "assistant"))
            } finally {
                hook = null
                writer?.join(10_000)
                assertEquals(0L, done.count)
                failure.get()?.let { throw AssertionError(it) }
            }
            assertEquals(after, SQLiteRuntimeStore.readCheckpointSnapshot(db, "assistant"))
        }
    }
    @Test fun metadataAndPayloadCannotComeFromDifferentRevisions() = concurrentRead("SELECT conversation_id,run_id")
    @Test fun payloadChunksCannotMixConcurrentStreamingRevisions() = concurrentRead("SELECT substr(CAST(payload AS BLOB)")

    @Test fun aLargeUtf8CheckpointRemainsCompleteAcrossCursorWindows() = database { db ->
        val saved = checkpoint(1, "a".repeat(65535) + "数🙂".repeat(400000))
        write(db, saved)
        assertEquals(saved, SQLiteRuntimeStore.readCheckpointSnapshot(db, "assistant"))
        assertNull(SQLiteRuntimeStore.readCheckpointSnapshot(db, "missing"))
        assertFalse(db.inTransaction())
    }
    @Test fun theCallerStillOwnsItsTransactionAndRollback() = database { db ->
        val saved = checkpoint(1); write(db, saved)
        db.beginTransactionNonExclusive()
        try {
            assertEquals(saved, SQLiteRuntimeStore.readCheckpointSnapshot(db, "assistant"))
            assertTrue(db.inTransaction())
            write(db, checkpoint(2))
        } finally { db.endTransaction() }
        assertEquals(saved, SQLiteRuntimeStore.readCheckpointSnapshot(db, "assistant"))
    }
    @Test fun genuinelyCorruptMetadataIsStillRejectedAndReleasesTheRead() = database { db ->
        write(db, checkpoint(1))
        db.execSQL("UPDATE runtime_checkpoints SET revision=2 WHERE message_id='assistant'")
        assertTrue(runCatching { SQLiteRuntimeStore.readCheckpointSnapshot(db, "assistant") }.exceptionOrNull() is IllegalStateException)
        assertFalse(db.inTransaction())
    }
}
