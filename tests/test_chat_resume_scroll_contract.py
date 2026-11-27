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
        self.assertIn('tailIndex, touchEpoch, scrollResumed, pageActive, retainingRows)', request)
        self.assertIn('if (!scrollResumed || !pageActive || currentRetainingRows) return@LaunchedEffect', request)
        self.assertIn('it.serial != consumedScrollSerial', request)
        self.assertIn('listState.animateScrollToItem(index)', request)
        resume = self.chat.split('Lifecycle.Event.ON_RESUME -> {', 1)[1].split('else -> Unit', 1)[0]
        self.assertNotIn('ChatScrollRequest(', resume)
        self.assertNotIn('consumedScrollSerial =', resume)

    def test_geometry_correction_is_separate_from_foreground_stream_motion(self):
        follow = self.chat.split('// Keep a single motion', 1)[1].split('// Read confirmation', 1)[0]
        self.assertIn('LaunchedEffect(listState, resumeFollowEpoch)', follow)
        guard = self.chat.split('fun canFollowBottom(): Boolean =', 1)[1].split('\n\n', 1)[0]
        self.assertIn('scrollResumed && followBottom && !currentDragging', guard)
        self.assertNotIn('while (true)', follow)
        self.assertIn('withFrameNanos', follow)
        self.assertNotIn('alignViewport', follow)
        self.assertNotIn('observedViewport', follow)
        self.assertIn('clearanceLayout.viewportLoss.pendingPx > 0f', follow)
        self.assertIn('correctFollowClearance()', follow)
        self.assertIn('observeFollowViewport(layout)', follow)
        self.assertIn('clearanceLayout.viewportLoss.consume(consumed)', follow)
        alignment = follow.split('if (alignment) {', 1)[1].split('}', 1)[0]
        self.assertIn('motion.reset()', alignment)
        self.assertIn('snapshotFlow {', follow)
        self.assertIn('Triple(canFollowBottom(),', follow)
        disabled = follow.split('if (target == null) {', 1)[1].split('return@collect', 1)[0]
        self.assertIn('motion.reset()', disabled)
        self.assertIn('clearanceLayout.viewportLoss.reset()', disabled)
        self.assertIn('clearanceLayout.followViewport = null', disabled)
        self.assertIn('listState.scrollToItem(index)', follow)
        self.assertIn('layout.viewportSize, Triple(layout.beforeContentPadding, layout.afterContentPadding, currentOcclusionPx + currentFollowClearancePx), Triple(resumeReady, currentNestedTail.contentHeight, Triple(currentTailKey, currentNestedTail, currentNestedTail.textClearance.typingPending))))', ' '.join(follow.split()))
        observation = self.chat.split('fun observeFollowViewport(', 1)[1].split('fun correctFollowClearance()', 1)[0]
        self.assertIn('val viewport = Triple(layout.viewportSize, layout.beforeContentPadding, currentOcclusionPx + currentFollowClearancePx)', ' '.join(observation.split()))
        self.assertNotIn('layout.afterContentPadding', observation)
        self.assertIn('viewport.first.height - viewport.second - currentOcclusionPx', observation)
        self.assertIn('following = canFollowBottom()', observation)
        self.assertIn('clearanceLayout.followViewport = viewport', observation)
        self.assertIn('internal val ChatTailClearance = 8.dp', self.chat)
        resume = follow.split('val resumeReady =', 1)[1].split('Triple(canFollowBottom()', 1)[0]
        self.assertIn('readyTurns[tail.listKey] == tail', resume)
        self.assertNotIn('revealedTurns', resume)
        self.assertIn('if (followDistance <= 0f)', follow)
        self.assertIn('val alignment = resumeFollowPending && !currentNestedTail.textClearance.pacedFollowing', follow)
        self.assertIn("last.size >= layout.viewportSize.height - currentOcclusionPx", follow)
        self.assertIn("if (!alignment && !documentOverflow)", follow)
        self.assertIn('var delta = if (alignment || documentOverflow) followDistance else 0f', follow)
        self.assertIn('val frameFollowDistance = maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())', follow)
        self.assertIn('delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity, continuous = currentNestedTail.textClearance.typingPending)', ' '.join(follow.split()))
        self.assertIn('while (canFollowBottom())', follow)
        self.assertIn('currentNestedTail.textClearance.finishPacedFollow()', follow)
        self.assertIn('overflow.toFloat()', follow)
        self.assertIn('motion.reset()', follow)
        stream = follow.split('val visibleBottom', 1)[1]
        self.assertIn('delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity, continuous = currentNestedTail.textClearance.typingPending)', ' '.join(stream.split()))
        self.assertNotIn('System.nanoTime()', stream)
        self.assertNotIn('delta = maxOf(', follow)

    def test_finished_drag_is_consumed_once_not_rearmed_by_geometry(self):
        self.assertIn('snapshotFlow { Triple(scrollResumed, currentDragging,', self.chat)
        drag = self.chat.split('snapshotFlow { Triple(scrollResumed, currentDragging,', 1)[1].split('// Keep a single motion', 1)[0]
        self.assertNotIn('snapshotFlow { listState.canScrollForward', drag)
        settle = drag.split('else if (!scrolling && wasDragged) {', 1)[1]
        self.assertLess(settle.index('wasDragged = false'), settle.index('followBottom = !listState.canScrollForward'))

    def test_resume_preserves_body_reveal_lane_and_formatted_body(self):
        self.assertIn('val revealResetEpoch = 0L', self.chat)
        self.assertIn('var resumeFollowEpoch by remember { mutableStateOf(0L) }', self.chat)
        self.assertEqual(2, self.chat.count('if (resumeFollowPending) resumeFollowEpoch++'))
        self.assertNotIn('revealResetEpoch++', self.chat)
        self.assertIn('LocalChatPresentationActive provides (presentationActive && pageActive && scrollResumed)', self.chat)
        self.assertIn('revealResetEpoch = revealResetEpoch', self.chat)
        self.assertIn(
            'rememberMarkdownReveal(bodySources, projection, streaming = turn.isStreaming, '
            'terminal = generationComplete, resetEpoch = revealResetEpoch, '
            'onVisibleAdvance = onVisibleAdvance, enabled = ordered.leading.isNotEmpty() || reasoningReady, blockedSources = blockedSources, '
            'live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty(), paceTerminal = true, '
            'initialBaseline = initialBaseline, presentationLane = "body:${turn.listKey}", awaitPaintAdmission = true, streamSnapshot = true)',
            ' '.join(self.chat.split()),
        )
        self.assertIn(
            'val initialBaseline = remember(turn.listKey, visualScope, revealResetEpoch) { '
            'visualScope?.bodyBaseline(turn.listKey, bodySources) }',
            ' '.join(self.chat.split()),
        )
        self.assertNotIn('val bodyRevealEpoch = remember(turn.listKey)', self.chat)
        narration = (BASE / 'WorkflowNarration.kt').read_text()
        self.assertIn('private val bodyOwners = mutableSetOf<String>()', narration)
        self.assertIn('if (bodyOwners.add(key) && !baseline) emptyList() else sources.toList()', narration)
        self.assertIn('live = turn.status == RunStatus.RUNNING || replyPlayback?.control?.pending(turn) == true', self.chat)
        self.assertNotIn('live = turn.status == RunStatus.RUNNING || turn.listKey == currentTailKey', self.chat)
        self.assertIn('remember(identity, resetEpoch, retainedLane) { retainedLane?.baseline ?: (initialBaseline ?: sources).toList() }', self.typing)
        self.assertIn('remember(identity, resetEpoch, retainedLane) { retainedLane?.motion ?: MarkdownTypingState(baseline) }', self.typing)
        self.assertIn('remember(playback?.control, presentationLane, identity, resetEpoch)', self.typing)
        self.assertIn(
            'return rememberMarkdownReveal(sources, prefixProjection, turn.isStreaming, '
            'turn.status != RunStatus.RUNNING && prefix.size == turn.content.size, resetEpoch, onVisibleAdvance, enabled, '
            'blockedSources = blockedSources, live = turn.status == RunStatus.RUNNING)',
            ' '.join(self.typing.split()),
        )
        self.assertIn('remember(motion, parts, baselineLength, streaming, targetTerminal, live, paceTerminal)', self.typing)
        self.assertIn('receivedMotion.update(parts, baselineLength, streaming, targetTerminal, live, paceTerminal)', self.typing)
        self.assertNotIn('key(revealResetEpoch)', self.chat)
        resume_prefix = (
            'resumeFollowPending && tail != null && !tail.isStreaming && '
            'layout.visibleItemsInfo.any { it.key == tail.listKey } && '
        )
        normalized_chat = ' '.join(self.chat.split())
        self.assertEqual(1, normalized_chat.count(resume_prefix + 'readyTurns[tail.listKey] != tail'))
        self.assertEqual(1, normalized_chat.count(resume_prefix +
            '(readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)'))
        resume = self.chat.split('val resumeReady =', 1)[1].split('Triple(canFollowBottom()', 1)[0]
        self.assertIn('readyTurns[tail.listKey] == tail', resume)
        self.assertNotIn('revealedTurns', resume)
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


    def test_inactive_release_keeps_wall_deadline_and_single_draw_sample(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatResumeScrollTest.kt').read_text()
        method = device.split('fun testInactiveTerminalAppendIsPresentationReadyBeforeReveal()', 1)[1].split('\n    fun ', 1)[0]
        sample = method.split('fun presentationSample(): PresentationSample {', 1)[1].split('fun sampledTick()', 1)[0]
        self.assertIn('ui.onAllNodes(prefixMatcher or copyMatcher,', sample)
        self.assertEqual(1, sample.count('.fetchSemanticsNodes()'))
        self.assertIn('check(length >= 0)', sample)
        tick = method.split('fun sampledTick(): PresentationSample {', 1)[1].split('fun assertNotCompleted', 1)[0]
        self.assertEqual(1, tick.count('ui.mainClock.advanceTimeByFrame()'))
        self.assertEqual(1, tick.count('presentationSample()'))
        self.assertNotIn('waitForIdle', tick)
        release = method.split('val startWall = System.nanoTime()', 1)[1].split('} catch (failure: Throwable)', 1)[0]
        self.assertIn('System.nanoTime() - startWall <= 5_000_000_000L', release)
        self.assertEqual(3, release.count('checkDeadline()'))
        self.assertIn('current in previous..appended.length', release)
        self.assertIn('assertEquals("The admitted suffix publishes once", 1, advances.get())', method)
        self.assertIn('if (current < appended.length) assertNotCompleted(sample)', release)
        self.assertIn('if (current == appended.length && sample.hasCopy && completed.get() > 0) break', release)
        self.assertNotIn('waitUntil', release)
        self.assertNotIn('Thread.sleep', release)

    def test_page_readiness_does_not_wait_for_inactive_terminal_typing(self):
        callback = self.chat.split(
            'SideEffect { onViewportReady(!initialPositionPending && incomingTurns.isNotEmpty()) }', 1
        )[1].split('DisposableEffect(userMessageEntrance, listState)', 1)[0]
        formatted = callback.split('val formatted =', 1)[1].split('val confirmedEmpty =', 1)[0]
        self.assertEqual('layout.visibleItemsInfo.none { item -> '
                         'val turn = currentAssistants[item.key] ?: return@none false readyTurns[turn.listKey] != turn }',
                         ' '.join(formatted.split()))
        self.assertNotIn('revealedTurns', callback)
        for token in ('currentHistoryReady && !currentRetainingRows',
                      'layout.viewportSize.width > 0 && layout.viewportSize.height > 0',
                      '!initialPositionPending && layout.visibleItemsInfo.isNotEmpty()',
                      'currentScrollSerial == consumedScrollSerial',
                      'currentPresentationReady(ready)'):
            self.assertIn(token, callback)
        receipts = self.chat.split('// Read confirmation', 1)[1].split('SideEffect { onViewportReady(', 1)[0]
        self.assertIn('completedVisibleTurns(visibleKeys, currentAssistants, readyTurns, revealedTurns)', receipts)
        self.assertIn('revealedTurns[it.listKey] == it', receipts)



class InactiveTickTimingContract(unittest.TestCase):
    def test_timing_diagnostics_do_not_add_a_wait_or_a_draw_sample(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatResumeScrollTest.kt').read_text()
        method = device.split('fun testInactiveTerminalAppendIsPresentationReadyBeforeReveal()', 1)[1].split('\n    fun ', 1)[0]
        sampled = method.split('fun sampledTick(): PresentationSample {', 1)[1].split('fun assertNotCompleted', 1)[0]
        self.assertEqual(1, sampled.count('ui.mainClock.advanceTimeByFrame()'))
        self.assertEqual(1, sampled.count('presentationSample()'))
        self.assertIn('advanceNanos += advanceElapsed', sampled)
        self.assertNotIn('waitForIdle', sampled)
        self.assertNotIn('runOnMainSync', sampled)
        self.assertNotIn('println', sampled)
        sample = method.split('fun presentationSample(): PresentationSample {', 1)[1].split('fun sampledTick()', 1)[0]
        self.assertEqual(1, sample.count('.fetchSemanticsNodes()'))
        self.assertIn('fetchNanos += fetchElapsed', sample)
        self.assertIn('scanNanos += System.nanoTime() - scanStart', sample)
        self.assertIn('"${timingSummary()} " +', method.split('} catch (failure:', 1)[1])
        self.assertIn('scanNanos = 0L\n            val startWall = System.nanoTime()', method)


if __name__ == '__main__':
    unittest.main()
