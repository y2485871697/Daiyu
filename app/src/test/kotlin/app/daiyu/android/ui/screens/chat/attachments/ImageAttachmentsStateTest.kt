package app.daiyu.android.ui.screens.chat.attachments

import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.ImageAttachmentLimits
import app.daiyu.android.media.ImageAttachmentOwner
import org.junit.Assert.*
import org.junit.Test

class ImageAttachmentsStateTest {
    private val owner = ImageAttachmentOwner("conversation-a", "draft-a")
    private val draft = ImageAttachmentDraft(owner, 1)
    private fun image(id: String, owner: ImageAttachmentOwner = this.owner, bytes: Int = 10) =
        ImageAttachment(owner, id, "image/png", bytes, "digest-$id", 32, 24)
    private fun slots(state: ImageAttachmentsState, count: Int = 1): List<ImageAttachmentItemKey> =
        state.beginImport(requireNotNull(state.requestSelection()), count)
    private fun ready(state: ImageAttachmentsState, id: String): ImageAttachmentItemKey =
        slots(state).single().also { assertTrue(state.importSucceeded(it, image(id))) }
    private fun ids(state: ImageAttachmentsState) = state.state.value.ready.map { it.attachment.id }

    @Test fun multiSelectionHasOrderedSlotsAndIndependentOutOfOrderResults() {
        val state = ImageAttachmentsState(draft)
        val request = requireNotNull(state.requestSelection())
        assertEquals(8, request.remaining)
        assertNull(state.requestSelection())
        assertFalse(state.state.value.canSubmit)
        val slots = state.beginImport(request, 3)
        assertEquals(3, state.state.value.items.size)
        assertTrue(state.state.value.isImporting)
        assertTrue(state.importSucceeded(slots[2], image("third")))
        assertTrue(state.importFailed(slots[1], ImageAttachmentFailure.UNSUPPORTED_IMAGE))
        assertTrue(state.importSucceeded(slots[0], image("first")))
        assertEquals(listOf("first", "third"), ids(state))
        assertFalse(state.state.value.isImporting)
        assertTrue(state.state.value.items[1] is ImageAttachmentItem.Failed)
        assertTrue(state.state.value.canSubmit)
        assertFalse(state.importSucceeded(slots[0], image("duplicate-callback")))
        assertFalse(state.importFailed(slots[0], ImageAttachmentFailure.STORAGE))
        assertTrue(state.beginImport(request, 1).isEmpty())
    }

    @Test fun pickerCancelAndEmptySelectionPreservePriorReadyImages() {
        val state = ImageAttachmentsState(draft, listOf(image("prior")))
        val request = requireNotNull(state.requestSelection())
        assertTrue(state.cancelSelection(request))
        assertFalse(state.cancelSelection(request))
        assertEquals(listOf("prior"), ids(state))
        assertTrue(state.beginImport(requireNotNull(state.requestSelection()), 0).isEmpty())
        assertEquals(listOf("prior"), ids(state))
        assertTrue(state.state.value.canSubmit)
    }

    @Test fun removingAnImportSlotFencesItsLateCompletionAndKeepsSiblings() {
        val state = ImageAttachmentsState(draft)
        val slots = slots(state, 2)
        assertTrue(state.remove(slots[0]) is ImageAttachmentItem.Importing)
        assertFalse(state.importSucceeded(slots[0], image("removed")))
        assertFalse(state.importFailed(slots[0], ImageAttachmentFailure.STORAGE))
        assertTrue(state.importSucceeded(slots[1], image("sibling")))
        assertEquals(listOf("sibling"), ids(state))
    }

    @Test fun cancellationOfOneImportDoesNotCancelOtherImages() {
        val state = ImageAttachmentsState(draft, listOf(image("prior")))
        val slots = slots(state, 2)
        assertTrue(state.importSucceeded(slots[0], image("new")))
        assertTrue(state.importFailed(slots[1], ImageAttachmentFailure.CANCELLED))
        assertEquals(listOf("prior", "new"), ids(state))
        assertEquals(2, state.state.value.items.size)
    }

    @Test fun failureIsVisibleRemovableAndDoesNotDestroyExistingImages() {
        for (failure in ImageAttachmentFailure.entries.filterNot { it == ImageAttachmentFailure.CANCELLED }) {
            val state = ImageAttachmentsState(draft, listOf(image("prior")))
            val slot = slots(state).single()
            assertTrue(state.importFailed(slot, failure))
            assertEquals(failure, (state.state.value.items.last() as ImageAttachmentItem.Failed).failure)
            assertFalse(state.openPreview(slot))
            assertNotNull(state.remove(slot))
            assertEquals(listOf("prior"), ids(state))
        }
    }

