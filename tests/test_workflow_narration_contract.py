"""Wiring guards; actual frame/feedback behavior is exercised by composition tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class WorkflowNarrationContract(unittest.TestCase):
    def test_pending_never_enters_either_reveal_owner(self):
        workflow = (BASE / 'AssistantWorkflowContent.kt').read_text()
        chat = (BASE / 'ChatMessageList.kt').read_text()
        self.assertIn('val split = lastStep + 1', workflow)
        self.assertIn('if (work.running) AssistantWorkflowContent(work, steps, emptyList(), pending = tail)', workflow)
        self.assertIn('if (work?.running == true) AssistantWorkflowContent(work, steps, emptyList(), narration, pending = body)', workflow)
        self.assertIn('ordered.body.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }', chat)
        self.assertIn('steps.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }', chat)
        self.assertNotIn('ordered.pending.filterIsInstance', chat)
        self.assertIn('ordered.pending.isEmpty() && contentReady', chat)
        self.assertIn('live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty()', chat)

    def test_real_source_identity_and_all_trailing_text_items_are_retained(self):
        mapper = (BASE / 'ChatTurnMapper.kt').read_text()
        self.assertIn('WorkSegmentUi(narration = block.text, narrationId = block.itemId)', mapper)
        self.assertIn('val lastText = workflowFinalTextIndex(message)', mapper)
        self.assertIn('lastText >= 0 && index >= lastText', mapper)
        self.assertIn('finalContent = finalContent', mapper)
        self.assertNotIn('if (hasWorkflow && message.status == RunStatus.RUNNING) -1', mapper)

    def test_confirmed_narration_uses_existing_budget_and_actual_visible_callback(self):
        narration = (BASE / 'WorkflowNarration.kt').read_text()
        chat = (BASE / 'ChatMessageList.kt').read_text()
        self.assertIn('rememberMarkdownReveal(sources, projection', narration)
        self.assertIn('enabled = expanded && !stopped && !paused', narration)
        self.assertIn('onVisibleAdvance = onVisibleAdvance', narration)
        self.assertIn('allowBaselinePrefix = false, blockedSources = blocked', narration)
        self.assertIn('terminal = !running && projection?.sources == texts', narration)
        self.assertIn('val reveal = key(expanded)', narration)
        self.assertIn('!reasoningReady(item.reported)', narration)
        self.assertIn('currentReveal.value.visibility(text.itemId)', chat)
        self.assertIn('boundaries.end(visibility.value)', chat)
        self.assertIn('Text(text.text.take(end)', chat)
        self.assertNotIn('HapticEngine', narration)
        self.assertNotIn('TouchHaptics', narration)

    def test_automatic_close_waits_for_narration_but_manual_close_can_finish(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        card = (BASE / 'WorkProcessCard.kt').read_text()
        self.assertIn('workflowTextSettled = !disclosureExpanded || textSources.isEmpty() || settledPhase == textPhase', chat)
        self.assertIn('if (narration.reveal.settled) settledPhase = textPhase', chat)
        self.assertIn('finishing && reasoningReady && workflowTextSettled', card)
        self.assertIn('val expanded = currentOverride ?: (!stopped && !finished)', card)
        self.assertIn('fullyClosed && !contentMounted', card)
        self.assertIn('workflowTimeline = true', chat)


if __name__ == '__main__':
    unittest.main()
