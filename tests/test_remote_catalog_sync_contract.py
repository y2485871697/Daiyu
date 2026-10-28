"""Production entry/ownership contracts; real JVM tests cover synchronization behavior."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class RemoteCatalogSyncContract(unittest.TestCase):
    def test_both_hosts_supply_remote_provenance_and_saved_connection_gate(self):
        for path in ('MainActivity.kt', 'ui/screens/settings/ProviderManagementRoute.kt'):
            source = (APP / path).read_text()
            self.assertIn('isRemoteModel = { it.settings.source == app.daiyu.android.model.catalog.CatalogModelSource.REMOTE }', source)
            self.assertIn('catalogConnectionSaved =', source)
            self.assertIn('== saved', source)

    def test_ui_awaits_existing_batch_delete_and_refreshes_after_fetch(self):
        source = (APP / 'ui/screens/settings/ProviderCatalogSettingsScreen.kt').read_text()
        self.assertIn('rememberUpdatedState(localModelActions?.deleteSelected)', source)
        self.assertIn('session.begin(providerIdentity, catalogConnectionSaved)', source)
        self.assertIn('suspendCancellableCoroutine', source)
        fetch = source.split('fetchCatalog(value, request::isCancelled)', 1)[1].split('if (!accepted)', 1)[0]
        self.assertIn('session.fetched(request, models)', fetch)
        self.assertIn('delete(tickets, { isCancelled() || !continuation.isActive })', fetch)
        self.assertIn('localRevision++', fetch)
        self.assertNotIn('persistence.delete', source)

    def test_cleanup_is_based_on_full_remote_snapshot_not_new_candidates(self):
        source = (APP / 'model/catalog/CatalogImportSession.kt').read_text()
        self.assertLess(source.index('remoteIds ='), source.index('val obsolete ='))
        self.assertLess(source.index('removeObsolete(tickets'), source.index('val existingIds ='))
        self.assertIn('if (remote.isEmpty())', source)
        self.assertIn('isRemoteModel(it) && remoteModelKey(it.id) !in remoteIds', source)
        self.assertIn('before.copy(revision = Math.addExact(before.revision, 1L)', source)
        self.assertIn('if (snapshot != expected)', source)
        self.assertIn('remote.filterNot { remoteModelKey(it.id) in existingIds }', source)

    def test_remote_provenance_is_durable_optional_and_not_inferred_for_old_or_manual_records(self):
        source = (APP / 'model/catalog/CatalogModelParameters.kt').read_text()
        self.assertIn('val source: CatalogModelSource = CatalogModelSource.LOCAL', source)
        self.assertIn('.put("source", record.source.name)', source)
        self.assertIn('else CatalogModelSource.LOCAL', source)
        self.assertIn('source: CatalogModelSource = CatalogModelSource.REMOTE', source)
        manual = (APP / 'model/catalog/CatalogModelEdit.kt').read_text()
        self.assertIn('CatalogModelParameters()', manual)
        self.assertIn('record.settings.copy(', manual)
        self.assertIn('newModel(RemoteModel(id, label), CatalogModelSource.LOCAL)', (APP / 'storage/ModelSettings.kt').read_text())

    def test_matching_uses_normalized_key_without_changing_wire_id(self):
        source = (APP / 'model/catalog/ModelSelectionState.kt').read_text()
        self.assertIn('id.trim().lowercase(Locale.ROOT)', source)
        self.assertIn('.distinctBy { remoteModelKey(it.id) }', source)
        store = (APP / 'model/catalog/SavedModelCatalogStore.kt').read_text()
        self.assertIn('current.models.map { remoteModelKey(it.id) }', store)
        self.assertIn('filterNot { remoteModelKey(it.id) in existingIds }', store)
        self.assertIn('require(it.id == remote.id)', store)

    def test_android_sources_cover_cleanup_cancel_refresh_and_unsaved_connection(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/ProviderCatalogSettingsScreenTest.kt').read_text()
        for name in ('testFetchCleansObsoleteRemoteBeforeDialogAndCancelKeepsManualModel',
                     'testOnlyCleanupRefreshesLocalListWithoutOpeningNewModelDialog',
                     'testUnsavedConnectionDoesNotStartFetchOrCleanup'):
            self.assertIn('fun '+name, source)
        self.assertIn('node("provider-model-option:existing").assertDoesNotExist()', source)

if __name__ == '__main__':
    unittest.main()
