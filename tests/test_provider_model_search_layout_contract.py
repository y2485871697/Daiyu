"""Source-order guards only; Android rendering remains part of the UI batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'
SEARCH = 'SettingsSearch(query, { query = it }, "搜索模型", "local-model-search")'


class ProviderModelSearchLayoutContractTest(unittest.TestCase):
    def test_catalog_search_heads_the_model_list_after_its_title(self):
        source = (UI / 'LocalCatalogModelsScreen.kt').read_text()
        layout = source.split('        headerContent()', 1)[1]
        title = 'SettingsSectionTitle("模型列表（共 ${snapshot?.models?.size ?: 0} 个）")'
        self.assertEqual(1, layout.count(SEARCH))
        self.assertLess(layout.index('ProviderModelManagementCard('), layout.index(title))
        self.assertLess(layout.index(title), layout.index(SEARCH))
        self.assertLess(layout.index(SEARCH), layout.index('if (loaded && snapshot?.models.isNullOrEmpty())'))
        self.assertLess(layout.index(SEARCH), layout.index('item("local-model:${model.id}")'))
        self.assertIn('query.isBlank() || it.id.contains(query.trim(), true) || it.displayName.contains(query.trim(), true)', layout)

    def test_empty_provider_keeps_the_same_title_search_notice_order(self):
        source = (UI / 'ProviderModelsComponents.kt').read_text()
        layout = source.split('internal fun ProviderEmptyModelsPage(', 1)[1]
        title = 'SettingsSectionTitle("模型列表（共 0 个）")'
        self.assertEqual(1, layout.count(SEARCH))
        self.assertLess(layout.index('ProviderModelManagementCard('), layout.index(title))
        self.assertLess(layout.index(title), layout.index(SEARCH))
        self.assertLess(layout.index(SEARCH), layout.index('SettingsNote(notice)'))
        self.assertIn('var query by queryState', layout)
        self.assertIn('listState = listState', layout)


if __name__ == '__main__':
    unittest.main()
