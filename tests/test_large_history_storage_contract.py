"""Bounded SQL/source contracts; Android CursorWindow coverage lives in instrumentation."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATABASE = ROOT / "app/src/main/kotlin/app/daiyu/android/storage/ConversationDatabase.kt"
READER = ROOT / "app/src/main/kotlin/app/daiyu/android/storage/SQLiteText.kt"


class LargeHistoryStorageContractTest(unittest.TestCase):
    def reader_sql(self):
        source = READER.read_text()
        constant = re.search(r"^    internal const val CHUNK_BYTES = (\d+) \* (\d+)$", source, re.MULTILINE)
        self.assertIsNotNone(constant, "extract the production chunk size, not a test-only substitute")
        chunk_bytes = int(constant.group(1)) * int(constant.group(2))
        self.assertEqual(1024 * 1024, chunk_bytes)
        self.assertLess(chunk_bytes, 2 * 1024 * 1024)
        queries = re.findall(r'db\.rawQuery\("([^"\n]+)"', source)
        self.assertEqual([
            "SELECT length(CAST($column AS BLOB)),substr(CAST($column AS BLOB),1,$CHUNK_BYTES) FROM $table WHERE $key=?",
            "SELECT substr(CAST($column AS BLOB),?,$CHUNK_BYTES) FROM $table WHERE $key=?",
        ], queries)
        initial, continuation = (query.replace("$CHUNK_BYTES", str(chunk_bytes)) for query in queries)
        return chunk_bytes, initial, continuation

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
        chunk_bytes, initial_sql, continuation_sql = self.reader_sql()
        with sqlite3.connect(":memory:") as db:
            db.execute("CREATE TABLE messages(id TEXT PRIMARY KEY,blocks TEXT,protocol TEXT,history_segments TEXT,error TEXT)")
            large = "head\0" + "数🙂" * 340_000 + "\0tail"
            values = ("[ " + large + " ]", " {" + large + "} ", "[" + large + "]", None)
            db.execute("INSERT INTO messages VALUES(?,?,?,?,?)", ("m", *values))
            statements = []
            db.set_trace_callback(statements.append)
            for column, expected in zip(("blocks", "protocol", "history_segments", "error"), values):
                initial = initial_sql.replace("$column", column).replace("$table", "messages").replace("$key", "id")
                continuation = continuation_sql.replace("$column", column).replace("$table", "messages").replace("$key", "id")
                before = len(statements)
                length, first = db.execute(initial, ("m",)).fetchone()
                if expected is None:
                    self.assertIsNone(length)
                    self.assertIsNone(first)
                    self.assertEqual(1, len(statements) - before)
                    continue
                self.assertGreater(length, 2 * 1024 * 1024)
                self.assertEqual(chunk_bytes, len(first))
                chunks = [first]
                consumed = len(first)
                offsets = []
                while consumed < length:
                    offsets.append(consumed)
                    part = db.execute(continuation, (str(consumed + 1), "m")).fetchone()[0]
                    self.assertEqual(min(chunk_bytes, length - consumed), len(part))
                    chunks.append(part)
                    consumed += len(part)
                self.assertEqual(list(range(chunk_bytes, length, chunk_bytes)), offsets)
                raw = b"".join(chunks)
                self.assertEqual(length, len(raw))
                self.assertEqual(expected.encode("utf-8"), raw)
                self.assertEqual(expected, raw.decode("utf-8", errors="strict"))
                reads = len(statements) - before
                self.assertEqual((length + chunk_bytes - 1) // chunk_bytes, reads)
                self.assertEqual(3, reads)
                legacy_reads = (length + 65535) // 65536
                self.assertEqual(37, legacy_reads)
                self.assertLessEqual(reads * 8, legacy_reads)
            self.assertEqual(10, len(statements))
            self.assertTrue(all(statement.startswith("SELECT ") for statement in statements))

    def test_reader_rejects_over_budget_and_invalid_utf8_without_fallback(self):
        source = READER.read_text()
        self.assertIn("maxBytes: Long = 64L * 1024 * 1024", source)
        self.assertIn("require(maxBytes in 1..(64L * 1024 * 1024))", source)
        self.assertIn("require(length in 0..maxBytes)", source)
        self.assertIn("check(first.size.toLong() == minOf(length, CHUNK_BYTES.toLong()))", source)
        self.assertIn("if (length <= CHUNK_BYTES)", source)
        self.assertIn("if (consumed == length) return -1", source)
        self.assertIn("check(part.size.toLong() == minOf(length - consumed, CHUNK_BYTES.toLong()))", source)
        self.assertIn("cancellationSignal?.throwIfCanceled()", source)
        self.assertIn("checkCancelled()", source)
        self.assertNotIn("65536", source)
        self.assertNotIn("CursorWindow(", source)
        self.assertIn("java.io.InputStreamReader(source, decoder)", source)
        self.assertNotIn("ByteArrayOutputStream", source)
        self.assertIn("CodingErrorAction.REPORT", source)
        self.assertNotIn("takeLast", source)
        self.assertNotIn("getOrDefault", source)
        with self.assertRaises(UnicodeDecodeError):
            b"\x80".decode("utf-8", errors="strict")

    def test_sqlite_text_chunk_count_and_boundary_regressions_remain_wired_to_the_reader(self):
        tests = (
            ("app/src/test/kotlin/app/daiyu/android/storage/SQLiteTextChunksTest.kt", (
                "multiMiBValuesRoundTripAllBytesWithOneLoadPerContinuationChunk",
                "largeValuesNeverReadTheFirstChunkTwiceAndKeepCrossChunkUnicode",
                "shortAndChunkedInputsRejectMalformedUtf8",
                "truncatedFirstAndLaterChunksCannotReturnPartialText",
                "cancellationIsCheckedBeforeDecodeDuringStreamingAndBeforeReturn")),
            ("app/src/test/kotlin/app/daiyu/android/storage/SQLiteTextTest.kt", (
                "multiMiBReadUsesOneSqlQueryPerChunkAndKeepsEveryByte",
                "streamedUtf8KeepsChunkBoundariesNullsAndStrictEndOfInput")),
            ("app/src/androidTest/kotlin/app/daiyu/android/storage/SQLiteTextReadDatabaseTest.kt", (
                "testMultiMiBReadUsesOneSqlQueryPerChunkAndKeepsEveryByte",
                "testCancellationDuringContinuationDoesNotReturnPartialText",
                "testMissingOverBudgetAndInvalidUtf8RemainRejected")),
        )
        for path, cases in tests:
            source = (ROOT / path).read_text()
            for case in cases:
                self.assertIn("fun " + case + "()", source)
            self.assertIn("SQLiteText.CHUNK_BYTES", source)
            self.assertIn("65536", source)
            if not path.endswith("SQLiteTextChunksTest.kt"):
                self.assertIn("SQLiteDatabase.CursorFactory", source)
                self.assertIn("SQLiteCursor(driver, editTable, query)", source)
                self.assertIn("SQLiteText.read(", source)
                self.assertIn("assertEquals(4, queries)", source)

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
