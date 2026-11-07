package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SQLiteVerifiedTextReuseTest {
    private fun columns(protocol: String, history: String = "[]") = StoredMessageColumns(
        "m", "c", "r", "assistant", "RUNNING", 1L, "[]", null, protocol, history)
    private fun database(): SQLiteDatabase = SQLiteDatabase.create(null).also {
        it.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,blocks TEXT,error TEXT,protocol TEXT,history_segments TEXT)")
        it.execSQL("INSERT INTO messages VALUES('m','[]',NULL,'[]','[]')")
    }

    @Test fun fullSqlByteProofReusesFieldsIndependentlyAndDetectsMiddleChanges() {
        database().use { db ->
            val text = "a".repeat(SQLiteText.CHUNK_BYTES + 5) + "数🙂\u0000end"
            val history = "history\u0000数🙂"
            db.execSQL("UPDATE messages SET protocol=?,history_segments=? WHERE id='m'", arrayOf(text, history))
            val reuse = SQLiteVerifiedTextReuse(minCacheCharacters = 0)
            val first = reuse.read(db, "m", "protocol")!!
            reuse.remember(columns(first, reuse.read(db, "m", "history_segments")!!))
            assertSame(first, reuse.read(db, "m", "protocol"))
            assertEquals(history, reuse.read(db, "m", "history_segments"))
            assertEquals(2L, reuse.hits)
            db.execSQL("UPDATE messages SET blocks='[ ]' WHERE id='m'")
            assertSame(first, reuse.read(db, "m", "protocol"))
            val changed = text.replaceRange(SQLiteText.CHUNK_BYTES, SQLiteText.CHUNK_BYTES + 1, "b")
            db.execSQL("UPDATE messages SET protocol=? WHERE id='m'", arrayOf(changed))
            assertEquals(changed, reuse.read(db, "m", "protocol"))
            assertEquals(history, reuse.read(db, "m", "history_segments"))
            db.execSQL("UPDATE messages SET protocol=CAST(X'80' AS TEXT) WHERE id='m'")
            assertTrue(runCatching { reuse.read(db, "m", "protocol") }.exceptionOrNull()
                is java.nio.charset.CharacterCodingException)
            db.execSQL("DELETE FROM messages WHERE id='m'")
            assertTrue(runCatching { reuse.read(db, "m", "history_segments") }.isFailure)
        }
    }

    @Test fun nullEmptyAndJsonSpellingAreDistinctAndBudgetClearFallsBack() {
        database().use { db ->
            val reuse = SQLiteVerifiedTextReuse(maxCharacters = 5, minCacheCharacters = 0)
            reuse.remember(columns("[]", "[]"))
            assertEquals(4L, reuse.retainedCharacters)
            assertEquals("[]", reuse.read(db, "m", "protocol"))
            for (value in listOf("[ ]", "", null)) {
                db.execSQL("UPDATE messages SET protocol=? WHERE id='m'", arrayOf(value))
                assertEquals(value, reuse.read(db, "m", "protocol"))
            }
            val hits = reuse.hits
            reuse.remember(columns("123456", "[]"))
            assertTrue(reuse.retainedCharacters <= 5)
            reuse.clear()
            assertEquals(0L, reuse.retainedCharacters)
            assertEquals("[]", reuse.read(db, "m", "history_segments"))
            assertEquals(hits, reuse.hits)
            assertThrows(IllegalArgumentException::class.java) { reuse.read(db, "m", "id") }
        }
    }
    @Test fun fieldsOverCombinedBudgetDoNotEvictOneAnotherEveryRead() {
        database().use { db ->
            db.execSQL("UPDATE messages SET protocol='1234',history_segments='123456' WHERE id='m'")
            val reuse = SQLiteVerifiedTextReuse(maxCharacters = 6, minCacheCharacters = 0)
            repeat(5) {
                reuse.remember(columns("1234", "123456"))
                assertEquals("1234", reuse.read(db, "m", "protocol"))
                assertEquals("123456", reuse.read(db, "m", "history_segments"))
                assertEquals(6L, reuse.retainedCharacters)
            }
            assertEquals(5L, reuse.hits)
        }
    }

    @Test fun smallSupplementFieldsCannotEvictLargeHistoryCandidates() {
        database().use { db ->
            val text = "a".repeat(4096)
            db.execSQL("UPDATE messages SET protocol=?,history_segments=? WHERE id='m'", arrayOf(text, text))
            val reuse = SQLiteVerifiedTextReuse()
            val first = reuse.read(db, "m", "protocol")!!
            reuse.remember(columns(first, first))
            repeat(20) { reuse.remember(columns("[]").copy(messageId = "supplement-$it")) }
            assertSame(first, reuse.read(db, "m", "protocol"))
            assertEquals(8192L, reuse.retainedCharacters)
        }
    }

}
