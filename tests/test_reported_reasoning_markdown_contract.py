from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"


class ReportedReasoningMarkdownContractTest(unittest.TestCase):
    def test_summary_uses_background_projection_and_shared_renderer(self):
        source = (CHAT / "ReportedReasoningCard.kt").read_text()
        self.assertIn("rememberMarkdownProjection(listOf(summary.text))", source)
        self.assertIn("ProjectedMessageMarkdown(", source)
        self.assertIn("projection?.blocks?.get(summary.text)", source)
        self.assertNotRegex(source, r"\bText\(it\.text")
        self.assertNotIn("MarkdownParser.parse", source)

    def test_summary_state_and_code_completion_are_bound_to_their_owner(self):
        source = (CHAT / "ReportedReasoningCard.kt").read_text()
        self.assertIn("key(ownerKey, summary.itemId)", source)
        self.assertIn("generationComplete = generationComplete", source)
        caller = (CHAT / "ChatMessageList.kt").read_text()
        card = caller.split("ReportedReasoningCard(", 1)[1].split("if (turn.content", 1)[0]
        self.assertIn("generationComplete = turn.status != RunStatus.RUNNING", card)

    def test_plain_progress_does_not_install_disabled_button_semantics(self):
        source = (CHAT / "ReportedReasoningCard.kt").read_text()
        self.assertNotIn("conversationClickable(enabled = hasSummary)", source)
        self.assertIn("if (hasSummary) Modifier.conversationClickable", source)
        self.assertIn("semantics(mergeDescendants = true)", source)

    def test_answer_fallback_does_not_duplicate_active_card_status(self):
        source = (CHAT / "ChatMessageList.kt").read_text().split("private fun AssistantTextContent", 1)[1]
        fallback = source.split('Text("正在思考…"', 1)[0]
        self.assertIn("!isReportedReasoningActive(", fallback)
        self.assertIn("turn.reportedReasoning, turn.isStreaming", fallback)

    def test_restoration_prepares_reasoning_with_the_existing_budget(self):
        source = (CHAT / "RestoredMarkdown.kt").read_text()
        self.assertIn("message.reportedReasoning", source)
        self.assertIn("hasReportedReasoningText", source)
        self.assertIn("cache.preparationSources(restoredMarkdownSources(messages))", source)

    def test_new_component_regressions_cover_rich_stream_and_identity(self):
        tests = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ReportedReasoningMarkdownTest.kt"
        source = tests.read_text() if tests.exists() else ""
        for case in ("testRichHistoricalSummaryRendersMarkdownWithoutChangingAnswer",
                     "testColdAndAppendedSummaryNeverExposeRawMarkup",
                     "testOwnerAndItemChangesDoNotBorrowPreviousFormattedSummary"):
            self.assertIn(case, source)


if __name__ == "__main__":
    unittest.main()
