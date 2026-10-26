"""Source wiring guards; recording-port JVM/Android tests cover runtime policies separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
CHAT = APP / "ui/screens/chat/conversation"

class TypewriterHapticsContract(unittest.TestCase):
    def test_only_actual_motion_growth_calls_latest_callback(self):
        typing = (CHAT / "MarkdownTyping.kt").read_text()
        motion = (CHAT / "MarkdownReveal.kt").read_text().split("fun advance(", 1)[1]
        self.assertIn("rememberUpdatedState(onVisibleAdvance)", typing)
        self.assertIn("motion.advance(current, it) { currentAdvance() }", typing)
        self.assertIn("if (target !== expected) return expected.start", motion)
        self.assertIn("if (shown > previous) onAdvance()", motion)
        self.assertLess(motion.index("if (target !== expected)"), motion.index("onAdvance()"))
        self.assertEqual(1, typing.count("currentAdvance()"))

    def test_latest_viewport_lifecycle_and_occlusion_gate_are_in_callback(self):
        source = (CHAT / "ChatMessageList.kt").read_text()
        self.assertIn("onVisibleAdvance = {", source)
        text = source.split("onVisibleAdvance = {", 1)[1].split("onRevealSettled =", 1)[0]
        for token in ["listState.layoutInfo", "currentOcclusionPx", "scrollResumed", "RunStatus.COMPLETED",
                      "it.key == turn.listKey", "it.size > 0", "it.offset < visibleBottom",
                      "it.offset + it.size > layout.viewportStartOffset", "actions.onVisibleTextAdvance(turn.listKey)"]:
            self.assertIn(token, text)
        self.assertNotIn("setVisibleOutput", text)

    def test_production_host_freezes_owner_and_supports_normal_terminal_drain(self):
        main = (APP / "MainActivity.kt").read_text()
        setup = main.split("private fun chatActions()", 1)[1].split("fun editorMatches()", 1)[0]
        self.assertIn("onVisibleTextAdvance = { key ->", main)
        call = main.split("onVisibleTextAdvance = { key ->", 1)[1].split("onInteractiveModeChange", 1)[0]
        for token in ["renderedRevealOwner", "associateBy { it.listKey }", "hapticsHost?.revealAdvance(renderedConversation)"]:
            self.assertIn(token, setup)
        for token in ["renderedRevealTurns[key]", "active.runId == renderedRevealOwner.runId",
                      "active.conversationId == renderedRevealOwner.conversationId", "controller.isRunUnfenced(active)",
                      "active == null && turn?.status == RunStatus.COMPLETED", "editorGeneration == renderedEditorGeneration",
                      'retainedPage == "chat"', "!sidebarVisible", "renderedRevealAdvance?.invoke()"]:
            self.assertIn(token, call)
        self.assertNotIn("filterIsInstance", call) # no history scan on every reveal frame
        self.assertNotIn("beginRun", call)

    def test_renderer_capability_is_fenced_by_exact_binding_not_mutable_run_lookup(self):
        source = (APP / "haptics/HapticsActivityHost.kt").read_text()
        self.assertIn("fun revealAdvance(", source)
        host = source.split("fun revealAdvance(", 1)[1].split("fun isCurrentSettings", 1)[0]
        for token in ["it.binding.conversationId == conversationId", "!closed", "chat === owner", "feedback.visibleAdvance()"]:
            self.assertIn(token, host)
        for token in ["beginRun", "attach(", "output("]:
            self.assertNotIn(token, host)

    def test_existing_global_throttle_settings_and_background_suppression_remain(self):
        engine = (ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain/haptics/HapticEngine.kt").read_text()
        self.assertIn("if (now - lastTick < 32L) return null", engine)
        self.assertIn("settings.generationOn", engine)
        self.assertIn("backgroundVisible(host.conversation)", engine)
        for name in ["MarkdownTyping.kt", "MarkdownReveal.kt"]:
            text = (CHAT / name).read_text()
            for forbidden in ["Vibrator", "TouchHaptics", "performHapticFeedback", "delay(", "Handler("]:
                self.assertNotIn(forbidden, text)

if __name__ == "__main__":
    unittest.main()
