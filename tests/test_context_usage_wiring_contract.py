"""Source/SQLite contracts only; the corresponding Kotlin/Android tests are batch-gated."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

def text(path):
    return (SRC / path).read_text()

def create_sql(path):
    source = text(path)
    expression = source.split('db.execSQL(', 1)[1].split(')\n', 1)[0]
    return ''.join(re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', expression)).replace('$TABLE', 'conversation_usage_receipts')

class ContextUsageWiringContractTest(unittest.TestCase):
    def test_actuals_follow_checkpoint_ownership_in_the_same_transaction(self):
        source = text('storage/SQLiteRuntimeStore.kt')
        commit = source.split('private fun commit(', 1)[1].split('@Synchronized override fun readCommitted', 1)[0]
        self.assertLess(commit.index('transactions.run { db ->'), commit.index('CloudContextUsageStore.record'))
        self.assertLess(commit.index('拒绝过期或异主检查点'), commit.index('CloudContextUsageStore.record'))
        self.assertLess(commit.index('database.writeRuntimeMessage(db, m)'), commit.index('CloudContextUsageStore.record'))
        self.assertIn('if (contextChanged) contextUsageRevision++', commit)
        self.assertIn('it.first.id == m.id && it.first.runId == m.runId', commit)

    def test_only_latest_admitted_main_request_can_advance_actual(self):
        source = text('storage/CloudContextUsageStore.kt')
        self.assertIn('it.source == UsageRequestSource.MAIN', source)
        self.assertIn('checkpoint.usageRequests.ifEmpty', source)
        self.assertIn('blockedMainCount = admitted.size', source)
        self.assertIn('admitted.size > next.blockedMainCount', source)
        self.assertIn('val request = admitted.last()', source)
        self.assertIn('it.request.owner == request.owner && it.request.requestId == request.requestId', source)
        for forbidden in ('afterTokens', 'localTokens', 'estimateTokens(', 'context_usage WHERE'):
            self.assertNotIn(forbidden, source)

    def test_restore_requires_actual_receipt_provenance_and_configuration(self):
        source = text('storage/CloudContextUsageStore.kt')
        for token in ('"cloud_actual"', '"version"', '"conversation"', '"epoch"', '"requestOwner"', '"requestId"',
                      'it.model.provider == model.provider', 'it.model.model == model.model',
                      'it.model.configuration == model.configuration', 'it.model.registryRevision == model.registryRevision'):
            self.assertIn(token, source)

    def test_history_edits_and_successful_summary_revoke_in_the_write_transaction(self):
        service = text('context/ContextService.kt')
        self.assertEqual(2, service.count('CloudContextUsageStore.invalidate(db, id)'))
        self.assertIn('CloudContextUsageStore.invalidate(db, target.conversationId)', text('storage/MessageActionStore.kt'))
        self.assertLess(service.index('check(inserted != -1L)'), service.index('CloudContextUsageStore.invalidate(db, id)'))

    def test_all_request_sources_update_the_conversation_ledger(self):
        for path in ('storage/SQLiteRuntimeStore.kt', 'model/usage/SQLiteAdditionalUsageStore.kt',
                     'model/usage/SQLiteChildUsageStore.kt', 'context/SQLitePreparationRuntime.kt'):
            self.assertIn('ConversationUsageStore.record(db', text(path))
        ledger = text('storage/ConversationUsageStore.kt')
        for field in ('inputTokens', 'outputTokens', 'cachedTokens', 'cacheCreationTokens'):
            self.assertIn('usage.' + field + ' ?: old?.' + field, ledger)
        self.assertIn('if (merged == old', ledger)
        self.assertIn('private data class Fields(', ledger)
        self.assertIn('val merged = Fields(', ledger)
        self.assertNotIn('val merged = ModelEvent.Usage(', ledger)
        self.assertNotIn('runtime_checkpoints', ledger.split('fun read(', 1)[1].split('private fun readUsage', 1)[0])

    def test_ledger_is_chosen_whole_without_adding_current_usage(self):
        controller = text('ChatController.kt').split('internal fun conversationTokenUsage(', 1)[1].split('internal val usageRevision', 1)[0]
        self.assertIn('ConversationUsageStore.read(db, conversation.value)\n                ?:', controller)
        self.assertNotIn(' + ', controller)
        dialog = text('ui/screens/chat/conversation/ChatUsageDialog.kt')
        self.assertIn('conversationId ?: return@LaunchedEffect', dialog)
        self.assertIn('controller.conversationTokenUsage(owner)', dialog)
        self.assertNotIn('recordedRequestUsage()', dialog)

    def test_production_composer_gets_fraction_and_unclamped_decimal_label(self):
        main = text('MainActivity.kt')
        self.assertIn('contextFraction = contextUsage.fraction, contextUsageText = contextUsage.label()', main)
        self.assertIn('rememberChatContextUsage(', main)
        helper = text('ui/screens/chat/conversation/ChatContextUsage.kt')
        self.assertIn('withContext(Dispatchers.IO)', helper)
        self.assertNotIn('contentRevision', helper)
        self.assertIn('catch (cancelled: CancellationException) { throw cancelled }', helper)
        model = text('ui/model/CloudContextUsage.kt')
        self.assertIn('coerceIn(0f, 1f)', model)
        self.assertIn('minimumFractionDigits = 1; maximumFractionDigits = 1', model)
        self.assertNotIn('outputTokens', model)
        self.assertNotIn('reserveTokens', model)

    def test_actual_label_and_window_use_provider_settings_even_during_a_run(self):
        main = text('MainActivity.kt')
        self.assertIn('contextTokensText = contextUsage.tokensLabel()', main)
        controller = text('ChatController.kt')
        binding = controller.split('bindContextUsage(assistant,', 1)[1].split('val owned = ChatRunner', 1)[0]
        self.assertIn('provider.contextWindowOverride?.toLong()', binding)
        self.assertNotIn('frozen.policy.contextTokens', binding)
        self.assertIn('readRegisteredModel(metadata.model)?.settings?.contextWindowOverride', text('storage/ProviderRepository.kt'))
        self.assertIn('if (conversation == null) return app.daiyu.android.ui.model.CloudContextUsageUi(0, model.windowTokens)', controller)
        helper = text('ui/screens/chat/conversation/ChatContextUsage.kt')
        self.assertIn('controller.cloudContextUsage(conversation)', helper)
        self.assertNotIn('conversation ?: return', helper)

    def test_actual_zero_survives_validation_and_receipt_restore(self):
        model = text('ui/model/CloudContextUsage.kt')
        self.assertIn('usage.inputTokens?.takeIf { it >= 0 }', model)
        self.assertIn('inputTokens?.takeIf { it >= 0 }', model)
        self.assertIn('it.input >= 0', text('storage/CloudContextUsageStore.kt'))
        self.assertIn('"${compact(inputTokens)}/${compact(windowTokens?.takeIf { it > 0 })}"', model)

    def test_schema_upgrade_installs_before_early_return_and_does_not_seed_actuals(self):
        schema = text('storage/ConversationDatabase.kt')
        self.assertIn('null, 27)', schema)
        self.assertIn('oldVersion in 1..26 && newVersion == 27', schema)
        for token in ('CloudContextUsageStore.installSchema(db)', 'ConversationUsageStore.installSchema(db)',
                      'ConversationUsageStore.backfill(db)'):
            self.assertLess(schema.index(token), schema.index('if (oldVersion >= 13) return'))
        self.assertNotIn('CloudContextUsageStore.record', schema)

    def test_sql_schema_keeps_request_receipts_when_messages_are_deleted(self):
        db = sqlite3.connect(':memory:')
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('CREATE TABLE conversations(id TEXT PRIMARY KEY)')
        db.execute('CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE)')
        db.execute(create_sql('storage/CloudContextUsageStore.kt'))
        db.execute(create_sql('storage/ConversationUsageStore.kt'))
        db.execute("INSERT INTO conversations VALUES('a')")
        db.execute("INSERT INTO messages VALUES('message','a')")
        db.execute("INSERT INTO context_ui_state VALUES('a','{}')")
        db.execute("INSERT INTO conversation_usage_receipts(conversation_id,request_owner,request_id,input_tokens) VALUES('a','owner','request',100)")
        db.execute("DELETE FROM messages WHERE id='message'")
        self.assertEqual(100, db.execute('SELECT input_tokens FROM conversation_usage_receipts').fetchone()[0])
        with self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO conversation_usage_receipts(conversation_id,request_owner,request_id) VALUES('a','owner','request')")
        db.execute("DELETE FROM conversations WHERE id='a'")
        self.assertEqual(0, db.execute('SELECT COUNT(*) FROM conversation_usage_receipts').fetchone()[0])
        self.assertEqual(0, db.execute('SELECT COUNT(*) FROM context_ui_state').fetchone()[0])
        db.close()

if __name__ == '__main__':
    unittest.main()
