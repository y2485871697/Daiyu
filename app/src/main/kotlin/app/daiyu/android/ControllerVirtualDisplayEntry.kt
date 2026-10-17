package app.daiyu.android

import android.content.Context
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*

/** Controller-owned immutable preparation. No owner, lease, process or authorization is created
 * by opening this entry. Mutable fields are protected by the real Controller's monitor.
 * The entry survives cancellation/UNKNOWN; it is not an Activity-owned resource. */
internal class ControllerVirtualDisplayEntry(
    val conversation: Conversation?,
    val editor: ChatController.TextSendOwner,
    val text: String,
    writes: List<R08VirtualDisplayWrite>,
    reads: List<R08VirtualDisplayRead>
) {
    val conversationId = conversation?.id ?: ConversationId(java.util.UUID.randomUUID().toString())
    val runId = RunId(java.util.UUID.randomUUID().toString())
    val assistantId = MessageId(java.util.UUID.randomUUID().toString())
    val writes = writes.toList()
    val reads = reads.toList()
    lateinit var taskSurface: ControllerTaskSurface
    var submitted = false
    var textTicket: ChatController.TextSendTicket? = null
    var accepted = false
    var persistenceUnknown = false
    var revoked = false
    var factoryEntered = false
    var owner: RunExecutionOwner? = null
    var capabilities: SessionCapabilities? = null
    var authorization: TaskAuthorization? = null
    var session: ControllerVirtualDisplaySession? = null
    var catalog: RunCatalogTools? = null
    init {
        require(text.isNotBlank() && editor.conversationId == conversation?.id)
        require(editor.editorGeneration >= 0 && editor.draftRevision >= 0)
        require(writes.size <= 64 && reads.size <= 64 && (writes.isNotEmpty() || reads.isNotEmpty()))
    }
}

/** Narrow test seam. Production always uses the durable runtime, not Session.production. */
internal fun interface ControllerVirtualDisplayFactory {
    fun create(context: Context, owner: RunExecutionOwner, capabilities: SessionCapabilities,
               writes: List<R08VirtualDisplayWrite>, reads: List<R08VirtualDisplayRead>,
               parent: ControllerVirtualDisplayRuntime.Parent,
               retention: ControllerVirtualDisplayRuntime.Retention): ControllerVirtualDisplaySession

    companion object {
        val REAL = ControllerVirtualDisplayFactory { context, owner, caps, writes, reads, parent, retention ->
            ControllerVirtualDisplayRuntime.create(context, owner, caps, writes, reads, parent, retention)
        }
    }
}
