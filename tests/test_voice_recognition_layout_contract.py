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

    def test_section_selector_keeps_card_width_and_compact_height(self):
        selector = body(self.panel, 'if (child == null) Column(')
        self.assertIn('SettingsCard(Modifier.testTag("voice-section-selector-card"))', selector)
        self.assertNotIn('.widthIn(', selector)
        self.assertNotIn('.width(', selector)
        self.assertIn('testTag("voice-section-selector-card")', selector)
        self.assertIn('testTag("voice-section-select")', selector)
        self.assertIn('.heightIn(min = 48.dp)', selector)
        self.assertIn('choosingSection = !choosingSection', selector)
        self.assertNotIn('.background(', selector)
        self.assertNotIn('.clip(', selector)
        self.assertNotIn('SettingsRow(', selector)

    def test_section_menu_uses_shared_motion_and_preserves_navigation_guard(self):
        selector = body(self.panel, 'if (child == null) Column(')
        self.assertIn('SettingsExpandableContent(expanded = choosingSection)', selector)
        self.assertNotIn('if (choosingSection) Column(', selector)
        self.assertIn('.heightIn(max = 220.dp)', selector)
        self.assertIn('.verticalScroll(rememberScrollState())', selector)
        self.assertIn('if (choosingSection) leavePage {', selector)
        self.assertIn('section = entry.first; choosingSection = false', selector)
        self.assertIn('if (target != profile) drafts[profile] = draft', selector)

    def test_section_selector_keeps_original_arrow(self):
        selector = body(self.panel, 'if (child == null) Column(')
        self.assertIn('DaiyuIcon(DaiyuIcons.ChevronDown, contentDescription = "选择语音分组"', selector)
        self.assertIn('Modifier.rotate(if (choosingSection) 180f else 0f)', selector)
        self.assertNotIn('settingsDisclosureRotation', selector)

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

    def test_home_keeps_download_owner_without_redundant_offline_dictation_switch(self):
        home = self.panel.split('"RECOGNITION" -> {', 1)[1].split('"READ_ALOUD", "CONVERSATION" ->', 1)[0]
        self.assertEqual(1, home.count('OfflineSpeechPackCard(context, ::current)'))
        self.assertNotIn('SettingsSwitchRow("离线听写"', home)
        self.assertNotIn('offline-speech-enable', home)
        self.assertNotIn('OfflineSpeechPack.setEnabled', home)
        self.assertNotIn('OfflineSpeechPack.state', self.panel)
        self.assertIn('switchFeature("input", it) { inputEnabled = it }', home)
        self.assertIn('child = "ASR"', home)

    def test_asr_has_its_own_title_without_home_selector(self):
        self.assertIn('"ASR" -> "识别方式"', self.panel)
        self.assertIn('if (child == null) Column(', self.panel)
        self.assertNotIn('voice-section-select', self.asr)
        self.assertIn('SettingsSegmentedTabs(', self.asr)
        self.assertIn('labels = listOf("本机识别", "豆包识别")', self.asr)
        self.assertIn('selectedIndex = if (draft.dictation == "DOUBAO") 1 else 0', self.asr)
        self.assertIn('enabled = !busy', self.asr)
        self.assertIn('testTag("voice-asr-method-card")', self.asr)
        self.assertNotIn('SettingsRadioRow(', self.asr)
        self.assertNotIn('SettingsCard(Modifier.testTag("voice-asr-method-card"))', self.asr)

    def test_asr_segments_keep_guarded_saved_method_without_saving_other_draft_fields(self):
        choice = self.asr.split('SettingsSegmentedTabs(', 1)[1].split('if (draft.dictation == "DOUBAO") {', 1)[0]
        self.assertIn('if (current() && !busy)', choice)
        self.assertIn('val method = if (index == 0) "SYSTEM" else "DOUBAO"', choice)
        self.assertIn('changeDraft(draft.copy(dictation = method))', choice)
        self.assertIn('save((saved ?: VoiceConfiguration()).copy(dictation = method))', choice)
        self.assertNotIn('save(draft', choice)
        self.assertIn('"voice-asr-method:system" else "voice-asr-method:doubao"', choice)
        self.assertNotIn('识别音频会发送到豆包', self.asr)

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
                       'cornerRadius = 24.dp', 'ButtonDefaults.textButtonColorsPrimary(color = DaiyuPalette.switchTrack)',
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
        sheet = self.panel.split('"ASR_ADVANCED" -> ChatSelectionSheet(', 1)[1].split('"DUPLEX_VOICE" ->', 1)[0]
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
        advanced = self.panel.split('"ASR_ADVANCED" -> ChatSelectionSheet(', 1)[1].split('"DUPLEX_VOICE" ->', 1)[0]
        self.assertIn('save((saved ?: VoiceConfiguration()).copy(asrResource = resource))', advanced)
        self.assertIn('changeDraft(draft.copy(asrResource = resource))', advanced)
        self.assertIn('https://console.volcengine.com/speech/app', self.asr)
        self.assertNotIn('saveAccount(', self.asr)
        key = self.asr.split('SettingsTextField("ASR API Key"', 1)[1].split('TextButton("保存连接设置"', 1)[0]
        self.assertNotIn('save(', key)
        self.assertNotIn('uriHandler', key)


if __name__ == '__main__':
    unittest.main()
