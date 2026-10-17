package app.daiyu.android.domain

enum class RunStatus { RUNNING, COMPLETED, STOPPED, FAILED, INTERRUPTED }
data class Conversation(val id: ConversationId, val title: String, val draft: String, val updatedAt: Long, val folder: String = "", val pinned: Boolean = false, val assistantId: String? = null)
data class ToolInvocation(val id: ToolCallId, val name: String, val arguments: String)
sealed interface MessageBlock {
    data class Text(val itemId: String, val text: String) : MessageBlock
    data class Tool(val invocation: ToolInvocation, val result: String? = null) : MessageBlock
    /** Stable asset reference only; original bytes remain in the message asset store. */
    data class Image(val reference: MessageImageReference) : MessageBlock
}

/** One ordered item in an assistant owner's persisted history. */
data class MessageHistorySegment(
    val messageId: MessageId,
    val role: String,
    val blocks: List<MessageBlock>,
    val protocolItems: List<String> = emptyList(),
    /** Epoch milliseconds recorded when this message was accepted; null means unknown. */
    val createdAt: Long? = null
) {
    init { require(createdAt == null || createdAt >= 0) }
}

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val runId: RunId,
    val role: String,
    val blocks: List<MessageBlock>,
    val status: RunStatus,
    val error: String? = null,
    val protocolItems: List<String> = emptyList(),
    val historySegments: List<MessageHistorySegment> = emptyList(),
    /**
     * Stable accepted-message time in epoch milliseconds, not last update/completion time.
     * Callers creating a new accepted message record the clock once. Decoders and historical
     * messages must use null when the original time is unknown; never invent a read-time value.
     */
    val createdAt: Long? = null
) {
    init { require(createdAt == null || createdAt >= 0) }

    companion object {
        const val ASSISTANT_ROLE = "assistant"
        const val USER_ROLE = "user"
    }
}

/** Expands persisted owner segments while retaining the original history order. */
object MessageHistory {
    /**
     * Expands every non-empty history owner in [history]. Empty histories are legacy data and
     * are returned unchanged. User segments may refer to standalone user rows by
     * conversation/run/id; when such a row is present it is validated and omitted from the
     * output because the segment supplies its position in the owner's authoritative order.
     */
    fun expand(history: List<Message>): List<Message> {
        history.forEach(MessageImages::validate)
        if (history.none { it.historySegments.isNotEmpty() }) return history
        val supplements = validatedSupplements(history)
        val originalRows = history.associateBy { it.key() }

        return history.flatMap { row ->
            if (row.historySegments.isNotEmpty()) {
                row.historySegments.map { segment ->
                    row.copy(
                        id = segment.messageId,
                        role = segment.role,
                        blocks = segment.blocks,
                        protocolItems = segment.protocolItems,
                        historySegments = emptyList(),
                        // A supplement must never inherit its assistant owner's time.
                        createdAt = segment.createdAt ?: originalRows[
                            Key(row.conversationId, row.runId, segment.messageId)]?.createdAt,
                        status = if (segment.role == Message.USER_ROLE) RunStatus.COMPLETED else row.status,
                        error = if (segment.role == Message.USER_ROLE) null else row.error
                    )
                }
            } else if (row.key() in supplements.keys) {
                emptyList()
            } else {
                listOf(row)
            }
        }
    }

    fun validate(history: List<Message>) {
        history.forEach(MessageImages::validate)
        validatedSupplements(history)
    }

    fun validateOwner(owner: Message) {
        MessageImages.validate(owner)
        if (owner.historySegments.isEmpty()) return
        identity(owner.id.value); identity(owner.conversationId.value); identity(owner.runId.value)
        require(owner.role == Message.ASSISTANT_ROLE)
        val users = mutableSetOf<MessageId>()
        owner.historySegments.forEach { segment ->
            validateSegment(segment)
            if (segment.role == Message.ASSISTANT_ROLE) {
                require(segment.messageId == owner.id)
                require(segment.createdAt == null || owner.createdAt == null || segment.createdAt == owner.createdAt) {
                    "Assistant history time must match its owner"
                }
            } else require(segment.messageId != owner.id && users.add(segment.messageId))
        }
    }

    fun validateSegment(segment: MessageHistorySegment) {
        identity(segment.messageId.value)
        require(segment.role == Message.USER_ROLE || segment.role == Message.ASSISTANT_ROLE)
        MessageImages.validateSegment(segment)
        val calls = mutableSetOf<ToolCallId>()
        segment.blocks.forEach { block -> when (block) {
            is MessageBlock.Text -> identity(block.itemId)
            is MessageBlock.Image -> Unit // Metadata and owner checked above.
            is MessageBlock.Tool -> {
                require(segment.role == Message.ASSISTANT_ROLE)
                identity(block.invocation.id.value); identity(block.invocation.name)
                require(calls.add(block.invocation.id))
            }
        } }
    }

    private fun identity(value: String) { require(value.isNotBlank() && value.length <= 200) }

    private fun validatedSupplements(history: List<Message>): Map<Key, MessageHistorySegment> {
        val owners = history.filter { it.historySegments.isNotEmpty() }
        if (owners.isEmpty()) return emptyMap()
        owners.forEach(::validateOwner)

        val rowCounts = history.groupingBy { it.key() }.eachCount()
        val ownerKeys = mutableSetOf<Key>()
        owners.forEach { owner ->
            require(owner.role == Message.ASSISTANT_ROLE) {
                "History owner must be an assistant message"
            }
            require(rowCounts[owner.key()] == 1) {
                "History owner identities must have exactly one row"
            }
            require(ownerKeys.add(owner.key())) {
                "History owner identities must be unique"
            }
        }

        val supplements = linkedMapOf<Key, MessageHistorySegment>()
        owners.forEach { owner ->
            owner.historySegments.forEach { segment ->
                when (segment.role) {
                    Message.ASSISTANT_ROLE -> require(segment.messageId == owner.id) {
                        "Assistant history segment identity must match its owner"
                    }
                    Message.USER_ROLE -> {
                        val key = Key(owner.conversationId, owner.runId, segment.messageId)
                        require(key !in ownerKeys) {
                            "User history segment identity must not match an owner"
                        }
                        require(supplements.put(key, segment) == null) {
                            "User history segment identities must be unique"
                        }
                    }
                    else -> require(false) { "Unsupported history segment role" }
                }
            }
        }

        val rowsByKey = history.groupBy { it.key() }
        supplements.forEach { (key, segment) ->
            val rows = rowsByKey[key].orEmpty()
            require(rows.size <= 1) {
                "History supplement identity must have at most one standalone row"
            }
            if (rows.isNotEmpty()) {
                val row = rows.single()
                require(row.role == Message.USER_ROLE && row.historySegments.isEmpty()) {
                    "History supplement row must be a plain user message"
                }
                require(row.status == RunStatus.COMPLETED && row.error == null) {
                    "History supplement row must be completed without an error"
                }
                require(row.blocks == segment.blocks && row.protocolItems == segment.protocolItems) {
                    "History supplement row does not match its history segment"
                }
                require(segment.createdAt == null || row.createdAt == null || segment.createdAt == row.createdAt) {
                    "History supplement time does not match its row"
                }
            }
        }

        return supplements
    }

    private data class Key(val conversationId: ConversationId, val runId: RunId, val messageId: MessageId)

    private fun Message.key() = Key(conversationId, runId, id)
}
