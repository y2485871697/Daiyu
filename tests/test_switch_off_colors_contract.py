"""OFF-state palette and routing; actual thumb pixels are covered by Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class SwitchOffColorsContractTest(unittest.TestCase):
    def test_off_palette_uses_visible_summary_thumb_and_surface_track(self):
        path = SRC / 'ui/app/DaiyuSwitchColors.kt'
        self.assertTrue(path.is_file(), 'shared OFF-state palette must exist')
        text = path.read_text()
        for value in ('return base.copy(',
                      'uncheckedThumbColor = colors.onSurfaceVariantSummary',
                      'uncheckedTrackColor = colors.surface',
                      'uncheckedBorderColor = colors.onSurfaceVariantSummary'):
            self.assertIn(value, text)

    def test_disabled_off_has_dimmed_thumb_not_a_filled_capsule(self):
        path = SRC / 'ui/app/DaiyuSwitchColors.kt'
        self.assertTrue(path.is_file(), 'shared OFF-state palette must exist')
        text = path.read_text()
        for value in ('val disabledOutline = colors.onSurfaceVariantSummary.copy(alpha = .38f)',
                      'disabledUncheckedThumbColor = disabledOutline',
                      'disabledUncheckedTrackColor = colors.surface',
                      'disabledUncheckedBorderColor = disabledOutline'):
            self.assertIn(value, text)

    def test_existing_on_and_muted_colors_are_preserved_from_base(self):
        path = SRC / 'ui/app/DaiyuSwitchColors.kt'
        self.assertTrue(path.is_file(), 'shared OFF-state palette must exist')
        text = path.read_text()
        copied = text.split('return base.copy(', 1)[1]
        for line in copied.splitlines():
            if 'Color =' in line:
                self.assertTrue(line.strip().startswith(('unchecked', 'disabledUnchecked')), line)
        skill = (SRC / 'ui/screens/settings/SkillManagementScreen.kt').read_text()
        self.assertIn('checkedTrack = if (muted) colors.onSurfaceVariantSummary else DaiyuPalette.switchTrack', skill)
        self.assertIn('disabledCheckedTrackColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f)', skill)

    def test_explicit_compact_palettes_and_defaults_use_the_shared_off_colors(self):
        text = (SRC / 'ui/screens/settings/SettingsSwitch.kt').read_text()
        self.assertIn('colors: SwitchColors = daiyuSwitchColors()', text)
        self.assertIn('colors = colors,', text)
        for name, count in (('HapticsSettingsScreen.kt', 3), ('AppearanceSettingsScreen.kt', 1)):
            self.assertEqual(count, (SRC / 'ui/screens/settings' / name).read_text().count('daiyuSwitchColors(SwitchDefaults.colors('))
        self.assertIn('daiyuSwitchColors(SwitchDefaults.colors(', (SRC / 'balance/ui/BalanceSettingsScreen.kt').read_text())
        self.assertIn('enabled = enabled,', text)
        self.assertIn('onCheckedChange = null', text)
        for name in ('HapticsSettingsScreen.kt', 'AppearanceSettingsScreen.kt', 'SkillManagementScreen.kt',
                     'PetSettingsScreen.kt', 'SettingsUiComponents.kt'):
            self.assertIn('SettingsSwitch', (SRC / 'ui/screens/settings' / name).read_text())
        self.assertIn('SettingsSwitch as Switch', (SRC / 'balance/ui/BalanceSettingsScreen.kt').read_text())

    def test_independent_subagent_accent_colors_are_also_normalized(self):
        text = (SRC / 'ui/SubAgentSettingsScreen.kt').read_text()
        self.assertIn('colors = daiyuSwitchColors(SwitchDefaults.colors(', text)
        self.assertIn('if (editor.enabled) { editor.updateProfile(profile.id)', text)
        self.assertIn('modifier = Modifier.scale(0.7f)', text)

    def test_default_material_controls_keep_callbacks_and_custom_palette_authority(self):
        text = (SRC / 'ui/haptics/material/HapticMaterialControls.kt').read_text()
        switch = text.split('internal fun Switch(', 1)[1].split('internal fun Checkbox(', 1)[0]
        self.assertIn('colors: SwitchColors = daiyuSwitchColors()', switch)
        self.assertIn('colors = colors,', switch)
        self.assertIn('onCheckedChange?.let { hapticChange(enabled, it) }', switch)
        self.assertIn('enabled = enabled,', switch)

    def test_android_regressions_check_thumb_interior_and_track_in_both_directions(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/SettingsSwitchOffStateTest.kt').read_text()
        for value in ('testCompactOffThumbStaysVisibleForPassiveAndInteractiveSettings',
                      'testDefaultAndAccentMaterialOffThumbStayVisible',
                      'listOf(false, true)', 'listOf(1f, .8f)', 'LayoutDirection.Ltr, LayoutDirection.Rtl',
                      'compositeOver(surface)', 'captureScreenNode', 'thumbX', 'trackX',
                      'assertIsNotEnabled()', 'assertEquals(0, calls)'):
            self.assertIn(value, text)


if __name__ == '__main__':
    unittest.main()
