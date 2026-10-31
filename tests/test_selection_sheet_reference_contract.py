"""Wiring guards for reference-style option sheets; Android covers rendering."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def source(path):
    return (SRC / path).read_text()


class SelectionSheetReferenceContractTest(unittest.TestCase):
    def test_sheet_radio_rows_delegate_without_changing_inline_settings(self):
        text = source("ui/screens/settings/SettingsUiComponents.kt")
        radio = text.split("internal fun SettingsRadioRow(", 1)[1].split("internal fun SettingsTextField(", 1)[0]
        sheet, inline = radio.split('    Box(Modifier.testTag("settings-radio:$title")', 1)
        for fragment in ("if (LocalBottomSheetContent.current)",
                         "OptionSelectionItem(title, selected, enabled = enabled, summary = summary",
                         'modifier = Modifier.testTag("settings-radio:$title"), onClick = onClick)',
                         "return"):
            self.assertIn(fragment, sheet)
        self.assertIn("SettingsRow(title = title, summary = summary", inline)
        self.assertIn("role = Role.RadioButton, enabled = enabled, onClick = onClick", inline)
        self.assertIn("tint = DaiyuPalette.selectionAccent, size = 24.dp", inline)
        self.assertNotIn("onClick()", radio)

    def test_shared_rows_use_reference_palette_dimensions_and_keep_card_style(self):
        text = source("ui/components/OptionSelectionSheet.kt")
        for fragment in ("selected -> SolidColor(colors.selectionContainer)",
                         "cardStyle -> Brush.linearGradient(colors.sheet)",
                         "else -> SolidColor(Color.Transparent)",
                         "RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp)",
                         "heightIn(min = if (cardStyle) 48.dp else 56.dp)",
                         "vertical = if (cardStyle) 12.dp else 14.dp",
                         "Arrangement.spacedBy(if (cardStyle) 12.dp else 16.dp)",
                         "fontSize = if (cardStyle) 14.sp else 16.sp",
                         "lineHeight = if (cardStyle) 20.sp else 24.sp",
                         "val selectedContent = colors.selectionContent",
                         "val indicatorColor = if (cardStyle) selectedContent else colors.accent",
                         "Modifier.size(22.dp).clip(CircleShape)",
                         'Icon(DaiyuIcons.Check, "已选择", Modifier.size(14.dp)',
                         "tint = if (cardStyle) colors.selectionContainer else colors.onPrimary",
                         "summary?.let"):
            self.assertIn(fragment, text)
        self.assertNotIn("Color(0xFFEFEDFB)", text)
        self.assertNotIn("maxLines =", text)

    def test_small_sheet_title_and_spacing_match_the_reference_without_fixed_height(self):
        text = source("ui/components/OptionSelectionSheet.kt")
        self.assertIn("itemSpacing: Dp = 0.dp", text)
        self.assertIn("showCloseButton = false, titleFontSize = 20.sp", text)
        self.assertIn(".verticalScroll(rememberScrollState()).selectableGroup()", text)
        self.assertNotIn("height =", text)

    def test_asr_has_no_title_description_and_keeps_exact_save_and_resource_mapping(self):
        text = source("VoiceSettingsPanel.kt")
        block = text.split('"ASR_ADVANCED" -> ChatSelectionSheet(', 1)[1].split('"DUPLEX_VOICE"', 1)[0]
        self.assertIn('"识别服务", onDismiss = { dialog = null }', block)
        self.assertIn("showCloseButton = false, titleFontSize = 20.sp", block)
        self.assertNotIn("subtitle", block)
        self.assertNotIn("仅在控制台开通了不同服务时更改", text)
        self.assertIn('.selectableGroup().testTag("voice-asr-service-sheet")', block)
        self.assertIn("DoubaoAsrProtocol.resources.forEachIndexed", block)
        self.assertIn('listOf("识别 2.0 · 按时长", "识别 2.0 · 按并发", "识别 1.0 · 按时长", "识别 1.0 · 按并发")[index]', block)
        self.assertIn("draft.asrResource == resource, !busy", block)
        self.assertIn("if (current() && !busy)", block)
        self.assertIn("changeDraft(draft.copy(asrResource = resource))", block)
        self.assertIn("save((saved ?: VoiceConfiguration()).copy(asrResource = resource))", block)
        self.assertEqual(1, block.count("dialog = null"))
        self.assertNotIn("save(draft", block)

    def test_reference_uses_the_same_row_and_only_original_change_callback(self):
        text = source("ui/screens/settings/AppearanceSettingsScreen.kt")
        block = text.split("if (choosingBlur)", 1)[1].split("SettingsScaffoldPage(title", 1)[0]
        self.assertIn("SettingsRadioRow(title, selected = state.topBarBlurStyle.ordinal == index)", block)
        self.assertIn("onChange(state.copy(topBarBlurStyle = AppearanceTopBarBlurStyle.entries[index]))", block)
        self.assertEqual(1, block.count("onChange("))
        self.assertEqual(1, block.count("choosingBlur = false"))
        self.assertNotIn("subtitle =", block)
        self.assertNotIn("footer =", block)


if __name__ == "__main__":
    unittest.main()
