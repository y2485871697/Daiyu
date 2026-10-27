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

    def test_compression_uses_frozen_anchor_and_only_unanchored_items_extend_tail(self):
        listing = source('ui/screens/chat/conversation/ChatMessageList.kt')
        self.assertIn('item(key = "chat-context-compressing:${activity.id}"', listing)
        self.assertIn('turns.lastIndex + tailCompressions.size', listing)
        self.assertIn('compressionAfter[turn.listKey]', listing)
        self.assertIn('tailCompressions.forEach', listing)
        self.assertIn('val index = currentTailIndex', listing)
        self.assertIn('fadeInSpec = androidx.compose.animation.core.tween(180)', listing)
        divider = source('ui/screens/chat/conversation/ChatTimelineDivider.kt')
        self.assertIn('entry.messages <= 0 && entry.failure == null) return', divider)
        self.assertIn('indication = null', divider)

    def test_timeline_lines_use_readable_theme_foreground_at_the_existing_half_dp_height(self):
        divider = source('ui/screens/chat/conversation/ChatTimelineDivider.kt')
        self.assertIn('val line = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .5f)', divider)
        self.assertNotIn('val line = MiuixTheme.colorScheme.outline', divider)
        self.assertEqual(2, divider.count('Modifier.weight(1f).height(.5.dp).background(line)'))
        self.assertIn('val color = MiuixTheme.colorScheme.onSurfaceVariantSummary', divider)
        self.assertIn('.padding(horizontal = 20.dp, vertical = 10.dp)', divider)
        self.assertIn('style = MiuixTheme.textStyles.footnote2, color = color', divider)

    def test_reconnect_notice_is_run_owned_and_cleared_when_that_owner_is_fenced(self):
        controller = source('ChatController.kt')
        fence = controller.split('private fun fenceRun(', 1)[1].split('compressionActivitiesById.entries', 1)[0]
        self.assertIn('if (problem == lifetime.reconnectNotice) problem = null', fence)
        self.assertIn('lifetime.reconnectNotice = null', fence)
        notice = controller.split('fun postReconnectNotice(', 1)[1].split('val observedModel', 1)[0]
        self.assertIn('problem == original.reconnectNotice', notice)
        self.assertIn('original.reconnectNotice = message', notice)
        self.assertIn('!original.fenced', notice)

    def test_reconnect_timer_only_runs_in_running_state_and_resume_reuses_the_same_segment(self):
        divider = source('ui/screens/chat/conversation/ChatTimelineDivider.kt')
        self.assertIn('LaunchedEffect(entry.id, status, supplied)', divider)
        self.assertIn('elapsed = if (status == "running") maxOf(elapsed, supplied) else supplied', divider)
        self.assertIn('if (status == "running")', divider)
        self.assertIn('delay(1000)', divider)
        self.assertIn('"running" -> "正在重连：${formatReconnectElapsed(elapsedMs)}"', divider)
        self.assertIn('"succeeded" -> "已重连"', divider)
        self.assertIn('"stopped" -> "已停止重连"', divider)
        budget = source('model/reconnect/ModelErrorReconnect.kt')
        self.assertIn('sendLocked("stopped")', budget)
        self.assertIn('private val id = UUID.randomUUID().toString()', budget)
        self.assertEqual(1, budget.count('UUID.randomUUID().toString()'))
        callback = source('ChatController.kt').split('RunnerOptions(reconnect=frozenReconnect.runnerPolicyWithDiagnostics', 1)[1].split('runtimeStore=checkNotNull', 1)[0]
        self.assertIn('reconnectTransitionAllowed(previous.status, event.status)', callback)
        self.assertIn('reconnectTransitionAllowed(prior.status, entry.status)', source('timeline/ChatTimelineStore.kt'))
        self.assertIn('pausedDurationMs += (timing.nowMs() - pauseStart).coerceAtLeast(0)', budget)

    def test_reconnect_details_use_live_actual_errors_without_lifecycle_text(self):
        divider = source('ui/screens/chat/conversation/ChatTimelineDivider.kt')
        body = divider.split('if (showDetails && entry !is ChatTimelineEntry.ContextLimit)', 1)[1]
        self.assertIn('?: entry.reason', body)
        self.assertNotIn('reconnectLabel(', body)
        self.assertNotIn('累计耗时', body)
        self.assertIn('TimelineDetailsSheet(summary', body)
        controller = source('ChatController.kt')
        self.assertIn('reconnectErrorDetails(event.reason, event.diagnostic)', controller)
        runner = (ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/ChatRunner.kt').read_text()
        self.assertIn('reconnectBudget?.updateReason(reported.failure, reported.diagnostic)', runner)
        self.assertLess(runner.index('reconnectBudget?.updateReason(reported.failure, reported.diagnostic)'),
                        runner.index('classified !in options.reconnect.failures'))
        self.assertIn('attemptFence != pauseEpoch', runner)

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
