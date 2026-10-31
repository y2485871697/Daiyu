"""Voice settings use the same page and card owners, not a copied color palette."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class VoiceSettingsPaletteContractTest(unittest.TestCase):
    def test_personal_routes_have_no_old_style_escape_hatch(self):
        shell = (SRC / 'VoicePersonalScaffold.kt').read_text()
        panel = (SRC / 'VoiceAccountPanel.kt').read_text()
        self.assertIn('SettingsScaffoldPage(title, onBack, compactTitle = true, actions = {', shell)
        self.assertNotIn('MiuixScaffoldPage', shell)
        self.assertNotIn('settingsStyle', shell + panel)
        for route in ('LIST', 'ACCOUNT', 'SYNC', 'IMPORT', 'CREATE'):
            self.assertIn(f'page == "{route}"', panel)
        self.assertIn('VoicePersonalScaffold("我的声音", onBack', shell)
        self.assertIn('if (onAdd != null) item { Spacer(Modifier.height(80.dp)) }', shell)

    def test_voice_cards_delegate_paint_and_geometry_to_shared_settings(self):
        components = (SRC / 'VoiceSettingsComponents.kt').read_text()
        card = components.split('internal fun VoiceSettingsCard(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('SettingsCard(modifier, content)', card)
        self.assertNotIn('top.yukonga.miuix.kmp.basic.Card', components)
        for override in ('.padding(', '.background(', '.clip(', 'Color(', 'colorScheme'):
            self.assertNotIn(override, card)

    def test_page_style_still_comes_from_settings_owner(self):
        settings = (SRC / 'ui/screens/settings/SettingsScaffold.kt').read_text()
        self.assertIn('LocalSettingsPageStyle provides true', settings)
        self.assertIn('containerColor = settingsPageBackground()', settings)
        self.assertIn('MiuixTheme.colorScheme.background == Color.Black', settings)
        self.assertIn('MiuixTheme.colorScheme.surfaceContainerHigh', settings)
        shell = (SRC / 'VoicePersonalScaffold.kt').read_text()
        for override in ('LocalSettingsPageStyle provides', 'MaterialTheme(', 'MiuixTheme(', 'Color('):
            self.assertNotIn(override, shell)

    def test_quota_cards_share_settings_fill_and_edge_without_recoloring_text(self):
        options = (SRC / 'ui/components/OptionSelectionSheet.kt').read_text()
        self.assertIn('cardStyle -> Brush.linearGradient(colors.sheet)', options)
        self.assertIn('cardStyle && selected -> SolidColor(colors.selectionContainer)', options)
        self.assertIn('if (selected) colors.selectionContent else colors.edge, shape)', options)
        self.assertIn('color = if (selected) selectedContent else colors.text', options)
        self.assertIn('selected -> SolidColor(selectedBackground)', options)
        self.assertNotIn('cardStyle -> colors.bubble', options)

    def test_recognition_save_reuses_selected_tab_fill_without_changing_foregrounds(self):
        panel = (SRC / 'VoiceSettingsPanel.kt').read_text()
        asr = panel.split('if (child == "ASR") {', 1)[1].split('} else if (child == "MIMO")', 1)[0]
        self.assertIn('LinuxSettingsTheme { SettingsSegmentedTabs(', asr)
        save = asr.split('TextButton("保存连接设置"', 1)[1].split('SettingsRow("高级：更换识别服务"', 1)[0]
        self.assertIn('ButtonDefaults.textButtonColorsPrimary(color = DaiyuPalette.switchTrack)', save)
        for override in ('textColor =', 'disabledColor =', 'disabledTextColor =', 'Color('):
            self.assertNotIn(override, save)
        local_theme = (SRC / 'ui/screens/settings/LinuxSettingsTheme.kt').read_text()
        self.assertIn('val primary = DaiyuPalette.switchTrack', local_theme)
        self.assertIn('MiuixTheme.colorScheme.copy(primary = primary', local_theme)
        tabs = (SRC / 'ui/screens/settings/SettingsSegmentedTabs.kt').read_text()
        self.assertIn('.background(if (active) colors.primary else colors.primaryContainer)', tabs)

    def test_visual_regression_compares_real_shared_components_and_themes(self):
        visual = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceSettingsPaletteActivityTest.kt').read_text()
        for marker in ('SettingsScaffoldPage(', 'VoicePersonalScaffold(', 'VoicePersonalEmptyPage(',
                       'VoiceSettingsCard(', 'SettingsCard(', 'captureToImage().toPixelMap()',
                       'pureBlackEnabled = pureBlack',
                       'LocalSettingsPageStyle.current', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(marker, visual)


if __name__ == '__main__':
    unittest.main()
