package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class SQLiteTextReadDatabaseTest : InstrumentationTestCase() {
    private fun <T> withDatabase(factory: SQLiteDatabase.CursorFactory? = null,
        block: (SQLiteDatabase) -> T): T = SQLiteDatabase.create(factory).use { db ->
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
            "a".repeat(SQLiteText.CHUNK_BYTES), "a".repeat(SQLiteText.CHUNK_BYTES - 1) + "🙂数\u0000",
            "数🙂".repeat(340_000))) {
            put(db, text)
            assertEquals(text, read(db))
        }
    }

    fun testMultiMiBReadUsesOneSqlQueryPerChunkAndKeepsEveryByte() {
        var queries = 0
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            queries++
            SQLiteCursor(driver, editTable, query)
        }
        withDatabase(factory) { db ->
            val text = "a".repeat(SQLiteText.CHUNK_BYTES - 1) + "🙂数\u0000" + "数🙂".repeat(340_000) + " \n"
            val bytes = text.toByteArray(Charsets.UTF_8)
            put(db, text)
            queries = 0
            val actual = read(db, bytes.size.toLong())
            assertEquals(text, actual)
            assertTrue(bytes.contentEquals(actual!!.toByteArray(Charsets.UTF_8)))
            assertEquals((bytes.size + SQLiteText.CHUNK_BYTES - 1) / SQLiteText.CHUNK_BYTES, queries)
            assertEquals(4, queries)
            assertTrue(queries * 8 <= (bytes.size + 65535) / 65536)
            queries = 0
            assertTrue(runCatching { read(db, bytes.size - 1L) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, queries)
        }
    }

    fun testCancellationDuringContinuationDoesNotReturnPartialText() {
        val cancellation = CancellationSignal()
        var queries = 0
        var cancelDuringRead = false
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            if (++queries == 2 && cancelDuringRead) cancellation.cancel()
            SQLiteCursor(driver, editTable, query)
        }
        withDatabase(factory) { db ->
            put(db, "a".repeat(SQLiteText.CHUNK_BYTES + 1))
            queries = 0
            cancelDuringRead = true
            assertTrue(runCatching { read(db, cancellation = cancellation) }.exceptionOrNull() is OperationCanceledException)
            assertEquals(2, queries)
        }
    }

    fun testMissingOverBudgetAndInvalidUtf8RemainRejected() = withDatabase { db ->
        assertTrue(runCatching { read(db) }.exceptionOrNull() is IllegalStateException)
        put(db, "数🙂")
        assertEquals("数🙂", read(db, 7))
        assertTrue(runCatching { read(db, 6) }.exceptionOrNull() is IllegalArgumentException)
        for (size in listOf(0L, 64L * 1024 * 1024 + 1)) {
            assertTrue(runCatching { read(db, size) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (prefix in listOf(0, 65535, SQLiteText.CHUNK_BYTES - 1, SQLiteText.CHUNK_BYTES)) {
            val invalid = ByteArray(prefix) { 0x61 } + byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x99.toByte())
            db.execSQL("UPDATE messages SET blocks=CAST(? AS TEXT) WHERE id='m'", arrayOf(invalid))
            assertTrue(runCatching { read(db) }.exceptionOrNull() is java.nio.charset.CharacterCodingException)
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
