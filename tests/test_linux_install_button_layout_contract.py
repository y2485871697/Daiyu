"""Source-only regression for moving the initial install button; no device execution."""
from pathlib import Path
import itertools
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings/LinuxSettingsScreen.kt'


class LinuxInstallButtonLayoutContract(unittest.TestCase):
    def test_information_card_keeps_original_copy_typography_and_alignment(self):
        source = SCREEN.read_text()
        info = source.split('item("linux-status") {', 1)[1].split('if (!installed || pendingRequest)', 1)[0]
        for token in [
            'SettingsCard(Modifier.padding(top = 12.dp))',
            'Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp))',
            'Text(distributionName(distribution), style = MiuixTheme.textStyles.title3)',
            'Text(modeName(mode), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)',
            'else -> "尚未安装此环境"',
        ]:
            self.assertIn(token, info)
        for token in ['CenterHorizontally', 'TextAlign.Center', 'textStyles.body2', 'linux-install-environment']:
            self.assertNotIn(token, info)

    def test_single_initial_install_button_is_inside_existing_configuration_card(self):
        source = SCREEN.read_text()
        status, config = source.split('item("linux-configuration") {', 1)
        config = config.split('item("linux-tools-heading")', 1)[0]
        self.assertEqual(source.count('testTag("linux-install-environment")'), 1)
        self.assertEqual(config.count('SettingsCard('), 1)
        self.assertIn('SettingsCard(Modifier.testTag("linux-environment-configuration"))', config)
        self.assertLess(config.index('LinuxChoiceRow("Linux 发行版"'), config.index('LinuxChoiceRow("运行方式"'))
        self.assertLess(config.index('LinuxChoiceRow("运行方式"'), config.index('testTag("linux-install-environment")'))
        self.assertNotIn('testTag("linux-install-environment")', status)
        self.assertNotIn('item("linux-install-actions")', source)

    def test_install_button_is_full_width_primary_with_direct_request_and_owner_gate(self):
        source = SCREEN.read_text()
        config = source.split('item("linux-configuration") {', 1)[1].split('item("linux-tools-heading")', 1)[0]
        for token in [
            'Button(enabled = installEnabled,', 'ButtonDefaults.buttonColorsPrimary()',
            'Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)',
            '.fillMaxWidth().heightIn(min = 48.dp).testTag("linux-install-environment")',
            'if (basicCurrent() && page.prepareInstall(distribution, "primary", mode)) {',
            'Text("安装环境", color = if (installEnabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.disabledOnPrimaryButton)',
        ]:
            self.assertIn(token, config)
        self.assertNotIn('page.install(', config)

    def test_install_intent_is_one_shot_and_old_prepared_never_autostarts(self):
        source = SCREEN.read_text()
        self.assertIn('var installRequest by remember(page, runningMode) { mutableStateOf<LinuxInstallChoice?>(null) }', source)
        effect = source.split('LaunchedEffect(installRequest, installation, tabScope, runningMode, state.revoked)', 1)[1].split('val registration =', 1)[0]
        for token in ['val request = installRequest ?: return@LaunchedEffect',
                      '!basicCurrent()', 'request.distribution != distribution', 'request.executionMode != mode',
                      'runningModeStore.mode.value != RunningMode.ROOT',
                      'controller.chrootInstallation else controller.installation',
                      'if (current?.busy == true) return@LaunchedEffect',
                      'val prepared = current?.prepared', '!current.unknown', '!current.recoveryBlocked',
                      '!current.cleanupPending', '!current.registered', 'current.unavailableReason == null',
                      'current.receipt == null', 'prepared.artifact.distribution.equals(request.distribution, true)',
                      'prepared.installation.executionMode == request.executionMode', 'val preparationFailed = awaitingInstallPreparation && current?.operationFailed == true',
                      '!preparationFailed && prepared != null', 'awaitingInstallPreparation = false', 'page.install(prepared)']:
            self.assertIn(token, effect)
        self.assertLess(effect.rindex('installRequest = null'), effect.index('page.install(prepared)'))
        self.assertLess(effect.index('if (current?.busy == true)'), effect.rindex('installRequest = null'))
        self.assertNotIn('confirmInstall', source)
        self.assertNotIn('下载并安装', source)
        self.assertIn('title = "启用环境"', source)
        self.assertIn('DaiyuConfirmDialog("完成环境设置"', source)

    def test_cancel_choice_and_tab_changes_clear_pending_click(self):
        source = SCREEN.read_text()
        self.assertIn('onInstall = { if (basicCurrent()) { awaitingInstallPreparation = false; installRequest = LinuxInstallChoice(distribution, mode) } }', source)
        self.assertIn('onCancel = { if (basicCurrent()) { installRequest = null; page.cancelInstall() } }', source)
        self.assertIn('state.busy -> TextButton("取消安装", onClick = onCancel', source)
        choose = source.split('fun choose(', 1)[1].split('val nextChoice =', 1)[0]
        self.assertIn('installRequest = null', choose)
        self.assertIn('tabScope = Any(); installRequest = null; confirmUse = false; confirmPending = false; tab = it', source)
        self.assertIn('page.discardInstall(mode)', source)

    def test_original_recovery_resume_and_enable_actions_stay_in_status_card(self):
        source = SCREEN.read_text()
        status = source.split('item("linux-status") {', 1)[1].split('item("linux-configuration")', 1)[0]
        self.assertIn('TextButton("继续上次安装"', status)
        self.assertIn('else LinuxInstallAction(displayedInstallation, page, mode, enabled,', status)
        helper = source.split('private fun LinuxInstallAction(', 1)[1]
        for text in ['当前设备暂不可用', '取消安装', '恢复安装', '重试完成安装', '启用环境', '继续设置', '完成安装', '继续安装', '操作未完成，请重试。']:
            self.assertIn(text, helper)
        self.assertIn('else -> Unit', helper)
        self.assertNotIn('"安装环境"', helper)

    def test_moved_button_preserves_all_original_state_branch_gates(self):
        source = SCREEN.read_text()
        config = source.split('item("linux-configuration") {', 1)[1].split('item("linux-tools-heading")', 1)[0]
        for token in ['(!installed || pendingRequest)', '!(pendingRequest && installation?.prepared != null && !installationMatches)', 'displayedInstallation?.let {',
                      'it.unavailableReason == null && !it.busy && !it.unknown && !it.recoveryBlocked &&',
                      '!it.registered && it.prepared == null && it.receipt?.directorySyncConfirmed != true',
                      'val installEnabled = enabled && otherInstallation?.let {',
                      'it.busy || it.unknown || it.recoveryBlocked || it.cleanupPending || it.registrationPending || (it.prepared != null && !it.registered)']:
            self.assertIn(token, config)
        # Exhaust every original branch flag, including malformed recovery/receipt combinations.
        for flags in itertools.product((False, True), repeat=12):
            exists, unavailable, busy, unknown, recovery, cleanup, registered, prepared, receipt, synced, visible, resume = flags
            original = visible and not resume and exists and not (
                unavailable or busy or unknown or recovery or (cleanup and prepared) or registered
                or (receipt and synced) or (receipt and prepared) or prepared
            )
            moved = visible and not resume and exists and not unavailable and not busy and not unknown and not recovery \
                and not registered and not prepared and not (receipt and synced)
            self.assertEqual(original, moved, flags)


if __name__ == '__main__':
    unittest.main()
