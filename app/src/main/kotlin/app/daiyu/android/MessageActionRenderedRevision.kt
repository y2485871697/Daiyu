package app.daiyu.android

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.context.ContextRevision

/** UI-owned, single-entry content projection, never an action capability or execution authority.
 *
 * Every lookup detaches ALL list structure, including nested segment lists: Message's val
 * properties do not prevent callers mutating a backing MutableList in place. Leaves (strings,
 * blocks, image references and reasoning values) are immutable and can be shared. Equality
 * checks values without serializing long text to UTF-8 or hashing it on an unchanged refresh.
 * Storage/work is bounded by one rendered history, not a map of conversations or revisions.
 * Fresh callback admission and action execution must still perform their own content guards.
 */
internal class MessageActionRenderedRevision(
    private val digest: (List<Message>) -> String = ContextRevision::digest,
) {
    private data class Entry(val owner: ConversationId, val rows: List<Message>, val revision: String)
    private var entry: Entry? = null

    fun revision(owner: ConversationId, rows: List<Message>): String {
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
        entry?.let { if (it.owner == owner && it.rows == frozen) return it.revision }
        return digest(frozen).also { entry = Entry(owner, frozen, it) }
    }
}
