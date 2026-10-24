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

/** Public text actually reported by a provider (summary or native thinking), not hidden reasoning.
 * Signatures/encrypted/redacted data belong only to authoritative protocolItems. */
data class ReportedReasoning(
    val itemId: String,
    val text: String,
    val elapsedMillis: Long? = null,
    /** Process-local monotonic anchor; codecs persist only the sampled elapsed duration. */
    val activeSinceNanos: Long? = null
) {
    init {
        require(itemId.isNotBlank() && itemId.length <= 512 && text.length <= 2_000_000)
        require(elapsedMillis == null || elapsedMillis >= 0)
        require(activeSinceNanos == null || elapsedMillis != null)
    }

    fun elapsedAt(nowNanos: Long = System.nanoTime()): Long? = elapsedMillis?.let { elapsed ->
        val active = activeSinceNanos?.let { ((nowNanos - it).coerceAtLeast(0) / 1_000_000) } ?: 0
        elapsed + active.coerceAtMost(Long.MAX_VALUE - elapsed)
    }

    fun frozen(nowNanos: Long = System.nanoTime()): ReportedReasoning =
        if (activeSinceNanos == null) this else copy(elapsedMillis = elapsedAt(nowNanos), activeSinceNanos = null)
}

/** One ordered item in an assistant owner's persisted history. */
data class MessageHistorySegment(
    val messageId: MessageId,
    val role: String,
    val blocks: List<MessageBlock>,
    val protocolItems: List<String> = emptyList(),
    /** Epoch milliseconds recorded when this message was accepted; null means unknown. */
    val createdAt: Long? = null,
    val reportedReasoning: List<ReportedReasoning> = emptyList()
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
    val createdAt: Long? = null,
    /** Expanded-history display projection only. Durable owners keep this in historySegments;
     * model request encoders must never turn this into user/assistant body text. */
    val reportedReasoning: List<ReportedReasoning> = emptyList()
) {
    init { require(createdAt == null || createdAt >= 0) }

    companion object {
        const val ASSISTANT_ROLE = "assistant"
        const val USER_ROLE = "user"
    }
}

/** Checkpoints carry stable durations, never a process-local live clock. */
internal fun Message.frozenReasoning(nowNanos: Long = System.nanoTime()): Message {
    if (reportedReasoning.none { it.activeSinceNanos != null } &&
        historySegments.all { segment -> segment.reportedReasoning.none { it.activeSinceNanos != null } }) return this
    return copy(
        reportedReasoning = reportedReasoning.map { it.frozen(nowNanos) },
        historySegments = historySegments.map { segment ->
            if (segment.reportedReasoning.none { it.activeSinceNanos != null }) segment else
                segment.copy(reportedReasoning = segment.reportedReasoning.map { it.frozen(nowNanos) })
        }
    )
}

/** Expands persisted owner segments while retaining the original history order. */
object MessageHistory {
    /**
     * Expands every non-empty history owner in [history]. Empty histories are legacy data and
     * are returned unchanged. User segments may refer to standalone user rows by
     * conversation/run/id; when such a row is present it is validated and omitted from the
     * output because the segment supplies its position in the owner's authoritative order.
     */
    data class ExpandedEntry(val message: Message, val sourcePhysicalIndex: Int)

    fun expand(history: List<Message>): List<Message> =
        if (history.none { it.historySegments.isNotEmpty() }) {
            history.forEach(MessageImages::validate)
            history
        } else expandWithSources(history).map { it.message }

    /** The source is the durable owner, never a duplicate standalone supplement. */
    fun expandWithSources(history: List<Message>): List<ExpandedEntry> {
        history.forEach(MessageImages::validate)
        if (history.none { it.historySegments.isNotEmpty() }) return history.mapIndexed { index, message -> ExpandedEntry(message, index) }
        val supplements = validatedSupplements(history)
        val originalRows = history.associateBy { it.key() }

        return history.flatMapIndexed { source, row ->
            if (row.historySegments.isNotEmpty()) {
                row.historySegments.map { segment ->
                    ExpandedEntry(row.copy(
                        id = segment.messageId,
                        role = segment.role,
                        blocks = segment.blocks,
                        protocolItems = segment.protocolItems,
                        reportedReasoning = segment.reportedReasoning,
                        historySegments = emptyList(),
                        // A supplement must never inherit its assistant owner's time.
                        createdAt = segment.createdAt ?: originalRows[
                            Key(row.conversationId, row.runId, segment.messageId)]?.createdAt,
                        status = if (segment.role == Message.USER_ROLE) RunStatus.COMPLETED else row.status,
                        error = if (segment.role == Message.USER_ROLE) null else row.error
                    ), source)
                }
            } else if (row.key() in supplements.keys) {
                emptyList()
            } else {
                listOf(ExpandedEntry(row, source))
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
        require(segment.reportedReasoning.isEmpty() || segment.role == Message.ASSISTANT_ROLE)
        require(segment.reportedReasoning.size <= 1024 && segment.reportedReasoning.map { it.itemId }.distinct().size == segment.reportedReasoning.size)
        require(segment.reportedReasoning.sumOf { it.text.length.toLong() } <= 2_000_000)
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
