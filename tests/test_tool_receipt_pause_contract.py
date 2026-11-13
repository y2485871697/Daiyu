"""Pause refresh retains exact display evidence, never question authority or guessed success."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ToolReceiptPauseContract(unittest.TestCase):
    def test_pending_receipt_refresh_keeps_only_compatible_display_evidence(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        visible = host.split('val currentReceipts =', 1)[1].split('val remapTurns', 1)[0]
        self.assertIn('displayReader(loaded, currentReceipts, controller::runState, controller::runStopping)', visible)
        self.assertIn('retainedReceipts?.invoke(message, block)', visible)
        self.assertIn('if (receiptsReady) null else', visible)
        prepare = host.split('private fun prepareToolReceipts()', 1)[1].split('private fun publishChat()', 1)[0]
        for fence in ('toolReceiptRead?.cancel()', 'withContext(Dispatchers.IO)',
                      'toolReceiptRequest !== request', 'toolReceiptRequested != key', '!key.canDisplayFor(currentToolReceiptKey())'):
            self.assertIn(fence, prepare)
        self.assertIn('toolReceiptRequested?.canDisplayFor(key) == true', prepare)
        complete = prepare.split('private fun completeToolReceipts(', 1)[1]
        self.assertLess(complete.index('toolReceiptRequest = null'), complete.index('publishChat()'))
        self.assertIn('if (activityUsable() && currentToolReceiptKey() != key) publishChat()', prepare)

    def test_real_host_completion_regression_keeps_source_fences_and_latest_refresh(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ToolReceiptPauseActivityTest.kt').read_text()
        for token in ('testFirstReadSurvivesLivePhaseChangesAndPublishesWithoutAnotherModelEvent',
                      'testLateReceiptCompletionCannotReplaceAnotherSourceOrRequest',
                      'it.name == "completeToolReceipts"', 'complete.invoke(activity, Any(), current, snapshot)',
                      'assertEquals("receipt refresh never opens a model session", 0, fixture.opens.get())'):
            self.assertIn(token, test)

    def test_compatibility_ignores_only_live_execution_and_requires_all_source_fields(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        key = source.split('internal data class ReadKey', 1)[1].split('internal class Snapshot', 1)[0]
        for field in ('conversation:', 'data:', 'generation:', 'sources:', 'execution:'):
            self.assertIn(field, key)
        self.assertIn('copy(execution = current.execution) == current', key)
        self.assertIn('Result(block.invocation, block.result, sourcePosition)', source)

    def test_display_continuity_does_not_relax_question_binding_or_unknown_semantics(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        questions = host.split('if (receiptsReady) questionCards =', 1)[1].split('val reconnects', 1)[0]
        self.assertIn('question.copy(binding = null, canAnswer = false)', questions)
        self.assertNotIn('canDisplayFor', questions)
        activity = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolActivityUi.kt').read_text()
        self.assertIn('ToolDisposition.UNKNOWN -> WorkStepState.UNKNOWN', activity)
        mapper = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatTurnMapper.kt').read_text()
        self.assertIn('else WorkStepState.UNKNOWN', mapper)

    def test_partial_reuse_keeps_owner_revision_and_current_absolute_position_fences(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        partial = source.split('fun retainedPositions(', 1)[1].split('private fun positions(', 1)[0]
        for fence in ('conversation != current.conversation', 'data != current.data', 'generation != current.generation',
                      'positions(current.sources)', 'positions[owner]?.get(block)', 'tools?.getOrNull(index) == block'):
            self.assertIn(fence, partial)
        reuse = source.split('fun displayReader(', 1)[1].split('fun projectionReader(', 1)[0]
        for state in ('SUCCEEDED', 'FAILED', 'NOT_EXECUTED'):
            self.assertIn('ToolDisposition.' + state, reuse)
        for state in ('READY', 'IN_FLIGHT', 'UNKNOWN'):
            self.assertNotIn('ToolDisposition.' + state, reuse)
        self.assertIn('val position = loaded.retainedPositions(current)', reuse)
        self.assertIn('val index = position(message, block) ?: return@reader null', reuse)
        self.assertNotIn('?: position(message, block)', reuse)

    def test_reopened_history_uses_one_authority_and_exact_physical_suffix_evidence(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        blocks = source.split('private fun sourceBlocks(', 1)[1].split('internal fun sources(', 1)[0]
        self.assertIn('if (message.historySegments.isEmpty()) message.blocks else message.historySegments', blocks)
        self.assertIn('filter { it.role == Message.ASSISTANT_ROLE }', blocks)
        self.assertNotIn('message.blocks +', source)
        read = source.split('fun read(database:', 1)[1].split('fun pendingProjection(', 1)[0]
        self.assertIn('projected.subList(tailStart, projected.size) != physical', read)
        self.assertIn('it.invocation == block.invocation && it.result == block.result', read)
        self.assertIn('offset + position', read)

    def test_host_regression_observes_pending_frames_at_actual_pause_and_resume(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ToolReceiptPauseActivityTest.kt').read_text()
        for token in ('SQLiteRuntimeStore.acquire(database)', 'call("publishChat")', 'val pending = Job()',
                      'fixture.controller.pause(owner)', 'fixture.controller.resume(owner)',
                      'ExecutionPhase.PAUSED', 'pendingFrame(key, snapshot, expected)',
                      'WorkStepState.UNKNOWN', 'assertTrue("fresh read remains pending", pending.isActive)',
                      'key.copy(data = key.data - 1)', 'key.copy(generation = key.generation - 1)'):
            self.assertIn(token, test)
