"""Source guards for real-Activity completion boundaries, not device execution evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class ActivityCompletionContract(unittest.TestCase):
    def test_restore_failure_and_second_commit_wait_for_controller_completion(self):
        source = (TESTS / 'BackupActivityTest.kt').read_text()
        case = source.split('fun testRestoreCommitFailureInRealActivityPreservesOriginalAndRequiresNewPreview()', 1)[1].split('fun testRestoreEmptyMetadataOnly', 1)[0]
        first, second = case.split('recreate();', 1)
        self.assertLess(first.index('restorePress(DialogInterface.BUTTON_POSITIVE)'), first.index('awaitState { !controller.dataBusy }'))
        self.assertLess(first.index('awaitState { !controller.dataBusy }'), first.index('assertFalse("restore failure injection was not consumed",fixtureApp.failNextRestoreCommit)'))
        self.assertIn('assertEquals(before,modelPreferences().all)', first)
        self.assertLess(second.index('restorePreview();restorePress(DialogInterface.BUTTON_POSITIVE)'), second.index('awaitState { !controller.dataBusy }'))
        self.assertLess(second.index('awaitState { !controller.dataBusy }'), second.index('assertEquals("imported-model",modelPreferences().getString("model",null))'))
        for oracle in ('assertNull(restoreDialog())', 'assertEquals(0,modelCalls.get())', 'assertEquals(0,provider.opens.get())', 'assertEquals(0L,controller.dataRevision)'):
            self.assertIn(oracle, case)

    def test_shizuku_direct_send_click_follows_final_scroll_and_native_ime_frame(self):
        source = (TESTS / 'ShizukuMainActivityTest.kt').read_text()
        case = source.split('private fun clickSend()', 1)[1].split('private fun submitWhileWorkerBlocked()', 1)[0]
        parts = ('awaitNodeWindowFocus(node("shizuku-text-panel"))', 'dismissIme()',
                 'val send = panelNode(hasTestTag("shizuku-text-send"))',
                 'awaitImeFrame(send, visible = false)', 'send.performClick()')
        positions = [case.index(part) for part in parts]
        self.assertEqual(sorted(positions), positions)
        self.assertEqual(1, case.count('send.performClick()'))
        submit = source.split('private fun submitWhileWorkerBlocked()', 1)[1].split('private fun reserveReceiptFixture(', 1)[0]
        self.assertLess(submit.index('clickSend()'), submit.index('checkNotNull(fixture.controller.shizukuPreparation())'))
        self.assertIn('assertTrue(it.isSubmitted())', submit)
        self.assertNotIn('private fun preview(', source)
        self.assertNotIn('awaitDialogEnter', case)


if __name__ == '__main__':
    unittest.main()
