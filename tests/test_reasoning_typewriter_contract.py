"""Source wiring guards; actual frame behavior also has Android regression coverage."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'

class ReasoningTypewriterContract(unittest.TestCase):
    def test_reasoning_uses_same_reveal_and_formatted_leaf(self):
        source = (CHAT / 'ReportedReasoningCard.kt').read_text()
        for token in ['val reveal = key(revealGate)', 'rememberMarkdownReveal(', 'MarkdownTypingSource(summary.itemId, text, sourceWindow)',
                      'RevealedMessageMarkdown(', 'display.reveal, summary.itemId',
                      'resetEpoch = revealResetEpoch', 'onVisibleAdvance = onVisibleAdvance']:
            self.assertIn(token, source)
        self.assertNotIn('ProjectedMessageMarkdown(', source)
        timer = source.split('internal fun rememberReasoningElapsed(', 1)[1].split('internal fun hasReportedReasoningText', 1)[0]
        self.assertIn('delay(100L)', timer)
        self.assertNotIn('withFrameNanos', timer)
        for forbidden in ['TouchHaptics', 'Vibrator', 'HapticsRuntime']:
            self.assertNotIn(forbidden, source)

    def test_unified_and_fallback_hosts_forward_feedback_and_resume(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()

        def section(text, start, end):
            self.assertIn(start, text)
            tail = text.partition(start)[2]
            self.assertIn(end, tail)
            return tail.partition(end)[0]

        outer = section(source, 'WorkProcessCard(work, ownerKey,', 'content = { expanded ->')
        self.assertIn('revealResetEpoch = revealResetEpoch', outer)
        self.assertIn('onVisibleAdvance = onVisibleAdvance', outer)
        reasoning = section(source, 'reasoningBody = { reported, connector ->', 'entryBody = { key, body ->')
        self.assertIn('ReasoningStepRow(ownerKey, reported, connector,', reasoning)
        self.assertIn('revealResetEpoch = revealResetEpoch', reasoning)
        self.assertIn('onVisibleAdvance = { if (expanded) onVisibleAdvance() }', reasoning)
        self.assertIn('onHandoffReady = { if (it) handoff.complete(reported) }', reasoning)
        fallback = section((CHAT / 'WorkProcessCard.kt').read_text(),
                           'ReasoningStepRow(ownerKey, reported,', 'if (!stopped && segment.reasoning')
        self.assertIn('revealResetEpoch = revealResetEpoch', fallback)
        self.assertIn('onVisibleAdvance = { if (expanded) onVisibleAdvance() }', fallback)
        row = (CHAT / 'ReportedReasoningCard.kt').read_text().partition('internal fun ReasoningStepRow(')[2]
        self.assertTrue(row)
        self.assertIn('revealResetEpoch = revealResetEpoch', row)
        self.assertIn('parentExpanded = parentExpanded', row)
        self.assertIn('onVisibleAdvance = onVisibleAdvance', row)
        self.assertIn('onHandoffReady = onHandoffReady', row)

    def test_child_disclosure_resets_baseline_and_parent_identity_survives_exit(self):
        card = (CHAT / 'ReportedReasoningCard.kt').read_text()
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('key(ownerKey, summary.itemId)', card)
        self.assertIn('streaming = isThinking', card)
        self.assertIn('key(ownerKey, "reasoning", reported.itemId)', work)
        self.assertNotIn('key(ownerKey, "reasoning", reported.itemId, expanded)', work)
        self.assertIn('onVisibleAdvance = { if (expanded) onVisibleAdvance() }', work)
        self.assertIn('revealResetEpoch = revealResetEpoch', work)
        content = (CHAT / 'MessageContent.kt').read_text()
        self.assertIn('key(block.workflowKey(index))', content)
        self.assertIn('key(block.step.renderKey)', content)
        self.assertIn('reasoningState.SaveableStateProvider("ordered-content")', work)
        self.assertIn('MessageContent(steps,', (CHAT / 'ChatMessageList.kt').read_text())

    def test_live_empty_item_observes_first_output_before_visibility(self):
        card = (CHAT / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('val summaryDisplays = reported.map', card)
        setup = card.split('val summaryDisplays = reported.map', 1)[1].split('val chevronRotation', 1)[0]
        self.assertIn('summary.text.takeIf(::hasReportedReasoningText).orEmpty()', setup)
        self.assertIn('rememberMarkdownReveal(', setup)
        self.assertIn('val expanded = hasSummary && disclosureExpanded', card)
        self.assertNotIn('key(ownerKey, summary.itemId, isThinking)', card)

    def test_terminal_state_is_independent_of_first_display_backlog(self):
        card = (CHAT / 'ReportedReasoningCard.kt').read_text()
        for token in ('val displayThinking = isThinking',
                      'reasoningDisplayLabel(displayThinking, displayPaused)', 'visualScope?.claimReasoning(ownerKey, summary.itemId)',
                      'ticket.take() && revealGate && !stopped', 'paceTerminal = firstPresentation',
                      'initialBaseline = if (firstPresentation || queuedEmptyBaseline) emptyList() else null',
                      'enabled = revealEnabled && !paused', 'leadingIndicator?.invoke(displayThinking)',
                      'val complete = (generationComplete || reported.activeSinceNanos == null) && !displayThinking'):
            self.assertIn(token, card)
        self.assertNotIn('queuedForArrival', card)
        self.assertIn('val displayThinking = isThinking || (observedLive && !stopped && !paused', card)
        self.assertIn('queuedEmptyBaseline', card)
        typing = (CHAT / 'MarkdownTyping.kt').read_text()
        self.assertIn('completion.value && publication.settled(target)', typing)
        self.assertIn('published[it.source.id] == it.source', typing)
        self.assertNotIn('clearance?.paintPending', typing)
        self.assertIn('val ready: Boolean get() = pending.isEmpty()', typing)
        self.assertIn('if (sourceReady && blocks != null) reveal.presented(partId)', (CHAT / 'MessageMarkdown.kt').read_text())
        self.assertIn('firstPresentation || hasDisplayText || queuedEmptyBaseline && arrived', card)
        scope = (CHAT / 'WorkflowNarration.kt').read_text()
        self.assertIn('reasoningClaims.add(owner to itemId) && !baseline', scope)
        self.assertIn('ReasoningHandoff(if (baseline) initial else emptyList())', scope)
        chat = (CHAT / 'ChatMessageList.kt').read_text()
        resume = chat.split('val resumeReady =', 1)[1].split('Triple(canFollowBottom()', 1)[0]
        self.assertNotIn('revealedTurns', resume)
        self.assertIn('readyTurns[tail.listKey] == tail', resume)
        self.assertIn('resumeFollowPending && !currentNestedTail.textClearance.pacedFollowing', chat)

    def test_body_adapter_and_reasoning_share_one_clock(self):
        typing = (CHAT / 'MarkdownTyping.kt').read_text()
        self.assertIn('return rememberMarkdownReveal(sources, prefixProjection, turn.isStreaming, turn.status != RunStatus.RUNNING && prefix.size == turn.content.size,', ' '.join(typing.split()))
        self.assertIn('motion.update(parts, baselineLength, streaming, targetTerminal, live, paceTerminal)', typing)
        self.assertEqual(1, typing.count('withFrameNanos { frameNanos ->'))
        self.assertIn('val clearance = LocalStreamingTextClearance.current\n'
                      '    val textClearance by rememberUpdatedState(clearance)', typing)
        self.assertIn('shown = when { !markdownTypingCanAdvance(currentCanAdvance, currentPlayback) -> { motion.pause(current) motion.visible(current) } textClearance?.held == true -> { motion.hold(current, frameNanos) motion.visible(current) } currentAwaitPaintAdmission && !publication.ready -> motion.visible(current) else -> motion.advance(current, frameNanos, end, adaptiveBody) { currentAdvance() } }', ' '.join(typing.split()))
        self.assertIn('val currentPlayback by rememberUpdatedState(playback)', typing)
        self.assertIn('canAdvance && playback?.paused != true', typing)
        self.assertEqual(1, typing.count('motion.hold(current, frameNanos)'))
        self.assertIn('rememberUpdatedState(onVisibleAdvance)', typing)

if __name__ == '__main__':
    unittest.main()
