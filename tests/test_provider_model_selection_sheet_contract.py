"""Source wiring guards only; Android tests own rendered geometry and interactions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
DIALOG = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings/ProviderModelSelectionDialog.kt'


class ProviderModelSelectionSheetContractTest(unittest.TestCase):
    def setUp(self):
        self.text = DIALOG.read_text()

    def section(self, start, end):
        self.assertIn(start, self.text)
        remainder = self.text.split(start, 1)[1]
        self.assertIn(end, remainder)
        return remainder.split(end, 1)[0]

    def test_reuses_new_model_sheet_with_fixed_footer(self):
        self.assertIn('ChatSelectionSheet(title = "选择要添加的模型"', self.text)
        self.assertIn('height = 500.dp, showCloseButton = false', self.text)
        footer = self.text.split('footer = {', 1)[1].split('Column(Modifier.fillMaxSize()', 1)[0]
        for fragment in ('MiuixDialogActions(', 'onCancel = dismiss',
                         'onConfirm = { if (editable && selection.canConfirm) onConfirm(selection) }',
                         'cancelEnabled = !isSaving', 'confirmEnabled = editable && selection.canConfirm'):
            self.assertIn(fragment, footer)
        self.assertNotIn('WindowDialog', self.text)
        self.assertIn('LazyColumn(Modifier.fillMaxWidth().weight(1f)', self.text)

    def test_search_uses_reference_style_and_real_selection_query(self):
        search = self.section('BasicTextField(', 'testTag("provider-model-bulk-actions")')
        for fragment in ('value = selection.query', 'selection.withQuery(it)', 'enabled = editable',
                         'Modifier.managedInput()', 'testTag("provider-model-search")',
                         'contentDescription = "搜索模型"', 'RoundedCornerShape(14.dp)',
                         'heightIn(min = 48.dp)', 'DaiyuIcons.Search',
                         'sheetColors.background', 'sheetColors.accent else sheetColors.line'):
            self.assertIn(fragment, search)

    def test_bulk_actions_are_local_grey_text_buttons(self):
        self.assertIn('import app.daiyu.android.ui.haptics.material.TextButton as BulkTextButton', self.text)
        palette = self.text.split('val bulkColors = ButtonDefaults.textButtonColors(', 1)[1].split('ChatSelectionSheet(', 1)[0]
        self.assertIn('contentColor = colors.onSurfaceVariantSummary', palette)
        self.assertNotIn('containerColor', palette)
        toolbar = self.section('testTag("provider-model-bulk-actions")', 'if (selection.visibleCount == 0)')
        self.assertEqual(2, toolbar.count('BulkTextButton('))
        self.assertEqual(2, toolbar.count('colors = bulkColors'))
        self.assertEqual(2, toolbar.count('enabled = editable && selection.visibleCount > 0'))
        self.assertNotIn('.background(', toolbar)
        self.assertNotIn('ProviderActionButton', toolbar)

    def test_original_count_stays_right_of_both_actions_on_the_same_row(self):
        toolbar = self.section('testTag("provider-model-bulk-actions")', 'if (selection.visibleCount == 0)')
        tags = ['provider-model-select-all', 'provider-model-invert', 'Spacer(Modifier.weight(1f))', 'provider-model-count']
        offsets = [toolbar.index(tag) for tag in tags]
        self.assertEqual(sorted(offsets), offsets)
        self.assertIn('"${selection.selectedCount}/${selection.totalCount}"', toolbar)
        self.assertIn('textAlign = TextAlign.End', toolbar)
        self.assertIn('modifier = Modifier.testTag("provider-model-count")', toolbar)
        self.assertNotIn('Column(', toolbar)
        self.assertNotIn('FlowRow(', toolbar)

    def test_saving_blocks_dismissal_before_exit_animation_starts(self):
        self.assertIn('dismissEnabled = !isSaving', self.text)
        sheet = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatSelectionSheet.kt').read_text()
        self.assertIn('dismissEnabled: Boolean = true', sheet)
        self.assertIn('PreviewDialog(onDismiss, bottomSheet = true, dismissEnabled = dismissEnabled)', sheet)
        self.assertIn('.clickable(enabled = dismissEnabled', sheet)
        preview = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/components/PreviewDialog.kt').read_text()
        for fragment in ('dismissEnabled: Boolean = true',
                         'if (dismissEnabled) motion?.requestDismiss(BottomSheetDismissSource.System)',
                         'if (dismissEnabled) motion?.requestDismiss(BottomSheetDismissSource.Outside)',
                         'dismissOnBackPress = dismissEnabled', 'dismissOnClickOutside = dismissEnabled',
                         '.clickable(enabled = dismissEnabled'):
            self.assertIn(fragment, preview)

    def test_multi_select_and_host_guards_are_preserved(self):
        for fragment in ('val editable = enabled && !isSaving', 'val dismiss = { if (!isSaving) onDismiss() }',
                         'selection.selectAllVisible()', 'selection.invertVisible()',
                         'items(selection.visibleModels, key = { it.id })', 'model.id in selection.selectedIds',
                         'Role.Checkbox', 'selection.toggle(model.id)', 'onCheckedChange = null',
                         'testTag("provider-model-empty")', 'SettingsNote(errorText, error = true)'):
            self.assertIn(fragment, self.text)
        self.assertNotIn('Role.RadioButton', self.text)


if __name__ == '__main__':
    unittest.main()
