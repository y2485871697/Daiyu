"""Keep the settings compression icon vertically convergent, as in the original UI."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
ICONS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/icons/DaiyuIcons.kt"
SETTINGS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings"


class SettingsCompressionIconContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = ICONS.read_text()
        cls.icon = source.split("    val Compress: ImageVector by lazy {", 1)[1].split("    val Id:", 1)[0]

    def test_arrows_converge_vertically_on_two_horizontal_bars(self):
        commands = re.findall(r"(moveTo|lineTo)\((\d+(?:\.\d+)?)f, (\d+(?:\.\d+)?)f\)", self.icon)
        subpaths = []
        for operation, x, y in commands:
            if operation == "moveTo":
                subpaths.append([])
            subpaths[-1].append((float(x), float(y)))
        self.assertEqual([
            [(4, 10), (20, 10)],
            [(4, 14), (20, 14)],
            [(12, 2), (12, 7)],
            [(9, 4), (12, 7), (15, 4)],
            [(12, 22), (12, 17)],
            [(9, 20), (12, 17), (15, 20)],
        ], subpaths)

    def test_existing_vector_frame_and_outline_style_are_preserved(self):
        self.assertIn('name = "Daiyu.Compress"', self.icon)
        for value in ("defaultWidth = 24.dp", "defaultHeight = 24.dp", "viewportWidth = 24f", "viewportHeight = 24f",
                      "strokeLineWidth = 1.75f", "strokeLineCap = StrokeCap.Round", "strokeLineJoin = StrokeJoin.Round", "fill = null"):
            self.assertIn(value, self.icon)

    def test_settings_entry_uses_the_icon_without_changing_its_route_or_display_size(self):
        catalog = (SETTINGS / "SettingsCatalog.kt").read_text()
        self.assertRegex(catalog, r'SettingsEntry\("compression", "上下文压缩", DaiyuIcons\.Compress, SettingControl\.PAGE, FeatureRoute\.COMPRESSION_SETTINGS[,)]')
        home = (SETTINGS / "SettingsHomeScreen.kt").read_text()
        self.assertIn("DaiyuIcon(icon, contentDescription = null, tint = communityBrandIconColor(icon) ?: daiyuIconColor(icon), size = 23.dp)", home)


if __name__ == "__main__":
    unittest.main()
