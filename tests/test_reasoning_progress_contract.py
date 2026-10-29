from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
DOMAIN = ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain"


class ReasoningProgressContractTest(unittest.TestCase):
    def test_responses_partial_snapshots_are_ongoing_not_finals(self):
        source = (APP / "model/ResponsesStream.kt").read_text()
        partial = source.split("fun summary(event:", 1)[1].split("fun summaryItem(", 1)[0]
        self.assertIn("ReasoningDelta(id,combined, cumulative = true)", partial)
        self.assertNotIn("ReasoningFinal(", partial)
        self.assertIn("ReasoningFinal(id,combined)", source.split("fun summaryItem(", 1)[1])

    def test_runner_replaces_cumulative_text_without_finishing_the_item(self):
        source = (DOMAIN / "ChatRunner.kt").read_text()
        branch = source.split("is ModelEvent.ReasoningDelta ->", 1)[1].split("is ModelEvent.ReasoningFinal ->", 1)[0]
        self.assertIn("final = event.cumulative", branch)
        self.assertIn("final = false", branch)
        self.assertIn("replace = event.cumulative", branch)
        history = (DOMAIN / "RunnerHistorySegments.kt").read_text()
        self.assertIn("if (replace) text else", history)
        self.assertIn("if (final) next.frozen(now) else next", history)

    def test_budget_and_haptics_consume_the_same_replacement_semantics(self):
        budget = (APP / "context/SummaryOutputBudget.kt").read_text()
        haptics = (APP / "haptics/HapticsActivityHost.kt").read_text()
        self.assertIn("if (event.cumulative) event.text else", budget)
        self.assertIn("if (event.cumulative) event.text else old + event.text", haptics)

    def test_disclosure_completion_invalidates_live_override_and_body_collapses(self):
        source = (APP / "ui/screens/chat/conversation/ReportedReasoningCard.kt").read_text()
        self.assertIn("rememberSaveable(ownerKey, disclosureActive)", source)
        self.assertIn("overridePhase == disclosureActive", source)
        body = source.split('testTag("reported-reasoning-content:$ownerKey")', 1)[1]
        self.assertIn('conversationClickable(onClickLabel = "收起思考摘要")', body)
        self.assertIn("expandedOverride = false", body)

    def test_timer_stays_local_and_uses_compact_tenths(self):
        source = (APP / "ui/screens/chat/conversation/ReportedReasoningCard.kt").read_text()
        self.assertIn("100_000_000L", source)
        self.assertIn('Text("${duration / 1_000}.${duration / 100 % 10}s",', source)
        self.assertLess(source.index("ReasoningElapsedTime(ownerKey"), source.index("Icon(ChatPreviewIcons.Chevron"))


if __name__ == "__main__":
    unittest.main()
