"""Source contracts only; floating geometry and IME tests await the approved Android batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def source(path):
    return (SRC / path).read_text()


class BottomSheetEdgeContractTest(unittest.TestCase):
    SHELLS = {
        "ui/screens/chat/conversation/ChatSelectionSheet.kt": (10, "RoundedCornerShape(25.dp)"),
        "ui/components/subagent/ConversationAgentStyle.kt": (10, "RoundedCornerShape(radius.dp)"),
        "ui/screens/assistants/AssistantPreviewComponents.kt": (10, "RoundedCornerShape(25.dp * s)"),
        "ui/components/UsagePresentation.kt": (10, "RoundedCornerShape(if (compact) 16.dp else 25.dp)"),
        "ui/screens/settings/CatalogModelEditorDialog.kt": (12, "RoundedCornerShape(32.dp)"),
        "ui/components/DaiyuDialogs.kt": (12, "RoundedCornerShape(20.dp)"),
    }

    def test_preview_uses_external_safe_area_and_keeps_ime_and_bottom_motion(self):
        text = source("ui/components/PreviewDialog.kt")
        self.assertIn("bottomSheet: Boolean = false", text)
        self.assertIn(".windowInsetsPadding(WindowInsets.safeDrawing).imePadding()", text)
        self.assertIn("decorFitsSystemWindows = false", text)
        self.assertIn("val motion = if (bottomSheet) Modifier.dialogEnterMotion() else Modifier", text)
        self.assertNotIn("WindowInsetsSides", text)
        self.assertNotIn("bottomSheetContentInsets", text)

    def test_six_shells_restore_original_outer_margins_and_all_four_corners(self):
        for path, (margin, shape) in self.SHELLS.items():
            with self.subTest(path=path):
                text = source(path)
                shell = text.split("internal fun DaiyuChoiceSheet(", 1)[1] if path.endswith("DaiyuDialogs.kt") else text
                self.assertIn(f".padding({margin}.dp)", shell)
                self.assertLess(shell.index(f".padding({margin}.dp)"), shell.index(".clip("))
                self.assertIn(shape, shell)
                self.assertNotIn("bottomSheetContentInsets", shell)
                self.assertNotIn("RoundedCornerShape(topStart =", shell)
                if not path.endswith(("UsagePresentation.kt", "DaiyuDialogs.kt")):
                    self.assertIn("bottomSheet = true", shell)

    def test_compact_usage_keeps_center_alignment_width_and_padding(self):
        text = source("ui/components/UsagePresentation.kt")
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = !compact)", text)
        self.assertIn("if (compact) Alignment.Center else Alignment.BottomCenter", text)
        self.assertIn(".padding(10.dp)", text)
        self.assertIn("RoundedCornerShape(if (compact) 16.dp else 25.dp)", text)
        self.assertIn("max = if (compact) cardWidth else 550.dp", text)

    def test_non_preview_choice_has_external_safe_area_and_original_motion(self):
        text = source("ui/components/DaiyuDialogs.kt").split("internal fun DaiyuChoiceSheet(", 1)[1]
        self.assertIn("decorFitsSystemWindows = false", text)
        self.assertIn(".windowInsetsPadding(WindowInsets.safeDrawing)", text)
        self.assertIn(".imePadding()", text)
        self.assertIn(".fillMaxWidth().padding(12.dp).heightIn(max = 560.dp)", text)
        self.assertIn("animateContent = false", text)
        self.assertIn("widthIn(max = 640.dp)", text)

    def test_height_caps_and_content_padding_are_preserved(self):
        assistant = source("ui/screens/assistants/AssistantPreviewComponents.kt")
        self.assertIn(".height(sheetHeight)", assistant)
        self.assertIn("start = 15.dp * s, end = 15.dp * s, top = 10.dp * s, bottom = 16.dp * s", assistant)
        editor = source("ui/screens/settings/CatalogModelEditorDialog.kt")
        self.assertIn(".padding(12.dp).widthIn(max = 560.dp)", editor)
        self.assertIn(".padding(horizontal = 24.dp, vertical = 20.dp)", editor)
        self.assertIn("MiuixDialogActions", editor)
        selection = source("ui/screens/chat/conversation/ChatSelectionSheet.kt")
        self.assertIn("start = 15.dp, end = 15.dp, top = 11.dp, bottom = 15.dp", selection)
        self.assertIn("maxHeight - if (height == null) 70.dp else 88.dp", selection)

    def test_centered_and_material_sheets_remain_separate(self):
        for path in ["ui/components/subagent/ConversationAgentChoice.kt", "ui/screens/chat/conversation/ToolOutputDialog.kt"]:
            with self.subTest(path=path):
                self.assertNotIn("bottomSheet =", source(path))
        material = source("ui/components/SubAgentPresetActionsSheet.kt")
        self.assertIn("ModalBottomSheet(", material)
        self.assertNotIn("bottomSheetContentInsets", material)

    def test_android_regression_asserts_dialog_viewport_and_content_geometry(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/BottomSheetEdgeActivityTest.kt").read_text()
        self.assertIn("surface.positionOnScreen - surface.positionInRoot", text)
        self.assertIn("ViewCompat.getRootWindowInsets(view)", text)
        self.assertIn("val keyboardTop = checkNotNull(originalWindowBottom) - imeBottom", text)
        self.assertIn("safeBottom = checkNotNull(originalWindowBottom) - maxOf(imeBottom, systemBottom)", text)
        self.assertIn("awaitNodeWindowFocus(node(surfaceTag))", text)
        self.assertIn("if (!ime) {", text)
        self.assertIn("dismissIme(root)", text)
        self.assertIn("viewportBottom >= keyboardTop - 2f", text)
        self.assertNotIn("minOf(viewportBottom", text)
        self.assertIn("Rect(node.positionInRoot,", text)
        self.assertIn("node.size.width.toFloat(), node.size.height.toFloat()", text)
        self.assertNotIn("node.layoutInfo.coordinates", text)
        self.assertIn("assertSame(surface.root, viewport.root)", text)
        self.assertIn("assertSame(surface.root, control.root)", text)
        self.assertIn('"choice-sheet-cancel"', text)
        self.assertIn("val open = mutableStateOf(true)", text)
        self.assertNotIn("remember { mutableStateOf(true) }", text)
        self.assertIn("safeBottom - margin, surfaceBottom, 2f", text)
        self.assertIn("assertHorizontalInsets(surfaceTag, surface, viewport, insets)", text)
        self.assertIn("safeLeft + gap, surfaceLeft, 2f", text)
        self.assertIn("safeRight - gap, surfaceRight, 2f", text)
        self.assertIn("override fun doFrame(frameTimeNanos: Long)", text)
        self.assertIn("currentFrameNanos = frameTimeNanos", text)
        self.assertIn("frame != Long.MIN_VALUE && frame != lastRestoredFrame", text)
        self.assertIn("choreographer.removeFrameCallback(frameCallback)", text)
        self.assertNotIn("getInstance().frameTimeNanos", text)
        self.assertIn("controlBottom <= surfaceBottom + 2f", text)
        self.assertIn("originalBottom - safeInset - bottomMargin", text)
        for tag in ["chat-selection-sheet", "conversation-agent-sheet", "assistant-sheet", "usage-glass-dialog", "daiyu-choice-sheet", "catalog-model-dialog"]:
            self.assertIn('"' + tag + '"', text)


if __name__ == "__main__":
    unittest.main()
