package app.daiyu.android.storage

import app.daiyu.android.domain.*

/** Inert presentation data, never a provider history or an action/asset capability. */
internal data class MessageDisplayWindow(val rows: List<Pair<Long, MessageDisplayRow>>) {
    init {
        val originals = rows.map { it.second }.associateBy { Triple(it.conversationId, it.runId, it.id) }
        val supplements = mutableSetOf<Triple<ConversationId, RunId, MessageId>>()
        rows.forEach { (_, owner) -> owner.segments.filter { it.role == Message.USER_ROLE }.forEach { segment ->
            val key = Triple(owner.conversationId, owner.runId, segment.messageId)
            require(supplements.add(key))
            originals[key]?.let { row ->
                require(row.role == Message.USER_ROLE && row.segments.isEmpty() && row.status == RunStatus.COMPLETED && row.error == null)
                require(row.blocks == segment.blocks)
                require(segment.createdAt == null || row.createdAt == null || segment.createdAt == row.createdAt)
            }
        } }
    }
}

internal data class MessageDisplaySegment(
    val messageId: MessageId,
    val role: String,
    val blocks: List<MessageBlock>,
    val createdAt: Long?,
    val reportedReasoning: List<ReportedReasoning>,
)

internal data class MessageDisplayRow(
    val id: MessageId,
    val conversationId: ConversationId,
    val runId: RunId,
    val role: String,
    val blocks: List<MessageBlock>,
    val status: RunStatus,
    val error: String?,
    val createdAt: Long?,
    val segments: List<MessageDisplaySegment>,
) {
    init {
        require(listOf(id.value, conversationId.value, runId.value).all { it.isNotBlank() && it.length <= 200 })
        require(role == Message.USER_ROLE || role == Message.ASSISTANT_ROLE)
        blocks.forEach { block -> when (block) {
            is MessageBlock.Text -> require(block.itemId.isNotBlank() && block.itemId.length <= 200)
            is MessageBlock.Tool -> require(role == Message.ASSISTANT_ROLE && block.invocation.id.value.isNotBlank() &&
                block.invocation.id.value.length <= 200 && block.invocation.name.isNotBlank() && block.invocation.name.length <= 200)
            is MessageBlock.Image -> Unit
        } }
        MessageImages.validateBlocks(blocks, MessageImageOwner(conversationId.value, id.value))
        if (segments.isNotEmpty()) {
            require(role == Message.ASSISTANT_ROLE)
            val users = mutableSetOf<MessageId>()
            segments.forEach {
                require(it.messageId.value.isNotBlank() && it.messageId.value.length <= 200)
                require(it.role == Message.USER_ROLE || it.role == Message.ASSISTANT_ROLE)
                if (it.role == Message.ASSISTANT_ROLE) {
                    require(it.messageId == id)
                    require(it.createdAt == null || createdAt == null || it.createdAt == createdAt)
                } else require(it.messageId != id && users.add(it.messageId))
                MessageImages.validateBlocks(it.blocks, MessageImageOwner(conversationId.value, it.messageId.value))
            }
        }
    }
}
