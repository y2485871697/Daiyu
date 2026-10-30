package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.ToolDisposition
import org.junit.Assert.*
import org.junit.Test

class ToolActivityUiTest {
    @Test fun staleOwnersCannotBecomeRunningPausedOrWaiting() {
        for (phase in ExecutionPhase.entries) for (disposition in listOf(ToolDisposition.READY, ToolDisposition.IN_FLIGHT)) {
            assertEquals(ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN), toolReceiptPresentation(disposition, false, phase, true))
        }
    }
    @Test fun livePhasesArePresentationOnly() {
        assertEquals(ToolActivityUi.QUEUED, toolReceiptPresentation(ToolDisposition.READY, true, ExecutionPhase.TOOL_EXECUTING).activity)
        assertEquals(ToolActivityUi.PREPARING, toolReceiptPresentation(ToolDisposition.IN_FLIGHT, true, ExecutionPhase.BOUNDARY).activity)
        assertEquals(ToolActivityUi.WAITING, toolReceiptPresentation(ToolDisposition.IN_FLIGHT, true, ExecutionPhase.WAITING_USER).activity)
        assertEquals(ToolActivityUi.PAUSING, toolReceiptPresentation(ToolDisposition.IN_FLIGHT, true, ExecutionPhase.TOOL_EXECUTING, true).activity)
        assertEquals(ToolActivityUi.PAUSED, toolReceiptPresentation(ToolDisposition.IN_FLIGHT, true, ExecutionPhase.PAUSED, true).activity)
    }
    @Test fun runStopLabelsNeverChangeToolReceiptsAndFinalReceiptsWin() {
        for ((status, expected) in listOf(RunStatus.STOPPED to ToolActivityUi.STOPPED, RunStatus.INTERRUPTED to ToolActivityUi.INTERRUPTED)) {
            val value = toolReceiptPresentation(ToolDisposition.UNKNOWN, false, ExecutionPhase.TERMINAL, runStatus = status)
            assertEquals(WorkStepState.UNKNOWN, value.state); assertEquals(expected, value.activity)
            assertEquals(ToolActivityUi.SUCCESS, toolReceiptPresentation(ToolDisposition.SUCCEEDED, false, ExecutionPhase.TERMINAL, runStatus = status).activity)
            assertEquals(ToolActivityUi.FAILED, toolReceiptPresentation(ToolDisposition.FAILED, false, ExecutionPhase.TERMINAL, runStatus = status).activity)
        }
        assertEquals(ToolActivityUi.STOPPING, toolReceiptPresentation(ToolDisposition.IN_FLIGHT, true, ExecutionPhase.TOOL_EXECUTING, stopRequested = true).activity)
        assertEquals(ToolActivityUi.UNKNOWN, toolReceiptPresentation(ToolDisposition.IN_FLIGHT, false, ExecutionPhase.TOOL_EXECUTING, stopRequested = true).activity)
        assertEquals(ToolActivityUi.UNKNOWN, toolReceiptPresentation(ToolDisposition.IN_FLIGHT, true, ExecutionPhase.TERMINAL).activity)
    }
    @Test fun exactActiveCallDoesNotRemainQueuedWhenTheWorkerSnapshotStillSaysReady() {
        val running = toolReceiptPresentation(ToolDisposition.READY, true, ExecutionPhase.TOOL_EXECUTING, activeCall = true)
        assertEquals(WorkStepState.PENDING, running.state)
        assertEquals(ToolActivityUi.RUNNING, running.activity)
        assertEquals(ToolActivityUi.QUEUED, toolReceiptPresentation(ToolDisposition.READY, true,
            ExecutionPhase.TOOL_EXECUTING, activeCall = false).activity)
    }
    @Test fun activeUnknownReceiptKeepsRunningUntilItsFinalReceiptArrives() {
        assertEquals(ToolActivityUi.RUNNING, toolReceiptPresentation(ToolDisposition.UNKNOWN, true,
            ExecutionPhase.TOOL_EXECUTING, activeCall = true).activity)
        assertEquals(ToolActivityUi.PAUSING, toolReceiptPresentation(ToolDisposition.UNKNOWN, true,
            ExecutionPhase.TOOL_EXECUTING, pauseRequested = true, activeCall = true).activity)
        for (disposition in listOf(ToolDisposition.SUCCEEDED, ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED)) {
            assertEquals(ToolActivityUi.from(when (disposition) {
                ToolDisposition.SUCCEEDED -> WorkStepState.OK
                ToolDisposition.FAILED -> WorkStepState.FAILED
                else -> WorkStepState.NOT_EXECUTED
            }), toolReceiptPresentation(disposition, true, ExecutionPhase.TOOL_EXECUTING, activeCall = true).activity)
        }
    }
    @Test fun activeQuestionCanWaitWithoutInventingAConfirmedReceipt() {
        val ui = toolReceiptPresentation(ToolDisposition.UNKNOWN, true, ExecutionPhase.WAITING_USER, activeCall = true)
        assertEquals(ToolActivityUi.WAITING, ui.activity); assertEquals(WorkStepState.UNKNOWN, ui.state)
    }
    @Test fun activityRequiresBothTheExactLiveCallAndExecutingPhase() {
        for (phase in ExecutionPhase.entries) {
            assertEquals(ToolActivityUi.UNKNOWN, toolReceiptPresentation(ToolDisposition.UNKNOWN, false,
                phase, activeCall = true).activity)
            assertEquals(ToolActivityUi.UNKNOWN, toolReceiptPresentation(ToolDisposition.UNKNOWN, true,
                phase, activeCall = false).activity)
            if (phase !in setOf(ExecutionPhase.TOOL_EXECUTING, ExecutionPhase.WAITING_USER)) assertEquals(ToolActivityUi.UNKNOWN,
                toolReceiptPresentation(ToolDisposition.UNKNOWN, true, phase, activeCall = true).activity)
        }
    }
    @Test fun allDisplayStatesHaveAnEvidenceBasedPath() {
        val seen = WorkStepState.entries.map { ToolActivityUi.from(it) }.toMutableSet()
        for (phase in ExecutionPhase.entries) for (disposition in ToolDisposition.entries) for (pause in listOf(false, true)) {
            seen += toolReceiptPresentation(disposition, true, phase, pause).activity
            seen += toolReceiptPresentation(disposition, true, phase, pause, stopRequested = true).activity
            seen += toolReceiptPresentation(disposition, false, phase, runStatus = RunStatus.STOPPED).activity
            seen += toolReceiptPresentation(disposition, false, phase, runStatus = RunStatus.INTERRUPTED).activity
        }
        assertEquals(ToolActivityUi.entries.toSet(), seen)
    }
    @Test fun unknownAndFinalReceiptsCannotBePromotedByTheLivePhase() {
        for (phase in ExecutionPhase.entries) {
            assertEquals(WorkStepState.UNKNOWN, toolReceiptPresentation(ToolDisposition.UNKNOWN, true, phase).state)
            assertEquals(WorkStepState.NOT_EXECUTED, toolReceiptPresentation(ToolDisposition.NOT_EXECUTED, true, phase).state)
            assertEquals(ToolActivityUi.SUCCESS, toolReceiptPresentation(ToolDisposition.SUCCEEDED, true, phase).activity)
            assertEquals(ToolActivityUi.FAILED, toolReceiptPresentation(ToolDisposition.FAILED, true, phase).activity)
        }
    }
}
