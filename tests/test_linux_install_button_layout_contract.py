"""Source-only regression for moving the initial install button; no device execution."""
from pathlib import Path
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
        effect = source.split('LaunchedEffect(installRequest, installation, tabScope, runningMode, state.revoked)', 1)[1].split('LaunchedEffect(proot, chroot', 1)[0]
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

    def test_choice_tab_and_mode_changes_clear_both_click_fields(self):
        source = SCREEN.read_text()
        choose = source.split('fun choose(', 1)[1].split('val nextChoice =', 1)[0]
        self.assertIn('installRequest = null', choose)
        self.assertIn('awaitingInstallPreparation = false', choose)
        self.assertIn('tabScope = Any(); installRequest = null; awaitingInstallPreparation = false; confirmUse = false; confirmPending = false; tab = it', source)

    def test_residual_request_is_really_discarded_once_and_active_click_is_preserved(self):
        source = SCREEN.read_text()
        effect = source.split('LaunchedEffect(proot, chroot, installRequest, runningMode, tabScope, state)', 1)[1].split('val registration =', 1)[0]
        for token in ['if (filesOnly || page.state.value.revoked)',
                      'listOfNotNull(controller.installation, controller.chrootInstallation)',
                      'val current = coordinator.state.value', 'val prepared = current.prepared ?: continue',
                      'val activeClick = !current.operationFailed && request != null',
                      'request.executionMode == prepared.installation.executionMode',
                      'request.distribution.equals(prepared.artifact.distribution, true)',
                      '!activeClick && current.canDiscard', 'prepared !in discardedRequests',
                      'page.discardInstall(prepared)', 'discardedRequests.add(prepared)']:
            self.assertIn(token, effect)
        self.assertLess(effect.index('page.discardInstall(prepared)'), effect.index('discardedRequests.add(prepared)'))
        self.assertIn('val discardedRequests = remember(page)', source)
        self.assertNotIn('copy(prepared = null)', effect)

    def test_old_resume_and_cancel_buttons_are_removed_but_publication_recovery_remains(self):
        source = SCREEN.read_text()
        for text in ['TextButton("继续安装"', 'TextButton("取消安装"', 'TextButton("继续上次安装"', 'onInstall =', 'onCancel =']:
            self.assertNotIn(text, source)
        helper = source.split('private fun LinuxInstallAction(', 1)[1]
        for text in ['当前设备暂不可用', '恢复安装', '重试完成安装', '启用环境', '继续设置', '完成安装', '操作未确认成功：']:
            self.assertIn(text, helper)
        self.assertIn('state.busy -> Unit', helper)
        self.assertIn('Text(notice, color = MiuixTheme.colorScheme.error)', helper)
        self.assertIn('state.recoveryBlocked || state.cleanupPending', helper)
        self.assertIn('title = "启用环境"', source)
        self.assertIn('DaiyuConfirmDialog("完成环境设置"', source)

    def test_original_rotating_ball_is_busy_only_next_to_install_summary(self):
        source = SCREEN.read_text()
        status = source.split('item("linux-status") {', 1)[1].split('item("linux-configuration")', 1)[0]
        for token in ['import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator',
                      'Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically)',
                      'if (installation?.busy == true) InfiniteProgressIndicator(',
                      'Modifier.testTag("linux-install-progress"), size = 20.dp',
                      'installation?.busy == true -> "正在安装…"',
                      'installation?.discarding == true -> "正在取消未完成安装…"']:
            self.assertIn(token, source)
        self.assertEqual(source.count('InfiniteProgressIndicator('), 1)
        self.assertIn('linux-install-progress', status)

    def test_primary_button_is_visible_but_cannot_replace_an_uncancelled_request(self):
        source = SCREEN.read_text()
        config = source.split('item("linux-configuration") {', 1)[1].split('item("linux-tools-heading")', 1)[0]
        for token in ['(!installed || pendingRequest)', 'displayedInstallation?.let {',
                      'it.unavailableReason == null && !it.busy && !it.unknown && !it.recoveryBlocked',
                      '!it.cleanupPending && !it.registrationPending && !it.registered',
                      'it.receipt == null && it.registration == null',
                      'val installEnabled = enabled && displayedInstallation.prepared == null && otherInstallation?.let {',
                      'it.busy || it.unknown || it.recoveryBlocked || it.cleanupPending || it.registrationPending || (it.prepared != null && !it.registered)']:
            self.assertIn(token, config)

    def test_discard_is_identity_bound_and_cannot_delete_published_or_unknown_state(self):
        coordinator = (ROOT / 'app/src/main/kotlin/app/daiyu/android/terminal/install/LinuxInstallCoordinator.kt').read_text()
        predicate = coordinator.split('val canDiscard:', 1)[1].split('}', 1)[0]
        for token in ['prepared != null', '!busy', '!unknown', '!recoveryBlocked', '!cleanupPending',
                      '!registrationPending', '!registered', 'receipt == null', 'registration == null']:
            self.assertIn(token, predicate)
        self.assertIn('eligible = { it.canDiscard && it.prepared === prepared }', coordinator)
        self.assertIn('installer.discard(prepared)', coordinator)
        controller = (ROOT / 'app/src/main/kotlin/app/daiyu/android/terminal/LinuxSettingsController.kt').read_text()
        discard = controller.split('internal fun discardInstall(page:', 1)[1].split('internal fun cancelInstall', 1)[0]
        self.assertIn('!active(page)', discard)
        self.assertIn('target?.discard(prepared)', discard)
        self.assertNotIn('allowChroot()', discard)


if __name__ == '__main__':
    unittest.main()
