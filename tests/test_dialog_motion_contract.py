"""Source contracts only; pixel/Compose animation regressions run with the next approved build."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class DialogMotionContractTest(unittest.TestCase):
    def text(self, relative):
        return (SRC / relative).read_text()

    def test_dialog_primitives_cannot_bypass_motion_adapters(self):
        allowed = {"ui/components/DialogMotion.kt", "ui/components/PreviewDialog.kt"}
        for path in SRC.rglob("*.kt"):
            relative = path.relative_to(SRC).as_posix()
            if relative in allowed:
                continue
            text = path.read_text()
            with self.subTest(path=relative):
                self.assertNotRegex(text, r"(?m)^import androidx\.compose\.ui\.window\.Dialog(?:[ \t]+as[^\n]*)?$")
                self.assertNotIn("androidx.compose.ui.window.Dialog(", text)
                self.assertNotRegex(text, r"(?m)^import androidx\.compose\.material3\.AlertDialog(?:[ \t]+as[^\n]*)?$")
                self.assertNotIn("androidx.compose.material3.AlertDialog(", text)
                if "import androidx.compose.material3.*" in text and re.search(r"(?<![\w.])AlertDialog\(", text):
                    self.assertIn("DaiyuAlertDialog as AlertDialog", text)

    def test_original_assistant_spring_and_stationary_scrim(self):
        motion = self.text("ui/components/DialogMotion.kt")
        self.assertIn("dampingRatio = 0.88f, stiffness = 450f, visibilityThreshold = 0.0001f", motion)
        self.assertIn("folmeSpring(damping = 0.9f, response = 0.3f)", motion)
        self.assertIn("840f", motion)
        self.assertIn("480f", motion)
        self.assertIn("translationY = (1f - value) * size.height", motion)
        self.assertIn("tween(300, easing = DecelerateEasing(1.5f))", motion)
        preview = self.text("ui/components/PreviewDialog.kt")
        self.assertIn(".dialogDimBackground(", preview)
        self.assertIn("BoxWithConstraints(Modifier.dialogEnterMotion()", preview)
        self.assertIn("setWindowAnimations(0)", preview)
        choices = self.text("ui/components/DaiyuDialogs.kt")
        self.assertIn("animateContent = false", choices)
        self.assertIn("Modifier.dialogEnterMotion().align(Alignment.BottomCenter)", choices)

    def test_all_native_dialogs_use_the_same_spring_solver(self):
        native = self.text("ui/components/NativeDialogMotion.kt")
        self.assertIn("TargetBasedAnimation(dialogEnterSpec(largeScreen)", native)
        self.assertIn("animator.cancel()", native)
        self.assertIn("override fun onAnimationEnd", native)
        self.assertIn("window?.setDimAmount(targetDim * dim)", native)
        self.assertIn("window?.setDimAmount(targetDim * value)", self.text("ui/components/DialogMotion.kt"))
        for file in ["ui/GenerationMediaDialog.kt", "ui/FeatureHubDialog.kt", "question/QuestionHost.kt"]:
            with self.subTest(file=file):
                self.assertIn("startDaiyuEnterMotion()", self.text(file))
        sheet = self.text("ui/components/SubAgentPresetActionsSheet.kt")
        self.assertIn("ModalBottomSheet(", sheet)
        self.assertIn("SheetState(", sheet)
        self.assertIn("initialValue = SheetValue.Expanded", sheet)
        self.assertIn("skipPartiallyExpanded = true", sheet)
        self.assertIn("56.dp.toPx()", sheet)
        self.assertIn("125.dp.toPx()", sheet)
        self.assertIn("modifier = Modifier.dialogEnterMotion()", sheet)
        self.assertIn("dragHandle = { BottomSheetDefaults.DragHandle() }", sheet)
        self.assertNotIn("AssistantSheetMotion", sheet)
        self.assertNotIn("MotionScheme", self.text("ui/components/DialogMotion.kt"))
        self.assertNotIn(".motionScheme", self.text("ui/components/DialogMotion.kt"))

    def test_production_glass_menu_uses_original_popup_motion_and_exit_retention(self):
        motion = self.text("ui/components/PopupMotion.kt")
        for token in ["FractionAnimationSpec", "AlphaEnterAnimationSpec", "AlphaExitAnimationSpec"]:
            self.assertIn("ListPopupDefaults." + token, motion)
        self.assertIn("PopupExitRetentionMillis = 160L", motion)
        self.assertIn("tween(durationMillis = 150)", motion)
        layer = self.text("ui/screens/chat/conversation/ChatMenuLayer.kt")
        for fragment in ["rememberPopupMotion(menu.expanded())", "0.15f + 0.85f * motion.fraction.value",
                         "TransformOrigin(1f, 0f)", ".etaMenuClipReveal(", "if ((expanded || mounted) && layer != null)",
                         "delay(PopupExitRetentionMillis)", "PointerEventPass.Initial"]:
            self.assertIn(fragment, layer)
        self.assertIn("if (!menu.expanded()) event.changes.forEach { it.consume() }", layer)
        # Keep the existing single-pass measure/place and page-owned backdrop.
        self.assertIn("val surface = measurables[1].measure", layer)
        self.assertIn("surface.place(x, y)", layer)
        self.assertIn("captureForTopBar(backdrop)", layer)
        self.assertIn("else if (layer == null && expanded)", layer)
        self.assertIn("LaunchedEffect(layer, menu, expanded)", layer)
        self.assertIn("layer.active?.takeIf { it !== menu }?.onDismiss?.invoke()", layer)
        self.assertIn("clipWhenFullyRevealed = false", layer)
        self.assertIn("else Modifier.clearAndSetSemantics {}", layer)
        self.assertIn("rememberPopupMotion(expanded)", self.text("ui/components/EtaDropdownMenu.kt"))

    def test_more_button_is_transparent_but_keeps_selection_semantics(self):
        text = self.text("ui/screens/chat/conversation/ChatTopBar.kt")
        button = text[text.index('val moreShape'):text.index('ChatOverflowMenu(expanded')]
        self.assertNotIn(".background(", button)
        self.assertNotIn(".border(", button)
        self.assertIn("indication = null", button)
        self.assertIn("selected = expanded", button)
        self.assertIn("actions.onMore(); expanded = true", button)


if __name__ == "__main__":
    unittest.main()
