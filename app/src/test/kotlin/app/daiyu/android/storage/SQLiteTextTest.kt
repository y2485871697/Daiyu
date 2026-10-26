package app.daiyu.android.storage

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
    @Test fun streamedUtf8KeepsChunkBoundariesNullsAndStrictEndOfInput() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE composer (id TEXT PRIMARY KEY, draft TEXT)")
            val draft = "a".repeat(65535) + "数🙂\u0000" + "b".repeat(65533) + "🙂数"
            db.execSQL("INSERT INTO composer VALUES (?,?)", arrayOf("1", draft))
            assertEquals(draft, SQLiteText.read(db, "composer", "draft", "id", "1"))
            for (prefix in 65533..65535) {
                val boundary = "a".repeat(prefix) + "🙂" + "末"
                db.execSQL("UPDATE composer SET draft=? WHERE id='1'", arrayOf(boundary))
                val bytes = boundary.toByteArray(Charsets.UTF_8).size.toLong()
                assertEquals(boundary, SQLiteText.read(db, "composer", "draft", "id", "1", maxBytes = bytes))
                assertTrue(runCatching { SQLiteText.read(db, "composer", "draft", "id", "1", maxBytes = bytes - 1) }
                    .exceptionOrNull() is IllegalArgumentException)
            }
            for (hex in listOf("80", "E4B8", "F09F99")) {
                db.execSQL("UPDATE composer SET draft=CAST(X'$hex' AS TEXT) WHERE id='1'")
                assertTrue(runCatching { SQLiteText.read(db, "composer", "draft", "id", "1") }
                    .exceptionOrNull() is java.nio.charset.CharacterCodingException)
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
