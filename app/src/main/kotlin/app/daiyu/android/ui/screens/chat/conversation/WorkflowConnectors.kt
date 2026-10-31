package app.daiyu.android.ui.screens.chat.conversation

internal data class WorkflowConnector(val before: Boolean, val after: Boolean, val through: Boolean)

/** The first and last real steps bound the line; narration and empty text never split it. */
internal fun workflowConnectors(content: List<MessageContentUi>, continuous: Boolean): List<WorkflowConnector> {
    fun step(item: MessageContentUi?) = item is MessageContentUi.Tool || item is MessageContentUi.Reasoning
    val first = content.indexOfFirst(::step)
    val last = content.indexOfLast(::step)
    return content.mapIndexed { index, item ->
        val node = step(item)
        WorkflowConnector(
            before = node && if (continuous) index > first else step(content.getOrNull(index - 1)),
            after = node && if (continuous) index < last else step(content.getOrNull(index + 1)),
            through = continuous && !node && index > first && index < last,
        )
    }
}