    @Test fun wrongOwnerAndDuplicateAssetAreNeverAdopted() {
        val state = ImageAttachmentsState(draft, listOf(image("prior")))
        val slots = slots(state, 2)
        assertFalse(state.importSucceeded(slots[0], image("foreign", ImageAttachmentOwner("b", "b"))))
        assertEquals(ImageAttachmentFailure.OWNER_MISMATCH, (state.state.value.items[1] as ImageAttachmentItem.Failed).failure)
        assertFalse(state.importSucceeded(slots[1], image("prior")))
        assertEquals(listOf("prior"), ids(state))
        assertEquals(1, state.state.value.ready.size)
    }

    @Test fun imageAndDraftLimitsRejectWithoutDiscardingEarlierAttachments() {
        val limits = ImageAttachmentLimits(maxImageBytes = 15, maxImages = 3, maxDraftBytes = 20)
        val state = ImageAttachmentsState(draft, listOf(image("prior")), limits)
        val slots = slots(state, 2)
        assertFalse(state.state.value.canSelect)
        assertFalse(state.importSucceeded(slots[0], image("too-big", bytes = 16)))
        assertFalse(state.importSucceeded(slots[1], image("too-much-total", bytes = 11)))
        assertEquals(listOf("prior"), ids(state))
        slots.forEach { assertEquals(ImageAttachmentFailure.TOO_LARGE,
            (state.state.value.items.single { item -> item.key == it } as ImageAttachmentItem.Failed).failure) }
        state.remove(slots[0])
        assertEquals(1, requireNotNull(state.requestSelection()).remaining)
    }

    @Test fun invalidDimensionsAreFailedNotAdopted() {
        val state = ImageAttachmentsState(draft)
        val slots = slots(state, 2)
        assertFalse(state.importSucceeded(slots[0], image("zero").copy(width = 0)))
        assertFalse(state.importSucceeded(slots[1], image("pixels").copy(width = 10_000, height = 10_000)))
        assertTrue(ids(state).isEmpty())
    }

    @Test fun providerOverLimitDoesNotSilentlyTruncateSelection() {
        val state = ImageAttachmentsState(draft, limits = ImageAttachmentLimits(maxImages = 1))
        val request = requireNotNull(state.requestSelection())
        assertThrows(IllegalArgumentException::class.java) { state.beginImport(request, 2) }
        assertEquals(request, state.state.value.selection)
        assertTrue(state.cancelSelection(request))
        assertTrue(state.state.value.items.isEmpty())
    }

    @Test fun previewIsScopedAndRemovalClosesOnlyThatImage() {
        val state = ImageAttachmentsState(draft)
        val first = ready(state, "first")
        val second = ready(state, "second")
        assertTrue(state.openPreview(first))
        assertTrue(state.openPreview(second))
        state.closePreview(first)
        assertEquals(second, state.state.value.preview)
        state.remove(first)
        assertEquals(second, state.state.value.preview)
        state.remove(second)
        assertNull(state.state.value.preview)
        assertFalse(state.openPreview(first))
    }

    @Test fun acceptedConsumesOnlyCapturedImagesAfterOwnershipTransfer() {
        val state = ImageAttachmentsState(draft)
        val first = ready(state, "first")
        state.openPreview(first)
        val sent = requireNotNull(state.beginSubmission())
        assertEquals(listOf(image("first")), sent.attachments)
        assertFalse(state.state.value.canRemove(first))
        assertNull(state.remove(first))
        assertNull(state.beginSubmission())
        ready(state, "added-after-send")
        assertEquals(listOf(image("first")), sent.attachments)
        assertTrue(state.finishSubmission(sent, ImageAttachmentAcceptance.Accepted))
        assertEquals(listOf("added-after-send"), ids(state))
        assertNull(state.state.value.preview)
        assertNull(state.state.value.submission)
        assertFalse(state.finishSubmission(sent, ImageAttachmentAcceptance.Accepted))
    }

