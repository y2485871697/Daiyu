"""Local voice removal uses the collaboration sheet without altering account operations."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class VoiceLocalRemovalSheetContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / "VoiceAccountPanel.kt").read_text()
        cls.sheet = (SRC / "VoiceLocalRemovalSheet.kt").read_text()

    def test_only_local_removal_omits_the_description(self):
        self.assertIn('"REMOVE" -> if (!latestVoice.uncertain) ask(null)', self.panel)
        self.assertNotIn('仅移除本机记录，不删除云端音色或任务，不退款、不释放名额', self.panel)
        self.assertIn('val label: String?', self.panel)
        self.assertIn('if (pending.label == null) VoiceLocalRemovalSheet(', self.panel)
        self.assertIn('else DaiyuConfirmDialog("确认本次操作", pending.label, "确认", false,', self.panel)
        self.assertIn('ask("我有权使用此声音，并同意正式合成可能产生音色费用")', self.panel)
        self.assertIn('ask("查询项目 $p 的槽位；已制作槽位将用当前语音 Key 校验，不制作、不合成",', self.panel)
        self.assertEqual(1, self.panel.count('ask(null)'))

    def test_confirmation_keeps_the_original_owner_and_execution_guards(self):
        confirm = self.panel.split('confirmation?.let { pending ->', 1)[1].split('@Composable', 1)[0]
        self.assertIn('confirmation === pending && enabled && feedback.visit == pending.visit', confirm)
        self.assertIn('execute(pending.failureNotice, block = pending.action)', confirm)
        self.assertIn('VoiceLocalRemovalSheet(onConfirm = confirm, onDismiss = { confirmation = null })', confirm)
        self.assertIn('onConfirm = confirm, onDismiss = { confirmation = null }', confirm)
        self.assertIn('preview.stop(); withContext(Dispatchers.IO) { resources.voices.removeLocal(latestVoice) }', self.panel)
        self.assertIn('previewGuard.execute(::operationCurrent, { latestStrictStop() })', self.panel)

    def test_shell_is_the_shared_centered_bodyless_confirmation(self):
        for token in ('DaiyuDeleteConfirmDialog(', 'title = "移除这个音色？"', 'confirmText = "移除"',
                      'onConfirm = onConfirm', 'onDismiss = onDismiss'):
            self.assertIn(token, self.sheet)
        for token in ('ConversationAgentSheet', 'ConversationAgentHandle', 'HorizontalDivider', 'Text(message', 'removeLocal('):
            self.assertNotIn(token, self.sheet)

    def test_shared_card_owns_buttons_without_recoloring_account_operations(self):
        card = (SRC / 'ui/components/DaiyuDeleteConfirmDialog.kt').read_text()
        for token in ('DeleteConfirmButton(stringResource(R.string.action_cancel), onDismiss', 'DeleteConfirmButton(confirmText, onConfirm',
                      'testTag("dialog-confirm")', 'testTag("dialog-cancel")', '0xFFBB5358', '0xFFCB6B70'):
            self.assertIn(token, card)
        self.assertNotIn('Color(', self.sheet)
        self.assertNotIn('resources.', self.sheet)

    def test_cloud_scope_and_real_ui_fixture_are_present(self):
        workflow = (ROOT / '.github/workflows/voice-selector-checks.yml').read_text()
        visual = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceLocalRemovalSheetActivityTest.kt').read_text()
        for token in ('test_voice_local_removal_sheet_contract.py', 'local_removal',
                      'checks_only.init.gradle', ':app:compileDebugKotlin', ':app:compileDebugAndroidTestKotlin'):
            self.assertIn(token, workflow)
        for token in ('VoiceLocalRemovalSheet(', 'listOf(false, true)', 'listOf(1f, 1.3f)',
                      'captureToImage()', 'KEYCODE_BACK', 'preview-dialog-scrim',
                      'confirmed.get()', 'dismissed.get()'):
            self.assertIn(token, visual)


if __name__ == '__main__':
    unittest.main()
