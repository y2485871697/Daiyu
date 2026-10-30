"""Hot-path wiring and bounded host SQLite proof; Android integration is separate."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class CheckpointEncodingHotpathContract(unittest.TestCase):
    def test_archive_builder_is_shared_but_only_checkpoint_avoids_the_wire_roundtrip(self):
        archive = (APP / 'context/ContextStorage.kt').read_text()
        codec = (APP / 'storage/RunCheckpointCodec.kt').read_text()
        self.assertIn('fun encode(messages: List<Message>): ByteArray = archiveBytes(buildArray(messages))', archive)
        self.assertIn('internal fun checkpointArray(messages: List<Message>): JSONArray', archive)
        self.assertIn('if (hasUnpairedSurrogate(value))', archive)
        self.assertIn('JSONArray(String(archiveBytes(value), Charsets.UTF_8)) else value', archive)
        self.assertIn('check(MAX_BYTES.toLong() <= ArchiveCodec.MAX_ARCHIVE_BYTES)', codec)
        self.assertIn('fun messages(rows: List<Message>) = ArchiveCodec.checkpointArray(rows)', codec)
        self.assertNotIn('JSONArray(String(ArchiveCodec.encode(rows)', codec)
        self.assertIn('checkEncodedSize(it.toByteArray(Charsets.UTF_8).size, value.message.status == RunStatus.RUNNING)', codec)
        for gate in ['MessageHistory.validate(messages)', 'validateMessage(message, identities)',
                     'MessageHistoryCodec.validate(message)', 'MessageTimeCodec.write(item, message.createdAt)']:
            self.assertIn(gate, archive)
        projection = codec.split('fun projectedHistory(rows: List<Message>)', 1)[1].split('val version', 1)[0]
        self.assertIn('messages(listOf(row)).getJSONObject(0)', projection)
        self.assertNotIn('messages(rows)', projection)

    def test_text_reader_reuses_the_first_chunk_and_keeps_strict_decoder(self):
        reader = (APP / 'storage/SQLiteText.kt').read_text()
        self.assertIn('SELECT length(CAST($column AS BLOB)),substr(CAST($column AS BLOB),1,65536)', reader)
        self.assertIn('require(length in 0..maxBytes)', reader)
        self.assertIn('first.size.toLong() == minOf(length, 65536L)', reader)
        self.assertIn('private var consumed = 0L', reader)
        self.assertIn('private var part = first', reader)
        self.assertIn('part = load(consumed)', reader)
        self.assertIn('(consumed + 1).toString()', reader)
        self.assertIn('if (length <= 65536) return decoder.decode(java.nio.ByteBuffer.wrap(first)).toString()', reader)
        self.assertIn('CodingErrorAction.REPORT', reader)
        self.assertNotIn('getString(', reader)
        self.assertIn('java.io.InputStreamReader(source, decoder)', reader)
        self.assertIn('cancellationSignal?.throwIfCanceled()', reader)
        self.assertIn('checkCancelled()', reader)

    def test_actual_initial_query_is_bounded_and_preserves_raw_bytes(self):
        reader = (APP / 'storage/SQLiteText.kt').read_text()
        template = re.search(r'"(SELECT length\(CAST\(\$column AS BLOB\)\),substr\(CAST\(\$column AS BLOB\),1,65536\) FROM \$table WHERE \$key=\?)"', reader).group(1)
        sql = template.replace('$column', 'payload').replace('$table', 'messages').replace('$key', 'id')
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE messages(id TEXT PRIMARY KEY, payload TEXT)')
            for text in [None, '', 'a', 'a' * 65535, 'a' * 65536, 'a' * 65537, 'a' * 65535 + '🙂\x00中' * 30000]:
                db.execute('DELETE FROM messages')
                db.execute('INSERT INTO messages VALUES(?,?)', ('m', text))
                length, first = db.execute(sql, ('m',)).fetchone()
                if text is None:
                    self.assertIsNone(length)
                    self.assertIsNone(first)
                else:
                    raw = text.encode('utf-8')
                    self.assertEqual(len(raw), length)
                    self.assertEqual(raw[:65536], b'' if length == 0 else first)
                    self.assertLessEqual(len(first or b''), 65536)
            self.assertIsNone(db.execute(sql, ('missing',)).fetchone())
            db.execute("UPDATE messages SET payload=CAST(X'FF' AS TEXT) WHERE id='m'")
            self.assertEqual((1, b'\xff'), db.execute(sql, ('m',)).fetchone())


if __name__ == '__main__':
    unittest.main()
