package app.daiyu.android.timeline

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory

/** Display coordinates only: UTF-16 text/thought units and one unit per non-text slot. */
data class ReconnectPosition(val offset: Long) {
    init { require(offset >= 0) }

    companion object {
        fun size(message: Message): Long = message.blocks.sumOf {
            if (it is MessageBlock.Text) it.text.length.toLong() else 1L
        } + message.reportedReasoning.sumOf { it.text.length.toLong() }

        fun atEnd(messages: List<Message>, anchor: TimelineAnchor): ReconnectPosition = ReconnectPosition(
            MessageHistory.expand(messages).filter {
                it.conversationId.value == anchor.conversationId && it.runId.value == anchor.runId &&
                    it.id.value == anchor.messageId
            }.sumOf(::size))
    }
}

/** A status update cannot retarget an existing display event, including legacy events. */
internal fun ChatTimelineEntry.Reconnect.keepPositionOf(prior: ChatTimelineEntry.Reconnect) =
    copy(anchor = prior.anchor, created = prior.created, position = prior.position)
