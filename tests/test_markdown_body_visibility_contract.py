"""Wiring for clamped body reveal; behavior is exercised by JVM and Android tests."""
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class MarkdownBodyVisibilityContract(unittest.TestCase):
    def test_body_and_thought_both_defer_frame_reads_to_markdown_leaves(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        reasoning = (BASE / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('generationComplete = currentComplete.value, deferBlockReveal = true)', chat)
        self.assertIn('deferBlockReveal = true', reasoning)
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

    def test_scroll_regressions_exercise_both_paths_with_live_reveal_and_real_pointer_input(self):
        cases = Path(__file__).resolve().parents[1] / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/StreamingScrollTest.kt'
        source = cases.read_text()
        for token in ('verifyScroll(false)', 'verifyScroll(true)', 'ChatMessageList(',
                      'down(Offset(', 'moveTo(Offset(', 'performTouchInput { up() }',
                      'advances.get() > beforeAdvances', 'beforeWalks, walks.get()',
                      'beforeParses, parses.get()', 'scrollValue() < beforeScroll'):
            self.assertIn(token, source)

    def test_scroll_fixture_does_not_collapse_the_clickable_thought_body_before_dragging(self):
        cases = Path(__file__).resolve().parents[1] / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/StreamingScrollTest.kt'
        source = cases.read_text()
        self.assertNotIn('click(center)', source)
        self.assertEqual(2, source.count('down(Offset('))
        self.assertLess(source.index('down(Offset('), source.index('text = appended'))
