package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision

/** Recheck an open edit after its existing assistant finishes settling, without admitting new history. */
internal class MessageEditBaseline private constructor(private val rows: List<Row>, val settled: Boolean) {
    private data class Row(val id: MessageId, val run: RunId, val role: String,
        val revision: String, val tailRevision: String?)

    fun canRefresh(next: MessageEditBaseline?, target: MessageActionTarget): Boolean {
        if (next == null || !settled || !next.settled || rows.size != next.rows.size ||
            target.ownerId != target.messageId || target.segmentIndex != 0) return false
        val source = rows.indexOfFirst { it.id == target.ownerId && it.run == target.runId && it.role == Message.USER_ROLE }
        if (source < 0) return false
        return rows.indices.all { index ->
            val before = rows[index]
            val after = next.rows[index]
            before.id == after.id && before.run == after.run && before.role == after.role &&
                (before.revision == after.revision || index > source && before.run == target.runId &&
                    before.role == Message.ASSISTANT_ROLE && before.tailRevision != null &&
                    before.tailRevision == after.tailRevision)
        }
    }

    companion object {
        fun capture(history: List<Message>, revisions: Map<String, String>): MessageEditBaseline =
            MessageEditBaseline(history.map { row ->
                val tail = if (row.role != Message.ASSISTANT_ROLE) null else ContextRevision.digest(listOf(row.copy(
                    blocks = emptyList(), protocolItems = emptyList(), reportedReasoning = emptyList(),
                    status = RunStatus.COMPLETED, error = null,
                    historySegments = row.historySegments.map { segment ->
                        if (segment.role != Message.ASSISTANT_ROLE) segment else segment.copy(
                            blocks = emptyList(), protocolItems = emptyList(), reportedReasoning = emptyList())
                    })))
                Row(row.id, row.runId, row.role, revisions.getValue(row.id.value), tail)
            }, history.none { it.status == RunStatus.RUNNING })
    }
}
