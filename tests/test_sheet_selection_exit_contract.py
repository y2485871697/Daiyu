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

    def test_only_one_shot_option_scope_opts_in(self):
        text = (UI / 'components/OptionSelectionSheet.kt').read_text()
        self.assertIn('staticCompositionLocalOf { false }', text)
        self.assertIn('LocalOptionSelectionClosesSheet provides true', text)
        self.assertIn('if (closesSheet) finishSelection(onClick) else onClick()', text)
        folder = (UI / 'components/ConversationFolderPicker.kt').read_text()
        self.assertIn('onClick = { selectedFolderId = folder.id }', folder)
        self.assertNotIn('OptionSelectionSheet(', folder)

    def test_subagent_and_settings_models_opt_in_without_changing_chat_default(self):
        text = (UI / 'screens/chat/conversation/ChatModelPicker.kt').read_text()
        self.assertIn('closeOnSelect: Boolean = false', text)
        self.assertIn('if (closeOnSelect) finishSelection(select) else select()', text)
        for path in ('components/subagent/SubAgentModelPicker.kt', 'screens/settings/SettingsModelPicker.kt'):
            self.assertIn('closeOnSelect = true', (UI / path).read_text())
        local = (UI / 'screens/chat/conversation/LocalCatalogChatModelPicker.kt').read_text()
        self.assertNotIn('closeOnSelect = true', local)

    def test_linux_accessibility_selection_also_requests_exit(self):
        text = (UI / 'screens/settings/LinuxSettingsScreen.kt').read_text()
        self.assertIn('hapticClick(enabled) { finishSelection(selectChoice) }', text)
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
