"""Source wiring only; persistence and Compose behavior regressions run with the app tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
UI = SRC / 'ui/components'


class SubAgentProfileDeleteContractTest(unittest.TestCase):
    def test_existing_and_new_profiles_have_different_secondary_actions(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('val editing = session.expectedProfile != null', form)
        self.assertIn('if (editing) "删除" else "取消"', form)
        self.assertIn('if (expected == null) userDismiss()', form)
        footer = form.split('private fun SubAgentDraftDialogActions', 1)[1]
        self.assertIn('ConversationAgentButton(secondaryLabel, secondaryEnabled', footer)
        self.assertIn('MiuixTextButton(text = secondaryLabel', footer)
        self.assertNotIn('onCancel', footer)

    def test_delete_uses_opened_profile_ticket_and_application_not_edited_draft(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('onSecondary = {', form)
        action = form.split('onSecondary = {', 1)[1].split('}, onConfirm = {', 1)[0]
        for text in ('val expected = session.expectedProfile', 'val ticket = session.openToken',
                     'session.editor.removeProfileDraft(expected, session.expectedApplicationToken,',
                     'session.canDelete(latestEditor, latestEnabled, ticket)',
                     'session.submitting = true', 'finally { session.submitting = false }',
                     'result is ConversationSubAgentPreferences.WriteResult.Saved', '未删除'):
            self.assertIn(text, action)
        for text in ('session.name', 'session.draft', 'parallelChange', 'commitProfileDraft'):
            self.assertNotIn(text, action)

    def test_invalid_or_unavailable_model_does_not_block_deletion(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('fun canDelete(', form)
        delete_gate = form.split('fun canDelete(', 1)[1].split('fun edit(', 1)[0]
        for text in ('expectedProfile != null', 'ticket === openToken', 'isCurrent(currentEditor, allowed)', 'editor.enabled'):
            self.assertIn(text, delete_gate)
        for text in ('boundConfig', 'modelRefreshing', 'parallelIsValid', 'name.trim'):
            self.assertNotIn(text, delete_gate)
        self.assertIn('!session.submitting && (!editing || session.canDelete(currentEditor, enabled))', form)

    def test_editor_updates_state_only_from_saved_deletion(self):
        editor = (UI / 'ConversationSubAgentEditor.kt').read_text()
        self.assertIn('fun removeProfileDraft(', editor)
        block = editor.split('fun removeProfileDraft(', 1)[1].split('fun add()', 1)[0]
        self.assertIn('if (!enabled || !canCommit())', block)
        self.assertIn('repository.removeProfile(owner, expectedProfile, expectedApplicationToken) { enabled && canCommit() }', block)
        self.assertIn('result is ConversationSubAgentPreferences.WriteResult.Saved', block)
        self.assertIn('storedState = SubAgentEditorState.Loaded(result.config)', block)
        self.assertIn('catch (failure: Exception) { fail(failure)', block)
        self.assertNotIn('providerLookup', block)

    def test_repository_deletes_one_profile_under_the_existing_durability_transaction(self):
        text = (SRC / 'delegation/ConversationSubAgentPreferences.kt').read_text()
        self.assertIn('fun removeProfile(', text)
        block = text.split('fun removeProfile(', 1)[1].split('fun modelDefaults(', 1)[0]
        for part in ('= synchronized(lock)', 'ensureClean()', 'canCommit()', 'canEdit(owner)',
                     'presetExists(owner.value)', 'owner !is SubAgentConfigKey.Draft || stored(key(owner)) != null',
                     'current.presetApplicationToken != expectedApplicationToken',
                     'current.profiles.singleOrNull { it.id == expectedProfile.id } != expectedProfile',
                     'current.copy(profiles = current.profiles.filterNot { it.id == expectedProfile.id })',
                     'transaction(mapOf(key(owner) to encode(next)), owner)'):
            self.assertIn(part, block)
        self.assertEqual(2, block.count('if (!allowed()) return@synchronized WriteResult.Rejected'))
        for forbidden in ('SubAgentModelDefaults.KEY', 'parallelLimits =', 'removePreset(', 'delete(owner', '.cancel('):
            self.assertNotIn(forbidden, block)

    def test_subagent_picker_and_both_callers_have_no_clear_callback(self):
        for name in ('subagent/SubAgentModelPicker.kt', 'SubAgentProfileConfigDialog.kt', 'SubAgentProfileRow.kt'):
            text = (UI / name).read_text()
            self.assertNotIn('onClearSelection', text)
            self.assertNotIn('ModelFeatureSelection(true, "", "")', text)
        # Other feature pickers may still use their own meaningful empty selection.
        shared = (SRC / 'ui/screens/chat/conversation/ChatModelPicker.kt').read_text()
        self.assertIn('onClearSelection', shared)

    def test_editor_rejects_empty_binding_without_changing_legacy_storage_format(self):
        text = (UI / 'ConversationSubAgentEditor.kt').read_text()
        self.assertIn('require(selection.providerId.isNotBlank() && selection.modelId.isNotBlank())', text)
        commit = text.split('suspend fun commitProfileDraft(', 1)[1].split('fun removeProfileDraft(', 1)[0]
        self.assertIn('if (draft.providerId.isBlank() || draft.modelId.isBlank()) throw LostOwner()', commit)
        self.assertNotIn('if (draft.providerId.isBlank()) null', commit)
        save = text.split('fun saveModel(', 1)[1]
        self.assertIn('if (selection.providerId.isBlank() || selection.modelId.isBlank()) throw LostOwner()', save)
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('val ready = usable && boundConfig != null &&', form)
        self.assertNotIn('!hasModelIdentity ||', form)

    def test_settings_and_conversation_keep_the_same_shared_profile_dialog(self):
        for name in ('ui/SubAgentSettingsScreen.kt', 'ui/components/ConversationCollaborationDialog.kt'):
            self.assertIn('SubAgentProfileConfigDialog(session,', (SRC / name).read_text())


if __name__ == '__main__':
    unittest.main()
