"""Source wiring checks; Android interaction assertions run in the instrumented batch."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
UI = APP / "ui/screens/chat/conversation"
class CompressionSendGateContractTest(unittest.TestCase):
    def test_only_current_main_actual_compression_blocks(self):
        source = (APP / "ChatController.kt").read_text()
        gate = source.split("internal fun isMainContextCompressing(", 1)[1].split("private val contextManagementExecutor", 1)[0]
        self.assertIn("if (conversationId == null) return false", gate)
        self.assertIn("it.running && !it.waiting && it.taskId == null && it.conversationId == conversationId.value", gate)
        self.assertIn("it.initial.conversationId == conversationId && it.runner.isCompacting()", gate)
    def test_composer_stays_editable_but_no_send_alternative_is_enabled(self):
        models = (UI / "ChatScreenModels.kt").read_text()
        self.assertIn("textActionEnabled(): Boolean = enabled && !mainContextCompressing", models)
        activity = (APP / "MainActivity.kt").read_text()
        self.assertIn("mainContextCompressing = controller.isMainContextCompressing(selected?.id)", activity)
        self.assertNotIn("enabled = allowed && !controller.isMainContextCompressing", activity)
    def test_blocked_click_precedes_input_dismiss_and_has_neutral_visual(self):
        composer = (UI / "ChatComposer.kt").read_text()
        click = composer.split("ChatIconButton(primaryIcon, primaryLabel, {", 1)[1].split("iconSize = if", 1)[0]
        self.assertLess(click.index("if (compressionBlocked) actions.onCompressionSendBlocked()"), click.index("hideChatInputIme("))
        self.assertIn("enabled = primaryEnabled || compressionBlocked", click)
        self.assertIn("containerColor = if (compressionBlocked) MiuixTheme.colorScheme.onSurface", click)
        self.assertIn("tint = if (compressionBlocked) MiuixTheme.colorScheme.onSurface", click)
        self.assertIn(".controlGesture(actions, primaryEnabled)", click)
        self.assertIn("state.mainContextCompressing && (textAction || state.phase == ComposerPhase.IDLE)", composer)
    def test_host_notices_and_guards_before_mutating_draft_or_voice(self):
        activity = (APP / "MainActivity.kt").read_text()
        self.assertIn('onCompressionSendBlocked = { toast("正在压缩上下文") }', activity)
        self.assertIn("onSend = send@{ text ->\n            if (compressionSendBlocked()) return@send", activity)
        self.assertIn("onSupplement = supplement@{ text ->\n            if (compressionSendBlocked()) return@supplement", activity)
        draft = activity.split("private fun sendDraft(", 1)[1].split("private fun cancelPendingTextSend", 1)[0]
        self.assertLess(draft.index("compressionSendBlocked()"), draft.index("controller.database.draft("))
        supplement = activity.split("private fun submitSupplement(", 1)[1].split("private fun ", 1)[0]
        self.assertLess(supplement.index("compressionSendBlocked(owner.conversationId)"), supplement.index("supplementDraft.begin("))
    def test_runner_final_admission_is_atomic_and_never_cancels_on_rejection(self):
        source = (ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain/ChatRunner.kt").read_text()
        admission = source.split("fun admitSupplement(", 1)[1].split("private fun completeAdmission", 1)[0]
        self.assertIn("if (owner == null || compacting ||", admission)
        self.assertLess(admission.index("compacting ||"), admission.index("pendingSupplements.addLast(pending)"))
        self.assertLess(admission.index("return false"), admission.index("compactingRuntime?.cancel()"))
        legacy = source.split("fun supplement(", 1)[1].split("fun admitSupplement(", 1)[0]
        self.assertIn("if (compacting || terminal", legacy)
        compact = source.split("fun compactAtBoundary()", 1)[1].split("fun contextSize()", 1)[0]
        self.assertLess(compact.index("compacting = true"), compact.index("manual?.create?.invoke"))
        self.assertLess(compact.index("compacting = false"), compact.index("finishedCompaction()"))
if __name__ == "__main__": unittest.main()
