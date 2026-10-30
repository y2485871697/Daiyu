"""Guard production wiring; exercise its exact SQL with host SQLite, not Android."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
STORE = (APP / "storage/SQLiteRuntimeStore.kt").read_text()


class RuntimeCheckpointReuseContract(unittest.TestCase):
    def test_payload_proof_and_fallback_are_in_the_original_write_transaction(self):
        commit = STORE.split("val encoded = RunCheckpointCodec.encode(checkpoint)", 1)[1].split(
            "if (contextChanged) contextUsageRevision++", 1)[0]
        self.assertLess(commit.index("transactions.run { db ->"), commit.index("CAST(payload AS BLOB)=CAST(? AS BLOB)"))
        self.assertIn("arrayOf(checkpointReuse.expectedPayload(m.id.value), m.id.value)", commit)
        self.assertIn("checkpointReuse.preservation(stamp, c.getInt(6) == 1)", commit)
        self.assertIn("else preparations.preserve(requireNotNull(read(db, m.id.value)).value, checkpoint)", commit)
        self.assertIn("c.getType(1) == android.database.Cursor.FIELD_TYPE_INTEGER", commit)
        for field in ["c.getString(0) == checkpoint.owner", "c.getLong(1) == checkpoint.revision - 1",
                      "c.getString(2) != ExecutionPhase.TERMINAL.name", "c.getString(3) == processOwner",
                      "c.getString(4) == m.conversationId.value", "c.getString(5) == m.runId.value"]:
            self.assertIn(field, commit)

    def test_atomic_writes_and_cas_still_precede_cache_publication(self):
        commit = STORE.split("val encoded = RunCheckpointCodec.encode(checkpoint)", 1)[1].split(
            "if (contextChanged) contextUsageRevision++", 1)[0]
        publish = commit.index("checkpointReuse.committed(checkpoint, processOwner, encoded)")
        for call in ["database.insertRuntimeSupplement", "database.writeRuntimeMessage", "ConversationUsageStore.record",
                     "CloudContextUsageStore.record", "publishRunCompression", "publishRunArchive"]:
            self.assertLess(commit.index(call), publish)
        self.assertIn("message_id=? AND owner=? AND revision=? AND phase!='TERMINAL' AND process_owner=?", commit)
        self.assertIn("prior.first.revision.toString()", commit)
        self.assertIn("} catch (failure: Throwable) {\n            checkpointReuse.clear()\n            throw failure", commit)
        self.assertRegex(commit, r"publishRunArchive\(db, checkpoint\)\s*}\s*checkpointReuse.committed")
        close = STORE.split("@Synchronized override fun close()", 1)[1].split("companion object", 1)[0]
        self.assertLess(close.index("checkpointReuse.clear()"), close.index("lock.release()"))

    def test_cache_is_a_bounded_single_lease_projection_not_an_execution_authority(self):
        cache = (APP / "storage/RuntimeCheckpointReuse.kt").read_text()
        self.assertIn("maxChars: Int = 1_048_576", cache)
        self.assertIn("exactPayload && it.stamp == stamp", cache)
        self.assertIn("checkpoint.phase == ExecutionPhase.TERMINAL || encoded.length > maxChars", cache)
        self.assertIn("CheckpointPreservation.capture(checkpoint)", cache)
        for forbidden in ["hashCode(", "reconcileCheckpoint(", "readCommittedCheckpoint(", "mutableStateOf", "RunCheckpointCodec.decode"]:
            self.assertNotIn(forbidden, cache)

    def test_recovery_metadata_uses_the_same_strict_parse(self):
        codec = (APP / "storage/RunCheckpointCodec.kt").read_text()
        self.assertIn("fun decode(text: String): RunCheckpoint = decodeWithRecovery(text).checkpoint", codec)
        self.assertIn(".also(::validate), recoveryOwner)", codec)
        read = STORE.split("val (metadata, payload) = raw", 1)[1].split("return Snapshot", 1)[0]
        self.assertIn("RunCheckpointCodec.decodeWithRecovery(payload)", read)
        self.assertIn("decoded.recoverySourceOwner", read)
        self.assertNotIn("JSONObject(payload)", read)
        for field in ["value.message.id.value == messageId", "value.message.conversationId.value == metadata[0]",
                      "value.message.runId.value == metadata[1]", "value.owner == metadata[2]",
                      "value.revision.toString() == metadata[3]", "value.phase.name == metadata[4]"]:
            self.assertIn(field, read)

    def test_encoding_and_published_facts_use_the_same_frozen_input(self):
        commit = STORE.split("private fun commit(input: RunCheckpoint", 1)[1].split(
            "if (contextChanged) contextUsageRevision++", 1)[0]
        self.assertLess(commit.index("val checkpoint = CheckpointPreservation.freeze(input)"),
                        commit.index("RunCheckpointCodec.encode(checkpoint)"))
        self.assertIn("checkpointReuse.committed(checkpoint, processOwner, encoded)", commit)
        facts = (APP / "context/CheckpointPreservation.kt").read_text()
        for token in ["usageRequests = value.usageRequests.toList()", "usage = value.usage.toList()",
                      "it.copy(summaries = it.summaries.toList())"]:
            self.assertIn(token, facts)

    def test_all_original_preservation_rejections_remain(self):
        facts = (APP / "context/CheckpointPreservation.kt").read_text()
        for code in ["main_request_coverage_dropped", "summary_usage_changed_after_handoff", "runtime_compaction_dropped",
                     "runtime_summary_admission_dropped", "runtime_summary_without_admission"]:
            self.assertIn(code, facts)
        preparation = (APP / "context/SQLitePreparationRuntime.kt").read_text()
        self.assertIn("CheckpointPreservation.capture(prior).preserve(next)", preparation)
        self.assertIn("internal fun preserve(prior: CheckpointPreservation, next: RunCheckpoint) = prior.preserve(next)", preparation)


class RuntimePayloadProofSql(unittest.TestCase):
    """No product DB access; use the production query on an isolated in-memory DB."""

    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("CREATE TABLE runtime_checkpoints(message_id TEXT PRIMARY KEY, owner TEXT, revision INTEGER, "
                        "phase TEXT, process_owner TEXT, conversation_id TEXT, run_id TEXT, payload TEXT)")
        self.query = re.search(r'rawQuery\("(SELECT owner,revision,phase,process_owner,conversation_id,run_id,CAST\(payload AS BLOB\).*?)"', STORE).group(1)

    def proof(self, stored, expected):
        self.db.execute("DELETE FROM runtime_checkpoints")
        self.db.execute("INSERT INTO runtime_checkpoints VALUES ('m','o',7,'STREAMING','p','c','r',?)", (stored,))
        return self.db.execute(self.query, (expected, "m")).fetchone()[6]

    def test_exact_large_unicode_payload_matches(self):
        payload = '{"text":"' + "数🙂e\u0301" * 70_000 + '"}'
        self.assertEqual(1, self.proof(payload, payload))
        self.assertEqual(0, self.proof(payload, payload[:-2] + 'x"}'))

    def test_text_collation_and_same_hash_are_not_payload_identity(self):
        for stored, expected in [("Aa", "BB"), ("JSON", "json"), ("é", "e\u0301"), ("x\x00a", "x\x00b"), ("x", "x ")]:
            with self.subTest(stored=stored, expected=expected):
                self.assertEqual(0, self.proof(stored, expected))

    def test_null_and_missing_rows_cannot_prove_identity(self):
        self.assertIsNone(self.proof(None, ""))
        self.assertIsNone(self.db.execute(self.query, ("", "missing")).fetchone())

    def test_same_metadata_changed_payload_and_rollback_are_visible(self):
        self.assertEqual(1, self.proof("committed", "committed"))
        self.db.commit()
        self.db.execute("BEGIN")
        self.db.execute("UPDATE runtime_checkpoints SET payload='changed' WHERE message_id='m'")
        self.assertEqual(0, self.db.execute(self.query, ("committed", "m")).fetchone()[6])
        self.db.rollback()
        row = self.db.execute(self.query, ("committed", "m")).fetchone()
        self.assertEqual(("o", 7, "STREAMING", "p", "c", "r", 1), row)


if __name__ == "__main__":
    unittest.main()
