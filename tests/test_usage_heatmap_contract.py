"""Source checks complement the device heatmap geometry/pixel regressions."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCREEN = Path('app/src/main/kotlin/app/daiyu/android/ui/screens/stats/UsageStatsScreen.kt')


class UsageHeatmapContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = (ROOT / SCREEN).read_text()
        cls.chart = source.split('private fun ChatHeatmap(', 1)[1].split('@Composable\nprivate fun HeatmapCell', 1)[0]
        cls.cell = source.split('private fun HeatmapCell(', 1)[1].split('@Composable', 1)[0]
        cls.device_tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/UsagePresentationActivityTest.kt').read_text()

    def test_unknown_is_a_real_square_not_a_transparent_dash(self):
        self.assertIn('unknown -> colors.bubble', self.cell)
        self.assertNotIn('Text(', self.cell)
        self.assertNotIn('Color.Transparent', self.cell)
        self.assertIn('.size(sizeDp.dp)', self.cell)
        self.assertIn('RoundedCornerShape(2.dp)', self.cell)

    def test_unknown_has_an_outline_and_one_localized_chart_state(self):
        self.assertIn('if (unknown) Modifier.border(0.5.dp, colors.line,', self.cell)
        self.assertEqual(1, self.chart.count('stateDescription = unavailableDescription'))
        self.assertIn('if (!available) stateDescription', self.chart)
        self.assertNotIn('semantics', self.cell)
        for values in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            strings = ET.parse(ROOT / f'app/src/main/res/{values}/preserved_ui.xml')
            self.assertTrue(strings.find(".//string[@name='stats_page_heatmap_unavailable']").text)

    def test_original_calendar_geometry_and_latest_week_scroll_are_retained(self):
        for code in ('minusWeeks(52)', 'val numWeeks = 53', 'val cellSize = 11.dp',
                     'val cellSpacing = 2.dp', 'for (dow in 0..6)',
                     'rememberScrollState(initial = Int.MAX_VALUE)', '.horizontalScroll(scrollState)',
                     'stats_page_dow_mon', 'stats_page_dow_wed', 'stats_page_dow_fri',
                     'LocalConfiguration.current.locales[0]'):
            self.assertIn(code, self.chart)

    def test_availability_and_future_and_known_heat_colors_are_not_faked(self):
        self.assertIn('unknown = !available && !isFuture', self.chart)
        self.assertIn('heatmapAlpha(count, q1, q2, q3, isFuture)', self.chart)
        self.assertIn('alpha < 0f -> colors.bubble.copy(alpha = 0.3f)', self.cell)
        self.assertIn('alpha == 0f -> colors.bubble', self.cell)
        self.assertIn('else -> colors.accent.copy(alpha = alpha)', self.cell)
        self.assertNotIn('available = true', self.chart)

    def test_device_regressions_cover_unknown_geometry_and_actual_heat_pixels(self):
        for code in ('fun testUnavailableHeatmapKeepsSquareGridInsteadOfRowsOfDashesAcrossThemesAndScales',
                     'fun testAvailableHeatmapPreservesZeroAndFourHeatLevelsWithoutUnknownState',
                     'assertCountEquals(53 * 7)', 'captureToImage().toPixelMap()',
                     'bounds.width, bounds.height', '5, colors.toSet().size',
                     'SemanticsProperties.StateDescription', 'listOf(false, true)', 'listOf(1f, .8f)',
                     'import androidx.compose.ui.semantics.getOrNull',
                     'today.minusDays(6) to .25f', 'today.minusDays(4) to .5f',
                     'today.minusDays(2) to .75f', 'compositeOver(pixels[x, gapY])'):
            self.assertIn(code, self.device_tests)


if __name__ == '__main__':
    unittest.main()
