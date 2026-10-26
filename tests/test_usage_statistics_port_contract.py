"""Full statistics wiring guards. Android persistence regressions remain batch-gated."""
from pathlib import Path
import re
import sqlite3
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
def read(p): return (SRC / p).read_text()
class UsageStatisticsPortContractTest(unittest.TestCase):
    def test_new_and_upgraded_databases_install_independent_accounting(self):
        source=read('storage/ConversationDatabase.kt')
        self.assertIn('null, 28)', source)
        self.assertIn('oldVersion in 1..27 && newVersion == 28', source)
        upgrade=source.split('override fun onUpgrade',1)[1]
        self.assertLess(upgrade.index('UsageStatisticsStore.install(db)'),upgrade.index('if (oldVersion >= 13) return'))
        self.assertLess(upgrade.index('ConversationUsageStore.backfill(db)'),upgrade.index('UsageStatisticsStore.install(db)'))
        self.assertEqual(2, source.count('UsageStatisticsStore.created(db, value.id.value, value.updatedAt)'))
    def test_request_table_does_not_cascade_with_runtime_or_messages(self):
        source=read('storage/UsageStatisticsStore.kt')
        expression=source.split('db.execSQL("CREATE TABLE IF NOT EXISTS $REQUESTS',1)[1].split(')")',1)[0]
        self.assertNotIn('REFERENCES',expression)
        self.assertIn('id TEXT PRIMARY KEY',expression)
        self.assertIn('CONFLICT_REPLACE',source)
        self.assertIn('check(prior == null || prior == listOf(',source)
        self.assertNotIn('takeLast(4000)', source)
    def test_partial_null_merge_and_persist_before_ack(self):
        source=read('model/usage/UsageRecordingModel.kt')
        fields=read('storage/UsageStatisticsStore.kt')
        for field in ('input','output','cached','creation'): self.assertIn('next.'+field+' ?: '+field,fields)
        self.assertIn('val current = start()',source)
        self.assertIn('val current = checkNotNull(request)',source)
        self.assertIn('observe(event)\n            persist()\n            try { sink(event) } finally { persist() }',source)
        self.assertIn('batch.events.forEach(::observe)\n            persist()',source)
        self.assertIn('nextAcknowledged(outputs, { current.deliver(it, localSink) }',source)
        self.assertIn('catch (failure: Exception)',source)
    def test_frozen_metadata_and_every_production_model_path_are_wired(self):
        controller=read('ChatController.kt')
        self.assertIn('UsageRecorder.install(controller.database)',controller)
        self.assertIn('UsageRecorder.wrap(preparedModel, requestProvider, conversationId.value)',controller)
        self.assertLess(controller.index('assembledDelegate.withBackgroundResume(background)'),controller.index('UsageRecorder.wrap(preparedModel'))
        self.assertEqual(2,controller.count('UsageRecorder.wrap(messageImages.wrap(delegate, provider), provider)'))
        for path in ('title/TitleModelCoordinator.kt','context/ProviderSummaryModel.kt','model/auxiliary/AuxiliaryVisionModelAdapter.kt','pet/AndroidPetModelPort.kt'):
            self.assertIn('UsageRecorder.wrap(',read(path))
        self.assertIn('snapshot.usageIdentity',read('model/usage/UsageRecorder.kt'))
        self.assertIn('.opt("model") as? String',read('model/usage/UsageRecorder.kt'))
        self.assertIn('inferConversation = false',read('pet/AndroidPetModelPort.kt'))
        self.assertIn('frozen.settings.copy(usageIdentity = null)',read('storage/ProviderRepository.kt'))
    def test_retirement_and_branch_share_real_write_transactions(self):
        self.assertIn('UsageStatisticsStore.retire(db, id.value)',read('storage/ConversationDatabase.kt'))
        self.assertIn('UsageStatisticsStore.retireMessages(db, removed)',read('storage/MessageActionStore.kt'))
        self.assertIn('UsageStatisticsStore.retireMessages(db, original.drop(cutoff))',read('context/ContextService.kt'))
        self.assertIn('UsageStatisticsStore.created(db, copy.value, values.getAsLong("updated"))',read('context/ProductionConversationBranch.kt'))
        store=read('storage/UsageStatisticsStore.kt')
        self.assertIn('SQLiteText.read(db, "messages", "blocks", "id", id)',store)
        self.assertNotIn('SELECT role,blocks FROM messages',store)
    def test_complete_backup_has_receipts_and_static_statistics_not_execution(self):
        source=read('backup/NeutralBackupService.kt')
        self.assertIn('spool.write(8, payload)',source)
        self.assertIn('statistics.toString()',source)
        self.assertIn('.put("statistics", capture.statistics)',source)
        self.assertIn('val statisticsReceipt = BackupStatisticsCodec.prepare(db, parsed.statistics,',source)
        self.assertLess(source.index('onStage("正在提交导入事务")'),source.index('BackupStatisticsCodec.verify(db, parsed.statistics, statisticsReceipt)'))
        self.assertIn('UsageStatisticsStore.importLegacyReceipts(db, importedConversations)',source)
        codec=read('backup/BackupStatisticsCodec.kt')
        for field in ('request_owner','request_id','window_tokens','retired_messages'): self.assertIn(field,codec)
        self.assertIn('if (previous != null && !same(fields, previous, row))',codec)
        for token in ('conversation in restoringConversations', 'conversation in incoming.textConversations',
                      'same(fields.filterNot', 'retirement.before', 'retirement.after'):
            self.assertIn(token,codec)
        self.assertIn('restoringConversations = if (duplicate) emptySet()',source)
        self.assertIn('BackupStatisticsCodec.restore(db, parsed.statistics, statisticsReceipt)',source)
        self.assertNotIn('CONFLICT_REPLACE',codec)
        self.assertNotIn('launch_count',codec)
        self.assertEqual(2,read('backup/BackupPackageSpool.kt').count('1L..8L'))
    def test_unknown_metadata_stays_unknown_and_no_bounded_reader_drives_totals(self):
        source=read('storage/UsageStatisticsStore.kt')
        migrated=source.split('fun importLegacyReceipts',1)[1].split('fun created',1)[0]
        self.assertNotIn('System.currentTimeMillis',migrated)
        self.assertNotIn('put("model_id"',migrated)
        self.assertNotIn('put("at_millis"',migrated)
        ui=read('ui/RetainedConversationData.kt').split('fun stats()',1)[1].split('fun folders',1)[0]
        self.assertIn('UsageStatisticsStore.snapshot',ui)
        self.assertIn('currentAccountingTokens',read('data/repository/UsageStatsSnapshot.kt'))
        self.assertIn('usage-model-coverage',read('ui/screens/stats/UsageStatsScreen.kt'))
if __name__=='__main__': unittest.main()
