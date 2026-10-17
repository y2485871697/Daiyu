package app.daiyu.android

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.daiyu.android.storage.SQLiteText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real framework SQLite cancellation, including a deterministic cancellation between chunks. */
@Suppress("DEPRECATION")
class SearchSQLiteCancellationTest : android.test.AndroidTestCase() {
    fun testCancellationBetween64KiBChunksClosesEveryCursorAndLeavesConnectionUsable() {
        val signal = CancellationSignal()
        val cursors = mutableListOf<SQLiteCursor>()
        var queryCount = 0
        val factory = SQLiteDatabase.CursorFactory { _, driver, table, query ->
            SQLiteCursor(driver, table, query).also { cursor ->
                cursors.add(cursor)
                queryCount++
                // length SELECT, first chunk SELECT, then second chunk SELECT.
                if (queryCount == 3) signal.cancel()
            }
        }
        SQLiteDatabase.create(factory).use { db ->
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, blocks TEXT)")
            val original = "中文🙂".repeat(40000)
            db.execSQL("INSERT INTO messages(id,blocks) VALUES(?,?)", arrayOf("owner", original))
            try {
                SQLiteText.read(db, "messages", "blocks", "id", "owner", cancellationSignal = signal)
                fail("chunk reader ignored cancellation")
            } catch (_: OperationCanceledException) { }
            assertEquals(3, queryCount)
            assertTrue(cursors.all { it.isClosed })
            assertFalse(db.inTransaction())
            assertEquals(original, SQLiteText.read(db, "messages", "blocks", "id", "owner",
                cancellationSignal = CancellationSignal()))
            assertTrue(cursors.all { it.isClosed })
        }
    }

    fun testSignalInterruptsExecutingNativeRawQuery() {
        SQLiteDatabase.create(null).use { db ->
            val signal = CancellationSignal()
            val entered = CountDownLatch(1)
            val outcome = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    db.rawQuery(
                        "WITH RECURSIVE numbers(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM numbers WHERE x<1000000000) SELECT sum(x) FROM numbers",
                        null, signal,
                    ).use { cursor ->
                        entered.countDown()
                        cursor.moveToFirst()
                        outcome.set(AssertionError("native query finished without cancellation"))
                    }
                } catch (failure: Throwable) { outcome.set(failure) }
            }
            worker.start()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // The billion-row aggregate cannot finish in this interval; cancellation must
                // reach SQLite while moveToFirst is executing, not merely before read starts.
                Thread.sleep(50)
                signal.cancel()
                worker.join(5000)
                assertFalse("native query failed to stop", worker.isAlive)
                assertTrue(outcome.get() is OperationCanceledException)
                db.rawQuery("SELECT 1", null).use { assertTrue(it.moveToFirst()) }
            } finally {
                signal.cancel()
                worker.join(5000)
            }
        }
    }
}
