package app.daiyu.android.domain

/**
 * A shared compression queue must not lock unrelated conversations out of sending.
 * Null identifies the unsaved draft here, not a wildcard for every conversation.
 * Keep the current conversation flag: Runtime/pre-send compaction has no shared job.
 */
public fun conversationCompressionBlocksSend(
    conversationId: String?,
    isCompressingContext: Boolean,
    jobActive: Boolean,
    jobConversationId: String?,
    hasPending: Boolean,
    pendingConversationId: String?,
): Boolean = isCompressingContext ||
    (jobActive && jobConversationId == conversationId) ||
    (hasPending && pendingConversationId == conversationId)
