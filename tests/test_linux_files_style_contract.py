"""Source-only contracts; Compose rendering and interaction run in androidTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'


def read(name):
    return (SETTINGS / name).read_text()


class LinuxFilesStyleContract(unittest.TestCase):
    def test_bottom_navigation_is_flat_and_keeps_accessible_haptic_tabs(self):
        nav = read('LinuxSettingsNavigation.kt').split('internal fun LinuxFileLocationTabs')[0]
        self.assertIn('import app.daiyu.android.ui.haptics.selectable', nav)
        self.assertIn('selectableGroup()', nav)
        self.assertIn('role = Role.Tab', nav)
        self.assertIn('linux-tab:${tab.name.lowercase()}', nav)
        self.assertIn('linux-tab-indicator:${tab.name.lowercase()}', nav)
        self.assertIn('fontSize = 13.sp', nav)
        self.assertIn('size = 22.dp', nav)
        self.assertIn('heightIn(min = 64.dp)', nav)
        self.assertIn('horizontalCutoutPadding()', nav)
        self.assertNotIn('colors.surface', nav)
        self.assertNotIn('alpha = .12f', nav)
        self.assertNotIn('RoundedCornerShape(18.dp)', nav)

    def test_navigation_inset_is_owned_once_by_parent_not_each_tab_or_list(self):
        page = read('LinuxSettingsScreen.kt')
        nav = read('LinuxSettingsNavigation.kt')
        self.assertEqual(page.count('.navigationBarsPadding()'), 1)
        self.assertNotIn('navigationBarsPadding()', nav)
        self.assertLess(page.index('.navigationBarsPadding()'), page.index('Box(Modifier.weight(1f))'))
        self.assertIn('tabScope === renderedTabScope && tab != it', page)
        self.assertIn('tabScope = Any(); confirmInstall = false; confirmUse = false; confirmPending = false; tab = it', page)

    def test_file_source_tabs_are_lightweight_and_allow_large_text_to_wrap(self):
        nav = read('LinuxSettingsNavigation.kt').split('internal fun LinuxFileLocationTabs', 1)[1].split('internal fun linuxBrowsableRootfs')[0]
        self.assertIn('enabled = enabled', nav)
        self.assertIn('role = Role.Tab', nav)
        self.assertIn('fontSize = 14.sp', nav)
        self.assertIn('maxLines = 2, textAlign = TextAlign.Center', nav)
        self.assertIn('linux-files-indicator:${location.name.lowercase()}', nav)
        self.assertNotIn('background(colors.surface)', nav)
        self.assertNotIn('RoundedCornerShape(16.dp)', nav)

    def test_private_import_toolbar_is_not_an_empty_full_width_card(self):
        panel = read('PrivateWorkspacePanel.kt')
        toolbar = panel.split('return { Column {', 1)[1].split('        notice?.let', 1)[0]
        compact = toolbar.split('        } else {', 1)[0]
        self.assertIn('LinuxFilePanelHeader("私有工作区")', compact)
        self.assertIn('LinuxFileAction("导入文件", "workspace-private-import"', compact)
        self.assertIn('enabled = controlsEnabled && listing != null', compact)
        self.assertIn('workspace-private-refresh', compact)
        self.assertNotIn('SettingsCard', compact)
        self.assertIn('if (compact) entryContent() else SettingsCard { entryContent() }', panel)
        self.assertIn('if (compact) SettingsCard { directoryContent() } else directoryContent()', panel)

    def test_shared_and_rootfs_use_the_same_header_and_empty_state_without_writes(self):
        rootfs = read('LinuxRootfsPanel.kt')
        shared = read('WorkspaceSettingsScreen.kt')
        self.assertIn('LinuxFilePanelHeader("Rootfs")', rootfs)
        self.assertIn('LinuxFileDirectoryHeader(shown.directory.relative)', rootfs)
        self.assertIn('LinuxFileEmptyState("当前环境暂无可浏览的 Rootfs")', rootfs)
        self.assertIn('LinuxFileEmptyState("此目录为空")', rootfs)
        self.assertIn('PrivateWorkspacePaths.existingRoot(context.filesDir, root)', rootfs)
        self.assertIn('entry.symbolicLink -> "符号链接 · 不跟随"', rootfs)
        self.assertNotIn('importFile(', rootfs)
        self.assertNotIn('.delete(', rootfs)
        self.assertIn('LinuxFilePanelHeader("共享文件夹")', shared)
        self.assertIn('LinuxFileEmptyState("还没有共享目录或文件")', shared)
        self.assertIn('fileScope === renderedScope', shared)
        self.assertIn('epoch.get() != pickerCallbackEpoch', shared)

    def test_existing_descriptions_and_empty_messages_are_preserved_verbatim(self):
        for name, texts in {
            'PrivateWorkspacePanel.kt': [
                '应用专属目录，与只读共享和 Linux 挂载分开。导入单文件上限 256 MiB；删除仅支持文件和空目录。',
                '此私有目录为空', '仅显示前 100 项，请缩小目录范围',
                '操作未确认完成，请重新进入目录检查；不会自动重试'],
            'LinuxRootfsPanel.kt': [
                'Rootfs 仅供只读浏览，不跟随符号链接，不导入或删除环境文件。',
                'Rootfs 未读取，请刷新或检查环境状态', '此目录为空'],
            'WorkspaceSettingsScreen.kt': [
                '选择要共享的目录或文件，访问只读。Linux 目录挂载在 Linux 工具环境中单独配置。',
                '还没有共享目录或文件', '撤销后，本应用将无法再读取该目录或文件。不会删除原文件。'],
        }.items():
            for text in texts:
                with self.subTest(name=name, text=text):
                    self.assertIn(text, read(name))

    def test_controls_and_empty_state_use_local_readable_sizes_not_a_global_scale(self):
        components = read('LinuxFileComponents.kt')
        for token in ['fontSize = 16.sp', 'fontSize = 14.sp', 'fontSize = 13.sp',
                      'heightIn(min = 48.dp)', 'heightIn(min = 176.dp)', 'role = Role.Button']:
            self.assertIn(token, components)
        self.assertNotIn('LocalDensity', components)
        self.assertNotIn('interfaceScale', components)
        self.assertNotIn('ButtonDefaults', components)

    def test_compact_and_standalone_file_action_regressions_share_original_guards(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/PrivateWorkspacePanelTest.kt').read_text()
        for token in ['compact = false', 'compact = true',
                      'rememberPrivateWorkspacePanel(access, gate, compact = compact)',
                      'workspace-private-actions:sub/keep.txt', 'performTouchInput { longClick() }',
                      'assertEquals("keep", File(sub, "keep.txt").readText())',
                      'assertTrue(sub.isDirectory)', 'realPauseResume(activity, atPause = { retained() })']:
            self.assertIn(token, test)

    def test_device_test_source_covers_selection_disabled_and_large_font_both_themes(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/LinuxSettingsNavigationTest.kt').read_text()
        for token in ['testBottomNavigationHasTwoTabTargetsAndOneStableSelectionIndicator',
                      'testFileLocationTabsPreserveSelectionAndDisabledState',
                      'fontScale = 1.3f', 'width(320.dp)', 'for (dark in listOf(false, true))',
                      'assertHeightIsAtLeast(48.dp)', 'Role.Tab', 'assertIsNotEnabled()']:
            self.assertIn(token, test)


if __name__ == '__main__':
    unittest.main()
