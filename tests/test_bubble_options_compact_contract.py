"""Bubble choices stay compact and use the preview selection roles; pixels run in the UI batch."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
class BubbleOptionsCompactContractTest(unittest.TestCase):
    def group(self):
        sheet = (SRC / 'ui/screens/settings/AppearanceColorSheet.kt').read_text()
        self.assertIn('testTag("appearance-bubble-options")', sheet)
        return sheet.split('if (editor == AppearanceColorEditor.BUBBLES) {', 1)[1].split('if (draft.userBubbleMode == UserBubbleMode.CUSTOM)', 1)[0]
    def test_only_bubble_choices_have_compact_group_spacing(self):
        group = self.group()
        self.assertIn('verticalArrangement = Arrangement.spacedBy(4.dp)', group)
        self.assertIn('.heightIn(min = 48.dp)', group)
        self.assertNotIn('SettingsRadioRow(', group)
        self.assertNotIn('.height(48.dp)', group)
        global_rows = (SRC / 'ui/screens/settings/SettingsUiComponents.kt').read_text()
        self.assertIn('.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp)', global_rows)
    def test_selected_choice_uses_same_selection_background_and_content_as_preview(self):
        group = self.group()
        self.assertIn('UsagePalette(buttons.dark, preview)', group)
        self.assertIn('RoundedCornerShape(12.dp)', group)
        self.assertIn('if (selected) selection.selectionContainer else Color.Transparent', group)
        self.assertIn('if (selected) selection.selectionContent else buttons.text', group)
        self.assertIn('tint = selection.selectionContent', group)
    def test_entire_row_is_one_radio_target_and_changes_only_draft_mode(self):
        group = self.group()
        self.assertIn('.selectableGroup()', group)
        self.assertIn('.fillMaxWidth().heightIn(min = 48.dp)', group)
        self.assertIn('testTag("settings-radio:$label")', group)
        self.assertEqual(1, group.count('.selectable('))
        self.assertIn('role = Role.RadioButton', group)
        self.assertIn('draft = draft.copy(userBubbleMode = mode)', group)
        self.assertNotIn('onChange(', group)
    def test_preview_and_choices_use_the_model_picker_palette_including_defaults(self):
        sheet = (SRC / 'ui/screens/settings/AppearanceColorSheet.kt').read_text()
        preview = sheet.split('private fun AppearanceDraftPreview(', 1)[1].split('private fun AppearanceColorPicker(', 1)[0]
        self.assertIn('val selection = UsagePalette(dark, draft)', preview)
        self.assertIn('UsagePalette(buttons.dark, preview)', self.group())
        self.assertNotIn('ConversationPanePreviewPalette', preview)
        model = (SRC / 'ui/screens/chat/conversation/ChatModelPicker.kt').read_text()
        self.assertIn('val colors = usagePalette()', model)
        self.assertIn('val selectedBackground = colors.selectionContainer', model)
        self.assertIn('colors.selectionContent', model)
    def test_android_regression_covers_geometry_pixels_edge_clicks_and_cancel(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/AppearanceColorPickerActivityTest.kt').read_text()
        for token in ('testBubbleOptionsStayCompactAndMatchPreviewSelection', 'testBubbleOptionCancelAndDefaultDoNotSave',
                      'appearance-preview-selection', 'appearance-bubble-options', 'boundsInRoot',
                      'assertIsSelected()', 'assertIsNotSelected()', 'width - 2f', 'assertTrue(requests.isEmpty())'):
            self.assertIn(token, test)
if __name__ == '__main__': unittest.main()
