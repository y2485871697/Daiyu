package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus
import org.junit.Assert.*
import org.junit.Test

class ComposerPhaseProjectionTest {
    @Test fun missingOwnerCannotExposeRunControls() {
        assertEquals(ComposerPhase.IDLE, composerPhaseForRun(null))
    }
    @Test fun pauseLabelIsProjectedFromRequestNotPersistedMessageStatus() {
        assertEquals(ComposerPhase.RUNNING, composerPhaseForRun(false))
        assertEquals(ComposerPhase.PAUSED, composerPhaseForRun(true))
        assertEquals(ComposerPhase.RUNNING, composerPhaseForRun(false))
    }
    private fun turn() = ChatTurnUi.Assistant("reply", status = RunStatus.RUNNING, isStreaming = true)
    private fun control(turn: ChatTurnUi.Assistant, live: Boolean = true) =
        ReplyPresentationControl(turn.listKey, live)

    @Test fun terminalSnapshotKeepsControlUntilThatExactReplyIsDisplayed() {
        val running = turn()
        val state = control(running)
        state.setSettled(running, true)
        assertFalse(state.pending(running))
        val terminal = running.copy(status = RunStatus.COMPLETED, isStreaming = false, finalText = "received")
        assertTrue(state.pending(terminal))
        state.setSettled(running, true)
        assertTrue("An older snapshot cannot release new output", state.pending(terminal))
        state.setSettled(terminal, true)
        assertFalse(state.pending(terminal))
    }

    @Test fun terminalPresentationCanPauseResumeAndFinishWithoutARuntimeOwner() {
        val running = turn()
        val terminal = running.copy(status = RunStatus.COMPLETED, isStreaming = false, finalText = "received")
        val state = control(running)
        val playback = ReplyPlayback(state, runtimePaused = false)
        state.pause(true)
        assertTrue(playback.paused)
        assertTrue(state.pending(terminal))
        state.pause(false)
        assertFalse(playback.paused)
        assertTrue(state.pending(terminal))
        state.pause(true)
        state.finish()
        assertFalse(playback.paused)
        assertFalse(state.pending(terminal))
    }

    @Test fun disposingASettledTerminalRowDoesNotRearmItsComposer() {
        val live = turn()
        val state = control(live)
        val done = live.copy(status = RunStatus.COMPLETED, isStreaming = false, finalText = "done")
        state.setSettled(done, true)
        state.setSettled(done, false)
        assertFalse(state.pending(done))
        assertTrue(state.pending(done.copy(finalText = "done with more output")))
        state.setSettled(live, true)
        state.setSettled(live, false)
        assertTrue(state.pending(live))
    }

    @Test fun runtimePauseAlsoFreezesThePresentation() {
        val state = control(turn())
        assertTrue(ReplyPlayback(state, runtimePaused = true).paused)
        state.finish()
        assertFalse(ReplyPlayback(state, runtimePaused = true).paused)
    }

    @Test fun historyDoesNotAcquireControlsWhileItParses() {
        val history = turn().copy(status = RunStatus.COMPLETED, isStreaming = false, finalText = "history")
        val state = control(history, live = false)
        state.setSettled(history, false)
        assertFalse(state.pending(history))
        state.observeLive()
        assertTrue(state.pending(history))
    }

    @Test fun differentRepliesCannotRetainControls() {
        val running = turn()
        val state = control(running)
        state.pause(true)
        assertFalse(state.pending(running.copy(id = "other", listKey = "other")))
        state.setSettled(running.copy(id = "other", listKey = "other"), true)
        assertTrue(state.pending(running))
        assertTrue(state.pauseRequested)
        state.finish()
        assertFalse(state.pending(running))
    }

    @Test fun terminalPresentationDraftAndAttachmentsUseNewSendAdmission() {
        for (phase in listOf(ComposerPhase.RUNNING, ComposerPhase.PAUSED)) {
            assertTrue(ComposerUi(text = "next", phase = phase, presentationOnly = true).textActionEnabled())
            assertTrue(ComposerUi(phase = phase, presentationOnly = true, imageSendAvailable = true).textActionEnabled())
            assertFalse(ComposerUi(phase = phase, presentationOnly = true).textActionEnabled())
            assertFalse(ComposerUi(text = "next", phase = phase, supplementAvailable = false).textActionEnabled())
        }
    }

    @Test fun reasoningLabelsFollowGenerationAndNotLocalDisplayBacklog() {
        assertEquals("正在思考…", reasoningDisplayLabel(thinking = true, paused = false))
        assertEquals("已暂停", reasoningDisplayLabel(thinking = true, paused = true))
        assertEquals("已思考", reasoningDisplayLabel(thinking = false, paused = false))
        assertEquals("已思考", reasoningDisplayLabel(thinking = false, paused = true))
    }

    @Test fun narrationPendingOutlivesAnOtherwiseSettledRowAndSurvivesPause() {
        val terminal = turn().copy(status = RunStatus.COMPLETED, isStreaming = false)
        val state = control(terminal)
        val lane = Any()
        state.setSettled(terminal, true)
        state.reportPending(lane, true)
        assertTrue(state.pending(terminal))
        state.pause(true)
        assertTrue(state.pending(terminal))
        state.reportPending(lane, false)
        assertFalse(state.pending(terminal))
    }

    @Test fun unfinishedLaneKeepsTheSameMotionAndBaselineUntilExplicitSkip() {
        val state = control(turn())
        val parser = MarkdownParserIdentity { emptyList() }
        val lane = state.lane("body", parser, 0) { emptyList() }
        lane.motion.update(emptyList(), 0, streaming = true, terminal = false)
        val source = MarkdownTypingSource("body", "甲".repeat(100))
        val target = lane.motion.update(listOf(MarkdownTypingPart(source, 100, source.text)), 0,
            streaming = false, terminal = true, paceTerminal = true)!!
        lane.motion.advance(target, 1_000_000_000L)
        lane.motion.advance(target, 1_050_000_000L)
        val before = lane.motion.visible(target)
        state.pause(true)
        state.reportPending(lane.motion, true)
        val remounted = state.lane("body", parser, 0) { listOf(source) }
        assertSame(lane, remounted)
        assertEquals(emptyList<MarkdownTypingSource>(), remounted.baseline)
        assertEquals(before, remounted.motion.visible(target))
        state.skipLane("body")
        assertNotSame(lane, state.lane("body", parser, 0) { listOf(source) })
    }

}
