package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.RunStatus

/** Text/tool/reasoning updates already arrive through the live owner, not a DB reload. */
internal class StreamingHistoryRefresh {
    private data class Key(val conversation: String, val run: String, val message: String,
        val data: Long, val editor: Long, val userSegments: List<String>)
    private var previous: Key? = null

    fun invalidate() { previous = null }

    fun needsRead(active: Message?, loaded: Boolean, data: Long, editor: Long, force: Boolean): Boolean {
        val key = active?.takeIf { it.status == RunStatus.RUNNING && it.role == Message.ASSISTANT_ROLE }?.let {
            Key(it.conversationId.value, it.runId.value, it.id.value, data, editor,
                it.historySegments.filter { segment -> segment.role == Message.USER_ROLE }.map { segment -> segment.messageId.value })
        }
        val read = force || !loaded || key == null || key != previous
        previous = key
        return read
    }
}

/** An IO snapshot may lag only the exact live row; navigation and every other row stay fenced. */
internal fun canOverlayLiveHistory(
    before: List<Pair<Long, Message>>,
    after: List<Pair<Long, Message>>,
    active: Message?,
): Boolean {
    if (active == null || before.size != after.size) return false
    fun isOwner(message: Message) = message.id == active.id && message.runId == active.runId &&
        message.conversationId == active.conversationId
    return before.indices.all { index ->
        val old = before[index]
        val current = after[index]
        old.first == current.first && (old.second == current.second || isOwner(old.second) && isOwner(current.second))
    }
}
