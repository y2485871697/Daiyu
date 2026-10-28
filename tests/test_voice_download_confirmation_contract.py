from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"

class VoiceDownloadConfirmationContractTest(unittest.TestCase):
    def test_conversation_uses_its_sibling_card_without_changing_recognition(self):
        panel = (SRC / "VoiceSettingsPanel.kt").read_text()
        self.assertIn('OfflineSpeechPackCard(context, ::current, conversationStyle = true)', panel)
        self.assertIn('item { OfflineSpeechPackCard(context, ::current) }', panel)
        offline = (SRC / "ui/screens/settings/OfflineSpeechSettingsScreen.kt").read_text()
        self.assertIn('conversationStyle: Boolean = false', offline)
        self.assertIn('if (conversationStyle) VoiceSettingsCard(modifier, content)', offline)
        self.assertIn('else SettingsCard(modifier, content)', offline)

    def test_confirmation_has_exact_copy_and_shared_content_sized_sheet(self):
        text = (SRC / "ui/screens/settings/OfflineSpeechSettingsScreen.kt").read_text()
        confirmation = text.split('internal fun OfflineSpeechDownloadConfirmation(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('将从下载约141 MB的语音模型，可能产生流量。', confirmation)
        self.assertIn('ChatSelectionSheet(', confirmation)
        self.assertIn('showCloseButton = false', confirmation)
        self.assertNotIn('height =', confirmation)
        self.assertNotIn('Hugging Face', text)
        self.assertNotIn('DaiyuConfirmDialog(', text)
        self.assertIn('模型权重授权尚未明确', text)

    def test_buttons_explicitly_use_secondary_and_primary_roles(self):
        text = (SRC / "ui/screens/settings/OfflineSpeechSettingsScreen.kt").read_text()
        confirmation = text.split('internal fun OfflineSpeechDownloadConfirmation(', 1)[1].split('\n@Composable', 1)[0]
        cancel, confirm = confirmation.split('text = "下载"', 1)
        self.assertIn('secondary = true', cancel)
        self.assertIn('onClick = onDismiss', cancel)
        self.assertIn('primary = true', confirm)
        self.assertIn('onClick = onConfirm', confirm)
        self.assertEqual(2, confirmation.count('.weight(1f).heightIn(min = 48.dp)'))
        self.assertNotIn('Color(', confirmation)

    def test_original_owner_lifecycle_and_download_state_guards_remain(self):
        text = (SRC / "ui/screens/settings/OfflineSpeechSettingsScreen.kt").read_text()
        card = text.split('internal fun OfflineSpeechPackCard(', 1)[1].split('internal fun OfflineSpeechDownloadConfirmation(', 1)[0]
        for guard in ('owner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)',
                      'if (canAct()) OfflineSpeechPack.download(context)',
                      'if (pack.checking || pack.ready) null',
                      'OfflineSpeechPack.cancelDownload()',
                      'pack.error -> "下载未完成或校验失败，点击重试。"',
                      'onDismiss = { confirm = false }'):
            self.assertIn(guard, card)
        self.assertEqual(1, card.count('OfflineSpeechPack.download(context)'))

if __name__ == "__main__":
    unittest.main()
