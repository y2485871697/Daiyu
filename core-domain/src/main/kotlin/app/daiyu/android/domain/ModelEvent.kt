package app.daiyu.android.domain

sealed interface ModelEvent {
    data class TextDelta(val itemId: String, val text: String) : ModelEvent
    data class TextFinal(val itemId: String, val text: String) : ModelEvent
    data class ReasoningDelta(val itemId: String, val text: String) : ModelEvent
    data class ReasoningFinal(val itemId: String, val text: String) : ModelEvent
    data class ProtocolOutput(val items: List<String>) : ModelEvent
    data class ToolReady(val invocation: ToolInvocation) : ModelEvent
}
data class ToolOutput(val callId: ToolCallId, val output: String)
interface ModelSession {
    /** Blocking, ordered delivery. Return only after a successful protocol terminal event. */
    fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit)
    fun cancel()
}
fun interface ModelPort { fun open(history: List<Message>): ModelSession }
fun interface BatteryTool { fun execute(invocation: ToolInvocation): String }
interface ConversationStore {
    fun save(message: Message)
}
