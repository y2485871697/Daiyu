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
}
