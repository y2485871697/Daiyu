package app.daiyu.android.media.video

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** Storage contract tests use a fake inspector and NON-media bytes, not proof of Android decoding. */
class VideoAssetStoreTest {
    private lateinit var root: File
    private lateinit var store: VideoAssetStore
    private val owner = VideoAttachmentOwner("conversation", "draft")
    private val message = MessageVideoOwner("conversation", "message")
    private val other = VideoAttachmentOwner("other", "draft")
    private val bytes = byteArrayOf(5, 4, 3, 2, 1)
    private val metadata = VideoMetadata("video/mp4", 1200, 32, 24)
    private val inspector = VideoInspector { _, _, _ -> metadata }

    @Before fun before() {
        root = Files.createTempDirectory("video-assets-test").toFile()
        store = newStore()
    }
    @After fun after() { root.deleteRecursively() }
    private fun newStore(limits: VideoAttachmentLimits = VideoAttachmentLimits(), inspect: VideoInspector = inspector) =
        VideoAssetStore(root, limits, inspect)
    private fun add(target: VideoAttachmentOwner = owner) = store.import(target, bytes.inputStream())
    private fun failWith(reason: VideoAttachmentFailure, action: () -> Unit) {
        try { action(); fail("Expected $reason") }
        catch (failure: VideoAttachmentException) { assertEquals(reason, failure.failure) }
    }
    private fun assertEmpty() {
        assertTrue(store.list(owner).isEmpty())
        assertFalse(root.walkTopDown().any { it.isFile })
    }
    private fun original(ref: VideoReference) = File(File(File(root, ref.owner.directoryName()), ref.id), "original.video")

