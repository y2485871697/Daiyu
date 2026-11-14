package app.daiyu.android.domain

/** A reported unknown outcome is not a successful execution receipt. ToolRecord/ledger stay
 * UNKNOWN; this payload only closes the model protocol and explicitly forbids replay. */
object ModelHistory {
    const val UNKNOWN_TOOL_RESULT = "{\"ok\":false,\"outcome\":\"UNKNOWN\",\"replay_allowed\":false,\"message\":\"工具结果未知；禁止重放，请仅使用只读方式核实\"}"

    /** 旧终态的 null 结果按未知处理，活动调用保持待定。
     * 保留物理所有者和分段供授权及 CAS 使用，仅丢弃受影响分段的协议快照，再从块重建单一结果。
     * 原生 JSON 和持久运行结果保持不变，此处 COMPLETED 表示历史可用。
     */
    fun project(history: List<Message>): List<Message> {
        MessageHistory.validate(history)
        return history.map { row ->
            if (row.role != Message.ASSISTANT_ROLE || row.status == RunStatus.RUNNING) return@map row
            fun unknown(block: MessageBlock) = block is MessageBlock.Tool &&
                (block.result == null || block.result == UNKNOWN_TOOL_RESULT)
            val affected = row.blocks.any(::unknown) || row.historySegments.any { it.blocks.any(::unknown) }
            if (!affected) return@map row
            fun close(blocks: List<MessageBlock>) = blocks.map { block ->
                if (block is MessageBlock.Tool && block.result == null) block.copy(result = UNKNOWN_TOOL_RESULT) else block
            }
            row.copy(
                blocks = close(row.blocks),
                status = RunStatus.COMPLETED,
                protocolItems = if (row.blocks.any(::unknown)) emptyList() else row.protocolItems,
                historySegments = row.historySegments.map { segment ->
                    if (segment.role != Message.ASSISTANT_ROLE || !segment.blocks.any(::unknown)) segment
                    else segment.copy(blocks = close(segment.blocks), protocolItems = emptyList())
                }
            )
        }
    }

    /** Validate and return the model view without modifying originals. */
    fun requireResolvedTools(history: List<Message>): List<Message> {
        val projected = project(history)
        require(MessageHistory.expand(projected).none { row ->
            row.blocks.any { it is MessageBlock.Tool && it.result == null }
        }) { "保留的历史有未确认工具结果；请先核实结果" }
        return projected
    }
}
