package app.daiyu.android.backup

import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.*
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real ConversationDatabase/SQLite, real PNG inspector and actual leaf filesystem operations.
 * Source fixture is an offline committed asset, NOT a production live registration. No generation
 * attempt is manufactured for a completed tool; this does not prove Controller/T1/T2 integration.
 */
@Suppress("DEPRECATION")
class StaticGenerationLeafDeviceTest : InstrumentationTestCase() {
    private lateinit var appContext: Context
    private lateinit var helper: ConversationDatabase
    private lateinit var root: File
    private val db get() = helper.writableDatabase
    private val png get() = app.daiyu.android.validDeviceTestPng()
    override fun setUp() {
        super.setUp()
        appContext = testContext(instrumentation.targetContext, "static-generation-leaf-${UUID.randomUUID()}-")
        root = File(appContext.cacheDir, "static-generation-leaf-${UUID.randomUUID()}").apply { check(mkdirs()) }
        helper = ConversationDatabase(appContext)
        // Existing backup-owned schema supplied by the host, not installed/versioned by the leaf.
        db.execSQL("CREATE TABLE IF NOT EXISTS neutral_backup_history(kind TEXT NOT NULL,identity TEXT NOT NULL," +
            "owner_message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,payload TEXT NOT NULL,PRIMARY KEY(kind,identity))")
    }
    override fun tearDown() {
        try { helper.close(); appContext.deleteDatabase("conversations.db"); root.deleteRecursively() }
        finally { super.tearDown() }
    }
    private fun destination(): StaticGenerationDestination {
        val conversation = helper.create("default").id
        val run = RunId("run-${UUID.randomUUID()}"); val id = MessageId("assistant-${UUID.randomUUID()}")
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("call-${UUID.randomUUID()}"), "generate_images", "{}"))
        val assistant = Message(id, conversation, run, "assistant", listOf(tool), RunStatus.RUNNING,
            historySegments = listOf(MessageHistorySegment(id, "assistant", listOf(tool))))
        helper.send(Message(MessageId("user-${UUID.randomUUID()}"), conversation, run, "user",
            listOf(MessageBlock.Text("text", "offline fixture")), RunStatus.COMPLETED), assistant)
        val closed = tool.copy(result = "UNKNOWN; historical evidence only")
        helper.save(assistant.copy(status = RunStatus.COMPLETED, blocks = listOf(closed),
            historySegments = listOf(MessageHistorySegment(id, "assistant", listOf(closed)))))
        return StaticGenerationDestination("fixture-account", conversation.value, id.value, run.value, tool.invocation.id.value, tool.invocation.name)
    }
    private fun messages(value: StaticGenerationOutput): JSONArray = db.rawQuery(
        "SELECT id,conversation_id,run_id,role,blocks,history_segments FROM messages WHERE id=?",
        arrayOf(value.destination.assistantMessageId)).use { cursor ->
        check(cursor.moveToFirst())
        JSONArray().put(JSONObject().put("id", cursor.getString(0)).put("conversation_id", cursor.getString(1))
            .put("run_id", cursor.getString(2)).put("role", cursor.getString(3))
            .put("blocks", JSONArray(cursor.getString(4))).put("history_segments", JSONArray(cursor.getString(5))))
    }
    private data class Source(val source: StaticGenerationProvenance, val reader: StaticGenerationOriginalReader)
    private fun source(): Source {
        val binding = GenerationAttemptBinding("deleted-origin-conversation", "deleted-origin-assistant", "origin-run", "origin-call", "generate_images")
        val owner = GenerationAssetOwner("origin-account", binding)
        val media = MessageGenerationAssets(File(root, "source-private"), GenerationAssetFence(owner) {
            check(!db.inTransaction()); true
        })
        val info = media.inspect(GenerationKind.IMAGE, png, "image/png")
        val receipt = media.commit(binding.identity("item"), 2, GenerationKind.IMAGE, png, info)
        val pending = media.pending(receipt)
        val reference = GenerationOutputReference(binding.identity("item"), 2, "a".repeat(64),
            GenerationProtocol.OPENAI_IMAGES_JSON, owner.accountScope, binding.conversationId, binding.assistantMessageId,
            binding.runId, binding.toolCallId, binding.toolName, receipt, pending.kind, pending.mimeType,
            pending.bytes, pending.sha256, pending.width, pending.height, pending.durationMs)
        return Source(StaticGenerationProvenance.capture(reference), StaticGenerationOriginalReader { requested ->
            check(!db.inTransaction())
            check(requested == StaticGenerationProvenance.capture(reference))
            media.openOriginal(receipt)
        })
    }
    private fun assets() = StaticGenerationAssets(File(root, "destination-private"))
    private fun count(table: String) = db.rawQuery("SELECT count(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }
    private fun tx(action: () -> Unit) {
        db.beginTransaction()
        try { action(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: Exception) { } }
    private fun publish(store: StaticGenerationAssets, value: StaticGenerationOutput, source: Source): StaticGenerationAssets.Lease {
        val lease = store.prepare(value, source.reader); store.publish(lease); return lease
    }
    private fun read(store: StaticGenerationAssets, value: StaticGenerationOutput): ByteArray =
        store.openVerified(value, { check(!db.inTransaction()); StaticGenerationCodec.find(db, it) }).use { it.readBytes() }

    fun testRealPngRegistryRoundTripReopenDeletedSourceAndIndependentOwners() {
        val source = source(); val first = StaticGenerationOutput(source.source, destination())
        val second = StaticGenerationOutput(source.source, destination()); val store = assets()
        val attempts = count("generation_attempts"); val outputs = count("generation_outputs")
        for (value in listOf(first, second)) {
            val lease = publish(store, value, source)
            tx {
                StaticGenerationCodec.saveInTransaction(db, value, messages(value))
                StaticGenerationCodec.verifyInTransaction(db, value, messages(value))
            }
            store.settle(lease, StaticGenerationSettlement.COMMITTED)
        }
        assertEquals(attempts, count("generation_attempts")); assertEquals(outputs, count("generation_outputs"))
        assertEquals(2, StaticGenerationCodec.read(db).size)
        assertEquals(listOf(first), StaticGenerationCodec.decodeSection(StaticGenerationCodec.encodeSection(listOf(first)), messages(first)))
        helper.close(); helper = ConversationDatabase(appContext)
        // Explicit test-fixture source deletion: production leaf has NO source delete API.
        assertTrue(File(root, "source-private").deleteRecursively())
        assertTrue(png.contentEquals(read(assets(), first))); assertTrue(png.contentEquals(read(assets(), second)))
        val again = first.copy(destination = destination())
        val next = store.prepare(again, StaticGenerationOriginalReader { read(store, first).inputStream() })
        store.publish(next)
        tx { StaticGenerationCodec.saveInTransaction(db, again, messages(again)) }
        store.settle(next, StaticGenerationSettlement.COMMITTED)
        assertEquals(first.source, again.source)
        assertTrue(png.contentEquals(read(store, again)))
        db.execSQL("DELETE FROM messages WHERE id=?", arrayOf(first.destination.assistantMessageId))
        assertNull(StaticGenerationCodec.find(db, first.identity))
        assertTrue(png.contentEquals(read(store, second))); assertTrue(png.contentEquals(read(store, again)))
        assertEquals(attempts, count("generation_attempts")); assertEquals(outputs, count("generation_outputs"))
    }

    fun testIndependentFortyMiBMetadataOperationsDoNotShareDatabaseLifetimeBudget() {
        // Metadata/SQLite regression only: no original is allocated, opened or claimed verified.
        fun metadataVideo(sha: String): StaticGenerationOutput {
            val binding = GenerationAttemptBinding("metadata-source", "metadata-assistant", "metadata-run", "metadata-call", "generate_images")
            val provenance = StaticGenerationProvenance("metadata-account", binding.operationId, "item", 0,
                binding.conversationId, binding.assistantMessageId, binding.runId, binding.toolCallId, binding.toolName,
                "b".repeat(64), GenerationProtocol.OPENAI_VIDEO_MULTIPART, "genasset-v1-${"c".repeat(64)}-$sha",
                GenerationKind.VIDEO, "video/mp4", 40L * 1024 * 1024, sha, 32, 24, 1000)
            return StaticGenerationOutput(provenance, destination())
        }
        val first = metadataVideo("e".repeat(64)); val second = metadataVideo("f".repeat(64))
        val version = db.version
        for (value in listOf(first, second)) {
            assertEquals(40L * 1024 * 1024, StaticGenerationCodec.validateBatch(listOf(value)))
            tx { StaticGenerationCodec.saveInTransaction(db, value, messages(value)) }
        }
        assertEquals(setOf(first, second), StaticGenerationCodec.read(db).toSet())
        assertEquals(listOf(first), StaticGenerationCodec.read(db, first.destination.conversationId))
        assertEquals(listOf(second), StaticGenerationCodec.readForOwners(db, setOf(second.destination.assistantMessageId)))
        val multiChunkOwners = (0 until 400).map { "absent-owner-$it" }.toSet() + second.destination.assistantMessageId
        assertEquals(listOf(second), StaticGenerationCodec.readForOwners(db, multiChunkOwners))
        assertTrue(StaticGenerationCodec.readForOwners(db, emptySet()).isEmpty())
        // The same two assets in ONE operation still exceed 64 MiB, even though metadata reads work.
        rejects { StaticGenerationCodec.validateBatch(listOf(first, second)) }
        rejects { StaticGenerationCodec.encodeSection(listOf(first, second)) }
        tx { StaticGenerationCodec.saveInTransaction(db, first, messages(first)) }
        val conflict = first.copy(source = first.source.copy(fingerprint = "d".repeat(64)))
        assertEquals(first.identity, conflict.identity)
        rejects { tx { StaticGenerationCodec.saveInTransaction(db, conflict, messages(first)) } }
        // Physical owner equality must not be weakened by scoped reads or idempotent saves.
        db.execSQL("UPDATE neutral_backup_history SET owner_message_id=? WHERE kind=? AND identity=?",
            arrayOf(second.destination.assistantMessageId, StaticGenerationCodec.KIND, first.identity))
        rejects { StaticGenerationCodec.find(db, first.identity) }
        rejects { StaticGenerationCodec.readForOwners(db, setOf(second.destination.assistantMessageId)) }
        rejects { tx { StaticGenerationCodec.saveInTransaction(db, first, messages(first)) } }
        assertEquals(version, db.version)
        assertEquals(0, count("generation_attempts")); assertEquals(0, count("generation_outputs"))
    }

    fun testIgnoredAndMutatedRegistryReceiptsRollbackAndCleanOnlyThisDestination() {
        val source = source(); val value = StaticGenerationOutput(source.source, destination()); val store = assets()
        db.execSQL("CREATE TRIGGER leaf_ignore BEFORE INSERT ON neutral_backup_history WHEN NEW.kind='generation_static_output' BEGIN SELECT RAISE(IGNORE); END")
        var lease = publish(store, value, source)
        rejects { tx { StaticGenerationCodec.saveInTransaction(db, value, messages(value)) } }
        store.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
        assertNull(StaticGenerationCodec.find(db, value.identity))
        assertTrue(png.contentEquals(source.reader.open(source.source).use { it.readBytes() }))
        db.execSQL("DROP TRIGGER leaf_ignore")
        db.execSQL("CREATE TRIGGER leaf_mutate AFTER INSERT ON neutral_backup_history WHEN NEW.kind='generation_static_output' " +
            "BEGIN UPDATE neutral_backup_history SET payload=replace(payload,'image/png','image/jpeg') WHERE kind=NEW.kind AND identity=NEW.identity; END")
        lease = publish(store, value, source)
        rejects { tx { StaticGenerationCodec.saveInTransaction(db, value, messages(value)) } }
        store.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
        assertNull(StaticGenerationCodec.find(db, value.identity))
        assertTrue(png.contentEquals(source.reader.open(source.source).use { it.readBytes() }))
        assertEquals(0, count("generation_attempts")); assertEquals(0, count("generation_outputs"))
    }

    fun testRealInspectorsRejectSpoofedImageMetadataAndPngClaimedAsVideo() {
        val source = source(); val value = StaticGenerationOutput(source.source, destination()); val store = assets()
        // Deliberately untrusted offline stream: prove the destination does not trust metadata.
        val raw = StaticGenerationOriginalReader { png.inputStream() }
        rejects { store.prepare(value.copy(source = value.source.copy(width = 999)), raw) }
        rejects { store.prepare(value.copy(source = value.source.copy(kind = GenerationKind.VIDEO,
            protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART, mimeType = "video/mp4", durationMs = 1000)), raw) }
        assertFalse(File(root, "destination-private").walkTopDown().any { it.name == "original" })
        assertTrue(png.contentEquals(source.reader.open(source.source).use { it.readBytes() }))
        assertEquals(0, count("neutral_backup_history")); assertEquals(0, count("generation_attempts"))
        assertEquals(0, count("generation_outputs"))
    }

    fun testActualPhysicalOwnerAndLastWriteRecheckCannotBeReplacedByCallerSnapshot() {
        val source = source(); val value = StaticGenerationOutput(source.source, destination()); val store = assets()
        val rows = messages(value)
        val forged = value.copy(destination = value.destination.copy(runId = "forged-run"))
        val forgedRows = JSONArray(rows.toString()).also { it.getJSONObject(0).put("run_id", "forged-run") }
        rejects { tx { StaticGenerationCodec.saveInTransaction(db, forged, forgedRows) } }
        val lease = publish(store, value, source)
        rejects { tx {
            StaticGenerationCodec.saveInTransaction(db, value, rows)
            // Simulate mutation caused by the FINAL SQL write, after insertion's own receipt.
            db.execSQL("UPDATE messages SET run_id='changed-after-save' WHERE id=?", arrayOf(value.destination.assistantMessageId))
            StaticGenerationCodec.verifyInTransaction(db, value, rows)
        } }
        store.settle(lease, StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED)
        assertNull(StaticGenerationCodec.find(db, value.identity))
        assertEquals(value.destination.runId, messages(value).getJSONObject(0).getString("run_id"))
        assertTrue(png.contentEquals(source.reader.open(source.source).use { it.readBytes() }))
    }
}
