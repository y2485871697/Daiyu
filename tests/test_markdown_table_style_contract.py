"""Table appearance wiring only; actual pixel regressions run in the Android batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/"


class MarkdownTableStyleContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.table = (ROOT / (BASE + "MessageMarkdown.kt")).read_text().split(
            "private fun MarkdownTable(", 1)[1].split("private fun headingSize", 1)[0]

    def test_grid_has_theme_content_contrast_and_one_dp_strokes(self):
        self.assertIn("val gridColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.28f)", self.table)
        self.assertIn("val gridWidth = 1.dp", self.table)
        self.assertIn("val stroke = gridWidth.toPx()", self.table)
        self.assertNotIn("outline.copy(alpha = 0.35f)", self.table)
        self.assertNotIn("0.5.dp", self.table)

    def test_same_rounded_frame_clips_before_horizontal_scroll(self):
        self.assertIn("val tableShape = RoundedCornerShape(8.dp)", self.table)
        border = self.table.index(".border(gridWidth, gridColor, tableShape)")
        clip = self.table.index(".clip(tableShape)")
        scroll = self.table.index(".horizontalScroll(rememberScrollState())")
        self.assertLess(border, clip)
        self.assertLess(clip, scroll)
        self.assertNotIn("clipToBounds()", self.table)
        self.assertIn("Modifier.widthIn(max = tableWidth).fillMaxWidth()", self.table)
        self.assertIn("widths.fold(0.dp)", self.table)

    def test_each_internal_boundary_is_drawn_once_and_preserves_rtl(self):
        self.assertIn("if (rowIndex > 0)", self.table)
        self.assertIn("if (column < widths.lastIndex)", self.table)
        self.assertIn("layoutDirection == LayoutDirection.Ltr", self.table)
        self.assertIn("size.width - consumed", self.table)
        self.assertEqual(2, self.table.count("drawLine(gridColor,"))
        self.assertNotIn("style = Stroke", self.table)

    def test_existing_width_reveal_and_grouping_stay_in_place(self):
        for token in ["table.header.size > 32", "indices.chunked(32)", "MarkdownText.tableColumnWidthEms(table.header)",
                      "style.fontSize.toDp()", "if (visibleCharacters <= offsets[rowIndex][0]) break", "visibleCharacters - offsets[rowIndex][column]",
                      "MarkdownInlineText(cell, openLink", "SelectionContainer"]:
            source = self.table if token != "SelectionContainer" else (ROOT / (BASE + "MessageMarkdown.kt")).read_text()
            self.assertIn(token, source)

    def test_android_pixel_regressions_cover_theme_corners_grid_and_scrolling(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownTableStyleTest.kt").read_text()
        for token in ["testGridAndFourRoundedCornersInLightDarkAndRtl", "testRoundedFrameStaysFixedWhenWideContentScrolls",
                      "AppearanceThemeMode.DARK", "AppearanceThemeMode.LIGHT", "LayoutDirection.Rtl", "captureToImage()",
                      "rounded corner", "horizontal separator", "vertical separator", "actually scrolled", "assertEquals(before"]:
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
