from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class WorkspaceSelectionBoundary(unittest.TestCase):
    def test_selection_reuses_resolver_and_component_boundary(self):
        source = (BASE / 'workspace/ConversationWorkspaceSelection.kt').read_text()
        self.assertIn('sourceId == "private"', source)
        self.assertIn('ConversationWorkspaceResolver(filesDir).resolve(', source)
        self.assertIn('host.toPath().startsWith(root.toPath())', source)
        self.assertNotIn('path.trim()', source)
        self.assertNotIn('host.path.startsWith', source)

    def test_browse_and_save_share_selection_policy(self):
        access = (BASE / 'workspace/ConversationWorkspaceAccess.kt').read_text()
        binding = access.split('fun binding(', 1)[1].split('fun directory(', 1)[0]
        directory = access.split('fun directory(', 1)[1].split('fun resolve(', 1)[0]
        self.assertIn('ConversationWorkspaceSelection(context.filesDir).binding(sourceId, path)', binding)
        self.assertIn('selection.binding(sourceId, path)', directory)
        self.assertIn('selection.parentPath(host)', directory)
        self.assertIn('freeze(raw).use', directory)
        self.assertNotIn('storageRoot', directory)
        controller = (BASE / 'ChatController.kt').read_text()
        save = controller.split('fun saveWorkspaceOnWorker(', 1)[1].split('fun ', 1)[0]
        self.assertIn('conversationWorkspaces.binding(source, path)', save)

    def test_picker_has_no_source_escape_and_opens_workspace_root(self):
        sheet = (BASE / 'ui/components/ConversationWorkspaceSheet.kt').read_text()
        self.assertIn('!busy && !listing && directory?.parentPath != null', sheet)
        self.assertNotIn('onSources', sheet)
        self.assertNotIn('onSourceChange', sheet)
        self.assertNotIn('workspace-source:', sheet)
        main = (BASE / 'MainActivity.kt').read_text()
        browse = main.split('ConversationWorkspaceSheet(', 1)[1].split('onNavigate =', 1)[0]
        self.assertIn('visit.editor.sources.firstOrNull { it.id == "private" }', browse)
        self.assertIn('browseConversationWorkspace(visit, source.id, source.initialPath, editingIndex = null)', browse)
        self.assertNotIn('File(filesDir,', browse)
        access = (BASE / 'workspace/ConversationWorkspaceAccess.kt').read_text()
        self.assertIn('val defaultPath = java.io.File(context.filesDir, "workspace").absolutePath', access)
        self.assertIn('ConversationWorkspaceSource("private", "应用私有目录", context.filesDir.absolutePath, defaultPath)', access)
        self.assertIn('只能选择工作区及其子目录', main)

    def test_general_resolver_keeps_existing_binding_compatibility(self):
        resolver = (BASE / 'workspace/ConversationWorkspaceResolver.kt').read_text()
        self.assertIn('is ConversationWorkspaceBinding.Rootfs', resolver)
        self.assertIn('existingPrivateDirectory(File(binding.path))', resolver)
        self.assertNotIn('workspace_selection_root_required', resolver)


if __name__ == '__main__':
    unittest.main()
