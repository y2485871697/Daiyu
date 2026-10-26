"""Compose dismissal parks first; native blur transfers and reversed selection survive."""
from pathlib import Path
import unittest
R = Path(__file__).resolve().parents[1]
M = R / 'app/src/main/kotlin/app/daiyu/android/ui/components/ManagedInput.kt'

class R53BatchContract(unittest.TestCase):
    def test_native_clear_preserves_selection_in_shared_finally(self):
        source = M.read_text()
        helper = source.split('private fun clearWithoutNativeFocusFallback', 1)[1].split('private class ComposeInputRegion', 1)[0]
        for token in ['val start = field.selectionStart', 'val end = field.selectionEnd',
                      'field.setSelection(start.coerceAtMost(field.length()), end.coerceAtMost(field.length()))']:
            self.assertIn(token, helper)
        self.assertLess(helper.index('val start ='), helper.index('try {'))
        self.assertLess(helper.index('finally {'), helper.index('field.setSelection'))
        self.assertLess(helper.index('listener?.onFocusChange'), helper.index('FOCUS_BLOCK_DESCENDANTS'))
        self.assertNotIn('sorted', helper)
        native = source.split('internal fun EditText.manageInput', 1)[1].split('private fun bindInput', 1)[0]
        self.assertIn('clearWithoutNativeFocusFallback(field) { field.clearFocus() }', native)
        self.assertNotIn('field.setSelection', native)

    def test_compose_parking_does_not_replace_native_transfer_veto(self):
        source = M.read_text()
        common = source.split('fun dismiss(view: View, clear: () -> Unit, hideIme: () -> Unit = { hideManagedInputIme(view) }): Boolean {', 1)[1].split('fun remove(entry:', 1)[0]
        self.assertLess(common.index('if (nativeBefore !is EditText)'), common.index('holdRestingFocus()'))
        self.assertLess(common.index('holdRestingFocus()'), common.index('root.requestFocus()'))
        self.assertLess(common.index('root.requestFocus()'), common.index('clear()'))
        self.assertIn('(window.currentFocus !== nativeBefore && window.currentFocus?.onCheckIsTextEditor() == true)) return false', common)
        self.assertLess(common.index('return false'), common.index('hideIme()'))

    def test_voice_back_and_save_wait_for_the_actual_ui_terminal_state(self):
        source = (R / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceSettingsStructureActivityTest.kt').read_text()
        back = source.split('private fun backFromSelectionSheet()', 1)[1].split('private fun assertSelectionSheet', 1)[0]
        self.assertLess(back.index('awaitNodeWindowFocus'), back.index('KEYCODE_BACK'))
        self.assertIn('fetchSemanticsNodes().isEmpty()', back)
        self.assertIn('activity.window.decorView.hasWindowFocus()', back)
        self.assertEqual(1, back.count('KEYCODE_BACK'))
        self.assertEqual(1, back.count('ui.waitUntil(timeoutMillis = 5000)'))
        self.assertNotIn('awaitHostWindowFocus', back)
        saved = source.split('fun testCloneAccountEyeAndThemeSave', 1)[1].split('fun testDoubaoWithoutAccount', 1)[0]
        self.assertLess(saved.index('awaitUniqueTag("page:我的声音")'), saved.index('node("voice-account").assertExists()'))
        self.assertIn('node("voice-account-add").assertIsDisplayed()', saved)
        self.assertIn('assertEquals(0, fixture.opens.get())', saved)

if __name__ == '__main__':
    unittest.main()
