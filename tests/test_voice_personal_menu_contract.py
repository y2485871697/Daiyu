"""My Voices action-sheet visuals and accent wiring; no account operations."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class VoicePersonalMenuContractTest(unittest.TestCase):
    def test_only_personal_navigation_menus_use_action_sheets(self):
        panel = (SRC / "VoiceAccountPanel.kt").read_text()
        scaffold = (SRC / "VoicePersonalScaffold.kt").read_text()
        self.assertIn("if (menu) VoiceAccountSettingsSheet(", panel)
        self.assertIn("if (addMenu) VoiceAddSheet(", panel)
        self.assertIn("onSelect = { navigate(it) }", panel)
        self.assertIn("onSelect = { beginFlow(it) }", panel)
        self.assertIn("if (menu) VoiceAccountSettingsSheet(", scaffold)
        self.assertIn('isEnabled = { it == "ACCOUNT" && isCurrent() }', scaffold)
        self.assertIn('if (isCurrent() && it == "ACCOUNT")', scaffold)
        self.assertIn("VoiceChoiceDialog(voice.name,", panel)
        self.assertIn("SettingsRadioRow", (SRC / "VoiceSettingsDialogs.kt").read_text())

    def test_sheet_reuses_attachment_shell_and_row_geometry(self):
        menu = (SRC / "VoicePersonalMenus.kt").read_text() if (SRC / "VoicePersonalMenus.kt").exists() else ""
        self.assertIn("ChatSelectionSheet(title", menu)
        self.assertIn("showCloseButton = false, titleFontSize = 20.sp", menu)
        for token in ("heightIn(max = 440.dp)", "verticalScroll(rememberScrollState())",
                      "heightIn(min = 56.dp)", "RoundedCornerShape(12.dp)",
                      "padding(horizontal = 12.dp, vertical = 14.dp)",
                      "Arrangement.spacedBy(16.dp)", "Modifier.size(24.dp)",
                      "fontSize = 16.sp, lineHeight = 24.sp", "Modifier.size(18.dp)",
                      "DaiyuIcons.ChevronRight", "Role.Button"):
            self.assertIn(token, menu)
        self.assertNotIn("SettingsRadioRow", menu)
        self.assertNotIn("height(440.dp)", menu)
        self.assertNotIn("RadioButton", menu)
        self.assertNotIn("resources.", menu)
        self.assertNotIn("execute(", menu)

    def test_voice_menus_only_opt_into_compact_shared_header(self):
        menu = (SRC / "VoicePersonalMenus.kt").read_text()
        self.assertIn('titleFontSize = 20.sp, compact = true', menu)
        self.assertNotIn('height =', menu)
        shell = (SRC / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        for token in ('compact: Boolean = false',
                      'min = if (compact) 0.dp else minOf(180.dp, available)',
                      'padding(bottom = if (compact) 8.dp else 17.dp)',
                      'padding(bottom = if (compact) 6.dp else 13.dp)',
                      'heightIn(min = if (compact && !showCloseButton) 0.dp else 40.dp)',
                      'Column(Modifier.fillMaxWidth().weight(1f, fill = height != null)',
                      'if (showCloseButton) Box(Modifier.size(40.dp)',
                      'PreviewDialog(onDismiss, bottomSheet = true, dismissEnabled = dismissEnabled)'):
            self.assertIn(token, shell)

    def test_android_spacing_regression_measures_local_gap_not_the_scrim(self):
        visual = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoicePersonalVisualActivityTest.kt').read_text()
        for token in ('bounds.first().top - title.bottom', '8f * menuDensity',
                      'sheet.bottom - bounds.last().bottom', '17f * menuDensity',
                      'listOf(false, true)', 'listOf(1f, 1.3f)',
                      'assertHeightIsAtLeast(56.dp)', 'testDisabledSyncAndBackOrOutsideNeverDispatchAnAction'):
            self.assertIn(token, visual)

    def test_action_ids_icons_order_and_disabled_guard_are_kept(self):
        menu = (SRC / "VoicePersonalMenus.kt").read_text() if (SRC / "VoicePersonalMenus.kt").exists() else ""
        for token in ('"CREATE", "用录音制作声音", DaiyuIcons.Voice',
                      '"IMPORT", "导入已有声音", DaiyuIcons.File',
                      '"ACCOUNT", "声音复刻账户", DaiyuIcons.Key',
                      '"SYNC", "同步音色名额", DaiyuIcons.Refresh',
                      'if (isEnabled(item.id)) onSelect(item.id)',
                      'enabled = enabled', 'indication = null'):
            self.assertIn(token, menu)
        self.assertLess(menu.index('"CREATE"'), menu.index('"IMPORT"'))
        self.assertLess(menu.index('"ACCOUNT"'), menu.index('"SYNC"'))

    def test_add_button_uses_same_primary_pair_as_done(self):
        scaffold = (SRC / "VoicePersonalScaffold.kt").read_text()
        self.assertIn("val colors = assistantPreviewPalette()", scaffold)
        self.assertIn("backgroundColor = colors.primary", scaffold)
        self.assertIn('DaiyuIcons.Add, "添加声音", tint = colors.onPrimary', scaffold)
        self.assertNotIn("MiuixTheme.colorScheme.primary", scaffold)
        self.assertIn("minHeight = 56.dp, minWidth = 56.dp, cornerRadius = 18.dp", scaffold)
        self.assertIn('testTag("voice-account-add")', scaffold)


if __name__ == "__main__":
    unittest.main()
