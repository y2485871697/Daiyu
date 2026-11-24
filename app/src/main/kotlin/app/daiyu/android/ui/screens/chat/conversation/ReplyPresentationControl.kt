package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

internal class ReplyPresentationControl(
    private val rowKey: String?,
    initiallyLive: Boolean,
) {
    private var observedLive by mutableStateOf(initiallyLive)
    private var settledTurn by mutableStateOf<ChatTurnUi.Assistant?>(null)
    var pauseRequested by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set

    private data class LaneKey(val slot: Any, val parser: MarkdownParserIdentity, val epoch: Long)
    private val lanes = mutableMapOf<LaneKey, ReplyTypingLane>()
    private val pendingLanes = mutableStateMapOf<Any, Unit>()

    // Only seeing this very reply run arms its terminal draw callbacks. History can
    // report pending layout work, but neither that nor remounting may arm feedback.
    private var terminalFeedbackArmed = false
    fun updateFeedback(turn: ChatTurnUi.Assistant) {
        if (!matches(turn)) return
        if (turn.status == RunStatus.RUNNING) terminalFeedbackArmed = !turn.stopRequested
        else if (turn.status != RunStatus.COMPLETED || !pending(turn)) terminalFeedbackArmed = false
    }
    fun permitsTerminalFeedback(turn: ChatTurnUi.Assistant) = terminalFeedbackArmed &&
        turn.status == RunStatus.COMPLETED && !turn.stopRequested && !pauseRequested && pending(turn)

    fun matches(turn: ChatTurnUi.Assistant) = turn.listKey == rowKey
    fun observeLive() { observedLive = true }
    fun pending(turn: ChatTurnUi.Assistant) = matches(turn) && observedLive && !finished && (settledTurn != turn || pendingLanes.isNotEmpty() || lanes.values.any { lane -> lane.inks.values.any { it.hasRemaining } })
    fun setSettled(turn: ChatTurnUi.Assistant, settled: Boolean) {
        if (!matches(turn)) return
        if (settled) {
            settledTurn = turn
            // Row completion is final for feedback even when a disposed lane still reports pending.
            if (turn.status == RunStatus.COMPLETED) terminalFeedbackArmed = false
        }
        // Removing a lazy row revokes its viewport readiness, not an already displayed terminal reply.
        else if (turn.status == RunStatus.RUNNING && settledTurn == turn) settledTurn = null
    }
    fun lane(slot: Any, parser: MarkdownParserIdentity, epoch: Long, baseline: () -> List<MarkdownTypingSource>): ReplyTypingLane =
        lanes.getOrPut(LaneKey(slot, parser, epoch)) { ReplyTypingLane(baseline().toList()) }
    fun skipLane(slot: Any) {
        val skipped = lanes.keys.filter { it.slot == slot }
        skipped.forEach { key -> lanes.remove(key)?.let { pendingLanes.remove(it.motion) } }
    }
    fun reportPending(token: Any, pending: Boolean) {
        if (pending && !finished) { observedLive = true; pendingLanes[token] = Unit }
        else pendingLanes.remove(token)
    }
    fun pause(paused: Boolean) { if (!finished) pauseRequested = paused }
    var stopped by mutableStateOf(false)
        private set
    fun stopAtVisible() { if (observedLive) stopped = true; finish() }
    fun finish() { finished = true; pauseRequested = false }
}

internal class ReplyTypingLane(val baseline: List<MarkdownTypingSource>) {
    val inks = mutableStateMapOf<String, MarkdownInk>()
    val answerPaint = mutableMapOf<String, AnswerPaint>()
    val motion = MarkdownTypingState(baseline)
}

internal data class ReplyPlayback(val control: ReplyPresentationControl, val runtimePaused: Boolean) {
    val paused: Boolean get() = !control.finished && (runtimePaused || control.pauseRequested)
}

internal val LocalReplyPlayback = staticCompositionLocalOf<ReplyPlayback?> { null }

internal fun reasoningDisplayLabel(thinking: Boolean, paused: Boolean): String = when {
    thinking && paused -> "已暂停"
    thinking -> "正在思考…"
    else -> "已思考"
}
