package app.daiyu.android.platform

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONObject

/**
 * Private capture store, never a Catalog/model data source. Capture defaults on and legacy off migrates once;
 * later explicit choices, the original epoch and existing records are preserved. Android listener authorization remains
 * checked by CrossAppNotifications before recording or reading any content.
 */
internal class NotificationHistoryStore(private val database: ConversationDatabase) {
    private val db get() = database.writableDatabase

    init { migrateProductDefaults(db) }
    fun enabled(): Boolean = db.rawQuery("SELECT enabled FROM notification_capture WHERE id=1", null).use {
        it.moveToFirst() && it.getInt(0) == 1
    }
    fun setEnabled(enabled: Boolean, now: Long) {
        val db = db
        db.beginTransaction()
        try {
            // Every opt-in begins a NEW epoch; off always stops and erases captured content.
            db.delete("notification_records", null, null)
            db.execSQL("UPDATE notification_capture SET enabled=?,since_ms=? WHERE id=1", arrayOf(if (enabled) 1 else 0, if (enabled) now else 0L))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** System revocation erases content but is not an explicit user preference change. */
    fun clearForSystemRevocation() {
        val db = db
        db.beginTransaction()
        try {
            db.delete("notification_records", null, null)
            db.execSQL("UPDATE notification_capture SET since_ms=0 WHERE id=1")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Reconnection starts a new future-event epoch, never backfills pre-grant notifications. */
    fun resumeAfterSystemGrant(now: Long) {
        db.execSQL("UPDATE notification_capture SET since_ms=? WHERE id=1 AND enabled=1 AND since_ms=0", arrayOf(now))
        prune(now)
    }

    fun prune(now: Long) {
        val db = db
        db.delete("notification_records", "captured_ms<? OR captured_ms>?", arrayOf((now - RETENTION_MS).toString(), now.toString()))
        db.execSQL("DELETE FROM notification_records WHERE seq NOT IN (SELECT seq FROM notification_records ORDER BY seq DESC LIMIT $MAX_ROWS)")
        while (db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM notification_records", null).use { it.moveToFirst(); it.getLong(0) } > MAX_BYTES) {
            db.execSQL("DELETE FROM notification_records WHERE seq=(SELECT MIN(seq) FROM notification_records)")
        }
    }
    fun append(row: JSONObject, now: Long) {
        if (!enabled()) return
        val payload = JSONObject(row.toString()).put("captured_at", now).toString()
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_ROW_BYTES) return
        val db = db
        db.beginTransaction()
        try {
            db.insertOrThrow("notification_records", null, ContentValues().apply {
                put("captured_ms", now); put("payload", payload); put("bytes", bytes)
            })
            prune(now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun since(): Long = db.rawQuery("SELECT since_ms FROM notification_capture WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }
    fun rows(now: Long, start: Long, end: Long): List<JSONObject> {
        prune(now)
        return db.rawQuery("SELECT payload FROM notification_records WHERE captured_ms>=? AND captured_ms<=? ORDER BY seq DESC LIMIT $MAX_ROWS",
            arrayOf(start.toString(), end.toString())).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    }
    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ROWS = 500
        const val MAX_BYTES = 512 * 1024
        const val MAX_ROW_BYTES = 4096
        fun installSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS notification_capture(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL DEFAULT 1,since_ms INTEGER NOT NULL DEFAULT 0)")
            migrateProductDefaults(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS notification_records(seq INTEGER PRIMARY KEY AUTOINCREMENT,captured_ms INTEGER NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL)")
        }
        private fun migrateProductDefaults(db: SQLiteDatabase) {
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS notification_capture_policy(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO notification_capture(id,enabled,since_ms) VALUES(1,1,0)")
                val migrated = db.rawQuery("SELECT version FROM notification_capture_policy WHERE id=1", null).use {
                    it.moveToFirst() && it.getInt(0) >= 1
                }
                if (!migrated) {
                    db.execSQL("UPDATE notification_capture SET since_ms=CASE WHEN enabled=0 THEN 0 ELSE since_ms END,enabled=1 WHERE id=1")
                    db.execSQL("INSERT OR REPLACE INTO notification_capture_policy(id,version) VALUES(1,1)")
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
}
