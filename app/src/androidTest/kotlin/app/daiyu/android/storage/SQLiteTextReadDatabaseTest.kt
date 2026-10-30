package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class SQLiteTextReadDatabaseTest : InstrumentationTestCase() {
    private fun <T> withDatabase(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.create(null).use { db ->
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,blocks TEXT)")
        block(db)
    }

    private fun put(db: SQLiteDatabase, text: String?) {
        val values = ContentValues().apply {
            put("id", "m")
            if (text == null) putNull("blocks") else put("blocks", text)
        }
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun read(db: SQLiteDatabase, maxBytes: Long = 64L * 1024 * 1024,
        cancellation: CancellationSignal? = null) = SQLiteText.read(db, "messages", "blocks", "id", "m", maxBytes, cancellation)

    fun testNullEmptySmallAndCrossChunkUtf8PreserveExactValue() = withDatabase { db ->
        for (text in listOf(null, "", "\u0000", "a".repeat(65536), "a".repeat(65535) + "🙂数\u0000",
            "数🙂".repeat(340_000))) {
            put(db, text)
            assertEquals(text, read(db))
        }
    }

    fun testMissingOverBudgetAndInvalidUtf8RemainRejected() = withDatabase { db ->
        assertTrue(runCatching { read(db) }.isFailure)
        put(db, "数🙂")
        assertEquals("数🙂", read(db, 7))
        assertTrue(runCatching { read(db, 6) }.exceptionOrNull() is IllegalArgumentException)
        for (size in listOf(0L, 64L * 1024 * 1024 + 1)) {
            assertTrue(runCatching { read(db, size) }.exceptionOrNull() is IllegalArgumentException)
        }
        db.execSQL("UPDATE messages SET blocks=CAST(X'FF' AS TEXT) WHERE id='m'")
        assertTrue(runCatching { read(db) }.exceptionOrNull() is java.nio.charset.CharacterCodingException)
    }

    fun testCancellationAndFieldWhitelistRemainEnforced() = withDatabase { db ->
        put(db, "value")
        val cancelled = CancellationSignal().apply { cancel() }
        assertTrue(runCatching { read(db, cancellation = cancelled) }.exceptionOrNull() is OperationCanceledException)
        assertTrue(runCatching { SQLiteText.read(db, "messages", "id", "id", "m") }
            .exceptionOrNull() is IllegalArgumentException)
    }
}
