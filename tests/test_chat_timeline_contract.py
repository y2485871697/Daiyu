"""Display timeline source/SQLite contracts; Android behavior is covered by gated device tests."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

def source(path):
    return (SRC / path).read_text()

class ChatTimelineContractTest(unittest.TestCase):
    def test_conversation_delete_cascades_display_events(self):
        sql = re.search(r'db.execSQL\("(CREATE TABLE IF NOT EXISTS chat_timeline_events[^"\n]+)"\)', source('timeline/ChatTimelineStore.kt')).group(1)
        db = sqlite3.connect(':memory:')
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('CREATE TABLE conversations(id TEXT PRIMARY KEY)')
        db.execute(sql)
        db.execute("INSERT INTO conversations VALUES('c')")
        db.execute("INSERT INTO chat_timeline_events VALUES('e','c','m','reconnect','{}',1)")
        db.execute("DELETE FROM conversations WHERE id='c'")
        self.assertEqual([], db.execute('SELECT * FROM chat_timeline_events').fetchall())
        db.close()

    def test_timeline_writer_has_a_separate_lane_and_pins_storage(self):
        controller = source('ChatController.kt')
        persist = controller.split('private fun persistReconnect(', 1)[1].split('private val contextPreparation', 1)[0]
        self.assertIn('timelineExecutor.execute', persist)
        self.assertIn('timelineWriters++', persist)
        self.assertIn('timelineWriters--', persist)
        self.assertIn('timelineWriters!=0 || contextManagementReaders!=0 || closingStorage', controller)
        self.assertIn('timelineExecutor.shutdown()', controller)
        callback = controller.split('RunnerOptions(reconnect=frozenReconnect.runnerPolicyWithDiagnostics', 1)[1].split('runtimeStore=checkNotNull', 1)[0]
        self.assertNotIn('handler.post', callback)
        self.assertIn('synchronized(this)', callback)
        self.assertIn('postReconnectNotice(', callback)

    def test_resume_does_not_hold_controller_monitor_while_entering_budget(self):
        resume = source('ChatController.kt').split('internal fun resume(owner:Message):Boolean', 1)[1].split('internal fun stopMain(', 1)[0]
        capture, call = resume.split('val accepted = lifetime.runner.resume()', 1)
        self.assertIn('current to current.childPauseEpoch\n        }', capture)
        self.assertIn('if (accepted) synchronized(this)', call)
        self.assertIn('resources === lifetime && ownedRunner(owner) === lifetime.runner', call)

    def test_repeat_summary_is_rejected_before_full_history_expansion(self):
        publish = source('context/ContextService.kt').split('internal fun publishRunCompression(', 1)[1].split('internal fun publishRunArchive(', 1)[0]
        self.assertLess(publish.index('SELECT 1 FROM chat_timeline_events'), publish.index('val originals ='))
        self.assertIn('wrapped.removePrefix(opening).removeSuffix(ending)', publish)
        self.assertIn('summaryIdentity', source('context/ContextPreparationCoordinator.kt'))
        self.assertIn('configuration.apiModel', source('context/ProviderSummaryModel.kt'))

    def test_stable_compression_item_preserves_message_indices_and_extends_tail(self):
        listing = source('ui/screens/chat/conversation/ChatMessageList.kt')
        self.assertIn('item(key = "chat-context-compressing:${activity.id}"', listing)
        self.assertIn('turns.lastIndex + liveCompressions.size', listing)
        self.assertIn('val index = currentTailIndex', listing)
        self.assertIn('fadeInSpec = androidx.compose.animation.core.tween(180)', listing)
        divider = source('ui/screens/chat/conversation/ChatTimelineDivider.kt')
        self.assertIn('entry.messages <= 0 && entry.failure == null) return', divider)
        self.assertIn('indication = null', divider)

    def test_backup_v7_validates_display_payload_and_authoritative_anchor(self):
        backup = source('backup/NeutralBackupService.kt')
        self.assertIn('columnsFor(8)', backup)
        self.assertIn('validatePayload(8, "export-validation"', backup)
        self.assertIn('spool.write(8, payload)', backup)
        self.assertIn('require(entry.anchor.listKey in anchors)', backup)
        codec = source('timeline/ChatTimelineStore.kt')
        self.assertIn('keys == common + detail', codec)
        self.assertIn('setOf("provider", "model", "protocol")', codec)
        self.assertIn('require(it in 0..50000)', codec)

    def test_async_reader_rechecks_revision_after_its_own_completion(self):
        activity = source('MainActivity.kt').split('private fun refreshTimeline()', 1)[1].split('private fun publishChat()', 1)[0]
        self.assertIn('timelineRead === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]', activity)
        self.assertIn('controller.timelineVersion != revision || editorGeneration != generation', activity)
        self.assertLess(activity.index('timelineRead = null', activity.index('finally')), activity.index('refreshTimeline()', activity.index('finally')))

    def test_dialog_native_frame_reads_captured_nodes_without_main_thread_test_synchronization(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatTimelineDividerTest.kt').read_text()
        body = test.split('val (windowBounds, cardBounds, safeBounds) = awaitStableWindowFrame(view) {', 1)[1].split('val blankBottom', 1)[0]
        self.assertNotIn('fetchSemanticsNode()', body)
        self.assertIn('viewportNode.boundsInRoot', body)
        self.assertIn('cardNode.boundsInRoot', body)
        self.assertIn('cardNode.layoutInfo.coordinates.isAttached', body)
        self.assertIn('viewport.performTouchInput { click(outside - windowBounds.topLeft) }', test)
        self.assertIn('assertEquals(1, dismissals)', test)

if __name__ == '__main__':
    unittest.main()
