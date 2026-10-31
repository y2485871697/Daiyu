"""Bounded source wiring checks; frame behavior is covered by Android fixtures."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui'

class SheetSelectionExitContractTest(unittest.TestCase):
    def test_action_is_first_wins_and_delivered_after_both_exit_animations(self):
        text = (UI / 'components/DialogMotion.kt').read_text()
        request = text.split('fun requestAction(', 1)[1].split('fun takeAction', 1)[0]
        self.assertIn('if (dismissSource != null) return', request)
        self.assertIn('dismissAction = action', request)
        provider = text.split('internal fun DialogMotionProvider(', 1)[1].split('internal fun Modifier.dialogDismissInputGuard', 1)[0]
        self.assertLess(provider.index('motion.scrimProgress.animateTo'), provider.index('motion.takeAction()?.invoke()'))
        self.assertLess(provider.index('motion.progress.animateTo'), provider.index('motion.takeAction()?.invoke()'))
        self.assertIn('Lifecycle.Event.ON_PAUSE) motion.cancelDismiss()', provider)
        self.assertNotIn('delay(', provider)

    def test_value_options_invoke_immediately_without_requesting_exit(self):
        text = (UI / 'components/OptionSelectionSheet.kt').read_text()
        self.assertNotIn('LocalOptionSelectionClosesSheet', text)
        self.assertNotIn('rememberBottomSheetAction', text)
        self.assertIn('onClick = onClick', text)
        folder = (UI / 'components/ConversationFolderPicker.kt').read_text()
        self.assertIn('onClick = { selectedFolderId = folder.id }', folder)
        self.assertNotIn('OptionSelectionSheet(', folder)

    def test_all_model_options_select_without_implicit_exit(self):
        text = (UI / 'screens/chat/conversation/ChatModelPicker.kt').read_text()
        self.assertNotIn('closeOnSelect', text)
        self.assertNotIn('rememberBottomSheetAction', text)
        self.assertIn('if (provider.id == state.providerId) onSelect(model.id)', text)
        for path in ('components/subagent/SubAgentModelPicker.kt', 'screens/settings/SettingsModelPicker.kt'):
            self.assertNotIn('closeOnSelect', (UI / path).read_text())
        local = (UI / 'screens/chat/conversation/LocalCatalogChatModelPicker.kt').read_text()
        self.assertNotIn('closeOnSelect = true', local)

    def test_linux_accessibility_and_pointer_selection_both_stay_open(self):
        text = (UI / 'screens/settings/LinuxSettingsScreen.kt').read_text()
        self.assertIn('hapticClick(enabled) { selectChoice() }', text)
        self.assertNotIn('expanded = false; onSelect(index)', text)
        self.assertIn('onClick { semanticSelectChoice(); true }', text)

    def test_attachment_and_workspace_replacement_wait_before_next_action(self):
        text = (UI / 'screens/chat/conversation/ChatMenus.kt').read_text()
        self.assertIn('finishSelection { onDismiss(); onAction(entry.action) }', text)
        text = (UI / 'components/WorkspaceEntryDialogs.kt').read_text()
        self.assertIn('finishSelection {\n                                if (enabled()) when (id)', text)
        self.assertIn('isFailure) invalidName = true', text)

    def test_child_choices_keep_draft_confirmation_and_parallel_state_gates(self):
        text = (UI / 'components/SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('session.canCommit(latestEditor, latestEnabled)', text)
        self.assertIn('session.parallelBoundTo(boundConfig)', text)
        self.assertIn('SubAgentChoiceWindow(', text)
        self.assertIn('ConversationAgentChoice(', text)

    def test_async_form_confirmations_do_not_force_selection_exit(self):
        for path in ('screens/settings/ProviderModelSelectionDialog.kt', 'screens/settings/CatalogModelEditorDialog.kt'):
            text = (UI / path).read_text()
            self.assertNotIn('rememberBottomSheetAction', text)
        voice = (ROOT / 'app/src/main/kotlin/app/daiyu/android/VoiceSettingsDialogs.kt').read_text()
        model = voice.split('internal fun VoiceModelDialog(', 1)[1].split('internal val SettingsSpeechModelChoice', 1)[0]
        self.assertNotIn('closeOnSelect = true', model)
        self.assertIn('if (alive.get() && current()) onSave(next)', model)

if __name__ == '__main__':
    unittest.main()
