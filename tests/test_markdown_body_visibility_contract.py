"""Wiring for clamped body reveal; behavior is exercised by JVM and Android tests."""
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class MarkdownBodyVisibilityContract(unittest.TestCase):
    def test_only_body_uses_deferred_read_and_thought_entry_is_unchanged(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        reasoning = (BASE / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('generationComplete = currentComplete.value, deferBlockReveal = true)', chat)
        self.assertNotIn('deferBlockReveal', reasoning)
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        self.assertIn('visibility = reveal.visibility(partId)', ui)
        self.assertIn('else ProjectedMessageMarkdown(text, blocks, modifier, reveal.limit(partId), generationComplete)', ui)

    def test_container_passes_state_without_subscribing_to_the_frame(self):
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        blocks = ui.split('private fun MarkdownBlocks(', 1)[1].split('private fun MarkdownBlockBody(', 1)[0]
        self.assertNotIn('visibility.value', blocks)
        self.assertIn('rememberMarkdownVisibility(visibility, offset, lengths[index])', blocks)
        self.assertIn('MarkdownBlockBody(block, openLink, budget, generationComplete)', blocks)
        leaf = ui.split('private fun MarkdownBlockBody(', 1)[1].split('private fun MarkdownListItem(', 1)[0]
        self.assertIn('derivedStateOf { visibility.value > 0 }', leaf)
        self.assertIn('MarkdownBlocks(block.blocks, openLink, visibility, generationComplete)', leaf)
        self.assertIn('MarkdownInlineText(block.content, openLink, visibility = visibility)', leaf)

    def test_table_cell_budget_and_visible_semantics_are_preserved(self):
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        row = ui.split('private fun MarkdownTableRow(', 1)[1].split('private fun headingSize', 1)[0]
        self.assertIn('derivedStateOf { visibility.value > offsets[0] }', row)
        self.assertIn('rememberMarkdownVisibility(visibility, offsets[column], lengths[column])', row)
        self.assertIn('visibility = budget)', row)
        self.assertIn('BasicText(visible, modifier, style)', ui)
        self.assertIn('SelectionContainer {', ui)
        helper = (BASE / 'MarkdownVisibility.kt').read_text()
        self.assertIn('derivedStateOf { (parent.value - start).coerceIn(0, length) }', helper)
