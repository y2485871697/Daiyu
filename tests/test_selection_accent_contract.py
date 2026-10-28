"""Selection indicators share an accent; filled contrast marks and status icons keep their roles."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
def source(name): return (SRC / name).read_text()

class SelectionAccentContractTest(unittest.TestCase):
    def test_existing_foreground_is_single_source_not_material_primary(self):
        appearance = source('ui/app/AppearanceColors.kt')
        self.assertIn('Color(if (dark) 0xFFC6B6D5 else 0xFF82718F)', appearance)
        self.assertIn('resolveSelectionAccent(LocalAppearanceDark.current)', source('ui/app/DaiyuPalette.kt'))
        usage = source('ui/components/UsagePresentation.kt')
        self.assertIn('val accent = resolveSelectionAccent(dark)', usage)
        self.assertIn('val primary = resolveSelectionAccent(dark)', usage)

    def test_material_defaults_only_override_enabled_selected_color(self):
        controls = source('ui/haptics/material/HapticMaterialControls.kt')
        self.assertIn('CheckboxDefaults.colors(checkedColor = DaiyuPalette.selectionAccent)', controls)
        self.assertIn('RadioButtonDefaults.colors(selectedColor = DaiyuPalette.selectionAccent)', controls)
        for name in ('Checkbox', 'RadioButton'):
            block = controls.split('internal fun ' + name + '(', 1)[1].split('\n@Composable', 1)[0]
            self.assertIn('colors = colors', block)
            self.assertIn('enabled = enabled', block)
            self.assertNotIn('disabledSelectedColor', block)
            self.assertNotIn('uncheckedColor', block)

    def test_settings_radio_check_has_no_independent_primary_fallback(self):
        block = source('ui/screens/settings/SettingsUiComponents.kt').split('internal fun SettingsRadioRow(', 1)[1].split('/** Foundation editor', 1)[0]
        self.assertIn('tint = DaiyuPalette.selectionAccent', block)
        self.assertIn('role = Role.RadioButton, enabled = enabled, onClick = onClick', block)
        self.assertNotIn('readableAccent', block)
        self.assertNotIn('colorScheme.primary', block)

    def test_explicit_settings_selection_overrides_share_accent(self):
        for path in ('ProviderDirectoryPage.kt', 'LocalCatalogModelsScreen.kt'):
            text = source('ui/screens/settings/' + path)
            self.assertIn('background(if (checked) DaiyuPalette.selectionAccent else colors.surfaceVariant)', text)
            self.assertIn('tint = DaiyuPalette.selectionAccent)', text)
            self.assertIn('tint = colors.onPrimary, size = 18.dp', text)
        self.assertIn('checkedColor = DaiyuPalette.selectionAccent', source('ui/screens/settings/ProviderModelSelectionDialog.kt'))
        self.assertIn('tint = DaiyuPalette.selectionAccent, size = 24.dp', source('ui/screens/settings/RunningModeSettingsScreen.kt'))
        theme = source('ui/screens/settings/AppearanceSettingsScreen.kt')
        self.assertIn('if (selected) DaiyuPalette.selectionAccent else colors.outline', theme)
        self.assertIn('.background(DaiyuPalette.selectionAccent, CircleShape)', theme)
        self.assertIn('tint = colors.onPrimary, size = 13.dp', theme)

    def test_question_option_changes_indicator_not_recommendation_or_actions(self):
        option = source('ui/screens/chat/conversation/QuestionCard.kt').split('private fun QuestionOptionRow(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('val selectionAccent = DaiyuPalette.selectionAccent', option)
        self.assertIn('if (selected) selectionAccent else colors.muted', option)
        self.assertIn('background(selectionAccent, CircleShape)', option)
        self.assertIn('Text("推荐", color = colors.accent', option)
        self.assertIn('role = Role.RadioButton', option)

    def test_explicit_chat_and_workspace_selections_use_shared_role(self):
        self.assertIn('selectedColor = DaiyuPalette.selectionAccent, unselectedColor = colors.muted', source('ui/screens/chat/context/CompressionConversationDialog.kt'))
        workspace = source('ui/components/ConversationWorkspaceSheet.kt')
        self.assertIn('checkedColor = DaiyuPalette.selectionAccent', workspace)
        self.assertIn('"当前工作区", tint = DaiyuPalette.selectionAccent', workspace)
        self.assertIn('checkmarkColor = actions.onPrimary', workspace)
        self.assertIn('background(if (isSelected) colors.accent else Color.Transparent, CircleShape)', source('ui/screens/chat/conversation/ChatMenus.kt'))

    def test_filled_checkmarks_and_success_states_keep_their_original_roles(self):
        self.assertIn('tint = Color.White', source('ui/screens/chat/conversation/WorkProcessCard.kt'))
        self.assertIn('Color(0xFF34C759)', source('browser/ported/ui/browser/BrowserDownloadsSheet.kt'))
        option = source('ui/components/OptionSelectionSheet.kt')
        self.assertIn('else colors.accent', option)
        self.assertIn('tint = if (cardStyle) colors.selectionContainer', option)

if __name__ == '__main__': unittest.main()
