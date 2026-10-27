"""Source wiring contracts only; Android persistence/rendering tests require approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SETTINGS = SOURCE / 'ui/screens/settings'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings'


class LinuxInstallChoiceContract(unittest.TestCase):
    def test_install_pair_is_read_back_per_new_page_instead_of_ephemeral_drafts(self):
        screen = (SETTINGS / 'LinuxSettingsScreen.kt').read_text()
        self.assertIn('remember(page, choicePreferences) { mutableStateOf(choicePreferences.read()) }', screen)
        self.assertIn('getSharedPreferences("linux-settings-v1", android.content.Context.MODE_PRIVATE)', screen)
        self.assertIn('val distributionDraft = installChoice?.distribution', screen)
        self.assertIn('val modeDraft = installChoice?.executionMode', screen)
        self.assertNotIn('mutableStateOf<String?>(null)', screen.split('val pendingMode')[0])
        self.assertNotIn('mutableStateOf<LinuxExecutionMode?>(null)', screen)

    def test_save_is_one_validated_pair_with_confirmed_commit_and_no_runtime_authority(self):
        prefs = (SETTINGS / 'LinuxInstallChoicePreferences.kt').read_text()
        for token in ['setOf("alpine", "debian")', 'parts.size != 2', 'LinuxExecutionMode.valueOf(parts[1])',
                      'preferences.edit().putString(KEY, "${choice.distribution}:${choice.executionMode.name}").commit()',
                      '.getOrDefault(false)', '.getOrNull()', 'choice.distribution !in DISTRIBUTIONS']:
            self.assertIn(token, prefs)
        self.assertEqual(prefs.count('putString('), 2)
        self.assertIn('if (!saved) runCatching { preferences.edit().putString(KEY, previous).commit() }', prefs)
        read = prefs.split('fun read()', 1)[1].split('fun save(', 1)[0]
        for token in ['edit(', '.apply(', '.commit(']:
            self.assertNotIn(token, read)
        for token in ['LinuxEnvironmentStore', 'selected-linux', 'generation', 'rootfs', '.approve(', '.install(']:
            self.assertNotIn(token, prefs.split('/**', 1)[0] + prefs.split('internal class', 1)[1])

    def test_current_owner_root_and_busy_fences_precede_preference_write(self):
        screen = (SETTINGS / 'LinuxSettingsScreen.kt').read_text()
        choose = screen.split('fun choose(', 1)[1].split('val rootfs =', 1)[0]
        for token in ['!choiceCurrent()', '!choiceEnabled', 'runningModeStore.mode.value != RunningMode.ROOT']:
            self.assertLess(choose.index(token), choose.index('choicePreferences.save(nextChoice)'))
        self.assertLess(choose.index('if (!choicePreferences.save(nextChoice))'), choose.index('installChoice = nextChoice'))
        self.assertIn('选项未保存，请重试。', choose)
        self.assertIn('it.label.equals(nextDistribution, ignoreCase = true) && it.executionMode == nextMode', choose)
        self.assertIn('host.selectLinux(choice.reference)', choose)
        self.assertIn('选项已保存，但当前使用的环境未切换，请稍后重试。', choose)
        self.assertNotIn('distributionDraft = null', choose)
        self.assertNotIn('modeDraft = null', choose)

    def test_resume_pending_install_saves_pair_without_restarting_install(self):
        screen = (SETTINGS / 'LinuxSettingsScreen.kt').read_text()
        resume = screen.split('TextButton("继续上次安装"', 1)[1].split('} else LinuxInstallAction', 1)[0]
        for token in ['if (basicCurrent())', 'installation.prepared.artifact.distribution',
                      'installation.prepared.installation.executionMode', 'choicePreferences.save(nextChoice)']:
            self.assertIn(token, resume)
        for token in ['.install(', '.approve(', 'selectLinux(']:
            self.assertNotIn(token, resume)

    def test_pending_recovery_remains_visible_ahead_of_saved_install_preference(self):
        screen = (SETTINGS / 'LinuxSettingsScreen.kt').read_text()
        self.assertIn('else pendingInstall?.prepared?.artifact?.distribution ?: distributionDraft', screen)
        self.assertIn('else pendingMode ?: modeDraft ?: selected?.executionMode', screen)
        self.assertIn('val mode = if (!rootMode) LinuxExecutionMode.PROOT', screen)
        rendering = screen.split('val pendingMode', 1)[1].split('fun choose(', 1)[0]
        self.assertNotIn('choicePreferences.save(', rendering)

    def test_linux_scoped_theme_matches_actual_switch_and_covers_dialogs(self):
        theme = (SETTINGS / 'LinuxSettingsTheme.kt').read_text()
        switch = (SOURCE / 'ui/components/subagent/ConversationAgentStyle.kt').read_text()
        palette = (SOURCE / 'ui/app/DaiyuPalette.kt').read_text()
        self.assertIn('LocalAppearanceSettings.current.resolvedPrimaryAccent ?: Color(0xFF82728F)', palette)
        self.assertIn('import app.daiyu.android.ui.app.DaiyuPalette', switch)
        actual_switch = switch.split('@Composable internal fun ConversationAgentSwitch(', 1)[1].split('@Composable internal fun ConversationAgentButton(', 1)[0]
        self.assertIn('.background(if (checked) DaiyuPalette.switchTrack else Color(0x28b4aabb))', actual_switch)
        self.assertIn('.border(1.dp, if (checked) DaiyuPalette.switchTrack else colors.line, shape)', actual_switch)
        self.assertIn('val primary = DaiyuPalette.switchTrack', theme)
        for token in ['val onPrimary = Color.White', 'MiuixTheme(colors = MiuixTheme.colorScheme.copy(',
                      'MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(',
                      'primaryVariant = primary', 'disabledPrimaryButton = container',
                      'primaryContainer = container, onPrimaryContainer = onContainer']:
            self.assertIn(token, theme)
        screen = (SETTINGS / 'LinuxSettingsScreen.kt').read_text()
        self.assertIn('suppliedChoicePreferences: LinuxInstallChoicePreferences? = null) = LinuxSettingsTheme {', screen)
        self.assertIn('ButtonDefaults.buttonColorsPrimary()', screen)
        self.assertIn('MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.disabledOnPrimaryButton', screen)
        self.assertIn('import app.daiyu.android.ui.app.DaiyuPalette', theme)

    def test_custom_primary_keeps_the_original_white_foreground(self):
        theme = (SETTINGS / 'LinuxSettingsTheme.kt').read_text()
        self.assertIn('import app.daiyu.android.ui.app.resolvedPrimaryAccent', theme)
        self.assertIn('val onPrimary = Color.White', theme)
        self.assertNotIn('val onPrimary = colorContentColor(primary)', theme)
        nav = (ANDROID / 'LinuxSettingsNavigationTest.kt').read_text()
        self.assertIn('assertEquals(Color.White, colors.onPrimary)', nav)
        self.assertNotIn('assertEquals(Color.Black, colors.onPrimary)', nav)
        self.assertIn('testCustomPrimaryKeepsOriginalWhiteForegroundInBothThemes', nav)
        self.assertIn('for (accent in listOf(0xff000000.toInt(), 0xffe6c7f2.toInt(), 0xffffffff.toInt()))', nav)
        self.assertIn('val foreground = Color.White', nav)
        self.assertNotIn('to Color.Black', nav)
        self.assertIn('assertEquals(colors.onPrimary, materialColors.onPrimary)', nav)

    def test_upload_is_upward_existing_vector_but_remains_real_import(self):
        panel = (SETTINGS / 'PrivateWorkspacePanel.kt').read_text()
        actions = panel.split('return LinuxFilePanel(actions = {', 1)[1].split('}, content = {', 1)[0]
        self.assertIn('DaiyuIcons.Export, "上传文件"', actions)
        self.assertIn('onClick = importFile, enabled = importEnabled', actions)
        self.assertIn('ActivityResultContracts.OpenDocument()', panel)
        self.assertIn('files.importFile(checkNotNull(ticket.directory)', panel)
        icon = (SOURCE / 'ui/icons/DaiyuIcons.kt').read_text().split('val Export:', 1)[1].split('val Rename:', 1)[0]
        self.assertIn('lineTo(12f, 3f)', icon)
        self.assertIn('lineTo(4f, 19f)', icon)
        self.assertNotIn('.rotate(', actions)

    def test_android_source_covers_reentry_root_gate_failed_write_stale_callbacks_and_pairs(self):
        screen = (ANDROID / 'LinuxSettingsScreenTest.kt').read_text()
        for token in ['testInstallChoicesPersistAcrossNewPageAndPreferenceInstancesWithoutInstalling',
                      'testPreferenceWriteFailureKeepsConfirmedChoiceAndShowsError',
                      'testRetainedDistributionCallbackCannotOverwritePreferenceAfterReentry',
                      'setRunningMode(RunningMode.NORMAL)', 'setRunningMode(RunningMode.ROOT)',
                      'assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())']:
            self.assertIn(token, screen)
        prefs = (ANDROID / 'LinuxInstallChoicePreferencesTest.kt').read_text()
        for token in ['testAllPairsRoundTripAcrossNewStoreInstances', 'LinuxExecutionMode.entries',
                      'testMalformedPreferenceDoesNotBecomeRuntimeSelectionOrWriteOnRead',
                      'testUnsupportedDistributionCannotOverwriteSavedPair',
                      'testCommitFailureAfterMemoryUpdateRestoresPreviousPairForNewStore']:
            self.assertIn(token, prefs)
        nav = (ANDROID / 'LinuxSettingsNavigationTest.kt').read_text()
        self.assertIn('LinuxSettingsTheme {', nav)
        self.assertIn('assertEquals(Color(0xff82728f), colors.primary)', nav)
        self.assertIn('captureToImage().toPixelMap()', nav)


if __name__ == '__main__':
    unittest.main()
