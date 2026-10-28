"""Lifecycle wiring guards; JVM owns state behavior, Android sources own controller integration."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app/src/main/kotlin/app/daiyu/android'
DOMAIN=ROOT/'core-domain/src/main/kotlin/app/daiyu/android/domain/delegation'
class DeletedModelSessionContract(unittest.TestCase):
    def test_delete_has_independent_worker_and_does_not_weaken_general_data_gate(self):
        s=(APP/'ChatController.kt').read_text()
        delete=s.split('private fun deleteCatalogModelBindings(',1)[1].split('private fun editConfiguredCatalogModel(',1)[0]
        self.assertIn('modelDeletionWorker.execute',delete)
        self.assertNotIn('idleAvailable()',delete)
        self.assertNotIn('hasLiveChildTasks()',delete)
        self.assertIn('catalogDeletionReaders++',delete);self.assertIn('catalogDeletionReaders--',delete)
        self.assertIn('check(idleAvailable() && !hasLiveChildTasks())',s)
    def test_busy_main_label_is_owned_by_exact_frozen_conversation_and_binding(self):
        s=(APP/'ChatController.kt').read_text()
        label=s.split('fun retainedDeletedMainModel(',1)[1].split('private fun reconcileDeletedModelCards',1)[0]
        for part in ('it.initial.conversationId.value == conversationId','it.modelBinding','deletedCatalogModels','?.provider?.model'):
            self.assertIn(part,label)
        self.assertIn('frozenProvider.localModelIdentity?.let { frozenProvider.editor.slotId to it }',s)
        ui=(APP/'MainActivity.kt').read_text()
        self.assertIn('controller.retainedDeletedMainModel(selected?.id?.value) ?: confirmedModelLabel',ui)
        self.assertIn('modelLabel = savedModelLabel()',ui)
        self.assertIn('modelDeletionRevision != controller.modelDeletionRevision',ui)
    def test_all_owners_and_both_child_execution_paths_participate_in_cleanup(self):
        s=(APP/'ChatController.kt').read_text()
        section=s.split('private fun reconcileDeletedModelCards',1)[1].split('private fun scheduleDeletedModelCleanup',1)[0]
        self.assertIn('childSupervisor',section);self.assertIn('childModelBatches',section);self.assertIn('batch.liveProfiles()',section)
        preferences=(APP/'delegation/ConversationSubAgentPreferences.kt').read_text()
        self.assertIn('OWNER_PREFIX + "c_"',preferences);self.assertIn('OWNER_PREFIX + "d_"',preferences)
        self.assertIn('(profile.providerId to profile.modelId) in deleted && (owner.value to profile.id) !in live',preferences)
    def test_accepted_snapshot_and_new_admission_are_separate_in_all_paths(self):
        batch=(DOMAIN/'ChildModelBatch.kt').read_text();retained=(DOMAIN/'RetainedChildTasks.kt').read_text()
        self.assertIn('queued == null && snapshot.any { !models.getValue(it.profileId).acceptsNewTask() }',batch)
        self.assertIn('profileReservations.remove(reservations[index])',batch)
        self.assertIn('!workerFinished || !cleanupFinished || itemReleased',batch)
        self.assertIn('if (!definition.acceptsNewTask()) return ChildTaskCommandReceipt.Rejected("child_model_deleted")',retained)
        frozen=(APP/'delegation/SavedSubAgentRuntimeBindings.kt').read_text()
        self.assertIn('AtomicReference(binding.profile to original)',frozen)
        self.assertIn('if (deletedSelection()) lastSelection.get().second',frozen)
    def test_catalog_absence_recovers_without_interpreting_unknown_provider_as_deleted(self):
        s=(APP/'ChatController.kt').read_text()
        self.assertIn('runCatching { recoverDeletedModelBindings() }.onFailure',s)
        self.assertIn('available[profile.providerId]?.contains(profile.modelId) == false',s)
        store=(APP/'storage/ModelSettings.kt').read_text()
        self.assertIn('clearMissingCatalogSelection()',store)
        self.assertIn('modelCatalog.updateExistingRegisteredModel(previousConnection.model)',store)
        self.assertNotIn('CURRENT_MODEL',(APP/'model/catalog/CatalogModelSelection.kt').read_text())
if __name__=='__main__':unittest.main()
