package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelSession
import app.daiyu.android.domain.ResumableModelSession
import app.daiyu.android.model.ProviderProtocol

/** Capability is a transport contract, NOT a protocol name, reconnect preference, or SSE id. */
enum class ReconnectCapability(val supported: Boolean, val userMessage: String) {
    REQUEST_RETRY(true, "支持重试当前模型请求；已执行的本地工具不会再次运行"),
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
    /** Production transports can replay the current request without re-executing local tools. */
    fun forProtocol(protocol: ProviderProtocol): ReconnectCapability = when (protocol) {
        ProviderProtocol.RESPONSES -> ReconnectCapability.REQUEST_RETRY
        ProviderProtocol.MESSAGES -> ReconnectCapability.REQUEST_RETRY
        ProviderProtocol.CHAT_COMPLETIONS -> ReconnectCapability.REQUEST_RETRY
        ProviderProtocol.GEMINI -> ReconnectCapability.REQUEST_RETRY
        ProviderProtocol.UNKNOWN -> ReconnectCapability.UNKNOWN_TRANSPORT
    }

    /** Inspect the exact retained session without opening/replacing it. Runner MUST still check
     * canResume at the failure boundary, as it already does: an earlier true is not a permit. */
    fun forSession(session: ModelSession): ReconnectCapability {
        val resumable = session as? ResumableModelSession ?: return ReconnectCapability.NOT_RESUMABLE
        return try {
            if (!resumable.canResume) ReconnectCapability.SESSION_UNAVAILABLE
            else if (session is app.daiyu.android.domain.RequestRetryModelSession && session.retriesCurrentRequest)
                ReconnectCapability.REQUEST_RETRY
            else ReconnectCapability.TRANSPORT_RESUME
        } catch (_: Exception) {
            // Never expose provider exception bodies/credentials as a capability diagnostic.
            ReconnectCapability.SESSION_UNAVAILABLE
        }
    }
}
