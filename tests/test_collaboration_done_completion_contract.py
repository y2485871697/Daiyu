"""Source contracts only; window delivery and dismissal require Android regression."""
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/androidTest/kotlin/app/daiyu/android/CollaborationEmptyStateTest.kt'


class CollaborationDoneCompletionContractTest(unittest.TestCase):
    def body(self):
        return SOURCE.read_text(encoding='utf-8').split('fun testCurrentPanelHintAndAddCenterInCompactRowForEmptyAndThreeProfiles()', 1)[1].split('fun testEmptyPresetDirectoryKeepsCurrentConfigurationEntryAndDoesNotApplyAnything()', 1)[0]

    def test_parent_focus_and_single_real_click_precede_bounded_dismissal(self):
        body = self.body()
        click = 'done.assertIsDisplayed().assertIsEnabled().performClick()'
        self.assertEqual(1, body.count(click))
        self.assertLess(body.index('node("subagent-config-window").assertDoesNotExist()'), body.index('val done = node("subagent-collaboration-done")'))
        self.assertLess(body.index('awaitNodeWindowFocus(done)'), body.index(click))
        self.assertLess(body.index(click), body.index('ui.waitUntil(5_000)'))
        self.assertIn('.fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()', body)
        self.assertLess(body.index('ui.waitUntil(5_000)'), body.index('node("subagent-collaboration-card").assertDoesNotExist()'))
        for forbidden in ['Thread.sleep', 'SystemClock.sleep', 'dismissIme', 'awaitImeFrame', 'repeat(', 'onNodeWithText("完成").performClick()']:
            self.assertNotIn(forbidden, body)

    def test_original_geometry_preferences_and_zero_io_oracles_remain(self):
        body = self.body()
        self.assertIn('for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (count in listOf(0, 3))', body)
        self.assertEqual(2, body.count('assertEquals(before, prefs.all)'))
        for oracle in ['Hint and add centers:', 'Add glyph centers:', 'No extra top inset:', 'No extra bottom inset:', 'Hint does not overflow:', 'Text line box centers:', 'Last card gap stays 8dp:', 'Body has no stacked bottom inset:', 'Footer gap stays 8dp:', 'assertEquals(0, fixture.opens.get())', 'add.performTouchInput { click(']:
            self.assertIn(oracle, body)


if __name__ == '__main__':
    unittest.main()
