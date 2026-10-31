"""Rendering propagation guards; runtime frame acceptance remains device-only."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class WorkflowRenderIsolationContract(unittest.TestCase):
    def test_workflow_host_does_not_receive_the_appended_answer_or_its_ast(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        host = source.split('private fun AssistantWorkflow(', 1)[1]
        signature = host.split(') {', 1)[0]
        self.assertNotIn('ChatTurnUi.Assistant', signature)
        self.assertNotIn('MarkdownProjection', signature)
        self.assertNotIn('MarkdownReveal', signature)
        for token in ('val ordered = rememberAssistantWorkflowContent(displayedTurn)',
                      'val textBody = rememberAssistantTextBody(',
                      'val currentVisibleAdvance = rememberUpdatedState(onVisibleAdvance)',
                      'val notifyVisibleAdvance = remember { { currentVisibleAdvance.value() } }',
                      'work?.let { AssistantWorkflow(it, ordered.steps, turn.listKey,',
                      'onAnswerReleased = { if (reasoningReady && answerPending) onAnswerReleased() }'):
            self.assertIn(token, source)
        renderer = source.split('internal fun rememberAssistantTextBody(', 1)[1].split('private fun AssistantWorkflow', 1)[0]
        for token in ('rememberUpdatedState(projection)', 'rememberUpdatedState(reveal)',
                      'rememberUpdatedState(generationComplete)', 'return remember(plainNarration) { { text, modifier ->',
                      'currentProjection.value?.blocks?.get(text.text)', 'currentReveal.value', 'currentComplete.value'):
            self.assertIn(token, renderer)

    def test_workflow_and_items_keep_exact_equality_and_identity_gates(self):
        source = (CHAT / 'AssistantWorkflowContent.kt').read_text()
        for token in ('val work = remember(ordered.work) { ordered.work }',
                      'val steps = remember(ordered.steps) { ordered.steps }',
                      'val narration = remember(ordered.plainNarration) { ordered.plainNarration }'):
            self.assertIn(token, source)
        content = (CHAT / 'MessageContent.kt').read_text()
        self.assertIn('key(source.workflowKey(index)) { rememberWorkflowItem(source) }', content)
        self.assertIn('rememberWorkflowItem(item: MessageContentUi): MessageContentUi = remember(item) { item }', content)
        self.assertIn('key(block.step.owner, block.step.toolCallId)', content)
        self.assertEqual(1, content.count('dedicatedTool(block.step)'))

    def test_chevron_frames_are_read_only_in_graphics_layer(self):
        reasoning = (CHAT / 'ReportedReasoningCard.kt').read_text()
        settings = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings/SettingsDisclosureMotion.kt').read_text()
        for source, name in ((reasoning, 'chevronRotation'), (settings, 'rotation')):
            self.assertIn(f'val {name} = animateFloatAsState(', source)
            self.assertNotIn(f'val {name} by animateFloatAsState(', source)
            self.assertIn(f'graphicsLayer {{ rotationZ = {name}.value }}', source)
        self.assertIn('targetValue = if (expanded) 180f else 0f', reasoning)
        self.assertIn('targetValue = if (expanded) expandedDegrees else 0f', settings)
        self.assertIn('ReasoningDisclosureDurationMillis = 220', reasoning)
        self.assertIn('SettingsDisclosureDurationMillis = 220', settings)


if __name__ == '__main__':
    unittest.main()
