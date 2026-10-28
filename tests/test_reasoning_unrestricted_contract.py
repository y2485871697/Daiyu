"""Seven explicit text choices stay available; API errors retain their existing boundary."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
class UnrestrictedReasoningContract(unittest.TestCase):
    def test_model_names_and_capabilities_do_not_filter_or_reject_choices(self):
        source = (SRC / "delegation/SubAgentModelSource.kt").read_text()
        choices = source.split("fun textChoices(", 1)[1].split("fun fromWireValue", 1)[0]
        self.assertIn("entries.filter { it != DEFAULT }", choices)
        self.assertNotIn("gpt-", choices)
        policy = (SRC / "model/ReasoningRequestPolicy.kt").read_text()
        self.assertNotIn("textChoices(", policy)
        self.assertNotIn("reasoningOverride == false", policy)
        bindings = (SRC / "delegation/SavedSubAgentRuntimeBindings.kt").read_text()
        self.assertNotIn("model.reasoningMandatory", bindings)
        self.assertIn("ReasoningRequestPolicy.freeze(childBase, effort)", bindings)
    def test_oauth_does_not_rewrite_explicit_efforts(self):
        source = (SRC / "model/oauth/CodexRequestPolicy.kt").read_text()
        self.assertNotIn('"none" ->', source)
        self.assertNotIn('"minimal" ->', source)
        self.assertNotIn('"max" ->', source)
        self.assertIn('!reasoning.has("effort")', source)
        self.assertIn('reasoning.optString("effort") != "none"', source)
    def test_provider_rejections_keep_actual_request_context(self):
        for name in ("ResponsesModel", "MessagesModel", "NativeCompletionModel"):
            source = (SRC / f"model/{name}.kt").read_text()
            self.assertIn("ProviderHttpFailure.from(conn, status, cancelled::get, request = ", source)
    def test_previously_restricted_gpt_is_the_real_picker_fixture(self):
        source = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ChatThinkingPickerActivityTest.kt").read_text()
        self.assertIn('model = "gpt-6.1-sol", reasoningOverride = false', source)
        self.assertIn('awaitDialogEnter(node("chat-thinking-title"))', source)
        self.assertIn('awaitNodeWindowFocus(node("chat-thinking-title"))', source)
if __name__ == "__main__": unittest.main()
