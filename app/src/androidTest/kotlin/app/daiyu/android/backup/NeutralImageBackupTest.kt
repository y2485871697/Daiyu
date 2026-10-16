package app.daiyu.android.backup

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.ImageAttachmentStore
import app.daiyu.android.media.ImageDimensions
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner as AssetOwner
import app.daiyu.android.media.MessageImageReference as AssetReference
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageImageReferenceCodec
import app.daiyu.android.testContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Backup orchestration tests. Source originals use the real media resolver; RecordingAssets is
 * explicitly a publication test double, NOT evidence of a production media restore implementation. */
@Suppress("DEPRECATION")
class NeutralImageBackupTest : InstrumentationTestCase() {
    private data class Fixture(val context: Context, val db: ConversationDatabase)
    private val fixtures = mutableListOf<Fixture>()
    private val original = byteArrayOf(1, 2, 3, 4, 5)

    private fun fresh(): Fixture {
        val base = testContext(instrumentation.targetContext, "image-backup-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "image-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = files }
        return Fixture(context, ConversationDatabase(context)).also { fixtures.add(it) }
    }

    override fun tearDown() {
        fixtures.forEach { it.db.close(); it.context.deleteDatabase("conversations.db"); it.context.filesDir.deleteRecursively() }
        super.tearDown()
    }

    private class SourceAssets(val root: File) : BackupImageAssets {
        private val drafts = ImageAttachmentStore(File(root, "drafts"), ImageInspector { ImageDimensions("image/png", 4, 3) })
        private val store = MessageImageAssetStore(File(root, "accepted"))
        fun prepare(owner: AssetOwner, bytes: ByteArray): AssetReference {
            val draft = ImageAttachmentOwner(owner.conversationId, UUID.randomUUID().toString())
            val attachment = drafts.import(draft, bytes.inputStream())
            return store.prepare(owner, draft, drafts.freeze(draft, listOf(attachment), true)).single().also { drafts.clear(draft) }
        }
        var reads = 0
        override fun read(owner: AssetOwner, reference: AssetReference): ByteArray {
            reads++
            return store.resolver.read(owner, reference)
        }
        override fun restore(owner: AssetOwner, references: List<AssetReference>, contents: List<ByteArray>) = error("source is read-only")
        fun removeOriginal(reference: AssetReference) {
            check(root.walkTopDown().single { it.name == reference.id + ".image" }.delete())
        }
        fun corruptOriginal(reference: AssetReference) {
            root.walkTopDown().single { it.name == reference.id + ".image" }.writeBytes(ByteArray(reference.bytes))
        }
    }

    private class RecordingAssets : BackupImageAssets {
        val batches = linkedMapOf<AssetOwner, List<AssetReference>>()
        val data = linkedMapOf<Pair<AssetOwner, String>, ByteArray>()
        var restoreCalls = 0
        override fun read(owner: AssetOwner, reference: AssetReference): ByteArray {
            require(reference.owner == owner && reference in batches[owner].orEmpty())
            val bytes = data.getValue(owner to reference.id)
            require(bytes.size == reference.bytes && PrivateBlobs.digest(bytes) == reference.sha256)
            return bytes.copyOf()
        }
        override fun restore(owner: AssetOwner, references: List<AssetReference>, contents: List<ByteArray>) {
            restoreCalls++
            require(references.size == contents.size && references.all { it.owner == owner })
            references.zip(contents).forEach { (ref, bytes) -> require(ref.bytes == bytes.size && ref.sha256 == PrivateBlobs.digest(bytes)) }
            val old = batches[owner]
            if (old != null) {
                require(old.toSet() == references.toSet())
                references.forEach { read(owner, it) }
                return
            }
            batches[owner] = references.toList()
            references.zip(contents).forEach { (ref, bytes) -> data[owner to ref.id] = bytes.copyOf() }
        }
    }

    private fun image(source: SourceAssets, conversation: ConversationId, message: String): MessageBlock.Image =
        MessageBlock.Image(MessageImageReferenceCodec.fromAsset(source.prepare(AssetOwner(conversation.value, message), original)))

