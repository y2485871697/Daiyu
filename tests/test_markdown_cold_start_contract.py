"""Cold-start source wiring; Android regressions are present but run in the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class MarkdownColdStartContract(unittest.TestCase):
    def test_pending_body_is_visible_and_has_nonzero_height_without_raw_fallback(self):
        source = (BASE / 'MessageMarkdown.kt').read_text()
        pending = source.split('internal fun ProjectedMessageMarkdown(', 1)[1].split('internal fun ParsedMessageMarkdown(', 1)[0]
        self.assertIn('if (body == null) MarkdownBodyLoading(modifier, text.isNotBlank())', pending)
        self.assertIn('Box(modifier) {', pending)
        self.assertIn('Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)', pending)
        self.assertIn('testTag("markdown-pending")', pending)
        self.assertIn('正在加载消息…', pending)
        self.assertNotIn('BasicText(text', pending)
        self.assertNotIn('MarkdownParser.parse', pending)
        self.assertNotIn('runBlocking', pending)

    def test_first_pending_completed_turn_has_no_premature_actions(self):
        source = (BASE / 'ChatMessageList.kt').read_text().split('internal fun AssistantTurn(', 1)[1]
        self.assertIn('var hasProjectedBody by remember(MarkdownParserIdentity(LocalMarkdownParser.current))', source)
        self.assertIn('sources.any { it.isNotBlank() } && !hasDisplayBody && !hasProjectedBody', source)
        self.assertIn('if (hasDisplayBody) hasProjectedBody = true', source)
        self.assertIn('turn.status != RunStatus.RUNNING && !initialBodyPending', source)
        self.assertIn('SideEffect { onContentReady(contentReady) }', source)
        self.assertIn('SideEffect { onRevealSettled(reveal.settled) }', source)
        self.assertIn(') = key(turn.listKey)', (BASE / 'ChatMessageList.kt').read_text())

    def test_android_regressions_cover_cold_final_mixed_and_append_paths(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageMarkdownTest.kt').read_text()
        for name in ['testColdCompletedTurnsShowLoadingInsteadOfEmptyBodiesAndActions',
                     'testColdMixedBodyShowsLoadingWithoutPublishingReadOrRawMarkdown',
                     'testColdPendingTurnReplacementCannotBorrowAnotherAnswersBodyOrActions',
                     'testEmptyProjectionDoesNotCountAsPreviouslyDisplayedBody']:
            self.assertIn(name, source)
        self.assertIn('assertHeightIsAtLeast(52.dp)', source)
        self.assertIn('testAppendedBodyKeepsMarkdownGeometryThroughPendingParseAndCompletion', source)
        self.assertIn('testCachedChatReturnIsFormattedAndReadReadyWithoutWaitingForAnotherParse', source)


if __name__ == '__main__':
    unittest.main()
