from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class WorkspacePathDisplayContract(unittest.TestCase):
    def test_worker_supplies_trusted_roots_without_ui_filesystem_access(self):
        access = (BASE / 'workspace/ConversationWorkspaceAccess.kt').read_text()
        main = (BASE / 'MainActivity.kt').read_text()
        self.assertIn('privateWorkspaceRoots: List<String>', access)
        self.assertIn('context.filesDir.canonicalFile, "workspace"', access)
        self.assertIn('workspaceRoots = visit.editor.privateWorkspaceRoots', main)
        display = (BASE / 'ui/components/WorkspacePathPresentation.kt').read_text()
        for forbidden in ['java.io', 'java.nio', '.trim(', '.canonical', 'substringAfterLast("workspace")']:
            self.assertNotIn(forbidden, display)

    def test_card_has_one_line_name_and_relative_location(self):
        sheet = (BASE / 'ui/components/ConversationWorkspaceSheet.kt').read_text()
        card = sheet.split('val display = workspacePathPresentation', 1)[1].split('if (selecting)', 1)[0]
        self.assertIn('Text(display.name', card)
        self.assertIn('display.location?.let', card)
        self.assertEqual(2, card.count('maxLines = 1'))
        self.assertIn('color = colors.muted', card)
        self.assertIn('TextOverflow.MiddleEllipsis', card)
        self.assertNotIn('Text(workspace.path,', sheet)

    def test_directory_keeps_full_scrollable_selectable_path_and_copy_callback(self):
        sheet = (BASE / 'ui/components/ConversationWorkspaceSheet.kt').read_text()
        header = sheet.split('val fullPath = directory?.path ?: path', 1)[1].split('HorizontalDivider', 1)[0]
        self.assertIn('SelectionContainer', header)
        self.assertIn('heightIn(max = 96.dp)', header)
        self.assertIn('verticalScroll(rememberScrollState())', header)
        self.assertIn('Text(fullPath', header)
        self.assertNotIn('maxLines', header)
        self.assertIn('onCopyPath(fullPath)', header)
        self.assertIn('enabled = !busy && !listing', header)
        self.assertIn('workspace-copy-path', header)

    def test_copy_uses_exact_current_snapshot_and_existing_clipboard(self):
        main = (BASE / 'MainActivity.kt').read_text()
        callback = main.split('private fun copyWorkspacePath(', 1)[1].split('private fun cancelWorkspaceSelection', 1)[0]
        for guard in ['!workspaceCurrent(visit)', 'workspaceVisit !== visit', '!visit.choosing', 'visit.busy', 'visit.listing',
                      'path != (visit.directory?.path ?: visit.path)']:
            self.assertIn(guard, callback)
        self.assertIn('ClipData.newPlainText("工作区路径", path)', callback)
        self.assertNotIn('workspacePathPresentation', callback)
        self.assertNotIn('.trim()', callback)
        self.assertIn('onCopyPath = { if (dialogActionsCurrent(lease)) copyWorkspacePath(visit, it) }', main)


if __name__ == '__main__':
    unittest.main()
