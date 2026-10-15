package app.daiyu.android.question

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.daiyu.android.domain.question.*

/** Separate app-private database; no conversation/shared schema changes or external files. */
class PrivateQuestionStore(context: Context) : SQLiteOpenHelper(context, "questions.db", null, 1), QuestionStore, java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE questions (call_id TEXT PRIMARY KEY NOT NULL, request TEXT NOT NULL, answer TEXT, interrupted INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw IllegalStateException("Unsupported question store version") }
    override fun get(callId: String): QuestionRecord? = readableDatabase.query("questions", null, "call_id=?", arrayOf(callId), null, null, null).use { if (it.moveToFirst()) decode(it) else null }
    override fun records(): List<QuestionRecord> = readableDatabase.query("questions", null, null, null, null, null, "rowid ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(decode(cursor)) }
    }
    private fun decode(cursor: Cursor): QuestionRecord = QuestionRecord(
        cursor.getString(cursor.getColumnIndexOrThrow("call_id")),
        AskUserContract.parse(cursor.getString(cursor.getColumnIndexOrThrow("request"))),
        cursor.getColumnIndexOrThrow("answer").let { if (cursor.isNull(it)) null else decodeAnswer(cursor.getString(it)) },
        cursor.getInt(cursor.getColumnIndexOrThrow("interrupted")) != 0
    )
    override fun create(record: QuestionRecord): Boolean {
        record.question.validate()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Database-level serialization prevents concurrent pending questions within an owner.
            val prefix = ownerPrefix(record.callId)
            // Compare UTF-8 bytes on both sides: Kotlin lengths count UTF-16 units, while
            // SQLite TEXT substr counts code points and stops at NUL. BLOB handles both.
            db.rawQuery("SELECT 1 FROM questions WHERE answer IS NULL AND interrupted=0 AND substr(CAST(call_id AS BLOB),1,length(CAST(? AS BLOB)))=CAST(? AS BLOB) LIMIT 1", arrayOf(prefix, prefix)).use { if (it.moveToFirst()) return false }
            val values = ContentValues().apply { put("call_id", record.callId); put("request", record.question.json().toString()) }
            val inserted = db.insertWithOnConflict("questions", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            db.setTransactionSuccessful()
            return inserted
        } finally { db.endTransaction() }
    }
    override fun answer(callId: String, answer: QuestionAnswer): Boolean = writableDatabase.update("questions",
        ContentValues().apply { put("answer", answer.json().toString()) },
        "call_id=? AND answer IS NULL AND interrupted=0", arrayOf(callId)) == 1
    override fun interruptPending() = interruptPending("")
    override fun interruptPending(ownerPrefix: String) {
        writableDatabase.update("questions", ContentValues().apply { put("interrupted", 1) },
            "answer IS NULL AND interrupted=0 AND substr(CAST(call_id AS BLOB),1,length(CAST(? AS BLOB)))=CAST(? AS BLOB)", arrayOf(ownerPrefix, ownerPrefix))
    }
    private fun ownerPrefix(key: String): String {
        if (!key.startsWith("q1:")) return ""
        var at = 3
        repeat(3) {
            val colon = key.indexOf(':', at)
            require(colon > at)
            val length = key.substring(at, colon).toInt()
            require(length in 1..256 && colon + 1 + length <= key.length)
            at = colon + 1 + length
        }
        return key.substring(0, at)
    }
}
