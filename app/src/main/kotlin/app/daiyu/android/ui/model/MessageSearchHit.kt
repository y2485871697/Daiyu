package app.daiyu.android.ui.model

data class MessageSearchHit(
    val conversationId: String,
    val conversationTitle: String,
    val messageId: String,
    val snippet: String,
    val roleLabel: String,
    val ownerMessageId: String = messageId,
    /** Zero-based occurrence of messageId within its run, not the database owner or window index. */
    val segmentIndex: Int? = null,
)
