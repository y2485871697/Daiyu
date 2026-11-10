package app.daiyu.android

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.storage.MessageActionSnapshot

/** UI-owned, single-entry content projection, never an action capability or execution authority.
 *
 * Cached entries detach ALL list structure, including nested segment lists: Message's val
 * properties do not prevent callers mutating a backing MutableList in place. Leaves (strings,
 * blocks, image references and reasoning values) are immutable and can be shared. Equality
 * checks values without serializing long text to UTF-8 or hashing it on an unchanged refresh.
 * Storage/work is bounded by one rendered history, not a map of conversations or revisions.
 * Fresh callback admission and action execution must still perform their own content guards.
 */
internal class MessageActionRenderedRevision(
    private val matcher: (MessageActionSnapshot, List<Message>) -> Boolean = { snapshot, rows -> snapshot.matchesRendered(rows) },
    private val digest: (List<Message>) -> String = ContextRevision::digest,
) {
    private class Entry(val owner: ConversationId, val rows: List<Message>) {
        var revision: String? = null
        var match: Match? = null
    }
    private data class Match(val snapshot: MessageActionSnapshot, val revisions: Map<String, String>, val result: Boolean)
    private var entry: Entry? = null

    fun revision(owner: ConversationId, rows: List<Message>): String {
        val current = projection(owner, rows)
        return current.revision ?: digest(current.rows).also { current.revision = it }
    }

    fun matches(snapshot: MessageActionSnapshot, rows: List<Message>): Boolean {
        val current = projection(snapshot.conversationId, rows)
        val revisions = snapshot.rowRevisions.toMap()
        current.match?.let {
            if (it.snapshot === snapshot && it.revisions == revisions) return it.result
        }
        return matcher(snapshot, current.rows).also { current.match = Match(snapshot, revisions, it) }
    }

    private fun projection(owner: ConversationId, rows: List<Message>): Entry {
        entry?.let { if (it.owner == owner && it.rows == rows) return it }
        val frozen = rows.map { message ->
            message.copy(
                blocks = message.blocks.toList(),
                protocolItems = message.protocolItems.toList(),
                reportedReasoning = message.reportedReasoning.toList(),
                historySegments = message.historySegments.map { segment ->
                    segment.copy(
                        blocks = segment.blocks.toList(),
                        protocolItems = segment.protocolItems.toList(),
                        reportedReasoning = segment.reportedReasoning.toList(),
                    )
                },
            )
        }
        return Entry(owner, frozen).also { entry = it }
    }
}
