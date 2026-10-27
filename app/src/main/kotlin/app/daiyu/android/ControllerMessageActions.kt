package app.daiyu.android

import app.daiyu.android.domain.Conversation
import app.daiyu.android.storage.MessageActionSnapshot
import app.daiyu.android.storage.MessageActionTarget

internal enum class MessageAction { EDIT_USER, SAVE_EDIT, BRANCH, DELETE_TURN, REGENERATE, RETRY_STEP }
/** Receipt for THIS preparation, not inferred from a later history refresh or another send. */
internal enum class RegenerationSendOutcome { NOT_SENT, ACCEPTED, UNKNOWN }
internal sealed interface MessageActionResult {
    data class EditDraft(val text: String, val target: MessageActionTarget? = null,
        val videos: List<app.daiyu.android.media.video.MessageVideoReference> = emptyList()) : MessageActionResult
    data class Branch(val conversation: Conversation) : MessageActionResult
    data object Deleted : MessageActionResult
    data class Regenerated(val conversation: Conversation, val outcome: RegenerationSendOutcome) : MessageActionResult
    data class Rejected(val reason: String) : MessageActionResult
    /** Commit/receipt uncertain: refresh persisted history, NEVER automatically retry. */
    data class Unknown(val reason: String, val locator: app.daiyu.android.context.BranchLocator? = null) : MessageActionResult
}

/** Activity-local owner lease. Keep one host per Activity and invalidate it on conversation /
 * editor generation / draft revision / lifecycle changes. Each returned callback closes over its
 * rendered snapshot, not a mutable selected-message lookup. A late callback cannot write a new
 * editor or navigate a replacement Activity. Backend commits still refresh via dataRevision. */
internal class ControllerMessageActionHost(private val controller: ChatController) {
    private var lease: Any? = null
    private var ownerEpoch: Any = Any()
    fun invalidate() { lease = null; ownerEpoch = Any() }

    fun bind(snapshot: MessageActionSnapshot?, isCurrent: () -> Boolean,
        result: (MessageActionResult) -> Unit): Callbacks {
        val token = Any().also { lease = it }
        val admittedOwner = ownerEpoch
        fun invoke(action: MessageAction, id: String, text: String? = null,
            images: List<app.daiyu.android.media.ImageAttachment> = emptyList()) {
            if (lease !== token || !isCurrent()) return
            val target: MessageActionTarget? = if (action == MessageAction.RETRY_STEP) snapshot?.steps?.get(id)
                else (snapshot?.renderedTargets?.get(id) ?: snapshot?.targets?.get(id))
            if (target == null) {
                result(MessageActionResult.Rejected("消息操作快照不可用；请刷新后重试")); return
            }
            val admitted = controller.messageAction(action, target, replacementText = text, editImages = images) {
                // Busy/stream notifications may legitimately re-render the SAME editor while
                // work is in flight. Rebinding fences old gestures, not an admitted receipt.
                // Branch UNKNOWN is already retained by the controller, outside this UI lease;
                // do NOT relax the fence to deliver navigation/editor effects to an old host.
                if (ownerEpoch === admittedOwner && isCurrent()) result(it)
            }
            if (!admitted) result(MessageActionResult.Rejected("请等待运行、恢复或数据操作结束后再操作消息"))
        }
        return Callbacks(
            onRegenerate = { invoke(MessageAction.REGENERATE, it) },
            onBranch = { invoke(MessageAction.BRANCH, it) },
            onEditUser = { invoke(MessageAction.EDIT_USER, it) },
            onSaveEdit = { id, text -> invoke(MessageAction.SAVE_EDIT, id, text) },
            onSaveEditImages = { id, text, images -> invoke(MessageAction.SAVE_EDIT, id, text, images.toList()) },
            onDeleteTurn = { invoke(MessageAction.DELETE_TURN, it) },
            onRetryStep = { invoke(MessageAction.RETRY_STEP, it) })
    }
    data class Callbacks(val onRegenerate: (String) -> Unit, val onBranch: (String) -> Unit,
        val onEditUser: (String) -> Unit, val onSaveEdit: (String, String) -> Unit, val onDeleteTurn: (String) -> Unit, val onRetryStep: (String) -> Unit,
        val onSaveEditImages: (String, String, List<app.daiyu.android.media.ImageAttachment>) -> Unit)
}
