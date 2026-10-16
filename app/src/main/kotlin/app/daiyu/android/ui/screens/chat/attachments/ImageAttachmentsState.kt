package app.daiyu.android.ui.screens.chat.attachments

import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.ImageAttachmentLimits
import app.daiyu.android.media.ImageAttachmentOwner
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Increment revision when replacing/clearing a draft, even if its owner stays the same. */
data class ImageAttachmentDraft(val owner: ImageAttachmentOwner, val revision: Long) {
    init { require(revision >= 0) }
}

/** Opaque UI-operation identity, NOT an asset ID or a persisted picker URI. */
data class ImageAttachmentItemKey internal constructor(
    val draft: ImageAttachmentDraft,
    internal val session: String,
    val id: String,
)

data class ImageAttachmentSelection internal constructor(
    val draft: ImageAttachmentDraft,
    internal val session: String,
    val id: String,
    val remaining: Int,
)

sealed interface ImageAttachmentItem {
    val key: ImageAttachmentItemKey
    data class Importing(override val key: ImageAttachmentItemKey) : ImageAttachmentItem
    data class Ready(override val key: ImageAttachmentItemKey, val attachment: ImageAttachment) : ImageAttachmentItem
    data class Failed(override val key: ImageAttachmentItemKey, val failure: ImageAttachmentFailure) : ImageAttachmentItem
}

/** Host maps its authoritative send receipt here; completion of a coroutine is not acceptance. */
enum class ImageAttachmentAcceptance { Accepted, Rejected, Unknown, Cancelled }

/** Immutable references captured before sending. The host freezes bytes through the media API. */
data class ImageAttachmentSubmission internal constructor(
    val draft: ImageAttachmentDraft,
    internal val session: String,
    val id: String,
    val items: List<ImageAttachmentItem.Ready>,
) {
    val attachments: List<ImageAttachment> get() = items.map { it.attachment }
}

data class ImageAttachmentsUiState internal constructor(
    val draft: ImageAttachmentDraft,
    val items: List<ImageAttachmentItem> = emptyList(),
    val selection: ImageAttachmentSelection? = null,
    val preview: ImageAttachmentItemKey? = null,
    val submission: ImageAttachmentSubmission? = null,
    val maxImages: Int = 8,
) {
    val ready: List<ImageAttachmentItem.Ready> get() = items.filterIsInstance<ImageAttachmentItem.Ready>()
    val isImporting: Boolean get() = items.any { it is ImageAttachmentItem.Importing }
    val canSelect: Boolean get() = selection == null && items.size < maxImages
    val canSubmit: Boolean get() = ready.isNotEmpty() && !isImporting && selection == null && submission == null
    fun canRemove(key: ImageAttachmentItemKey): Boolean =
        items.any { it.key == key } && submission?.items?.none { it.key == key } != false
}

/**
 * UI-only state machine. No Activity, content URI, controller or disk access is retained.
 *
 * Keep this in the host's retained draft owner (or collect [state] in Compose). After Activity/process
 * recreation, construct a NEW instance with persisted Ready references; old picker/import/send
 * tokens then cannot match, even with the same owner/revision. Never save operation tokens.
 * Every async callback must pass the original token, never `state.value.draft` at completion time.
 * Methods are serialized so import/send callbacks may safely arrive from different dispatchers.
 */
