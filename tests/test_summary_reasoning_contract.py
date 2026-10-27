"""Summary-only effort negotiation and failure delivery wiring."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
DOMAIN = ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain"
class SummaryReasoningContract(unittest.TestCase):
    def test_only_summary_factory_enables_exact_reasoning_scope(self):
        factory = (APP / "model/SummaryProviderFactory.kt").read_text()
        self.assertIn("reasoning: SummaryReasoningLevel = SummaryReasoningLevel.OFF", factory)
        self.assertEqual(3, factory.count("summaryRequest = true"))
        for name in ["ResponsesModel.kt", "MessagesModel.kt", "NativeCompletionModel.kt"]:
            code = (APP / "model" / name).read_text()
            self.assertIn("summaryRequest: Boolean = false", code)
            self.assertIn("if (summaryRequest) null else settings.reasoningOverride", code)
    def test_retry_uses_new_admission_and_same_deadline(self):
        code = (APP / "context/ModelContextSummarizer.kt").read_text()
        self.assertEqual(1, code.count("bound.startDeadline()"))
        self.assertIn("requests++ == 0", code)
        self.assertIn("requireNotNull(nextModel)", code)
        self.assertIn("failure.failure == ModelFailure.REQUEST", code)
        self.assertIn("failure.diagnostic?.reasoningLevelRejected == true", code)
        self.assertIn("reasoningIndex < bound.reasoningLevels.lastIndex", code)
        self.assertIn("requests < 64", code)
    def test_other_errors_never_become_reasoning_retries(self):
        parser = (APP / "model/ProviderHttpFailure.kt").read_text()
        self.assertIn("rejection == RequestRejection.UNSUPPORTED_VALUE", parser)
        self.assertNotIn("RequestRejection.UNSUPPORTED_PARAMETER,", parser)
        self.assertIn("reasoningRejected", parser)
        self.assertNotIn("providerMessage", (DOMAIN / "ModelEvent.kt").read_text())
    def test_manual_failure_uses_actual_service_event_without_duplicate_waiting(self):
        service = (APP / "context/ContextService.kt").read_text()
        self.assertIn("terminal = terminal.copy(failure = compressionFailureMessage(failure))", service)
        self.assertIn("finally { onCompression(terminal) }", service)
        controller = (APP / "ChatController.kt").read_text()
        self.assertIn("if (!compressionStarted && !token.cancellation.isCancelled", controller)
        self.assertIn("override fun compressionStarted() { compressionStarted = true }", controller)
        self.assertIn("onStarted = lease::compressionStarted", (APP / "context/ContextManagementWorker.kt").read_text())
    def test_failure_divider_opens_the_failure_not_summary(self):
        divider = (APP / "ui/screens/chat/conversation/ChatTimelineDivider.kt").read_text()
        self.assertIn("entry.failure ?: entry.summary", divider)
        self.assertIn("压缩失败", divider)
        self.assertIn("showDetails = true", divider)
        self.assertIn("(failure as? ModelRequestException)?.userMessage", (DOMAIN / "ChatRunner.kt").read_text())
    def test_failure_is_live_before_the_async_database_writer(self):
        code = (APP / "ChatController.kt").read_text()
        self.assertIn("compressionFailureTimeline[entry.id] = entry", code)
        self.assertLess(code.index("compressionFailureTimeline[entry.id] = entry"), code.index("persistReconnect(entry)"))
        self.assertIn("+ compressionFailureTimeline.values.filter", code)
        self.assertIn("filterNot { it.id in liveIds }", code)
    def test_failed_runtime_seals_boundary_and_reopens_without_tool_replay(self):
        code = (DOMAIN / "ChatRunner.kt").read_text()
        catch = code[code.index("val failedRuntime = runtime ?: base"):]
        catch = catch[:catch.index("return false")]
        self.assertIn("failedRuntime.beginCompaction(compactionBoundary)", catch)
        self.assertIn("segmentHistory.sealSession(current, emptyList())", catch)
        self.assertIn("modelHistory = effectiveHistory()", catch)
        self.assertIn("acceptedContextSegments = sealed.historySegments.size", catch)
        self.assertIn("outputs = emptyList()", catch)
        self.assertIn("reopenAfterCompaction = true", catch)
    def test_codex_preserves_summary_effort_only(self):
        code = (APP / "model/oauth/CodexRequestPolicy.kt").read_text()
        self.assertIn("preserveReasoning: Boolean = false", code)
        self.assertIn("if (!preserveReasoning) when", code)
if __name__ == "__main__":
    unittest.main()
