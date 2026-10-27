"""Stable HSV coordinates and non-self-tinting editor chrome."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'

class ColorPickerStabilityContractTest(unittest.TestCase):
    def sheet(self): return (SRC / 'AppearanceColorSheet.kt').read_text()

    def test_saturation_value_plane_never_rebuilds_hue_from_output_rgb(self):
        picker = self.sheet().split('private fun AppearanceColorPicker(', 1)[1]
        self.assertNotIn('val hsv = colorHsv(color)', picker)
        self.assertNotIn('grayHue', picker)
        self.assertIn('var selection by remember(label)', picker)
        self.assertIn('val synced = selection.synchronize(color)', picker)
        self.assertIn('Color.hsv(selection.hue, 1f, 1f)', picker)
        self.assertIn('Offset(selection.saturation * size.width', picker)

    def test_draft_changes_only_preview_not_editor_chrome(self):
        sheet = self.sheet()
        editor = sheet.split('internal fun AppearanceColorSheet(', 1)[1].split('private fun AppearanceDraftPreview', 1)[0]
        self.assertNotIn('LocalAppearanceSettings provides preview', sheet)
        self.assertNotIn('CompositionLocalProvider', editor)
        self.assertIn('AppearanceDraftPreview(preview)', editor)
        self.assertIn('if (saved) onDismiss()', editor)
        self.assertEqual(1, editor.count('onChange(editor.merge(state, draft))'))

    def test_hue_track_is_fixed_full_spectrum_not_a_theme_fill(self):
        picker = self.sheet().split('private fun AppearanceColorPicker(', 1)[1]
        self.assertIn('listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)', picker)
        self.assertIn('LayoutDirection.Rtl', picker)
        self.assertIn('Brush.horizontalGradient(trackColors)', picker)
        self.assertIn('activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent', picker)
        self.assertNotIn('DaiyuPalette.switchTrack', picker)

if __name__ == '__main__': unittest.main()
