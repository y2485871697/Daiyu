"""Source wiring for resume scroll ownership; actual frame behavior is instrumentation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'
DEVICE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatResumeScrollTest.kt'


class ChatResumeScrollContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chat = (BASE / 'ChatMessageList.kt').read_text()
        cls.typing = (BASE / 'MarkdownTyping.kt').read_text()

    def test_pause_cancels_follow_without_erasing_scroll_ownership(self):
        self.assertIn('Lifecycle.Event.ON_PAUSE -> {', self.chat)
        pause = self.chat.split('Lifecycle.Event.ON_PAUSE -> {', 1)[1].split('Lifecycle.Event.ON_RESUME', 1)[0]
        self.assertIn('scrollResumed = false', pause)
        self.assertIn('resumeFollowPending = true', pause)
        self.assertIn('wasDragged = false', pause)
        for forbidden in ('consumedScrollSerial =', 'followBottom = true', 'scrollToItem'):
            self.assertNotIn(forbidden, pause)
        self.assertIn('lifecycle.addObserver(observer)', self.chat)
        self.assertIn('onDispose { lifecycle.removeObserver(observer) }', self.chat)

    def test_resume_does_not_issue_a_new_request_or_replay_a_consumed_serial(self):
        request = self.chat.split('LaunchedEffect(scrollRequest,', 1)[1].split('// Keep a single motion', 1)[0]
        self.assertIn('tailIndex, touchEpoch, scrollResumed)', request)
        self.assertIn('if (!scrollResumed) return@LaunchedEffect', request)
        self.assertIn('it.serial != consumedScrollSerial', request)
        self.assertIn('listState.animateScrollToItem(index)', request)
        resume = self.chat.split('Lifecycle.Event.ON_RESUME -> {', 1)[1].split('else -> Unit', 1)[0]
        self.assertNotIn('ChatScrollRequest(', resume)
        self.assertNotIn('consumedScrollSerial =', resume)

    def test_geometry_correction_is_separate_from_foreground_stream_motion(self):
        follow = self.chat.split('// Keep a single motion', 1)[1].split('// Read confirmation', 1)[0]
        self.assertIn('LaunchedEffect(listState, revealResetEpoch)', follow)
        guard = self.chat.split('fun canFollowBottom(): Boolean =', 1)[1].split('\n\n', 1)[0]
        self.assertIn('scrollResumed && followBottom && !currentDragging', guard)
        self.assertIn('snapshotFlow { canFollowBottom() }', follow)
        self.assertIn('if (!canFollowBottom())', follow)
        self.assertIn('layout.viewportSize, layout.beforeContentPadding, layout.afterContentPadding', follow)
        correction = follow.split('if (resumeFollowPending || alignViewport) {', 1)[1].split('val visibleBottom', 1)[0]
        self.assertIn('listState.scrollBy(overflow.toFloat())', correction)
        self.assertIn('motion.reset()', correction)
        stream = follow.split('val visibleBottom', 1)[1]
        self.assertIn('motion.step(overflow.toFloat(), frameTime, currentDensity)', stream)
        self.assertIn('obscuredTail.toFloat()', stream)

    def test_finished_drag_is_consumed_once_not_rearmed_by_geometry(self):
        self.assertIn('snapshotFlow { Triple(scrollResumed, currentDragging,', self.chat)
        drag = self.chat.split('snapshotFlow { Triple(scrollResumed, currentDragging,', 1)[1].split('// Keep a single motion', 1)[0]
        self.assertNotIn('snapshotFlow { listState.canScrollForward', drag)
        settle = drag.split('else if (!scrolling && wasDragged) {', 1)[1]
        self.assertLess(settle.index('wasDragged = false'), settle.index('followBottom = !listState.canScrollForward'))

    def test_resume_resets_only_reveal_progress_not_the_formatted_body(self):
        self.assertIn('if (resumeFollowPending) revealResetEpoch++', self.chat)
        self.assertIn('revealResetEpoch = revealResetEpoch', self.chat)
        self.assertIn('rememberMarkdownReveal(turn, projection, revealResetEpoch, onVisibleAdvance)', self.chat)
        self.assertIn('remember(identity, resetEpoch) { sources.toList() }', self.typing)
        self.assertIn('remember(identity, resetEpoch) { MarkdownTypingState(baseline) }', self.typing)
        self.assertIn('remember(motion, parts, baselineLength, streaming, terminal)', self.typing)
        self.assertNotIn('key(revealResetEpoch)', self.chat)
        resume_gate = (
            'resumeFollowPending && tail != null && '
            'layout.visibleItemsInfo.any { it.key == tail.listKey } && '
            '(if (tail.isStreaming) displayedTurns[tail.listKey] != tail '
            'else readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)'
        )
        self.assertEqual(2, ' '.join(self.chat.split()).count(resume_gate))
        self.assertIn('val contentReady = projection?.sources == sources', self.chat)
        self.assertIn('SideEffect { onContentReady(contentReady) }', self.chat)
        self.assertIn('SideEffect { onContentDisplayed(hasDisplayBody || contentReady) }', self.chat)

    def test_device_regressions_cover_stopped_growth_static_return_and_ownership(self):
        device = DEVICE.read_text()
        for token in ('testStoppedGrowthDoesNotScrollAndResumeAlignsWithoutAnEasingTail',
                      'testCompletedTextAndConsumedRequestStayStillAcrossRepeatedResume',
                      'testBackgroundMarkdownReplacementIsCompleteBeforeResumeAlignment',
                      'testTouchOwnershipSurvivesResumeAndTemporaryViewportChanges',
                      'testFinishedHistoryDragCannotRearmFollowOnLaterLayoutOnlyChanges',
                      'LifecycleRegistry', 'Lifecycle.State.CREATED', 'Lifecycle.State.RESUMED',
                      'ui.mainClock.autoAdvance = false', 'ui.mainClock.advanceTimeByFrame()',
                      'positionInRoot().y', 'fixture.opens.get()'):
            self.assertIn(token, device)


if __name__ == '__main__':
    unittest.main()
