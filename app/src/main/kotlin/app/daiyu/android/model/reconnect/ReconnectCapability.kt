package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelSession
import app.daiyu.android.domain.ResumableModelSession
import app.daiyu.android.model.ProviderProtocol

/** Capability is a transport contract, NOT a protocol name, reconnect preference, or SSE id. */
enum class ReconnectCapability(val supported: Boolean, val userMessage: String) {
    TRANSPORT_RESUME(true, "原会话支持只读取尚未确认事件的恢复；不会重新提交请求或工具结果"),
    RESPONSES_UNSUPPORTED(false, "当前 Responses 接入不支持无重发恢复；未重新 POST 模型请求或工具结果"),
    MESSAGES_UNSUPPORTED(false, "当前 Messages 接入不支持无重发恢复；未重新 POST 模型请求或工具结果"),
    CHAT_COMPLETIONS_UNSUPPORTED(false, "当前 Chat Completions 接入不支持无重发恢复；未重新 POST 模型请求或工具结果"),
    GEMINI_UNSUPPORTED(false, "当前 Gemini 接入不支持无重发恢复；未重新 POST 模型请求或工具结果"),
    UNKNOWN_TRANSPORT(false, "当前模型恢复能力未确认；未重新发送请求"),
    NOT_RESUMABLE(false, "当前模型会话未实现无重发恢复；未重新发送请求"),
    SESSION_UNAVAILABLE(false, "原模型会话当前不能安全恢复；未新建会话或重新发送请求"),
}

class ReconnectUnavailableException(val capability: ReconnectCapability) :
    IllegalStateException(capability.userMessage)

object ReconnectCapabilities {
    /** Preflight for the production transports in this source tree. No open/network call.
     * Ordinary ResponsesModel.Session and MessagesModel.Session only implement ModelSession.
     * Neither previous_response_id nor a new POST can satisfy ResumableModelSession's contract. */
    fun forProtocol(protocol: ProviderProtocol): ReconnectCapability = when (protocol) {
        ProviderProtocol.RESPONSES -> ReconnectCapability.RESPONSES_UNSUPPORTED
        ProviderProtocol.MESSAGES -> ReconnectCapability.MESSAGES_UNSUPPORTED
        ProviderProtocol.CHAT_COMPLETIONS -> ReconnectCapability.CHAT_COMPLETIONS_UNSUPPORTED
        ProviderProtocol.GEMINI -> ReconnectCapability.GEMINI_UNSUPPORTED
        ProviderProtocol.UNKNOWN -> ReconnectCapability.UNKNOWN_TRANSPORT
    }

    /** Inspect the exact retained session without opening/replacing it. Runner MUST still check
     * canResume at the failure boundary, as it already does: an earlier true is not a permit. */
    fun forSession(session: ModelSession): ReconnectCapability {
        val resumable = session as? ResumableModelSession ?: return ReconnectCapability.NOT_RESUMABLE
        return try {
            if (resumable.canResume) ReconnectCapability.TRANSPORT_RESUME
            else ReconnectCapability.SESSION_UNAVAILABLE
        } catch (_: Exception) {
            // Never expose provider exception bodies/credentials as a capability diagnostic.
            ReconnectCapability.SESSION_UNAVAILABLE
        }
    }
}
