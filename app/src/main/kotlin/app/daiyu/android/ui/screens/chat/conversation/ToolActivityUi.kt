package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.ToolDisposition

/** Display only. No transition here changes a runner, child or tool authorization. */
enum class ToolActivityUi(val label: String) {
    PENDING("待派发"), QUEUED("排队中"), PREPARING("准备中"), RUNNING("执行中"), WAITING("等待回答"),
    PAUSING("暂停中"), PAUSED("已暂停"), STOPPING("停止中"), STOPPED("已停止"), INTERRUPTED("已中断"),
    SUCCESS("成功"), FAILED("失败"), UNKNOWN("结果未确认"), NOT_EXECUTED("未执行");

    val inFlight: Boolean get() = this == RUNNING || this == WAITING || this == PAUSING || this == STOPPING

    companion object {
        fun from(state: WorkStepState): ToolActivityUi = when (state) {
            WorkStepState.PENDING -> PENDING
            WorkStepState.OK -> SUCCESS
            WorkStepState.FAILED -> FAILED
            WorkStepState.UNKNOWN -> UNKNOWN
            WorkStepState.NOT_EXECUTED -> NOT_EXECUTED
        }
    }
}

data class ToolReceiptUi(val state: WorkStepState, val activity: ToolActivityUi = ToolActivityUi.from(state))

internal fun toolReceiptPresentation(disposition: ToolDisposition, liveOwner: Boolean,
    phase: ExecutionPhase, pauseRequested: Boolean = false,
    stopRequested: Boolean = false, runStatus: RunStatus? = null, activeCall: Boolean = false): ToolReceiptUi {
    val state = when (disposition) {
        ToolDisposition.SUCCEEDED -> WorkStepState.OK
        ToolDisposition.FAILED -> WorkStepState.FAILED
        ToolDisposition.NOT_EXECUTED -> WorkStepState.NOT_EXECUTED
        ToolDisposition.UNKNOWN -> WorkStepState.UNKNOWN
        ToolDisposition.READY, ToolDisposition.IN_FLIGHT -> if (liveOwner) WorkStepState.PENDING else WorkStepState.UNKNOWN
    }
    val activity = when {
        disposition == ToolDisposition.SUCCEEDED || disposition == ToolDisposition.FAILED ||
            disposition == ToolDisposition.NOT_EXECUTED -> ToolActivityUi.from(state)
        runStatus == RunStatus.STOPPED -> ToolActivityUi.STOPPED
        runStatus == RunStatus.INTERRUPTED -> ToolActivityUi.INTERRUPTED
        liveOwner && stopRequested && (state == WorkStepState.PENDING || activeCall) -> ToolActivityUi.STOPPING
        disposition in setOf(ToolDisposition.UNKNOWN, ToolDisposition.READY) && liveOwner && activeCall &&
            phase in setOf(ExecutionPhase.TOOL_EXECUTING, ExecutionPhase.WAITING_USER) ->
            if (pauseRequested) ToolActivityUi.PAUSING
            else if (phase == ExecutionPhase.WAITING_USER) ToolActivityUi.WAITING else ToolActivityUi.RUNNING
        state != WorkStepState.PENDING -> ToolActivityUi.from(state)
        phase == ExecutionPhase.TERMINAL || phase == ExecutionPhase.RECONNECTING -> ToolActivityUi.UNKNOWN
        phase == ExecutionPhase.PAUSED -> ToolActivityUi.PAUSED
        pauseRequested -> ToolActivityUi.PAUSING
        phase == ExecutionPhase.WAITING_USER && disposition == ToolDisposition.IN_FLIGHT -> ToolActivityUi.WAITING
        phase == ExecutionPhase.BOUNDARY || phase == ExecutionPhase.IDLE -> ToolActivityUi.PREPARING
        disposition == ToolDisposition.READY -> ToolActivityUi.QUEUED
        else -> ToolActivityUi.RUNNING
    }
    return ToolReceiptUi(state, activity)
}
