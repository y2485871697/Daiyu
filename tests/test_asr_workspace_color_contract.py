"""Recognition must use the workspace-local color source, not just the same widget."""
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


class AsrWorkspaceColorContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / "VoiceSettingsPanel.kt").read_text()
        cls.asr = body(cls.panel, 'if (child == "ASR")')

    def test_only_recognition_segments_use_the_workspace_local_theme(self):
        self.assertIn('LinuxSettingsTheme {', self.asr)
        scoped = body(self.asr, 'LinuxSettingsTheme {')
        self.assertEqual(1, self.panel.count('LinuxSettingsTheme {'))
        self.assertIn('SettingsSegmentedTabs(', scoped)
        self.assertIn('labels = listOf("本机识别", "豆包识别")', scoped)
        self.assertIn('enabled = !busy', scoped)
        self.assertNotIn('voice-asr-account-card', scoped)
        self.assertNotIn('SettingsCard(', scoped)
        self.assertNotIn('TextButton(', scoped)
        self.assertNotIn('Color(', scoped)
        self.assertIn('SettingsCard(Modifier.testTag("voice-asr-account-card"))', self.asr)

    def test_workspace_theme_still_owns_both_theme_color_pairs(self):
        settings = SRC / 'ui/screens/settings'
        theme = (settings / 'LinuxSettingsTheme.kt').read_text()
        self.assertIn('val primary = DaiyuPalette.switchTrack', theme)
        self.assertIn('val onPrimary = Color.White', theme)
        self.assertIn('val dark = LocalAppearanceDark.current', theme)
        for token in ['0xff302a36 else 0xffeee9f1', '0xffc6b7d5 else 0xff756980',
                      'primaryContainer = container, onPrimaryContainer = onContainer']:
            self.assertIn(token, theme)
        screen = (settings / 'LinuxSettingsScreen.kt').read_text()
        self.assertIn(') = LinuxSettingsTheme {', screen)

    def test_method_save_and_busy_owner_guards_are_unchanged(self):
        tabs = self.asr.split('SettingsSegmentedTabs(', 1)[1].split('if (draft.dictation == "DOUBAO") {', 1)[0]
        for token in ['selectedIndex = if (draft.dictation == "DOUBAO") 1 else 0',
                      'if (current() && !busy)', 'changeDraft(draft.copy(dictation = method))',
                      'save((saved ?: VoiceConfiguration()).copy(dictation = method))']:
            self.assertIn(token, tabs)
        self.assertNotIn('save(draft', tabs)

    def test_real_recognition_selection_test_checks_fill_and_white_text(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceSettingsStructureActivityTest.kt').read_text()
        helper = body(test, 'private fun assertRecognitionSelectedColors(')
        for token in ['node(tag).captureToImage().toPixelMap()',
                      'pixel.matches(Color(0xff82728f))', 'pixel.matches(Color.White)',
                      'backgroundCount > pixels.width * pixels.height * .04f', 'foregroundCount > 8']:
            self.assertIn(token, helper)
        case = body(test, 'fun testRecognitionSegmentsPersistSelectionAndKeepOtherProfiles()')
        self.assertIn('assertRecognitionSelectedColors("voice-asr-method:system")', case)
        self.assertIn('assertRecognitionSelectedColors("voice-asr-method:doubao")', case)
        self.assertIn('assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)', case)


if __name__ == '__main__':
    unittest.main()
