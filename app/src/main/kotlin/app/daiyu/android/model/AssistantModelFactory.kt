package app.daiyu.android.model

import app.daiyu.android.assistants.AssistantRunContext
import app.daiyu.android.domain.ModelPort
import app.daiyu.android.model.sessiongateway.SessionGatewayContext

/** 使用已捕获的输入；工具定义和权限由调用方提供。 */
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
