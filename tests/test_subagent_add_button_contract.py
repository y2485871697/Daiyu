"""Source-only guard for the preset list FAB; Android pixels/actions are checked separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/SubAgentSettingsScreen.kt'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/SubAgentSettingsButtonTest.kt'


class SubAgentAddButtonContractTest(unittest.TestCase):
    def setUp(self):
        self.source = SCREEN.read_text()
        self.buttons = [part.split('}) { padding ->', 1)[0]
                        for part in self.source.split('floatingActionButton = {')[1:]]
        self.assertEqual(2, len(self.buttons))

    def test_list_and_detail_explicitly_share_filled_colors_and_elevation(self):
        for button in self.buttons:
            self.assertIn('FilledTonalButton(', button)
            self.assertIn('containerColor = MaterialTheme.colorScheme.primaryContainer', button)
            self.assertIn('contentColor = MaterialTheme.colorScheme.onPrimaryContainer', button)
            self.assertIn('ButtonDefaults.filledTonalButtonElevation(defaultElevation = 3.dp)', button)
            self.assertIn('shape = RoundedCornerShape(50)', button)
            self.assertIn('Modifier.heightIn(min = 48.dp).widthIn(min = 168.dp)', button)
        self.assertEqual(2, self.source.count('floatingActionButtonPosition = FabPosition.Center'))

    def test_existing_enabled_draft_and_save_logic_is_preserved(self):
        self.assertIn('val editable = !directory.loading && directory.error == null', self.source)
        self.assertIn('FilledTonalButton(enabled = editable, onClick = {', self.buttons[0])
        self.assertIn('name = ""; add = true', self.buttons[0])
        self.assertIn('if (add) repository.addPreset(name.trim())', self.source)
        self.assertIn('saveEnabled = editable && name.trim().isNotBlank()', self.source)
        self.assertIn('onDismiss = { add = false; rename = null }', self.source)
        self.assertIn('SubAgentProfileDraftSession.open(editor)', self.buttons[1])

    def test_regression_covers_real_screen_pixels_states_and_save_cancel(self):
        test = ANDROID.read_text()
        for fragment in ['SubAgentSettingsScreen(', 'listOf(false, true)', 'listOf(1f, .8f)',
                         'button.captureToImage().toPixelMap()', 'primaryContainer',
                         '168f * density', '48f * density', 'assertFloatingContainer(addGroupButton())',
                         'assertFloatingContainer(detail)', 'repository.addPreset("按钮样式")',
                         'assertTrue(repository.presets().isEmpty())',
                         'ui.onNodeWithText("保存").assertIsNotEnabled()',
                         'ui.onNodeWithText("取消").performClick()',
                         'ui.onNodeWithText("保存").assertIsEnabled().performClick()',
                         'repository.presets().map { it.name }', 'assertEquals(0, fixture.opens.get())']:
            self.assertIn(fragment, test)


    def test_reopen_waits_for_the_underlying_window_and_hidden_native_ime(self):
        test = ANDROID.read_text()
        reopen = test.split('ui.onNodeWithText("取消").performClick()', 1)[1].split('name.performTextReplacement("新子代理组")', 1)[0]
        self.assertLess(reopen.index('name.assertDoesNotExist()'), reopen.index('awaitNodeWindowFocus(addGroupButton())'))
        self.assertLess(reopen.index('awaitNodeWindowFocus(addGroupButton())'), reopen.index('dismissIme()'))
        self.assertLess(reopen.index('dismissIme()'), reopen.index('awaitImeFrame(addGroupButton(), visible = false)'))
        self.assertLess(reopen.index('awaitImeFrame(addGroupButton(), visible = false)'), reopen.index('addGroupButton().performClick()'))
        self.assertLess(reopen.index('addGroupButton().performClick()'), reopen.index('fetchSemanticsNodes().size == 1'))
        self.assertIn('ui.waitUntil(5_000)', reopen)
        self.assertNotIn('Thread.sleep', reopen)


if __name__ == '__main__':
    unittest.main()
