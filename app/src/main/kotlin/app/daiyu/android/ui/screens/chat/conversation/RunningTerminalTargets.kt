package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.TerminalExecutionTarget
import org.json.JSONObject

internal object RunningTerminalTargets {
    fun project(turns: List<ChatTurnUi>, owner: Message?, targets: Map<ToolInvocation, TerminalExecutionTarget>): List<ChatTurnUi> {
        if (owner == null || owner.role != Message.ASSISTANT_ROLE || owner.status != RunStatus.RUNNING || targets.isEmpty()) return turns
        val scope = WorkStepOwnerUi(owner.conversationId.value, owner.runId.value, owner.id.value)
        val renderedKeys = mutableMapOf<String, MutableSet<String>>()
        fun record(value: WorkStepUi) {
            if (value.owner == scope) renderedKeys.getOrPut(value.id) { mutableSetOf() }.add(value.renderKey)
        }
        turns.filterIsInstance<ChatTurnUi.Assistant>().forEach { turn ->
            turn.workProcess?.segments?.forEach { it.steps.forEach(::record) }
            turn.content.filterIsInstance<MessageContentUi.Tool>().forEach { record(it.step) }
            turn.reconnectSource?.content?.filterIsInstance<MessageContentUi.Tool>()?.forEach { record(it.step) }
        }
        val pending = owner.blocks.filterIsInstance<MessageBlock.Tool>()
            .groupBy { it.invocation.id.value }.mapNotNull { (id, blocks) ->
                val block = blocks.singleOrNull()?.takeIf { it.result == null } ?: return@mapNotNull null
                if (renderedKeys[id]?.size != 1) return@mapNotNull null
                val target = targets[block.invocation] ?: return@mapNotNull null
                val receipt = JSONObject().put("backend", target.backend).put("uid", target.uid)
                    .put("guest_uid", target.guestUid).put("root", target.root).put("host_root", target.hostRoot)
                val label = TerminalToolTarget.from(block.invocation.name, receipt)?.label ?: return@mapNotNull null
                id to (block.invocation.name to label)
            }.toMap()
        if (pending.isEmpty()) return turns
        fun step(value: WorkStepUi): WorkStepUi {
            if (value.owner != scope || value.executionTargetLabel != null || value.category !in setOf("terminal", "background_job")) return value
            val (tool, label) = pending[value.id] ?: return value
            if (tool != value.toolName) return value
            return value.copy(name = "${value.name} · $label", executionTargetLabel = label)
        }
        fun content(items: List<MessageContentUi>): List<MessageContentUi> = items.map { item ->
            if (item !is MessageContentUi.Tool) item else {
                val value = step(item.step)
                if (value === item.step) item else item.copy(step = value)
            }
        }
        var changed = false
        val projected = turns.map { turn ->
            if (turn !is ChatTurnUi.Assistant) return@map turn
            val work = turn.workProcess?.let { process ->
                val segments = process.segments.map { segment ->
                    val steps = segment.steps.map(::step)
                    if (steps == segment.steps) segment else segment.copy(steps = steps)
                }
                if (segments == process.segments) process else process.copy(segments = segments)
            }
            val displayedContent = content(turn.content)
            val reconnect = turn.reconnectSource?.let { source ->
                val items = content(source.content)
                if (items == source.content) source else source.copy(content = items)
            }
            if (work === turn.workProcess && displayedContent == turn.content && reconnect === turn.reconnectSource) turn else {
                changed = true
                turn.copy(workProcess = work, content = displayedContent, reconnectSource = reconnect)
            }
        }
        return if (changed) projected else turns
    }
}
