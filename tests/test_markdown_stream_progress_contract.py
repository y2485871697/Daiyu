"""Integration wiring; real stream scheduler JVM cases live in MarkdownProjectionTest."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'

class MarkdownStreamProgressContract(unittest.TestCase):
    def test_streaming_and_terminal_share_reveal_state_but_not_parser_cache_entries(self):
        base = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"
        ui = (base / "MessageMarkdown.kt").read_text()
        typing = (base / "MarkdownTyping.kt").read_text()
        projection = (base / "MarkdownProjection.kt").read_text()
        self.assertIn("streaming && configuredParser === defaultMarkdownParser", ui)
        self.assertIn("internal val streamingMarkdownParser:", projection)
        self.assertIn("parser === streamingMarkdownParser", projection)
        self.assertIn("target.parts == parts", typing)
        self.assertIn("baselineLength != null", typing)
        self.assertIn("remember(identity, resetEpoch) { MarkdownTypingState(baseline) }", typing)

    def test_stream_queue_finishes_running_parse_and_conflates_pending_appends(self):
        source = (BASE / 'MarkdownProjection.kt').read_text()
        for token in ['Channel.CONFLATED', 'worker?.cancelAndJoin()',
                      '!isMarkdownAppend(previous!!, sources)',
                      'projectMarkdown(snapshot, parser, cache, published)', 'publish(projection)', 'published = projection']:
            self.assertIn(token, source)
        ui = (BASE / 'MessageMarkdown.kt').read_text()
        self.assertIn('collectMarkdownProjections(snapshotFlow { requested }, parser)', ui)
        self.assertNotIn('(cached, sources, parserIdentity)', ui)

    def test_render_and_typing_use_the_completed_snapshot_but_readiness_stays_exact(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        for token in ['val contentReady = projection?.sources == sources',
                      'turn.status != RunStatus.RUNNING && contentReady',
                      'content.copy(text = projection.sources.getOrNull(textIndex++) ?: "")',
                      'MessageContent(displayedTurn.content',
                      'AssistantTextContent(displayedTurn, actions, projection, reveal, generationComplete']:
            self.assertIn(token, chat)
        typing = (BASE / 'MarkdownTyping.kt').read_text()
        self.assertIn('MarkdownTypingSource(id, parsed.sources[index])', typing)
        self.assertIn('partIds.take(parsed.sources.size).mapIndexed', typing)
        self.assertIn('parsed.blocks.getValue(displayed.text)', typing)
        self.assertIn('projection?.sources == sources.map { it.text }', typing)
        reasoning = (BASE / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('displayed, projection?.blocks?.get(displayed)', reasoning)
        self.assertIn('generationComplete && projection != null && displayed == summary.text', reasoning)

    def test_resume_follow_accepts_displayed_streaming_prefix_without_relaxing_exact_readiness(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        self.assertEqual(2, chat.count('if (tail.isStreaming) displayedTurns[tail.listKey] != tail'))
        self.assertIn('onContentDisplayed(hasDisplayBody || contentReady)', chat)
        self.assertIn('onContentDisplayed(false)', chat)
        typing = (BASE / 'MarkdownTyping.kt').read_text()
        self.assertIn('remember(baselineProjection, baseline, sources == baseline, allowBaselinePrefix)', typing)
        self.assertIn('markdownTypingBaselineLength(baselineProjection, baseline.map { it.text }, allowPrefix = sources == baseline && allowBaselinePrefix)', typing)
        self.assertIn('it.sources == baseline || (allowPrefix && isMarkdownAppend(it.sources, baseline))', typing)
        self.assertIn('projection?.sources == sources.map { it.text } && baselineLength != null', typing)
        self.assertIn('parsed.sources.sumOf', typing)

    def test_real_regressions_cover_bounded_progress_replacement_and_ui_publication(self):
        unit = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownProjectionTest.kt').read_text()
        for token in ['streamingAppendsPublishTheRunningSnapshotAndOnlyTheLatestPendingOne',
                      'repeat(200)', 'assertEquals(2, seen.size)',
                      'replacementCancelsTheOldSnapshotWithoutPublishingItsLateCompletion']:
            self.assertIn(token, unit)
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageMarkdownTest.kt').read_text()
        self.assertIn('testStreamingAppendsDisplayCompletedSnapshotsWhileNewerParsesRemainBlocked', android)
        self.assertIn('assertEquals(1L, releaseThird.count)', android)
        self.assertIn('assertFalse(ready.get()); assertFalse(settled.get())', android)
