"""Wiring for clamped body reveal; behavior is exercised by JVM and Android tests."""
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class MarkdownBodyVisibilityContract(unittest.TestCase):
    def test_native_input_observes_constraints_not_candidate_and_hint_reads_do_not_subscribe_measure(self):
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        leaf = ui.split('internal fun ClearanceText(', 1)[1].split('internal fun markdownLineSpace', 1)[0]
        frame = ui.split('private class MarkdownTextFrame {', 1)[1].split('@Composable', 1)[0]
        self.assertIn('var measureConstraints by mutableStateOf<Constraints?>(null)', frame)
        native = leaf.split('val displayedText by remember(', 1)[1].split('val candidateText =', 1)[0]
        self.assertIn('frame.measureConstraints?.let { constraints ->', native)
        self.assertIn('constraints = constraints.copy(minHeight = 0)', native)
        self.assertNotIn('frame.layout', native)
        self.assertIn('if (prepared != null && clearance.fits(coordinates, prepared.size.height)) latestCandidateText', native)
        self.assertIn('else frame.committed?.layoutInput?.text ?: latestCandidateText', native)
        self.assertIn('frame.drawn?.layoutInput?.text ?: AnnotatedString("")', native)
        measure = leaf.split('BasicText(displayedText, modifier.layout { measurable, constraints ->', 1)[1].split('.onPlaced(::placed)', 1)[0]
        self.assertIn('frame.layout = candidate\n            frame.measureConstraints = candidate.layoutInput.constraints', measure)
        self.assertIn('val drawn = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { frame.drawn }', measure)
        draw = leaf.split('.drawWithContent {', 1)[1]
        self.assertIn('drawContent()\n                frame.drawn = actual\n                prepareNextLine()', draw)
        self.assertIn('actual.size.height <= available + .01f', draw)
        self.assertIn('publication?.setPending(owner, !published())', draw)

    def test_body_and_thought_both_defer_frame_reads_to_markdown_leaves(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        reasoning = (BASE / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('generationComplete = currentComplete.value && displayed == text.text, deferBlockReveal = true,', chat)
        self.assertIn('deferBlockReveal = true', reasoning)
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        self.assertIn('visibility = reveal.visibility(partId)', ui)
        self.assertIn(
            'else ProjectedMessageMarkdown(text, blocks, presented, reveal.limit(partId), generationComplete, sourceWindow = sourceWindow,\n'
            '            onCurrentBodyInstalled = { MarkdownPaintApplied(reveal, partId) })',
            ui,
        )

    def test_text_leaf_pairs_each_accepted_source_with_its_own_ast(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        for token in ('rememberAssistantTextBody(emptySet(), bodySources, projection, reveal, generationComplete)',
                      'rememberAssistantTextBody(plainNarration, textSources, narration.projection, narration.reveal, generationComplete)',
                      'currentSources.value.indexOfFirst { it.id == text.itemId }',
                      'accepted?.sources?.getOrNull(index)?.takeIf { text.text.startsWith(it) }',
                      'RevealedMessageMarkdown(displayed, accepted?.blocksAt(index)',
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
        self.assertIn('previous == null -> baselineLength.coerceAtMost(total)', motion)
        self.assertNotIn('closed && !paceTerminal', motion)

    def test_received_answer_obeys_layout_admission_without_a_synthetic_rate(self):
        motion = (BASE / 'MarkdownReveal.kt').read_text()
        self.assertIn('val end = maxOf(previous, expected.boundary(limit))', motion)
        self.assertIn('(rate * seconds / width).coerceIn(0.0, 1.0)', motion)
        self.assertIn('desiredRate = rate', motion)
        self.assertIn('lastFrame = 0L', motion)
        self.assertIn('resuming = true', motion)
        self.assertNotIn('position = end.toDouble()', motion)
        self.assertIn('lastAdvanceFrame = frameNanos', motion)
        self.assertIn('onAdvance()', motion)
        self.assertIn('val next = expected.next(previous)', motion)
        self.assertIn('val phase = position % 1.0 + step', motion)
        self.assertNotIn('val speed = 70.0', motion)
        self.assertNotIn('250_000_000.0', motion)
        device = (Path(__file__).resolve().parents[1] / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ReasoningWorkflowTest.kt').read_text()
        for token in ('testPureFinalBodyFollowsReceivedOutputWhenCompletionArrives',
                      'testCompletedHistoryDoesNotReplayTypewriterOrFeedback',
                      'No answer text may overlap the closing workflow'):
            self.assertIn(token, device)

    def test_container_passes_state_without_subscribing_to_the_frame(self):
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        blocks = ui.split('private fun MarkdownBlocks(', 1)[1].split('private fun MarkdownBlockBody(', 1)[0]
        self.assertNotIn('visibility.value', blocks)
        self.assertIn('rememberMarkdownVisibility(visibility, offset, lengths[index])', blocks)
        self.assertIn('MarkdownBlockBody(block, openLink, budget, generationComplete, paintOffset + offset)', blocks)
        leaf = ui.split('private fun MarkdownBlockBody(', 1)[1].split('private fun MarkdownListItem(', 1)[0]
        self.assertIn('derivedStateOf { visibility.value > 0 }', leaf)
        self.assertIn('MarkdownBlocks(block.blocks, openLink, visibility, generationComplete, paintOffset)', leaf)
        self.assertIn('MarkdownInlineText(block.content, openLink, visibility = visibility, paintOffset = paintOffset)', leaf)

    def test_next_received_line_is_only_a_space_hint_not_native_or_paint_input(self):
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        clearance = ui.split('internal fun ClearanceText(', 1)[1].split('internal fun TextLayoutResult.canPaintPrefixOf', 1)[0]
        for token in ('val candidateText = if (playback?.paused == true) displayedText else text',
                      'frame.layout = candidate', 'val future = nextLineText?.let {',
                      'it.lineCount > candidate.lineCount', '(it.size.height - candidate.size.height).coerceAtLeast(0)',
                      'if (playback?.paused != true &&', 'clearance.prepareLine(owner, 0)',
                      'BasicText(displayedText, modifier.layout',
                      'actual.size.height <= available + .01f',
                      'actual.layoutInput.text == latestCandidateText'):
            self.assertIn(token, clearance)
        publication = clearance.split('fun published(): Boolean =', 1)[1].split('fun placed(', 1)[0]
        self.assertIn('latestCandidateText.isEmpty()', publication)
        self.assertNotIn('text.isEmpty()', publication)
        self.assertNotIn('== text', publication)
        self.assertNotIn('frame.layout = future', clearance)
        self.assertNotIn('BasicText(nextLineText', clearance)
        self.assertIn('maxLines = candidate.lineCount + 1', clearance)
        self.assertNotIn('getLineBottom(', clearance)
        future = clearance.split('val future = nextLineText?.let {', 1)[1].split('val drawn = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { frame.drawn }', 1)[0]
        self.assertIn('lineMeasurer.measure(it, style, softWrap = softWrap, maxLines = candidate.lineCount + 1,', future)
        self.assertIn('constraints = constraints.copy(minHeight = 0)', future)
        self.assertIn('(it.size.height - candidate.size.height).coerceAtLeast(0)', future)
        self.assertNotIn('val bottomPadding =', clearance)
        self.assertIn('val drawn = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { frame.drawn }\n                ?.takeIf { it.canPaintPrefixOf(candidate) && it.layoutInput.text.isNotEmpty() }', clearance)
        self.assertIn('frame.nextAdvance = future?.takeIf { it.lineCount > candidate.lineCount }', clearance)
        self.assertIn('(frame.nextAdvance == 0 || drawn == null || candidate.size.height > drawn.size.height)', clearance)
        self.assertIn('fun prepareNextLine()', clearance)
        self.assertIn('clearance.bodyEndInLeaf(frame.coordinates), drawn?.size?.height?.toFloat()', clearance)
        prepare = clearance.split('fun prepareNextLine()', 1)[1].split('fun placed(', 1)[0]
        self.assertIn('if (playback?.paused == true) return', prepare)
        self.assertIn('it.canPaintPrefixOf(candidate)', prepare)
        self.assertNotIn('drawn?.getLineBottom(', prepare)
        placed = clearance.split('fun placed(', 1)[1].split('val refreshAdmission', 1)[0]
        self.assertIn('prepareNextLine()', placed)
        draw = clearance.split('.drawWithContent {', 1)[1]
        self.assertLess(draw.index('frame.drawn = actual'), draw.index('prepareNextLine()'))
        tail = (BASE / 'ChatMessageList.kt').read_text()
        body_end = tail.split('fun bodyEndInLeaf(', 1)[1].split('fun whollyAboveViewport', 1)[0]
        self.assertIn('val current = viewportGeometry ?: return null', body_end)
        self.assertIn('- current.offset + current.height -', body_end)
        self.assertNotIn('contentHeight', body_end)
        line_space = ui.split('internal fun markdownLineSpace(', 1)[1].split('internal fun TextLayoutResult.canPaintPrefixOf', 1)[0]
        self.assertIn('if (bodyEnd == null || drawnBottom == null) 0', line_space)
        self.assertIn('kotlin.math.ceil((nextAdvance - (bodyEnd - drawnBottom).coerceAtLeast(0f)).coerceAtLeast(0f)).toInt()', line_space)


    def test_table_cell_budget_and_visible_semantics_are_preserved(self):
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        row = ui.split('private fun MarkdownTableRow(', 1)[1].split('private fun headingSize', 1)[0]
        self.assertIn('derivedStateOf { visibility.value > offsets[0] }', row)
        self.assertIn('rememberMarkdownVisibility(visibility, offsets[column], lengths[column])', row)
        self.assertIn('visibility = budget, paintOffset = paintOffset + offsets[column])', row)
        inline = ui.split('private fun MarkdownInlineText(', 1)[1].split('private fun MarkdownBlocks(', 1)[0]
        self.assertIn('ClearanceText(visible, paintOffset, modifier, style,', inline)
        self.assertIn('if (end < body.length) body else null', inline)
        self.assertNotIn('body.subSequence(0, boundaries.next(end))', inline)
        self.assertIn('nextLineText = nextLineText', inline)
        self.assertNotIn('nextLineText = body.takeIf', inline)
        self.assertIn('if (limit <= 0) { Box(modifier); return }', inline)
        clearance = ui.split('internal fun ClearanceText(', 1)[1].split('internal fun TextLayoutResult.canPaintPrefixOf', 1)[0]
        self.assertIn("val latestCandidateText by rememberUpdatedState(text)", clearance)
        native_input = clearance.split('val displayedText by remember(', 1)[1].split('val candidateText =', 1)[0]
        for token in ('derivedStateOf {',
                      'if (playback?.paused == true) frame.drawn?.layoutInput?.text ?: AnnotatedString("")',
                      'frame.coordinates?.takeIf { it.isAttached }',
                      'measurer.measure(latestCandidateText, style, softWrap = softWrap,',
                      'frame.measureConstraints?.let { constraints ->',
                      'constraints = constraints.copy(minHeight = 0)',
                      'clearance.fits(coordinates, prepared.size.height)',
                      'else frame.committed?.layoutInput?.text ?: latestCandidateText'):
            self.assertIn(token, native_input)
        for forbidden in ('frame.committed =', 'frame.drawn =', 'frame.rendered =',
                          'publication?.setPending', 'painted[paintOffset] ='):
            self.assertNotIn(forbidden, native_input)
        self.assertIn('BasicText(displayedText', clearance)
        self.assertIn('clearance.fits(coordinates, candidate.size.height)', clearance)
        self.assertIn('clearance.fits(frame.coordinates, actual.size.height)', clearance)
        self.assertIn('publication?.setPending(owner, false)', clearance)
        self.assertNotIn('drawText(', clearance)
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
