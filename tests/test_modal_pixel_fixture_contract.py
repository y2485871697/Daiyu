"""Preserve real modal and screen-pixel oracles while synchronizing their fixtures."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'

class ModalPixelFixtureContractTest(unittest.TestCase):
    def test_thinking_picker_waits_for_real_entry_and_focus(self):
        text = (TESTS / 'ChatThinkingPickerActivityTest.kt').read_text()
        opened = text.split('private fun openPicker()', 1)[1].split('private fun closePicker()', 1)[0]
        self.assertIn('awaitUniqueTag("chat-thinking-title")', opened)
        self.assertLess(opened.index('performClick()'), opened.index('awaitUniqueTag("chat-thinking-title")'))
        self.assertLess(opened.index('awaitDialogEnter(node("chat-thinking-title"))'), opened.index('assertIsDisplayed()'))
        self.assertIn('awaitNodeWindowFocus(node("chat-thinking-title"))', opened)
        closed = text.split('private fun closePicker()', 1)[1].split('fun testLegacy', 1)[0]
        self.assertLess(closed.index('awaitNodeWindowFocus('), closed.index('sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)'))
        self.assertEqual(1, closed.count('sendKeyDownUpSync('))
        self.assertIn('ui.waitUntil(5_000)', closed)
        self.assertIn('fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()', closed)
        self.assertIn('assertDoesNotExist()', closed)
        self.assertNotIn('Thread.sleep', opened + closed)

    def test_switch_screen_pixels_are_below_system_status_bar(self):
        text = (TESTS / 'ui/screens/settings/SettingsSwitchOffStateTest.kt').read_text()
        self.assertIn('Column(Modifier.statusBarsPadding())', text)
        self.assertIn('WindowInsetsCompat.Type.statusBars()', text)
        self.assertIn('frameNode.positionOnScreen.y >= statusBarBottom', text)
        for oracle in ('captureScreenNode(frame)', 'distance(pixels[thumbX, y], thumb) < .035f',
                       'distance(pixels[trackX, y], surface) < .035f',
                       'distance(pixels[thumbX, y], pixels[trackX, y]) > .08f',
                       'outline.copy(alpha = .38f).compositeOver(surface)',
                       'listOf(1f, .8f)', 'LayoutDirection.Ltr, LayoutDirection.Rtl'):
            self.assertIn(oracle, text)

if __name__ == '__main__':
    unittest.main()
