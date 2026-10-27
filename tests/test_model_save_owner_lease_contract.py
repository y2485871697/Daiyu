"""Keep the lifecycle fixture lease longer than its bounded UI transitions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ModelSaveOwnerActivityTest.kt'


class ModelSaveOwnerLeaseContractTest(unittest.TestCase):
    def test_held_catalog_has_a_bounded_lease_and_unconditional_release(self):
        source = SOURCE.read_text()
        body = source.split('private fun withHeldCatalog(', 1)[1].split('fun testPausedQueuedSave', 1)[0]
        self.assertIn('check(released.await(60, TimeUnit.SECONDS))', body)
        self.assertIn('finally {', body)
        self.assertIn('released.countDown(); holder.join(10_000); assertFalse(holder.isAlive)', body)
        self.assertIn('failure.get()?.let { throw it }', body)
        self.assertIn('primary.addSuppressed(cleanup)', body)
        self.assertNotIn('Thread.sleep', body)

    def test_restore_read_still_checks_both_controls_and_preserves_the_draft(self):
        source = SOURCE.read_text()
        body = source.split('fun testPauseDuringRestoreReadKeepsDraftForNextResumedEditor()', 1)[1].split('fun testLeavingPage', 1)[0]
        for oracle in ['modelField("API Key").assertIsNotEnabled()',
                       'reveal("model-save").assertIsNotEnabled()',
                       'realPauseResume(activity, atPause = { release() })',
                       'assertEquals(saved, fixture.settings.load())',
                       'modelField("API Key").assertEditableText("restore-read-paused-model")',
                       'assertEquals(0, fixture.opens.get())']:
            self.assertIn(oracle, body)
