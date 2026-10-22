"""Source wiring guards; real pointer and IME behavior is Android instrumentation coverage."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = MAIN / 'ui/screens/chat/conversation'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class ChatOutsideTapContractTest(unittest.TestCase):
    def test_observer_only_handles_completed_short_taps_without_consuming(self):
        text = (MAIN / 'ui/components/ChatInputIme.kt').read_text()
        body = text.split('internal fun chatInputDismissTapModifier(', 1)[1]
        self.assertIn('rememberUpdatedState(onTap)', body)
        self.assertIn('Modifier.pointerInput(Unit)', body)
        self.assertIn('awaitEachGesture', body)
        self.assertIn('requireUnconsumed = false, pass = PointerEventPass.Initial', body)
        self.assertIn('awaitPointerEvent(PointerEventPass.Initial)', body)
        self.assertLess(body.index('if (!change.pressed)'), body.index('currentTap()'))
        self.assertIn('change.previousPressed', body)
        self.assertNotIn('.consume(', body)
        self.assertNotIn('detectTapGestures', body)
        self.assertNotIn('delay(', body)

    def test_drag_long_press_multi_pointer_and_outside_up_are_not_taps(self):
        body = (MAIN / 'ui/components/ChatInputIme.kt').read_text().split('internal fun chatInputDismissTapModifier(', 1)[1]
        for check in ('event.changes.size != 1', 'change == null',
                      'change.position.x !in 0f..size.width.toFloat()',
                      'change.position.y !in 0f..size.height.toFloat()',
                      'getDistance() > viewConfiguration.touchSlop',
                      '>= viewConfiguration.longPressTimeoutMillis',
                      'change.position.x in 0f..size.width.toFloat()',
                      'change.position.y in 0f..size.height.toFloat()'):
            self.assertIn(check, body)

    def test_only_non_composer_content_uses_observer(self):
        text = (CHAT / 'ChatScreen.kt').read_text()
        self.assertIn('if (draftFocused) {', text)
        dismiss = text.split('val dismissInputOnTap = chatInputDismissTapModifier {', 1)[1].split('ChatMenuLayer(', 1)[0]
        self.assertLess(dismiss.index('inputDismissInProgress = true'), dismiss.index('hideChatInputIme(focus, keyboard, view)'))
        self.assertIn('finally {', dismiss)
        self.assertLess(dismiss.index('hideChatInputIme(focus, keyboard, view)'), dismiss.index('inputDismissInProgress = false'))
        self.assertIn('ChatTopBar(state.title, actions, modifier = dismissInputOnTap', text)
        self.assertIn('modifier = dismissInputOnTap.testTag("chat-status")', text)
        self.assertIn('ChatMessageList(state.turns, actions, Modifier.fillMaxSize().then(dismissInputOnTap)', text)
        composer = text.split('}, composer = { backdrop ->', 1)[1]
        self.assertIn('onInputFocusChanged = { draftFocused = it }', composer)
        self.assertIn('isInputDismissInProgress = { inputDismissInProgress }', composer)
        self.assertNotIn('dismissInputOnTap', composer)
        self.assertIn('ChatMenuLayer(modifier.fillMaxSize())', text)
        self.assertNotIn('boundsInRoot', text)

    def test_editor_reports_real_focus_without_changing_ime_or_draft_model(self):
        text = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('onInputFocusChanged: (Boolean) -> Unit = {}', text)
        self.assertIn('.onFocusChanged { onInputFocusChanged(it.isFocused) }', text)
        self.assertIn('lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)', text)
        self.assertIn('isInputDismissInProgress: () -> Boolean = { false }', text)
        self.assertIn('if ((!lifecycleDismissInProgress && !isInputDismissInProgress() || value.text != references.text) &&', text)
        self.assertIn('editingValue = TextFieldValue(projected.text, selection, composition)', text)
        self.assertNotIn('if (value.selection.collapsed)', text)

    def test_real_screen_taps_clear_focus_without_test_hiding_ime(self):
        text = (ANDROID / 'ChatComposerImeActivityTest.kt').read_text()
        body = text.split('fun testOutsideBlankAndTitleTapsReleaseDraftAndImeWithoutChangingDraft()', 1)[1].split('\n    fun ', 1)[0]
        self.assertIn('listOf("chat-messages", "chat-title")', body)
        after_tap = body.split('node(target).performTouchInput', 1)[1]
        for check in ('assertIsNotFocused()', 'settledFrame(visible = false, before = before)',
                      'assertDraft(draft)', 'assertEquals(database, fixture.database.exportBackup())',
                      'performTextInput("续写")', 'draft.replaceRange(selection.min, selection.max, "续写")'):
            self.assertIn(check, after_tap)
        self.assertIn('setSelection(8, 2, relativeToOriginalText = true)', body)
        self.assertIn('assertSelection(8, 2)', after_tap)
        self.assertIn('assertHostSelection(8, 2)', after_tap)
        self.assertNotIn('dismissIme()', after_tap)
        self.assertNotIn('clearFocus(', after_tap)
        self.assertNotIn('Thread.sleep', body)
        controls = text.split('fun testDraftAndComposerBlankTapsKeepEditingButTopButtonStillWorks()', 1)[1]
        self.assertIn('node("chat-composer-divider").performTouchInput { click() }', controls)
        self.assertIn('onNodeWithContentDescription("更多").performTouchInput { click() }', controls)
        self.assertIn('node("chat-menu:NEW_CHAT").assertIsDisplayed()', controls)
        self.assertIn('awaitImeFrame(node("chat-draft"), visible = false)', controls)

    def test_real_gestures_keep_child_actions_and_latest_callback(self):
        text = (ANDROID / 'ChatInputDismissGestureTest.kt').read_text()
        for check in ('chatInputDismissTapModifier', 'callbackVersion.intValue = 1',
                      'performTouchInput { click() }', 'longClick()', 'swipeLeft()',
                      'down(0, centerLeft)', 'down(1, centerRight)',
                      'assertEquals(1, clicks)', 'assertEquals(2, clicks)', 'assertEquals(1, oldDismisses)',
                      'assertEquals(1, longPresses)',
                      'drags > 0', 'assertEquals(1, dismisses)', 'assertEquals(1, oldDismisses)',
                      'beforeDowns + 2, childDowns', 'beforeUps + 2, childUps', 'childSawConsumedInitial',
                      'moveTo(Offset(width.toFloat() + 1f, center.y))'):
            self.assertIn(check, text)
        self.assertNotIn('Thread.sleep', text)
        first_click = text.index('node("gesture-child").performTouchInput { click() }')
        update = text.index('callbackVersion.intValue = 1')
        self.assertLess(first_click, update)

    def test_hidden_ime_disables_the_cursor_brush_without_changing_focus(self):
        text = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('import androidx.compose.foundation.layout.isImeVisible', text)
        self.assertIn('cursorBrush = SolidColor(if (WindowInsets.isImeVisible) chrome.accent else Color.Unspecified)', text)
        self.assertNotIn('clearFocus(', text)
        self.assertIn('onInputFocusChanged(it.isFocused)', text)
        android = (ANDROID / 'ChatComposerImeActivityTest.kt').read_text()
        hidden = android.split('fun testHiddenImeStopsCaretDrawingEvenWhenDraftRemainsFocused()', 1)[1]
        for check in ('dismissIme()', 'assertIsFocused()', 'visible = false', 'repeat(3)',
                      'advanceTimeBy(550)', 'captureScreenNode(node("chat-draft"))',
                      'expected.contentEquals(pixels)', 'cursorChanged',
                      'Visible IME must expose cursor blinking', 'assertIsNotFocused()',
                      'expected.contentEquals(withoutFocus)', 'assertSelection(draft.length, draft.length)',
                      'assertHostSelection(draft.length, draft.length)', 'assertDraft(draft)',
                      'assertEquals(database, fixture.database.exportBackup())'):
            self.assertIn(check, hidden)


if __name__ == '__main__':
    unittest.main()
