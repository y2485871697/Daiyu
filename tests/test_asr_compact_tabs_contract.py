"""ASR-local width cap must not change workspace tabs or recognition settings."""
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


class AsrCompactTabsContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / 'VoiceSettingsPanel.kt').read_text()
        cls.asr = body(cls.panel, 'if (child == "ASR")')

    def test_asr_controls_are_centered_inside_safe_side_margins(self):
        self.assertIn('Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)', self.asr)
        scoped = body(self.asr, 'Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)')
        self.assertIn('contentAlignment = Alignment.Center', self.asr)
        self.assertIn('LinuxSettingsTheme { SettingsSegmentedTabs(', scoped)
        self.assertNotIn('voice-asr-account-card', scoped)

    def test_width_cap_is_local_flexible_and_keeps_vertical_spacing(self):
        self.assertIn('Modifier.widthIn(max = 300.dp).padding(vertical = 8.dp)', self.asr)
        self.assertEqual(1, self.panel.count('widthIn(max = 300.dp)'))
        self.assertNotIn('.width(300.dp)', self.asr)
        self.assertIn('testTag("voice-asr-method-card")', self.asr)
        shared = (SRC / 'ui/screens/settings/SettingsSegmentedTabs.kt').read_text()
        workspace = (SRC / 'ui/screens/settings/LinuxSettingsNavigation.kt').read_text()
        for text in [shared, workspace]:
            self.assertNotIn('widthIn(max = 300.dp)', text)
        self.assertIn('Row(modifier.fillMaxWidth()', shared)
        self.assertIn('.heightIn(min = 48.dp)', shared)
        self.assertIn('fontSize = 14.sp', shared)

    def test_labels_color_scope_and_profile_save_guards_are_preserved(self):
        for token in ['labels = listOf("本机识别", "豆包识别")',
                      'selectedIndex = if (draft.dictation == "DOUBAO") 1 else 0',
                      'enabled = !busy', 'if (current() && !busy)',
                      'save((saved ?: VoiceConfiguration()).copy(dictation = method))']:
            self.assertIn(token, self.asr)
        self.assertNotIn('Color(', self.asr)
        self.assertNotIn('fontSize', self.asr)
        self.assertIn('val profile = if (section == "CONVERSATION") VoiceProfile.CONVERSATION else VoiceProfile.READ_ALOUD', self.panel)

    def test_real_reading_and_conversation_regressions_verify_compact_geometry(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceSettingsStructureActivityTest.kt').read_text()
        helper = body(text, 'private fun assertRecognitionSegmentsAreCompact()')
        for token in ['node("page:语音设置").getUnclippedBoundsInRoot()',
                      'node("voice-asr-method-card").getUnclippedBoundsInRoot()',
                      'val pageWidth = (page.right - page.left).value',
                      'val groupWidth = (group.right - group.left).value',
                      'minOf(300f, pageWidth - 32f)',
                      'page.left.value + page.right.value',
                      'group.left.value + group.right.value', '.assertHeightIsAtLeast(48.dp)',
                      'assertEquals(local.width, cloud.width, 1f)']:
            self.assertIn(token, helper)
        for name in ['testRecognitionSegmentsPersistSelectionAndKeepOtherProfiles',
                     'testConversationRecognitionEntryKeepsConfigurationAndReturnsToConversation']:
            self.assertIn('assertRecognitionSegmentsAreCompact()', body(text, f'fun {name}()'))


if __name__ == '__main__':
    unittest.main()