    @Test fun exactOriginalSurvivesReopenAndPreviewIsAnIndependentCopy() {
        val source = bytes.copyOf()
        val draft = store.import(owner, source.inputStream())
        source.fill(0)
        store = newStore()
        assertEquals(listOf(draft), store.list(owner))
        assertEquals("video/mp4", draft.mime)
        assertEquals(bytes.size.toLong(), draft.bytes)
        assertEquals(videoDigest(bytes), draft.sha256)
        assertEquals(1200L, draft.durationMs)
        store.openOriginal(owner, draft).use { assertArrayEquals(bytes, it.readBytes()) }
        val jpeg = byteArrayOf(1, 2)
        val preview = VideoPreview(draft, 2, 1, jpeg)
        jpeg.fill(0)
        preview.bytes().fill(0)
        assertArrayEquals(byteArrayOf(1, 2), preview.bytes())
        assertEquals("image/jpeg", preview.mime)
        assertNotEquals(draft.sha256, preview.sha256)
        store.openOriginal(owner, draft).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun prepareCopiesOriginalAndRemainsIdempotentAfterDraftDeletion() {
        val draft = add()
        val accepted = store.prepare(message, draft)
        assertTrue(store.remove(owner, draft))
        assertFalse(store.remove(owner, draft))
        store = newStore()
        assertEquals(listOf(accepted), store.preparedReferences(message))
        assertEquals(accepted, store.prepare(message, draft))
        store.openOriginal(message, accepted).use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(draft.id, accepted.id)
        assertEquals(draft.sha256, accepted.sha256)
        assertEquals(draft.mime, accepted.mime)
    }

    @Test fun authorizesEveryOwnerAndMetadataFieldForReadPrepareAndDeletion() {
        val draft = add()
        val accepted = store.prepare(message, draft)
        failWith(VideoAttachmentFailure.OWNER_MISMATCH) { store.openOriginal(other, draft) }
        failWith(VideoAttachmentFailure.OWNER_MISMATCH) { store.openOriginal(owner, accepted) }
        failWith(VideoAttachmentFailure.OWNER_MISMATCH) { store.remove(other, draft) }
        failWith(VideoAttachmentFailure.OWNER_MISMATCH) { store.prepare(MessageVideoOwner("other", "m"), draft) }
        val changes = listOf(draft.copy(sha256 = "a".repeat(64)), draft.copy(bytes = 4),
            draft.copy(width = 33), draft.copy(height = 25), draft.copy(durationMs = 1400),
            draft.copy(mime = "video/webm"))
        changes.forEach {
            failWith(VideoAttachmentFailure.CORRUPT) { store.openOriginal(owner, it) }
            failWith(VideoAttachmentFailure.CORRUPT) { store.remove(owner, it) }
            failWith(VideoAttachmentFailure.CORRUPT) { store.prepare(message, it) }
        }
        failWith(VideoAttachmentFailure.CORRUPT) { store.openOriginal(owner, draft.copy(id = "../outside")) }
        failWith(VideoAttachmentFailure.CORRUPT) { store.prepare(message, draft.copy(owner = owner.copy(draftId = "forged"))) }
        assertEquals(listOf(draft), store.list(owner))
        assertEquals(listOf(accepted), store.preparedReferences(message))
    }

    @Test fun exactCleanupNeverDeletesOtherDraftsOrAcceptedOriginals() {
        val a = add()
        val b = add(other)
        val c = add(owner.copy(draftId = "second"))
        val accepted = store.prepare(message, a)
        store.remove(owner, a)
        store.cleanUnpublished(owner)
        store.openOriginal(other, b).use { assertArrayEquals(bytes, it.readBytes()) }
        store.openOriginal(c.owner, c).use { assertArrayEquals(bytes, it.readBytes()) }
        store.openOriginal(message, accepted).use { assertArrayEquals(bytes, it.readBytes()) }
        failWith(VideoAttachmentFailure.CORRUPT) { store.discardUnreferenced(message, accepted.copy(width = 100)) }
        assertTrue(store.discardUnreferenced(message, accepted))
        assertFalse(store.discardUnreferenced(message, accepted))
        store.openOriginal(other, b).close()
    }

    @Test fun byteCountOwnerQuotaAndPixelDurationLimitsFailWithoutPublishing() {
        failWith(VideoAttachmentFailure.TOO_LARGE) {
            newStore(VideoAttachmentLimits(maxVideoBytes = 4)).import(owner, bytes.inputStream())
        }
        assertEmpty()
        failWith(VideoAttachmentFailure.TOO_LARGE) {
            newStore(VideoAttachmentLimits(maxPixels = 100)).import(owner, bytes.inputStream())
        }
        assertEmpty()
        failWith(VideoAttachmentFailure.TOO_LARGE) {
            newStore(VideoAttachmentLimits(maxDurationMs = 100)).import(owner, bytes.inputStream())
        }
        assertEmpty()
        failWith(VideoAttachmentFailure.INVALID_VIDEO) {
            newStore(inspect = VideoInspector { _, _, _ -> metadata.copy(durationMs = 0) }).import(owner, bytes.inputStream())
        }
        assertEmpty()
        val first = add()
        failWith(VideoAttachmentFailure.TOO_MANY) {
            newStore(VideoAttachmentLimits(maxVideos = 1)).import(owner, bytes.inputStream())
        }
        failWith(VideoAttachmentFailure.TOO_LARGE) {
            newStore(VideoAttachmentLimits(maxOwnerBytes = 9)).import(owner, bytes.inputStream())
        }
        assertEquals(listOf(first), store.list(owner))
        assertEquals(2, root.walkTopDown().count { it.isFile })
    }

    @Test fun corruptMissingAndSymlinkOriginalsNeverReadOrOverwrite() {
        val draft = add()
        val file = original(draft)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        failWith(VideoAttachmentFailure.CORRUPT) { store.openOriginal(owner, draft) }
        failWith(VideoAttachmentFailure.CORRUPT) { store.prepare(message, draft) }
        file.delete()
        failWith(VideoAttachmentFailure.CORRUPT) { store.openOriginal(owner, draft) }
        val outside = File(root, "outside").apply { writeBytes(bytes) }
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        failWith(VideoAttachmentFailure.CORRUPT) { store.openOriginal(owner, draft) }
        failWith(VideoAttachmentFailure.CORRUPT) { store.remove(owner, draft) }
        assertArrayEquals(bytes, outside.readBytes())
    }

    @Test fun rejectsRootAndOwnerSymlinksAndUnknownCleanupEntries() {
        val outside = Files.createTempDirectory("outside-video").toFile()
        try {
            val linked = File(root, "linked")
            Files.createSymbolicLink(linked.toPath(), outside.toPath())
            failWith(VideoAttachmentFailure.CORRUPT) { VideoAssetStore(linked, VideoAttachmentLimits(), inspector) }
            val ownerDir = File(root, owner.directoryName())
            Files.createSymbolicLink(ownerDir.toPath(), outside.toPath())
            failWith(VideoAttachmentFailure.CORRUPT) { add() }
            Files.delete(ownerDir.toPath())
            ownerDir.mkdir()
            val pending = File(ownerDir, "00000000-0000-0000-0000-000000000000.pending").apply { mkdir() }
            val known = File(pending, "original.video").apply { writeBytes(bytes) }
            val unknown = File(pending, "not-ours").apply { writeText("retain") }
            failWith(VideoAttachmentFailure.CORRUPT) { store.cleanUnpublished(owner) }
            assertTrue(known.exists())
            assertTrue(unknown.exists())
            unknown.delete()
            store.cleanUnpublished(owner)
            assertFalse(pending.exists())
        } finally { outside.deleteRecursively() }
    }

    @Test fun cancelledImportAtReadInspectAndAfterRenameLeavesNoPublishedSuccess() {
        var cancelled = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return super.read(buffer, offset, length).also { cancelled = true }
            }
        }
        failWith(VideoAttachmentFailure.CANCELLED) { store.import(owner, input) { cancelled } }
        assertEmpty()
        cancelled = false
        val inspecting = newStore(inspect = VideoInspector { _, _, _ -> cancelled = true; metadata })
        failWith(VideoAttachmentFailure.CANCELLED) { inspecting.import(owner, bytes.inputStream()) { cancelled } }
        assertEmpty()
        failWith(VideoAttachmentFailure.CANCELLED) {
            store.import(owner, bytes.inputStream()) {
                root.walkTopDown().any { it.name == "metadata.json" && VIDEO_ID.matches(it.parentFile.name) }
            }
        }
        assertEmpty()
    }

    @Test fun cancelledPrepareAfterRenameRollsBackOnlyNewMessageAsset() {
        val draft = add()
        val ownerDir = File(root, message.directoryName())
        failWith(VideoAttachmentFailure.CANCELLED) {
            store.prepare(message, draft) { File(ownerDir, draft.id).exists() }
        }
        assertEquals(listOf(draft), store.list(owner))
        assertTrue(store.preparedReferences(message).isEmpty())
        val accepted = store.prepare(message, draft)
        failWith(VideoAttachmentFailure.CANCELLED) { store.prepare(message, draft) { true } }
        store.openOriginal(message, accepted).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun sourceIsClosedOnRejectionAndCloseFailurePreventsPublication() {
        var closed = false
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        failWith(VideoAttachmentFailure.CANCELLED) { store.import(owner, source) { true } }
        assertTrue(closed)
        assertEmpty()
        val failedClose = object : ByteArrayInputStream(bytes) {
            override fun close() { throw IOException("provider close failed") }
        }
        failWith(VideoAttachmentFailure.STORAGE) { store.import(owner, failedClose) }
        assertEmpty()
        failWith(VideoAttachmentFailure.SOURCE_UNAVAILABLE) {
            store.import(owner, object : InputStream() { override fun read(): Int = throw IOException("unavailable") })
        }
        assertEmpty()
    }

    @Test fun zeroLengthSourceRejectedAndZeroReadCannotBypassBounds() {
        failWith(VideoAttachmentFailure.INVALID_VIDEO) { store.import(owner, byteArrayOf().inputStream()) }
        assertEmpty()
        val zeroReader = object : InputStream() {
            var n = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read(): Int = if (n++ < 5) 1 else -1
        }
        failWith(VideoAttachmentFailure.TOO_LARGE) {
            newStore(VideoAttachmentLimits(maxVideoBytes = 4)).import(owner, zeroReader)
        }
        assertEmpty()
    }
}
