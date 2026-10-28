from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android'

def read(path):
    return (BASE / path).read_text()

class WorkspaceCreateFolderContract(unittest.TestCase):
    def test_picker_action_is_above_footer_and_keeps_owner_control(self):
        sheet = read('ui/components/ConversationWorkspaceSheet.kt')
        create = sheet.split('ConversationAgentButton("新建文件夹"', 1)[1]
        self.assertIn('!busy && !listing && directory?.selectable == true', create)
        self.assertLess(create.index('onClick = onCreateFolder'), create.index('HorizontalDivider'))
        self.assertIn('primaryColor = actions.primary, onPrimaryColor = actions.onPrimary', create)
        main = read('MainActivity.kt')
        action = main.split('private fun createWorkspaceFolder(', 1)[1].split('private fun copyWorkspacePath', 1)[0]
        for guard in ['!dialogActionsCurrent(lease)', '!workspaceCurrent(visit)', 'workspaceVisit !== visit',
                      'visit.busy', 'visit.listing', 'writeCancellation.get()', '!lease!!.isActive']:
            self.assertIn(guard, action)
        self.assertIn('controller.createWorkspaceDirectoryOnWorker', action)
        self.assertIn('controller.workspaceDirectoryOnWorker', action)
        self.assertNotIn('saveWorkspaceOnWorker', action)

    def test_same_dialog_is_reused_without_changing_global_input_policy(self):
        dialog = read('ui/components/WorkspaceCreateFolderDialog.kt')
        self.assertIn('WorkspaceNameDialog("新建文件夹", ""', dialog)
        self.assertIn('"文件夹名称", "确定"', dialog)
        self.assertIn('workspaceDirectoryName(name)', dialog)
        for file in ['ui/screens/settings/PrivateWorkspacePanel.kt', 'ui/screens/settings/LinuxRootfsPanel.kt', 'MainActivity.kt']:
            self.assertIn('WorkspaceCreateFolderDialog(', read(file))

    def test_names_share_real_miuix_field_in_standard_sheet_with_dynamic_button_roles(self):
        path = BASE / 'ui/components/WorkspaceNameDialog.kt'
        self.assertTrue(path.exists(), 'creation and rename need the same scoped name dialog')
        dialog = path.read_text()
        for token in ['ChatSelectionSheet(', 'showCloseButton = false', 'secondary = true', 'primary = true',
                      'remember(initial)', 'label = hint', 'useLabelAsPlaceholder = true', 'singleLine = true',
                      'Modifier.managedInput().testTag("prompt-input").fillMaxWidth()',
                      'imeCursorBrush(MiuixTheme.colorScheme.primary)', 'testTag("workspace-name-field")',
                      'if (enabled()) onConfirm(value)', 'onDismiss = { if (enabled()) onDismiss() }',
                      'onValueChange = { if (enabled()) value = it }', 'dismissEnabled = enabled()']:
            self.assertIn(token, dialog)
        self.assertEqual(2, dialog.count('enabled = enabled()'))
        self.assertEqual(2, dialog.count('Modifier.weight(1f).heightIn(min = 48.dp)'))
        for forbidden in ['Color(0x', 'AssistantSearchField(', 'AssistantPreviewField(', '.trim()', 'onConfirm(value); onDismiss']:
            self.assertNotIn(forbidden, dialog)
        common = read('ui/components/DaiyuDialogs.kt')
        self.assertIn('preview: Boolean = false', common)
        self.assertIn('WindowDialog(show = true', common)

    def test_pinned_mkdir_is_atomic_and_does_not_open_other_writes(self):
        files = read('workspace/PrivateWorkspaceFiles.kt')
        creation = files.split('fun createDirectory(', 1)[1].split('fun parent(', 1)[0]
        for guard in ['!readOnly || allowDirectoryCreation', 'workspaceDirectoryName(name)', 'pinned(directory, cancelled)',
                      'active(cancelled)', 'Os.mkdir("${fdPath(descriptor)}/$safeName", 448)', 'OsConstants.EEXIST']:
            self.assertIn(guard, creation)
        self.assertNotIn('mkdirs', creation)
        self.assertEqual(3, files.count('check(!readOnly) { "workspace_read_only" }'))
        self.assertIn('PrivateWorkspacePaths(filesDir, parts, false, rootfsMutations)', read('workspace/PrivateWorkspacePaths.kt'))
        self.assertIn('existingRoot(filesDir, root, false)', read('workspace/PrivateWorkspacePaths.kt'))
        access = read('workspace/ConversationWorkspaceAccess.kt').split('fun createDirectory(', 1)[1].split('fun resolve(', 1)[0]
        self.assertIn('selection.binding(sourceId, path)', access)
        self.assertIn('freeze(raw).use', access)

    def test_two_path_rows_have_trailing_plus_and_shared_source_stays_read_only(self):
        header = read('ui/screens/settings/LinuxFileComponents.kt').split('internal fun LinuxFileDirectoryHeader', 1)[1].split('internal fun LinuxFileEmptyState', 1)[0]
        self.assertIn('modifier = Modifier.weight(1f)', header)
        self.assertLess(header.index('Text(path.ifEmpty'), header.index('DaiyuIcons.Add'))
        self.assertIn('tint = MiuixTheme.colorScheme.primary', header)
        for file, tag in [('PrivateWorkspacePanel.kt', 'workspace-private-create-folder'), ('LinuxRootfsPanel.kt', 'linux-rootfs-create-folder')]:
            panel = read('ui/screens/settings/' + file)
            self.assertIn('createTag = "' + tag + '"', panel)
            self.assertIn('onOccupied(true)', panel)
            self.assertIn('parentVisit.get()', panel)
        self.assertNotIn('createDirectory', read('workspace/WorkspaceFilesAccess.kt'))

    def test_rootfs_creation_uses_approved_generation_pin_not_shell_or_readwrite_paths(self):
        application = read('terminal/LinuxSettingsApplication.kt')
        action = application.split('internal fun createRootfsDirectoryOnWorker', 1)[1].split('/** Real application teardown', 1)[0]
        self.assertIn('pinWorkspaceRootOnWorker(reference).use', action)
        self.assertIn('approved.rootfs == installation.rootfs', action)
        self.assertIn('allowDirectoryCreation = true', action)
        for forbidden in ['openLinux(', 'ProcessBuilder', 'Os.exec', '.mkdirs(', 'select(']:
            self.assertNotIn(forbidden, action)
        panel = read('ui/screens/settings/LinuxRootfsPanel.kt')
        self.assertIn('createDirectory(creation, name)', panel)
        self.assertIn('!parentVisit.get() || token.get()', panel)
        self.assertIn('createRootfsDirectory = { installation, directory, name, cancelled ->', read('MainActivity.kt'))
        self.assertIn('createRootfsDirectory = createRootfsDirectory', read('ui/screens/settings/LinuxSettingsScreen.kt'))
        self.assertIn('createRootfsDirectory(rootfs, directory, name, cancelled)', read('ui/screens/settings/WorkspaceSettingsScreen.kt'))

    def test_page_and_file_scope_revocation_reaches_confirmation_and_worker_before_disposal(self):
        for file in ['PrivateWorkspacePanel.kt', 'LinuxRootfsPanel.kt']:
            panel = read('ui/screens/settings/' + file)
            self.assertIn('parentVisit.get() && ownerCurrent()', panel)
        self.assertIn('ownerCurrent() && epoch.get() == at', read('ui/screens/settings/PrivateWorkspacePanel.kt'))
        self.assertIn('token.get() || !ownerCurrent()', read('ui/screens/settings/LinuxRootfsPanel.kt'))
        workspace = read('ui/screens/settings/WorkspaceSettingsScreen.kt')
        self.assertEqual(2, workspace.count('ownerCurrent() && fileScope === renderedScope'))
        screen = read('ui/screens/settings/LinuxSettingsScreen.kt')
        self.assertIn('!page.state.value.revoked && tabScope === renderedTabScope', screen)
        self.assertIn('enabled = !fileOperationBusy', screen)
        self.assertIn('tab != it && !fileOperationBusy', screen)
        self.assertIn('page.state.value.revoked || cancelled()', read('MainActivity.kt'))
