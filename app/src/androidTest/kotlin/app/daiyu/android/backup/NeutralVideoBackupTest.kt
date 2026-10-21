package app.daiyu.android.backup

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.SQLiteMessageVideoStore
import app.daiyu.android.testContext
import app.daiyu.android.context.PrivateBlobs
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Transaction/manifest integration with an explicit fake asset capability, not decoder evidence. */
@Suppress("DEPRECATION")
class NeutralVideoBackupTest : InstrumentationTestCase() {
    private val databases = mutableListOf<Pair<Context, ConversationDatabase>>()
    private class Assets : BackupVideoAssets {
        val originals = mutableMapOf<Pair<MessageVideoOwner, String>, Pair<MessageVideoReference, ByteArray>>()
        override fun read(owner: MessageVideoOwner, reference: MessageVideoReference): ByteArray {
            val saved = requireNotNull(originals[owner to reference.id])
            require(saved.first == reference)
            return saved.second.copyOf()
        }
        override fun restore(owner: MessageVideoOwner, references: List<MessageVideoReference>, contents: List<ByteArray>) {
            references.zip(contents).forEach { (ref, bytes) ->
                require(ref.owner == owner)
                val old = originals[owner to ref.id]
                require(old == null || old.first == ref && old.second.contentEquals(bytes))
                originals[owner to ref.id] = ref to bytes.copyOf()
            }
        }
    }
    private fun fresh(): Pair<Context, ConversationDatabase> {
        val base = testContext(instrumentation.targetContext, "video-backup-${UUID.randomUUID()}-")
        val directory = File(base.cacheDir, "video-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        return (context to ConversationDatabase(context)).also { databases.add(it) }
    }
    override fun tearDown() {
        databases.forEach { (context, db) -> db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively() }
        super.tearDown()
    }
    private val bytes = byteArrayOf(8, 7, 6, 5)
    private fun service(context: Context, db: ConversationDatabase, assets: Assets) =
        NeutralBackupService(context, db, null, BackupVideoIntegration(assets,
            exportText = { sql, scope -> db.exportBackupForVideoAssets(sql, scope, assets) },
            restoreSidecars = { sql, sidecars -> db.restoreVideoBackupSidecars(sql, sidecars) }))
    private fun source(): Pair<String, MessageVideoReference> {
        val (context, db) = fresh()
        val owner = db.create("default").id
        val user = Message(MessageId("u"), owner, RunId("run"), "user", listOf(MessageBlock.Text("t", "original")), RunStatus.COMPLETED)
        val assistant = Message(MessageId("a"), owner, user.runId, "assistant", emptyList(), RunStatus.RUNNING)
        val ref = MessageVideoReference(MessageVideoOwner(owner.value, user.id.value),
            "11111111-1111-1111-1111-111111111111", "video/mp4", bytes.size.toLong(), PrivateBlobs.digest(bytes), 1000, 32, 24)
        db.send(user, assistant, videoInputs = listOf(MessageVideoInput(ref.owner, listOf(ref))))
        db.save(assistant.copy(status = RunStatus.COMPLETED))
        reject { db.exportBackup() }
        reject { NeutralBackupService(context, db).exportPackage() }
        val assets = Assets().apply { restore(ref.owner, listOf(ref), listOf(bytes)) }
        return service(context, db, assets).exportPackage() to ref
    }
    private fun reject(action: () -> Unit) {
        try { action(); fail("must reject") } catch (_: Exception) { }
    }
    private fun mutate(source: String, edit: (JSONObject) -> Unit): String {
        val envelope = JSONObject(source)
        val payload = JSONObject(envelope.getString("payload")).also(edit).toString()
        return envelope.put("payload", payload).put("sha256", PrivateBlobs.digest(payload.toByteArray(Charsets.UTF_8))).toString()
    }

    fun testOriginalIdBytesSidecarAndDuplicateReceiptThenMissingFileRejected() {
        val (source, ref) = source()
        val (context, db) = fresh(); val assets = Assets(); val backup = service(context, db, assets)
        assertEquals(1, backup.preflight(source).attachments)
        assertFalse(backup.importPackage(source).alreadyImported)
        assertEquals(24, db.writableDatabase.version)
        assertTrue(bytes.contentEquals(assets.read(ref.owner, ref)))
        val message = db.messages(ConversationId(ref.owner.conversationId)).single { it.second.id.value == ref.owner.messageId }.second
        assertEquals(ref, SQLiteMessageVideoStore.read(db.readableDatabase, message).single().references.single())
        assertTrue(backup.importPackage(source).alreadyImported)
        assets.originals.clear()
        reject { backup.importPackage(source) }
        assertEquals(1, db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use { it.moveToFirst(); it.getInt(0) })
    }

    fun testBadManifestMissingBytesAndCrossConversationTamperHaveNoVisibleEffects() {
        val (source, _) = source()
        val (context, db) = fresh(); val assets = Assets(); val backup = service(context, db, assets)
        val corrupt = listOf(
            mutate(source) { it.getJSONObject("message_videos").put("version", 99) },
            mutate(source) { it.getJSONArray("blobs").remove(0) },
            mutate(source) { it.getJSONObject("message_videos").getJSONArray("sidecars").getJSONObject(0).put("message_id", "foreign") },
            mutate(source) {
                val sidecar = it.getJSONObject("message_videos").getJSONArray("sidecars").getJSONObject(0)
                val body = JSONObject(sidecar.getString("payload"))
                body.getJSONObject("owner").put("conversation", "foreign")
                sidecar.put("payload", body.toString())
            })
        corrupt.forEach { text ->
            reject { backup.importPackage(text) }
            assertEquals(0, db.readableDatabase.rawQuery("SELECT count(*) FROM messages", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, db.readableDatabase.rawQuery("SELECT count(*) FROM message_video_inputs", null).use { it.moveToFirst(); it.getInt(0) })
            assertTrue(assets.originals.isEmpty())
        }
        reject { NeutralBackupService(context, db).importPackage(source) }
    }

    fun testLateSidecarWriteFailureRollsBackTextAndDoesNotDeletePreexistingOriginal() {
        val (source, ref) = source()
        val (context, db) = fresh()
        val assets = Assets().apply { restore(ref.owner, listOf(ref), listOf(bytes)) }
        db.writableDatabase.execSQL("""CREATE TRIGGER reject_video BEFORE INSERT ON message_video_inputs
            BEGIN SELECT RAISE(ABORT,'injected sidecar failure'); END""")
        reject { service(context, db, assets).importPackage(source) }
        assertEquals(0, db.readableDatabase.rawQuery("SELECT count(*) FROM messages", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use { it.moveToFirst(); it.getInt(0) })
        assertTrue(bytes.contentEquals(assets.read(ref.owner, ref)))
    }
    fun testDuplicateImportRejectsMissingPlainMessageWitnessWithoutRepair() {
        val (source, _) = source()
        val (context, db) = fresh()
        val backup = service(context, db, Assets())
        assertFalse(backup.importPackage(source).alreadyImported)
        db.writableDatabase.execSQL("DELETE FROM message_video_witnesses WHERE message_id='a'")
        reject { backup.importPackage(source) }
        assertEquals(0, db.readableDatabase.rawQuery(
            "SELECT count(*) FROM message_video_witnesses WHERE message_id='a'", null).use {
            it.moveToFirst(); it.getInt(0)
        })
    }

    fun testIgnoredZeroWitnessAndLateWitnessDeletionRollBackImport() {
        val (source, _) = source()
        for (trigger in listOf(
            "BEFORE INSERT ON message_video_witnesses WHEN NEW.message_id='a' BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON neutral_imports BEGIN DELETE FROM message_video_witnesses WHERE message_id='a'; END"
        )) {
            val (context, db) = fresh()
            db.writableDatabase.execSQL("CREATE TRIGGER witness_fault $trigger")
            reject { service(context, db, Assets()).importPackage(source) }
            for (table in listOf("messages", "message_video_inputs", "message_video_witnesses", "neutral_imports")) {
                assertEquals(table, 0, db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use {
                    it.moveToFirst(); it.getInt(0)
                })
            }
        }
    }

}
