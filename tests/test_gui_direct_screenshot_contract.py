from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
GUI = ROOT / "app/src/main/kotlin/app/daiyu/android/gui"


def section(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))]


class GuiDirectScreenshotContractTest(unittest.TestCase):
    def test_explicit_screenshot_routes_without_node_observation(self):
        text = (GUI / "GuiRunSession.kt").read_text()
        body = section(text, "internal fun dynamicScreenshot(", "    fun observe(")
        self.assertIn("GuiReadApproval(0, 1)", body)
        self.assertIn("GuiRuntime.currentWindowScreenshot(lease, captureCancelled)", body)
        self.assertIn("GuiRuntime.screenshot(lease, observationId)", body)
        self.assertNotIn("GuiRuntime.observe(", body)
        self.assertNotIn("supportsVisualFallback", body)

    def test_current_window_read_does_not_sample_or_publish_nodes(self):
        text = (GUI / "GuiAccessibilityService.kt").read_text()
        body = section(text, "internal fun currentWindowScreenshot(", "    private fun captureScreenshot(")
        for forbidden in ("sampleTree(", "observe(", "gate.publish(", "getChild(", "GuiObservation("):
            self.assertNotIn(forbidden, body)
        for required in ("externalRoot(", "gate.retireObservation(lease); clearNodes()",
                         "supportsVisualFallback(lease, denied.code)", "visualScreenshot(lease, cancelled)",
                         "gate.consumeRead(lease, screenshot = true)", "validateCallback = ::validateWindow",
                         "validateResult = ::validateWindow", "cancelled()", "windowReadGeneration != version"):
            self.assertIn(required, body)

    def test_default_observation_stays_node_first(self):
        text = (GUI / "GuiRunSession.kt").read_text()
        body = section(text, "internal fun dynamicScreen(", "internal fun dynamicLaunch(")
        self.assertLess(body.index("GuiRuntime.observe("), body.index("GuiRuntime.visualScreenshot("))
        self.assertIn("GuiScreenRead.Nodes(observed.value)", body)
        self.assertIn("GuiRuntime.supportsVisualFallback(lease, observed.code)", body)

    def test_bound_capture_keeps_its_validation_and_shared_drain(self):
        text = (GUI / "GuiAccessibilityService.kt").read_text()
        body = section(text, "internal fun screenshot(lease:", "    private data class CaptureWindow")
        self.assertIn("validateScreenshot(lease, observationId)", body)
        self.assertIn("validateObservation(lease, observationId", body)
        self.assertIn("validateResult = ::validateCapture", body)
        capture = text[text.index("    private fun captureScreenshot("):]
        for required in ("GuiCaptureRequest<GuiScreenshot>", "frame.dispose(request)",
                         "val rejection = validateCallback()", "val invalid = validateResult()"):
            self.assertIn(required, capture)