    private fun send(f: Fixture, conversation: ConversationId, id: String, block: MessageBlock, segments: List<MessageHistorySegment> = emptyList()) {
        val run = RunId("run-$id")
        val user = Message(MessageId(id), conversation, run, "user", listOf(block), RunStatus.COMPLETED)
        val assistant = Message(MessageId("answer-$id"), conversation, run, "assistant", listOf(MessageBlock.Text("t", "answer")),
            RunStatus.RUNNING, historySegments = segments)
        f.db.send(user, assistant)
        f.db.save(assistant.copy(status = RunStatus.COMPLETED))
    }

    private fun archive(f: Fixture, message: Message) {
        val bytes = ArchiveCodec.encode(listOf(message)); val hash = PrivateBlobs(f.context).put(bytes)
        f.db.writableDatabase.insertOrThrow("context_archives", null, ContentValues().apply {
            put("id", "archive-${message.id.value}"); put("conversation_id", message.conversationId.value)
            put("prefix_count", 1); put("revision", ContextRevision.digest(listOf(message))); put("summary", "summary")
            put("blob_hash", hash); put("created", 1); put("active", 0)
        })
    }

    private fun payload(text: String) = JSONObject(JSONObject(text).getString("payload"))
    private fun mutate(text: String, edit: (JSONObject) -> Unit): String {
        val envelope = JSONObject(text); val body = payload(text); edit(body); val raw = body.toString()
        return envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("must reject") } catch (_: Exception) { /* fail() is an AssertionError, not caught */ }
    }

    fun testRealOriginalsFromRowsSegmentOnlyAndArchiveOnlyAreCarriedAndDeduplicated() {
        val from = fresh(); val assets = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        val conversation = from.db.create().id
        val ordinary = image(assets, conversation, "visible")
        val supplement = image(assets, conversation, "segment-only")
        val segments = listOf(
            MessageHistorySegment(MessageId("segment-only"), "user", listOf(supplement), emptyList()),
            MessageHistorySegment(MessageId("answer-visible"), "assistant", listOf(MessageBlock.Text("t", "answer")), emptyList()))
        send(from, conversation, "visible", ordinary, segments)
        val archived = image(assets, conversation, "archive-only")
        archive(from, Message(MessageId("archive-only"), conversation, RunId("old"), "user", listOf(archived), RunStatus.COMPLETED))
        val exporter = NeutralBackupService(from.context, from.db, assets)
        val text = exporter.exportPackage()
        assertEquals(3, payload(text).getJSONArray("message_images").length())
        assertEquals(2, payload(text).getJSONArray("blobs").length()) // one archive + one shared original
        assertEquals(3, exporter.preflight(text).attachments)
        val to = fresh(); val destination = RecordingAssets(); val importer = NeutralBackupService(to.context, to.db, destination)
        importer.importPackage(text)
        assertEquals(from.db.exportBackup(), to.db.exportBackup())
        assertEquals(3, destination.batches.size)
        listOf(ordinary, supplement, archived).forEach {
            val ref = MessageImageReferenceCodec.toAsset(it.reference)
            assertTrue(original.contentEquals(destination.read(ref.owner, ref)))
        }
        assertTrue(importer.importPackage(text).alreadyImported)
        assertEquals(3, destination.restoreCalls)
        assertEquals(text, importer.exportPackage())
        val lost = MessageImageReferenceCodec.toAsset(supplement.reference)
        destination.data.remove(lost.owner to lost.id)
        reject { importer.importPackage(text) } // import marker cannot hide a subsequently missing original
        assertEquals(from.db.exportBackup(), to.db.exportBackup())
        assertEquals(3, destination.restoreCalls)
    }

    fun testMissingCorruptAndForgedImagePackagesRejectBeforeAnyPublicationOrSqlMutation() {
        val from = fresh(); val assets = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        val conversation = from.db.create().id; send(from, conversation, "visible", image(assets, conversation, "visible"))
        val text = NeutralBackupService(from.context, from.db, assets).exportPackage()
        val invalid = listOf(
            mutate(text) { it.remove("message_images") },
            mutate(text) { it.getJSONArray("blobs").remove(0) },
            mutate(text) { it.getJSONArray("blobs").getJSONObject(0).put("data", "AAAAAAA=") },
            mutate(text) { it.getJSONArray("message_images").getJSONObject(0).put("width", 99) },
            mutate(text) { it.getJSONArray("message_images").getJSONObject(0).put("message", "someone-else") },
            mutate(text) { it.getJSONArray("message_images").put(it.getJSONArray("message_images").getJSONObject(0)) },
            mutate(text) { body ->
                val legacy = JSONObject(body.getString("text_backup")); val rows = JSONObject(legacy.getString("payload"))
                rows.getJSONArray("messages").getJSONObject(0).getJSONArray("blocks").getJSONObject(0)
                    .getJSONObject("reference").put("conversation", "foreign")
                val raw = rows.toString(); legacy.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8)))
                body.put("text_backup", legacy.toString())
            })
        val to = fresh(); to.db.create(); val before = to.db.exportBackup(); val target = RecordingAssets()
        val importer = NeutralBackupService(to.context, to.db, target)
        invalid.forEach {
            reject { importer.importPackage(it) }
            assertEquals(before, to.db.exportBackup()); assertEquals(0, target.restoreCalls)
            assertEquals(0, to.db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use { c -> c.moveToFirst(); c.getInt(0) })
        }
        assertFalse(File(to.context.filesDir, "neutral-context-blobs").listFiles().orEmpty().any { it.isFile })
    }

    fun testScopedExportNeverReadsOtherConversationsButMissingAndCorruptSelectedOriginalFail() {
        val from = fresh(); val assets = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        val selected = from.db.create().id; val selectedImage = image(assets, selected, "selected")
        send(from, selected, "selected", selectedImage)
        val other = from.db.create().id; val otherImage = image(assets, other, "other")
        send(from, other, "other", otherImage)
        assets.removeOriginal(MessageImageReferenceCodec.toAsset(otherImage.reference))
        val exporter = NeutralBackupService(from.context, from.db, assets)
        assertEquals(1, exporter.preflight(exporter.exportConversation(selected)).attachments)
        reject { exporter.exportPackage() }
        assets.corruptOriginal(MessageImageReferenceCodec.toAsset(selectedImage.reference))
        reject { exporter.exportConversation(selected) }
        assets.removeOriginal(MessageImageReferenceCodec.toAsset(selectedImage.reference))
        reject { exporter.exportConversation(selected) }
    }

    fun testConfigurationConflictAndAssetCollisionCannotOverwriteExistingState() {
        val from = fresh(); val assets = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        val conversation = from.db.create().id; val block = image(assets, conversation, "visible")
        send(from, conversation, "visible", block)
        val exporter = NeutralBackupService(from.context, from.db, assets)
        exporter.setNonSecretConfiguration(mapOf("model" to "incoming")); val text = exporter.exportPackage()
        val to = fresh(); val target = RecordingAssets(); val importer = NeutralBackupService(to.context, to.db, target)
        importer.setNonSecretConfiguration(mapOf("model" to "existing")); val before = to.db.exportBackup()
        reject { importer.importPackage(text) }; assertEquals(before, to.db.exportBackup()); assertEquals(0, target.restoreCalls)
        importer.setNonSecretConfiguration(emptyMap())
        val ref = MessageImageReferenceCodec.toAsset(block.reference)
        val old = ref.copy(width = ref.width + 1)
        target.restore(old.owner, listOf(old), listOf(original))
        reject { importer.importPackage(text) }
        assertEquals(before, to.db.exportBackup())
        assertEquals(listOf(old), target.batches[old.owner]); assertTrue(original.contentEquals(target.read(old.owner, old)))
        assertEquals(0, to.db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use { it.moveToFirst(); it.getInt(0) })
    }

    fun testArchiveClaimsCannotAuthorizeReadingAnotherConversationDuringScopedExport() {
        val from = fresh(); val source = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        val selected = from.db.create().id; val foreign = from.db.create().id
        send(from, selected, "selected", MessageBlock.Text("t", "selected text"))
        val block = image(source, foreign, "foreign-image")
        archive(from, Message(MessageId("foreign-image"), foreign, RunId("old"), "user", listOf(block), RunStatus.COMPLETED))
        from.db.writableDatabase.execSQL("UPDATE context_archives SET conversation_id=?", arrayOf(selected.value))
        reject { NeutralBackupService(from.context, from.db, source).exportConversation(selected) }
        assertEquals(0, source.reads)
    }

    fun testArchiveOnlyImageCannotBypassManifestRequirement() {
        val from = fresh(); val assets = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        val conversation = from.db.create().id
        send(from, conversation, "text", MessageBlock.Text("t", "current text"))
        val archived = image(assets, conversation, "old-image")
        archive(from, Message(MessageId("old-image"), conversation, RunId("old"), "user", listOf(archived), RunStatus.COMPLETED))
        val exporter = NeutralBackupService(from.context, from.db, assets)
        val text = exporter.exportPackage(); assertEquals(1, exporter.preflight(text).attachments)
        val to = fresh(); val target = RecordingAssets(); val importer = NeutralBackupService(to.context, to.db, target)
        val before = to.db.exportBackup()
        reject { importer.importPackage(mutate(text) { it.remove("message_images") }) }
        assertEquals(before, to.db.exportBackup()); assertEquals(0, target.restoreCalls)
        importer.importPackage(text)
        val ref = MessageImageReferenceCodec.toAsset(archived.reference)
        assertTrue(original.contentEquals(target.read(ref.owner, ref)))
    }

    fun testLaterOwnerCollisionRollsBackDbWithoutDeletingPreviouslyPublishedOrExistingAssets() {
        val from = fresh(); val source = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        val conversation = from.db.create().id
        val first = image(source, conversation, "a-new"); val last = image(source, conversation, "z-existing")
        send(from, conversation, "a-new", first); send(from, conversation, "z-existing", last)
        val text = NeutralBackupService(from.context, from.db, source).exportPackage()
        val to = fresh(); val target = RecordingAssets(); val importer = NeutralBackupService(to.context, to.db, target)
        val old = MessageImageReferenceCodec.toAsset(last.reference).copy(width = 99)
        target.restore(old.owner, listOf(old), listOf(original))
        val before = to.db.exportBackup()
        reject { importer.importPackage(text) }
        assertEquals(before, to.db.exportBackup())
        assertEquals(listOf(old), target.batches[old.owner]); assertTrue(original.contentEquals(target.read(old.owner, old)))
        val orphan = MessageImageReferenceCodec.toAsset(first.reference)
        assertTrue(original.contentEquals(target.read(orphan.owner, orphan))) // safe orphan, never deleted in finally
        assertEquals(0, to.db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null).use { it.moveToFirst(); it.getInt(0) })
    }

    fun testUnconnectedImageCapabilityFailsExplicitlyAndLegacyTextPackagesRemainCompatible() {
        val from = fresh(); val conversation = from.db.create().id
        send(from, conversation, "text", MessageBlock.Text("t", "legacy"))
        val plain = NeutralBackupService(from.context, from.db); val text = plain.exportPackage()
        assertFalse(payload(text).has("message_images"))
        val to = fresh(); NeutralBackupService(to.context, to.db).importPackage(text)
        assertEquals(from.db.exportBackup(), to.db.exportBackup())
        val assets = SourceAssets(File(from.context.filesDir, "test-message-assets"))
        send(from, conversation, "image", image(assets, conversation, "image"))
        reject { plain.exportPackage() }
        val imagePackage = NeutralBackupService(from.context, from.db, assets).exportPackage()
        val before = to.db.exportBackup()
        reject { NeutralBackupService(to.context, to.db).importPackage(imagePackage) }
        assertEquals(before, to.db.exportBackup())
    }
}
