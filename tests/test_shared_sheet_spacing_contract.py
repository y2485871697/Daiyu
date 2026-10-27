"""Shared popup spacing defaults; physical geometry is checked in Android tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def source(path):
    return (SRC / path).read_text()


class SharedSheetSpacingContractTest(unittest.TestCase):
    def test_short_selection_content_wraps_without_a_page_opt_in(self):
        sheet = source("ui/screens/chat/conversation/ChatSelectionSheet.kt")
        self.assertIn("if (height == null) Modifier.heightIn(max = available)", sheet)
        self.assertNotIn("180.dp", sheet)
        self.assertNotRegex(sheet, r"\bcompact\b")
        self.assertIn("else Modifier.height(minOf(height, available))", sheet)
        self.assertIn("Column(Modifier.fillMaxWidth().weight(1f, fill = height != null)", sheet)

    def test_hidden_close_has_no_phantom_title_row_and_real_close_keeps_touch_target(self):
        sheet = source("ui/screens/chat/conversation/ChatSelectionSheet.kt")
        self.assertIn("heightIn(min = if (showCloseButton) 40.dp else 0.dp)", sheet)
        self.assertIn("if (showCloseButton) Box(Modifier.size(40.dp)", sheet)
        self.assertIn("clickable(enabled = dismissEnabled", sheet)
        self.assertIn(".padding(bottom = 8.dp)", sheet)
        self.assertIn(".padding(bottom = 6.dp)", sheet)

    def test_shared_callers_cannot_restore_the_old_compact_opt_in(self):
        calls = 0
        for path in SRC.rglob("*.kt"):
            if path.name == "ChatSelectionSheet.kt":
                continue
            for match in re.finditer(r"\bChatSelectionSheet\s*\(", path.read_text()):
                text = path.read_text()
                start = match.end()
                depth = 1
                end = start
                while depth and end < len(text):
                    depth += (text[end] == "(") - (text[end] == ")")
                    end += 1
                self.assertNotRegex(text[start:end], r"\bcompact\s*=", str(path))
                calls += 1
        self.assertGreaterEqual(calls, 10)

    def test_section_spacing_follows_shared_sheet_scope_not_individual_pages(self):
        window = source("ui/components/PreviewDialog.kt")
        self.assertIn("internal val LocalBottomSheetContent = staticCompositionLocalOf { false }", window)
        self.assertIn("LocalBottomSheetContent provides bottomSheet", window)
        settings = source("ui/screens/settings/SettingsUiComponents.kt").split("internal fun SettingsSectionTitle(", 1)[1].split("\n@Composable", 1)[0]
        self.assertIn("val inSheet = LocalBottomSheetContent.current", settings)
        self.assertIn("top = if (inSheet) 8.dp else if (grouped) 24.dp else 28.dp", settings)
        self.assertIn("bottom = if (inSheet) 6.dp else if (grouped) 9.dp else 10.dp", settings)
        tones = source("VoiceSettingsDialogs.kt")
        self.assertIn("SettingsSectionTitle(section)", tones)

    def test_usage_bottom_sheet_matches_spacing_without_changing_centered_mode(self):
        usage = source("ui/components/UsagePresentation.kt")
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = !compact)", usage)
        self.assertIn("if (compact) Alignment.Center else Alignment.BottomCenter", usage)
        self.assertIn("if (useDefaultHeight && !compact) Modifier.height(defaultBottomSheetHeight(maxHeight))", usage)
        self.assertIn("if (!compact) Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)", usage)
        self.assertIn("padding(bottom = if (compact) 10.dp else 6.dp)", usage)

    def test_assistant_workspaces_keep_height_without_phantom_heading_or_footer(self):
        assistant = source("ui/screens/assistants/AssistantPreviewComponents.kt")
        self.assertIn(".height(sheetHeight)", assistant)
        self.assertIn("defaultBottomSheetHeight(maxHeight)", assistant)
        self.assertEqual(2, assistant.count("footer: (@Composable RowScope.() -> Unit)? = null"))
        self.assertIn("heightIn(min = if (onBack != null || showCloseButton) 40.dp * s else 0.dp)", assistant)
        self.assertIn("Spacer(Modifier.height(8.dp * s))", assistant)
        self.assertIn("padding(bottom = 6.dp * s)", assistant)
        footer = assistant.split("if (footer != null) {", 1)[1].split("\n    }", 1)[0]
        self.assertIn('testTag("assistant-sheet-footer")', footer)
        self.assertIn("content = footer", footer)
        self.assertIn("Box(Modifier.size(40.dp * s)", assistant)

    def test_usage_empty_state_has_content_padding_not_a_fake_work_area(self):
        usage = source("ui/screens/chat/conversation/ChatUsageDialog.kt")
        self.assertNotIn("heightIn(min = 100.dp)", usage)
        self.assertIn('padding(vertical = 12.dp).testTag("chat-usage-empty")', usage)
        self.assertIn("compact = true, adaptiveWidth = true", usage)
        self.assertIn("weight(1f, fill = false)", usage)

    def test_android_geometry_covers_default_short_empty_subtitle_footer_and_fixed_height(self):
        tests = ROOT / "app/src/androidTest/kotlin/app/daiyu/android"
        options = (tests / "OptionSelectionSheetActivityTest.kt").read_text()
        for token in ("bounds.first().top - title.bottom", "8f * menuDensity", "sheet.bottom - bounds.last().bottom", "17f * menuDensity"):
            self.assertIn(token, options)
        shared = (tests / "SharedSheetSpacingActivityTest.kt").read_text()
        for token in ("testEmptyAndSingleOptionSheetsWrapWithoutOptIn", "testSubtitleAndFooterSpacingPreserveCallbacks", "testExplicitHeightAndCloseTouchTargetStayUnchanged", "testSettingsSectionSpacingStaysScopedToSheet", "listOf(false, true)", "listOf(1f, 1.3f)", "LocalDensity.current", "fixture.opens.get()"):
            self.assertIn(token, shared)


if __name__ == "__main__":
    unittest.main()
