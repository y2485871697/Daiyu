"""Voice pickers share model visuals, not model catalogs, brands or balances."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
DIALOGS = (SRC / 'VoiceSettingsDialogs.kt').read_text()
TONE = (SRC / 'VoiceTonePicker.kt').read_text()
SHARED = (SRC / 'ui/components/GroupedPickerComponents.kt').read_text()
MODEL = (SRC / 'ui/screens/chat/conversation/ChatModelPicker.kt').read_text()


class VoiceToneSelectionContractTest(unittest.TestCase):
    def test_same_search_and_continuous_group_surface_as_models(self):
        for text in (TONE, MODEL):
            self.assertIn('GroupedPickerSearch(', text)
            self.assertIn('.pickerGroupSurface(', text)
        self.assertIn('internal fun GroupedPickerSearch(', SHARED)
        self.assertIn('internal fun Modifier.pickerGroupSurface(', SHARED)
        self.assertIn('"搜索音色", "搜索音色"', TONE)
        self.assertIn('height = 500.dp', TONE)
        self.assertIn('showCloseButton = false, titleTextAlign = TextAlign.Start', TONE)
        self.assertNotIn('SettingsSectionTitle(section)', DIALOGS)

    def test_classification_and_card_heading_are_voice_specific(self):
        for name in ('女声', '男声', '个人音色', '其他音色'):
            self.assertIn(f'"{name}"', TONE)
        self.assertLess(TONE.index('id.contains("female"'), TONE.index('id.contains("male"'))
        self.assertIn('DoubaoVoiceIdentity.isPersonal(id)', TONE)
        self.assertIn('options.distinctBy { it.id }', TONE)
        self.assertIn('Text(category,', TONE)
        self.assertIn('textAlign = TextAlign.Start', TONE)
        self.assertIn('"${rows.size} 个音色"', TONE)
        self.assertIn('Text(voice.name,', TONE)
        self.assertIn('"没有找到匹配的音色"', TONE)

    def test_category_collapse_and_search_do_not_write_selection(self):
        for fragment in ('var query by remember', 'var expanded by remember(selectedGroup)',
                         'var searchCollapsed by remember(query)', 'if (open) itemsIndexed(rows',
                         'it.name.contains(search, true)', 'it.id.contains(search, true)',
                         'it.category.contains(search, true)', 'LaunchedEffect(search) { listState.scrollToItem(0) }'):
            self.assertIn(fragment, TONE)
        self.assertNotIn('var selected', TONE)
        self.assertNotIn('mutableStateOf(selectedId)', TONE)
        self.assertEqual(1, TONE.count('onSelect(voice.id)'))
        for forbidden in ('Balance', 'balance', 'ModelBrandIcon', 'ChatModelPickerState',
                          'ModelCatalogEntryUi', 'onDismiss()', 'rememberBottomSheetAction', '.save(', '.create(', 'preview.play'):
            self.assertNotIn(forbidden, TONE)

    def test_single_selection_uses_raw_id_and_retains_owner_callbacks(self):
        self.assertIn('val selected = voice.id == selectedId', TONE)
        self.assertIn('onClick = { onSelect(voice.id) }', TONE)
        self.assertIn('enabled = voice.enabled', TONE)
        self.assertIn('Role.RadioButton', TONE)
        self.assertIn('VoiceToneOption(id, label)', DIALOGS)
        panel = (SRC / 'VoiceSettingsPanel.kt').read_text()
        self.assertIn('VoiceToneDialog("音色", draft.voice', panel)
        self.assertIn('VoiceToneDialog("选择音色", draft.duplexVoice', panel)
        self.assertIn('save(draft.copy(voice = it), speechOnly = true)', panel)
        self.assertIn('save(realtimeConfiguration().copy(duplexVoice = it))', panel)

    def test_rows_keep_reference_highlight_check_and_stable_ids(self):
        for fragment in ('heightIn(min = 56.dp)', 'RoundedCornerShape(12.dp)', 'colors.selectionContainer',
                         'colors.selectionContent', 'Modifier.size(22.dp)', 'CircleShape',
                         'Icon(DaiyuIcons.Check, "已选择", Modifier.size(14.dp), tint = colors.onPrimary)',
                         'testTag("voice-tone-indicator:${voice.id}")', 'key = { _, voice -> "voice:${voice.id}" }',
                         'val tag: String = "voice-tone-option:$id"'):
            self.assertIn(fragment, TONE)


if __name__ == '__main__':
    unittest.main()
