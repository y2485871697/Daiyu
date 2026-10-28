"""Voice settings layout wiring; device rendering waits for the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


def body(text, marker):
    start = text.index("{", text.index(marker))
    depth = 0
    for index in range(start, len(text)):
        depth += (text[index] == "{") - (text[index] == "}")
        if depth == 0:
            return text[start + 1:index]
    raise AssertionError(f"Unclosed block: {marker}")


class VoiceRecognitionLayoutContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / "VoiceSettingsPanel.kt").read_text()
        cls.fields = (SRC / "ui/screens/settings/SettingsUiComponents.kt").read_text()
        cls.asr = body(cls.panel, 'if (child == "ASR")')

    def test_section_selector_has_no_filled_container(self):
        selector = body(self.panel, 'if (child == null) Column(')
        header = self.panel.split('if (child == null) Column(', 1)[1].split('Row(', 1)[0]
        self.assertNotIn('.background(', header + selector)
        self.assertNotIn('.clip(', header + selector)
        self.assertIn('testTag("voice-section-select")', selector)
        self.assertIn('choosingSection = !choosingSection', selector)
        self.assertIn('leavePage {', selector)

    def test_home_omits_extra_offline_entry_and_keeps_download_and_method_cards(self):
        home = self.panel.split('"RECOGNITION" -> {', 1)[1].split('"READ_ALOUD", "CONVERSATION" ->', 1)[0]
        group = body(home, 'SettingsCard(Modifier.testTag("voice-recognition-input-card"))')
        self.assertIn('tag = "voice-input-enable"', group)
        self.assertNotIn('tag = "voice-offline-settings"', home)
        self.assertNotIn('SettingsRow("离线语音"', home)
        self.assertNotIn('child = "OFFLINE"', home)
        self.assertIn('switchFeature("input", it) { inputEnabled = it }', group)
        self.assertNotIn('OfflineSpeechPackCard(', group)
        self.assertLess(home.index('voice-recognition-input-card'), home.index('OfflineSpeechPackCard('))
        self.assertLess(home.index('OfflineSpeechPackCard('), home.index('voice-recognition-method-card'))
        self.assertIn('SettingsCard(Modifier.testTag("voice-recognition-method-card"))', home)

    def test_download_card_reuses_same_settings_card_without_extra_wrapper(self):
        offline = (SRC / "ui/screens/settings/OfflineSpeechSettingsScreen.kt").read_text()
        card = body(offline, 'internal fun OfflineSpeechPackCard(')
        self.assertIn('conversationStyle: Boolean = false', offline)
        self.assertIn('val modifier = Modifier.testTag("voice-offline-pack-card")', card)
        self.assertIn('if (conversationStyle) VoiceSettingsCard(modifier, content)', card)
        self.assertIn('else SettingsCard(modifier, content)', card)
        self.assertEqual(1, card.count('else SettingsCard('))
        self.assertNotIn('.background(', card)
        self.assertNotIn('.clip(', card)
        self.assertIn('owner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)', card)
        self.assertIn('if (pack.checking || pack.ready) null', card)
        self.assertIn('OfflineSpeechPack.cancelDownload()', card)
        self.assertIn('if (canAct()) OfflineSpeechPack.download(context)', card)
        self.assertIn('onDismiss = { confirm = false }', card)

    def test_home_keeps_download_owner_and_ready_gated_offline_dictation(self):
        home = self.panel.split('"RECOGNITION" -> {', 1)[1].split('"READ_ALOUD", "CONVERSATION" ->', 1)[0]
        self.assertEqual(1, home.count('OfflineSpeechPackCard(context, ::current)'))
        self.assertIn('if (pack.ready && draft.dictation != "DOUBAO")', home)
        self.assertIn('SettingsSwitchRow("离线听写", pack.enabled,', home)
        self.assertIn('if (current()) OfflineSpeechPack.setEnabled(context, it)', home)

    def test_asr_has_its_own_title_without_home_selector(self):
        self.assertIn('"ASR" -> "识别方式"', self.panel)
        self.assertIn('if (child == null) Column(', self.panel)
        self.assertNotIn('voice-section-select', self.asr)
        self.assertIn('SettingsRadioRow("本机识别"', self.asr)
        self.assertIn('SettingsRadioRow("豆包识别"', self.asr)
        self.assertIn('SettingsCard(Modifier.testTag("voice-asr-method-card"))', self.asr)

    def test_connection_key_save_advanced_and_console_share_one_card(self):
        group = body(self.asr, 'SettingsCard(Modifier.testTag("voice-asr-account-card"))')
        positions = [group.index(marker) for marker in [
            'SettingsSectionTitle("连接豆包账户")', 'SettingsTextField("ASR API Key"',
            'TextButton("保存连接设置"', 'SettingsRow("高级：更换识别服务"',
            'SettingsRow("打开豆包控制台"',
        ]]
        self.assertEqual(sorted(positions), positions)
        self.assertNotIn('VoiceSettingsCard(', group)
        self.assertNotIn('SettingsCard(', group)
        self.assertNotIn('SettingsRow("保存连接设置"', group)
        self.assertIn('tag = "voice-asr-console", showChevron = true', group)
        self.assertIn('tag = "voice-asr-advanced"', group)
        self.assertIn('showChevron = true, onClick = if (busy)', group)

    def test_save_is_full_width_round_theme_accent_below_key(self):
        save = self.asr.split('TextButton("保存连接设置"', 1)[1].split('SettingsRow("高级：更换识别服务"', 1)[0]
        for marker in ['.fillMaxWidth().testTag("voice-save")', 'minHeight = 48.dp',
                       'cornerRadius = 24.dp', 'ButtonDefaults.textButtonColorsPrimary()',
                       'enabled = !busy && draft.asrKey.isNotBlank()',
                       'if (current()) save(recognitionConfiguration().copy(asrKey = draft.asrKey.trim()))']:
            self.assertIn(marker, save)
        self.assertNotIn('Color(', save)

    def test_asr_uses_existing_trailing_eye_not_the_text_visibility_button(self):
        key = self.asr.split('SettingsTextField("ASR API Key"', 1)[1].split('TextButton("保存连接设置"', 1)[0]
        self.assertIn('password = true, enabled = !busy, compact = true', key)
        compact = body(self.fields, 'if (compact) {')
        self.assertLess(compact.index('field()'), compact.index('if (password) visibilityButton()'))
        visibility = body(self.fields, 'val visibilityButton:')
        self.assertEqual(1, visibility.count('app.daiyu.android.ui.haptics.material.IconButton('))
        self.assertIn('enabled = enabled', visibility)
        self.assertIn('onClick = toggleSecret', visibility)
        self.assertIn('testTag("settings-secret-visibility:$label")', visibility)
        self.assertNotIn('TextButton(', visibility)
        self.assertNotIn('if (!compact)', visibility)
        self.assertIn('Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff', visibility)
        self.assertIn('Lifecycle.Event.ON_PAUSE', self.fields)
        self.assertNotIn('rememberSaveable', self.fields)

    def test_advanced_opens_shared_bottom_sheet_with_all_four_original_services(self):
        self.assertIn('if (current()) dialog = "ASR_ADVANCED"', self.asr)
        self.assertNotIn('asrAdvanced', self.panel)
        sheet = self.panel.split('"ASR_ADVANCED" -> ChatSelectionSheet(', 1)[1].split('"ASR_RESOURCE" ->', 1)[0]
        self.assertIn('onDismiss = { dialog = null }', sheet)
        self.assertIn('subtitle = "仅在控制台开通了不同服务时更改。"', sheet)
        self.assertIn('.verticalScroll(rememberScrollState()).testTag("voice-asr-service-sheet")', sheet)
        self.assertIn('DoubaoAsrProtocol.resources.forEachIndexed', sheet)
        for title in ['识别 2.0 · 按时长', '识别 2.0 · 按并发', '识别 1.0 · 按时长', '识别 1.0 · 按并发']:
            self.assertEqual(1, sheet.count(f'"{title}"'))
        self.assertIn('draft.asrResource == resource, !busy', sheet)
        self.assertIn('if (current() && !busy)', sheet)
        self.assertIn('dialog = null', sheet)
        self.assertNotIn('VoiceChoiceDialog(', sheet)
        self.assertNotIn('WindowDialog(', sheet)

    def test_connection_keeps_explicit_save_and_advanced_does_not_send_draft_key(self):
        advanced = self.panel.split('"ASR_ADVANCED" -> ChatSelectionSheet(', 1)[1].split('"ASR_RESOURCE" ->', 1)[0]
        self.assertIn('save((saved ?: VoiceConfiguration()).copy(asrResource = resource))', advanced)
        self.assertIn('changeDraft(draft.copy(asrResource = resource))', advanced)
        self.assertIn('https://console.volcengine.com/speech/app', self.asr)
        self.assertNotIn('saveAccount(', self.asr)
        key = self.asr.split('SettingsTextField("ASR API Key"', 1)[1].split('TextButton("保存连接设置"', 1)[0]
        self.assertNotIn('save(', key)
        self.assertNotIn('uriHandler', key)


if __name__ == '__main__':
    unittest.main()
