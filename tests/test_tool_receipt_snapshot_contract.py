"""Keep durable receipt decoding off the UI publisher without weakening snapshot validation."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
class ReceiptSnapshotContract(unittest.TestCase):
    def test_all_payload_queries_share_a_transaction_and_outer_ownership_is_preserved(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/storage/SQLiteRuntimeStore.kt').read_text()
        read = text.split('private fun read(db: SQLiteDatabase, messageId: String): Stored?', 1)[1].split('private val leases', 1)[0]
        self.assertIn('val ownTransaction = !db.inTransaction()', read)
        self.assertIn('db.beginTransactionReadOnly()', read)
        self.assertIn('else db.beginTransactionNonExclusive()', read)
        self.assertLess(read.index('val raw = try'), read.index('SQLiteText.read('))
        self.assertLess(read.index('SQLiteText.read('), read.index('if (ownTransaction) db.endTransaction()'))
        self.assertLess(read.index('db.endTransaction()'), read.index('RunCheckpointCodec.decode(payload)'))
        self.assertIn('检查点元数据不匹配', read)
    def test_the_ui_publisher_uses_only_a_worker_prepared_immutable_snapshot(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        publish = text.split('private fun publishChat(rowsChanged:', 1)[1].split('private fun requestScroll', 1)[0]
        self.assertNotIn('RetainedToolOutcomes.projectionReader(controller.database', publish)
        self.assertIn('toolReceiptSnapshot?.projectionReader(', publish)
        self.assertIn('receiptsReady &&', publish)
        prepare = text.split('private fun prepareToolReceipts()', 1)[1].split('private fun publishChat()', 1)[0]
        self.assertIn('withContext(Dispatchers.IO)', prepare)
        self.assertIn('currentToolReceiptKey() != key', prepare)
        self.assertIn('toolReceiptRequested != key', prepare)
    def test_body_growth_is_not_a_reason_to_decode_checkpoints_again(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        source = text.split('internal fun sources(', 1)[1].split('internal class Snapshot', 1)[0]
        self.assertIn('filterIsInstance<MessageBlock.Tool>()', source)
        self.assertNotIn('MessageBlock.Text', source)
        self.assertIn('Result(block.invocation, block.result)', text)
        self.assertIn('snapshot.message.runId.value != source.run', text)
        self.assertIn('snapshot.message.conversationId.value != source.conversation', text)
if __name__ == '__main__': unittest.main()
