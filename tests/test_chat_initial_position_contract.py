"""First-position source wiring; draw-frame behavior is covered by Android regressions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt'
HOST = ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt'


class ChatInitialPositionContract(unittest.TestCase):
    def test_selection_publishes_target_and_fresh_bottom_request_together(self):
        host = HOST.read_text()
        select = host.split('private fun select(conversation: Conversation?, prepared:', 1)[1].split('private fun sendDraft(', 1)[0]
        self.assertIn('publishChat(rowsChanged = true, scrollRequest = ChatScrollRequest(++scrollSerial))', select)
        self.assertNotIn('publishChat(); jumpToBottom()', select)
        publish = host.split('private fun publishChat(rowsChanged:', 1)[1].split('private fun requestScroll(', 1)[0]
        self.assertIn('scrollRequest: ChatScrollRequest? = chatState.scrollRequest', publish)
        self.assertIn('scrollRequest = scrollRequest,', publish)
        self.assertIn('conversationKey = selected?.id?.value', publish)

    def test_only_fresh_initial_bottom_position_is_concealed(self):
        chat = CHAT.read_text()
        initial = chat.split('var initialPositionPending by remember {', 1)[1].split('val lifecycle', 1)[0]
        self.assertIn('it.serial != consumedScrollSerial', initial)
        self.assertIn('it.followBottom && it.messageId == null && it.listKey == null', initial)
        self.assertIn('.drawWithContent { if (!initialPositionPending) drawContent() }', chat)

    def test_initial_alignment_waits_for_measured_ready_content_and_snaps(self):
        chat = CHAT.read_text()
        initial = chat.split('if (initialPositionPending) {', 1)[1].split('consumedScrollSerial = request.serial', 1)[0]
        for token in ('withFrameNanos', 'layout.viewportSize.height', 'layout.visibleItemsInfo',
                      'readyTurns[it.listKey] != it', 'it.listKey in visibleKeys && !it.isStreaming', 'revealedTurns[it.listKey] != it',
                      'layout.afterContentPadding - layout.viewportEndOffset', 'listState.canScrollForward',
                      'listState.scrollBy(overflow.toFloat())', 'initialPositionPending = false'):
            self.assertIn(token, initial)
        self.assertNotIn('animateScroll', initial)
        self.assertLess(initial.index('listState.scrollBy(overflow.toFloat())'), initial.rindex('initialPositionPending = false'))

    def test_hidden_rows_cannot_take_touch_or_send_visibility_receipts(self):
        chat = CHAT.read_text()
        self.assertIn('userScrollEnabled = !initialPositionPending', chat)
        self.assertIn('if (initialPositionPending) Modifier.clearAndSetSemantics {}', chat)
        gesture = chat.split('awaitEachGesture {', 1)[1].split('contentPadding =', 1)[0]
        hidden, visible = gesture.split('// A down over a child action', 1)
        self.assertIn('return@awaitEachGesture', hidden)
        self.assertIn('event.changes.forEach { it.consume() }', hidden)
        self.assertNotIn('consumedScrollSerial =', hidden)
        self.assertIn('followBottom = false; wasDragged = false', visible)
        self.assertIn('!initialPositionPending && scrollResumed && turn.status', chat)
        receipts = chat.split('// Read confirmation', 1)[1].split('Box(modifier', 1)[0]
        self.assertGreaterEqual(receipts.count('!initialPositionPending'), 2)

    def test_later_navigation_and_stream_motion_are_preserved(self):
        chat = CHAT.read_text()
        request = chat.split('LaunchedEffect(scrollRequest,', 1)[1].split('// Keep a single motion', 1)[0]
        self.assertIn('turns.size, tailIndex, touchEpoch, scrollResumed)', request)
        self.assertIn('listState.animateScrollToItem(index)', request)
        follow = chat.split('// Keep a single motion', 1)[1].split('// Read confirmation', 1)[0]
        self.assertIn('snapshotFlow { canFollowBottom() }', follow)
        guard = chat.split('fun canFollowBottom(): Boolean =', 1)[1].split('\n\n', 1)[0]
        self.assertIn('!initialPositionPending &&', guard)
        self.assertIn('motion.step(overflow.toFloat(), frameTime, currentDensity)', follow)


    def test_android_regressions_observe_draws_cold_parse_revisit_and_live_updates(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatInitialPositionTest.kt').read_text()
        for token in ('testLongTailIsBottomAlignedOnEveryDrawAfterSwitch',
                      'testColdMarkdownStaysUndrawnAndUnreadUntilItsRealHeightIsReady',
                      'testRevisitDoesNotDrawTheSavedHistoryPositionBeforeTheNewBottomRequest',
                      'testFastSwitchRejectsTheLateColdLayoutWithoutDrawingIt',
                      'testStreamingTargetCanAppearWithoutWaitingForGenerationToFinish',
                      'testContinuousStreamingUpdatesDoNotKeepTheEntireConversationHidden',
                      'drawWithContent', 'frame.toImageBitmap().toPixelMap()', 'this@drawWithContent.drawContent()',
                      'graphics.releaseGraphicsLayer(frame)', 'it.frameOwner == id',
                      'pixels.buffer.any { it ushr 24 != 0 }', 'unmarked visible content',
                      'boundsInRoot()', 'rememberSaveableStateHolder()',
                      'assertOnlyAlignedDraws("first")', 'body +=', 'release.await(5, TimeUnit.SECONDS)'):
            self.assertIn(token, device)

    def test_frame_capture_uses_original_draw_scope_and_real_capture_dispatcher(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatInitialPositionTest.kt').read_text()
        self.assertIn('frame.record(size = IntSize(', device)
        self.assertNotIn('frame.record(this, layoutDirection,', device)
        self.assertIn('scope.launch(Dispatchers.Main.immediate)', device)
        for helper, next_helper in (('awaitCapturedFrames', 'awaitDraw'), ('awaitDraw', 'assertOnlyAlignedDraws')):
            body = device.split('private fun ' + helper + '(', 1)[1].split('private fun ' + next_helper + '(', 1)[0]
            wait = body.split('ui.waitUntil(5_000) {', 1)[1]
            self.assertLess(wait.index('throwCaptureFailure()'), wait.index('pendingCaptures.get()' if helper == 'awaitCapturedFrames' else 'draws.any'))

    def test_controlled_frames_require_new_successful_viewport_samples(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatInitialPositionTest.kt').read_text()
        self.assertIn('val probe = visit to probeEpoch', device)
        self.assertIn('capturedProbes += probe', device)
        completion = device.split('val pixels = frame.toImageBitmap().toPixelMap()', 1)[1].split('catch (failure:', 1)[0]
        self.assertIn('capturedProbes += probe', completion)
        frames = device.split('private fun frames(', 1)[1].split('private fun throwCaptureFailure(', 1)[0]
        self.assertLess(frames.index('val probe = requestProbe()'), frames.index('advanceTimeByFrame()'))
        self.assertIn('awaitProbe(probe)', frames)
        wait = device.split('private fun awaitProbe(', 1)[1].split('private fun frames(', 1)[0]
        self.assertLess(wait.index('throwCaptureFailure()'), wait.index('probe in capturedProbes'))
        self.assertIn('ui.onAllNodesWithTag("chat-turn:cold-tail").assertCountEquals(0)', device)
        self.assertIn('useUnmergedTree = true).assertCountEquals(1)', device)
        self.assertIn('saved.isNotEmpty()', device)
        self.assertIn('saved.maxOf { it.bottom } > bottom + 10f', device)


if __name__ == '__main__':
    unittest.main()
