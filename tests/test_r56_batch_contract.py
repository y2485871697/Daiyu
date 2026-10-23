"""Real window barriers and exact native selection preconditions; no weaker oracle."""
from pathlib import Path
import unittest
R = Path(__file__).resolve().parents[1]
T = R / 'app/src/androidTest/kotlin/app/daiyu/android'

class R56BatchContract(unittest.TestCase):
    def test_terminal_geometry_waits_for_entry_and_samples_one_ui_frame(self):
        s = (T / 'terminal/TerminalSheetLayoutTest.kt').read_text()
        helper = s.split('private fun assertFooter()', 1)[1].split('fun testEmptySession', 1)[0]
        self.assertLess(helper.index('awaitDialogEnter'), helper.index('val sheetNode'))
        self.assertLess(helper.index('val createNode'), helper.index('ui.runOnIdle'))
        sample = helper.split('ui.runOnIdle {', 1)[1].split('\n        }', 1)[0]
        self.assertIn('Triple(sheetNode.boundsInRoot, footerNode.boundsInRoot, createNode.boundsInRoot)', sample)
        self.assertNotIn('fetchSemanticsNode', sample)
        self.assertIn('create.top >= footer.top && create.bottom <= footer.bottom', helper)
        self.assertIn('48f * density.get().density - 1f', helper)
        self.assertIn('create.left >= sheet.left && create.right <= sheet.right', helper)
        for token in ['Offset(center.x, 1f)', 'Offset(center.x, height - 1f)', 'assertEquals(0, selected)']:
            self.assertIn(token, s)

    def test_direct_dismiss_starts_with_exact_selection_in_same_main_turn(self):
        s = (T / 'ChatComposerImeActivityTest.kt').read_text()
        m = s.split('fun testDismissDoesNotHideImeWhenBlurListenerTransfersToAnotherEditor()', 1)[1].split('fun testOutsideBlank', 1)[0]
        direct = m.split('try {', 1)[1].split('awaitStableWindowFrame(second)', 1)[0]
        before, after = direct.split('hideChatInputIme(focus, keyboard, first)', 1)
        self.assertIn('mainThread {\n                first.setSelection(5, 1)', before)
        self.assertIn('assertEquals(5, first.selectionStart)', before)
        self.assertIn('assertEquals(1, first.selectionEnd)', before)
        for part in [after, m.split('awaitImeFrame(node("ime-focus-transfer-fixture"), visible = true)', 2)[-1]]:
            self.assertIn('assertEquals(5, first.selectionStart)', part)
            self.assertIn('assertEquals(1, first.selectionEnd)', part)
        self.assertIn('assertEquals("Outer hide must respect the managed focus-transfer veto", 0, keyboardHideCalls)', after)
        self.assertIn('injectInputEvent(event, true)', m)
        self.assertNotIn('post {', m)

    def test_picker_cancel_has_hidden_ime_premise_and_one_real_back(self):
        s = (T / 'VoiceSettingsStructureActivityTest.kt').read_text()
        m = s.split('private fun backFromSelectionSheet()', 1)[1].split('private fun assertSelectionSheet', 1)[0]
        for token in ['ViewRootForTest).view', 'dismissIme(root)', 'awaitDialogImeFrame(sheet, visible = false)',
                      'sheetGone && hostFocused', 'instrumentation.runOnMainSync', 'fetchSemanticsNodes().isEmpty()']:
            self.assertIn(token, m)
        self.assertLess(m.index('awaitDialogImeFrame'), m.index('KEYCODE_BACK'))
        self.assertEqual(1, m.count('KEYCODE_BACK'))
        self.assertEqual(1, m.count('ui.waitUntil(timeoutMillis = 5000)'))
        self.assertNotIn('awaitHostWindowFocus', m)

    def test_visible_ime_back_keeps_picker_then_cancel_preserves_profiles_and_catalog(self):
        s = (T / 'VoiceSettingsStructureActivityTest.kt').read_text()
        m = s.split('fun testReadAloudPickerBackHidesImeBeforeCancellingSelection()', 1)[1].split('fun testRealtimeTone', 1)[0]
        self.assertLess(m.index('visible = true'), m.index('KEYCODE_BACK'))
        self.assertLess(m.index('KEYCODE_BACK'), m.index('visible = false'))
        self.assertLess(m.index('visible = false'), m.index('node("chat-selection-sheet").assertIsDisplayed()'))
        self.assertLess(m.index('node("voice-model-dialog").assertExists()'), m.index('backFromSelectionSheet()'))
        for token in ['assertEquals(readingBefore', 'assertEquals(conversationBefore', 'assertEquals(modelBefore',
                      'assertEquals(catalogBefore', 'assertEquals(0, fixture.opens.get())']:
            self.assertIn(token, m)

if __name__ == '__main__':
    unittest.main()
