"""Wiring for clamped body reveal; behavior is exercised by JVM and Android tests."""
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class MarkdownBodyVisibilityContract(unittest.TestCase):
    def test_body_and_thought_both_defer_frame_reads_to_markdown_leaves(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        reasoning = (BASE / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('generationComplete = currentComplete.value && displayed == text.text, deferBlockReveal = true)', chat)
        self.assertIn('deferBlockReveal = true', reasoning)
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        self.assertIn('visibility = reveal.visibility(partId)', ui)
        self.assertIn('else ProjectedMessageMarkdown(text, blocks, modifier, reveal.limit(partId), generationComplete)', ui)

    def test_text_leaf_pairs_each_accepted_source_with_its_own_ast(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        for token in ('rememberAssistantTextBody(emptySet(), bodySources, projection, reveal, generationComplete)',
                      'rememberAssistantTextBody(plainNarration, textSources, narration.projection, narration.reveal, generationComplete)',
                      'currentSources.value.indexOfFirst { it.id == text.itemId }',
                      'accepted?.sources?.getOrNull(index)?.takeIf { text.text.startsWith(it) }',
                      'RevealedMessageMarkdown(displayed, accepted?.blocks?.get(displayed)',
                      'generationComplete = currentComplete.value && displayed == text.text'):
            self.assertIn(token, chat)

    def test_hidden_answer_and_typewriter_share_the_same_release_gate(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        typing = (BASE / 'MarkdownTyping.kt').read_text()
        for token in ('val bodyVisible = answerReleased || canReleaseWithoutWorkflow',
                      'if (bodyVisible) emptySet() else ordered.body.filterIsInstance<MessageContentUi.Text>()',
                      'blockedSources = blockedSources', 'ordered.body.isNotEmpty() && bodyVisible'):
            self.assertIn(token, chat)
        for token in ('takeWhile { it.source.id !in blockedSources }', 'target, canAdvance, advanceLimit',
                      'val canAdvance = enabled && sourcesCompatible',
                      'var shown = motion.visible(current)', 'if (end < current.total) motion.pause(current)'):
            self.assertIn(token, typing)

    def test_live_ownership_and_exact_terminal_are_independent_of_streaming_speed(self):
        typing = (BASE / 'MarkdownTyping.kt').read_text()
        motion = (BASE / 'MarkdownReveal.kt').read_text()
        self.assertIn('live: Boolean = streaming', typing)
        self.assertIn('val targetTerminal = terminal && exactProjection', typing)
        self.assertIn('motion.update(parts, baselineLength, streaming, targetTerminal, live, paceTerminal)', typing)
        self.assertIn('if (live || streaming) { seenLive = true; closed = false }', motion)
        self.assertIn('if (terminal && total > 0 && start >= total) closed = true', motion)

    def test_terminal_answer_is_paced_without_changing_workflow_narration_drain(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        narration = (BASE / 'WorkflowNarration.kt').read_text()
        motion = (BASE / 'MarkdownReveal.kt').read_text()
        self.assertIn('live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty(), paceTerminal = true)', chat)
        self.assertNotIn('paceTerminal = true', narration)
        self.assertIn('expected.streaming || expected.terminal && expected.paceTerminal', motion)
        self.assertIn('else 70.0', motion)
        self.assertIn('250_000_000.0', motion)
        device = (Path(__file__).resolve().parents[1] /
                  'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ReasoningWorkflowTest.kt').read_text()
        for token in ('advanceTimeBy(512)', 'paced.length in firstVisible!!.length + 1..48',
                      'testPureFinalBodyDoesNotRushWhenCompletionArrives',
                      'testCompletedHistoryDoesNotReplayTypewriterOrFeedback'):
            self.assertIn(token, device)

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
