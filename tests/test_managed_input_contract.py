"""Source wiring coverage only. The real-window instrumentation tests are not run here."""
from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SOURCE = MAIN / 'ui/components/ManagedInput.kt'


class ManagedInputContractTest(unittest.TestCase):
    def test_all_censused_ordinary_fields_are_registered_and_cursor_aware(self):
        inventory = json.loads((ROOT / 'tests/fixtures/managed_input_fields.json').read_text())
        self.assertGreater(len(inventory), 25)
        self.assertEqual(56, sum(row['calls'] for row in inventory))
        for row in inventory:
            text = (ROOT / row['path']).read_text()
            with self.subTest(path=row['path']):
                self.assertGreaterEqual(text.count('.managedInput()'), row['calls'])
                self.assertGreaterEqual(text.count('imeCursorBrush(') + text.count('materialImeCursorColor('), row['calls'])

    def test_window_resolution_prefers_dialog_and_rejects_foreign_tokens(self):
        text = SOURCE.read_text().split('private fun View.inputWindow()', 1)[1].split('private fun View.visibleInputBounds()', 1)[0]
        self.assertLess(text.index('DialogWindowProvider'), text.index('context.inputActivity()'))
        self.assertIn('it.decorView.windowToken == windowToken', text)

    def test_callback_delegates_once_then_revalidates_current_focus(self):
        text = SOURCE.read_text()
        dispatch = text.split('override fun dispatchTouchEvent', 1)[1].split('    init {', 1)[0]
        self.assertEqual(1, dispatch.count('original.dispatchTouchEvent(event)'))
        self.assertLess(dispatch.index('original.dispatchTouchEvent(event)'), dispatch.index('dismiss(before)'))
        for token in ('before in entries', 'before.focused()', 'window.decorView.hasWindowFocus()',
                      'window.currentFocus === nativeFocus', 'return handled'):
            self.assertIn(token, dispatch)
        self.assertNotIn('.consume(', text)
        self.assertNotIn('setOnTouchListener', text)

    def test_completed_short_tap_only_and_protect_every_input_region(self):
        text = SOURCE.read_text()
        for token in ('entries.none { it.bounds()?.contains(event.rawX, event.rawY)',
                      'MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL', 'event.pointerCount != 1',
                      'hypot(event.rawX - x, event.rawY - y) > slop', 'ViewConfiguration.getLongPressTimeout()',
                      'MotionEvent.ACTION_UP && tap && outside', 'InputDismissal.insideInput = !outside',
                      'finally { InputDismissal.insideInput = previousInside }'):
            self.assertIn(token, text)

    def test_native_and_decor_bounds_use_screen_coordinates(self):
        text = SOURCE.read_text()
        for token in ('rootView.getLocationOnScreen(origin)', 'RectF(bounds).apply { offset(',
                      'field.visibleInputBounds()', 'window.decorView.visibleInputBounds()'):
            self.assertIn(token, text)

    def test_stop_not_pause_and_no_automatic_show_or_business_edits(self):
        text = SOURCE.read_text()
        self.assertIn('Lifecycle.Event.ON_STOP', text)
        self.assertNotIn('Lifecycle.Event.ON_PAUSE', text)
        self.assertNotIn('Lifecycle.Event.ON_RESUME', text)
        self.assertNotIn('.show(', text)
        self.assertNotIn('.setText(', text)
        self.assertIn('ViewCompat.getWindowInsetsController(entry.view)', text)
        self.assertIn('hideSoftInputFromWindow(entry.view.windowToken, 0)', text)

    def test_registration_cleanup_preserves_later_window_wrappers(self):
        text = SOURCE.read_text()
        for token in ('onDispose { detach() }', 'removeOnAttachStateChangeListener(listener)',
                      'lifecycle?.removeObserver(observer)', 'if (window.callback === callback)',
                      'InputWindows.windows.remove(window, this)'):
            self.assertIn(token, text)

    def test_brush_and_material_hidden_cursor_keep_visible_original_color(self):
        text = SOURCE.read_text()
        self.assertIn('if (WindowInsets.isImeVisible) visibleColor else Color.Unspecified', text)
        self.assertIn('if (WindowInsets.isImeVisible) visibleColor else Color.Transparent', text)
        self.assertIn('SolidColor(imeCursorColor(visibleColor))', text)

    def test_native_fields_register_and_pet_draft_is_not_owned_by_focus(self):
        for name in ('question/QuestionHost.kt', 'question/QuestionCardView.kt', 'pet/PetViewFactory.kt'):
            self.assertIn('manageInput(', (MAIN / name).read_text())
        text = (MAIN / 'pet/PetViewFactory.kt').read_text()
        self.assertIn('field.text.toString() == previous', text)
        self.assertNotIn('if (!assistant.hasFocus()) assistant.setText', text)
        self.assertIn('lastModel = s.settings.model', text)
        self.assertIn('if (field.text.toString() != next) field.setText(next)', text)

    def test_textfieldvalue_guards_only_our_blur_callback(self):
        text = (MAIN / 'ui/screens/settings/SettingsUiComponents.kt').read_text()
        self.assertIn('!app.daiyu.android.ui.components.InputDismissal.inProgress || updated.text != editorValue.text', text)
        self.assertIn('if (updated.text != value) onValueChange(updated.text)', text)
        chat = (MAIN / 'ui/screens/chat/conversation/ChatScreen.kt').read_text()
        self.assertIn('!app.daiyu.android.ui.components.InputDismissal.insideInput', chat)

    def test_web_dom_and_terminal_program_cursor_are_not_rewritten(self):
        terminal = (MAIN / 'ui/screens/terminal/TerminalConsole.kt').read_text()
        self.assertNotIn('managedInput()', terminal)
        self.assertNotIn('imeCursor', terminal)
        browser = (ROOT / 'components/openminis-browser/adapter/src/main/kotlin/app/daiyu/android/browser/openminis/AndroidOpenMinisBrowserHost.kt').read_text()
        self.assertNotIn('caret-color', browser)
        self.assertNotIn('document.activeElement.blur()', browser)

    def test_instrumentation_uses_actual_touches_and_real_window_ime(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ManagedInputActivityTest.kt').read_text()
        for token in ('exerciseFields(false)', 'exerciseFields(true)', 'tapField("managed-second")',
                      'assertEquals(5, selection.start)', 'assertEquals(1, selection.end)',
                      'GLOBAL_ACTION_HOME', 'awaitHostWindowFocus(original)',
                      'awaitImeFrame(node("managed-first"), visible = false)', '动作次数 1',
                      'realLongPress("managed-first")', 'realDrag("managed-first")', 'fixture.database.exportBackup()', 'fixture.opens.get()'):
            self.assertIn(token, text)
        self.assertNotIn('Thread.sleep', text)
        taps = text.split('private fun exerciseFields', 1)[1].split('fun testHiddenIme', 1)[0]
        self.assertNotIn('dismissIme()', taps)
        self.assertIn('uiAutomation.injectInputEvent(event, true)', text)
        self.assertIn('node(tag).assertIsFocused()', text)
        self.assertIn('Hidden IME must not draw a steady caret either', text)
        self.assertIn('image[it % width + inset, it / width + inset]', text)
        self.assertIn('testNativeFieldsKeepDraftAndSelectionWhenOutsideTapClearsFocus', text)
        self.assertNotIn('performTouchInput', text)


if __name__ == '__main__':
    unittest.main()