    @Test fun rejectionUnknownAndSendCancellationAlwaysRetainImagesAndPreview() {
        for (outcome in listOf(ImageAttachmentAcceptance.Rejected, ImageAttachmentAcceptance.Unknown, ImageAttachmentAcceptance.Cancelled)) {
            val state = ImageAttachmentsState(draft)
            val key = ready(state, "kept")
            state.openPreview(key)
            val sent = requireNotNull(state.beginSubmission())
            assertTrue(state.finishSubmission(sent, outcome))
            assertEquals(listOf("kept"), ids(state))
            assertEquals(key, state.state.value.preview)
            assertTrue(state.state.value.canRemove(key))
        }
    }

    @Test fun sendCannotStartWhilePickerOrImportIsPendingOrWithoutReadyImages() {
        val state = ImageAttachmentsState(draft)
        assertNull(state.beginSubmission())
        ready(state, "prior")
        val request = requireNotNull(state.requestSelection())
        assertNull(state.beginSubmission())
        val key = state.beginImport(request, 1).single()
        assertNull(state.beginSubmission())
        state.importFailed(key, ImageAttachmentFailure.INVALID_IMAGE)
        assertNotNull(state.beginSubmission())
    }

    @Test fun conversationAndDraftVersionChangesFenceEveryOldOperation() {
        val destinations = listOf(ImageAttachmentDraft(ImageAttachmentOwner("b", "draft-b"), 1), draft.copy(revision = 2))
        for (destination in destinations) {
            val state = ImageAttachmentsState(draft)
            val oldReady = ready(state, "old")
            val oldSend = requireNotNull(state.beginSubmission())
            val oldSlot = slots(state).single()
            val oldPicker = requireNotNull(state.requestSelection())
            state.activate(destination, listOf(image("destination", destination.owner)))
            assertFalse(state.cancelSelection(oldPicker))
            assertTrue(state.beginImport(oldPicker, 1).isEmpty())
            assertFalse(state.importSucceeded(oldSlot, image("late")))
            assertFalse(state.importFailed(oldSlot, ImageAttachmentFailure.STORAGE))
            assertNull(state.remove(oldReady))
            assertFalse(state.openPreview(oldReady))
            assertFalse(state.finishSubmission(oldSend, ImageAttachmentAcceptance.Accepted))
            assertNull(state.requestSelection(draft))
            assertNull(state.beginSubmission(draft))
            assertEquals(listOf("destination"), ids(state))
        }
    }

    @Test fun returningToSameOwnerDoesNotReviveOldPickerOrSend() {
        val state = ImageAttachmentsState(draft)
        val oldReady = ready(state, "old")
        val sent = requireNotNull(state.beginSubmission())
        val picker = requireNotNull(state.requestSelection())
        state.activate(ImageAttachmentDraft(ImageAttachmentOwner("b", "b"), 1))
        state.activate(draft, listOf(image("old")))
        assertFalse(state.state.value.ready.single().key == oldReady)
        assertTrue(state.beginImport(picker, 1).isEmpty())
        assertFalse(state.finishSubmission(sent, ImageAttachmentAcceptance.Accepted))
        assertEquals(listOf("old"), ids(state))
    }

    @Test fun recreatedStateRestoresReadyReferencesButNeverOldOperations() {
        val before = ImageAttachmentsState(draft, listOf(image("persisted")))
        val sent = requireNotNull(before.beginSubmission())
        val importKey = slots(before).single()
        val picker = requireNotNull(before.requestSelection())
        val recreated = ImageAttachmentsState(draft, before.state.value.ready.map { it.attachment })
        assertFalse(recreated.finishSubmission(sent, ImageAttachmentAcceptance.Accepted))
        assertFalse(recreated.importSucceeded(importKey, image("late")))
        assertTrue(recreated.beginImport(picker, 1).isEmpty())
        assertNull(recreated.state.value.selection)
        assertFalse(recreated.state.value.isImporting)
        assertNull(recreated.state.value.submission)
        assertEquals(listOf("persisted"), ids(recreated))
    }

    @Test fun invalidRestoreIsAtomicAndSameDraftRebindIsANoOp() {
        val state = ImageAttachmentsState(draft, listOf(image("persisted")))
        val slot = slots(state).single()
        state.activate(draft)
        assertTrue(state.importSucceeded(slot, image("still-active")))
        assertThrows(IllegalArgumentException::class.java) {
            state.activate(draft.copy(revision = 2), listOf(image("wrong", ImageAttachmentOwner("b", "b"))))
        }
        assertEquals(draft, state.state.value.draft)
        assertEquals(listOf("persisted", "still-active"), ids(state))
        assertThrows(IllegalArgumentException::class.java) { ImageAttachmentsState(draft, listOf(image("same"), image("same"))) }
    }
}
