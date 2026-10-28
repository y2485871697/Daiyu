from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android'


class ConversationWorkspacesContract(unittest.TestCase):
    def source(self, path):
        return (BASE / path).read_text()

    def test_live_owner_wires_each_operation_and_selection(self):
        main = self.source('MainActivity.kt')
        for name in ['onSelectWorkspace = { selectConversationWorkspace(visit, it) }',
                     'onDeleteWorkspaces = { removeConversationWorkspaces(visit, it) }',
                     'onEditWorkspace = { editConversationWorkspace(visit, it) }',
                     'replaceIndex = visit.editingWorkspaceIndex',
                     'editingWorkspaceIndex = editingIndex',
                     'editingIndex = null)',
                     'activeWorkspaceIndex = visit.editor.workspaces.activeIndex',
                     'workspaceCurrent(saving) && workspaceVisit === saving',
                     'controller.workspaceEditorOnWorker(visit.conversation)',
                     'selectionMode = false, selectedWorkspaces = emptySet()']:
            self.assertIn(name, main)

    def test_delete_is_only_cas_metadata_not_directory_validation(self):
        controller = self.source('ChatController.kt')
        remove = controller.split('internal fun removeWorkspacesOnWorker', 1)[1].split('private fun updateWorkspacesOnWorker', 1)[0]
        self.assertIn('validateActive = false', remove)
        self.assertIn('it.remove(indices)', remove)
        operation = controller.split('private fun updateWorkspacesOnWorker', 1)[1].split('internal fun freezeConversationWorkspaceOnWorker', 1)[0]
        self.assertIn('if (!idleAvailable() || hasLiveChildTasks()) return false', operation)
        self.assertIn('database.bindWorkspace(owner, expected, encoded)', operation)
        self.assertIn('if (validateActive)', operation)
        self.assertIn('validationIndex?.let { workspaces.bindings[it] } ?: workspaces.active', operation)
        self.assertIn('} else commit()', operation)
        self.assertNotIn('deleteRecursively', operation)
        self.assertNotIn('directory(', operation)

    def test_runtime_decodes_only_active_but_snapshot_remains_whole(self):
        codec = self.source('workspace/ConversationWorkspaceCodec.kt')
        self.assertIn('decodeWorkspaces(value).active', codec)
        self.assertIn('size <= 4096', codec)
        self.assertIn('decodeSingle(values.getJSONObject(index).toString())', codec)
        controller = self.source('ChatController.kt')
        self.assertIn('token.workspaceBinding = raw', controller)
        self.assertIn('expectedWorkspaceBinding = token.workspaceBinding', controller)

    def test_empty_selection_stays_in_management_and_no_ui_owned_persistence(self):
        sheet = self.source('ui/components/ConversationWorkspaceSheet.kt')
        self.assertIn('val selecting = selectionMode', sheet)
        self.assertIn('enabled = !busy && selectedWorkspaces.isNotEmpty()', sheet)
        self.assertIn('onSelectionChange(false, emptySet())', sheet)
        self.assertNotIn('mutableStateOf', sheet)
        self.assertNotIn('java.io', sheet)


    def test_real_activity_waits_for_loaded_directory_on_the_test_thread(self):
        android = Path(__file__).resolve().parents[1] / 'app/src/androidTest/kotlin/app/daiyu/android'
        source = (android / 'ConversationWorkspacesActivityTest.kt').read_text()
        helper = source.split('private fun awaitLoadedDirectory(', 1)[1].split('fun test', 1)[0]
        for token in ('ui.waitUntil(5_000)', 'ui.mainClock.advanceTimeByFrame()',
                      'hasTestTag("workspace-current-path") and hasText(path)',
                      'hasTestTag("workspace-save") and isEnabled()',
                      'node("workspace-error").assertDoesNotExist()'):
            self.assertIn(token, helper)
        self.assertNotIn('awaitControl("edited subdirectory loaded")', source)
        self.assertIn('awaitUniqueTag("workspace-folder:new-path")', source)
        self.assertIn('awaitLoadedDirectory(edited.canonicalPath)', source)
        self.assertIn('testRealActivityAddUsesControllerRootAndPersistsOnlyAfterLoadedSelection', source)
        self.assertIn('awaitLoadedDirectory(root.canonicalPath)', source)
        self.assertIn('assertNull(fixture.database.workspaceBinding(owner.id))', source)
        self.assertIn('ConversationWorkspaceBinding.Private(added.canonicalPath), saved.active', source)
        cancelled = (android / 'ConversationWorkspaceActivityTest.kt').read_text().split(
            'fun testRealChatFocusedEditorOpensActualSheetAndHidesIme()', 1)[1].split('private fun showComposer()', 1)[0]
        self.assertIn('hasTestTag("workspace-save") and isEnabled()', cancelled)
        self.assertIn('node("workspace-error").assertDoesNotExist()', cancelled)

if __name__ == '__main__':
    unittest.main()
