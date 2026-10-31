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
        self.assertIn('unknown -> colors.heatmapEmpty', self.cell)
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
        self.assertIn('alpha == 0f -> colors.heatmapEmpty', self.cell)
        self.assertIn('else -> colors.accent.copy(alpha = alpha)', self.cell)
        self.assertNotIn('available = true', self.chart)

    def test_empty_shade_is_local_to_heatmap_and_not_the_card_background(self):
        palette = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/components/UsagePresentation.kt').read_text()
        self.assertIn('val heatmapEmpty = Color(if (dark) 0xff45414c else 0xffe0dde5)', palette)
        self.assertIn('val bubble = Color(if (dark) 0xff28262f else 0xfff1eff3)', palette)
        self.assertIn('.background(colors.bubble.copy(alpha = .62f))', palette)
        self.assertIn('alpha < 0f -> colors.bubble.copy(alpha = 0.3f)', self.cell)
        self.assertEqual(2, self.cell.count('colors.heatmapEmpty'))

    def test_two_historical_coverage_footers_are_removed_without_replacing_data(self):
        screen = (ROOT / SCREEN).read_text()
        for removed in ('usage-model-coverage', 'usage-accounting-coverage',
                        '仅含已知模型及日期的请求', '个旧会话缺少创建日期'):
            self.assertNotIn(removed, screen)
        for retained in ('stats.overviewTokens', 'stats.modelTokens(filtered)', 'usage.filtered(',
                         'filtered.providers.forEach', 'ChatHeatmap(conversationsPerDay = stats.conversationsPerDay, available = stats.heatmapAvailable)'):
            self.assertIn(retained, screen)
        self.assertIn('fun testHistoricalCoverageFootersAreAbsentAndKnownTotalsStayUnchanged', self.device_tests)
        self.assertIn('unknownConversationDates = 5, unattributedUsageRequests = 42, accountingFailure = true', self.device_tests)
        self.assertIn('assertEquals(tokens, stats.overviewTokens)', self.device_tests)
        self.assertIn('assertEquals(400L, stats.modelTokens(stats.modelUsage).total)', self.device_tests)

    def test_pixel_regression_compares_cells_with_actual_card_gap(self):
        for code in ('private fun assertHeatmapCellContrastsWithCard',
                     'Empty heatmap cells must remain visible against the actual card',
                     'contrast >= .07f', 'val backing = pixels[gapX, y]',
                     'assertHeatmapCellContrastsWithCard(today)',
                     'assertHeatmapCellContrastsWithCard(today.minusDays(8))'):
            self.assertIn(code, self.device_tests)

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
