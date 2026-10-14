package app.daiyu.android.integration.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.daiyu.android.domain.tools.LedgerEntry
import app.daiyu.android.domain.tools.LedgerState
import app.daiyu.android.domain.tools.ToolLedgerStore
import java.io.Closeable

/** App-private, transactional ledger. Multiple instances/processes share SQLite's write serialization.
 * UNKNOWN rows deliberately survive reopen; no recovery routine automatically retries or deletes them. */
class SQLiteToolLedgerStore(
    private val helper: SQLiteOpenHelper,
    private val closeHelper: Boolean = false
) : ToolLedgerStore, Closeable {
    /** Shared app helper stays owned by the host. The context constructor owns its standalone helper. */
    constructor(context: Context, databaseName: String = "integration-tools.db") : this(standaloneHelper(context, databaseName), true)

    @Synchronized override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
        val db = helper.writableDatabase
        // A nested transaction can return before the outer commit, which must NOT permit execution.
        check(!db.inTransaction()) { "ledger_requires_top_level_transaction" }
        db.beginTransaction()
        try {
            createSchema(db)
            val entries = mutableListOf<LedgerEntry>()
            db.query("tool_ledger", null, null, null, null, null, "rowid ASC").use { cursor ->
                fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                while (cursor.moveToNext()) {
                    val effectIndex = cursor.getColumnIndexOrThrow("effect_key")
                    val errorFlag = cursor.getInt(cursor.getColumnIndexOrThrow("is_error"))
                    check(errorFlag == 0 || errorFlag == 1) { "invalid_ledger" }
                    entries.add(LedgerEntry(
                        text("session_id"), text("call_id"), text("task_id"), text("request_key"),
                        if (cursor.isNull(effectIndex)) null else cursor.getString(effectIndex),
                        text("owner"), LedgerState.valueOf(text("state")), text("payload"),
                        errorFlag == 1, text("code")
                    ))
                }
            }
            check(entries.all { it.sessionId.isNotBlank() && it.callId.isNotBlank() && it.taskId.isNotBlank() && it.owner.isNotBlank() }) { "invalid_ledger" }
            val original = entries.toList()
            val result = block(entries)
            if (entries != original) {
                db.delete("tool_ledger", null, null)
                for (entry in entries) {
                    val values = ContentValues().apply {
                        put("session_id", entry.sessionId); put("call_id", entry.callId); put("task_id", entry.taskId)
                        put("request_key", entry.requestKey); put("effect_key", entry.effectKey); put("owner", entry.owner)
                        put("state", entry.state.name); put("payload", entry.payload)
                        put("is_error", if (entry.isError) 1 else 0); put("code", entry.code)
                    }
                    db.insertOrThrow("tool_ledger", null, values)
                }
            }
            db.setTransactionSuccessful()
            return result
        } finally {
            // endTransaction commits BEFORE the caller may invoke an adapter; any failure propagates.
            db.endTransaction()
        }
    }
    @Synchronized override fun close() { if (closeHelper) helper.close() }

    companion object {
        const val TABLE_NAME = "tool_ledger"

        /** Additive only: callable from the host's onCreate/migration; never resets existing rows.
         * Also installed lazily in a top-level transaction for an injected existing helper. */
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS tool_ledger (
                session_id TEXT NOT NULL, call_id TEXT NOT NULL, task_id TEXT NOT NULL,
                request_key TEXT NOT NULL, effect_key TEXT, owner TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('UNKNOWN','CONFIRMED','NOT_EXECUTED')),
                payload TEXT NOT NULL, is_error INTEGER NOT NULL CHECK(is_error IN (0,1)), code TEXT NOT NULL,
                PRIMARY KEY(session_id, call_id)
            )""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS tool_ledger_effect ON tool_ledger(effect_key)")
        }

        private fun standaloneHelper(context: Context, name: String): SQLiteOpenHelper {
            require(name.matches(Regex("[a-zA-Z0-9_.-]+")) && name != "." && name != "..")
            return Helper(context.applicationContext ?: context, name)
        }
    }

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) = createSchema(db)
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // A future migration must preserve unknown protections; never fall back to DROP/CREATE.
            error("unsupported_ledger_version")
        }
    }
}
