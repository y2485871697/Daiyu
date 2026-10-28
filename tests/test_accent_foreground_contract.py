"""Custom accent roles preserve selected color; surface-independent text and custom bubble contrast stay separate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

def source(name):
    return (SRC / name).read_text()

class AccentForegroundContractTest(unittest.TestCase):


    def test_material_and_local_filled_controls_do_not_derive_black_from_accent_brightness(self):
        theme = source('ui/app/AgentAppTheme.kt')
        self.assertEqual(2, theme.count('onTertiary = materialDefaults.onTertiary'))
        for name in ['ui/components/UsagePresentation.kt', 'ui/components/subagent/ConversationAgentStyle.kt',
                     'ui/screens/settings/LinuxSettingsTheme.kt']:
            self.assertNotIn('colorContentColor', source(name))
        self.assertIn('val onPrimary = Color.White', source('ui/screens/settings/LinuxSettingsTheme.kt'))


    def test_settings_selected_check_uses_shared_exact_primary_selection_role(self):
        rows = source('ui/screens/settings/SettingsUiComponents.kt')
        self.assertIn('tint = DaiyuPalette.selectionAccent', rows)
        self.assertIn('resolveSelectionAccent(LocalAppearanceDark.current)', source('ui/app/DaiyuPalette.kt'))
        self.assertNotIn('readableAccent(MiuixTheme.colorScheme.primary', rows)

if __name__ == '__main__': unittest.main()
