"""Bottom-sheet headings are centered without re-aligning body content."""
from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class PopupTitleContractTest(unittest.TestCase):
    def test_selection_default_and_voice_override_are_centered(self):
        shell = (SRC / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        self.assertIn("titleTextAlign: TextAlign = TextAlign.Center", shell)
        self.assertIn("padding(start = if (showCloseButton) 40.dp else 0.dp)", shell)
        self.assertIn("padding(bottom = 12.dp)", shell)
        self.assertIn("textAlign = titleTextAlign", shell)
        tones = (SRC / "VoiceTonePicker.kt").read_text()
        self.assertNotIn("titleTextAlign = TextAlign.Start", tones)

    def test_assistant_buttons_have_symmetric_non_interactive_slots(self):
        shell = (SRC / "ui/screens/assistants/AssistantPreviewComponents.kt").read_text()
        heading = shell.split('testTag("assistant-sheet-heading")', 1)[1].split('Column(Modifier.fillMaxWidth().weight(1f)', 1)[0]
        for token in ("textAlign = TextAlign.Center", "padding(bottom = 12.dp * s)",
                      "else if (showCloseButton) Spacer(Modifier.size(40.dp * s))",
                      "else if (onBack != null) Spacer(Modifier.size(40.dp * s))"):
            self.assertIn(token, heading)

    def test_usage_bottom_heading_preserves_compact_mode(self):
        shell = (SRC / "ui/components/UsagePresentation.kt").read_text()
        self.assertIn("textAlign = if (compact) null else TextAlign.Center", shell)
        self.assertIn("Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth())", shell)
        self.assertIn("padding(bottom = if (compact) 10.dp else 12.dp)", shell)

    def test_collaboration_and_status_top_headings_are_explicitly_centered(self):
        for path, title in (("ui/components/ConversationCollaborationDialog.kt", "本会话协作"),
                            ("ui/screens/chat/conversation/SubAgentStatusDialog.kt", "子代理状态")):
            text = (SRC / path).read_text().split('Text("' + title + '"', 1)[1].split("\n", 2)
            self.assertIn("TextAlign.Center", "\n".join(text))

    def test_custom_workspace_skill_and_mcp_headers_cannot_bypass_centering(self):
        paths = ("VoiceLocalRemovalSheet.kt", "ui/components/ConversationWorkspaceSheet.kt",
                 "ui/screens/settings/SkillManagementScreen.kt", "ui/screens/settings/McpSettingsScreen.kt")
        for path in paths:
            self.assertIn("TextAlign.Center", (SRC / path).read_text(), path)
        skills = (SRC / "ui/screens/settings/SkillManagementScreen.kt").read_text()
        self.assertIn("Text(entry.skill.name, modifier = Modifier.fillMaxWidth(), textAlign =", skills)
        self.assertIn("Text(title, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)", skills)

    def test_cloud_scope_is_exact_and_not_full_tool_discovery(self):
        path = ROOT / "scripts/acceptance/record_tool_tests.py"
        spec = importlib.util.spec_from_file_location("popup_title_receipt", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        classes = module.POPUP_TITLE_PLATFORM_CLASSES
        self.assertEqual(4, len(classes))
        command, scope = module.command_for("python3", {"SMOKE_MODE": "smoke", "SMOKE_TEST_CLASSES": ",".join(reversed(classes))})
        self.assertEqual("popup-title-targeted", scope)
        self.assertIn("test_popup_title_contract", command)
        self.assertNotIn("discover", command)
        _, other = module.command_for("python3", {"SMOKE_MODE": "smoke", "SMOKE_TEST_CLASSES": classes[0]})
        self.assertNotEqual(scope, other)

    def test_device_regression_checks_actual_glyphs_not_only_node_centers(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/PopupTitleGeometryTest.kt").read_text()
        for token in ("awaitStableWindowFrame(view)", "SemanticsActions.GetTextLayoutResult", "getLineLeft(line)",
                      "getLineRight(line)", "sheetBounds.center.x", "text.hasVisualOverflow", "expectedGap * pixelDensity",
                      "testAssistantHeadingCentersWithEveryNavigationButtonCombination",
                      "testConversationCollaborationHeadingIsCentered", "testLongTitleWrapsAndStaysCenteredWithOneSidedButtons"):
            self.assertIn(token, text)


if __name__ == "__main__":
    unittest.main()
