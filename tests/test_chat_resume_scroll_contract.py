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
        self.assertIn('tailIndex, touchEpoch, scrollResumed, pageActive)', request)
        self.assertIn('if (!scrollResumed || !pageActive) return@LaunchedEffect', request)
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
        self.assertNotIn('while (true)', follow)
        self.assertIn('withFrameNanos', follow)
        self.assertIn('if (observedViewport != null && observedViewport != viewport) alignViewport = true', follow)
        alignment = follow.split('if (alignment) {', 1)[1].split('}', 1)[0]
        self.assertIn('motion.reset()', alignment)
        self.assertIn('snapshotFlow {', follow)
        self.assertIn('Triple(canFollowBottom(),', follow)
        self.assertIn('if (!canFollow)', follow)
        self.assertIn('listState.scrollToItem(index)', follow)
        self.assertIn('layout.viewportSize, layout.beforeContentPadding to layout.afterContentPadding, resumeReady to currentNestedTail.contentHeight', ' '.join(follow.split()))
        self.assertIn('readyTurns[tail.listKey] == tail && revealedTurns[tail.listKey] == tail', follow)
        self.assertIn('if (followDistance <= 0f)', follow)
        self.assertIn('val alignment = alignViewport || resumeFollowPending', follow)
        self.assertIn('var delta = if (alignment) followDistance else 0f', follow)
        self.assertIn('val frameFollowDistance = maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())', follow)
        self.assertIn('delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity)', follow)
        self.assertIn('while (canFollowBottom())', follow)
        self.assertIn('readyTurns[tail.listKey] == tail && revealedTurns[tail.listKey] == tail', follow)
        self.assertIn('overflow.toFloat()', follow)
        self.assertIn('motion.reset()', follow)
        stream = follow.split('val visibleBottom', 1)[1]
        self.assertIn('delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity)', stream)
        self.assertNotIn('System.nanoTime()', stream)
        self.assertNotIn('delta = maxOf(', follow)

    def test_finished_drag_is_consumed_once_not_rearmed_by_geometry(self):
        self.assertIn('snapshotFlow { Triple(scrollResumed, currentDragging,', self.chat)
        drag = self.chat.split('snapshotFlow { Triple(scrollResumed, currentDragging,', 1)[1].split('// Keep a single motion', 1)[0]
        self.assertNotIn('snapshotFlow { listState.canScrollForward', drag)
        settle = drag.split('else if (!scrolling && wasDragged) {', 1)[1]
        self.assertLess(settle.index('wasDragged = false'), settle.index('followBottom = !listState.canScrollForward'))

    def test_resume_resets_only_reveal_progress_not_the_formatted_body(self):
        self.assertIn('if (resumeFollowPending) revealResetEpoch++', self.chat)
        self.assertIn('revealResetEpoch = revealResetEpoch', self.chat)
        self.assertIn(
            'rememberMarkdownReveal(bodySources, projection, streaming = turn.isStreaming, '
            'terminal = generationComplete, resetEpoch = revealResetEpoch, '
            'onVisibleAdvance = onVisibleAdvance, enabled = reasoningReady, blockedSources = blockedSources, '
            'live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty(), paceTerminal = true)',
            ' '.join(self.chat.split()),
        )
        self.assertIn('remember(identity, resetEpoch) { (initialBaseline ?: sources).toList() }', self.typing)
        self.assertIn('remember(identity, resetEpoch) { MarkdownTypingState(baseline) }', self.typing)
        self.assertIn(
            'return rememberMarkdownReveal(sources, prefixProjection, turn.isStreaming, '
            'turn.status != RunStatus.RUNNING && prefix.size == turn.content.size, resetEpoch, onVisibleAdvance, enabled, '
            'blockedSources = blockedSources, live = turn.status == RunStatus.RUNNING)',
            ' '.join(self.typing.split()),
        )
        self.assertIn('remember(motion, parts, baselineLength, streaming, targetTerminal, live, paceTerminal)', self.typing)
        self.assertIn('motion.update(parts, baselineLength, streaming, targetTerminal, live, paceTerminal)', self.typing)
        self.assertNotIn('key(revealResetEpoch)', self.chat)
        resume_gate = (
            'resumeFollowPending && tail != null && !tail.isStreaming && '
            'layout.visibleItemsInfo.any { it.key == tail.listKey } && '
            '(readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)'
        )
        normalized_chat = ' '.join(self.chat.split())
        self.assertEqual(2, normalized_chat.count(resume_gate))
        self.assertNotIn('if (tail.isStreaming) displayedTurns[tail.listKey]', normalized_chat)
        self.assertNotIn('displayedTurns[tail.listKey] != tail', normalized_chat)
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
