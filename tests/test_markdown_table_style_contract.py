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
        self.assertIn("markdownTableGridStrokePx(gridWidth.toPx())", self.table)
        self.assertIn("gridStroke.toDp()", self.table)
        self.assertNotIn("outline.copy(alpha = 0.35f)", self.table)
        self.assertNotIn("0.5.dp", self.table)

    def test_same_rounded_frame_clips_before_horizontal_scroll(self):
        self.assertIn("val tableShape = RoundedCornerShape(8.dp)", self.table)
        border = self.table.index(".border(gridWidth, gridColor, tableShape)")
        clip = self.table.index(".clip(tableShape)")
        scroll = self.table.index(".horizontalScroll(rememberScrollState())")
        self.assertLess(border, clip)
        inset = self.table.index(".padding(gridInset)")
        inner = self.table.index(".clip(innerShape)")
        self.assertLess(clip, inset)
        self.assertLess(inset, inner)
        self.assertLess(inner, scroll)
        self.assertNotIn("clipToBounds()", self.table)
        self.assertIn("Modifier.widthIn(max = tableWidth).fillMaxWidth()", self.table)
        self.assertIn("widths.fold(0.dp)", self.table)

    def test_grid_uses_cached_disjoint_pixel_fills_not_crossing_strokes(self):
        self.assertIn("Row(Modifier.drawWithCache", self.table)
        self.assertIn("markdownTableGridRects(widths.map { it.roundToPx() }", self.table)
        self.assertIn("drawTop = rowIndex > 0", self.table)
        self.assertIn("layoutDirection == LayoutDirection.Rtl", self.table)
        self.assertIn("drawRect(gridColor,", self.table)
        self.assertNotIn("drawLine(gridColor,", self.table)
        grid = (ROOT / (BASE + "MarkdownTableGrid.kt")).read_text()
        for token in ["ceil(widthPx)", "val top = if (drawTop)", "left, top, left + stroke, height", "width - start - stroke"]:
            self.assertIn(token, grid)

    def test_existing_width_reveal_and_grouping_stay_in_place(self):
        for token in ["table.header.size > 32", "indices.chunked(32)", "MarkdownText.tableColumnWidthEms(table.header)",
                      "style.fontSize.toDp()", "derivedStateOf { visibility.value > offsets[0] }", "rememberMarkdownVisibility(visibility, offsets[column], lengths[column])",
                      "MarkdownInlineText(cell, openLink", "SelectionContainer"]:
            source = self.table if token != "SelectionContainer" else (ROOT / (BASE + "MessageMarkdown.kt")).read_text()
            self.assertIn(token, source)

    def test_android_pixel_regressions_cover_theme_corners_grid_and_scrolling(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownTableStyleTest.kt").read_text()
        for token in ["testGridAndFourRoundedCornersInLightDarkAndRtl", "testRoundedFrameStaysFixedWhenWideContentScrolls",
                      "AppearanceThemeMode.DARK", "AppearanceThemeMode.LIGHT", "LayoutDirection.Rtl", "captureToImage()",
                      "rounded corner", "horizontal separator", "vertical separator", "actually scrolled", "assertEquals(before",
                      "testSinglePaintAndEqualPixelWidthsSurviveDensityRtlScrollAndAppend", "intersection", "frame joint",
                      "vertical physical width", "horizontal physical width", "middle scroll", "appended"]:
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
