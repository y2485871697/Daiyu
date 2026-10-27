package app.daiyu.android

import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.PlatformActionScope
import app.daiyu.android.platform.actions.PlatformAction
import app.daiyu.android.platform.actions.PlatformActionBackend
import app.daiyu.android.tasksurface.*
import app.daiyu.android.domain.tools.ExecutionReceipt
import java.util.UUID

/** In-memory single-use preview, not persisted settings and not model-issued permission. */
internal class ControllerPlatformActionPreparation(
    val conversation: Conversation?, val text: String, val action: PlatformAction,
    private val backend: PlatformActionBackend,
    private val surfaceStore: (() -> TaskSurfaceStore)? = null,
    private val ownPackage: String = "",
    private val clockTarget: () -> String? = { null },
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
    val requiresLocation = action.kind == app.daiyu.android.platform.actions.PlatformActionKind.ALARM ||
        action.kind == app.daiyu.android.platform.actions.PlatformActionKind.TIMER
    @Volatile var location: ControllerTaskSurface? = null
        private set
    fun freezeLocationOnWorker(): TaskSurfaceDecision {
        val route = synchronized(this) {
            if (cancelled || submitted) return TaskSurfaceDecision.NotExecuted("PLATFORM_PREPARATION_REVOKED", "原系统操作准备已失效")
            location ?: run {
                val store = surfaceStore?.invoke() ?: return TaskSurfaceDecision.NotExecuted("TASK_SURFACE_SETTINGS_UNAVAILABLE", "执行位置设置不可用；未执行")
                val target = clockTarget()?.takeIf { it.isNotBlank() && it != ownPackage }
                    ?: return TaskSurfaceDecision.NotExecuted("CLOCK_TARGET_UNAVAILABLE", "没有可解析的外部时钟；未执行")
                ControllerTaskSurface(this, store, ownPackage, target, false, admission = { !cancelled })
                    .also { location = it }
            }
        }
        return route.freezeOnWorker()
    }
    private val routedBackend = object : PlatformActionBackend {
        override fun unavailable(kind: app.daiyu.android.platform.actions.PlatformActionKind) = backend.unavailable(kind)
        override fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
            if (requiresLocation && location?.ready(TaskSurface.FOREGROUND) == null)
                return ExecutionReceipt.NotExecuted("TASK_SURFACE_CLOCK_NOT_FOREGROUND")
            return backend.execute(action, cancelled)
        }
    }
    val messageText: String get() = "$text\n\n本次只批准以下精确系统操作（普通应用身份，不得修改或自动重试）：\n${action.preview}\n必须调用 ${action.kind.toolName}，参数为 ${action.json}。Intent/媒体键仅提交不能证明效果；UNKNOWN 必须停止。"
    /** Issue the original exact token at task submission, without a second capability question.
     * Location and real Android support are execution prerequisites, not app permission toggles.
     * No backend execution happens here; the original dispatcher/ledger still admits the write.
     */
    @Synchronized fun authorizeForSubmission(): PlatformActionScope {
        check(!submitted && !cancelled) { "platform_approval_consumed" }
        check(backend.unavailable(action.kind) == null) { "platform_capability_unavailable" }
        check(!requiresLocation || location?.ready(TaskSurface.FOREGROUND) != null) { "clock_location_not_approved" }
        submitted = true
        return PlatformActionScope(conversationId, runId, action,
            TaskAuthorization(runId.value, runId.value, setOf(action.effect)), routedBackend, onDrained = { location?.end() }).also { scope = it }
    }
    /** Source compatibility; submitting the task already grants this exact action. */
    fun approve(): PlatformActionScope = authorizeForSubmission()
    @Synchronized fun cancel() {
        cancelled = true
        val approved = scope
        if (approved == null) location?.end() else approved.close()
    }
}
