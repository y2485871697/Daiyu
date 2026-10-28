"""Header-only source contracts; the action rows deliberately retain their original style."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHEET = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/components/SubAgentPresetActionsSheet.kt"


class SubAgentPresetActionsThemeContractTest(unittest.TestCase):
    def test_header_uses_a_compact_badge_and_glyph(self):
        source = SHEET.read_text()
        self.assertIn('testTag("subagent-preset-badge")', source)
        self.assertIn('Modifier.padding(8.dp).size(24.dp).testTag("subagent-preset-glyph")', source)
        self.assertNotIn('Modifier.padding(14.dp).size(28.dp)', source)

    def test_only_header_pairs_reference_secondary_background_with_primary_glyph(self):
        header = SHEET.read_text().split('            HorizontalDivider(', 1)[0]
        self.assertIn('val colors = conversationAgentColors()', header)
        self.assertIn('val palette = assistantPreviewPalette()', header)
        self.assertIn('shape = RoundedCornerShape(12.dp), color = colors.soft', header)
        self.assertIn('tint = palette.primary', header)
        self.assertNotIn('isSystemInDarkTheme', header)

    def test_rename_and_delete_keep_their_original_colors_and_plain_rows(self):
        actions = SHEET.read_text().split('            HorizontalDivider(', 1)[1]
        self.assertIn('"重命名组", Icons.Rounded.Edit, MaterialTheme.colorScheme.onSurface, onRename', actions)
        self.assertIn('"删除组", Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.error, onDelete', actions)
        self.assertIn('.heightIn(min = 64.dp)', actions)
        self.assertIn('.clickable(role = Role.Button', actions)
        self.assertIn('onClick()', actions)
        for unexpected in ('colors.soft', 'palette.primary', 'Surface(', '.background('):
            self.assertNotIn(unexpected, actions)


if __name__ == "__main__":
    unittest.main()
