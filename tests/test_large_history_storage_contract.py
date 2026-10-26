"""Bounded SQL/source contracts; Android CursorWindow coverage lives in instrumentation."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATABASE = ROOT / "app/src/main/kotlin/app/daiyu/android/storage/ConversationDatabase.kt"
READER = ROOT / "app/src/main/kotlin/app/daiyu/android/storage/SQLiteText.kt"


class LargeHistoryStorageContractTest(unittest.TestCase):
    def test_duplicate_receipt_compares_raw_fields_outside_metadata_cursor(self):
        source = DATABASE.read_text()
        fallback = source.split("if (count==0) {", 1)[1].split("check(identical)", 1)[0]
        self.assertIn("SELECT conversation_id,run_id,role,status FROM messages", fallback)
        self.assertNotIn("role,blocks", fallback)
        self.assertIn('listOf("blocks", "error", "protocol", historyColumn)', fallback)
        self.assertIn('SQLiteText.read(db, "messages", column, "id", message.id.value)', fallback)
        self.assertIn("encoded.getAsString(column)", fallback)

    def test_backup_uses_chunked_raw_fields_and_preserves_budget_and_validation(self):
        export = DATABASE.read_text().split("private fun exportBackup(conversationId:", 1)[1].split("private fun parseBackup", 1)[0]
        self.assertIn("SELECT id,conversation_id,run_id,role,status,created_at FROM messages", export)
        self.assertNotIn("role,blocks", export)
        self.assertIn('SQLiteText.read(db, "messages", column, "id", c.getString(0), 16L*1024*1024)', export)
        self.assertIn('SQLiteText.read(db, "conversations", "draft", "id", c.getString(0), 16L*1024*1024)', export)
        self.assertIn('val draft=if(conversationId==null) composerDraft(db) else ""', export)
        for guard in ("conversationCount<=1000 && messageCount<=50000", "rawBytes<=16*1024*1024",
                      "encodedBytes<=16*1024*1024", "parseBackup(result)", "MessageHistoryCodec.validate(projection)",
                      "SQLiteMessageVideoStore.readVerified(db, projection)", "JSONArray(rawSegments)"):
            self.assertIn(guard, export)

    def test_import_large_existing_composer_keeps_original_conflict_gate(self):
        source = DATABASE.read_text().split("private fun importBackup(text: String, deferVideoWitness:", 1)[1]
        self.assertIn("val existing=composerDraft(db)", source)
        self.assertIn("draft.isEmpty() || existing.isEmpty() || draft==existing", source)
        self.assertIn("verifyBackupRows(db, imported, restored", source)

    def test_chunk_sql_roundtrips_multibyte_nul_null_and_all_four_raw_fields(self):
        source = READER.read_text()
        sql = re.search(r'"(SELECT substr\(CAST\(\$column AS BLOB\),\?,65536\) FROM \$table WHERE \$key=\?)"', source).group(1)
        with sqlite3.connect(":memory:") as db:
            db.execute("CREATE TABLE messages(id TEXT PRIMARY KEY,blocks TEXT,protocol TEXT,history_segments TEXT,error TEXT)")
            large = "head\0" + "数🙂" * 340_000 + "\0tail"
            values = ("[ " + large + " ]", " {" + large + "} ", "[" + large + "]", None)
            db.execute("INSERT INTO messages VALUES(?,?,?,?,?)", ("m", *values))
            for column, expected in zip(("blocks", "protocol", "history_segments", "error"), values):
                length = db.execute(f"SELECT length(CAST({column} AS BLOB)) FROM messages WHERE id=?", ("m",)).fetchone()[0]
                if expected is None:
                    self.assertIsNone(length)
                    continue
                self.assertGreater(length, 2 * 1024 * 1024)
                query = sql.replace("$column", column).replace("$table", "messages").replace("$key", "id")
                chunks = [db.execute(query, (str(start), "m")).fetchone()[0] for start in range(1, length + 1, 65536)]
                self.assertTrue(all(0 < len(part) <= 65536 for part in chunks))
                raw = b"".join(chunks)
                self.assertEqual(length, len(raw))
                self.assertEqual(expected, raw.decode("utf-8", errors="strict"))

    def test_reader_rejects_over_budget_and_invalid_utf8_without_fallback(self):
        source = READER.read_text()
        self.assertIn("require(length in 0..maxBytes)", source)
        self.assertIn("if (consumed == length) return -1", source)
        self.assertIn("check(part.isNotEmpty() && part.size.toLong() <= length - consumed)", source)
        self.assertIn("java.io.InputStreamReader(source, decoder)", source)
        self.assertNotIn("ByteArrayOutputStream", source)
        self.assertIn("CodingErrorAction.REPORT", source)
        self.assertNotIn("takeLast", source)
        self.assertNotIn("getOrDefault", source)
        with self.assertRaises(UnicodeDecodeError):
            b"\x80".decode("utf-8", errors="strict")

    def test_large_android_regressions_remain_discoverable(self):
        source = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/storage/LargeConversationStorageTest.kt").read_text()
        for case in ("testLargeTerminalReceiptIsIdempotentButDifferentLateReceiptStillRejects",
                     "testLargeBlocksAndHistorySegmentsRoundTripThroughBothTextExports",
                     "testLargeConversationAndComposerDraftsExportAndImportWithoutClipping",
                     "testUnrelatedLargeComposerSurvivesSelectedImportAndConflictingImportRollsBack",
                     "testDuplicateReceiptPreservesRawJsonAndNullVersusEmptyErrorContract",
                     "testLargeRawProtocolKeepsOriginalBytesAcrossDuplicateSaveAndBackup",
                     "testInvalidUtf8AndChunkBudgetRejectInsteadOfReplacingOrClipping",
                     "testBackupKeepsOriginalSixteenMiBBudget"):
            self.assertIn("fun " + case + "()", source)

    def test_next_request_regression_uses_production_send_and_complete_wire_comparison(self):
        source = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ControllerLongHistoryRequestTest.kt").read_text()
        for required in ("fun testResponsesNextRequestRetainsLargeReopenedHistory()",
                         "fun testMessagesNextRequestRetainsLargeReopenedHistory()",
                         "db.close(); db = ConversationDatabase(app)",
                         "assertEquals(50, db.messages(owner.id).size)",
                         "assertEquals(64, prepared.messages.size)",
                         "large.codePointCount(0, large.length) > 1_000_000",
                         "c.sendText(reopenedOwner, 1, 1, pending)",
                         "connection.sent.toByteArray()", "normalizedHistory(protocol, request) == expected",
                         "sink(ModelEvent.ProtocolOutput(listOf(messagesFirst, messagesResult, messagesLast)))"):
            self.assertIn(required, source)
        self.assertNotIn("contextPreparationFactory =", source)
        self.assertNotIn("openConnection(", source)
        self.assertIn('titleModelFactory = { error("unexpected title model request") }', source)
