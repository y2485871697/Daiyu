package app.daiyu.android.model

import app.daiyu.android.assistants.AssistantRunContext
import app.daiyu.android.domain.ModelPort

/** Uses already captured inputs only. Tool definitions/permissions remain the caller's policy. */
object AssistantModelFactory {
    fun create(snapshot: ProviderSnapshot, assistant: AssistantRunContext): ModelPort = when (snapshot.protocol) {
        ProviderProtocol.RESPONSES -> ResponsesModel(snapshot, assistant.systemPrompt)
        ProviderProtocol.MESSAGES -> MessagesModel(snapshot, assistant.systemPrompt)
        ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
    }
}
