"""Source wiring only; actual menu, owner, Rootfs and return tests are Android sources."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class WorkspaceFilesEntryContract(unittest.TestCase):
    def host(self):
        return (SOURCE / 'MainActivity.kt').read_text()

    def test_menu_keeps_current_chat_guard_and_enters_files_only_owner(self):
        host = self.host()
        self.assertIn('ChatMenuAction.OPEN_WORKSPACE -> if (currentChat()) openLinuxSettings(filesOnly = true)', host)
        self.assertNotIn('"chat-workspace" -> app.daiyu.android.ui.screens.settings.WorkspaceSettingsScreen(', host)
        self.assertIn('FeatureRoute.LINUX_SETTINGS -> openLinuxSettings()', host)

    def test_shared_renderer_uses_real_controller_host_and_fresh_page_key(self):
        route = self.host().split('"linux-settings", "chat-workspace" -> {', 1)[1].split('"generation-settings" ->', 1)[0]
        for token in ['val route = retainedPage', 'val visit = linuxSettingsVisit',
                      'retainedPage == route && linuxSettingsVisit === visit && linuxSettingsPageOwner === page',
                      'if (route == "chat-workspace") returnToChat() else retainedPage = "settings"',
                      'else key(page)', 'LinuxSettingsScreen(ready.application.settings, page, host = ready.application.terminalOwner.host,',
                      'filesOnly = route == "chat-workspace"', 'onBack = leave']:
            self.assertIn(token, route)

    def test_files_only_defaults_to_files_hides_navigation_and_keeps_full_rootfs_binding(self):
        screen = (SOURCE / 'ui/screens/settings/LinuxSettingsScreen.kt').read_text()
        for token in ['filesOnly: Boolean = false', 'remember(page, filesOnly)',
                      'mutableStateOf(if (filesOnly) LinuxSettingsTab.FILES else LinuxSettingsTab.BASIC)',
                      'fun basicCurrent() = !filesOnly && tab == LinuxSettingsTab.BASIC',
                      'if (!filesOnly) LinuxSettingsTabs(tab, enabled = !fileOperationBusy)',
                      'pageTitle = if (filesOnly) "工作区" else "Linux 工具环境", integratedLinux = true',
                      'val selectedReference = state.selected ?: terminal?.linux?.selected',
                      'val currentInstallation = state.entries.firstOrNull {\n'
                      '        LinuxGenerationRef(it.environmentId, it.generation) == selectedReference\n'
                      '    }',
                      'val rootfs = linuxBrowsableRootfs(state, currentInstallation?.distribution ?: distribution,\n'
                      '        currentInstallation?.executionMode ?: mode, selectedReference)',
                      'rootfs = rootfs, onOpenTerminal = onOpenTerminal']:
            self.assertIn(token, screen)
        self.assertEqual(screen.count('.navigationBarsPadding()'), 1)
        self.assertIn('if (tabScope === renderedTabScope && tab != it && !fileOperationBusy)', screen)

    def test_files_only_current_environment_ignores_install_form_draft_and_pending_mode(self):
        screen = (SOURCE / 'ui/screens/settings/LinuxSettingsScreen.kt').read_text()
        distribution = screen.split('val distribution = if (filesOnly)', 1)[1].split('\n        else ', 1)[0]
        self.assertIn('selected?.label ?: terminal?.linux?.distribution', distribution)
        self.assertNotIn('pendingInstall', distribution)
        self.assertNotIn('distributionDraft', distribution)
        mode = screen.split('else if (filesOnly)', 1)[1].split('\n        else ', 1)[0]
        self.assertIn('selected?.executionMode ?: LinuxExecutionMode.PROOT', mode)
        self.assertNotIn('pendingMode', mode)
        self.assertIn('val mode = if (!rootMode) LinuxExecutionMode.PROOT', screen)

    def test_owner_loading_is_route_and_visit_bound_and_revoked_on_leave(self):
        host = self.host()
        self.assertIn('if (value != "linux-settings" && value != "chat-workspace") {', host)
        opening = host.split('private fun openLinuxSettings(filesOnly: Boolean = false)', 1)[1].split('private fun terminalPageCurrent', 1)[0]
        for token in ['val route = if (filesOnly) "chat-workspace" else "linux-settings"',
                      'linuxSettingsPageOwner?.close()', 'val visit = Any()', 'linuxSettingsVisit = visit',
                      'retainedPage = route', 'retainedPage == route && linuxSettingsVisit === visit',
                      'state is app.daiyu.android.terminal.TerminalApplicationState.Ready',
                      'linuxSettingsPageOwner = state.application.settings.enterPage()']:
            self.assertIn(token, opening)
        for token in ['.install(', '.approve(', '.create(', '.open(']:
            self.assertNotIn(token, opening)

    def test_terminal_return_and_recreation_preserve_files_only_route(self):
        host = self.host()
        self.assertIn('setOf("settings", "chat", "linux-settings", "chat-workspace")', host)
        self.assertIn('"settings", "linux-settings", "chat-workspace" -> retainedPage', host)
        close = host.split('private fun closeTerminalPage()', 1)[1].split('private fun openUsagePage', 1)[0]
        self.assertIn('"chat-workspace" -> openLinuxSettings(filesOnly = true)', close)
        self.assertIn('"linux-settings" -> openLinuxSettings()', close)

    def test_android_source_covers_real_menu_hidden_tabs_rootfs_and_terminal_round_trip(self):
        tests = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        menu = (tests / 'ChatMenuEntryActivityTest.kt').read_text()
        for token in ['testWorkspaceTerminalRoundTripAndRecreationReturnToFilesOnlyWithFreshOwner',
                      'node("linux-bottom-navigation").assertDoesNotExist()',
                      'node("linux-tab:basic").assertDoesNotExist()', 'node("linux-tab:files").assertDoesNotExist()',
                      'activity = recreateActivity(activity)', 'assertNotSame(previousOwner, currentOwner)',
                      'assertTrue(previousOwner.state.value.revoked)', 'assertEquals(0, fixture.opens.get())',
                      'assertFalse(application.terminalOwner.host.state.value.ownsResources)',
                      'assertTrue(application.terminalSessions.state.value.sessions.isEmpty())',
                      'testWorkspaceMenuRetainedCallbackCannotNavigateWhilePausedOnAnotherPageOrAfterEditorReplacement']:
            self.assertIn(token, menu)
        files = (tests / 'ui/screens/settings/LinuxSettingsScreenTest.kt').read_text()
        for token in ['testFilesOnlyOpensNewWorkspaceWithoutBottomNavigationOrBasicActions',
                      'testFilesOnlyUsesApprovedRootfsReadOnlyWithoutBottomNavigation',
                      'assertApprovedRootfsReadOnly(filesOnly = true)',
                      'assertApprovedRootfsReadOnly(filesOnly = false)',
                      'revealLinux("linux-rootfs-entry:etc/config").assertHasNoClickAction()',
                      'assertEquals(0, writes.get())']:
            self.assertIn(token, files)


if __name__ == '__main__':
    unittest.main()
