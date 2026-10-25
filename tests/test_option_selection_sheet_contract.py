"""Source wiring guards; visual and touch behavior is covered by Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(path):
    return (SRC / path).read_text()


class OptionSelectionSheetContractTest(unittest.TestCase):
    def test_small_choices_reuse_the_reference_sheet_without_fixed_height(self):
        text = source('ui/components/OptionSelectionSheet.kt')
        self.assertIn('ChatSelectionSheet(title = title, onDismiss = onDismiss, subtitle = subtitle, showCloseButton = false)', text)
        self.assertIn('.verticalScroll(rememberScrollState()).selectableGroup()', text)
        self.assertNotIn('height =', text)
        self.assertNotIn('fillMaxHeight', text)
        shell = source('ui/screens/chat/conversation/ChatSelectionSheet.kt')
        self.assertIn('PreviewDialog(onDismiss, bottomSheet = true)', shell)
        self.assertIn('RoundedCornerShape(25.dp)', shell)
        self.assertIn('.size(29.dp, 3.dp)', shell)
        self.assertIn('weight(1f, fill = height != null)', shell)

    def test_rows_match_model_highlight_checkmark_and_accessible_selection(self):
        text = source('ui/components/OptionSelectionSheet.kt')
        for fragment in ('Color(0xFFEFEDFB)', 'colors.bubble', 'RoundedCornerShape(12.dp)',
                         'Role.RadioButton', 'selected = selected, enabled = enabled',
                         'heightIn(min = 48.dp)', 'Modifier.size(22.dp).clip(CircleShape)',
                         'Icon(DaiyuIcons.Check, "已选择", Modifier.size(14.dp)',
                         'import app.daiyu.android.ui.haptics.selectable'):
            self.assertIn(fragment, text)
        self.assertNotIn('maxLines = 1', text)
        self.assertNotIn('.height(32.dp)', text)

    def test_provider_preserves_tags_lifecycle_and_exact_callback(self):
        text = source('ui/screens/settings/ProviderSettingsComponents.kt')
        self.assertIn('if (expanded && enabled) OptionSelectionSheet(title', text)
        self.assertIn('Modifier.testTag("$tag-menu")', text)
        self.assertIn('OptionSelectionItem(choice, selected = index == selectedIndex', text)
        self.assertIn('Modifier.testTag(optionTags[index])', text)
        self.assertIn('Lifecycle.Event.ON_PAUSE) expanded = false', text)
        self.assertIn('expanded && currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)', text)
        self.assertIn('expanded = false\n                        onSelect(index)', text)
        self.assertNotIn('DropdownMenu(', text)

    def test_all_four_provider_choice_callers_keep_their_business(self):
        settings = SRC / 'ui/screens/settings'
        calls = {p.name: p.read_text() for p in settings.glob('*.kt') if 'ProviderChoiceRow(' in p.read_text()}
        self.assertEqual(set(calls), {'ProviderSettingsComponents.kt', 'ModelSettingsScreen.kt',
                                    'ProviderBalanceFields.kt', 'CompressionSettingsForm.kt'})
        self.assertIn('draft.selectProtocol(protocols[choice])', calls['ProviderSettingsComponents.kt'])
        self.assertIn('BalanceOption.applyPreset', calls['ProviderBalanceFields.kt'])
        self.assertIn('selectedIndex = -1', calls['CompressionSettingsForm.kt'])

    def test_linux_keeps_scope_owner_and_option_identity(self):
        text = source('ui/screens/settings/LinuxSettingsScreen.kt')
        block = text.split('private fun LinuxChoiceRow(', 1)[1].split('private fun LinuxInstallAction(', 1)[0]
        for fragment in ('key(popupScope)', 'if (expanded && enabled) OptionSelectionSheet(title',
                         'if (enabled && expanded && ownerCurrent())', 'onSelect(index)',
                         'linux-choice:$tag:$index', 'selected = index == selectedIndex', 'semanticSelectChoice'):
            self.assertIn(fragment, block)
        self.assertNotIn('DropdownMenu(', block)

    def test_subagent_value_choices_are_separate_from_anchored_actions(self):
        row = source('ui/components/SubAgentProfileRow.kt')
        self.assertIn('if (usable && rolePicker) OptionSelectionSheet("职责"', row)
        self.assertIn('if (usable && tierPicker) OptionSelectionSheet("任务分工"', row)
        self.assertNotIn('SubAgentDropdownMenu(', row)
        tier = source('ui/components/SubAgentTaskTierButton.kt')
        self.assertIn('if (enabled && expanded) OptionSelectionSheet("任务分工"', tier)
        self.assertIn('DropdownMenu(expanded = expanded', tier)
        actions = source('ui/SubAgentSettingsScreen.kt')
        self.assertIn('if (editable) SubAgentDropdownMenu(expanded', actions)
        for label in ('配置', '重命名', '删除'):
            self.assertIn(f'Text("{label}"', actions)

    def test_resolution_selection_keeps_restrictions_and_binding_guards(self):
        text = source('ui/components/SubAgentProfileRow.kt')
        block = text.split('if (usable && resolutionPicker && config != null)', 1)[1].split('if (usable && modelPicker)', 1)[0]
        for fragment in ('OptionSelectionSheet("默认分辨率"', 'selected = profile.imageResolution == tier',
                         'enabled = usable && allowed', 'setOf("low", "high")',
                         'latest.providerId == profile.providerId && latest.modelId == profile.modelId && latest.role == "image_generation"'):
            self.assertIn(fragment, block)
        self.assertNotIn('AlertDialog(', block)

    def test_draft_choices_share_rows_and_only_edit_the_existing_draft(self):
        window = source('ui/components/subagent/ConversationAgentChoice.kt')
        self.assertIn('OptionSelectionSheet(title, onDismiss) { content() }', window)
        self.assertIn('OptionSelectionItem(label, selected, onClick = onClick)', window)
        self.assertNotIn('WindowDialog', window)
        form = source('ui/components/SubAgentProfileConfigDialog.kt').split('if (usable) choices?.let', 1)[1].split('private fun SubAgentDraftDialogActions', 1)[0]
        self.assertIn('session.edit { profile -> when (field)', form)
        self.assertIn('}; choices = null', form)
        self.assertNotIn('commitProfileDraft', form)
        self.assertNotIn('.verticalScroll(', form)

    def test_existing_models_actions_and_confirmations_are_not_migrated(self):
        model = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('ChatSelectionSheet(height = 500.dp', model)
        skills = source('ui/screens/settings/SkillManagementScreen.kt')
        self.assertIn('EtaDropdownMenu(expanded', skills)
        linux = source('ui/screens/settings/LinuxSettingsScreen.kt')
        self.assertIn('DaiyuConfirmDialog(', linux)
        menu = source('ui/screens/chat/conversation/ChatMenuLayer.kt')
        self.assertIn('androidx.compose.material3.DropdownMenu(', menu)


if __name__ == '__main__':
    unittest.main()
