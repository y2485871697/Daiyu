"""Source-only contracts; Compose rendering and interaction run in androidTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'
COMPONENTS = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/components'


def read(name):
    return (SETTINGS / name).read_text()


class LinuxFilesStyleContract(unittest.TestCase):
    def test_bottom_navigation_limits_selection_to_icon_background(self):
        nav = read('LinuxSettingsNavigation.kt').split('internal fun LinuxFileLocationTabs')[0]
        self.assertIn('import app.daiyu.android.ui.haptics.selectable', nav)
        self.assertIn('selectableGroup()', nav)
        self.assertIn('role = Role.Tab', nav)
        self.assertIn('linux-tab:${tab.name.lowercase()}', nav)
        self.assertIn('linux-tab-indicator:${tab.name.lowercase()}', nav)
        self.assertIn('fontSize = 13.sp', nav)
        self.assertIn('size = 24.dp', nav)
        self.assertIn('size(width = 64.dp, height = 32.dp)', nav)
        self.assertIn('heightIn(min = 72.dp)', nav)
        self.assertIn('horizontalCutoutPadding()', nav)
        column, icon = nav.split('Box(Modifier.size(width = 64.dp', 1)
        self.assertNotIn('background(colors.primary', column)
        self.assertNotIn('.background(', column.split('Column(Modifier.weight(1f)', 1)[1])
        self.assertIn('background(if (active) colors.primary else colors.primaryContainer)', icon)
        self.assertNotIn('height = 3.dp', nav)

    def test_navigation_colors_use_theme_pairs_instead_of_sampled_reference_colors(self):
        nav = read('LinuxSettingsNavigation.kt')
        bottom, files = nav.split('internal fun LinuxFileLocationTabs', 1)
        self.assertIn('val tint = if (active) colors.onPrimary else colors.onPrimaryContainer', bottom)
        self.assertIn('color = if (active) colors.primary else colors.onPrimaryContainer', bottom)
        self.assertIn('tint = tint', bottom)
        for source in [bottom, files]:
            self.assertIn('background(if (active) colors.primary else colors.primaryContainer)', source)
            self.assertNotIn('primary.copy(alpha = .12f)', source)
            self.assertNotIn('Color(0x', source)
            self.assertNotIn('assistantPreviewPalette', source)
            self.assertNotIn('conversationAgentColors', source)
        self.assertIn('val tint = (if (active) colors.onPrimary else colors.onPrimaryContainer)', files)
        self.assertIn('.copy(alpha = if (enabled) 1f else .45f)', files)
        self.assertIn('tint = tint', files)
        self.assertIn('color = tint', files)
        self.assertIn('BorderStroke(1.dp, colors.outline)', files)
        self.assertIn('.background(colors.outline)', files)

    def test_navigation_inset_and_callback_owners_stay_with_parent(self):
        page = read('LinuxSettingsScreen.kt')
        nav = read('LinuxSettingsNavigation.kt')
        self.assertEqual(page.count('.navigationBarsPadding()'), 1)
        self.assertNotIn('navigationBarsPadding()', nav)
        self.assertLess(page.index('.navigationBarsPadding()'), page.index('Box(Modifier.weight(1f))'))
        self.assertIn('tabScope === renderedTabScope && tab != it', page)
        self.assertIn('tabScope = Any(); confirmInstall = false; confirmUse = false; confirmPending = false; tab = it', page)

    def test_file_source_segmented_control_preserves_disabled_and_tab_semantics(self):
        nav = read('LinuxSettingsNavigation.kt').split('internal fun LinuxFileLocationTabs', 1)[1].split('internal fun linuxBrowsableRootfs')[0]
        for token in ['BorderStroke(1.dp', 'RoundedCornerShape(28.dp)', 'enabled = enabled',
                      'role = Role.Tab', 'maxLines = 2, textAlign = TextAlign.Center',
                      'DaiyuIcons.Check', 'linux-files-indicator:${location.name.lowercase()}']:
            self.assertIn(token, nav)
        self.assertNotIn('height = 3.dp', nav)

    def test_topbar_uses_existing_real_actions_not_a_callback_registration_side_channel(self):
        panel = read('PrivateWorkspacePanel.kt')
        actions = panel.split('return LinuxFilePanel(actions = {', 1)[1].split('}, content = {', 1)[0]
        self.assertIn('onClick = importFile', actions)
        self.assertIn('val importEnabled = controlsEnabled && listing != null', actions)
        self.assertIn('onClick = importFile, enabled = importEnabled', actions)
        self.assertIn('val semanticImport = hapticClick(importEnabled, importFile)', actions)
        self.assertIn('onClick { semanticImport(); true }', actions)
        self.assertIn('onClick { semanticRefresh(); true }', actions)
        self.assertIn('workspace-private-refresh', actions)
        self.assertIn('DaiyuIcons.Export, "上传文件"', actions)
        self.assertNotIn('SettingsCard', actions)
        self.assertNotIn('SettingsNote', actions)
        parent = read('WorkspaceSettingsScreen.kt')
        toolbar = parent.split('SettingsScaffoldPage(', 1)[1].split('    }) {', 1)[0]
        for token in ['compactTitle = true', 'privatePanel?.actions?.invoke()',
                      'rootfsPanel?.actions?.invoke()', 'workspace-refresh', 'linux-open-terminal']:
            self.assertIn(token, toolbar)
        self.assertLess(toolbar.index('privatePanel?.actions'), toolbar.index('linux-open-terminal'))
        self.assertNotIn('LinuxFilePanelHeader', parent)

    def test_file_cards_and_path_are_separate_from_toolbar_and_each_other(self):
        panel = read('PrivateWorkspacePanel.kt')
        self.assertIn('if (compact) LinuxFileEntryCard { entryContent() } else SettingsCard { entryContent() }', panel)
        self.assertNotIn('SettingsCard { directoryContent() }', panel)
        rootfs = read('LinuxRootfsPanel.kt')
        self.assertIn('LinuxFileEntryCard {', rootfs)
        self.assertIn('summary = entry.relative', rootfs)
        self.assertIn('PrivateWorkspacePaths.existingRoot(context.filesDir, root)', rootfs)
        self.assertNotIn('importFile(', rootfs)
        self.assertNotIn('.delete(', rootfs)
        components = read('LinuxFileComponents.kt')
        self.assertIn('parentTag: String, onParent: (() -> Unit)?', components)
        self.assertIn('enabled = onParent != null', components)
        self.assertIn('RoundedCornerShape(16.dp)', components)

    def test_integrated_pages_have_no_static_descriptions_or_duplicate_panel_titles(self):
        components = read('LinuxFileComponents.kt')
        for name in ['LinuxFileDescription', 'LinuxFilePanelHeader', 'LinuxFileAction']:
            self.assertNotIn(name, components)
        private = read('PrivateWorkspacePanel.kt')
        standalone = private.split('if (!compact) {', 1)[1].split('        notice?.let', 1)[0]
        description = '应用专属目录，与只读共享和 Linux 挂载分开。导入单文件上限 256 MiB；删除仅支持文件和空目录。'
        self.assertIn(description, standalone)
        self.assertEqual(private.count(description), 1)
        shared = read('WorkspaceSettingsScreen.kt')
        self.assertIn('if (!integratedLinux) SettingsNote("选择要共享的目录或文件，访问只读。Linux 目录挂载在 Linux 工具环境中单独配置。")', shared)
        self.assertIn('summary = if (integratedLinux) null else', shared)
        rootfs = read('LinuxRootfsPanel.kt')
        self.assertNotIn('Rootfs 仅供只读浏览', rootfs)
        self.assertNotIn('LinuxFilePanelHeader', rootfs)
        basic = read('LinuxSettingsScreen.kt')
        self.assertIn('choices = listOf("Alpine", "Debian")', basic)
        self.assertIn('choices: List<String>', basic)
        self.assertNotIn('choice.second', basic)
        for text in ['轻量的 musl 环境', '软件兼容性更好的 glibc 环境',
                     '无需 Root，使用独立的私有 Linux 环境', '使用 Root 与独立挂载空间，保留已有环境']:
            self.assertNotIn(text, basic)

    def test_empty_errors_confirmations_and_access_fences_remain(self):
        for name, texts in {
            'PrivateWorkspacePanel.kt': ['此私有目录为空', '仅显示前 100 项，请缩小目录范围',
                '操作未确认完成，请重新进入目录检查；不会自动重试', '无法撤销；不会删除原导入来源。',
                'current() && !busy && !sharedBusy() && pending == null && request == null && directory != null',
                'lifecycle.withResumed', 'permitted(at)'],
            'LinuxRootfsPanel.kt': ['Rootfs 未读取，请刷新或检查环境状态', '此目录为空',
                '当前环境暂无可浏览的 Rootfs', '!visit.get() || token.get()'],
            'WorkspaceSettingsScreen.kt': ['还没有共享目录或文件',
                '撤销后，本应用将无法再读取该目录或文件。不会删除原文件。',
                'fileScope === renderedScope', 'epoch.get() != pickerCallbackEpoch'],
        }.items():
            for text in texts:
                with self.subTest(name=name, text=text):
                    self.assertIn(text, read(name))

    def test_settings_share_compact_topbar_without_changing_non_settings_defaults(self):
        adaptive = (COMPONENTS / 'AdaptiveTopAppBar.kt').read_text()
        scaffold = (COMPONENTS / 'MiuixScaffoldPage.kt').read_text()
        for source in [adaptive, scaffold]:
            self.assertIn('compactTitle: Boolean = false', source)
        self.assertIn('if (compactTitle || rememberIsWideScreen())', adaptive)
        self.assertIn('compactTitle = compactTitle', scaffold)
        self.assertIn('compactTitle: Boolean = true', read('SettingsScaffold.kt'))
        self.assertIn('compactTitle = compactTitle', read('SettingsScaffold.kt'))
        self.assertIn('leave, compactTitle = true, actions', read('LinuxSettingsScreen.kt'))

    def test_compact_and_standalone_file_action_regressions_share_original_guards(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/PrivateWorkspacePanelTest.kt').read_text()
        for token in ['compact = false', 'compact = true',
                      'rememberPrivateWorkspacePanel(access, gate, compact = compact)',
                      'actions = { panel.actions() }', 'workspace-private-actions:sub/keep.txt',
                      'performTouchInput { longClick() }', 'assertTrue(sub.isDirectory)',
                      'realPauseResume(activity, atPause = { retained() })']:
            self.assertIn(token, test)

    def test_device_test_source_keeps_selection_disabled_and_large_font_both_themes(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/LinuxSettingsNavigationTest.kt').read_text()
        for token in ['testBottomNavigationHasTwoTabTargetsAndOneStableSelectionIndicator',
                      'testFileLocationTabsPreserveSelectionAndDisabledState',
                      'fontScale = 1.3f', 'width(320.dp)', 'for (dark in listOf(false, true))',
                      'assertHeightIsAtLeast(48.dp)', 'Role.Tab', 'assertIsNotEnabled()',
                      'testNavigationUsesThemeColorPairsBeforeAndAfterSelectionInBothThemes',
                      'captureToImage().toPixelMap()', 'colors.primaryContainer', 'colors.onPrimaryContainer',
                      'val showKey = Any()', 'var tab by remember(showKey)', 'var location by remember(showKey)']:
            self.assertIn(token, test)


if __name__ == '__main__':
    unittest.main()
