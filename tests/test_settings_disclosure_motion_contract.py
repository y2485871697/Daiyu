"""Settings motion wiring guards; intermediate frames are covered by Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'


class SettingsDisclosureMotionContractTest(unittest.TestCase):
    def test_shared_motion_changes_layout_in_both_directions(self):
        source = (UI / 'SettingsDisclosureMotion.kt').read_text()
        for call in ('AnimatedVisibility(', 'visible = expanded', 'expandVertically(',
                     'shrinkVertically(', 'Alignment.Top', 'fadeIn(', 'fadeOut('):
            self.assertIn(call, source)
        self.assertNotIn('delay(', source)
        self.assertNotIn('animateContentSize', source)
        self.assertNotIn('if (expanded) AnimatedVisibility', source)
        self.assertIn('animateFloatAsState(', source)
        self.assertIn('return rotate(rotation)', source)

    def test_all_manual_settings_disclosures_use_the_shared_motion(self):
        calls = {
            'PetSettingsScreen.kt': ['SettingsExpandableContent(expanded)', 'settingsDisclosureRotation(expanded)'],
            'HapticsSettingsScreen.kt': ['SettingsExpandableContent(expanded && settings.backgroundOn)', 'settingsDisclosureRotation(expanded)'],
            'AppearanceSettingsScreen.kt': ['SettingsExpandableContent(state.morphLoadingIndicator && loadingExpanded)', 'settingsDisclosureRotation(iconExpanded, expandedDegrees = 180f)'],
            'IconAppearanceEditor.kt': ['SettingsExpandableContent(expanded)', 'currentExpanded && currentOwner()'],
            'ProviderInlineEditors.kt': ['SettingsExpandableContent(expanded)', 'settingsDisclosureRotation(expanded, expandedDegrees = 180f)'],
            'SettingsHomeScreen.kt': ['SettingsExpandableContent(relayExpanded)', 'settingsDisclosureRotation(relayExpanded)'],
        }
        for name, expected in calls.items():
            with self.subTest(file=name):
                source = (UI / name).read_text()
                for call in expected:
                    self.assertIn(call, source)
        editor = (UI / 'IconAppearanceEditor.kt').read_text()
        self.assertNotIn('if (!expanded) return', editor)
        self.assertEqual(2, (UI / 'ProviderInlineEditors.kt').read_text().count('SettingsExpandableContent(expanded)'))

    def test_exiting_content_loses_actions_and_only_clears_its_own_focus(self):
        source = (UI / 'SettingsDisclosureMotion.kt').read_text()
        self.assertIn('if (!expanded && hasFocus) focusManager.clearFocus(force = true)', source)
        self.assertIn('onFocusChanged { hasFocus = it.hasFocus }', source)
        self.assertIn('onEnter = { if (!expanded) cancelFocusChange() }', source)
        self.assertIn('.focusGroup()', source)
        self.assertIn('clearAndSetSemantics {}', source)
        self.assertIn('PointerEventPass.Initial', source)
        self.assertIn('it.consume()', source)
        fields = (UI / 'ProviderInlineEditors.kt').read_text()
        self.assertIn('latestEnabled && latestExpanded && latestDraft === draft', fields)
        self.assertIn('if (latestExpanded) onDraftChange(it)', fields)

    def test_android_tests_control_motion_instead_of_system_settings(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/SettingsDisclosureMotionTest.kt').read_text()
        for call in ('MotionDurationScale', 'ui.mainClock.autoAdvance = false',
                     'getUnclippedBoundsInRoot()', 'testBothDirectionsMoveCardAndFollowingContent',
                     'testRapidReversalFollowsTheLatestTarget', 'testZeroAndDoubleDurationScale',
                     'testCollapsingFieldsLoseFocusAndActions'):
            self.assertIn(call, source)
        self.assertNotIn('Thread.sleep', source)
        self.assertNotIn('Settings.Global', source)

    def test_reversal_samples_start_after_the_retarget_helper_frame(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/SettingsDisclosureMotionTest.kt').read_text()
        test = source.split('fun testRapidReversalFollowsTheLatestTarget()', 1)[1].split('fun testZeroAndDoubleDurationScale()', 1)[0]
        self.assertLess(test.index('mainThread { expanded.value = false }'), test.index('val closingStart = height()'))
        self.assertLess(test.index('mainThread { expanded.value = true }'), test.index('val reopeningStart = height()'))
        for token in ('reversing < closingStart', 'reversing > collapsed', 'height() > reopeningStart',
                      'afterTop() < closingTop', 'afterTop() > reopeningTop'):
            self.assertIn(token, test)
        self.assertEqual(2, test.count('advanceTimeBy(48)'))


if __name__ == '__main__':
    unittest.main()
