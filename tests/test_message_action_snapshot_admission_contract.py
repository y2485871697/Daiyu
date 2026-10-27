"""Admission rejection is not a failed snapshot read; no automatic action replay."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/MessageActionsActivityTest.kt'


class MessageActionSnapshotAdmissionContract(unittest.TestCase):
    def test_unadmitted_read_releases_both_loading_and_attempted_key(self):
        source = ACTIVITY.read_text()
        prepare = source.split('private fun prepareMessageActionSnapshot()', 1)[1].split('@Volatile private var editorGeneration', 1)[0]
        self.assertIn('if (!admitted) {', prepare)
        rejected = prepare.split('if (!admitted) {', 1)[1].split('}', 1)[0]
        self.assertIn('messageActionLoading = null', rejected)
        self.assertIn('messageActionAttempted = null', rejected)
        self.assertIn('messageActionAttempted == key', prepare)
        # An admitted failure stays consumed; only the unadmitted branch releases it.
        self.assertEqual(1, prepare.count('messageActionAttempted = null'))

    def test_loading_does_not_offer_failure_recovery(self):
        source = ACTIVITY.read_text()
        rejection = source.split('is MessageActionResult.Rejected -> {', 1)[1].split('is MessageActionResult.Unknown', 1)[0]
        self.assertIn('if (actionSnapshot == null && messageActionLoading != null) {', rejection)
        loading = rejection.split('if (actionSnapshot == null && messageActionLoading != null) {', 1)[1].split('} else if', 1)[0]
        self.assertIn('正在准备消息操作，请稍后再试', loading)
        self.assertNotIn('ChoicePrompt', loading)
        self.assertIn('ChoicePrompt("消息操作快照不可用"', rejection)
        self.assertNotIn('onRegenerate', rejection)

    def test_activity_regressions_cover_admission_loading_and_real_read_failure(self):
        source = ANDROID.read_text()
        for name in [
            'testSnapshotRecoversAfterPreparationGateWithoutChangingHistory',
            'testSnapshotRecoversAfterGenerationReaderGateWithoutChangingHistory',
            'testLoadingSnapshotDoesNotOfferFailureRecoveryOrReplayRegeneration',
            'testFailedSnapshotRefreshConsumesKeyWithoutAutomaticRetry',
        ]:
            self.assertIn('fun ' + name + '()', source)
        self.assertIn('retainedControls(activity).onRegenerate(before.last().second.id.value)', source)
        self.assertIn('assertNull(field("messageActionAttempted"))', source)
        self.assertIn('it.name == "reservePreparation" && it.parameterCount == 1', source)
        self.assertIn('it.name == "releasePreparation" && it.parameterCount == 1', source)
        self.assertNotIn('getDeclaredField("preparing")', source)
        self.assertIn('assertSame(attempted, field("messageActionAttempted"))', source)


if __name__ == '__main__':
    unittest.main()
