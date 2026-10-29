"""Source contracts only; window delivery and dismissal require Android regression."""
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/androidTest/kotlin/app/daiyu/android/CollaborationEmptyStateTest.kt'


class CollaborationDoneCompletionContractTest(unittest.TestCase):
    def body(self):
        return SOURCE.read_text(encoding='utf-8').split('fun testCurrentPanelHintAndAddStayAboveFooterForEmptyShortAndLongProfiles()', 1)[1].split('fun testEmptyPresetDirectoryKeepsCurrentConfigurationEntryAndDoesNotApplyAnything()', 1)[0]

    def test_parent_focus_and_single_real_click_precede_bounded_dismissal(self):
        body = self.body()
        click = 'done.assertIsDisplayed().assertIsEnabled().performClick()'
        self.assertEqual(1, body.count(click))
        self.assertLess(body.index('node("subagent-config-window").assertDoesNotExist()'), body.index('val done = node("subagent-collaboration-done")'))
        ready = 'awaitDialogTouchReady(done)'
        self.assertLess(body.index('val done = node("subagent-collaboration-done")'), body.index(ready))
        self.assertLess(body.index(ready), body.index(click))
        self.assertNotIn('awaitNodeWindowFocus(done)', body)
        self.assertLess(body.index(click), body.index('ui.waitUntil(5_000)'))
        self.assertIn('.fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()', body)
        self.assertLess(body.index('ui.waitUntil(5_000)'), body.index('node("subagent-collaboration-card").assertDoesNotExist()'))
        for forbidden in ['Thread.sleep', 'SystemClock.sleep', 'dismissIme', 'awaitImeFrame', 'repeat(', 'onNodeWithText("完成").performClick()']:
            self.assertNotIn(forbidden, body)

    def test_touch_ready_keeps_enter_focus_and_native_stability(self):
        base = SOURCE.with_name('ComposeActivityTestCase.kt').read_text(encoding='utf-8')
        helper = base.split('protected fun awaitDialogTouchReady(target: SemanticsNodeInteraction) {', 1)[1].split('\n    }\n', 1)[0]
        self.assertLess(helper.index('awaitDialogEnter(target)'), helper.index('awaitNodeWindowFocus(target)'))
        self.assertLess(helper.index('awaitNodeWindowFocus(target)'), helper.index('awaitStableWindowFrame(view)'))
        self.assertLess(helper.index('awaitStableWindowFrame(view)'), helper.index('target.assertIsDisplayed().assertIsEnabled()'))
        for required in ('view.windowToken != token', '!current.layoutInfo.isPlaced',
                         '!current.layoutInfo.coordinates.isAttached'):
            self.assertIn(required, helper)
        stable = base.split('protected fun <T : Any> awaitStableWindowFrame(', 1)[1].split('\n    }\n', 1)[0]
        for required in ('android.view.Choreographer.FrameCallback',
                         'android.view.ViewTreeObserver.OnPreDrawListener', 'next == previous', 'stableFrames >= 2'):
            self.assertIn(required, stable)

    def test_original_geometry_preferences_and_zero_io_oracles_remain(self):
        body = self.body()
        self.assertIn('for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (count in listOf(0, 1, 8))', body)
        self.assertEqual(2, body.count('assertEquals(before, prefs.all)'))
        for oracle in ['Hint and add centers:', 'Add glyph centers:', 'No extra top inset:', 'No extra bottom inset:', 'Hint does not overflow:', 'Text line box centers:', 'Add row starts below the scroll body:', 'Add row stays 8dp above the footer divider:', 'Short content leaves whitespace above the fixed add row:', 'Profile scroll actually advances:', 'Profiles scroll back toward the start:', 'Scrolling never moves the add row:', 'Scrolling never moves the footer:', 'assertEquals(0, fixture.opens.get())', 'add.performTouchInput { click(']:
            self.assertIn(oracle, body)


if __name__ == '__main__':
    unittest.main()
