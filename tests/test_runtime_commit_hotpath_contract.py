"""Production wiring for bounded reuse; Android behavior is checked by JVM DB fixtures."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / 'app/src/main/kotlin/app/daiyu/android/storage/SQLiteRuntimeStore.kt'


class RuntimeCommitHotpathContract(unittest.TestCase):
    def setUp(self):
        self.source = STORE.read_text()
        self.commit = self.source.split('private fun commit(', 1)[1].split(
            '@Synchronized override fun readCommittedCheckpoint', 1)[0]

    def test_one_commit_scoped_decode_context_is_shared_by_both_message_checks(self):
        self.assertEqual(1, self.commit.count('CommitMessageDecodeReuse().use { decodeReuse ->'))
        self.assertIn('database.runtimeMessage(db, m, decodeReuse)', self.commit)
        self.assertIn('database.writeRuntimeMessage(db, m, decodeReuse)', self.commit)
        self.assertLess(self.commit.index('transactions.run { db ->'),
                        self.commit.index('CommitMessageDecodeReuse().use'))
        self.assertNotIn('private val decodeReuse', self.source)

    def test_utf8_writer_is_reused_but_cleared_at_failure_terminal_and_close(self):
        self.assertIn('private val checkpointJsonWriter = CheckpointJsonWriter()', self.source)
        self.assertIn('RunCheckpointCodec.encodeUtf8(checkpoint, checkpointJsonWriter)', self.commit)
        self.assertLess(self.commit.index('try {'), self.commit.index('RunCheckpointCodec.encodeUtf8('))
        self.assertIn('if (checkpoint.phase == ExecutionPhase.TERMINAL) checkpointJsonWriter.clear()', self.commit)
        failure = self.commit.split('catch (failure: Throwable)', 1)[1]
        self.assertIn('checkpointJsonWriter.clear()', failure)
        self.assertIn('throw failure', failure)
        close = self.source.split('@Synchronized override fun close()', 1)[1].split('companion object', 1)[0]
        self.assertIn('checkpointJsonWriter.clear()', close)

    def test_each_event_still_encodes_and_commits_before_reuse_publication(self):
        self.assertIn('CheckpointPreservation.freeze(input)', self.commit)
        self.assertIn('c.getLong(1) == checkpoint.revision - 1', self.commit)
        self.assertIn('CAST(payload AS BLOB)=CAST(? AS BLOB)', self.commit)
        self.assertIn('writeCheckpoint(db, checkpoint, encoded, prior?.first)', self.commit)
        writer = self.source.split('private fun writeCheckpoint(', 1)[1].split(
            '@Synchronized override fun close()', 1)[0]
        self.assertIn('INSERT INTO runtime_checkpoints($columns) VALUES(?,?,?,?,?,?,?,CAST(? AS TEXT))', writer)
        self.assertIn('payload=CAST(? AS TEXT)', writer)
        self.assertIn("WHERE message_id=? AND owner=? AND revision=? AND phase!='TERMINAL' AND process_owner=?", writer)
        self.assertIn('statement.bindBlob(8, encoded)', writer)
        self.assertIn('check(statement.executeInsert() != -1L)', writer)
        self.assertIn('check(statement.executeUpdateDelete() == 1)', writer)
        self.assertLess(self.commit.index('writeCheckpoint(db, checkpoint, encoded, prior?.first)'),
                        self.commit.index('database.writeRuntimeMessage(db, m, decodeReuse)'))
        self.assertLess(self.commit.index('database.writeRuntimeMessage(db, m, decodeReuse)'),
                        self.commit.index('checkpointReuse.committed(checkpoint, processOwner, encoded)'))
        self.assertNotIn('runCatching', self.commit)
        self.assertNotIn('launch(', self.commit)


if __name__ == '__main__':
    unittest.main()
