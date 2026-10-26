"""Switch colour wiring only; Android pixel methods run in the approved UI batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SETTINGS = SRC / 'ui/screens/settings'
TOKEN = 'DaiyuPalette.switchTrack'


class SettingsSwitchAccentContractTest(unittest.TestCase):
    def test_checked_track_token_preserves_reference_and_global_primary_colours(self):
        palette = (SRC / 'ui/app/DaiyuPalette.kt').read_text()
        self.assertIn('val switchTrack: Color', palette)
        self.assertIn('LocalAppearanceSettings.current.resolvedPrimaryAccent ?: Color(0xFF82728F)', palette)
        self.assertIn('primary = Color(0xFF7563B4)', palette)
        self.assertIn('primary = Color(0xFFA491F1)', palette)
        style = (SRC / 'ui/components/subagent/ConversationAgentStyle.kt').read_text()
        switch = style.split('internal fun ConversationAgentSwitch(', 1)[1].split('internal fun ConversationAgentButton(', 1)[0]
        self.assertEqual(2, switch.count(f'if (checked) {TOKEN}'))
        for retained in ('Color(0x28b4aabb)', 'else colors.line', 'Color.White', 'Color(0xffa69baa)',
                         '.size(36.dp, 22.dp)', '.alpha(if (enabled) 1f else .4f)'):
            self.assertIn(retained, switch)

    def test_common_row_changes_only_checked_track_and_border(self):
        row = (SETTINGS / 'SettingsUiComponents.kt').read_text().split('internal fun SettingsSwitchRow(', 1)[1].split('internal fun SettingsRadioRow(', 1)[0]
        self.assertIn(f'checkedTrackColor = {TOKEN}', row)
        self.assertIn(f'checkedBorderColor = {TOKEN}', row)
        for retained in ('checkedThumbColor = colors.onPrimary',
                         'uncheckedThumbColor = colors.onSurfaceVariantSummary',
                         'uncheckedTrackColor = colors.surface',
                         'uncheckedBorderColor = colors.onSurfaceVariantSummary',
                         'enabled = enabled && !busy', 'enabled = enabled,',
                         'onCheckedChange = null', 'accessory?.invoke()'):
            self.assertIn(retained, row)
        self.assertNotIn('disabledChecked', row)

    def test_haptics_bypasses_share_track_without_replacing_row_actions(self):
        haptics = (SETTINGS / 'HapticsSettingsScreen.kt').read_text()
        self.assertEqual(3, haptics.count(f'checkedTrackColor = {TOKEN}'))
        self.assertEqual(3, haptics.count(f'checkedBorderColor = {TOKEN}'))
        for retained in ('if (canAct()) intensityDialog = true',
                         'else { expanded = !expanded }',
                         'onCheckedChange = { if (save { copy(background = it) }) expanded = it }',
                         'enabled = settings.generationOn', 'onCheckedChange = null'):
            self.assertIn(retained, haptics)

    def test_appearance_loading_switch_preserves_expansion_and_value_callbacks(self):
        appearance = (SETTINGS / 'AppearanceSettingsScreen.kt').read_text()
        self.assertEqual(1, appearance.count(f'checkedTrackColor = {TOKEN}'))
        self.assertEqual(1, appearance.count(f'checkedBorderColor = {TOKEN}'))
        self.assertIn('else loadingExpanded = !loadingExpanded', appearance)
        self.assertIn('onCheckedChange = { loadingExpanded = it; onChange(state.copy(morphLoadingIndicator = it)) }', appearance)

    def test_skill_muted_and_disabled_states_stay_distinct(self):
        skill = (SETTINGS / 'SkillManagementScreen.kt').read_text().split('private fun SkillSwitch(', 1)[1].split('private class SkillInstallPageOwner', 1)[0]
        self.assertIn(f'val checkedTrack = if (muted) colors.onSurfaceVariantSummary else {TOKEN}', skill)
        for retained in ('checkedTrackColor = checkedTrack', 'checkedBorderColor = checkedTrack',
                         'checkedThumbColor = if (muted) colors.surface else colors.onPrimary',
                         'disabledCheckedTrackColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f)',
                         'disabledCheckedBorderColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f)',
                         'disabledUncheckedTrackColor = colors.surface'):
            self.assertIn(retained, skill)

    def test_subagent_and_balance_direct_switches_share_track_and_keep_authority(self):
        subagent = (SRC / 'ui/SubAgentSettingsScreen.kt').read_text()
        balance = (SRC / 'balance/ui/BalanceSettingsScreen.kt').read_text()
        for text in (subagent, balance):
            self.assertEqual(1, text.count(f'checkedTrackColor = {TOKEN}'))
            self.assertEqual(1, text.count(f'checkedBorderColor = {TOKEN}'))
        self.assertIn('if (editor.enabled) { editor.updateProfile(profile.id) { it.copy(enabled = active) } }', subagent)
        self.assertIn('modifier = Modifier.scale(0.7f)', subagent)
        self.assertIn('onChange(option.copy(enabled = it))', balance)
        self.assertIn('if (it) expanded = true', balance)

    def test_pixel_regression_checks_both_themes_and_both_states(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/SettingsSwitchAccentTest.kt').read_text()
        for retained in ('listOf(false, true)', 'Color(0xFF82728F)', 'ConversationAgentSwitch(checked = checked',
                         'SettingsSwitchRow(', 'captureScreenNode', 'matchingPixels',
                         'assertIsOn()', 'assertIsOff()', 'assertEquals(1, calls)', 'fixture.opens.get()'):
            self.assertIn(retained, test)


if __name__ == '__main__':
    unittest.main()
