"""Keep streaming question projection and default-avatar decoding off the UI thread."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
class StreamQuestionProjectionContract(unittest.TestCase):
    def test_controller_projection_never_reads_durable_payload(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ChatController.kt').read_text()
        projection = text.split('@Synchronized internal fun questionProjection(', 1)[1].split('private val toolLedger', 1)[0]
        self.assertNotIn('SQLiteRuntimeStore', projection)
        self.assertNotIn('database.', projection)
        self.assertIn('evidence?.matches(message, call)', projection)
        self.assertIn('recoveryOwner == null', projection)
        self.assertIn('current.offer?.pending?.callId==call.id', projection)
    def test_worker_snapshot_shares_one_checkpoint_decode_for_receipts_and_questions(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        self.assertEqual(1, text.count('SQLiteRuntimeStore.readQuestionCheckpoint('))
        self.assertIn('QuestionCheckpointEvidence.from(snapshot, recoveryOwner)', text)
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('controller.questionWaiting(message)', host)
        self.assertIn('toolReceiptSnapshot?.questionEvidence(message)', host)
        self.assertIn('question.copy(binding = null, canAnswer = false)', host)
    def test_default_bitmap_is_shared_sampled_and_decoded_only_on_io(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/components/AssistantAvatar.kt').read_text()
        composition, worker = text.split('internal object DefaultAssistantAvatar', 1)
        self.assertNotIn('decodeResource', composition)
        self.assertNotIn('Canvas(', text)
        self.assertIn('withContext(Dispatchers.IO)', worker)
        self.assertIn('mutex.withLock', worker)
        self.assertIn('prepared.value?.let { return@withLock it }', worker)
        self.assertIn('inSampleSize', worker)
if __name__ == '__main__': unittest.main()
