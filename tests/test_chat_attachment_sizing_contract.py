"""Page-local attachment sizing guards; Android geometry tests are deferred."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"


class ChatAttachmentSizingContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.menus = (UI / "ChatMenus.kt").read_text()
        cls.attachment = cls.menus.split("internal fun ChatAttachmentSheet(", 1)[1].split("\n@Composable", 1)[0]
        cls.shell = (UI / "ChatSelectionSheet.kt").read_text()

    def test_only_attachment_opts_into_larger_header_fonts(self):
        self.assertIn("titleFontSize: TextUnit = 18.sp", self.shell)
        self.assertIn("subtitleFontSize: TextUnit = 12.sp", self.shell)
        self.assertIn("fontSize = titleFontSize, lineHeight = 26.sp", self.shell)
        self.assertIn("fontSize = subtitleFontSize, lineHeight = 18.sp", self.shell)
        self.assertIn("titleFontSize = 20.sp, subtitleFontSize = 14.sp", self.attachment)
        for file in UI.rglob("*.kt"):
            if file.name not in {"ChatMenus.kt", "ChatSelectionSheet.kt"}:
                text = file.read_text()
                for call in re.findall(r"\bChatSelectionSheet\s*\(([^)]*)\)", text, re.S):
                    self.assertNotRegex(call, r"\b(?:titleFontSize|subtitleFontSize)\s*=", str(file))

    def test_four_entries_keep_mapping_click_semantics_and_dismiss_order(self):
        self.assertEqual([
            ("图片", "Image", "ATTACH_IMAGE"),
            ("视频", "Video", "ATTACH_VIDEO"),
            ("文件引用", "File", "ATTACH_FILE"),
            ("目录引用", "Folder", "ATTACH_FOLDER"),
        ], re.findall(r'MenuEntry\("([^"]+)",\s*DaiyuIcons\.(\w+),\s*ChatMenuAction\.(\w+)\)',
                      self.attachment))
        self.assertIn("import app.daiyu.android.ui.haptics.clickable", self.menus)
        self.assertIn("indication = null", self.attachment)
        self.assertIn("role = Role.Button) { onDismiss(); onAction(entry.action) }", self.attachment)
        self.assertIn("showCloseButton = false", self.attachment)

    def test_rows_use_readable_text_icons_and_flexible_touch_targets(self):
        for token in [".heightIn(min = 56.dp)", ".padding(horizontal = 12.dp, vertical = 14.dp)",
                      "Arrangement.spacedBy(16.dp)", "fontSize = 16.sp, lineHeight = 24.sp",
                      '.size(24.dp).testTag("chat-attachment-icon:',
                      '.size(18.dp).testTag("chat-attachment-chevron:',
                      ".verticalScroll(rememberScrollState())"]:
            self.assertIn(token, self.attachment)
        self.assertNotIn(".height(56.dp)", self.attachment)
        self.assertNotIn("maxLines = 1", self.attachment)
        for token in ["LocalDensity", "fontScale", "interfaceScale", "graphicsLayer", ".scale("]:
            self.assertNotIn(token, self.attachment)

    def test_shared_shell_preserves_glass_safe_area_and_wrap_content(self):
        for token in ["PreviewDialog(onDismiss, bottomSheet = true, dismissEnabled = dismissEnabled)",
                      "RoundedCornerShape(25.dp)",
                      ".padding(10.dp)", ".previewGlassBlur(shape, 28f, 1.3f)", "max = available", "fill = height != null"]:
            self.assertIn(token, self.shell)
        self.assertNotIn("fontScale", self.shell)
        self.assertNotIn("interfaceScale", self.shell)

    def test_android_sources_cover_theme_scale_overflow_geometry_and_callbacks(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ChatAttachmentSizingActivityTest.kt").read_text()
        for token in ["listOf(false, true)", "listOf(1f, .8f)", "listOf(.9f, 1.3f)",
                      "TextLayoutResult", "it.hasVisualOverflow", "rowBounds.height >= 56f * density - 1f",
                      "assertEquals(fontScale, text.layoutInput.density.fontScale", '"chat-attachment-icon" to 24f',
                      '"chat-attachment-chevron" to 18f', "assertEquals(1, dismissals)",
                      "assertEquals(listOf(action), actions)", "performTouchInput { click(Offset(4f, center.y)) }", "scrollToVisible()",
                      "testOtherSelectionSheetsKeepTheirDefaultHeaderTypography", "fixture.opens.get()"]:
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
