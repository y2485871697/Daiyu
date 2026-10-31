"""Per-profile enablement is not configurable; group automatic delegation stays separate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class SubAgentAlwaysEnabledContractTest(unittest.TestCase):
    def test_shared_add_and_edit_form_has_no_enable_control(self):
        form = (MAIN / 'ui/components/SubAgentProfileConfigDialog.kt').read_text()
        self.assertNotIn('Text("启用"', form)
        self.assertNotIn('draft.enabled', form)
        self.assertNotIn('copy(enabled =', form)
        self.assertIn('session.editor.removeProfileDraft(expected', form)
        for path in ('ui/SubAgentSettingsScreen.kt', 'ui/components/ConversationCollaborationDialog.kt'):
            self.assertIn('SubAgentProfileConfigDialog(', (MAIN / path).read_text())

    def test_settings_cards_cannot_disable_profiles(self):
        settings = (MAIN / 'ui/SubAgentSettingsScreen.kt').read_text()
        self.assertNotIn('profile.enabled', settings)
        self.assertNotIn('启用${profile.name}', settings)
        self.assertIn('editor.remove(profile.id)', settings)
        self.assertIn('Text("自动委派"', (MAIN / 'ui/components/ConversationCollaborationDialog.kt').read_text())

    def test_profile_has_no_mutable_enabled_state_but_writes_legacy_true(self):
        profile = (MAIN / 'delegation/SubAgentProfile.kt').read_text()
        self.assertNotIn('val enabled:', profile)
        self.assertNotIn('optBoolean("enabled"', profile)
        self.assertIn('.put("enabled", true)', profile)
        defaults = (MAIN / 'delegation/SubAgentModelDefaults.kt').read_text()
        self.assertNotIn('val enabled:', defaults)
        self.assertNotIn('profile.enabled', defaults)
        self.assertIn('.put("enabled", true)', defaults)

    def test_runtime_admits_existing_profiles_and_keeps_group_gate(self):
        runtime = (MAIN / 'delegation/SavedSubAgentRuntimeBindings.kt').read_text()
        self.assertIn('if (frozen.enabled) frozen.profiles else emptyList()', runtime)
        controller = (MAIN / 'ChatController.kt').read_text()
        self.assertNotIn('selection.enabled && it.enabled', controller)
        self.assertNotIn('it.copy(enabled = true)', controller)


if __name__ == '__main__':
    unittest.main()
