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
    def test_action_rows_match_voice_reference_and_preserve_dismissal(self):
        ui = source('ui/components/WorkspaceEntryDialogs.kt')
        for token in ['ChatSelectionSheet(', 'Modifier.size(24.dp)', 'Modifier.weight(1f)', 'DaiyuIcons.ChevronRight',
                      'Modifier.size(18.dp)', 'heightIn(min = 56.dp)', 'onDismiss = onDismiss', 'preview = true', 'deletionAppearance = true']:
            self.assertIn(token, ui)
        self.assertNotIn('java.io', ui)
    def test_cards_keep_long_press_without_more_button(self):
        ui = source('ui/screens/settings/PrivateWorkspacePanel.kt')
        self.assertNotIn('workspace-private-actions:', ui)
        self.assertNotIn('DaiyuIcons.More', ui)
        self.assertNotIn('Modifier.rotate', ui)
        rows = ui.split('shown.entries.forEach { entry ->', 1)[1]
        for token in ['.combinedClickable(', 'onLongClickLabel = "文件操作"', 'entryCurrent()', 'WorkspaceAction(entry, renderedEpoch)']:
            self.assertIn(token, rows)
    def test_all_entry_menus_hide_cancel_but_keep_confirm_cancellation(self):
        ui = source('ui/components/WorkspaceEntryDialogs.kt')
        menu = ui.split('"actions" -> ChatSelectionSheet(', 1)[1]
        self.assertNotIn('"cancel"', menu)
        self.assertNotIn('DaiyuIcons.Close', menu)
        self.assertIn('onDismiss = onDismiss', menu)
        self.assertIn('add("rename" to "重命名")', menu)
        self.assertIn('add("delete" to "删除")', menu)
        self.assertIn('add("export" to "导出文件")', menu)
        rename = ui.split('"rename" ->', 1)[1].split('"delete" ->', 1)[0]
        self.assertIn('"取消"', rename)
        self.assertIn('DaiyuConfirmDialog(', ui)
        self.assertIn('"workspace-cancel"', source('ui/components/ConversationWorkspaceSheet.kt'))
    def test_rename_uses_assistant_search_geometry_without_close_or_label(self):
        ui = source('ui/components/WorkspaceEntryDialogs.kt')
        rename = ui.split('"rename" ->', 1)[1].split('"delete" ->', 1)[0]
        for token in ['ChatSelectionSheet(', 'showCloseButton = false', 'AssistantSearchField(', 'placeholder = "名称"',
                      'showSearchIcon = false', 'testTag("prompt-input")', 'testTag("dialog-cancel")', 'testTag("dialog-confirm")']:
            self.assertIn(token, rename)
        self.assertNotIn('DaiyuTextInputDialog(', rename)
        self.assertNotIn('AssistantPreviewField(', rename)
        field = source('ui/screens/assistants/AssistantSearchField.kt')
        self.assertIn('showSearchIcon: Boolean = true', field)
        self.assertIn('if (showSearchIcon) Icon(', field)
        self.assertIn('heightIn(min = 44.dp * s)', field)
        self.assertIn('heightIn(min = 42.dp * s)', field)
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
