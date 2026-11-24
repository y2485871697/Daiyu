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
        self.assertIn("motion.advance(current, frameNanos, end, streamSnapshot)", typing)
        self.assertIn("id !in publication.answerPaint && current.limit(id, end) > current.limit(id, before)", typing)
        self.assertIn("if (target !== expected) return expected.start", motion)
        self.assertIn("if (shown > previous) onAdvance()", motion)
        self.assertLess(motion.index("if (target !== expected)"), motion.index("onAdvance()"))
        document = (CHAT / "StreamingAnswer.kt").read_text()
        self.assertLess(document.index("drawContent()"), document.index("ticks.offer("))
        self.assertIn("if (!frozen && added > 0)", document)
        self.assertIn('val latestAdvance = rememberUpdatedState(reveal.onDocumentAdvance)', document)
        observer = (APP / 'haptics/ControllerHapticsObserver.kt').read_text()
        self.assertIn('activeRun.output(reasoning = false) {', observer)
        self.assertNotIn('activeRun.output((graphemes', observer)
        self.assertIn("{ currentAdvance() }", typing)

    def test_latest_viewport_lifecycle_and_occlusion_gate_are_in_callback(self):
        source = (CHAT / "ChatMessageList.kt").read_text()
        self.assertIn("onVisibleAdvance = {", source)
        text = source.split("onVisibleAdvance = {", 1)[1].split("onRevealSettled =", 1)[0]
        for token in ["listState.layoutInfo", "currentOcclusionPx", "scrollResumed", "RunStatus.COMPLETED",
                      "it.key == turn.listKey", "it.size > 0", "it.offset < visibleBottom",
                      "it.offset + it.size > layout.viewportStartOffset", "actions.onVisibleTextAdvance(turn.listKey)"]:
            self.assertIn(token, text)
        self.assertNotIn("setVisibleOutput", text)

    def test_only_unspent_body_can_forward_after_transport_end(self):
        detached = (CHAT / "DetachedReplyBody.kt").read_text()
        self.assertIn("part.lane.pulses(currentTurn.status)", detached)
        self.assertNotIn("ENDED_REASON -> false", detached)
        self.assertIn("BODY, ENDED_REASON -> status == RunStatus.RUNNING || status == RunStatus.COMPLETED", detached)
        card = (CHAT / "ReportedReasoningCard.kt").read_text()
        self.assertIn("var observedDisplayLive by rememberSaveable(revealGate, revealResetEpoch)", card)
        self.assertIn("previous?.resumePainted(shown)", detached)
        listing = (CHAT / "ChatMessageList.kt").read_text()
        self.assertIn("replyPlayback.control.permitsTerminalFeedback(tailAssistant)) actions.onTerminalTextAdvance", listing)
        self.assertNotIn("!detachedBody.value && replyPlayback?.control?.permitsTerminalFeedback", listing)

    def test_production_host_freezes_owner_and_silences_completed_history(self):
        main = (APP / "MainActivity.kt").read_text()
        setup = main.split("private fun chatActions(onVisibleCompleted:", 1)[1].split("fun editorMatches()", 1)[0]
        self.assertIn("onVisibleTextAdvance = { key ->", main)
        call = main.split("onVisibleTextAdvance = { key ->", 1)[1].split("onTerminalTextAdvance =", 1)[0]
        for token in ["renderedRevealOwner", "associateBy { it.listKey }", "hapticsHost?.revealAdvance(renderedConversation)"]:
            self.assertIn(token, setup)
        for token in ["renderedRevealTurns[key]", "active.runId == renderedRevealOwner.runId",
                      "active.conversationId == renderedRevealOwner.conversationId", "controller.isRunUnfenced(active)",
                      "editorGeneration == renderedEditorGeneration", 'retainedPage == "chat"',
                      "!sidebarVisible", "turn != null && live", "renderedRevealAdvance?.invoke()"]:
            self.assertIn(token, call)
        self.assertNotIn("completedDrain", call)
        self.assertNotIn("RunStatus.COMPLETED", call)
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
        self.assertIn("if (now - lastTick < 32L) { skipped?.invoke(TickSkip.THROTTLED); return null }", engine)
        self.assertIn("settings.generationOn", engine)
        self.assertIn("backgroundVisible(host.conversation)", engine)
        for name in ["MarkdownTyping.kt", "MarkdownReveal.kt"]:
            text = (CHAT / name).read_text()
            for forbidden in ["Vibrator", "TouchHaptics", "performHapticFeedback", "delay(", "Handler("]:
                self.assertNotIn(forbidden, text)

if __name__ == "__main__":
    unittest.main()
