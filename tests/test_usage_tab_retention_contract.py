"""Tab-local rendering must not own the asynchronous model statistics projection."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/stats/UsageStatsScreen.kt'


class UsageTabRetentionContractTest(unittest.TestCase):
    def test_model_projection_owner_is_independent_of_the_visible_tab(self):
        screen = SCREEN.read_text()
        owner = screen.split('val modelContent = ', 1)[1].split('SettingsScaffoldPage(', 1)[0]
        self.assertIn('if (!stats.isLoading && !loadFailed)', owner)
        self.assertIn('modelUsageContent(stats) else null', owner)
        self.assertNotIn('selectedTab', owner)
        self.assertIn('else if (selectedTab == UsageStatsTab.Models) {\n            modelContent?.invoke(this)', screen)
        model = screen.split('private fun modelUsageContent(', 1)[1].split('private fun ModelUsageGroupPart', 1)[0]
        for state in ('expandedModels', 'startBound', 'endBound'):
            self.assertIn('var ' + state + ' by remember', model)
        self.assertIn('produceState<Pair<ModelUsageSnapshot, UsageTokenMetrics>?>(null,', model)
        self.assertIn('withContext(Dispatchers.Default)', model)
        self.assertIn('usage-model-loading', model)

    def test_tab_selection_does_not_reload_the_statistics_snapshot(self):
        screen = SCREEN.read_text()
        load = screen.split('LaunchedEffect(loadAttempt)', 1)[1].split('val modelContent = ', 1)[0]
        self.assertIn('withContext(Dispatchers.IO)', load)
        self.assertIn('Result.success(load())', load)
        self.assertNotIn('selectedTab', load)
        self.assertIn('onSelect = { selectedTab = it }', screen)


if __name__ == '__main__':
    unittest.main()
