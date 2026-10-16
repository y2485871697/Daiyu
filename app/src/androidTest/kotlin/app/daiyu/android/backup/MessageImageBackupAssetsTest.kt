package app.daiyu.android.backup

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.test.InstrumentationTestCase
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.media.AndroidImageInspector
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.ImageAttachmentLimits
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.ImageAttachmentStore
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner
import app.daiyu.android.media.MessageImageReference
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageImageReferenceCodec
import app.daiyu.android.testContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Real PNG decoding, real private filesystem, real production adapter, and (below) real SQLite.
 * No fake store or injected inspector stands in for the production restore validation.
 */
@Suppress("DEPRECATION")
class MessageImageBackupAssetsTest : InstrumentationTestCase() {
    private val roots = mutableListOf<File>()
    private val databases = mutableListOf<Pair<Context, ConversationDatabase>>()
    private val owner = MessageImageOwner("conversation", "message")

    private fun root(): File = File(instrumentation.targetContext.cacheDir, "real-image-backup-${UUID.randomUUID()}")
        .apply { check(mkdirs()); roots.add(this) }

    override fun tearDown() {
        databases.forEach { (context, db) -> db.close(); context.deleteDatabase("conversations.db") }
        roots.forEach { it.deleteRecursively() }
        super.tearDown()
    }

    private fun png(color: Int = Color.RED, width: Int = 4, height: Int = 3): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun reference(owner: MessageImageOwner, bytes: ByteArray, id: String = UUID.randomUUID().toString()): MessageImageReference {
        val actual = AndroidImageInspector().inspect(bytes)
        return MessageImageReference(owner, id, actual.mime, bytes.size, PrivateBlobs.digest(bytes), actual.width, actual.height)
    }

    private fun reject(expected: ImageAttachmentFailure? = null, action: () -> Unit) {
        try { action(); fail("restore must reject") }
        catch (failure: ImageAttachmentException) { if (expected != null) assertEquals(expected, failure.failure) }
    }

    private fun directory(root: File, reference: MessageImageReference): File =
        root.listFiles()!!.single { it.isDirectory && File(it, reference.id + ".image").exists() }

    private fun assertBytes(expected: ByteArray, actual: ByteArray) = assertTrue(expected.contentEquals(actual))

    fun testCompleteBatchSurvivesRestartAndOrderIndependentRetryWithoutOverwriting() {
        val root = root(); val store = MessageImageAssetStore(root); val adapter = MessageImageBackupAssets(store)
        val first = png(); val second = png(Color.BLUE, 5, 2)
        val a = reference(owner, first); val b = reference(owner, second)
        val refs = listOf(a, b); val inputs = listOf(first.copyOf(), second.copyOf())
        adapter.restore(owner, refs, inputs)
        inputs.forEach { it.fill(0) }
        val manifest = File(directory(root, a), "manifest.json")
        val before = manifest.readBytes(); val modified = manifest.lastModified()
        val reopened = MessageImageAssetStore(root); val retry = MessageImageBackupAssets(reopened)
        assertEquals(refs.sortedBy { it.id }, reopened.preparedReferences(owner))
        assertBytes(first, retry.read(owner, a)); assertBytes(second, retry.read(owner, b))
        retry.restore(owner, refs.reversed(), listOf(second, first))
        assertBytes(before, manifest.readBytes()); assertEquals(modified, manifest.lastModified())
        val frozen = reopened.resolver.freeze(owner, refs, true)
        assertBytes(first, frozen.first().bytes()); assertBytes(second, frozen.last().bytes())
        assertEquals(1, root.listFiles()!!.size)
    }

    fun testPreparedBatchRestoresIdempotentlyIgnoringDraftProvenanceAndOrder() {
        val parent = root(); val root = File(parent, "messages")
        val drafts = ImageAttachmentStore(File(parent, "drafts"), AndroidImageInspector())
        val draft = ImageAttachmentOwner(owner.conversationId, "original-draft")
        val originals = listOf(png(), png(Color.BLUE))
        val attachments = originals.map { drafts.import(draft, it.inputStream()) }
        val frozen = drafts.freeze(draft, attachments, true)
        val store = MessageImageAssetStore(root)
        val refs = store.prepare(owner, draft, frozen)
        val manifest = File(directory(root, refs.first()), "manifest.json")
        val before = manifest.readBytes()
        drafts.clear(draft)
        val reopened = MessageImageAssetStore(root)
        MessageImageBackupAssets(reopened).restore(owner, refs.reversed(), originals.reversed())
        assertBytes(before, manifest.readBytes())
        // restore must not replace the original draft provenance or ordered prepare manifest.
        assertEquals(refs, reopened.prepare(owner, draft, frozen))
        refs.zip(originals).forEach { (ref, bytes) -> assertBytes(bytes, reopened.resolver.read(owner, ref)) }
    }

