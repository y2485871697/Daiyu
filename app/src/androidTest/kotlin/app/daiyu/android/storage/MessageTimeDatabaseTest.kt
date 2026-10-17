package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.testContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class MessageTimeDatabaseTest : InstrumentationTestCase() {
    private val contexts = mutableListOf<Context>()
    private val helpers = mutableListOf<ConversationDatabase>()
    private fun fixture(): Pair<Context, ConversationDatabase> {
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "message-time-$token-")
        val directory = File(base.cacheDir, "message-time-$token").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        contexts.add(context)
        return context to ConversationDatabase(context).also { helpers.add(it) }
    }
    override fun tearDown() {
        try { helpers.forEach { it.close() }; contexts.forEach { it.deleteDatabase("conversations.db"); it.filesDir.deleteRecursively() } }
        finally { super.tearDown() }
    }
    private fun pair(db: ConversationDatabase, time: Long?): Pair<Message, Message> {
        val conversation = db.create("default").id
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId("u-${run.value}"), conversation, run, "user",
            listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED, createdAt = time)
        return user to user.copy(id = MessageId("a-${run.value}"), role = "assistant",
            blocks = emptyList(), status = RunStatus.RUNNING)
    }
    private fun rows(db: ConversationDatabase, pair: Pair<Message, Message>) = db.messages(pair.first.conversationId).map { it.second }
    private fun reject(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("invalid message time must reject", rejected)
    }

    fun testNewAcceptedTimesSurviveStreamingCompletionAndReopen() {
        val (_, db) = fixture()
        val before = System.currentTimeMillis()
        val turn = pair(db, System.currentTimeMillis())
        db.send(turn.first, turn.second)
        val after = System.currentTimeMillis()
        assertTrue(turn.first.createdAt!! in before..after)
        val partial = turn.second.copy(blocks = listOf(MessageBlock.Text("answer", "partial")))
        db.save(partial)
        assertEquals(listOf(turn.first, partial), rows(db, turn))
        reject { db.save(partial.copy(createdAt = after + 1000)) }
        assertEquals(partial, rows(db, turn).last())
        val done = partial.copy(blocks = listOf(MessageBlock.Text("answer", "complete")), status = RunStatus.COMPLETED)
        db.save(done)
        db.close()
        assertEquals(listOf(turn.first, done), rows(db, turn))
        assertEquals(listOf(turn.first, done), ContextService.read(db.readableDatabase, turn.first.conversationId))
        assertEquals(19, db.readableDatabase.version)
    }

    fun testUnknownTimesRemainNullOnSaveRecoveryReopenAndTextImport() {
        val (_, db) = fixture(); val turn = pair(db, null)
        db.send(turn.first, turn.second)
        db.save(turn.second.copy(blocks = listOf(MessageBlock.Text("answer", "partial"))))
        db.recover(); db.close()
        assertTrue(rows(db, turn).all { it.createdAt == null })
        val backup = db.exportBackup()
        val payload = JSONObject(JSONObject(backup).getString("payload")).getJSONArray("messages")
        for (i in 0 until payload.length()) assertFalse(payload.getJSONObject(i).has("created_at"))
        val (_, target) = fixture(); target.importBackup(backup); target.close()
        assertEquals(rows(db, turn), rows(target, turn))
        assertEquals(backup, target.exportBackup())
    }

    fun testCheckpointStreamingAndCrashRecoveryRetainOriginalTime() {
        val (_, db) = fixture(); val turn = pair(db, 1_700_000_000_123L)
        val runtime = SQLiteRuntimeStore.acquire(db)
        try {
            runtime.recoverPending()
            db.send(turn.first, turn.second)
            val first = RunCheckpoint(turn.second, listOf(turn.first), ExecutionPhase.BOUNDARY,
                owner = "timestamp-owner", revision = 1)
            assertEquals(first, RunCheckpointCodec.decode(RunCheckpointCodec.encode(first)))
            runtime.commitCheckpoint(first)
            val streamed = first.copy(message = first.message.copy(blocks = listOf(MessageBlock.Text("answer", "partial"))), revision = 2)
            runtime.commitCheckpoint(streamed)
            assertEquals(streamed, runtime.readCommittedCheckpoint(streamed))
        } finally { runtime.close(); db.close() }
        val reopened = SQLiteRuntimeStore.acquire(db)
        try {
            reopened.recoverPending()
            val recovered = rows(db, turn).last()
            assertEquals(RunStatus.INTERRUPTED, recovered.status)
            assertEquals(turn.second.createdAt, recovered.createdAt)
        } finally { reopened.close() }
    }

    fun testArchiveAndNeutralBackupPreserveRealTimeWithoutLocalRuntimeData() {
        val (context, db) = fixture(); val turn = pair(db, 1_700_000_000_123L)
        db.send(turn.first, turn.second)
        val done = turn.second.copy(status = RunStatus.COMPLETED)
        db.save(done)
        val originals = listOf(turn.first, done)
        val archive = ArchiveCodec.encode(originals)
        assertEquals(originals, ArchiveCodec.decode(archive))
        assertEquals(String(archive, Charsets.UTF_8), String(ArchiveCodec.encode(ArchiveCodec.decode(archive)), Charsets.UTF_8))
        val archiveId = "message-time-archive"
        val hash = PrivateBlobs(context).put(archive)
        db.writableDatabase.execSQL("INSERT INTO context_archives(id,conversation_id,prefix_count,revision,summary,blob_hash,created,active) VALUES(?,?,?,?,?,?,?,0)",
            arrayOf<Any>(archiveId, turn.first.conversationId.value, originals.size, ContextRevision.digest(originals), "historical summary", hash, 1L))
        val exporter = NeutralBackupService(context, db)
        context.getSharedPreferences("model-settings", Context.MODE_PRIVATE).edit()
            .putString("api-key", "P35_SYNTHETIC_SECRET").commit()
        assertTrue(exporter.captureNonSecretConfiguration(context).isNotEmpty())
        val backup = exporter.exportPackage()
        assertFalse(backup.contains("P35_SYNTHETIC_SECRET"))
        assertEquals(1, exporter.preflight(backup).archives)
        assertFalse(backup.contains("runtime_checkpoints"))
        assertFalse(backup.contains("timestamp-owner"))
        val (targetContext, target) = fixture()
        val importer = NeutralBackupService(targetContext, target)
        importer.importPackage(backup)
        target.close()
        assertEquals(originals, rows(target, turn))
        assertEquals(originals, ContextService(targetContext, target).archivePage(archiveId).messages)
        assertTrue(NeutralBackupService(targetContext, target).importPackage(backup).alreadyImported)
        val legacy = JSONArray(String(archive, Charsets.UTF_8))
        for (i in 0 until legacy.length()) legacy.getJSONObject(i).remove("created_at")
        assertTrue(ArchiveCodec.decode(legacy.toString().toByteArray(Charsets.UTF_8)).all { it.createdAt == null })
    }

    fun testTimeWireFieldRejectsCoercionAndNeverInterpretsProtocolAsTime() {
        val (_, db) = fixture(); val turn = pair(db, 1L)
        val original = JSONArray(String(ArchiveCodec.encode(listOf(turn.first)), Charsets.UTF_8))
        for (bad in listOf<Any>("1700000000000", 1.5, -1L, true, JSONObject())) {
            val changed = JSONArray(original.toString())
            changed.getJSONObject(0).put("created_at", bad)
            reject { ArchiveCodec.decode(changed.toString().toByteArray(Charsets.UTF_8)) }
        }
        assertEquals(0L, MessageTimeCodec.read(JSONObject().put("created_at", 0L)))
        assertNull(MessageTimeCodec.read(JSONObject().put("created_at", JSONObject.NULL)))
        assertNull(MessageTimeCodec.read(JSONObject().put("protocol", "{\"created_at\":1700000000000}")))
        val segment = MessageHistorySegment(turn.first.id, "user", turn.first.blocks, createdAt = 123L)
        assertEquals(listOf(segment), MessageHistoryCodec.decode(MessageHistoryCodec.encode(listOf(segment))))
    }

    fun testTextBackupRejectsMalformedTimesAtomicallyAndRetainsLegacyShape() {
        val (_, db) = fixture(); val turn = pair(db, 123L)
        db.send(turn.first, turn.second); db.save(turn.second.copy(status = RunStatus.COMPLETED))
        val text = db.exportBackup()
        val (_, target) = fixture()
        val before = target.exportBackup()
        for (bad in listOf<Any>("123", 1.5, -1L, false)) {
            val envelope = JSONObject(text)
            val payload = JSONObject(envelope.getString("payload"))
            payload.getJSONArray("messages").getJSONObject(0).put("created_at", bad)
            val raw = payload.toString()
            val changed = envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
            reject { target.importBackup(changed) }
            assertEquals(before, target.exportBackup())
        }
        val envelope = JSONObject(text)
        val payload = JSONObject(envelope.getString("payload"))
        val messages = payload.getJSONArray("messages")
        for (i in 0 until messages.length()) messages.getJSONObject(i).remove("created_at")
        val raw = payload.toString()
        val legacy = envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
        target.importBackup(legacy)
        assertTrue(rows(target, turn).all { it.createdAt == null })
        assertEquals(legacy, target.exportBackup())
    }

    fun testRealV14MessageTableMigrationKeepsNonEmptyVideoWitnessesAndCommandsUntouched() {
        val (context, db) = fixture(); val turn = pair(db, null)
        val owner = MessageVideoOwner(turn.first.conversationId.value, turn.first.id.value)
        val bytes = byteArrayOf(1, 3, 5, 7)
        val video = MessageVideoReference(owner, "11111111-1111-1111-1111-111111111111", "video/mp4",
            bytes.size.toLong(), PrivateBlobs.digest(bytes), 1000L, 32, 24)
        val inputs = listOf(MessageVideoInput(owner, listOf(video)))
        assertEquals(MessageVideoInputCodec.encode(turn.first, inputs),
            MessageVideoInputCodec.encode(turn.first.copy(createdAt = 123L), inputs))
        db.send(turn.first, turn.second, videoInputs = inputs)
        db.save(turn.second.copy(status = RunStatus.COMPLETED))
        val originalRows = rows(db, turn)
        db.close()
        val before = context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            raw.execSQL("PRAGMA foreign_keys=OFF")
            // Recreate the actual pre-v15 messages shape, not a version-only downgrade.
            raw.execSQL("CREATE TABLE messages_v14(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL,status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL,history_segments TEXT NOT NULL DEFAULT '[]')")
            val columns = "seq,id,conversation_id,run_id,role,blocks,status,error,protocol,history_segments"
            raw.execSQL("INSERT INTO messages_v14($columns) SELECT $columns FROM messages")
            raw.execSQL("DROP TABLE messages")
            raw.execSQL("ALTER TABLE messages_v14 RENAME TO messages")
            raw.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
            raw.version = 14
            listOf("message_video_inputs", "message_video_witnesses", "child_tasks", "child_task_commands").associateWith { snapshot(raw, it) }
        }
        assertFalse(before.getValue("message_video_inputs").isEmpty())
        assertEquals(19, db.readableDatabase.version)
        assertEquals(originalRows, rows(db, turn))
        assertTrue(rows(db, turn).all { it.createdAt == null })
        before.forEach { (table, values) -> assertEquals(values, snapshot(db.readableDatabase, table)) }
        db.close()
        assertEquals(originalRows, rows(db, turn))
        before.forEach { (table, values) -> assertEquals(values, snapshot(db.readableDatabase, table)) }
        val commandColumns = db.readableDatabase.rawQuery("PRAGMA table_info(child_tasks)", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), c.getString(2)) }
        }
        assertEquals("INTEGER", commandColumns["control_revision"])
        assertEquals("TEXT", commandColumns["source_task_id"])
        assertEquals("TEXT", commandColumns["command_kind"])
    }

    private fun snapshot(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
}
