"""Entry wiring guards; JVM owns transactions and Android owns interaction/geometry."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
UI = MAIN / 'ui/screens/settings'
class ModelBulkSelectionContract(unittest.TestCase):
    def test_both_pages_invert_only_filtered_ids_and_preserve_hidden_ids(self):
        provider = (UI/'ProviderDirectoryPage.kt').read_text()
        local = (UI/'LocalCatalogModelsScreen.kt').read_text()
        for source, formula, tag in ((provider, '(selectedSlots - visibleIds) + (visibleIds - selectedSlots)', 'provider-invert-selection'),
                                    (local, '(selection - visibleIds) + (visibleIds - selection)', 'local-model-invert-selection')):
            self.assertIn(formula, source); self.assertIn(tag, source)
            self.assertIn('visibleIds.isNotEmpty()', source)
    def test_inline_long_press_selection_footer_and_lifecycle(self):
        s = (UI/'LocalCatalogModelsScreen.kt').read_text()
        for f in ('onLongClickLabel = "选择模型"', 'selectedIds = setOf(model.id)', 'Role.Checkbox', 'ToggleableState.On',
                  'local-model-select-all', 'local-model-delete-selected', 'local-model-cancel-selection',
                  'WidePageContent(fillHeight = false)', 'deletionButtonColors()', 'accents.secondaryContainer',
                  'previewEnabled = backPreviewEnabled && selectedIds == null', 'deletingSelected !== selections',
                  'selectedIds === selection', 'selections.all(::matches)'):
            self.assertIn(f,s)
        for start,end in (('fun pause()', 'fun reload()'), ('fun reload()', 'fun matches('), ('fun cancel()', 'fun pause()')):
            section=s.split(start,1)[1].split(end,1)[0]
            self.assertIn('selectedIds = null', section); self.assertIn('deletingSelected = null',section)
    def test_settings_button_is_sibling_of_full_content_column(self):
        s=(UI/'LocalCatalogModelsScreen.kt').read_text()
        self.assertIn('val ticket = snapshot?.selectionFor(model.id)',s)
        card=s.split('val ticket = snapshot?.selectionFor(model.id)',1)[1]
        self.assertIn('Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically)',card)
        self.assertIn('Column(Modifier.weight(1f))',card)
        self.assertLess(card.index('FlowRow('),card.index('testTag("local-model-rename:'))
        self.assertIn('if (selection != null)',card)
    def test_production_entry_reaches_one_atomic_batch_transaction(self):
        for file, entry in (('ProviderManagementRoute.kt','controller.deleteProviderCatalogModels'),):
            self.assertIn(entry,(UI/file).read_text())
        self.assertIn('controller.deleteConfiguredCatalogModels',(MAIN/'MainActivity.kt').read_text())
        controller=(MAIN/'ChatController.kt').read_text()
        self.assertIn('it.deleteCatalogModels(tickets, cancelled)',controller)
        store=(MAIN/'storage/ModelSettings.kt').read_text()
        self.assertIn('internal fun deleteCatalogModels(',store)
        batch=store.split('internal fun deleteCatalogModels(',1)[1].split('internal fun nonSecretConfiguration',1)[0]
        self.assertIn('modelCatalog.deleteSelections(selections',batch)
        self.assertIn('preferences.getString("model", "") in ids',batch)
        file=(MAIN/'model/catalog/FileSavedModelCatalogPersistence.kt').read_text()
        operation=file.split('fun deleteSelections(',1)[1].split('fun replaceSelection(',1)[0]
        self.assertEqual(1,operation.count('exclusive {'))
        self.assertEqual(1,operation.count('writeDocument('))
        for f in ('selections.isNotEmpty()', 'ids.size == selections.size', 'it.providerIdentity != old.identity',
                  'it.expectedRevision != old.revision', '!= selection.record', 'isCurrentModel(ids)', 'filterNot { it.id in ids }'):
            self.assertIn(f,operation)
    def test_behavioral_regressions_exist_for_atomicity_and_real_interaction(self):
        jvm=(ROOT/'app/src/test/kotlin/app/daiyu/android/model/catalog/CatalogBatchDeletionTest.kt').read_text()
        self.assertIn('currentCheckAndWriteHoldLeaseAgainstAnotherInstance',jvm)
        self.assertIn('rejectsWholeBatchContainingCurrentModel',jvm)
        ui=(ROOT/'app/src/androidTest/kotlin/app/daiyu/android/ModelBulkSelectionActivityTest.kt').read_text()
        for f in ('testFilteredAllAndInvertPreserveHiddenSelectionAndKeepEmptyMode',
                  'testBatchConfirmationDeletesBothWithOneRevisionAndNoChatWrites',
                  'testSettingsButtonIsCenteredInWholeModelCardIncludingWrappedNameAndBadges', 'before.revision + 1'):
            self.assertIn(f,ui)
if __name__=='__main__': unittest.main()
