package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.storage.MessageDisplayWindow

/** Worker-owned until the host accepts the exact history request; contains no action capabilities. */
internal fun prepareHistoryPresentations(
    conversation: String,
    messages: List<Message>,
    display: MessageDisplayWindow? = null,
    presentations: ToolPresentationCache = ToolPresentationCache(),
    checkActive: () -> Unit = {},
): ToolPresentationCache {
    val blocks = display?.rows?.flatMap { (_, row) ->
        if (row.segments.isEmpty()) row.blocks else row.segments.flatMap { it.blocks }
    } ?: MessageHistory.expand(messages).flatMap { it.blocks }
    presentations.scope(conversation)
    presentations.retainBlocks(blocks)
    blocks.forEach { block ->
        checkActive()
        if (block is MessageBlock.Tool) presentations.present(block)
    }
    checkActive()
    return presentations
}
