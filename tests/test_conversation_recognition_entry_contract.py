"""Voice conversation routes recognition through the same independent settings entry."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def body(text, marker):
    start = text.index('{', text.index(marker))
    depth = 0
    for index in range(start, len(text)):
        depth += (text[index] == '{') - (text[index] == '}')
        if depth == 0:
            return text[start + 1:index]
    raise AssertionError(f'Unclosed block: {marker}')


class ConversationRecognitionEntryContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / 'VoiceSettingsPanel.kt').read_text()
        cls.conversation = cls.panel.split('"READ_ALOUD", "CONVERSATION" -> {', 1)[1].split('"REALTIME" ->', 1)[0]

    def test_conversation_has_no_doubao_toggle_or_inline_account_editor(self):
        self.assertNotIn('SettingsSwitchRow("豆包语音识别"', self.conversation)
        self.assertNotIn('SettingsTextField("ASR API Key"', self.conversation)
        self.assertNotIn('SettingsRow("识别服务"', self.conversation)
        self.assertIn('SettingsSwitchRow("启用语音对话"', self.conversation)
        self.assertIn('SettingsSwitchRow("云端朗读"', self.conversation)

    def test_conversation_entry_reuses_recognition_card_summary_and_guarded_route(self):
        marker = 'SettingsCard(Modifier.testTag("voice-recognition-method-card"))'
        self.assertIn(marker, self.conversation)
        recognition = self.panel.split('"RECOGNITION" -> {', 1)[1].split('"READ_ALOUD", "CONVERSATION" ->', 1)[0]
        self.assertEqual(body(recognition, marker), body(self.conversation, marker))
        entry = body(self.conversation, marker)
        for token in ['SettingsRow("识别方式", tag = "voice-recognition-method"',
                      'saved?.dictation == "DOUBAO"', 'showChevron = true',
                      'onClick = if (busy) null', 'if (current() && stopAudio())', 'child = "ASR"']:
            self.assertIn(token, entry)
        self.assertIn('if (section == "CONVERSATION") item { SettingsCard(', self.conversation)

    def test_entry_sits_between_offline_card_and_cloud_reading(self):
        self.assertIn('if (section == "CONVERSATION" && draft.dictation != "DOUBAO")', self.conversation)
        self.assertIn('voice-recognition-method-card', self.conversation)
        self.assertLess(self.conversation.index('OfflineSpeechPackCard('), self.conversation.index('voice-recognition-method-card'))
        self.assertLess(self.conversation.index('voice-recognition-method-card'), self.conversation.index('SettingsSwitchRow("云端朗读"'))
        self.assertIn('VoiceSettingsPreview(runtime, profile', self.conversation)

    def test_shared_child_keeps_profile_scoped_save_and_explicit_key_save(self):
        self.assertIn('val profile = if (section == "CONVERSATION") VoiceProfile.CONVERSATION else VoiceProfile.READ_ALOUD', self.panel)
        save = self.panel.split('fun save(value: VoiceConfiguration', 1)[1].split('fun switchFeature(', 1)[0]
        self.assertIn('if (!current() || busy) return', save)
        self.assertIn('val target = profile', save)
        self.assertIn('runtime.save(next, target)', save)
        child = body(self.panel, 'if (child == "ASR")')
        self.assertIn('LinuxSettingsTheme { SettingsSegmentedTabs(', child)
        self.assertIn('save((saved ?: VoiceConfiguration()).copy(dictation = method))', child)
        self.assertIn('save(recognitionConfiguration().copy(asrKey = draft.asrKey.trim()))', child)
        self.assertIn('fun leaveChild() = leavePage { child = null; notice = "" }', self.panel)

    def test_removed_inline_path_does_not_leave_background_autosave_or_dead_dialog(self):
        self.assertNotIn('conversationAsrDirty', self.panel)
        self.assertNotIn('"ASR_RESOURCE"', self.panel)
        self.assertIn('realtimeDirty', self.panel)
        self.assertIn('"ASR_ADVANCED" -> ChatSelectionSheet(', self.panel)

    def test_android_regression_covers_conversation_while_reading_stays_unchanged(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceSettingsStructureActivityTest.kt').read_text()
        case = body(test, 'fun testConversationRecognitionEntryKeepsConfigurationAndReturnsToConversation()')
        for token in ['section("语音对话")', 'assertTextContains("语音对话")',
                      'node("settings-switch:豆包语音识别").assertDoesNotExist()',
                      'conversationConfiguration.value?.dictation == "DOUBAO"',
                      'conversationConfiguration.value?.dictation == "SYSTEM"',
                      'node("voice-save").assertIsEnabled().performClick()',
                      'assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)',
                      'assertEquals(0, fixture.opens.get())']:
            self.assertIn(token, case)


if __name__ == '__main__':
    unittest.main()
