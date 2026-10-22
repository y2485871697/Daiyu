"""Source wiring only. Geometry and IME behavior are covered by the deferred Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def source(path):
    return (SRC / path).read_text()


class BottomSheetEdgeContractTest(unittest.TestCase):
    def test_preview_sheet_mode_is_opt_in_and_keeps_ime(self):
        text = source("ui/components/PreviewDialog.kt")
        self.assertIn("bottomSheet: Boolean = false", text)
        self.assertIn("if (bottomSheet) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)", text)
        self.assertIn("else WindowInsets.safeDrawing", text)
        self.assertIn(".windowInsetsPadding(drawingInsets).imePadding()", text)
        self.assertIn("decorFitsSystemWindows = false", text)
        self.assertIn("val motion = if (bottomSheet) Modifier.dialogEnterMotion() else Modifier", text)

    def test_content_insets_use_consumable_bottom_safe_drawing_only(self):
        text = source("ui/components/PreviewDialog.kt")
        self.assertIn("internal fun Modifier.bottomSheetContentInsets(): Modifier =", text)
        self.assertIn("windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))", text)
        self.assertNotIn("getBottom(", text)

    def test_all_six_bottom_shells_move_safe_area_inside_the_painted_surface(self):
        for path, background in [
            ("ui/screens/chat/conversation/ChatSelectionSheet.kt", ".background(Brush.linearGradient(colors.sheet))"),
            ("ui/components/subagent/ConversationAgentStyle.kt", ".background(Brush.linearGradient(colors.gradient))"),
            ("ui/screens/assistants/AssistantPreviewComponents.kt", ".border(1.dp, colors.border, shape)"),
            ("ui/screens/settings/CatalogModelEditorDialog.kt", ".background(colors.surface)"),
            ("ui/components/UsagePresentation.kt", ".border(1.dp, colors.edge, shape)"),
            ("ui/components/DaiyuDialogs.kt", ".background(colors.surfaceContainer)"),
        ]:
            with self.subTest(path=path):
                text = source(path)
                start = text.index(background)
                self.assertIn(".bottomSheetContentInsets()", text[start:])
                if not path.endswith(("UsagePresentation.kt", "DaiyuDialogs.kt")):
                    self.assertIn("bottomSheet = true", text)

    def test_attachment_and_thinking_surface_edges_have_no_external_bottom_or_side_margin(self):
        for path in ["ui/screens/chat/conversation/ChatSelectionSheet.kt", "ui/components/subagent/ConversationAgentStyle.kt"]:
            text = source(path)
            with self.subTest(path=path):
                self.assertIn(".padding(top = 10.dp)", text)
                self.assertNotIn(".padding(10.dp)", text)
                self.assertIn("RoundedCornerShape(topStart =", text)
                self.assertIn("topEnd =", text)

    def test_compact_usage_keeps_center_alignment_and_original_insets(self):
        text = source("ui/components/UsagePresentation.kt")
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = !compact)", text)
        self.assertIn("if (compact) Alignment.Center else Alignment.BottomCenter", text)
        self.assertIn("if (compact) Modifier.padding(10.dp) else Modifier.padding(top = 10.dp)", text)
        self.assertIn("if (compact) Modifier else Modifier.bottomSheetContentInsets()", text)
        self.assertIn("if (compact) RoundedCornerShape(16.dp)", text)
        self.assertIn("max = if (compact) cardWidth else 550.dp", text)

    def test_non_preview_choice_has_edge_to_edge_window_and_top_only_outer_insets(self):
        text = source("ui/components/DaiyuDialogs.kt").split("internal fun DaiyuChoiceSheet(", 1)[1]
        self.assertIn("decorFitsSystemWindows = false", text)
        self.assertIn("WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)", text)
        self.assertIn(".imePadding()", text)
        self.assertNotIn(".navigationBarsPadding()", text)
        self.assertIn(".padding(top = 12.dp)", text)
        self.assertNotIn(".padding(12.dp)", text)
        self.assertIn("animateContent = false", text)
        self.assertIn("widthIn(max = 640.dp)", text)

    def test_custom_editor_and_assistant_keep_height_caps_but_remove_bottom_rounding(self):
        assistant = source("ui/screens/assistants/AssistantPreviewComponents.kt")
        self.assertIn("RoundedCornerShape(topStart = 25.dp * s, topEnd = 25.dp * s)", assistant)
        self.assertIn(".padding(top = 10.dp)", assistant)
        self.assertIn(".height(sheetHeight)", assistant)
        editor = source("ui/screens/settings/CatalogModelEditorDialog.kt")
        self.assertIn("RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)", editor)
        self.assertIn(".padding(top = 12.dp).widthIn(max = 560.dp)", editor)
        self.assertIn("MiuixDialogActions", editor)

    def test_centered_dialogs_and_material_sheet_do_not_opt_into_preview_sheet_mode(self):
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
        self.assertIn("windowBottom = keyboardTop", text)
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
        self.assertIn("windowBottom, surfaceBottom, 2f", text)
        self.assertIn("override fun doFrame(frameTimeNanos: Long)", text)
        self.assertIn("currentFrameNanos = frameTimeNanos", text)
        self.assertIn("frame != Long.MIN_VALUE && frame != lastRestoredFrame", text)
        self.assertIn("choreographer.removeFrameCallback(frameCallback)", text)
        self.assertNotIn("getInstance().frameTimeNanos", text)
        self.assertIn("<= safeBottom + 2f", text)
        for tag in ["chat-selection-sheet", "conversation-agent-sheet", "assistant-sheet", "usage-glass-dialog", "daiyu-choice-sheet", "catalog-model-dialog"]:
            self.assertIn('"' + tag + '"', text)


if __name__ == "__main__":
    unittest.main()
