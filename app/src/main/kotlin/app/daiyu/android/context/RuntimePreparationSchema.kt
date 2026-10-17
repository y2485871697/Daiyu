package app.daiyu.android.context

import android.database.sqlite.SQLiteDatabase

/** Called ONLY by the unique ConversationDatabase helper in onCreate / v15 -> v16 upgrade.
 * Not a parallel usage ledger: a row is the runtime authority before a real message exists.
 * The first real checkpoint transfers its snapshot and clears has_usage in the SAME transaction.
 * Terminal preparations retain usage even if the parent send was never accepted. */
internal object RuntimePreparationSchema {
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS runtime_preparations(" +
            "message_id TEXT PRIMARY KEY NOT NULL," +
            "conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE," +
            "run_id TEXT NOT NULL,owner TEXT NOT NULL,process_owner TEXT NOT NULL," +
            "revision INTEGER NOT NULL,state TEXT NOT NULL," +
            "request_id TEXT NOT NULL,request_owner TEXT NOT NULL,attempt_id TEXT NOT NULL," +
            "has_usage INTEGER NOT NULL DEFAULT 0," +
            "input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS runtime_preparations_conversation ON runtime_preparations(conversation_id,state)")
        val childColumns = db.rawQuery("PRAGMA table_info(child_request_usage)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        check(childColumns.isNotEmpty()) { "install_child_usage_schema_before_preparations" }
        if ("usage_source" !in childColumns)
            db.execSQL("ALTER TABLE child_request_usage ADD COLUMN usage_source TEXT NOT NULL DEFAULT 'MAIN'")
    }
}
