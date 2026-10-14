package app.daiyu.android.domain

enum class RunStatus { RUNNING, COMPLETED, STOPPED, FAILED, INTERRUPTED }
data class Conversation(val id: ConversationId, val title: String, val draft: String, val updatedAt: Long, val folder: String = "", val pinned: Boolean = false)
data class ToolInvocation(val id: ToolCallId, val name: String, val arguments: String)
sealed interface MessageBlock {
    data class Text(val itemId: String, val text: String) : MessageBlock
    data class Tool(val invocation: ToolInvocation, val result: String? = null) : MessageBlock
}
data class Message(val id: MessageId, val conversationId: ConversationId, val runId: RunId,
    val role: String, val blocks: List<MessageBlock>, val status: RunStatus, val error: String? = null, val protocolItems: List<String> = emptyList())
