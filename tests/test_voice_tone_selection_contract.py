"""Tone-specific visuals; device rendering is covered by the Android fixture."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
DIALOGS = (SRC / 'VoiceSettingsDialogs.kt').read_text()
TONE = DIALOGS.split('internal fun VoiceToneDialog(', 1)[1]
SHEET = (SRC / 'ui/screens/chat/conversation/ChatSelectionSheet.kt').read_text()


class VoiceToneSelectionContractTest(unittest.TestCase):
    def test_title_alignment_is_explicit_and_only_opted_in_by_tones(self):
        self.assertIn('titleTextAlign: TextAlign? = null', SHEET)
        self.assertIn('textAlign = titleTextAlign', SHEET)
        self.assertIn('titleTextAlign = TextAlign.Start', TONE)
        self.assertNotIn('titleTextAlign', DIALOGS.split('internal fun VoiceToneDialog(', 1)[0])
        self.assertIn('showCloseButton = false', TONE)
        self.assertIn('titleFontSize = 20.sp', TONE)

    def test_rows_match_the_blur_reference_and_keep_radio_semantics(self):
        for fragment in ('usagePalette()', '.selectableGroup()', 'RoundedCornerShape(12.dp)',
                         'heightIn(min = 56.dp)', 'sheetColors.selectionContainer',
                         'sheetColors.selectionContent', 'sheetColors.text',
                         'Role.RadioButton', 'testTag("settings-radio:$label")',
                         'padding(horizontal = 12.dp, vertical = 14.dp)',
                         'fontSize = 16.sp, lineHeight = 24.sp'):
            self.assertIn(fragment, TONE)
        self.assertNotIn('SettingsRadioRow(', TONE)

    def test_right_indicator_uses_theme_circle_and_contrasting_check(self):
        for fragment in ('Modifier.size(22.dp)', 'sheetColors.accent else Color.Transparent, CircleShape',
                         'if (selected) DaiyuIcon(DaiyuIcons.Check, "已选择", tint = sheetColors.onPrimary, size = 14.dp)',
                         'testTag("voice-tone-indicator:$id")'):
            self.assertIn(fragment, TONE)

    def test_selection_still_uses_saved_id_without_closing_or_local_draft(self):
        self.assertIn('val selected = id == initial', TONE)
        self.assertIn('onClick = { onSelect(id) }', TONE)
        for fragment in ('onDismiss()', 'rememberBottomSheetAction', 'mutableStateOf', 'onSelect(label)'):
            self.assertNotIn(fragment, TONE)
        panel = (SRC / 'VoiceSettingsPanel.kt').read_text()
        self.assertIn('VoiceToneDialog("音色", draft.voice', panel)
        self.assertIn('VoiceToneDialog("选择音色", draft.duplexVoice', panel)
        self.assertIn('save(draft.copy(voice = it), speechOnly = true)', panel)
        self.assertIn('save(realtimeConfiguration().copy(duplexVoice = it))', panel)

    def test_groups_deduplication_and_scroll_are_preserved(self):
        self.assertIn('choices.distinctBy { it.first }', TONE)
        self.assertLess(TONE.index('"female" in id'), TONE.index('"male" in id'))
        self.assertIn('listOf("女声", "男声", "", "个人声音")', TONE)
        self.assertIn('heightIn(max = 480.dp).verticalScroll(rememberScrollState())', TONE)
        self.assertIn('SettingsSectionTitle(section)', TONE)


if __name__ == '__main__':
    unittest.main()
