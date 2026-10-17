package app.daiyu.android

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.BackupHistoryRows
import org.json.JSONObject

@Suppress("DEPRECATION")
class SqlNullHistoryReceiptTest : InstrumentationTestCase() {
    fun testMixedNullBindingsRemainExactAndLiteralNullIsRejected() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE receipt(id TEXT PRIMARY KEY,a TEXT,b INTEGER,c TEXT,d INTEGER)")
            val row = JSONObject().put("id", "local").put("a", JSONObject.NULL).put("b", 0)
                .put("c", JSONObject.NULL).put("d", 9007199254740991L)
            BackupHistoryRows.insert(db, "receipt", row)
            BackupHistoryRows.verify(db, "receipt", row)
            db.execSQL("UPDATE receipt SET c='null' WHERE id='local'")
            assertTrue(runCatching { BackupHistoryRows.verify(db, "receipt", row) }.isFailure)
        } finally { db.close() }
    }
}
