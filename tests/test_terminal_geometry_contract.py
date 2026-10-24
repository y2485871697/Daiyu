"""Test-source contracts only; Android geometry still requires emulator regression."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/terminal/TerminalScreenTest.kt'


class TerminalGeometryContractTest(unittest.TestCase):
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
