package app.daiyu.android.media

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class ImageAttachmentStoreTest {
    private lateinit var root: File
    private val owner = ImageAttachmentOwner("conversation-a", "draft-a")
    private val other = ImageAttachmentOwner("conversation-b", "draft-a")
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)
    private val inspector = ImageInspector { ImageDimensions("image/png", 4, 3) }
    private lateinit var store: ImageAttachmentStore

    @Before fun before() {
        root = Files.createTempDirectory("daiyu-image-test").toFile()
        store = ImageAttachmentStore(root, inspector)
    }
    @After fun after() { root.deleteRecursively() }
    private fun add(target: ImageAttachmentStore = store, identity: ImageAttachmentOwner = owner) =
        target.import(identity, bytes.inputStream())
    private fun failure(expected: ImageAttachmentFailure, operation: () -> Unit) {
        try { operation(); fail("Expected $expected") }
        catch (error: ImageAttachmentException) { assertEquals(expected, error.failure) }
    }

    @Test fun originalBytesSurviveReopenAndSourceChange() {
        val source = bytes.copyOf()
        val ref = store.import(owner, source.inputStream())
        source.fill(0)
        val reopened = ImageAttachmentStore(root, inspector)
        assertEquals(listOf(ref), reopened.list(owner))
        assertArrayEquals(bytes, reopened.read(owner, ref))
        assertEquals(imageDigest(bytes), ref.sha256)
        assertEquals(4, ref.width)
        assertEquals(3, ref.height)
    }

    @Test fun separateDraftsWithSameBytesRemainIsolated() {
        val a = add()
        val b = add(identity = other)
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(a), store.list(owner))
        assertEquals(listOf(b), store.list(other))
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { store.read(other, a) }
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { store.remove(other, a) }
        store.clear(owner)
        assertArrayEquals(bytes, store.read(other, b))
    }

    @Test fun draftIdsWithinOneConversationAreIsolated() {
        val a = add()
        val bOwner = owner.copy(draftId = "draft-b")
        val b = add(identity = bOwner)
        store.clear(owner)
        assertArrayEquals(bytes, store.read(bOwner, b))
        failure(ImageAttachmentFailure.MISSING) { store.read(owner, a) }
    }

    @Test fun removeIsIdempotentAndRemovesPrivateBytes() {
        val ref = add()
        val file = File(File(root, owner.directoryName()), ref.id + ".image")
        assertTrue(store.remove(owner, ref))
        assertFalse(file.exists())
        assertFalse(store.remove(owner, ref))
        assertTrue(store.list(owner).isEmpty())
    }

    @Test fun missingBytesCannotBeFrozen() {
        val ref = add()
        File(File(root, owner.directoryName()), ref.id + ".image").delete()
        failure(ImageAttachmentFailure.MISSING) { store.freeze(owner, listOf(ref), true) }
        assertEquals(listOf(ref), store.list(owner))
    }

    @Test fun sameLengthCorruptionIsDetected() {
        val ref = add()
        File(File(root, owner.directoryName()), ref.id + ".image").writeBytes(byteArrayOf(5, 4, 3, 2, 1))
        failure(ImageAttachmentFailure.CORRUPT) { store.read(owner, ref) }
    }

    @Test fun mismatchingReferenceCannotReadOrDeleteStoredAttachment() {
        val ref = add()
        val changed = ref.copy(width = 99)
        failure(ImageAttachmentFailure.CORRUPT) { store.read(owner, changed) }
        failure(ImageAttachmentFailure.CORRUPT) { store.remove(owner, changed) }
        assertArrayEquals(bytes, store.read(owner, ref))
    }

    @Test fun pathTraversalInReferenceIsRejected() {
        val ref = add().copy(id = "../outside")
        failure(ImageAttachmentFailure.CORRUPT) { store.read(owner, ref) }
        failure(ImageAttachmentFailure.CORRUPT) { store.remove(owner, ref) }
    }

    @Test fun symlinkDataDoesNotReadOutsideAsset() {
        val ref = add()
        val file = File(File(root, owner.directoryName()), ref.id + ".image")
        val outside = File(root, "outside").apply { writeBytes(bytes) }
        file.delete(); Files.createSymbolicLink(file.toPath(), outside.toPath())
        failure(ImageAttachmentFailure.MISSING) { store.read(owner, ref) }
        store.clear(owner)
        assertArrayEquals(bytes, outside.readBytes())
    }

    @Test fun zeroByteImageIsRejectedAndInputIsClosed() {
        var closed = false
        val stream = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { closed = true } }
        failure(ImageAttachmentFailure.INVALID_IMAGE) { store.import(owner, stream) }
        assertTrue(closed); assertTrue(store.list(owner).isEmpty())
    }

    @Test fun byteLimitIsEnforcedWithoutPublishedReference() {
        val limited = ImageAttachmentStore(root, inspector, ImageAttachmentLimits(maxImageBytes = 4))
        failure(ImageAttachmentFailure.TOO_LARGE) { add(limited) }
        assertTrue(limited.list(owner).isEmpty())
    }

    @Test fun exactByteLimitSucceeds() {
        val limited = ImageAttachmentStore(root, inspector, ImageAttachmentLimits(maxImageBytes = 5))
        assertArrayEquals(bytes, limited.read(owner, add(limited)))
    }

    @Test fun emptyReadsDoNotSkipSizeChecks() {
        val input = object : InputStream() {
            var position = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            override fun read() = if (position < bytes.size) bytes[position++].toInt() else -1
        }
        val limited = ImageAttachmentStore(root, inspector, ImageAttachmentLimits(maxImageBytes = 4))
        failure(ImageAttachmentFailure.TOO_LARGE) { limited.import(owner, input) }
    }

    @Test fun unsupportedMimeIsRejectedWithoutPublishing() {
        val unsupported = ImageAttachmentStore(root, ImageInspector { ImageDimensions("image/gif", 4, 3) })
        failure(ImageAttachmentFailure.UNSUPPORTED_IMAGE) { add(unsupported) }
        assertTrue(store.list(owner).isEmpty())
    }

    @Test fun invalidOrExcessiveDimensionsAreRejected() {
        val zero = ImageAttachmentStore(root, ImageInspector { ImageDimensions("image/png", 0, 3) })
        failure(ImageAttachmentFailure.INVALID_IMAGE) { add(zero) }
        val huge = ImageAttachmentStore(root, ImageInspector { ImageDimensions("image/png", Int.MAX_VALUE, Int.MAX_VALUE) })
        failure(ImageAttachmentFailure.TOO_LARGE) { add(huge) }
    }

    @Test fun numberAndAggregateBudgetsAreIndependent() {
        val countLimited = ImageAttachmentStore(root, inspector, ImageAttachmentLimits(maxImages = 1))
        add(countLimited)
        failure(ImageAttachmentFailure.TOO_MANY) { add(countLimited) }
        store.clear(owner)
        val bytesLimited = ImageAttachmentStore(root, inspector, ImageAttachmentLimits(maxDraftBytes = 9))
        add(bytesLimited)
        failure(ImageAttachmentFailure.TOO_LARGE) { add(bytesLimited) }
        assertEquals(1, store.list(owner).size)
    }

    @Test fun preCancelledImportClosesInput() {
        var closed = false
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true } }
        failure(ImageAttachmentFailure.CANCELLED) { store.import(owner, input) { true } }
        assertTrue(closed); assertTrue(store.list(owner).isEmpty())
    }

    @Test fun cancellationAfterValidationDoesNotPublish() {
        var cancelled = false
        val target = ImageAttachmentStore(root, ImageInspector { cancelled = true; inspector.inspect(it) })
        failure(ImageAttachmentFailure.CANCELLED) { target.import(owner, bytes.inputStream()) { cancelled } }
        assertTrue(store.list(owner).isEmpty())
    }

    @Test fun cancellationImmediatelyBeforePublishCleansStagedFiles() {
        failure(ImageAttachmentFailure.CANCELLED) {
            store.import(owner, bytes.inputStream()) { root.walkTopDown().any { it.extension == "pending" } }
        }
        assertTrue(store.list(owner).isEmpty())
        assertTrue(root.walkTopDown().none { it.isFile })
    }

    @Test fun sourceReadAndCloseFailuresDoNotPublish() {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("provider failed")
            override fun close() { closed = true }
        }
        failure(ImageAttachmentFailure.SOURCE_UNAVAILABLE) { store.import(owner, input) }
        assertTrue(closed)
        val closeFailure = object : ByteArrayInputStream(bytes) { override fun close() { throw IOException("close failed") } }
        failure(ImageAttachmentFailure.SOURCE_UNAVAILABLE) { store.import(owner, closeFailure) }
        assertTrue(store.list(owner).isEmpty())
    }

    @Test fun unsupportedModelDoesNotConsumeDraft() {
        val ref = add()
        failure(ImageAttachmentFailure.MODEL_UNSUPPORTED) { store.freeze(owner, listOf(ref), false) }
        assertEquals(listOf(ref), store.list(owner))
        assertTrue(store.freeze(owner, emptyList(), false).isEmpty())
    }

    @Test fun frozenReferenceReadsOriginalAndRejectsDeletedSource() {
        val ref = add()
        val snapshot = store.freeze(owner, listOf(ref), true).single()
        snapshot.bytes().fill(0)
        assertArrayEquals(bytes, snapshot.bytes())
        store.clear(owner)
        failure(ImageAttachmentFailure.MISSING) { snapshot.openSource() }
    }

    @Test fun duplicateInputAndCrossOwnerSnapshotsAreRejected() {
        val ref = add()
        failure(ImageAttachmentFailure.CORRUPT) { store.freeze(owner, listOf(ref, ref), true) }
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { store.freeze(other, listOf(ref), true) }
    }

    @Test fun failedBatchFreezeRetainsEveryDraftReference() {
        val a = add(); val b = add()
        File(File(root, owner.directoryName()), b.id + ".image").delete()
        failure(ImageAttachmentFailure.MISSING) { store.freeze(owner, listOf(a, b), true) }
        assertEquals(2, store.list(owner).size)
        assertArrayEquals(bytes, store.read(owner, a))
    }

    @Test fun cleanupRemovesOnlyUnpublishedFilesOfSelectedOwner() {
        val a = add(); val b = add(identity = other)
        val directory = File(root, owner.directoryName())
        File(directory, "orphan.image").writeBytes(bytes)
        File(directory, "interrupted.pending").writeText("partial")
        store.cleanUnpublished(owner)
        assertFalse(File(directory, "orphan.image").exists())
        assertFalse(File(directory, "interrupted.pending").exists())
        assertArrayEquals(bytes, store.read(owner, a)); assertArrayEquals(bytes, store.read(other, b))
    }

    @Test fun metadataOwnerMismatchAndOversizedMetadataAreRejected() {
        val ref = add()
        val metadata = File(File(root, owner.directoryName()), ref.id + ".json")
        val json = JSONObject(metadata.readText()).put("conversation", "other")
        metadata.writeText(json.toString())
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { store.list(owner) }
        metadata.writeText(" ".repeat(4097))
        failure(ImageAttachmentFailure.CORRUPT) { store.list(owner) }
    }

    @Test fun cancelledSourceReadClosesAndDoesNotPublish() {
        var cancelled = false
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int { cancelled = true; throw IOException("cancelled read") }
            override fun close() { closed = true }
        }
        failure(ImageAttachmentFailure.CANCELLED) { store.import(owner, input) { cancelled } }
        assertTrue(closed)
        assertTrue(store.list(owner).isEmpty())
    }

    @Test fun cancelledFreezeDoesNotConsumeAnyDraft() {
        val ref = add()
        failure(ImageAttachmentFailure.CANCELLED) { store.freeze(owner, listOf(ref), true) { true } }
        assertEquals(listOf(ref), store.list(owner))
        assertArrayEquals(bytes, store.read(owner, ref))
    }

    @Test fun concurrentImportsCannotOverfillOneDraft() {
        val limited = ImageAttachmentStore(root, inspector, ImageAttachmentLimits(maxImages = 3))
        val start = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val rejected = java.util.concurrent.atomic.AtomicInteger()
        val unexpected = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val workers = List(6) {
            Thread {
                try {
                    check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    add(limited)
                    accepted.incrementAndGet()
                } catch (error: ImageAttachmentException) {
                    if (error.failure == ImageAttachmentFailure.TOO_MANY) rejected.incrementAndGet()
                    else unexpected.compareAndSet(null, error)
                } catch (error: Throwable) { unexpected.compareAndSet(null, error) }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(5_000); assertFalse("import worker did not finish", it.isAlive) }
        assertNull(unexpected.get())
        assertEquals(3, accepted.get()); assertEquals(3, rejected.get())
        assertEquals(3, limited.list(owner).size)
    }

    @Test fun ownerEncodingIsUnambiguousAndDoesNotUsePaths() {
        assertNotEquals(ImageAttachmentOwner("a", "bc").directoryName(), ImageAttachmentOwner("ab", "c").directoryName())
        val unusual = ImageAttachmentOwner("../a", "draft/../../b")
        val ref = add(identity = unusual)
        assertArrayEquals(bytes, store.read(unusual, ref))
        assertEquals(64, unusual.directoryName().length)
    }
}
