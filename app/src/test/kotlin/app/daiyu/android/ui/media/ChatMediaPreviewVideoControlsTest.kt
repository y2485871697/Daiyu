package app.daiyu.android.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaPreviewVideoControlsTest {
    @Test fun controlsStartHiddenWithPlaybackRequested() {
        val state = ChatMediaPreviewVideoControlsState()
        assertFalse(state.visible)
        assertTrue(state.playRequested)
        assertFalse(state.seeking)
        assertEquals(0f, state.fraction, 0f)
    }

    @Test fun surfaceTapsChangeVisibilityWithoutChangingPlaybackOrProgress() {
        val original = ChatMediaPreviewVideoControlsState().prepared(10_000).positionChanged(2_000)
        val shown = original.toggleVisibility()
        assertTrue(shown.visible)
        assertEquals(original, shown.toggleVisibility())
        assertEquals(original.playRequested, shown.playRequested)
        assertEquals(original.positionMillis, shown.positionMillis)
    }

    @Test fun pauseAndPlayKeepControlsAndPlaybackPosition() {
        val original = ChatMediaPreviewVideoControlsState(visible = true).prepared(12_000).positionChanged(3_000)
        val paused = original.togglePlayback()
        assertFalse(paused.playRequested)
        assertTrue(paused.visible)
        assertEquals(3_000, paused.positionMillis)
        assertFalse(paused.positionChanged(4_000).playRequested)
        assertEquals(original, paused.togglePlayback())
    }

    @Test fun playbackRequiresActiveResumedIntentAndNoScrubbing() {
        for (active in listOf(false, true)) for (resumed in listOf(false, true)) {
            for (requested in listOf(false, true)) for (seeking in listOf(false, true)) {
                val state = ChatMediaPreviewVideoControlsState(
                    playRequested = requested, durationMillis = 10_000,
                    seekPositionMillis = if (seeking) 5_000 else null,
                )
                assertEquals(active && resumed && requested && !seeking, state.shouldPlay(active, resumed))
            }
        }
    }

    @Test fun scrubbingClampsTargetAndKeepsManualPauseOrPlayIntent() {
        for (requested in listOf(false, true)) {
            val original = ChatMediaPreviewVideoControlsState(playRequested = requested).prepared(10_000)
            val scrubbed = original.scrub(0.65f)
            assertTrue(scrubbed.seeking)
            assertEquals(6_500, scrubbed.displayedPositionMillis)
            assertEquals(requested, scrubbed.playRequested)
            val committed = scrubbed.finishSeeking()
            assertFalse(committed.seeking)
            assertEquals(scrubbed.displayedPositionMillis, committed.positionMillis)
            assertEquals(requested, committed.playRequested)
            assertTrue(committed.seekPending)
            assertEquals(0, original.scrub(-1f).displayedPositionMillis)
            assertEquals(10_000, original.scrub(2f).displayedPositionMillis)
        }
    }

    @Test fun nativePositionPollingDoesNotOverwriteTheDraggedPreview() {
        val dragging = ChatMediaPreviewVideoControlsState().prepared(10_000)
            .positionChanged(1_000).scrub(0.7f).positionChanged(1_200)
        assertEquals(1_200, dragging.positionMillis)
        assertEquals(7_000, dragging.displayedPositionMillis)
        assertEquals(7_000, dragging.finishSeeking().positionMillis)
    }

    @Test fun unknownDurationAndPositionAreBounded() {
        val unknown = ChatMediaPreviewVideoControlsState().prepared(-1).positionChanged(100)
        assertEquals(0, unknown.durationMillis)
        assertEquals(0, unknown.positionMillis)
        assertEquals(unknown, unknown.scrub(0.5f))
        val ready = unknown.prepared(1_000)
        assertEquals(1_000, ready.positionChanged(5_000).positionMillis)
        assertEquals(0, ready.positionChanged(-1).positionMillis)
    }

    @Test fun leavingPageHidesControlsAndCancelsScrubWithoutChangingIntentOrSavedPosition() {
        val playing = ChatMediaPreviewVideoControlsState(visible = true, playRequested = false)
            .prepared(10_000).positionChanged(2_000)
        val hidden = playing.scrub(0.8f).hidden()
        assertFalse(hidden.visible)
        assertFalse(hidden.seeking)
        assertFalse(hidden.playRequested)
        assertEquals(2_000, hidden.positionMillis)
    }

    @Test fun completionPinsTheExactEndAndReplayWaitsForTheNativeSeek() {
        val ended = ChatMediaPreviewVideoControlsState(visible = true).prepared(10_000)
            .positionChanged(8_600).completed()
        assertEquals(1f, ended.fraction, 0f)
        assertFalse(ended.playRequested)
        assertEquals(ended, ended.positionChanged(8_600))
        val replay = ended.togglePlayback()
        assertEquals(0f, replay.fraction, 0f)
        assertTrue(replay.playRequested)
        assertTrue(replay.seekPending)
        assertFalse(replay.shouldPlay(true, true))
        assertEquals(0, replay.positionChanged(8_600).positionMillis)
        assertTrue(replay.seekCompleted().shouldPlay(true, true))
    }

    @Test fun seekCommitCannotBeOverwrittenByOldNativePosition() {
        val pending = ChatMediaPreviewVideoControlsState().prepared(10_000)
            .positionChanged(8_000).scrub(0f).finishSeeking()
        assertTrue(pending.seekPending)
        assertFalse(pending.shouldPlay(true, true))
        assertEquals(0f, pending.positionChanged(8_000).fraction, 0f)
        assertEquals(0f, pending.seekCompleted().fraction, 0f)
        assertTrue(pending.seekCompleted().shouldPlay(true, true))
        val end = pending.seekCompleted().scrub(1f).finishSeeking().seekCompleted().completed()
        assertEquals(1f, end.fraction, 0f)
        assertFalse(end.playRequested)
    }

    @Test fun endSeekStopsAndPendingRequestCannotBeOvertaken() {
        val pending = ChatMediaPreviewVideoControlsState().prepared(10_000).scrub(1f).finishSeeking()
        assertEquals(1f, pending.fraction, 0f)
        assertFalse(pending.playRequested)
        assertEquals(pending, pending.togglePlayback())
        assertEquals(pending, pending.scrub(0.25f))
        assertEquals(pending, pending.completed())
        val ended = pending.seekCompleted()
        assertFalse(ended.shouldPlay(true, true))
        assertEquals(ended, ended.positionChanged(9_800))
        val dragging = ChatMediaPreviewVideoControlsState().prepared(10_000).scrub(0.3f)
        assertEquals(dragging, dragging.completed())
    }

    @Test fun shortVideoUsesMillisecondFractionsThroughoutTheFinalSegment() {
        for (duration in listOf(900, 1_980, 2_000, 2_150)) {
            var state = ChatMediaPreviewVideoControlsState().prepared(duration)
            for (position in listOf(0, duration / 2, duration * 3 / 4, duration - 100, duration - 20, duration - 1)) {
                state = state.positionChanged(position)
                assertEquals(position.toFloat() / duration, state.fraction, 0f)
                assertTrue(state.playRequested)
            }
            assertEquals(1f, state.completed().fraction, 0f)
        }
    }

    @Test fun videoTimeUsesMinutesAndHoursWithoutNegativeValues() {
        assertEquals("0:00", chatMediaPreviewVideoTime(-1))
        assertEquals("0:09", chatMediaPreviewVideoTime(9_999))
        assertEquals("1:05", chatMediaPreviewVideoTime(65_000))
        assertEquals("1:00:01", chatMediaPreviewVideoTime(3_601_000))
    }
}
