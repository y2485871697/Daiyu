from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'
DOMAIN = ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain'

class ReasoningWorkflowContractTest(unittest.TestCase):
    def test_captured_position_is_display_metadata_not_protocol(self):
        source = (DOMAIN / 'Conversation.kt').read_text()
        self.assertIn('val blockOffset: Int? = null', source)
        runner = (DOMAIN / 'ChatRunner.kt').read_text()
        self.assertEqual(2, runner.count('blockOffset = current.blocks.size'))
        history = (DOMAIN / 'RunnerHistorySegments.kt').read_text()
        self.assertIn('if (previous == null) blockOffset else previous.blockOffset', history)
        self.assertIn('blocks.take(offset - start).count(::keep)', history)
        self.assertIn('it.protocolReasoning = protocolReasoning', history)

    def test_codec_preserves_legacy_shape_and_validates_new_metadata(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/storage/ReportedReasoningCodec.kt').read_text()
        self.assertIn('value.blockOffset?.let', source)
        self.assertIn('raw is Int || raw is Long', source)
        self.assertIn('0..Int.MAX_VALUE.toLong()', source)
        self.assertIn('blockOffset = offset', source)

    def test_both_mapper_paths_share_real_boundary_positions(self):
        source = (CHAT / 'ChatTurnMapper.kt').read_text()
        self.assertIn('MessageContentUi.Reasoning(it)', source)
        self.assertIn('WorkSegmentUi(reasoning = it)', source)
        self.assertIn('.groupBy { it.blockOffset ?: 0 }', source)
        self.assertIn('owner in workflowOwners', source)
        self.assertIn('val orderedTail', source)

    def test_integrated_card_reuses_disclosure_and_preserves_dedicated_tool_route(self):
        source = (CHAT / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('embedded: Boolean = false', source)
        self.assertIn('embedded = true', source)
        self.assertIn('rememberMarkdownProjection(listOf(text), streaming = !generationComplete)', source)
        self.assertIn('generationComplete && projection != null && displayed == summary.text', source)
        self.assertIn('ReasoningDisclosureDurationMillis = 220', source)
        content = (CHAT / 'MessageContent.kt').read_text()
        self.assertIn('is MessageContentUi.Reasoning -> reasoningBody', content)
        self.assertIn('if (!dedicatedTool(block.step)) WorkStepRow', content)

    def test_top_card_is_not_duplicated_and_timing_has_exact_owner_gate(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('if (!integratedReasoning) ReportedReasoningCard', source)
        self.assertIn('turn.isStreaming && turn.status == RunStatus.RUNNING', source)
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('key(ownerKey, "reasoning", reported.itemId, expanded)', work)
        self.assertIn('reasoningState.SaveableStateProvider(reported.itemId)', work)
        self.assertIn('val steps = work.segments.flatMap { it.steps }', work)

if __name__ == '__main__':
    unittest.main()
