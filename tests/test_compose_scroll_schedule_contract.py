from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / 'app/src/androidTest/kotlin'


class ComposeScrollScheduleContractTest(unittest.TestCase):
    def test_android_scroll_actions_do_not_enter_undriven_library_loop(self):
        for path in TESTS.rglob('*.kt'):
            self.assertNotIn('.performScrollTo()', path.read_text(), str(path.relative_to(ROOT)))

    def test_scroll_helper_advances_each_accepted_action_before_testing_progress(self):
        source = (TESTS / 'app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        block = source.split('private fun scrollWithin(', 1)[1].split('protected fun', 1)[0]
        for text in ('timeoutMillis = 5_000', 'SemanticsActions.ScrollBy',
                     'ui.mainClock.advanceTimeByFrame()', 'ui.waitForIdle()', 'Scroll made no progress'):
            self.assertIn(text, block)
        action = block.split('performSemanticsAction(SemanticsActions.ScrollBy)', 1)[1]
        self.assertLess(action.index('advance()'), action.index('Scroll made no progress'))


if __name__ == '__main__':
    unittest.main()
