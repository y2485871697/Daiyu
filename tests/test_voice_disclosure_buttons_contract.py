"""Voice disclosure and account-action presentation, without network or storage writes."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def block(text, marker):
    start = text.index('{', text.index(marker))
    depth = 0
    for i in range(start, len(text)):
        depth += (text[i] == '{') - (text[i] == '}')
        if depth == 0:
            return text[start + 1:i]
    raise AssertionError(marker)


class VoiceDisclosureButtonsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / 'VoiceAccountPanel.kt').read_text()
        cls.components = (SRC / 'VoiceSettingsComponents.kt').read_text()

    def test_advanced_keeps_its_title_and_shared_bidirectional_motion(self):
        advanced = block(self.panel, 'SettingsCard(Modifier.testTag("voice-create-advanced-card"))')
        self.assertIn('SettingsRow("高级：新建后付费音色"', advanced)
        self.assertNotIn('收起高级设置', advanced)
        self.assertIn('settingsDisclosureRotation(advancedCreation)', advanced)
        self.assertIn('SettingsExpandableContent(advancedCreation)', advanced)
        self.assertIn('SettingsSwitchRow("新建后付费音色", postpaid, enabled = enabled)', advanced)
        self.assertNotIn('if (advancedCreation) SettingsSwitchRow', advanced)

    def test_both_manual_id_disclosures_reuse_motion_without_changing_the_title(self):
        for marker in ('if (page == "IMPORT") item', 'SettingsCard(Modifier.testTag("voice-create-manual-card"))'):
            with self.subTest(marker=marker):
                content = block(self.panel, marker)
                self.assertIn('SettingsRow("备用方式：从控制台复制 ID"', content)
                self.assertIn('settingsDisclosureRotation(manualId)', content)
                self.assertIn('SettingsExpandableContent(manualId)', content)
                self.assertNotIn('if (manualId) {', content)
                self.assertIn('manualId = !manualId', content)
                self.assertIn('if (current()) uriHandler.openUri(', content)
                self.assertIn('AccountField(', content)
        self.assertEqual(2, self.panel.count('SettingsExpandableContent(manualId)'))

    def test_all_save_entrypoints_share_theme_primary_pill(self):
        self.assertIn('internal fun VoiceAccountSaveButton(', self.components)
        button = self.components.split('internal fun VoiceAccountSaveButton(', 1)[1].split('\ninternal fun ', 1)[0]
        for token in ('assistantPreviewPalette()', 'color = colors.primary', 'textColor = colors.onPrimary',
                      'ButtonDefaults.textButtonColorsPrimary(', '.fillMaxWidth().testTag(tag)',
                      'minHeight = 48.dp, cornerRadius = 24.dp', 'enabled = enabled'):
            self.assertIn(token, button)
        self.assertNotIn('Color(0x', button)
        self.assertEqual(1, self.panel.count('VoiceAccountSaveButton("保存账户", "voice-account-save"'))
        self.assertEqual(1, self.panel.count('VoiceAccountSaveButton("保存账户信息", "voice-sync-save"'))
        settings = (SRC / 'VoiceSettingsPanel.kt').read_text()
        self.assertEqual(1, settings.count('VoiceAccountSaveButton("保存账户", "voice-account-save"'))
        self.assertNotIn('SettingsRow("保存账户信息"', self.panel)

    def test_sync_save_still_fetches_projects_before_encrypted_save(self):
        storage = block(self.panel, 'SettingsCard(Modifier.testTag("voice-sync-storage-card"))')
        self.assertIn('enabled = enabled && ak.isNotBlank() && sk.isNotBlank()', storage)
        ordered = ['val a = ak; val s = sk; val preferred = project', 'execute("账户保存未确认',
                   'val p = refreshProjects(a, s, preferred)', 'currentCoroutineContext().ensureActive()',
                   'resources.preferences.save(account!!.fingerprint, a, s, p)',
                   'report("账户信息已加密保存在本机")']
        self.assertEqual(sorted(storage.index(s) for s in ordered), [storage.index(s) for s in ordered])
        self.assertNotIn('resources.voices.create(', storage)
        self.assertNotIn('resources.preferences.clear(', storage)


if __name__ == '__main__':
    unittest.main()
