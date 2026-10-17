package app.daiyu.android.model

import app.daiyu.android.domain.ModelPort
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.model.sessiongateway.SessionGatewayContext

object ModelFactory {
    fun snapshot(settings: ModelSettings): ProviderSnapshot = ProviderSnapshot.capture(settings)

    fun create(settings: ModelSettings): ModelPort = create(snapshot(settings))

    fun create(snapshot: ProviderSnapshot): ModelPort {
        return when (snapshot.protocol) {
            ProviderProtocol.RESPONSES -> {
                ResponsesModel(snapshot)
            }
            ProviderProtocol.MESSAGES -> MessagesModel(snapshot)
            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> NativeCompletionModel(snapshot)
            ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
        }
    }

    /** Production path: the controller has already committed/resolved the conversation. */
    fun create(snapshot: ProviderSnapshot, session: SessionGatewayContext): ModelPort = when (snapshot.protocol) {
        ProviderProtocol.RESPONSES -> ResponsesModel(snapshot, sessionContext = session)
        ProviderProtocol.MESSAGES -> MessagesModel(snapshot, sessionContext = session)
        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> NativeCompletionModel(snapshot, sessionContext = session)
        ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
    }

    fun create(settings: ModelSettings, session: SessionGatewayContext): ModelPort = create(snapshot(settings), session)

    /** Explicit frozen native instructions for independently bound feature models.
     * Uses the same provider implementations; never treats a system prompt as user history. */
    fun create(snapshot: ProviderSnapshot, instructions: String): ModelPort = when (snapshot.protocol) {
        ProviderProtocol.RESPONSES -> ResponsesModel(snapshot, instructions)
        ProviderProtocol.MESSAGES -> MessagesModel(snapshot, instructions)
        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI -> NativeCompletionModel(snapshot, instructions)
        ProviderProtocol.UNKNOWN -> throw UnsupportedProviderException(snapshot.protocol.wireName)
    }

    fun create(settings: ModelSettings, protocol: ProviderProtocol): ModelPort =
        create(ProviderSnapshot.capture(settings, protocol))

    fun create(settings: ModelSettings, protocol: String): ModelPort {
        val selected = when (protocol.trim().lowercase()) {
            ProviderProtocol.RESPONSES.wireName -> ProviderProtocol.RESPONSES
            ProviderProtocol.MESSAGES.wireName -> ProviderProtocol.MESSAGES
            ProviderProtocol.CHAT_COMPLETIONS.wireName -> ProviderProtocol.CHAT_COMPLETIONS
            ProviderProtocol.GEMINI.wireName -> ProviderProtocol.GEMINI
            else -> throw UnsupportedProviderException(protocol)
        }
        return create(settings, selected)
    }
}
