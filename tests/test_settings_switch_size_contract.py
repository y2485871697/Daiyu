"""Settings-only switch sizing contracts; pixel and input assertions need Android."""
from pathlib import Path
from math import floor
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SETTINGS = SRC / 'ui/screens/settings'


class SettingsSwitchSizeContractTest(unittest.TestCase):
    def test_visual_is_scaled_separately_from_interaction_and_layout(self):
        text = (SETTINGS / 'SettingsSwitch.kt').read_text()
        self.assertIn('if (onCheckedChange != null)', text)
        self.assertIn('Modifier.size(48.dp).toggleable(', text)
        self.assertIn('else Modifier.size(36.4.dp, 22.4.dp)', text)
        self.assertIn('Box(modifier.then(target), contentAlignment = Alignment.Center)', text)
        self.assertIn('Modifier.requiredSize(52.dp, 32.dp).scale(.7f)', text)
        self.assertEqual(1, text.count('.scale('))
        self.assertNotIn('LocalDensity', text)
        self.assertNotIn('.clip', text)

    def test_one_haptic_owner_preserves_null_callbacks_and_availability(self):
        text = (SETTINGS / 'SettingsSwitch.kt').read_text()
        self.assertIn('import app.daiyu.android.ui.haptics.toggleable', text)
        self.assertIn('import app.daiyu.android.ui.haptics.material.Switch', text)
        self.assertIn('value = checked, enabled = enabled, role = Role.Switch', text)
        self.assertIn('onValueChange = onCheckedChange', text)
        self.assertEqual(1, text.count('interactionSource = remember { MutableInteractionSource() }'))
        self.assertEqual(2, text.count('interactionSource = interactionSource'))
        self.assertIn('indication = null', text)
        visual = text.split('        Switch(', 1)[1]
        for passed in ('checked = checked', 'onCheckedChange = null', 'enabled = enabled', 'colors = colors'):
            self.assertIn(passed, visual)
        self.assertNotIn('hapticChange(', text)
        self.assertNotIn('TouchHaptics.action', text)

    def test_settings_entry_points_use_one_shared_compact_switch(self):
        alias = 'import app.daiyu.android.ui.screens.settings.SettingsSwitch as Switch'
        for name in ('SettingsUiComponents.kt', 'HapticsSettingsScreen.kt', 'SkillManagementScreen.kt'):
            text = (SETTINGS / name).read_text()
            self.assertIn(alias, text)
            self.assertNotIn('import app.daiyu.android.ui.haptics.material.Switch', text)
        balance = (SRC / 'balance/ui/BalanceSettingsScreen.kt').read_text()
        self.assertIn(alias, balance)
        appearance = (SETTINGS / 'AppearanceSettingsScreen.kt').read_text()
        self.assertNotIn('morphLoadingIndicator', appearance)
        self.assertNotIn('app.daiyu.android.ui.haptics.material.Switch(', appearance)

    def test_existing_compact_subagent_switch_and_nonsettings_controls_are_untouched(self):
        subagent = (SRC / 'ui/SubAgentSettingsScreen.kt').read_text()
        self.assertIn('modifier = Modifier.scale(0.7f)', subagent)
        self.assertNotIn('SettingsSwitch(', subagent)
        self.assertIn('import app.daiyu.android.ui.haptics.material.Switch', subagent)
        visual = (SRC / 'ui/components/subagent/ConversationAgentStyle.kt').read_text()
        self.assertIn('Modifier.size(36.dp, 22.dp)', visual)
        for path in (SRC / 'ui/haptics', SRC / 'ui/screens/chat', SRC / 'ui/screens/assistants'):
            for file in path.rglob('*.kt'):
                self.assertNotIn('SettingsSwitch', file.read_text(), str(file))

    def test_row_busy_guard_and_independent_row_actions_are_preserved(self):
        row = (SETTINGS / 'SettingsUiComponents.kt').read_text().split('internal fun SettingsSwitchRow(', 1)[1].split('internal fun SettingsRadioRow(', 1)[0]
        for text in ('enabled = enabled && !busy', 'enabled = enabled,', 'onCheckedChange = null', 'accessory?.invoke()'):
            self.assertIn(text, row)
        haptics = (SETTINGS / 'HapticsSettingsScreen.kt').read_text()
        self.assertIn('if (canAct()) intensityDialog = true', haptics)
        self.assertIn('onCheckedChange = { if (save { copy(background = it) }) expanded = it }', haptics)
        appearance = (SETTINGS / 'AppearanceSettingsScreen.kt').read_text()
        self.assertNotIn('loadingExpanded', appearance)
        self.assertNotIn('loadingExpanded', appearance)

    def test_rounding_keeps_track_within_both_target_types(self):
        px = lambda dp, density: floor(dp * density + .5)
        for density in (1., 1.5, 2.75, 3.5):
            for interface_scale in (.8, 1.):
                d = density * interface_scale
                width, height = px(52, d) * .7, px(32, d) * .7
                self.assertLessEqual(abs(width - px(36.4, d)), 1)
                self.assertLessEqual(abs(height - px(22.4, d)), 1)
                self.assertLess(width, px(48, d))
                self.assertLess(height, px(48, d))

    def test_system_education_fixture_is_dismissed_before_uncropped_capture(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/SettingsSwitchSizeTest.kt').read_text()
        helper = text.split('private fun assertTrackWithControl(', 1)[1].split('private fun assertCompactTrack(', 1)[0]
        dismissal = 'dismissDesktopEducationTooltip(instrumentation, ui, node("switch-education-blank"))'
        self.assertEqual(1, helper.count(dismissal))
        self.assertLess(helper.index(dismissal), helper.index('captureScreenNode(canvas)'))
        self.assertEqual(2, text.count('size(32.dp).educationDismissalBlank()'))
        blank = text.split('private fun Modifier.educationDismissalBlank()', 1)[1].split('@OptIn', 1)[0]
        self.assertIn('testTag("switch-education-blank")', blank)
        self.assertIn('awaitPointerEvent().changes.forEach { it.consume() }', blank)
        self.assertNotIn('onCheckedChange', blank)
        self.assertNotIn('performClick', blank)
        self.assertNotIn('captureToImage', text)
        self.assertIn('if (distance < .015f)', text)

    def test_android_regressions_check_pixels_targets_semantics_rtl_and_callbacks(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/SettingsSwitchSizeTest.kt').read_text()
        for anchor in ('36.4f * pixelsPerDp', '22.4f * pixelsPerDp', '48f * pixelsPerDp', '64f * pixelsPerDp',
                       'listOf(false, true)', 'listOf(1f, .8f)', 'LayoutDirection.Rtl', '1.3f',
                       'captureScreenNode', 'Color(0xFF82728F)', 'assertCountEquals(1)', 'assertCountEquals(0)',
                       'click(Offset(centerX, 2f * pixelsPerDp))', 'target.assertIsNotEnabled()',
                       'assertEquals(2, calls)', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(anchor, text)
        self.assertNotIn('Thread.sleep', text)


if __name__ == '__main__':
    unittest.main()
