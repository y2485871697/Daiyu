"""Pause refresh retains exact display evidence, never question authority or guessed success."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ToolReceiptPauseContract(unittest.TestCase):
    def test_pending_receipt_refresh_keeps_only_compatible_display_evidence(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        visible = host.split('val visibleToolReceipts =', 1)[1].split('val remapTurns', 1)[0]
        self.assertIn('receiptsReady || toolReceiptKey?.canDisplayFor(currentToolReceiptKey()) == true', visible)
        self.assertIn('projectionReader(controller::runState, controller::runStopping)', visible)
        prepare = host.split('private fun prepareToolReceipts()', 1)[1].split('private fun publishChat()', 1)[0]
        for fence in ('toolReceiptRead?.cancel()', 'withContext(Dispatchers.IO)',
                      'toolReceiptRequest !== request', 'toolReceiptRequested != key', 'currentToolReceiptKey() != key'):
            self.assertIn(fence, prepare)
        self.assertNotIn('canDisplayFor', prepare)

    def test_compatibility_ignores_only_live_execution_and_requires_all_source_fields(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/RetainedToolOutcomes.kt').read_text()
        key = source.split('internal data class ReadKey', 1)[1].split('internal class Snapshot', 1)[0]
        for field in ('conversation:', 'data:', 'generation:', 'sources:', 'execution:'):
            self.assertIn(field, key)
        self.assertIn('copy(execution = current.execution) == current', key)
        self.assertIn('Result(block.invocation, block.result)', source)

    def test_display_continuity_does_not_relax_question_binding_or_unknown_semantics(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        questions = host.split('if (receiptsReady) questionCards =', 1)[1].split('val reconnects', 1)[0]
        self.assertIn('question.copy(binding = null, canAnswer = false)', questions)
        self.assertNotIn('canDisplayFor', questions)
        activity = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ToolActivityUi.kt').read_text()
        self.assertIn('ToolDisposition.UNKNOWN -> WorkStepState.UNKNOWN', activity)
        mapper = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatTurnMapper.kt').read_text()
        self.assertIn('else WorkStepState.UNKNOWN', mapper)

    def test_host_regression_observes_pending_frames_at_actual_pause_and_resume(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ToolReceiptPauseActivityTest.kt').read_text()
        for token in ('SQLiteRuntimeStore.acquire(database)', 'call("publishChat")', 'val pending = Job()',
                      'fixture.controller.pause(owner)', 'fixture.controller.resume(owner)',
                      'ExecutionPhase.PAUSED', 'pendingFrame(key, snapshot, expected)',
                      'WorkStepState.UNKNOWN', 'assertTrue("fresh read remains pending", pending.isActive)',
                      'key.copy(data = key.data - 1)', 'key.copy(generation = key.generation - 1)'):
            self.assertIn(token, test)
