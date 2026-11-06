package app.daiyu.android.storage

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SQLiteTextTest {
    @Test fun draftCompareAndSetReadsRemainBoundedAndAllowlisted() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE composer (id TEXT PRIMARY KEY, draft TEXT)")
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, draft TEXT, title TEXT)")
            val draft = "草稿".repeat(40000)
            db.execSQL("INSERT INTO composer VALUES (?,?)", arrayOf("1", draft))
            db.execSQL("INSERT INTO conversations VALUES (?,?,?)", arrayOf("c", draft, "private"))
            assertEquals(draft, SQLiteText.read(db, "composer", "draft", "id", "1"))
            assertEquals(draft, SQLiteText.read(db, "conversations", "draft", "id", "c"))
            for (read in listOf<() -> String?>(
                { SQLiteText.read(db, "conversations", "title", "id", "c") },
                { SQLiteText.read(db, "composer", "draft", "draft", "1") },
                { SQLiteText.read(db, "composer", "draft", "id", "1", maxBytes = 1) },
            )) {
                assertTrue(runCatching { read() }.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }
    @Test fun multiMiBReadUsesOneSqlQueryPerChunkAndKeepsEveryByte() {
        var queries = 0
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            queries++
            SQLiteCursor(driver, editTable, query)
        }
        SQLiteDatabase.create(factory).use { db ->
            db.execSQL("CREATE TABLE composer (id TEXT PRIMARY KEY, draft TEXT)")
            val draft = "a".repeat(SQLiteText.CHUNK_BYTES - 1) + "🙂数\u0000" + "数🙂".repeat(340_000) + " \n"
            val bytes = draft.toByteArray(Charsets.UTF_8)
            db.execSQL("INSERT INTO composer VALUES (?,?)", arrayOf("1", draft))
            queries = 0
            val actual = SQLiteText.read(db, "composer", "draft", "id", "1", maxBytes = bytes.size.toLong())
            assertEquals(draft, actual)
            assertArrayEquals(bytes, actual!!.toByteArray(Charsets.UTF_8))
            assertEquals((bytes.size + SQLiteText.CHUNK_BYTES - 1) / SQLiteText.CHUNK_BYTES, queries)
            assertEquals(4, queries)
            assertTrue("queries=$queries", queries * 8 <= (bytes.size + 65535) / 65536)
            queries = 0
            assertTrue(runCatching {
                SQLiteText.read(db, "composer", "draft", "id", "1", maxBytes = bytes.size - 1L)
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, queries)
        }
    }

    @Test fun streamedUtf8KeepsChunkBoundariesNullsAndStrictEndOfInput() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE composer (id TEXT PRIMARY KEY, draft TEXT)")
            val draft = "a".repeat(65535) + "数🙂\u0000" + "b".repeat(65533) + "🙂数"
            db.execSQL("INSERT INTO composer VALUES (?,?)", arrayOf("1", draft))
            assertEquals(draft, SQLiteText.read(db, "composer", "draft", "id", "1"))
            for (chunk in listOf(65536, SQLiteText.CHUNK_BYTES)) for (prefix in chunk - 3..chunk + 1) {
                val boundary = "a".repeat(prefix) + "🙂数\u0000末"
                db.execSQL("UPDATE composer SET draft=? WHERE id='1'", arrayOf(boundary))
                val bytes = boundary.toByteArray(Charsets.UTF_8).size.toLong()
                assertEquals(boundary, SQLiteText.read(db, "composer", "draft", "id", "1", maxBytes = bytes))
                assertTrue(runCatching { SQLiteText.read(db, "composer", "draft", "id", "1", maxBytes = bytes - 1) }
                    .exceptionOrNull() is IllegalArgumentException)
            }
            for (prefix in listOf(0, 65535, SQLiteText.CHUNK_BYTES - 1, SQLiteText.CHUNK_BYTES)) {
                for (invalid in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xe4.toByte(), 0xb8.toByte()),
                    byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x99.toByte()))) {
                    db.execSQL("UPDATE composer SET draft=CAST(? AS TEXT) WHERE id='1'",
                        arrayOf(ByteArray(prefix) { 0x61 } + invalid))
                    assertTrue(runCatching { SQLiteText.read(db, "composer", "draft", "id", "1") }
                        .exceptionOrNull() is java.nio.charset.CharacterCodingException)
                }
            }
            db.execSQL("UPDATE composer SET draft='' WHERE id='1'")
            assertEquals("", SQLiteText.read(db, "composer", "draft", "id", "1"))
            db.execSQL("UPDATE composer SET draft=NULL WHERE id='1'")
            assertNull(SQLiteText.read(db, "composer", "draft", "id", "1"))
            val signal = android.os.CancellationSignal().apply { cancel() }
            assertTrue(runCatching { SQLiteText.read(db, "composer", "draft", "id", "1", cancellationSignal = signal) }
                .exceptionOrNull() is android.os.OperationCanceledException)
        }
    }

}
