package app.daiyu.android.ui.screens.chat.conversation

internal enum class ToolCardAction(val label: String) {
    PAUSE_TASK("暂停"), RESUME_TASK("继续"), STOP_TASK("停止"), READ_TASK_RESULT("查看结果"), VIEW_ASSETS("查看全部产物"),
    OPEN_IMAGE("查看原图"), PLAY_VIDEO("播放"), PLAY_AUDIO("播放"), PAUSE_AUDIO("暂停"), SAVE_MEDIA("保存原件"),
    SEEK_AUDIO("定位播放"),
}

internal data class ToolCardActionRequest(
    val owner: WorkStepOwnerUi?,
    val toolCallId: String,
    val toolName: String,
    val action: ToolCardAction,
    val assetKey: String? = null,
    val position: Float? = null,
)

internal fun WorkStepUi.actionRequest(action: ToolCardAction, assetKey: String? = null, position: Float? = null) =
    ToolCardActionRequest(owner, toolCallId, toolName, action, assetKey, position)

internal fun ToolCardActionRequest.matches(step: WorkStepUi): Boolean = owner != null && owner == step.owner &&
    toolCallId == step.toolCallId && toolName == step.toolName

internal fun resolveToolCardAction(turns: List<ChatTurnUi>, request: ToolCardActionRequest): WorkStepUi? =
    turns.filterIsInstance<ChatTurnUi.Assistant>().asSequence().flatMap { turn ->
        if (turn.content.isNotEmpty()) turn.content.filterIsInstance<MessageContentUi.Tool>().map { it.step }.asSequence()
        else turn.workProcess?.segments.orEmpty().flatMap { it.steps }.asSequence()
    }.firstOrNull { request.matches(it) }

internal fun mediaTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
