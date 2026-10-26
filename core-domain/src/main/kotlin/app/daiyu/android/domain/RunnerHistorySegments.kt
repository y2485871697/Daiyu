package app.daiyu.android.domain

/** One cumulative protocol snapshot per session; later partial blocks remain a raw-free tail. */
internal class RunnerHistorySegments(private val initial: Message, private val nanoTime: () -> Long = System::nanoTime) {
    private var sealed = initial.historySegments.toList()
    private var blockStart = if (sealed.isEmpty()) 0 else initial.blocks.size
    private var protocol = if (sealed.isEmpty()) initial.protocolItems.toList() else emptyList()
    private var protocolBlockEnd = initial.blocks.size
    private var protocolReasoning = emptySet<String>()
    private val reasoning = linkedMapOf<String, ReportedReasoning>()

    fun acceptReasoning(itemId: String, text: String, final: Boolean, blockOffset: Int? = null) {
        val previous = reasoning[itemId]
        val nextText = if (final) text else previous?.text.orEmpty() + text
        ReportedReasoning(itemId, nextText)
        check(reasoning.size < 1024 || itemId in reasoning)
        check(reasoning.filterKeys { it != itemId }.values.sumOf { it.text.length.toLong() } + nextText.length <= 2_000_000)
        val now = nanoTime()
        reasoning.replaceAll { id, value -> if (id == itemId) value else value.frozen(now) }
        val next = ReportedReasoning(itemId, nextText,
            elapsedMillis = previous?.elapsedMillis ?: if (final) null else 0L,
            activeSinceNanos = previous?.activeSinceNanos ?: if (final) null else now,
            blockOffset = if (previous == null) blockOffset else previous.blockOffset)
        reasoning[itemId] = if (final) next.frozen(now) else next
    }

    fun finishReasoning() {
        val now = nanoTime()
        reasoning.replaceAll { _, value -> value.frozen(now) }
    }

    /** A rejected transport batch must not mutate the accepted timers or their anchors. */
    fun fork(): RunnerHistorySegments = RunnerHistorySegments(initial, nanoTime).also {
        it.sealed = sealed
        it.blockStart = blockStart
        it.protocol = protocol
        it.protocolBlockEnd = protocolBlockEnd
        it.protocolReasoning = protocolReasoning
        it.reasoning.putAll(reasoning)
    }

    fun replaceSessionProtocol(message: Message, items: List<String>) {
        protocol = items.toList()
        protocolBlockEnd = message.blocks.size
        protocolReasoning = reasoning.keys.toSet()
    }
    fun project(message: Message): Message = message.copy(historySegments = sealed + active(message, false))

    fun sealSession(message: Message, users: List<Message>): Message {
        finishReasoning()
        val segments = users.map { user ->
            require(user.role == Message.USER_ROLE && user.status == RunStatus.COMPLETED && user.error == null &&
                user.conversationId == message.conversationId && user.runId == message.runId &&
                user.id != message.id && user.historySegments.isEmpty())
            MessageHistorySegment(user.id, Message.USER_ROLE, user.blocks.toList(), user.protocolItems.toList(),
                createdAt = user.createdAt)
        }
        sealed = sealed + active(message, true) + segments
        blockStart = message.blocks.size
        protocolBlockEnd = blockStart
        protocol = emptyList()
        protocolReasoning = emptySet()
        reasoning.clear()
        return project(message)
    }
    private fun active(message: Message, completedToolsOnly: Boolean): List<MessageHistorySegment> {
        val assigned = mutableSetOf<String>()
        fun segment(start: Int, end: Int, items: List<String>, last: Boolean): MessageHistorySegment? {
            val blocks = message.blocks.subList(start, end)
            fun keep(block: MessageBlock) = !completedToolsOnly || block !is MessageBlock.Tool || block.result != null
            val reported = reasoning.values.filter { value ->
                val offset = value.blockOffset
                value.itemId !in assigned && (if (offset == null) start == blockStart
                else offset >= start && (offset < end || offset == end && (last || value.itemId in protocolReasoning)))
            }.map { value ->
                value.blockOffset?.let { offset ->
                    value.copy(blockOffset = blocks.take(offset - start).count(::keep))
                } ?: value
            }
            assigned += reported.map { it.itemId }
            val kept = blocks.filter(::keep)
            if (kept.isEmpty() && items.isEmpty() && reported.isEmpty()) return null
            // Interrupted batches cannot replay protocol calls whose tools were never executed.
            val safeItems = if (completedToolsOnly && blocks.any { it is MessageBlock.Tool && it.result == null }) emptyList() else items
            return MessageHistorySegment(message.id, Message.ASSISTANT_ROLE, kept.toList(), safeItems.toList(),
                createdAt = message.createdAt, reportedReasoning = reported)
        }
        check(reasoning.values.all { it.blockOffset == null || it.blockOffset in blockStart..message.blocks.size })
        if (protocol.isEmpty()) return listOfNotNull(segment(blockStart, message.blocks.size, emptyList(), true))
        check(protocolBlockEnd in blockStart..message.blocks.size)
        return listOfNotNull(segment(blockStart, protocolBlockEnd, protocol, false),
            segment(protocolBlockEnd, message.blocks.size, emptyList(), true))
    }
}
