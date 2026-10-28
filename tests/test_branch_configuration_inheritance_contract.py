"""Branch selection persistence and production wiring; device execution is tracked separately."""
from pathlib import Path
import re
import sqlite3
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class BranchConfigurationInheritanceContractTest(unittest.TestCase):
    def test_production_model_is_captured_fenced_and_written_inside_t2(self):
        source = (SRC / 'context/ProductionConversationBranch.kt').read_text()
        self.assertIn('val modelBinding: ConversationModelBinding?', source)
        self.assertIn('ConversationModelSelections.read(sql, id)', source)
        self.assertIn('ConversationModelSelections.read(db, id) == snapshot.modelBinding', source)
        write = source.index('ConversationModelSelections.write(db, copy, snapshot.modelBinding)')
        self.assertLess(source.index('db.beginTransaction()'), write)
        self.assertLess(write, source.index('db.setTransactionSuccessful()'))
        self.assertIn('ConversationModelSelections.read(db, copy) == snapshot.modelBinding', source)

    def test_compatibility_branch_keeps_the_same_model_transaction(self):
        source = (SRC / 'context/ContextService.kt').read_text().split('private fun branchInternal', 1)[1].split('fun revise', 1)[0]
        self.assertIn('val modelBinding = ConversationModelSelections.read(db, id)', source)
        self.assertIn('ConversationModelSelections.write(db, copy, modelBinding)', source)
        self.assertIn('ConversationModelSelections.read(db, owner) == modelBinding', source)

    def test_child_config_uses_a_source_snapshot_and_never_copies_tasks(self):
        source = (SRC / 'ChatController.kt').read_text().split('internal fun messageAction(action:', 1)[1].split('internal fun skillManagement()', 1)[0]
        capture = source.index('childPreferences.export(SubAgentConfigKey.Conversation(target.conversationId.value))')
        self.assertLess(capture, source.index('messageActionStore.branch(target)'))
        self.assertIn('childPreferences.importOwner(SubAgentConfigKey.Conversation(id.value), checkNotNull(branchChildren))', source)
        self.assertIn('retainBranchConfiguration(copy.id)', source)
        self.assertIn('retainBranchConfiguration(failure.locator.conversationId)', source)
        self.assertIn('branchUnknown(app.daiyu.android.context.BranchLocator(copy.id, emptyList()), false)', source)
        self.assertIn('SELECT 1 FROM conversations WHERE id=?', source)
        self.assertNotRegex(source, r'(enqueue|continueTask|resumeTask|createTaskGroup)\(')
        self.assertNotIn('overwrite = true', source)
        self.assertNotIn('checkNotNull(branchChildren)); true', source)

    def test_real_sqlite_keeps_provider_identity_null_and_atomic_rollback(self):
        source = (SRC / 'storage/ConversationModelSelections.kt').read_text()
        schema = re.search(r'db.execSQL\("(CREATE TABLE IF NOT EXISTS conversation_model_selections[^"\n]+)"\)', source).group(1)
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE conversations(id TEXT PRIMARY KEY)')
        db.execute(schema)
        db.executemany('INSERT INTO conversation_model_selections VALUES(?,?,?)', [('source', 'other-provider', 'same-name-local-id'), ('', 'global-provider', 'global-local-id'), ('empty', None, None)])
        for owner in ('source', 'empty'):
            db.execute('INSERT INTO conversation_model_selections SELECT ?,provider_slot,local_model_id FROM conversation_model_selections WHERE owner_id=?', ('child-'+owner, owner))
        self.assertEqual(('other-provider', 'same-name-local-id'), db.execute("SELECT provider_slot,local_model_id FROM conversation_model_selections WHERE owner_id='child-source'").fetchone())
        self.assertEqual((None, None), db.execute("SELECT provider_slot,local_model_id FROM conversation_model_selections WHERE owner_id='child-empty'").fetchone())
        db.commit()
        db.execute("CREATE TRIGGER reject_selection BEFORE INSERT ON conversation_model_selections WHEN NEW.owner_id='rollback' BEGIN SELECT RAISE(ABORT, 'blocked'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            with db:
                db.execute("INSERT INTO conversations VALUES('rollback')")
                db.execute("INSERT INTO conversation_model_selections VALUES('rollback','p','m')")
        self.assertEqual(0, db.execute("SELECT count(*) FROM conversations WHERE id='rollback'").fetchone()[0])
