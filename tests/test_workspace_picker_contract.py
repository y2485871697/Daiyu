from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class WorkspacePickerContract(unittest.TestCase):
    def text(self, path):
        return (BASE / path).read_text()

    def test_body_and_strip_share_initial_pass_gesture(self):
        composer = self.text('ui/screens/chat/conversation/ChatComposer.kt')
        surface = self.text('ui/screens/chat/conversation/ChatComposerSurface.kt')
        self.assertIn('testTag("chat-draft").workspacePullUpGesture(workspacePointers, openWorkspace)', composer)
        self.assertIn('key(state.editorGeneration) { ComposerWorkspacePullUpArea(workspacePointers, openWorkspace) }', composer)
        self.assertIn('requireUnconsumed = true, pass = PointerEventPass.Initial', surface)
        self.assertIn('awaitPointerEvent(PointerEventPass.Initial)', surface)
        self.assertNotIn('down.consume()', surface)
        self.assertIn('event.changes.any { it.isConsumed }', surface)
        self.assertIn('PointerEventType.Release && !change.pressed && change.previousPressed', surface)
        self.assertIn('longPressTimeoutMillis', surface)

    def test_first_page_is_card_not_inline_editor(self):
        sheet = self.text('ui/components/ConversationWorkspaceSheet.kt')
        first = sheet.split('if (!choosing) {', 1)[1].split('} else {\n                        Row(', 1)[0]
        self.assertIn('workspace-path-card', first)
        self.assertNotIn('BasicTextField', sheet)
        self.assertNotIn('bodySmall', sheet)
        self.assertNotIn('Text("来源"', sheet)
        self.assertIn('workspace-choices', first)
        self.assertIn('workspace-add', first)
        self.assertIn('workspace-delete', first)
        self.assertIn('combinedClickable', first)
        self.assertIn('onSelectWorkspace(index)', first)
        self.assertIn('onDeleteWorkspaces(selectedWorkspaces)', first)
        self.assertIn('selectionMode', first)

    def test_picker_has_fixed_footer_and_our_theme_roles(self):
        sheet = self.text('ui/components/ConversationWorkspaceSheet.kt')
        self.assertIn('useDefaultHeight = true', sheet)
        self.assertIn('LazyColumn', sheet)
        self.assertLess(sheet.index('LazyColumn'), sheet.index('ConversationAgentButton("取消"'))
        self.assertIn('primaryColor = actions.secondaryContainer, onPrimaryColor = actions.onSecondary', sheet)
        self.assertIn('primaryColor = actions.primary, onPrimaryColor = actions.onPrimary', sheet)
        self.assertNotIn('Color(0x', sheet)
        self.assertIn('directory?.selectable == true', sheet)

    def test_both_pages_hide_close_and_preserve_saved_accent_roles(self):
        sheet = self.text('ui/components/ConversationWorkspaceSheet.kt')
        self.assertIn('resolveAppearanceActions(LocalAppearanceSettings.current, LocalAppearanceDark.current)', sheet)
        self.assertNotIn('MiuixTheme.colorScheme', sheet)
        self.assertNotIn('testTag("workspace-close")', sheet)
        self.assertNotIn('Icons.Rounded.Close', sheet)
        self.assertNotIn('contentDescription = "关闭"', sheet)
        self.assertIn('ConversationAgentSheet(onDismiss, useDefaultHeight = true,', sheet)
        self.assertIn('else \"会话工作区\"', sheet)
        self.assertIn('Column(Modifier.fillMaxSize()', sheet)

    def test_only_use_saves_cancel_discards_draft(self):
        main = self.text('MainActivity.kt')
        cancel = main.split('private fun cancelWorkspaceSelection', 1)[1].split('private fun saveConversationWorkspace', 1)[0]
        self.assertIn('source = visit.editor.selectedSourceId, path = visit.editor.path', cancel)
        self.assertIn('choosing = false, listing = false, directory = null', cancel)
        self.assertNotIn('saveWorkspace', cancel)
        self.assertIn('!visit.choosing || visit.directory?.selectable != true', main)
        self.assertIn('workspacePresented(loading) && workspaceVisit === loading', main)

    def test_failed_navigation_invalidates_previous_selection(self):
        main = self.text('MainActivity.kt')
        browse = main.split('private fun browseConversationWorkspace', 1)[1].split('private fun cancelWorkspaceSelection', 1)[0]
        failure = browse.split('catch (_: Exception)', 1)[1]
        self.assertIn('directory = loading.directory?.copy(selectable = false)', failure)
        self.assertIn('visit.directory?.selectable != true', main)

    def test_browser_stays_within_workspace_and_keeps_pinned_listing(self):
        access = self.text('workspace/ConversationWorkspaceAccess.kt')
        directory = access.split('fun directory(', 1)[1].split('fun resolve(', 1)[0]
        self.assertIn('freeze(raw).use', directory)
        self.assertIn('selection.binding(sourceId, path)', directory)
        self.assertIn('selection.parentPath(host)', directory)
        self.assertIn('files.listAll(files.directoryAt(relative))', directory)
        self.assertNotIn('storageRoot', directory)
        self.assertIn('conversationWorkspaces.directory(source, path)', self.text('ChatController.kt'))

    def test_listing_reuses_pinned_no_follow_without_old_scan_limit(self):
        files = self.text('workspace/PrivateWorkspaceFiles.kt')
        listing = files.split('fun directoryNames(', 1)[1].split('fun parent(', 1)[0]
        self.assertIn('pinned(null, cancelled)', listing)
        self.assertIn('Os.lstat', listing)
        self.assertIn('OsConstants.S_ISDIR', listing)
        self.assertIn('active(cancelled)', listing)
        self.assertNotIn('MAX_ENTRIES', listing)
        paths = self.text('workspace/PrivateWorkspacePaths.kt')
        self.assertIn('PrivateWorkspacePaths(filesDir, emptyList(), false)', paths)
        self.assertIn('selected != base', paths)


if __name__ == '__main__':
    unittest.main()
