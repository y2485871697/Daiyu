"""Preset actions keep their plain rows without a decorative group header."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHEET = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/components/SubAgentPresetActionsSheet.kt"
SETTINGS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/SubAgentSettingsScreen.kt"


class SubAgentPresetActionsThemeContractTest(unittest.TestCase):
    def test_sheet_has_no_group_title_icon_or_header_divider(self):
        source = SHEET.read_text()
        for removed in ('Text(preset.name', 'subagent-preset-badge', 'subagent-preset-glyph',
                        'Icons.Rounded.AccountTree', 'HorizontalDivider(', 'assistantPreviewPalette'):
            self.assertNotIn(removed, source)

    def test_drag_handle_and_original_panel_semantics_remain(self):
        source = SHEET.read_text()
        self.assertIn('dragHandle = { BottomSheetDefaults.DragHandle() }', source)
        self.assertIn('contentDescription = "子代理组操作面板"', source)
        self.assertIn('onDismissRequest = onDismiss', source)
        self.assertIn('remember(preset.id, density)', source)

    def test_rename_and_delete_keep_their_original_colors_and_plain_rows(self):
        actions = SHEET.read_text()
        self.assertIn('"重命名组", Icons.Rounded.Edit, MaterialTheme.colorScheme.onSurface, onRename', actions)
        self.assertIn('"删除组", Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.error, onDelete', actions)
        self.assertIn('.heightIn(min = 64.dp)', actions)
        self.assertIn('.clickable(role = Role.Button', actions)
        self.assertIn('onClick()', actions)
        for unexpected in ('colors.soft', 'palette.primary', 'Surface(', '.background('):
            self.assertNotIn(unexpected, actions)

    def test_editor_omits_the_explanation_item_but_keeps_real_status_and_retry(self):
        source = SETTINGS.read_text()
        self.assertNotIn('配置属于此预设组；会话应用时复制，后续修改互不影响。', source)
        self.assertIn('val notice = when (state)', source)
        self.assertIn('else -> if (!editable) "当前子代理组暂不可修改。" else null', source)
        self.assertIn('if (notice != null) item {', source)
        self.assertIn('子代理配置读取或保存失败：${state.reason}', source)
        self.assertIn('正在读取子代理组配置…', source)
        self.assertIn('editor.retry()', source)


if __name__ == "__main__":
    unittest.main()
