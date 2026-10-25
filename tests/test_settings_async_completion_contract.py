"""Guard exact async completion boundaries; Android execution remains a cloud gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ANDROID_TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class SettingsAsyncCompletionContractTest(unittest.TestCase):
    def test_running_mode_success_waits_for_rendered_selection_and_pending_release(self):
        text = (ANDROID_TEST / 'ui/screens/settings/RunningModeSettingsScreenTest.kt').read_text()
        for mode in ('ROOT', 'SHIZUKU'):
            tag = 'running-mode:' + mode.lower()
            tail = text.split(f'ui.waitUntil(5_000) {{ store.mode.value == RunningMode.{mode} }}', 1)[1]
            wait, assertion = tail.split(f'node("{tag}").assertIsSelected().assertIsEnabled()', 1)
            for token in ('ui.waitUntil(5_000)', 'ui.mainClock.advanceTimeByFrame()',
                          f'hasTestTag("{tag}") and isSelected() and isEnabled()',
                          '.fetchSemanticsNodes().size == 1'):
                self.assertIn(token, wait)
            self.assertNotIn('performClick', wait)
        for oracle in ('assertIsNotSelected().assertIsNotEnabled()',
                       'assertIsNotSelected().assertIsEnabled()',
                       'assertEquals(2, auth.shizukuCalls)', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(oracle, text)
        self.assertNotIn('Thread.sleep', text)

    def test_shared_restore_pumps_frames_until_original_second_read_latch_enters(self):
        text = (ANDROID_TEST / 'OtherPagePositionActivityTest.kt').read_text()
        body = text.split('fun testSharedDirectoryRestoresOnlyAfterFreshGrantAndDirectoryReadAndDropsRevokedTree()', 1)[1]
        wait = body.split('backgroundAndResume()', 1)[1].split('node("workspace-file:30").assertDoesNotExist()', 1)[0]
        for token in ('ui.waitUntil(5_000)', 'ui.mainClock.advanceTimeByFrame()', 'entered.count == 0L'):
            self.assertIn(token, wait)
        for token in ('entered.await', 'performClick', 'release.countDown()', 'Thread.sleep'):
            self.assertNotIn(token, wait)
        for oracle in ('queries.incrementAndGet() == 2', 'entered.countDown()',
                       'check(release.await(10, TimeUnit.SECONDS))',
                       'assertPositionWithoutScrolling("工作区", "workspace-file:30", before)',
                       'assertEquals(2, queries.get())', 'assertEquals(3, queries.get())',
                       'reads.set(emptyList())', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(oracle, body)


if __name__ == '__main__':
    unittest.main()
