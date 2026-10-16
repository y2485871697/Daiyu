package app.daiyu.android.media

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class MessageImageAssetStoreTest {
    private lateinit var root: File
    private lateinit var drafts: ImageAttachmentStore
    private lateinit var assets: MessageImageAssetStore
    private val draftOwner = ImageAttachmentOwner("conversation", "draft")
    private val owner = MessageImageOwner("conversation", "message")
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)
    private val inspector = ImageInspector { ImageDimensions("image/png", 4, 3) }
    private val assetRoot get() = File(root, "messages")
    private fun directory(suffix: String = "") = File(assetRoot, owner.directoryName() + suffix)

    @Before fun before() {
        root = Files.createTempDirectory("message-images").toFile()
        drafts = ImageAttachmentStore(File(root, "drafts"), inspector)
        assets = MessageImageAssetStore(assetRoot)
    }
    @After fun after() { root.deleteRecursively() }
    private fun frozen(): List<FrozenImageAttachment> {
        val ref = drafts.import(draftOwner, bytes.inputStream())
        return drafts.freeze(draftOwner, listOf(ref), true)
    }
    private fun failure(expected: ImageAttachmentFailure, action: () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (error: ImageAttachmentException) { assertEquals(expected, error.failure) }
    }

    @Test fun acceptedBytesSurviveDraftClearReopenAndMutableCopies() {
        val snapshot = frozen()
        val refs = assets.prepare(owner, draftOwner, snapshot)
        assertEquals(snapshot.single().attachment.id, refs.single().id)
        assertArrayEquals(bytes, drafts.read(draftOwner, snapshot.single().attachment))
        drafts.clear(draftOwner)
        val reopened = MessageImageAssetStore(assetRoot)
        val restored = refs.map { MessageImageReference.fromJson(it.toJson()) }
        assertEquals(refs, restored)
        val history = reopened.resolver.freeze(owner, restored, true).single()
        history.bytes().fill(0)
        reopened.resolver.read(owner, restored.single()).fill(0)
        assertArrayEquals(bytes, history.bytes())
        assertArrayEquals(bytes, reopened.resolver.read(owner, restored.single()))
        assertNotEquals(draftOwner, history.attachment.owner)
    }

    @Test fun retryIsIdempotentEvenAfterDraftRemovalButDifferentSnapshotIsRejected() {
        val snapshot = frozen()
        val refs = assets.prepare(owner, draftOwner, snapshot)
        drafts.clear(draftOwner)
        assertEquals(refs, MessageImageAssetStore(assetRoot).prepare(owner, draftOwner, snapshot))
        failure(ImageAttachmentFailure.CORRUPT) { assets.prepare(owner, draftOwner, frozen()) }
        assertArrayEquals(bytes, assets.resolver.read(owner, refs.single()))
        assertEquals(1, assetRoot.listFiles()!!.size)
    }

    @Test fun sameBytesInDifferentMessagesAreIndependentAndDeletionIsExact() {
        val snapshot = frozen()
        val refs = assets.prepare(owner, draftOwner, snapshot)
        val otherOwner = owner.copy(messageId = "other-message")
        val other = assets.prepare(otherOwner, draftOwner, snapshot)
        assertTrue(assets.discardUnreferenced(owner, refs))
        assertFalse(assets.discardUnreferenced(owner, refs))
        assertArrayEquals(bytes, assets.resolver.read(otherOwner, other.single()))
        assertArrayEquals(bytes, drafts.read(draftOwner, snapshot.single().attachment))
        failure(ImageAttachmentFailure.MISSING) { assets.resolver.read(owner, refs.single()) }
    }

    @Test fun ownerAndReferenceTamperingCannotReadOrDelete() {
        val refs = assets.prepare(owner, draftOwner, frozen())
        val ref = refs.single()
        val other = owner.copy(conversationId = "other")
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { assets.resolver.read(other, ref) }
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { assets.discardUnreferenced(other, refs) }
        failure(ImageAttachmentFailure.CORRUPT) { assets.resolver.read(owner, ref.copy(width = 99)) }
        failure(ImageAttachmentFailure.CORRUPT) { assets.discardUnreferenced(owner, listOf(ref.copy(width = 99))) }
        failure(ImageAttachmentFailure.CORRUPT) { assets.resolver.read(owner, ref.copy(id = "../outside")) }
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { assets.prepare(other, draftOwner, frozen()) }
        failure(ImageAttachmentFailure.OWNER_MISMATCH) {
            assets.prepare(owner, draftOwner.copy(draftId = "different"), frozen())
        }
        assertArrayEquals(bytes, assets.resolver.read(owner, ref))
    }

    @Test fun cancellationBeforePublishLeavesDraftAndNoPublishedBatch() {
        val snapshot = frozen()
        failure(ImageAttachmentFailure.CANCELLED) {
            assets.prepare(owner, draftOwner, snapshot) { File(directory(".pending"), "manifest.json").exists() }
        }
        assertTrue(assets.preparedReferences(owner).isEmpty())
        assertFalse(directory(".pending").exists())
        assertArrayEquals(bytes, drafts.read(draftOwner, snapshot.single().attachment))
        assertEquals(1, assets.prepare(owner, draftOwner, snapshot).size)
    }

    @Test fun failedPublicationDoesNotConsumeDraftOrTouchDestinationObstruction() {
        val snapshot = frozen()
        failure(ImageAttachmentFailure.STORAGE) {
            assets.prepare(owner, draftOwner, snapshot) {
                if (File(directory(".pending"), "manifest.json").exists()) {
                    directory().mkdir()
                    File(directory(), "unrelated").writeText("keep")
                }
                false
            }
        }
        assertEquals("keep", File(directory(), "unrelated").readText())
        assertFalse(directory(".pending").exists())
        assertArrayEquals(bytes, drafts.read(draftOwner, snapshot.single().attachment))
    }

    @Test fun failedOrUnknownDbOutcomeRetainsBothCopiesUntilExplicitRecoveryDecision() {
        val snapshot = frozen()
        val refs = assets.prepare(owner, draftOwner, snapshot)
        // A thrown/unknown DB outcome deliberately invokes no asset cleanup.
        val reopened = MessageImageAssetStore(assetRoot)
        assertEquals(refs, reopened.preparedReferences(owner))
        assertArrayEquals(bytes, drafts.read(draftOwner, snapshot.single().attachment))
        assertArrayEquals(bytes, reopened.resolver.read(owner, refs.single()))
        // Only after authoritative DB absence under the host lock:
        assertTrue(reopened.discardUnreferenced(owner, refs))
        assertArrayEquals(bytes, drafts.read(draftOwner, snapshot.single().attachment))
    }

    @Test fun interruptedDeleteCanResumeFromTombstoneWithoutDeletingAnotherMessage() {
        val refs = assets.prepare(owner, draftOwner, frozen())
        Files.move(directory().toPath(), directory(".deleting").toPath(), ATOMIC_MOVE)
        File(directory(".deleting"), refs.single().id + ".image").delete()
        val reopened = MessageImageAssetStore(assetRoot)
        assertEquals(refs, reopened.preparedReferences(owner))
        assertTrue(reopened.discardUnreferenced(owner, refs))
        assertFalse(directory(".deleting").exists())
        // Crash after deleting the manifest but before deleting the empty tombstone.
        directory(".deleting").mkdir()
        assertTrue(reopened.preparedReferences(owner).isEmpty())
        assertTrue(reopened.discardUnreferenced(owner, emptyList()))
    }

    @Test fun staleStagingIsRemovedOnlyForSelectedMessageAndPublishedCopyIsRetained() {
        val refs = assets.prepare(owner, draftOwner, frozen())
        directory(".pending").mkdir()
        File(directory(".pending"), refs.single().id + ".image").writeBytes(bytes)
        val other = MessageImageOwner("conversation", "other")
        val otherStage = File(assetRoot, other.directoryName() + ".pending").apply { mkdir() }
        val otherFile = File(otherStage, refs.single().id + ".image").apply { writeBytes(bytes) }
        assets.cleanStaging(owner)
        assertFalse(directory(".pending").exists())
        assertTrue(otherFile.exists())
        assertArrayEquals(bytes, assets.resolver.read(owner, refs.single()))
    }

    @Test fun corruptionAndMissingFilesDoNotFallBackToDraftOrProvider() {
        val ref = assets.prepare(owner, draftOwner, frozen()).single()
        val image = File(directory(), ref.id + ".image")
        image.writeBytes(byteArrayOf(5, 4, 3, 2, 1))
        failure(ImageAttachmentFailure.CORRUPT) { assets.resolver.read(owner, ref) }
        image.delete()
        failure(ImageAttachmentFailure.MISSING) { assets.resolver.read(owner, ref) }
    }

    @Test fun metadataSymlinkAndDirectorySymlinksCannotEscapeBoundary() {
        val ref = assets.prepare(owner, draftOwner, frozen()).single()
        val image = File(directory(), ref.id + ".image")
        val outside = File(root, "outside").apply { writeBytes(bytes) }
        image.delete()
        Files.createSymbolicLink(image.toPath(), outside.toPath())
        failure(ImageAttachmentFailure.CORRUPT) { assets.resolver.read(owner, ref) }
        // Deletion unlinks the owned leaf, not its target.
        assertTrue(assets.discardUnreferenced(owner, listOf(ref)))
        assertArrayEquals(bytes, outside.readBytes())
        Files.createSymbolicLink(directory().toPath(), root.toPath())
        failure(ImageAttachmentFailure.CORRUPT) { assets.cleanStaging(owner); assets.preparedReferences(owner) }
        assertArrayEquals(bytes, outside.readBytes())
    }

    @Test fun replacedRootIsRejectedByDraftAndMessageStores() {
        val snapshot = frozen()
        val refs = assets.prepare(owner, draftOwner, snapshot)
        val moved = File(root, "moved-messages")
        assertTrue(assetRoot.renameTo(moved))
        Files.createSymbolicLink(assetRoot.toPath(), moved.toPath())
        failure(ImageAttachmentFailure.CORRUPT) { assets.resolver.read(owner, refs.single()) }
        failure(ImageAttachmentFailure.CORRUPT) { assets.discardUnreferenced(owner, refs) }
        val draftRoot = File(root, "drafts")
        val movedDrafts = File(root, "moved-drafts")
        assertTrue(draftRoot.renameTo(movedDrafts))
        Files.createSymbolicLink(draftRoot.toPath(), movedDrafts.toPath())
        failure(ImageAttachmentFailure.CORRUPT) { drafts.clear(draftOwner) }
    }

    @Test fun limitsCapabilityAndFrozenMetadataAreRechecked() {
        val snapshot = frozen()
        val limited = MessageImageAssetStore(assetRoot, ImageAttachmentLimits(maxImageBytes = 4))
        failure(ImageAttachmentFailure.TOO_LARGE) { limited.prepare(owner, draftOwner, snapshot) }
        failure(ImageAttachmentFailure.CORRUPT) { assets.prepare(owner, draftOwner, snapshot + snapshot) }
        val corrupt = FrozenImageAttachment(snapshot.single().attachment, bytes.reversedArray())
        failure(ImageAttachmentFailure.CORRUPT) { assets.prepare(owner, draftOwner, listOf(corrupt)) }
        val refs = assets.prepare(owner, draftOwner, snapshot)
        failure(ImageAttachmentFailure.MODEL_UNSUPPORTED) { assets.resolver.freeze(owner, refs, false) }
        failure(ImageAttachmentFailure.CANCELLED) { assets.resolver.freeze(owner, refs, true) { true } }
        assertTrue(assets.resolver.freeze(owner, emptyList(), false).isEmpty())
        assertArrayEquals(bytes, drafts.read(draftOwner, snapshot.single().attachment))
    }

    @Test fun completeBatchOrderAndAggregateLimitsArePreserved() {
        val snapshot = frozen() + frozen()
        val countLimited = MessageImageAssetStore(assetRoot, ImageAttachmentLimits(maxImages = 1))
        failure(ImageAttachmentFailure.TOO_MANY) { countLimited.prepare(owner, draftOwner, snapshot) }
        val byteLimited = MessageImageAssetStore(assetRoot, ImageAttachmentLimits(maxDraftBytes = 9))
        failure(ImageAttachmentFailure.TOO_LARGE) { byteLimited.prepare(owner, draftOwner, snapshot) }
        assertTrue(assets.preparedReferences(owner).isEmpty())
        val refs = assets.prepare(owner, draftOwner, snapshot)
        assertEquals(snapshot.map { it.attachment.id }, refs.map { it.id })
        failure(ImageAttachmentFailure.CORRUPT) { assets.prepare(owner, draftOwner, snapshot.reversed()) }
        drafts.clear(draftOwner)
        assertEquals(2, assets.resolver.freeze(owner, refs, true).size)
    }

    @Test fun trustedPlatformParentAliasIsAnchoredAndRootSymlinkIsRejected() {
        val alias = File(root, "platform-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        val anchored = MessageImageAssetStore(File(alias, "anchored"))
        val refs = anchored.prepare(owner, draftOwner, frozen())
        alias.delete() // Later changes to the platform alias cannot redirect the established root.
        assertArrayEquals(bytes, anchored.resolver.read(owner, refs.single()))
        val rootLink = File(root, "root-link")
        Files.createSymbolicLink(rootLink.toPath(), File(root, "anchored").toPath())
        failure(ImageAttachmentFailure.CORRUPT) { MessageImageAssetStore(rootLink) }
    }

    @Test fun manifestIdentityAndSerializedReferenceVersionAreChecked() {
        val refs = assets.prepare(owner, draftOwner, frozen())
        val metadata = File(directory(), "manifest.json")
        metadata.writeText(JSONObject(metadata.readText()).put("message", "other").toString())
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { assets.resolver.read(owner, refs.single()) }
        failure(ImageAttachmentFailure.OWNER_MISMATCH) { assets.discardUnreferenced(owner, refs) }
        failure(ImageAttachmentFailure.CORRUPT) {
            MessageImageReference.fromJson(JSONObject(refs.single().toJson()).put("version", 2).toString())
        }
    }
}
