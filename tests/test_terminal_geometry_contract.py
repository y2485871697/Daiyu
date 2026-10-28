"""Test-source contracts only; Android geometry still requires emulator regression."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/terminal/TerminalScreenTest.kt'


class TerminalGeometryContractTest(unittest.TestCase):
    def test_terminal_ready_and_loading_both_choose_the_existing_compact_header(self):
        production = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/terminal/TerminalScreen.kt').read_text()
        header = production.split('MiuixScaffold(title = "终端"', 1)[1].split('{ padding, _, side ->', 1)[0]
        self.assertIn('onBack = onBack', header)
        self.assertIn('compactTitle = true', header)
        main = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        route = main.split('"terminal" -> terminalPageOwner?.let { page ->', 1)[1].split('"linux-settings", "chat-workspace" ->', 1)[0]
        loading = route.split('if (ready == null) TerminalLoadingPage(', 1)[1].split('else {', 1)[0]
        self.assertIn('compactTitle = true', loading)
        self.assertIn('closeTerminalPage()', loading)

    def test_android_title_oracle_checks_page_center_same_row_modes_and_real_back(self):
        text = SOURCE.read_text(encoding='utf-8')
        body = text.split('fun testTitleIsCenteredInBackRowInBothModesAndBackStaysWired()', 1)[1].split('fun testEntryIsReadOnly', 1)[0]
        for expected in ['assertCountEquals(1)', 'host.center.x, title.center.x',
                         'back.center.y, title.center.y', 'title.left >= back.right',
                         'node("terminal-mode").performClick()', 'performTouchInput { click() }',
                         'assertEquals(1, backs)', 'created.isEmpty()', 'sent.isEmpty()']:
            self.assertIn(expected, body)
        self.assertEqual(3, body.count('assertTitleInBackRow()'))

    def test_both_mode_oracles_use_same_native_frame_geometry(self):
        text = SOURCE.read_text(encoding='utf-8')
        body = text.split('fun testModeMovesToolbarAndDoesNotChangeDraftOutputOrLifecycle()', 1)[1].split('fun testRawKeys', 1)[0]
        self.assertEqual(2, body.count('= toolbarGeometry()'))
        self.assertIn('toolbar.top >= output.bottom', body)
        self.assertIn('rawToolbar.bottom <= raw.top', body)
        self.assertNotIn('boundsInRoot', body)
        for oracle in ['assertTextEquals("draft not sent")', 'assertTextEquals("retained output")', 'created.isEmpty()', 'assertEquals(0, closes)', 'sent.isEmpty()']:
            self.assertIn(oracle, body)
        self.assertIn('show()', body)
        self.assertNotIn('interceptInput', body)

    def test_sampler_observes_geometry_without_waiting_for_the_asserted_order(self):
        text = SOURCE.read_text(encoding='utf-8')
        helper = text.split('private fun toolbarGeometry()', 1)[1].split('private fun pressBackAndWait()', 1)[0]
        sample = helper.split('return awaitStableWindowFrame(view) {', 1)[1]
        self.assertIn('assertSame(output.root, toolbar.root)', sample)
        self.assertIn('output.layoutInfo.isPlaced', sample)
        self.assertIn('toolbar.layoutInfo.isPlaced', sample)
        self.assertIn('val position = node.positionInRoot', sample)
        self.assertIn('val size = node.size', sample)
        self.assertIn('val outputBounds = fullBounds(output)', sample)
        self.assertIn('val toolbarBounds = fullBounds(toolbar)', sample)
        self.assertIn('outputBounds.isEmpty || toolbarBounds.isEmpty', sample)
        self.assertIn('else outputBounds to toolbarBounds', sample)
        self.assertNotIn('fetchSemanticsNode()', sample)
        for forbidden in ['Thread.sleep', 'SystemClock.sleep', 'boundsInRoot', 'toolbar.top >=', 'rawToolbar.bottom <=', '5_000', 'advanceTimeBy(']:
            self.assertNotIn(forbidden, helper)


if __name__ == '__main__':
    unittest.main()
