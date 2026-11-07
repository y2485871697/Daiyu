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

    private fun nativeHistoryMessage(): Message {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aE6sAAAAASUVORK5CYII=")
        val padding = ByteArray(1_000_000)
        val type = "raNd".toByteArray(Charsets.US_ASCII)
        val crc = java.util.zip.CRC32().apply { update(type); update(padding) }.value.toInt()
        val chunk = java.io.ByteArrayOutputStream().apply {
            write(java.nio.ByteBuffer.allocate(4).putInt(padding.size).array()); write(type); write(padding)
            write(java.nio.ByteBuffer.allocate(4).putInt(crc).array())
        }.toByteArray()
        val bytes = png.copyOfRange(0, png.size - 12) + chunk + png.copyOfRange(png.size - 12, png.size)
        val args = "{\"operation\":\"screenshot\"}"
        val result = org.json.JSONObject().put("observation_id", "native-fixture").put("width", 1).put("height", 1)
            .put("mime_type", "image/png").put("data_base64", java.util.Base64.getEncoder().encodeToString(bytes)).toString()
        val block = MessageBlock.Tool(ToolInvocation(ToolCallId("large-screen"), "screen", args), result)
        val protocol = listOf(org.json.JSONObject().put("type", "function_call").put("call_id", "large-screen")
            .put("name", "screen").put("arguments", args).toString(), org.json.JSONObject()
            .put("type", "function_call_output").put("call_id", "large-screen").put("output", result).toString())
        assertTrue(protocol.last().length > 1_000_000)
        return assistant.copy(blocks = listOf(block), protocolItems = protocol, historySegments = listOf(
            MessageHistorySegment(assistant.id, "assistant", listOf(block), protocol, createdAt = assistant.createdAt)))
    }

    fun testLargeNativeReceiptProofIsSharedButEverySqlAndVideoWitnessStillRuns() {
        val original = nativeHistoryMessage()
        database.save(original)
        val db = database.writableDatabase
        val writer = CheckpointJsonWriter()
        CommitMessageDecodeReuse().use { reuse ->
            repeat(4) { n ->
                val next = original.copy(blocks = original.blocks + MessageBlock.Text("body", "append-$n"))
                val cp = RunCheckpoint(next, listOf(user), ExecutionPhase.BOUNDARY, owner = "owner", revision = n + 1L)
                val bytes = RunCheckpointCodec.encodeUtf8(cp, writer, historyReceipts = reuse.historyReceipts)
                assertTrue(bytes.size > 1_000_000)
                db.beginTransaction()
                try {
                    database.runtimeMessage(db, next, reuse)
                    database.writeRuntimeMessage(db, next, reuse, writer)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            assertEquals(1L, reuse.historyReceipts.validationCalls)
            assertTrue(reuse.historyReceipts.hits >= 8)
            // A pure receipt hit cannot authorize changed external video or SQL evidence.
            for (change in listOf(
                "UPDATE message_video_witnesses SET manifest_sha256='0000000000000000000000000000000000000000000000000000000000000000' WHERE message_id='assistant'",
                "UPDATE messages SET history_segments=replace(history_segments,'mime_type','mime_typf') WHERE id='assistant'")) {
                db.beginTransaction()
                try {
                    database.runtimeMessage(db, original, reuse)
                    db.execSQL(change)
                    rejected { database.runtimeMessage(db, original, reuse) }
                } finally { db.endTransaction() }
            }
        }
        writer.clear()
    }
    fun testTwoNativeReceiptsShareContextBudgetWithoutSkippingProofs() {
        val one = nativeHistoryMessage()
        val first = one.blocks.single() as MessageBlock.Tool
        val second = first.copy(invocation = first.invocation.copy(id = ToolCallId("second-screen")))
        val protocol = one.protocolItems + one.protocolItems.map {
            org.json.JSONObject(it).put("call_id", "second-screen").toString()
        }
        val blocks = listOf(first, second)
        val message = one.copy(blocks = blocks, protocolItems = protocol, historySegments = listOf(
            MessageHistorySegment(one.id, "assistant", blocks, protocol, createdAt = one.createdAt)))
        database.save(message)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            CommitMessageDecodeReuse().use { reuse ->
                repeat(3) {
                    assertEquals(message, database.runtimeMessage(db, message, reuse))
                    database.writeRuntimeMessage(db, message, reuse)
                }
                assertEquals(2L, reuse.historyReceipts.validationCalls)
                assertEquals(2, reuse.decodeCount) // The >8Mi-character row plus the standalone user.
                assertTrue(reuse.textReads.hits > 0)
                assertTrue(reuse.textReads.retainedCharacters <= 14L * 1024 * 1024)
                reuse.clear()
                assertEquals(0L, reuse.textReads.retainedCharacters)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

}
