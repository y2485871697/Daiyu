package app.daiyu.android.model

import app.daiyu.android.assistants.AssistantRunContext
import app.daiyu.android.domain.ModelPort
import app.daiyu.android.model.sessiongateway.SessionGatewayContext

/** Uses already captured inputs only. Tool definitions/permissions remain the caller's policy. */
object AssistantModelFactory {
    fun create(snapshot: ProviderSnapshot, assistant: AssistantRunContext): ModelPort = when (snapshot.protocol) {
        ProviderProtocol.RESPONSES -> ResponsesModel(snapshot, assistant.systemPrompt)
        ProviderProtocol.MESSAGES -> MessagesModel(snapshot, assistant.systemPrompt)
        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> NativeCompletionModel(snapshot, assistant.systemPrompt)
        ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
    }

    fun create(snapshot: ProviderSnapshot, assistant: AssistantRunContext, session: SessionGatewayContext): ModelPort =
        when (snapshot.protocol) {
            ProviderProtocol.RESPONSES -> ResponsesModel(snapshot, assistant.systemPrompt, sessionContext = session)
            ProviderProtocol.MESSAGES -> MessagesModel(snapshot, assistant.systemPrompt, sessionContext = session)
            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> NativeCompletionModel(snapshot, assistant.systemPrompt, sessionContext = session)
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
        }
}