    fun testAllMetadataAndRealImageBytesValidateBeforeAnyOwnerPublication() {
        val root = root(); val store = MessageImageAssetStore(root); val bytes = png(); val ref = reference(owner, bytes)
        val invalid = listOf(
            ref.copy(owner = owner.copy(messageId = "foreign")),
            ref.copy(id = "../outside"), ref.copy(id = "A".repeat(36)),
            ref.copy(mime = "image/gif"), ref.copy(mime = "image/jpeg"),
            ref.copy(width = 0), ref.copy(width = ref.width + 1), ref.copy(height = ref.height + 1),
            ref.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE),
            ref.copy(bytes = 0), ref.copy(bytes = bytes.size + 1),
            ref.copy(sha256 = "0".repeat(64)), ref.copy(sha256 = "../digest"),
        )
        invalid.forEach { bad ->
            reject { store.restore(owner, listOf(bad), listOf(bytes)) }
            assertEquals(0, root.listFiles()!!.size)
        }
        reject { store.restore(owner, listOf(ref), emptyList()) }
        reject { store.restore(owner, emptyList(), listOf(bytes)) }
        reject { store.restore(owner, listOf(ref, ref), listOf(bytes, bytes)) }
        // Matching size and SHA-256 are not sufficient: these bytes are not a decodable image.
        val garbage = ByteArray(bytes.size) { 42 }
        val badImage = ref.copy(id = UUID.randomUUID().toString(), sha256 = PrivateBlobs.digest(garbage))
        reject(ImageAttachmentFailure.INVALID_IMAGE) {
            store.restore(owner, listOf(ref, badImage), listOf(bytes, garbage))
        }
        val limited = MessageImageAssetStore(root, ImageAttachmentLimits(maxImageBytes = bytes.size - 1))
        reject(ImageAttachmentFailure.TOO_LARGE) { limited.restore(owner, listOf(ref), listOf(bytes)) }
        val single = MessageImageAssetStore(root, ImageAttachmentLimits(maxImages = 1))
        val another = ref.copy(id = UUID.randomUUID().toString())
        reject(ImageAttachmentFailure.TOO_MANY) { single.restore(owner, listOf(ref, another), listOf(bytes, bytes)) }
        val total = MessageImageAssetStore(root, ImageAttachmentLimits(maxDraftBytes = bytes.size.toLong()))
        reject(ImageAttachmentFailure.TOO_LARGE) { total.restore(owner, listOf(ref, another), listOf(bytes, bytes)) }
        assertEquals(0, root.listFiles()!!.size)
        store.restore(owner, listOf(ref), listOf(bytes))
        assertBytes(bytes, store.resolver.read(owner, ref))
    }

    fun testExistingConflictMissingCorruptionAndUnknownEntriesAreNeverRepairedOrRemoved() {
        val root = root(); val store = MessageImageAssetStore(root); val bytes = png(); val ref = reference(owner, bytes)
        store.restore(owner, listOf(ref), listOf(bytes))
        val directory = directory(root, ref); val image = File(directory, ref.id + ".image")
        val manifest = File(directory, "manifest.json"); val before = manifest.readBytes()
        val other = png(Color.BLUE); val conflicting = reference(owner, other, ref.id)
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(conflicting), listOf(other)) }
        assertBytes(bytes, image.readBytes()); assertBytes(before, manifest.readBytes())
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, emptyList(), emptyList()) }
        check(image.delete())
        reject { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertFalse(image.exists()); assertBytes(before, manifest.readBytes())
        val corrupted = ByteArray(bytes.size)
        image.writeBytes(corrupted)
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertBytes(corrupted, image.readBytes())
        image.writeBytes(bytes)
        val unknown = File(directory, "unrelated").apply { writeText("keep") }
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertEquals("keep", unknown.readText()); assertBytes(bytes, image.readBytes())
        check(unknown.delete()); check(manifest.delete())
        reject(ImageAttachmentFailure.MISSING) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertFalse(manifest.exists()); assertBytes(bytes, image.readBytes())
    }

    fun testSymlinkLeavesOwnerRootAndPendingWorkCannotBeFollowedOrDeleted() {
        val parent = root(); val root = File(parent, "messages"); val store = MessageImageAssetStore(root)
        val bytes = png(); val ref = reference(owner, bytes)
        store.restore(owner, listOf(ref), listOf(bytes))
        val directory = directory(root, ref); val image = File(directory, ref.id + ".image")
        val outside = File(parent, "outside").apply { writeBytes(bytes) }
        check(image.delete()); Files.createSymbolicLink(image.toPath(), outside.toPath())
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertTrue(Files.isSymbolicLink(image.toPath())); assertBytes(bytes, outside.readBytes())
        Files.delete(image.toPath()); image.writeBytes(bytes)
        val manifest = File(directory, "manifest.json"); val originalManifest = manifest.readBytes()
        val outsideManifest = File(parent, "outside-manifest").apply { writeBytes(originalManifest) }
        check(manifest.delete()); Files.createSymbolicLink(manifest.toPath(), outsideManifest.toPath())
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertBytes(originalManifest, outsideManifest.readBytes())
        Files.delete(manifest.toPath()); manifest.writeBytes(originalManifest)
        val moved = File(parent, "moved-owner"); check(directory.renameTo(moved))
        Files.createSymbolicLink(directory.toPath(), moved.toPath())
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertBytes(bytes, File(moved, ref.id + ".image").readBytes())
        Files.delete(directory.toPath()); check(moved.renameTo(directory))
        val pending = File(root, directory.name + ".pending"); check(directory.renameTo(pending))
        reject(ImageAttachmentFailure.STORAGE) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertBytes(bytes, File(pending, ref.id + ".image").readBytes()); assertFalse(directory.exists())
        check(pending.renameTo(moved))
        Files.createSymbolicLink(pending.toPath(), moved.toPath())
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertBytes(bytes, File(moved, ref.id + ".image").readBytes())
        Files.delete(pending.toPath()); check(moved.renameTo(pending))
        val tombstone = File(root, directory.name + ".deleting"); check(pending.renameTo(tombstone))
        reject(ImageAttachmentFailure.STORAGE) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertBytes(bytes, File(tombstone, ref.id + ".image").readBytes())
        check(tombstone.renameTo(directory))
        val movedRoot = File(parent, "moved-root"); check(root.renameTo(movedRoot))
        Files.createSymbolicLink(root.toPath(), movedRoot.toPath())
        reject(ImageAttachmentFailure.CORRUPT) { store.restore(owner, listOf(ref), listOf(bytes)) }
        assertBytes(bytes, File(File(movedRoot, directory.name), ref.id + ".image").readBytes())
        Files.delete(root.toPath()) // Teardown must not traverse the deliberately installed root link.
    }

    private data class Fixture(val context: Context, val db: ConversationDatabase, val root: File, val store: MessageImageAssetStore) {
        val assets = MessageImageBackupAssets(store)
        fun service() = NeutralBackupService(context, db, assets)
    }

    private fun fixture(): Fixture {
        val base = testContext(instrumentation.targetContext, "real-image-backup-${UUID.randomUUID()}-")
        val files = root()
        val context = object : ContextWrapper(base) { override fun getFilesDir() = files }
        val db = ConversationDatabase(context); databases.add(context to db)
        val root = File(files, "messages")
        return Fixture(context, db, root, MessageImageAssetStore(root))
    }

    private fun prepare(fixture: Fixture, owner: MessageImageOwner, bytes: ByteArray): MessageImageReference {
        val drafts = ImageAttachmentStore(File(fixture.context.filesDir, "drafts"), AndroidImageInspector())
        val draft = ImageAttachmentOwner(owner.conversationId, UUID.randomUUID().toString())
        val image = drafts.import(draft, bytes.inputStream())
        return fixture.store.prepare(owner, draft, drafts.freeze(draft, listOf(image), true)).single()
            .also { drafts.clear(draft) }
    }

    private fun send(fixture: Fixture, conversation: ConversationId, reference: MessageImageReference) {
        val id = reference.owner.messageId; val run = RunId("run-$id")
        val user = Message(MessageId(id), conversation, run, "user",
            listOf(MessageBlock.Image(MessageImageReferenceCodec.fromAsset(reference))), RunStatus.COMPLETED)
        val assistant = Message(MessageId("answer-$id"), conversation, run, "assistant",
            listOf(MessageBlock.Text("t", "answer")), RunStatus.RUNNING)
        fixture.db.send(user, assistant); fixture.db.save(assistant.copy(status = RunStatus.COMPLETED))
    }

    fun testRealBackupImportIntoPreparedTargetIsIdempotentAndFreshTargetRestartsReadable() {
        val source = fixture(); val conversation = source.db.create().id; val bytes = png()
        val owner = MessageImageOwner(conversation.value, "visible")
        val drafts = ImageAttachmentStore(File(source.context.filesDir, "drafts"), AndroidImageInspector())
        val draft = ImageAttachmentOwner(conversation.value, "source-draft-not-carried-in-backup")
        val attachment = drafts.import(draft, bytes.inputStream())
        val frozen = drafts.freeze(draft, listOf(attachment), true)
        val ref = source.store.prepare(owner, draft, frozen).single()
        send(source, conversation, ref)
        val text = source.service().exportPackage()
        val target = fixture()
        // Same accepted bytes/ids, but the backup knows nothing about this prepare provenance.
        target.store.prepare(owner, draft, frozen)
        drafts.clear(draft)
        val manifest = File(directory(target.root, ref), "manifest.json"); val before = manifest.readBytes()
        assertFalse(target.service().importPackage(text).alreadyImported)
        assertBytes(before, manifest.readBytes())
        assertEquals(source.db.exportBackup(), target.db.exportBackup())
        assertTrue(target.service().importPackage(text).alreadyImported)
        // Also exercise actual new-owner publication rather than only the existing-batch path.
        val fresh = fixture()
        assertFalse(fresh.service().importPackage(text).alreadyImported)
        val reopened = MessageImageAssetStore(fresh.root)
        val restarted = NeutralBackupService(fresh.context, fresh.db, MessageImageBackupAssets(reopened))
        assertBytes(bytes, reopened.resolver.read(ref.owner, ref))
        assertEquals(source.db.exportBackup(), fresh.db.exportBackup())
        assertTrue(restarted.importPackage(text).alreadyImported)
        assertEquals(text, restarted.exportPackage())
    }

    fun testLaterOwnerConflictRollsBackSqlButRetainsExistingAssetsAndEarlierOrphan() {
        val source = fixture(); val conversation = source.db.create().id; val bytes = png()
        val first = prepare(source, MessageImageOwner(conversation.value, "a-new"), bytes)
        val last = prepare(source, MessageImageOwner(conversation.value, "z-existing"), bytes)
        send(source, conversation, first); send(source, conversation, last)
        val text = source.service().exportPackage()
        val target = fixture(); val oldBytes = png(Color.BLUE)
        val old = reference(last.owner, oldBytes, last.id)
        target.assets.restore(old.owner, listOf(old), listOf(oldBytes))
        val unrelatedBytes = png(Color.GREEN)
        val unrelated = reference(MessageImageOwner("unrelated", "orphan"), unrelatedBytes)
        target.assets.restore(unrelated.owner, listOf(unrelated), listOf(unrelatedBytes))
        val oldManifest = File(directory(target.root, old), "manifest.json").readBytes()
        val before = target.db.exportBackup()
        repeat(2) {
            reject(ImageAttachmentFailure.CORRUPT) { target.service().importPackage(text) }
            assertEquals(before, target.db.exportBackup())
            assertBytes(oldBytes, target.assets.read(old.owner, old))
            assertBytes(oldManifest, File(directory(target.root, old), "manifest.json").readBytes())
            assertBytes(unrelatedBytes, target.assets.read(unrelated.owner, unrelated))
            // The earlier owner was fully published before the later conflict; rollback never deletes it.
            assertBytes(bytes, MessageImageBackupAssets(MessageImageAssetStore(target.root)).read(first.owner, first))
            assertEquals(0, target.db.readableDatabase.rawQuery("SELECT count(*) FROM neutral_imports", null)
                .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        }
    }
}
