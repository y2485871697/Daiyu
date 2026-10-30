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