class ImageAttachmentsState(
    draft: ImageAttachmentDraft,
    initialAttachments: List<ImageAttachment> = emptyList(),
    private val limits: ImageAttachmentLimits = ImageAttachmentLimits(),
) {
    private var session = UUID.randomUUID().toString()
    private val mutableState = MutableStateFlow(initialState(draft, initialAttachments))
    val state: StateFlow<ImageAttachmentsUiState> = mutableState.asStateFlow()

    private fun itemKey(draft: ImageAttachmentDraft) =
        ImageAttachmentItemKey(draft, session, UUID.randomUUID().toString())

    private fun initialState(draft: ImageAttachmentDraft, attachments: List<ImageAttachment>): ImageAttachmentsUiState {
        require(attachments.size <= limits.maxImages)
        require(attachments.map { it.id }.distinct().size == attachments.size)
        require(attachments.all {
            it.owner == draft.owner && it.bytes > 0 && it.bytes <= limits.maxImageBytes &&
                it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= limits.maxPixels
        })
        require(attachments.sumOf { it.bytes.toLong() } <= limits.maxDraftBytes)
        return ImageAttachmentsUiState(draft, attachments.map { ImageAttachmentItem.Ready(itemKey(draft), it) }, maxImages = limits.maxImages)
    }

    /** Switching owners invalidates ALL callbacks, including switch-away-and-back callbacks. */
    @Synchronized
    fun activate(draft: ImageAttachmentDraft, attachments: List<ImageAttachment> = emptyList()) {
        if (mutableState.value.draft == draft) return
        // Validate before changing session, so invalid restoration cannot damage the active draft.
        val next = initialState(draft, attachments)
        session = UUID.randomUUID().toString()
        mutableState.value = next.copy(items = next.ready.map { it.copy(key = itemKey(draft)) })
    }

    @Synchronized
    fun requestSelection(expectedDraft: ImageAttachmentDraft = mutableState.value.draft): ImageAttachmentSelection? {
        val current = mutableState.value
        if (current.draft != expectedDraft || !current.canSelect) return null
        val request = ImageAttachmentSelection(current.draft, session, UUID.randomUUID().toString(), limits.maxImages - current.items.size)
        mutableState.value = current.copy(selection = request)
        return request
    }

    /** Empty selection/back/cancel only closes this request; existing attachments remain intact. */
    @Synchronized
    fun cancelSelection(request: ImageAttachmentSelection): Boolean {
        if (mutableState.value.selection != request) return false
        mutableState.value = mutableState.value.copy(selection = null)
        return true
    }

    /**
     * Reserve ordered slots before starting independent imports. Count must fit [request.remaining];
     * on an oversized provider result the host can cancel and present its validation error.
     * URI -> slot correspondence belongs to the host and must not be persisted here.
     */
    @Synchronized
    fun beginImport(request: ImageAttachmentSelection, count: Int): List<ImageAttachmentItemKey> {
        val current = mutableState.value
        if (current.selection != request) return emptyList()
        require(count in 0..request.remaining)
        val keys = List(count) { itemKey(current.draft) }
        mutableState.value = current.copy(selection = null, items = current.items + keys.map { ImageAttachmentItem.Importing(it) })
        return keys
    }

    /**
     * True means adopted into this draft. False means stale/duplicate/rejected: the host must clean
     * up the unadopted asset under ITS ORIGINAL owner, without deleting an already retained ID.
     */
    @Synchronized
    fun importSucceeded(key: ImageAttachmentItemKey, attachment: ImageAttachment): Boolean {
        val current = mutableState.value
        if (current.items.none { it.key == key && it is ImageAttachmentItem.Importing }) return false
        val failure = when {
            attachment.owner != current.draft.owner -> ImageAttachmentFailure.OWNER_MISMATCH
            attachment.bytes <= 0 || attachment.width <= 0 || attachment.height <= 0 -> ImageAttachmentFailure.INVALID_IMAGE
            attachment.bytes > limits.maxImageBytes || attachment.width.toLong() * attachment.height > limits.maxPixels -> ImageAttachmentFailure.TOO_LARGE
            current.ready.any { it.attachment.id == attachment.id } -> ImageAttachmentFailure.INVALID_IMAGE
            current.ready.sumOf { it.attachment.bytes.toLong() } + attachment.bytes > limits.maxDraftBytes -> ImageAttachmentFailure.TOO_LARGE
            else -> null
        }
        val replacement = if (failure == null) ImageAttachmentItem.Ready(key, attachment) else ImageAttachmentItem.Failed(key, failure)
        mutableState.value = current.copy(items = current.items.map { if (it.key == key) replacement else it })
        return failure == null
    }

    @Synchronized
    fun importFailed(key: ImageAttachmentItemKey, failure: ImageAttachmentFailure): Boolean {
        val current = mutableState.value
        if (current.items.none { it.key == key && it is ImageAttachmentItem.Importing }) return false
        // Import cancellation removes only its empty slot, not successful imports or previous images.
        mutableState.value = current.copy(items = if (failure == ImageAttachmentFailure.CANCELLED) current.items.filterNot { it.key == key }
            else current.items.map { if (it.key == key) ImageAttachmentItem.Failed(key, failure) else it })
        return true
    }

    /** Returns the removed item so the host can cancel I/O or dispose draft storage if necessary. */
    @Synchronized
    fun remove(key: ImageAttachmentItemKey): ImageAttachmentItem? {
        val current = mutableState.value
        if (!current.canRemove(key)) return null
        val removed = current.items.first { it.key == key }
        mutableState.value = current.copy(items = current.items.filterNot { it.key == key }, preview = current.preview.takeUnless { it == key })
        return removed
    }

    @Synchronized
    fun openPreview(key: ImageAttachmentItemKey): Boolean {
        if (mutableState.value.ready.none { it.key == key }) return false
        mutableState.value = mutableState.value.copy(preview = key)
        return true
    }

    /** Scope dismiss to the preview which opened the dialog, not a newer dialog. */
    @Synchronized
    fun closePreview(key: ImageAttachmentItemKey) {
        if (mutableState.value.preview == key) mutableState.value = mutableState.value.copy(preview = null)
    }

    @Synchronized
    fun beginSubmission(expectedDraft: ImageAttachmentDraft = mutableState.value.draft): ImageAttachmentSubmission? {
        val current = mutableState.value
        if (current.draft != expectedDraft || !current.canSubmit) return null
        val submission = ImageAttachmentSubmission(current.draft, session, UUID.randomUUID().toString(), current.ready.toList())
        mutableState.value = current.copy(submission = submission)
        return submission
    }

    /**
     * Accepted consumes exactly the captured entries, never images imported after the send began.
     * Rejected/Unknown/Cancelled retain every attachment. Unknown is NOT permission to resend;
     * host is responsible for idempotency/reconciliation before permitting another send.
     * Asset ownership transfer/deletion is the host's job and must precede Accepted.
     */
    @Synchronized
    fun finishSubmission(submission: ImageAttachmentSubmission, acceptance: ImageAttachmentAcceptance): Boolean {
        val current = mutableState.value
        if (current.submission != submission) return false
        val consumed = if (acceptance == ImageAttachmentAcceptance.Accepted) submission.items.map { it.key }.toSet() else emptySet()
        mutableState.value = current.copy(submission = null, items = current.items.filterNot { it.key in consumed },
            preview = current.preview.takeUnless { it in consumed })
        return true
    }
}
