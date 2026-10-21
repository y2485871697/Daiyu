"""Source wiring contracts; Compose/pixel and Kotlin runtime checks await an approved build."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation"


class ToolCardHtmlContractTest(unittest.TestCase):
    def test_full_taxonomy_and_category_driven_body_are_wired(self):
        presentation = (CHAT / "ToolCardPresentation.kt").read_text()
        categories = re.search(r'val categories = setOf\(([^\n]+)\)', presentation).group(1)
        names = re.findall(r'"([a-z_]+)"', categories)
        self.assertEqual(21, len(names))
        body = (CHAT / "ToolBodyLayout.kt").read_text()
        for name in names:
            self.assertIn(f'"{name}"', body)
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn("val body = toolBodyLayout(category, details)", content)
        self.assertIn("if (fields.isEmpty()) return", content)
        self.assertNotIn('Disclosure("运行环境"', content)
        self.assertIn("if (rich.bodyRestricted)", content)

    def test_technical_header_uses_arrow_not_alternate_label(self):
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertIn('ToolDisclosureHeader("技术详情", technical,', shell)
        self.assertNotIn("收起技术详情", shell)
        self.assertIn("compact = true", shell)
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn("DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight", content)
        self.assertIn('stateDescription = if (expanded) "已展开" else "已折叠"', content)
        rich = (CHAT / "ToolRichDetails.kt").read_text()
        self.assertIn('ToolDisclosureUi("原始回执"', rich)
        self.assertNotIn('ToolDisclosureUi("调用回执"', rich)

    def test_output_preview_uses_overlay_and_shared_dialog(self):
        content = (CHAT / "ToolCategoryContent.kt").read_text()
        self.assertIn("if (tappable) Box(surface", content)
        self.assertIn('Text("点按查看完整输出", Modifier.align(Alignment.BottomEnd)', content)
        self.assertIn("if (opened) ToolOutputDialog(document", content)
        shell = (CHAT / "WorkProcessCard.kt").read_text()
        self.assertIn("LocalToolOutputContext provides ToolOutputContext(step.name, stateLabel)", shell)
        dialog = (CHAT / "ToolOutputDialog.kt").read_text()
        for token in ("0xFFFBF9FE", "0xFF24212C", "0xCC16101F", "scrimBlurRadius = 4f",
                      "viewportHeight * .82f", "RoundedCornerShape(18.dp)", '"$it · 输出"'):
            self.assertIn(token, dialog)
        self.assertNotIn("usagePalette", dialog)
        self.assertIn("clipboard.setText(AnnotatedString(document.text))", dialog)

    def test_time_and_parameter_projection_preserve_recorded_semantics(self):
        rich = (CHAT / "ToolRichDetails.kt").read_text()
        self.assertIn("ZonedDateTime.parse(value).toOffsetDateTime()", rich)
        self.assertIn('ToolDisclosureUi("调用参数"', rich)
        self.assertIn("value is JSONArray -> sanitizedArray(value, depth + 1)", rich)
        self.assertIn("is Number, is Boolean -> value", rich)
        for filename in ("ToolCardPresentation.kt", "ToolCategoryDetails.kt"):
            self.assertIn("65_536", (CHAT / filename).read_text())


if __name__ == "__main__":
    unittest.main()
