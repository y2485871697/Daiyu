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
        self.assertLess(read.index('db.endTransaction()'), read.index('RunCheckpointCodec.decodeWithRecovery(payload)'))
        self.assertIn('检查点元数据不匹配', read)
    def test_missing_receipt_does_not_invent_a_live_execution(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('RetainedToolOutcomes.pendingProjection(message, block,', host)
        projection = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        pending = projection.split('fun pendingProjection(', 1)[1].split('fun projectionReader(database:', 1)[0]
        self.assertIn('if (block.result == null) return null', pending)
        self.assertIn('ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN)', pending)
        self.assertIn('block.invocation.id !in liveState.activeToolCalls', pending)
        self.assertIn('status != RunStatus.RUNNING', pending)
        self.assertNotIn('WorkStepState.OK', pending)
        self.assertNotIn('JSONObject', pending)
    def test_pending_io_is_explicit_and_fenced_for_both_display_paths(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        visible = host.split('val currentReceipts =', 1)[1].split('val visibleChildNames', 1)[0]
        self.assertIn('val receiptPending = !toolReceiptReadFailed && toolReceiptRead?.isActive == true', visible)
        self.assertIn('toolReceiptRequested?.canDisplayFor(currentReceipts) == true', visible)
        self.assertEqual(2, visible.count('receiptPending = receiptPending'))
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        pending = source.split('fun pendingProjection(', 1)[1].split('fun projectionReader(database:', 1)[0]
        for guard in ('!receiptPending', 'status != RunStatus.RUNNING', 'liveState == null',
                      'liveState.phase == ExecutionPhase.IDLE', 'liveState.phase == ExecutionPhase.TERMINAL',
                      'projected.activity != ToolActivityUi.UNKNOWN'):
            self.assertIn(guard, pending)
        self.assertIn('return ToolReceiptUi(WorkStepState.PENDING, activity)', pending)
        self.assertNotIn('WorkStepState.OK', pending)
        self.assertNotIn('读取结果', pending)

    def test_display_fallback_uses_same_exact_live_aware_unknown_projection(self):
        activity = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolActivityUi.kt').read_text()
        self.assertNotIn('READING', activity)
        self.assertNotIn('读取结果', activity)
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        display = host.split('val visibleDisplayReceipts =', 1)[1].split('val visibleChildNames', 1)[0]
        self.assertIn('retainedDisplayReceipts?.invoke(owner, block)', display)
        self.assertIn('if (receiptsReady) null else', display)
        self.assertIn('RetainedToolOutcomes.pendingProjection(source.status, block,', display)
        self.assertIn('liveState = live?.let(controller::runState)', display)
        self.assertNotIn('ToolActivityUi.RUNNING', display)
        self.assertNotIn('WorkStepState.OK', display)

    def test_the_ui_publisher_uses_only_a_worker_prepared_immutable_snapshot(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        publish = text.split('private fun publishChat(rowsChanged:', 1)[1].split('private fun requestScroll', 1)[0]
        self.assertNotIn('RetainedToolOutcomes.projectionReader(controller.database', publish)
        self.assertIn('toolReceiptSnapshot?.displayReader(loaded, currentReceipts, controller::runState, controller::runStopping)', publish)
        self.assertIn('retainedReceipts?.invoke(message, block)', publish)
        self.assertIn('if (receiptsReady) questionCards =', publish)
        prepare = text.split('private fun prepareToolReceipts()', 1)[1].split('private fun publishChat()', 1)[0]
        self.assertIn('withContext(Dispatchers.IO)', prepare)
        self.assertIn('!key.canDisplayFor(currentToolReceiptKey())', prepare)
        self.assertIn('currentToolReceiptKey() != key) publishChat()', prepare)
        self.assertIn('toolReceiptRequested != key', prepare)
    def test_display_projection_consumes_receipts_without_forging_authoritative_messages(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        display_call = host.split('else displayWindow?.let { window -> ChatTurnMapper.mapDisplay(window,', 1)[1].split('} ?: ChatTurnMapper.map(', 1)[0]
        self.assertIn('toolReceipt = visibleDisplayReceipts', display_call)
        mapper = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatTurnMapper.kt').read_text()
        display = mapper.split('internal fun mapDisplay(', 1)[1].split('private data class RenderMessage', 1)[0]
        self.assertIn('receipt(message, block)?.state', display)
        self.assertIn('receipt(message, block)?.activity', display)
        self.assertNotIn('Message(', display.replace('RenderMessage(', ''))

    def test_pane_prepares_matching_receipts_before_first_publish_and_retains_async_update_guards(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        prepare = host.split('private fun preparePaneSnapshot(', 1)[1].split('private fun retainedConversation', 1)[0]
        self.assertNotIn('RetainedToolOutcomes.Snapshot.read(', prepare)
        self.assertIn('toolReceiptReader(snapshot.messages.map { it.second }, snapshot.display)', prepare)
        self.assertIn('toolReceipts = toolReceipts', prepare)
        select = host.split('private fun select(conversation: Conversation?, prepared:', 1)[1].split('private fun sendDraft(', 1)[0]
        self.assertLess(select.index('installPreparedToolReceipts(prepared)'), select.index('refresh()'))
        self.assertIn('key?.canDisplayFor(currentToolReceiptKey()) == true', host)
        self.assertIn('toolReceiptKey?.canDisplayFor(key) == true', host)
        self.assertNotIn('adoptPreparedToolReceipts', host)
        self.assertIn('RetainedToolOutcomes.Snapshot.read(controller.database, rows, display)', host)
        worker = host.split('private fun prepareToolReceipts(): Boolean', 1)[1].split('private fun completeToolReceipts(', 1)[0]
        for fragment in ('val rows = shown.map { it.second }', 'val display = displayWindow', 'val read = toolReceiptReader',
                         'toolReceiptRead?.cancel()', 'toolReceiptRequested?.canDisplayFor(key) == true',
                         'withContext(Dispatchers.IO)', 'read(rows, display)', 'completeToolReceipts(request, key, snapshot)',
                         'catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }',
                         'catch (_: Exception)', 'toolReceiptRequest === request'):
            self.assertIn(fragment, worker)
        complete = host.split('private fun completeToolReceipts(', 1)[1].split('private fun publishChat()', 1)[0]
        for fragment in ('!activityUsable()', 'toolReceiptRequest !== request', 'toolReceiptRequested != key',
                         '!key.canDisplayFor(currentToolReceiptKey())'):
            self.assertIn(fragment, complete)
        self.assertLess(complete.index('!key.canDisplayFor'), complete.index('toolReceiptKey = key'))
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ToolReceiptPauseActivityTest.kt').read_text()
        self.assertIn('testPaneReentryPreparesReceiptsBeforeDisplayAndAuthoritativeHistory', test)
        self.assertIn('previous page remains selected while receipt IO is blocked', test)
        self.assertIn('display consumes completed evidence without waiting for history', test)

    def test_body_growth_is_not_a_reason_to_decode_checkpoints_again(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        source = text.split('internal fun sources(', 1)[1].split('internal class Snapshot', 1)[0]
        self.assertIn('filterIsInstance<MessageBlock.Tool>()', source)
        self.assertNotIn('MessageBlock.Text', source)
        self.assertIn('Result(block.invocation, block.result, sourcePosition)', text)
        self.assertIn('snapshot.message.runId.value != source.run', text)
        self.assertIn('snapshot.message.conversationId.value != source.conversation', text)
if __name__ == '__main__': unittest.main()
