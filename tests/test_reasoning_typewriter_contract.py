"""Source wiring guards; actual frame behavior also has Android regression coverage."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'

class ReasoningTypewriterContract(unittest.TestCase):
    def test_reasoning_uses_same_reveal_and_formatted_leaf(self):
        source = (CHAT / 'ReportedReasoningCard.kt').read_text()
        for token in ['val reveal = rememberMarkdownReveal(', 'MarkdownTypingSource(summary.itemId, text)',
                      'RevealedMessageMarkdown(', 'display.reveal, summary.itemId',
                      'resetEpoch = revealResetEpoch', 'onVisibleAdvance = { if (expanded) onVisibleAdvance() }']:
            self.assertIn(token, source)
        self.assertNotIn('ProjectedMessageMarkdown(', source)
        for forbidden in ['TouchHaptics', 'Vibrator', 'HapticsRuntime', 'delay(']:
            self.assertNotIn(forbidden, source)

    def test_all_three_production_paths_forward_feedback_and_resume(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        for start, end in [('if (!integratedReasoning)', 'if (turn.content.isNotEmpty())'),
                           ('reasoningBody =', '}) { text, modifier ->'),
                           ('turn.workProcess?.let', 'if (turn.finalText.isNotBlank())')]:
            section = source.split(start, 1)[1].split(end, 1)[0]
            self.assertIn('revealResetEpoch = revealResetEpoch', section)
            self.assertIn('onVisibleAdvance = onVisibleAdvance', section)
        row = (CHAT / 'ReportedReasoningCard.kt').read_text().split('internal fun ReasoningStepRow(', 1)[1]
        self.assertIn('revealResetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance', row)

    def test_both_disclosures_reset_baseline_and_gate_exit_animation(self):
        card = (CHAT / 'ReportedReasoningCard.kt').read_text()
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('key(ownerKey, summary.itemId, disclosureExpanded)', card)
        self.assertIn('streaming = allowLiveTiming && disclosureExpanded', card)
        self.assertIn('key(ownerKey, "reasoning", reported.itemId, expanded)', work)
        self.assertIn('onVisibleAdvance = { if (expanded) onVisibleAdvance() }', work)
        self.assertIn('revealResetEpoch = revealResetEpoch', work)

    def test_live_empty_item_observes_first_output_before_visibility(self):
        card = (CHAT / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('val summaryDisplays = reported.map', card)
        setup = card.split('val summaryDisplays = reported.map', 1)[1].split('val chevronRotation', 1)[0]
        self.assertIn('summary.text.takeIf(::hasReportedReasoningText).orEmpty()', setup)
        self.assertIn('rememberMarkdownReveal(', setup)
        self.assertIn('val expanded = hasSummary && disclosureExpanded', card)
        self.assertNotIn('key(ownerKey, summary.itemId, isThinking)', card)

    def test_body_adapter_and_reasoning_share_one_clock(self):
        typing = (CHAT / 'MarkdownTyping.kt').read_text()
        self.assertIn('return rememberMarkdownReveal(sources, projection, turn.isStreaming, turn.status != RunStatus.RUNNING,', typing)
        self.assertIn('motion.update(parts, baselineLength, streaming, terminal)', typing)
        self.assertEqual(1, typing.count('withFrameNanos { motion.advance(current, it) { currentAdvance() } }'))
        self.assertIn('rememberUpdatedState(onVisibleAdvance)', typing)

if __name__ == '__main__':
    unittest.main()
