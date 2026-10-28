"""Model-switch presentation wiring; Android regressions own pixels and input."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
UI = SRC / "ui/screens/chat/conversation"


class ModelSwitchFeedbackContractTest(unittest.TestCase):
    def test_switching_is_distinct_from_disabled_and_keeps_real_input_lock(self):
        text = (UI / "ChatModelPicker.kt").read_text()
        self.assertIn("val switching: Boolean = false", text)
        self.assertIn("val canSelect = state.enabled && !state.loading && !state.switching", text)
        self.assertIn("val visuallyEnabled = (state.enabled || state.switching) && !state.loading", text)
        self.assertIn("!state.enabled && !state.switching ->", text)
        self.assertIn(".alpha(if (visuallyEnabled) 1f else .45f)", text)
        self.assertIn(".selectable(selected = selected, enabled = canSelect", text)

    def test_wrapper_forwards_switching_and_fences_both_provider_callbacks(self):
        text = (UI / "LocalCatalogChatModelPicker.kt").read_text().split("internal fun LocalCatalogChatModelPicker(", 1)[1]
        self.assertIn("switching: Boolean = false", text)
        self.assertIn("switching = switching", text)
        self.assertEqual(2, text.count("if (enabled && !loading && !switching)"))

    def test_host_marks_pending_before_controller_and_rejects_duplicate_selection(self):
        text = (SRC / "MainActivity.kt").read_text()
        render = text.split("else LocalCatalogChatModelPicker(session", 1)[1].split("composerShortcutPick", 1)[0]
        self.assertIn("switching = picker.switching", render)
        method = text.split("private fun selectChatModel(", 1)[1].split("private fun gatewayCurrent", 1)[0]
        self.assertIn("if (picker.switching) return", method)
        pending = method.index("chatModelPicker = picker.copy(switching = true)")
        self.assertLess(pending, method.index("controller.selectActiveProviderCatalogModel("))
        self.assertIn("!controller.taskConfigurationBusy(conversation) && !controller.dataBusy", method)
        self.assertIn("chatModelOwner === owner && chatModelSession === session", method)
        self.assertIn("if (success) { dismissChatModels(); refresh() }", method)

    def test_failure_releases_switching_without_hiding_error_or_reenabling_stale_session(self):
        text = (SRC / "MainActivity.kt").read_text().split("private fun selectChatModel(", 1)[1].split("private fun gatewayCurrent", 1)[0]
        self.assertEqual(2, text.count("copy(enabled = false, switching = false,"))
        self.assertEqual(2, text.count("切换未保存，请关闭后重新打开选择模型"))
        self.assertEqual(2, text.count("chatModelSelectionFailed = true"))

    def test_android_regression_covers_pending_pixels_both_provider_locks_failure_and_real_busy(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatModelPickerLayoutTest.kt").read_text()
        method = text.split("fun testModelSwitchKeepsPixelsAndSuppressesTaskHintWithoutAcceptingMoreSelections()", 1)[1].split("fun testHtmlStructure", 1)[0]
        for part in ("for (dark in listOf(false, true))", "for (enabled in listOf(true, false))",
                     "state.copy(enabled = enabled, switching = true)", "captureScreenNode(node(tag))",
                     "expected[x, y] != actual[x, y]", "assertEquals(callbacksBefore, selected.size)",
                     'node("chat-model-option:b:same").assertIsNotEnabled()',
                     "state.copy(enabled = false, message = failure)", "state.copy(enabled = false)",
                     ".performTouchInput { click(center) }"):
            self.assertIn(part, method)

    def test_all_switch_success_notices_are_silent_but_failures_and_data_gate_stay(self):
        text = (SRC / "ChatController.kt").read_text()
        self.assertNotIn('"模型已切换"', text)
        for name in ("selectProviderCatalogModel", "selectActiveProviderCatalogModel", "selectConfiguredModel", "selectConfiguredCatalogModel"):
            method = text.split("internal fun " + name + "(", 1)[1].split("\n    internal fun ", 1)[0]
            if name == "selectActiveProviderCatalogModel":
                self.assertIn("conversationConfigurationTask(conversation, failureNotice", method)
                self.assertNotIn("dataTask(", method)
            else:
                self.assertIn("dataTask(failureNotice", method)
                self.assertRegex(method, r"\n            null\n        \}\)")
            self.assertRegex(method, r"completed\s*=\s*completed")
        self.assertIn('check(idleAvailable() && !hasLiveChildTasks())', text)
        self.assertIn("dataBusy=true;notifyChanged()", text)
        self.assertIn("try { completed(success) }", text)


if __name__ == "__main__":
    unittest.main()
