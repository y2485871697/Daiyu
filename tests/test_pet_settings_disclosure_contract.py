"""Pet disclosure source guards; touch and lifecycle behavior need Android execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/PetSettingsScreen.kt"


class PetSettingsDisclosureContractTest(unittest.TestCase):
    def setUp(self):
        self.screen = SCREEN.read_text()

    def test_arrow_expands_in_place_without_actions_or_local_subpages(self):
        for obsolete in ("showMenu", "DaiyuChoiceSheet", "PetSettingsPage", "PageBackStack", "更多设置"):
            self.assertNotIn(obsolete, self.screen)
        self.assertIn('tag = "pet-options-toggle"', self.screen)
        self.assertIn('onClick = { expanded = !expanded }', self.screen)
        self.assertIn('if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight', self.screen)
        self.assertIn('stateDescription = if (expanded) "已展开" else "已收起"', self.screen)

    def test_disclosure_is_initially_collapsed_and_saveable_per_host(self):
        self.assertIn('var expanded by rememberSaveable(host) { mutableStateOf(false) }', self.screen)
        body = self.screen.split('if (expanded) {', 1)[1]
        for tag in ('pet-work-reactions', 'pet-global-visible', 'pet-custom-model', 'pet-model-configure'):
            self.assertIn('tag = "' + tag + '"', body)
        self.assertNotIn('if (settings.enabled', self.screen)

    def test_power_switch_is_independent_of_disclosure(self):
        header = self.screen.split('SettingsRow("鲸鱼女仆"', 1)[1].split('if (expanded) {', 1)[0]
        self.assertIn('SettingsSwitch(checked = settings.enabled', header)
        self.assertIn('onCheckedChange = { host.setEnabled(it) }', header)
        self.assertIn('Modifier.testTag("pet-enabled")', header)
        self.assertNotIn('SettingsSwitchRow("鲸鱼女仆"', self.screen)
        self.assertIn('contentDescription = "鲸鱼女仆开关"', header)
        self.assertLess(header.index('DaiyuIcons.ChevronDown'), header.index('SettingsSwitch('))
        self.assertEqual(1, header.count('expanded = !expanded'))

    def test_model_choice_and_save_guards_remain_real(self):
        self.assertIn('IndependentModelSettings.Slot.PET', self.screen)
        self.assertIn('enabled && (!model.busy || model.saving)', self.screen)
        self.assertIn('busy = model.saving', self.screen)
        self.assertIn('onCheckedChange = model.onEnabledChange', self.screen)
        self.assertIn('if (enabled && !model.busy) model.onChoose else null', self.screen)
        self.assertIn('enabled = !snapshot.closed', self.screen)

    def test_permission_confirmation_is_not_removed_with_action_sheet(self):
        self.assertIn('if (!host.snapshot().canDrawOverlays) showPermission = true', self.screen)
        self.assertIn('DaiyuConfirmDialog("允许悬浮显示"', self.screen)
        self.assertIn('showPermission = false; onOpenOverlayPermission()', self.screen)
        self.assertIn('onOverlayAllowedChange(false)', self.screen)
        self.assertIn('onOverlayAllowedChange(true)', self.screen)

    def test_android_regression_uses_real_touches_and_isolated_host(self):
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ActivityLifecycleTest.kt').read_text()
        self.assertIn('fun testPetSettingsDisclosureAndPowerSwitchStayIndependent()', tests)
        body = tests.split('fun testPetSettingsDisclosureAndPowerSwitchStayIndependent()', 1)[1].split('fun testPetRuntimeFailureDoesNotAppearOnSettingsPage()', 1)[0]
        self.assertIn('PetApplicationHost(petContext', body)
        self.assertIn('ui.onNodeWithTag("pet-options-chevron", useUnmergedTree = true).performTouchInput { click() }', body)
        self.assertIn('node("pet-enabled").performTouchInput { click() }', body)
        self.assertIn('assertEquals(before, isolated.host.snapshot().pet.state.settings)', body)
        self.assertIn('assertEquals(0, backs.get())', body)
        self.assertIn('current.setContent {}', body)
        self.assertIn('pets?.close()', body)
        self.assertNotIn('Thread.sleep', body)


if __name__ == '__main__':
    unittest.main()
