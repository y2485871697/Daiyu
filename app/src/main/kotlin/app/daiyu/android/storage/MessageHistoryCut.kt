package app.daiyu.android.storage

import app.daiyu.android.domain.*

/** Cut authoritative logical history, then retain its physical owners and matching supplements. */
internal object MessageHistoryCut {
    fun index(history: List<Message>, target: MessageActionTarget): Int {
        val logical = MessageHistory.expand(history)
        val matches = logical.indices.filter { logical[it].id == target.messageId && logical[it].role == target.role }
        return matches.getOrNull(target.segmentIndex) ?: error("消息段已变化；请刷新")
    }

    fun prefix(history: List<Message>, count: Int): List<Message> {
        val expanded = MessageHistory.expandWithSources(history)
        require(count in 0..expanded.size)
        val selected = expanded.take(count)
        val identities = selected.map { it.message.id }.toSet()
        val result = history.mapIndexedNotNull { index, row ->
            if (row.historySegments.isEmpty()) row.takeIf { row.id in identities }
            else {
                val size = selected.count { it.sourcePhysicalIndex == index }
                if (size == 0) null else if (size == row.historySegments.size) row else {
                    val segments = row.historySegments.take(size)
                    val assistant = segments.filter { it.role == Message.ASSISTANT_ROLE }
                    row.copy(blocks = assistant.flatMap { it.blocks }, protocolItems = assistant.flatMap { it.protocolItems },
                        historySegments = segments)
                }
            }
        }
        check(MessageHistory.expand(result) == expanded.take(count).map { it.message }) { "消息截点未闭合" }
        return result
    }
}

/** Immutable prospective edit. Preparation reads the original; only accepted send commits the cut. */
internal data class MessageRevision(val target: MessageActionTarget, val original: List<Message>,
    val prefix: List<Message>, val user: Message)
