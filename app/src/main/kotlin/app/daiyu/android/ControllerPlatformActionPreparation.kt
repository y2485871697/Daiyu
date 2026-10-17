package app.daiyu.android

import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.PlatformActionScope
import app.daiyu.android.platform.actions.PlatformAction
import app.daiyu.android.platform.actions.PlatformActionBackend
import java.util.UUID

/** In-memory single-use preview, not persisted settings and not model-issued permission. */
internal class ControllerPlatformActionPreparation(
    val conversation: Conversation?, val text: String, val action: PlatformAction,
    private val backend: PlatformActionBackend
) {
    val conversationId = conversation?.id ?: ConversationId(UUID.randomUUID().toString())
    val runId = RunId(UUID.randomUUID().toString())
    @Volatile var submitted = false
        private set
    @Volatile var cancelled = false
        private set
    var scope: PlatformActionScope? = null
        private set
    init { require(text.isNotBlank()); require(backend.unavailable(action.kind) == null) }
    val messageText: String get() = "$text\n\n本次只批准以下精确系统操作（普通应用身份，不得修改或自动重试）：\n${action.preview}\n必须调用 ${action.kind.toolName}，参数为 ${action.json}。Intent/媒体键仅提交不能证明效果；UNKNOWN 必须停止。"
    @Synchronized fun approve(): PlatformActionScope {
        check(!submitted && !cancelled) { "platform_approval_consumed" }
        submitted = true
        return PlatformActionScope(conversationId, runId, action,
            TaskAuthorization(runId.value, runId.value, setOf(action.effect)), backend).also { scope = it }
    }
    @Synchronized fun cancel() { cancelled = true; scope?.close() }
}
