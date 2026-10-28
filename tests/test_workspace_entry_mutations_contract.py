from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
A = ROOT / 'app/src/main/kotlin/app/daiyu/android'
def source(path): return (A / path).read_text()
class WorkspaceEntryMutationsContract(unittest.TestCase):
    def test_three_paths_share_dialog_and_real_mutation_wiring(self):
        for path in ['ui/screens/settings/PrivateWorkspacePanel.kt', 'ui/screens/settings/LinuxRootfsPanel.kt', 'MainActivity.kt']:
            self.assertIn('WorkspaceEntryDialogs(', source(path))
        for path in ['ui/screens/settings/PrivateWorkspacePanel.kt', 'ui/screens/settings/LinuxRootfsPanel.kt']:
            self.assertIn('.rename(', source(path))
            self.assertIn('.deleteTree(', source(path))
        self.assertIn('controller.modifyWorkspaceEntryOnWorker(', source('MainActivity.kt'))
        self.assertIn('conversationWorkspaces.modifyEntry(', source('ChatController.kt'))
    def test_action_rows_match_voice_reference_and_cancel_has_no_write(self):
        ui = source('ui/components/WorkspaceEntryDialogs.kt')
        for token in ['ChatSelectionSheet(', 'Modifier.size(24.dp)', 'Modifier.weight(1f)', 'DaiyuIcons.ChevronRight',
                      'Modifier.size(18.dp)', 'heightIn(min = 56.dp)', '"cancel" -> onDismiss()', 'preview = true', 'deletionAppearance = true']:
            self.assertIn(token, ui)
        self.assertNotIn('java.io', ui)
    def test_rootfs_protection_is_below_ui_and_applies_to_destination(self):
        files = source('workspace/PrivateWorkspaceFiles.kt')
        self.assertEqual(files.count('check(canModify(entry))'), 2)
        self.assertIn('WorkspaceEntryPolicy.mutable(targetRelative, paths.rootfsMutations)', files)
        self.assertIn('paths.rootfsMutations', files)
        self.assertIn('OsConstants.O_NOFOLLOW', files)
        self.assertIn('java.nio.file.Files.move(source, target)', files)
        self.assertNotIn('REPLACE_EXISTING', files)
    def test_picker_keeps_fixed_workspace_root_and_snapshot_entries(self):
        access = source('workspace/ConversationWorkspaceAccess.kt')
        self.assertIn('PrivateWorkspacePaths(context.filesDir)', access)
        self.assertIn('files.directoryAt(relative)', access)
        self.assertIn('entry.relative.substringBeforeLast', access)
        ui = source('ui/components/ConversationWorkspaceSheet.kt')
        self.assertIn('items(directory.entries', ui)
        self.assertIn('onLongClick = onLongClick', ui)
        self.assertIn('if (entry.directory) onNavigate', ui)
    def test_rootfs_mutation_blocks_parent_controls_and_uses_epoch(self):
        rootfs = source('ui/screens/settings/LinuxRootfsPanel.kt')
        for token in ['parentVisit.get()', 'epoch.get() != at', 'onOccupied(true)', 'onOccupied(false)', 'sharedBusy()']:
            self.assertIn(token, rootfs)
        host = source('ui/screens/settings/WorkspaceSettingsScreen.kt')
        self.assertIn('rememberLinuxRootfsPanel(rootfs?.rootfs, parentVisit = visit', host)
    def test_file_mutation_does_not_save_workspace_bindings(self):
        method = source('MainActivity.kt').split('private fun modifyConversationWorkspaceEntry(',1)[1].split('private fun editConversationWorkspace(',1)[0]
        self.assertIn('workspaceCurrent(changing)', method)
        self.assertIn('workspaceVisit !== changing', method)
        self.assertNotIn('persistConversationWorkspaces(', method)
        self.assertNotIn('saveWorkspaceOnWorker(', method)
        self.assertNotIn('choosing = false', method)
