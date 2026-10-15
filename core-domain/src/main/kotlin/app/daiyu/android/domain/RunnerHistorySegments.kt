package app.daiyu.android.domain

/** One cumulative protocol snapshot per session; later partial blocks remain a raw-free tail. */
internal class RunnerHistorySegments(initial: Message) {
    private var sealed = initial.historySegments.toList()
    private var blockStart = if (sealed.isEmpty()) 0 else initial.blocks.size
    private var protocol = if (sealed.isEmpty()) initial.protocolItems.toList() else emptyList()
    private var protocolBlockEnd = initial.blocks.size

    fun replaceSessionProtocol(message: Message, items: List<String>) {
        protocol = items.toList()
        protocolBlockEnd = message.blocks.size
    }
    fun project(message: Message): Message = message.copy(historySegments = sealed + active(message, false))

    fun sealSession(message: Message, users: List<Message>): Message {
        val segments = users.map { user ->
            require(user.role == Message.USER_ROLE && user.status == RunStatus.COMPLETED && user.error == null &&
                user.conversationId == message.conversationId && user.runId == message.runId &&
                user.id != message.id && user.historySegments.isEmpty())
            MessageHistorySegment(user.id, Message.USER_ROLE, user.blocks.toList(), user.protocolItems.toList())
        }
        sealed = sealed + active(message, true) + segments
        blockStart = message.blocks.size
        protocolBlockEnd = blockStart
        protocol = emptyList()
        return project(message)
    }
    private fun active(message: Message, completedToolsOnly: Boolean): List<MessageHistorySegment> {
        fun segment(blocks: List<MessageBlock>, items: List<String>): MessageHistorySegment? {
            val kept = if (completedToolsOnly) blocks.filter { it !is MessageBlock.Tool || it.result != null } else blocks
            if (kept.isEmpty() && items.isEmpty()) return null
            // Interrupted batches cannot replay protocol calls whose tools were never executed.
            val safeItems = if (completedToolsOnly && blocks.any { it is MessageBlock.Tool && it.result == null }) emptyList() else items
            return MessageHistorySegment(message.id, Message.ASSISTANT_ROLE, kept.toList(), safeItems.toList())
        }
        if (protocol.isEmpty()) return listOfNotNull(segment(message.blocks.drop(blockStart), emptyList()))
        check(protocolBlockEnd in blockStart..message.blocks.size)
        return listOfNotNull(segment(message.blocks.subList(blockStart, protocolBlockEnd), protocol),
            segment(message.blocks.drop(protocolBlockEnd), emptyList()))
    }
}
